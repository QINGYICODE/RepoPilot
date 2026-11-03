package io.github.qingyicode.repopilot.repository;

import java.util.List;
import java.util.Map;

public final class RepositoryData {
    private RepositoryData() {}
    public record Metadata(long githubId, String fullName, String defaultBranch, boolean privateRepository) {}
    public record SyncJob(String id, long repoId, String targetSha, Long snapshotId,
                          String status, int attempts, String errorCode, String updatedAt) {}
    public record Snapshot(long id, long repoId, String commitSha, String indexVersion, String status,
                           Map<String, Object> coverage, Map<String, Object> stats) {}
}
