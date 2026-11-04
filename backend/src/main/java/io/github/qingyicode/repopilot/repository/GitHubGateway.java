package io.github.qingyicode.repopilot.repository;

public interface GitHubGateway {
    RepositoryData.Metadata metadata(String owner, String name);
    default String defaultHead(String owner,String name,String branch) {
        throw io.github.qingyicode.repopilot.common.DomainException.conflict("GITHUB_HEAD_UNAVAILABLE");
    }
}
