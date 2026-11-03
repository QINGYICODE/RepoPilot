package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SyncService {
    private final RepositoryStore repositories;
    private final RepositoryPolicy policy;
    private final GitHubGateway github;
    private final GitMirror mirrors;
    private final RepositorySettings settings;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),task -> {
        var thread = new Thread(task,"repository-sync"); thread.setDaemon(true); return thread;
    });

    public SyncService(RepositoryStore repositories, RepositoryPolicy policy, GitHubGateway github, GitMirror mirrors,
                       RepositorySettings settings) {
        this.repositories = repositories; this.policy = policy; this.github = github; this.mirrors = mirrors;
        this.settings = settings;
    }
    public RepositoryRecord register(String owner, String name) {
        String fullName = policy.allowedName(owner,name);
        String[] parts = fullName.split("/");
        var previous = repositories.find(parts[0],parts[1]);
        if (previous != null && previous.getGithubId() != null) return previous;
        return repositories.register(parts[0],parts[1],github.metadata(parts[0],parts[1]));
    }
    public RepositoryData.SyncJob enqueue(long repoId, String sha) {
        var job = repositories.enqueue(repoId,RepositoryPolicy.sha(sha));
        dispatch(); return repositories.job(job.id());
    }
    @Scheduled(fixedDelay=1000)
    public void scheduledDispatch() {
        if (settings.backgroundSyncEnabled()) dispatch();
    }
    public synchronized void dispatch() {
        for (var job : repositories.queued()) {
            try { repositories.repository(job.repoId()); } catch (DomainException notAllowed) { continue; }
            if (workers.isShutdown() || !repositories.claim(job.id(),settings.jobLeaseSeconds())) continue;
            active.add(job.id());
            try { workers.execute(() -> run(job)); }
            catch (RejectedExecutionException full) { active.remove(job.id()); repositories.release(job.id()); }
        }
    }
    @Scheduled(fixedDelay=15000)
    public void maintainLeases() {
        active.forEach(id -> repositories.heartbeat(id,settings.jobLeaseSeconds()));
        repositories.recover();
    }
    private void run(RepositoryData.SyncJob job) {
        try {
            var record = repositories.repository(job.repoId());
            var metadata = github.metadata(record.getOwner(),record.getName());
            if (record.getGithubId() == null || record.getGithubId() != metadata.githubId()) throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
            var snapshot = repositories.prepare(record.getId(),job.targetSha());
            if (snapshot.status().equals("READY")) { repositories.finishExisting(job.id(),snapshot.id()); return; }
            mirrors.withSnapshot(record,job.targetSha(),repository -> {
                repositories.indexing(job.id(),snapshot.id());
                var tree = inspect(repository,job.targetSha());
                repositories.publishMetadata(snapshot,job.id(),java.util.Map.of("level","PARTIAL","sourceScope","METADATA_ONLY",
                        "historyComplete",false,"warnings",java.util.List.of("CODE_INDEX_NOT_BUILT","HISTORY_INDEX_NOT_BUILT")),
                        java.util.Map.of("treeSha",tree.get("treeSha"),"fileCount",tree.get("fileCount"),"commitCount",0));
                return null;
            });
        } catch (DomainException failure) { repositories.fail(job.id(),failure.code()); }
        catch (Exception failure) {
            // Do not expose upstream HTTP errors, connection strings or credentials in task records.
            org.slf4j.LoggerFactory.getLogger(SyncService.class).warn("Sync {} failed ({})",job.id(),failure.getClass().getSimpleName());
            repositories.fail(job.id(),"INDEXING_FAILED");
        } finally { active.remove(job.id()); }
    }
    public java.util.Map<String,Object> tree(long repoId,long snapshotId) {
        var snapshot=repositories.snapshot(repoId,snapshotId);
        return mirrors.withSnapshot(repositories.repository(repoId),snapshot.commitSha(),repo->inspect(repo,snapshot.commitSha()));
    }
    private java.util.Map<String,Object> inspect(org.eclipse.jgit.lib.Repository repository,String sha) {
        try(var walk=new org.eclipse.jgit.revwalk.RevWalk(repository);var tree=new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
            var commit=walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(sha));
            tree.addTree(commit.getTree());tree.setRecursive(true);
            var entries=new java.util.ArrayList<java.util.Map<String,Object>>();
            boolean truncated=false;
            while(tree.next()) {
                if(entries.size()>=settings.maxFiles()){truncated=true;break;}
                entries.add(java.util.Map.of("path",tree.getPathString(),"objectId",tree.getObjectId(0).name(),"mode",tree.getFileMode(0).toString()));
            }
            return java.util.Map.of("sourceSha",sha,"treeSha",commit.getTree().name(),"fileCount",entries.size(),"entries",entries,"truncated",truncated);
        } catch(java.io.IOException failure){throw DomainException.conflict("GIT_TREE_READ_FAILED");}
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
