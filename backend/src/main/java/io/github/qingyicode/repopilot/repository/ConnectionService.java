package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Management workflow only. None of these actions is registered as an Agent tool. */
@Service
public class ConnectionService {
    private final ConnectionStore connections;
    private final RepositoryStore repositories;
    private final RepositoryGrantStore grants;
    private final RepositoryPolicy policy;
    private final GitHubGateway github;
    private final SyncService sync;
    private final RepositorySettings settings;
    private final RepositoryNetwork network;
    private final boolean manual;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),Thread.ofPlatform().daemon().name("repository-connect-",0).factory());
    public ConnectionService(ConnectionStore connections,RepositoryStore repositories,RepositoryGrantStore grants,RepositoryPolicy policy,
            GitHubGateway github,SyncService sync,RepositorySettings settings,RepositoryNetwork network,
            @Value("${repopilot.allow-manual-repository-authorization:true}") boolean manual) {
        this.connections=connections;this.repositories=repositories;this.grants=grants;this.policy=policy;this.github=github;this.sync=sync;this.settings=settings;this.network=network;this.manual=manual;
    }
    public record AccessSettings(boolean manualAuthorizationEnabled,java.util.List<String> seededRepositories,boolean githubTokenConfigured,String networkMode) {}
    public AccessSettings accessSettings() {return new AccessSettings(manual,settings.allowedRepositories(),!settings.githubToken().isBlank(),network.mode());}
    public ConnectionJob connect(String address,boolean authorize,boolean autoImport) {
        var selected=RepositoryAddress.parse(address);
        if(!authorize || !manual) policy.allowedName(selected.owner(),selected.name());
        var job=connections.enqueue(selected,authorize&&manual,autoImport);dispatch();return connections.get(job.id());
    }
    public ConnectionJob get(String id) {return connections.get(id);}
    public ConnectionJob retry(String id) {
        var job=connections.get(id);
        if(job.status().equals("READY") && job.syncJobId()!=null && repositories.job(job.syncJobId()).status().equals("FAILED"))
            return connect(job.owner()+"/"+job.name(),job.authorizeAccess(),job.autoImport());
        if(!job.status().equals("FAILED")) return job;
        if(job.retryAt()!=null && Instant.parse(job.retryAt()).isAfter(Instant.now()))
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_RATE_LIMITED",(int)Duration.between(Instant.now(),Instant.parse(job.retryAt())).toSeconds()+1);
        return connect(job.owner()+"/"+job.name(),job.authorizeAccess(),job.autoImport());
    }
    public void revoke(long repoId) {
        var repo=repositories.repository(repoId);String name=RepositoryPolicy.fullName(repo.getOwner(),repo.getName());
        if(policy.seeded(name)) throw DomainException.conflict("REPOSITORY_AUTHORIZATION_MANAGED_BY_CONFIG");
        grants.revoke(name);
    }
    @Scheduled(fixedDelay=1000) public void scheduledDispatch() {if(settings.backgroundSyncEnabled()) dispatch();}
    public synchronized void dispatch() {
        for(var job:connections.queued()) {
            if(workers.isShutdown() || workers.getQueue().remainingCapacity()==0) break;
            if(!connections.claim(job.id())) continue;
            try {workers.execute(()->run(job));}
            catch(RejectedExecutionException full) {connections.fail(job.id(),"CONNECTION_QUEUE_FULL",null);}
        }
    }
    @Scheduled(fixedDelay=15000) public void maintain() {connections.maintain();}
    private void run(ConnectionJob job) {
        try {
            if(!job.authorizeAccess() || !manual) policy.allowedName(job.owner(),job.name());
            var metadata=github.metadata(job.owner(),job.name());
            if(metadata.privateRepository()) throw new DomainException(HttpStatus.FORBIDDEN,"GITHUB_PRIVATE_REPOSITORY_UNSUPPORTED");
            if(metadata.githubId()<=0 || !(job.owner()+"/"+job.name()).equalsIgnoreCase(metadata.fullName())) throw DomainException.conflict("GITHUB_IDENTITY_MISMATCH");
            var repo=job.authorizeAccess()?repositories.authorizeAndRegister(job.owner(),job.name(),metadata):repositories.register(job.owner(),job.name(),metadata);
            connections.registered(job.id(),repo.getId());
            String syncJob=null;
            if(job.autoImport()) {
                String sha=github.defaultHead(repo.getOwner(),repo.getName(),metadata.defaultBranch());
                syncJob=sync.enqueue(repo.getId(),sha).id();
            }
            connections.ready(job.id(),syncJob);
        } catch(DomainException failure) {connections.fail(job.id(),failure.code(),failure.retryAfterSeconds());}
        catch(RuntimeException failure) {connections.fail(job.id(),"CONNECTION_FAILED",null);}
    }
    @PreDestroy public void close() {connections.stop();workers.shutdownNow();}
}
