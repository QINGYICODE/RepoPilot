package io.github.qingyicode.repopilot.agent;

import java.util.List;
import java.util.Map;
import io.github.qingyicode.repopilot.dependency.DependencyData;
import io.github.qingyicode.repopilot.incident.IncidentData;

public final class AnalysisData {
    private AnalysisData() {}
    public enum Task { DEPENDENCY_IMPACT, INCIDENT_FIX }
    public record Request(Long repoId, Long snapshotId, Task task, String question,
                          Long baseSnapshotId, String module, String deployedSha) {}
    public record Bound(Request request, String repository, String knowledgeSha, String baseSha) {}
    public record Citation(String id, long snapshotId, String type, String sourceSha, String path,
                           int startLine, int endLine, String excerpt, String sourceUrl) {}
    public record Event(int sequence, String tool, String status, String code, boolean cached, long durationMs) {}
    public record Usage(int modelCalls, int requestedToolCalls, int executedToolCalls, int failedToolCalls,
                        int rejectedToolCalls, int cachedToolCalls, long inputTokens, long outputTokens,
                        boolean estimatedTokens, int maxPromptBytes, int compactedExchanges, long durationMs) {}
    public record Result(String id, String status, String reasonCode, String summary, String repository,
                         String knowledgeSha, String baseSha, List<Citation> citations,
                         DependencyData.Result dependency, IncidentData.Result incident,
                         List<Event> events, Usage usage, List<String> warnings) {}
    public record ToolResult(String status, Object data, List<String> evidenceIds, List<String> warnings, String error) {}
}
