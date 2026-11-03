package io.github.qingyicode.repopilot.dependency;

import static io.github.qingyicode.repopilot.dependency.DependencyData.*;
import io.github.qingyicode.repopilot.dependency.DependencyData.Module;
import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.Hashes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public class ImpactAnalyzer {
    public Result compare(long baseId, Graph base, long headId, Graph head, String moduleFilter, int maxDepth) {
        if (maxDepth < 1 || maxDepth > 12) throw DomainException.invalid("INVALID_GRAPH_DEPTH");
        var warnings = new LinkedHashSet<String>(); warnings.addAll(base.warnings()); warnings.addAll(head.warnings());
        warnings.add("DECLARATION_GRAPH_ONLY_NO_RUNTIME_CALL_GRAPH");
        var oldModules = modules(base); var newModules = modules(head);
        var paths = new TreeSet<String>(); paths.addAll(oldModules.keySet()); paths.addAll(newModules.keySet());
        if (moduleFilter != null && !paths.contains(moduleFilter)) throw DomainException.invalid("MODULE_NOT_IN_SNAPSHOTS");
        var changes = new ArrayList<Change>(); var impacts = new ArrayList<Impact>(); boolean[] truncated = {false};
        for (String path : paths) {
            if (moduleFilter != null && !moduleFilter.equals(path)) continue;
            var previous = oldModules.get(path); var next = newModules.get(path);
            var before = dependencies(previous); var after = dependencies(next);
            if (previous != null && next != null && !previous.opaqueManagement().equals(next.opaqueManagement())) {
                warnings.add("BOM_CHANGED_REQUIRES_RESOLUTION:" + path);
            }
            var keys = new TreeSet<String>(); keys.addAll(before.keySet()); keys.addAll(after.keySet());
            for (String key : keys) {
                var left = before.get(key); var right = after.get(key);
                if (equivalent(left,right)) continue;
                String kind = left == null ? "ADDED" : right == null ? "REMOVED" : "MODIFIED";
                String coordinate = left == null ? right.coordinate() : left.coordinate();
                var change = new Change(Hashes.sha256(path + "|" + key + "|" + kind).substring(0,20),path,coordinate,kind,left,right);
                changes.add(change);
                if (left != null) traverse(change,base,left,"BASE",maxDepth,impacts,warnings,truncated);
                if (right != null) traverse(change,head,right,"HEAD",maxDepth,impacts,warnings,truncated);
            }
        }
        return new Result(baseId,headId,List.copyOf(changes),List.copyOf(impacts),impacts.stream()
                .filter(i -> !i.kind().equals("UNKNOWN")).map(Impact::service).distinct().sorted().toList(),List.copyOf(warnings),truncated[0],"PARTIAL");
    }
    private record State(String module, String scope, boolean stop, List<String> path, Set<String> visited, List<String> evidence) {}
    private void traverse(Change change, Graph graph, Dependency dependency, String view, int maxDepth,
                          List<Impact> result, Set<String> warnings, boolean[] truncated) {
        var modules = modules(graph); var reverse = new LinkedHashMap<String,List<Edge>>();
        for (var edge : graph.edges()) reverse.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
        var queue = new ArrayDeque<State>();
        queue.add(new State(change.module(),dependency.scope(),dependency.optional() || !transitive(dependency.scope()),
                List.of(change.module(),dependency.coordinate() + "@" + (dependency.version().isBlank() ? "?" : dependency.version())),
                Set.of(change.module()),ids(dependency.sources())));
        while (!queue.isEmpty()) {
            if (result.size() >= 2000) { truncated[0] = true; warnings.add("GRAPH_RESULT_LIMIT"); return; }
            var state = queue.removeFirst(); var module = modules.get(state.module());
            if (module != null && module.service()) {
                result.add(new Impact(change.id(),state.module(),view,
                        dependency.resolution().equals("UNKNOWN") ? "UNKNOWN" : "DECLARED_IMPACT",state.scope(),state.path(),state.evidence()));
            }
            if (state.stop()) continue;
            var users = reverse.getOrDefault(state.module(),List.of());
            if (state.visited().size() >= maxDepth && !users.isEmpty()) { truncated[0] = true; warnings.add("GRAPH_DEPTH_LIMIT"); continue; }
            for (var edge : users) {
                if (edge.exclusions()!=null && edge.exclusions().stream().anyMatch(exclusion -> excluded(exclusion,dependency.coordinate(),state.path(),modules))) continue;
                if (state.visited().contains(edge.from())) { warnings.add("DEPENDENCY_CYCLE"); continue; }
                var path = new ArrayList<>(state.path()); path.addFirst(edge.from());
                var visited = new LinkedHashSet<>(state.visited()); visited.add(edge.from());
                var evidence = new LinkedHashSet<>(ids(edge.sources())); evidence.addAll(state.evidence());
                String scope = transitive(edge.scope()) ? state.scope() : edge.scope();
                queue.addLast(new State(edge.from(),scope,edge.optional() || !transitive(edge.scope()),List.copyOf(path),visited,List.copyOf(evidence)));
            }
        }
    }
    private static boolean excluded(String exclusion,String dependency,List<String> path,Map<String,Module> modules) {
        var coordinates=new ArrayList<String>();coordinates.add(dependency);
        for(String node:path) {var module=modules.get(node);if(module!=null)coordinates.add(module.groupId()+":"+module.artifactId());}
        String[] parts=exclusion.split(":",-1);
        if(parts.length!=2)return false;
        return coordinates.stream().anyMatch(coordinate->{var ga=coordinate.split(":",-1);return ga.length==2
                && (parts[0].equals("*")||parts[0].equals(ga[0]))&&(parts[1].equals("*")||parts[1].equals(ga[1]));});
    }
    private static boolean transitive(String scope) { return scope.equals("compile") || scope.equals("runtime"); }
    private static List<String> ids(List<Source> sources) { return sources.stream().map(Source::evidenceId).filter(s -> !s.isBlank()).distinct().toList(); }
    private static Map<String,Module> modules(Graph graph) { var result = new LinkedHashMap<String,Module>(); graph.modules().forEach(m -> result.put(m.path(),m)); return result; }
    private static Map<String,Dependency> dependencies(Module module) { var result = new LinkedHashMap<String,Dependency>(); if (module != null) module.dependencies().forEach(d -> result.put(d.key(),d)); return result; }
    private static boolean equivalent(Dependency before, Dependency after) {
        if (before == null || after == null) return before == after;
        return before.version().equals(after.version()) && before.scope().equals(after.scope()) && before.optional() == after.optional()
                && before.exclusions().equals(after.exclusions()) && before.resolution().equals(after.resolution())
                && java.util.Objects.equals(before.internalModule(),after.internalModule());
    }
}
