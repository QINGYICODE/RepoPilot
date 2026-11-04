package io.github.qingyicode.repopilot.repository;

import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GitMirror {
    private final RepositorySettings settings;
    private final RepositoryNetwork network;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public GitMirror(RepositorySettings settings,RepositoryNetwork network) { this.settings = settings;this.network=network; }
    public GitMirror(RepositorySettings settings) {this(settings,new RepositoryNetwork(settings));}

    /** Read the existing bare mirror only; analysis never fetches or checks out code. */
    public boolean isAncestor(RepositoryRecord record, String ancestor, String descendant) {
        RepositoryPolicy.sha(ancestor); RepositoryPolicy.sha(descendant);
        Path root = settings.storageDirectory().toAbsolutePath().normalize();
        Path directory = root.resolve(record.getId() + ".git");
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) throw DomainException.invalid("UNSAFE_STORAGE_PATH");
        try (var repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setMustExist(true).build();
             var walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(ObjectId.fromString(ancestor)), walk.parseCommit(ObjectId.fromString(descendant)));
        } catch (IOException failure) {
            throw DomainException.conflict("COMMIT_RELATION_UNAVAILABLE");
        }
    }

    public <T> T withSnapshot(RepositoryRecord record, String sha, Function<Repository, T> action) {
        return withRemote(record, sha, "https://github.com/" + record.getOwner() + "/" + record.getName() + ".git", action);
    }

    // Local fixture transport is available only inside this package's tests.
    <T> T withRemote(RepositoryRecord record, String sha, String remote, Function<Repository, T> action) {
        RepositoryPolicy.sha(sha);
        var lock = locks.computeIfAbsent(record.getId(), ignored -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
            Path root = settings.storageDirectory().toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw DomainException.invalid("UNSAFE_STORAGE_PATH");
            Path directory = root.resolve(record.getId() + ".git");
            if (Files.isSymbolicLink(directory)) throw DomainException.invalid("UNSAFE_STORAGE_PATH");
            String ref = "refs/heads/" + record.getDefaultRef();
            if (!Repository.isValidRefName(ref)) throw DomainException.invalid("INVALID_ALLOWED_REF");
            if (!Files.exists(directory)) Files.createDirectory(directory);
            if (!Files.exists(directory.resolve("HEAD"))) {
                // Initialize before fetch: an interrupted first transfer can be retried in the same bare mirror.
                try (var initialized = Git.init().setBare(true).setDirectory(directory.toFile()).call()) {
                    initialized.getRepository().getConfig().setString("http", null, "followRedirects", "false");
                    initialized.getRepository().getConfig().save();
                }
            }
            try (var repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setMustExist(true).build()) {
                boolean fetched = false;
                int routeIndex = 0;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try (var git = new Git(repository)) {
                        var fetch = git.fetch().setRemote(remote).setRefSpecs(new RefSpec("+" + ref + ":" + ref))
                                .setTimeout(30).setProgressMonitor(CANCELLABLE)
                                .setRecurseSubmodules(org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
                                .setCheckFetchedObjects(true);
                        var route=network.routes().get(routeIndex);
                        fetch.setTransportConfigCallback(transport -> {
                            if (transport instanceof org.eclipse.jgit.transport.TransportHttp http) {
                                var delegate=new org.eclipse.jgit.transport.http.JDKHttpConnectionFactory();
                                http.setHttpConnectionFactory(new org.eclipse.jgit.transport.http.HttpConnectionFactory() {
                                    public org.eclipse.jgit.transport.http.HttpConnection create(java.net.URL url) throws IOException { return delegate.create(url,route.proxy()); }
                                    public org.eclipse.jgit.transport.http.HttpConnection create(java.net.URL url,java.net.Proxy ignored) throws IOException { return delegate.create(url,route.proxy()); }
                                });
                            }
                        });
                        if (remote.startsWith("https://github.com/") && !settings.githubToken().isBlank()) {
                            fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", settings.githubToken()));
                        }
                        fetch.call(); fetched = true; break;
                    } catch (org.eclipse.jgit.api.errors.GitAPIException failure) {
                        if (Thread.currentThread().isInterrupted()) throw DomainException.conflict("SYNC_INTERRUPTED");
                        String message=java.util.Objects.toString(failure.getMessage(),"").toLowerCase(java.util.Locale.ROOT);
                        if(message.contains("not authorized") || message.contains("authentication") || message.contains("401"))
                            throw new DomainException(HttpStatus.BAD_GATEWAY,"GIT_AUTH_FAILED");
                        if(message.contains("403") || message.contains("429") || message.contains("rate limit"))
                            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,"GIT_ACCESS_DENIED_OR_RATE_LIMITED");
                        if(message.contains("remote branch") && message.contains("not found"))
                            throw DomainException.conflict("GIT_DEFAULT_BRANCH_NOT_FOUND");
                        if (attempt == 2) throw new DomainException(HttpStatus.BAD_GATEWAY, "GIT_FETCH_FAILED");
                        if (networkFailure(failure)) routeIndex=Math.min(routeIndex+1,network.routes().size()-1);
                        Thread.sleep(200L * (attempt + 1));
                    }
                }
                if (!fetched) throw new DomainException(HttpStatus.BAD_GATEWAY, "GIT_FETCH_FAILED");
                ObjectId head = repository.resolve(ref);
                ObjectId selected = ObjectId.fromString(sha);
                if (head == null || !repository.getObjectDatabase().has(selected)) throw DomainException.invalid("UNKNOWN_COMMIT");
                try (var walk = new RevWalk(repository)) {
                    org.eclipse.jgit.revwalk.RevCommit commit;
                    try { commit=walk.parseCommit(selected); }
                    catch (org.eclipse.jgit.errors.IncorrectObjectTypeException | org.eclipse.jgit.errors.MissingObjectException invalid) {
                        throw DomainException.invalid("UNKNOWN_COMMIT");
                    }
                    if (!walk.isMergedInto(commit, walk.parseCommit(head))) {
                        throw DomainException.invalid("COMMIT_NOT_ON_ALLOWED_REF");
                    }
                }
                return action.apply(repository);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw DomainException.conflict("SYNC_INTERRUPTED");
        } catch (IOException | org.eclipse.jgit.api.errors.GitAPIException failure) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "GIT_STORAGE_FAILED");
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private static boolean networkFailure(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.UnknownHostException || cause instanceof javax.net.ssl.SSLException) return true;
        return false;
    }

    private static final ProgressMonitor CANCELLABLE = new ProgressMonitor() {
        public void start(int totalTasks) {} public void beginTask(String title, int totalWork) {}
        public void update(int completed) {} public void endTask() {}
        public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
        public void showDuration(boolean enabled) {}
    };
}
