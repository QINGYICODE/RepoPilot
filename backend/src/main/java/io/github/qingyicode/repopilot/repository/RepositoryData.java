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
    public record Chunk(int startLine, int endLine, String symbol, boolean truncated) {}
    public record Blob(String bodyHash, String body, List<Chunk> chunks, List<String> warnings) {}
    public record IndexedFile(String path, String blobSha, String bodyHash, String modulePath, String language, int lineCount) {}
    public record Evidence(String id, long repoId, String type, String sourceSha, String parentSha,
                           String path, String symbol, String modulePath, int startLine, int endLine,
                           Map<String, Object> locator, String contentHash, String excerpt, String sourceUrl, boolean truncated) {}
    public record Commit(String sha, List<String> parents, long committedAt, String message) {}
    public record FileRange(String sourceSha, String path, int startLine, int endLine, int totalLines,
                            String content, String contentHash, boolean truncated) {}
    public record SearchPage(List<Evidence> items, String nextCursor, boolean truncated,
                             Map<String, Object> coverage, String retrievalMethod) {}
}
