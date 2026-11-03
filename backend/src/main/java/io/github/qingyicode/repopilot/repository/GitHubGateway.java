package io.github.qingyicode.repopilot.repository;

public interface GitHubGateway {
    RepositoryData.Metadata metadata(String owner, String name);
}
