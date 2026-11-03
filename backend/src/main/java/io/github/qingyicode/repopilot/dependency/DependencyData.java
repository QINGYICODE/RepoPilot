package io.github.qingyicode.repopilot.dependency;

import java.util.List;

public final class DependencyData {
    private DependencyData() {}
    public record Source(String path, int startLine, int endLine, String evidenceId) {}
    public record Dependency(String key, String coordinate, String version, String expression,
                             String scope, boolean optional, List<String> exclusions, String resolution,
                             String internalModule, List<Source> sources) {}
    public record Module(String path, String groupId, String artifactId, String version, boolean service,
                         String parentPath, List<Dependency> dependencies, List<String> opaqueManagement, List<Source> modelSources) {}
    public record Edge(String from, String to, String scope, boolean optional, List<String> exclusions, List<Source> sources) {}
    public record Graph(List<Module> modules, List<Edge> edges, List<String> warnings) {}
    public record Change(String id, String module, String coordinate, String kind,
                         Dependency before, Dependency after) {}
    public record Impact(String changeId, String service, String view, String kind, String scope,
                         List<String> path, List<String> evidenceIds) {}
    public record Result(long baseSnapshotId, long headSnapshotId, List<Change> changes,
                         List<Impact> impacts, List<String> affectedServices, List<String> warnings,
                         boolean truncated, String coverage) {}
}
