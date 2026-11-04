package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import io.github.qingyicode.repopilot.indexing.IndexBuilder;
import io.github.qingyicode.repopilot.indexing.IndexStore;
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
    private final IndexBuilder indexer;
    private final IndexStore indices;
    private final RepositorySettings settings;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),task -> {
        var thread = new Thread(task,"repository-sync"); thread.setDaemon(true); return thread;
    });

    public SyncService(RepositoryStore repositories, RepositoryPolicy policy, GitHubGateway github, GitMirror mirrors,
                       IndexBuilder indexer, IndexStore indices, RepositorySettings settings) {
        this.repositories = repositories; this.policy = policy; this.github = github; this.mirrors = mirrors;
        this.indexer = indexer; this.indices = indices; this.settings = settings;
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
            var snapshot = repositories.prepare(record.getId(),job.targetSha());
            if (snapshot.status().equals("READY")) { repositories.finishExisting(job.id(),snapshot.id()); return; }
            var metadata = github.metadata(record.getOwner(),record.getName());
            if (record.getGithubId() == null || record.getGithubId() != metadata.githubId()) throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
            record=repositories.register(record.getOwner(),record.getName(),metadata);
            var selectedRecord=record;
            mirrors.withSnapshot(record,job.targetSha(),repository -> {
                repositories.indexing(job.id(),snapshot.id());
                var product = indexer.build(selectedRecord,job.targetSha(),repository);
                repositories.repository(selectedRecord.getId());
                indices.publish(snapshot,job.id(),product); return null;
            });
        } catch (DomainException failure) { repositories.fail(job.id(),failure.code()); }
        catch (Exception failure) {
            // Do not expose upstream HTTP errors, connection strings or credentials in task records.
            org.slf4j.LoggerFactory.getLogger(SyncService.class).warn("Sync {} failed ({})",job.id(),failure.getClass().getSimpleName());
            repositories.fail(job.id(),"INDEXING_FAILED");
        } finally { active.remove(job.id()); }
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
