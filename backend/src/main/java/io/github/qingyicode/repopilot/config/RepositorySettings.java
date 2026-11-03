package io.github.qingyicode.repopilot.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("repopilot")
public record RepositorySettings(
        @DefaultValue(".local/repositories") Path storageDirectory,
        @DefaultValue("spring-petclinic/spring-petclinic-microservices") List<String> allowedRepositories,
        @DefaultValue("") String githubToken,
        @DefaultValue("5000") int maxFiles,
        @DefaultValue("262144") int maxFileBytes,
        @DefaultValue("2000") int maxHistoryCommits,
        @DefaultValue("40000") int maxEvidence,
        @DefaultValue("180") int jobLeaseSeconds,
        @DefaultValue("true") boolean backgroundSyncEnabled,
        @DefaultValue("") String networkProxy) {
    public static final String INDEX_VERSION = "d3-index";
    public static final String PROFILE_HASH = io.github.qingyicode.repopilot.common.Hashes.sha256("default-profile:no-external-resolution");
    public String profileHash() {
        return io.github.qingyicode.repopilot.common.Hashes.sha256(PROFILE_HASH + "|" + maxFiles + "|" + maxFileBytes + "|" + maxHistoryCommits + "|" + maxEvidence);
    }
    public RepositorySettings {
        if (maxFiles < 1 || maxFiles > 20000 || maxFileBytes < 1024 || maxFileBytes > 1048576
                || maxHistoryCommits < 1 || maxHistoryCommits > 10000 || maxEvidence < 100 || maxEvidence > 100000
                || jobLeaseSeconds < 30) throw new IllegalArgumentException("Invalid repository limits");
        allowedRepositories = List.copyOf(allowedRepositories);
        if (!networkProxy.isBlank()) proxyAddress(networkProxy);
    }
    public java.net.Proxy proxy() {
        return networkProxy.isBlank() ? java.net.Proxy.NO_PROXY : new java.net.Proxy(java.net.Proxy.Type.HTTP,proxyAddress(networkProxy));
    }
    private static java.net.InetSocketAddress proxyAddress(String address) {
        var uri=java.net.URI.create(address);
        if (!"http".equals(uri.getScheme()) || uri.getHost()==null || uri.getPort()<1 || uri.getUserInfo()!=null
                || uri.getQuery()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("REPOSITORY_PROXY must be http://host:port without credentials");
        }
        return new java.net.InetSocketAddress(uri.getHost(),uri.getPort());
    }
}
