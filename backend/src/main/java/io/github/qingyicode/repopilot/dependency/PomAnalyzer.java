package io.github.qingyicode.repopilot.dependency;

import static io.github.qingyicode.repopilot.dependency.DependencyData.*;
import io.github.qingyicode.repopilot.dependency.DependencyData.Module;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PomAnalyzer {
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private record Property(String value, Source source) {}
    private record Declaration(PomXml.Node node, String path) {}
    private static final class Effective {
        String path, group, artifact, version, parent;
        boolean service;
        final Map<String, Property> properties = new LinkedHashMap<>();
        final Map<String, Declaration> managed = new LinkedHashMap<>();
        final Map<String, Declaration> declared = new LinkedHashMap<>();
        final List<Declaration> imports = new ArrayList<>();
        final List<Source> modelSources = new ArrayList<>();
    }

    public Graph analyze(Map<String, String> files, Function<Source, Source> prove) {
        var warnings = new LinkedHashSet<String>();
        var poms = new LinkedHashMap<String, PomXml.Node>();
        if (files.containsKey("pom.xml")) discover("pom.xml", files, poms, warnings, new HashSet<>());
        else {
            var visited = new HashSet<String>();
            // Mixed frontend/backend repositories may have no root Maven aggregator.
            files.keySet().stream().filter(path -> path.endsWith("/pom.xml"))
                    .sorted(java.util.Comparator.comparingInt((String path) -> path.split("/").length).thenComparing(Function.identity()))
                    .forEach(path -> discover(path, files, poms, warnings, visited));
            if (!poms.isEmpty()) warnings.add("MAVEN_ROOTS_DISCOVERED_FROM_SUBDIRECTORIES");
        }
        if (poms.isEmpty()) { warnings.add("NO_MAVEN_ROOT"); return new Graph(List.of(), List.of(), List.copyOf(warnings)); }
        var models = new LinkedHashMap<String, Effective>();
        for (String path : poms.keySet()) resolve(path, poms, files, models, new HashSet<>(), warnings);
        var modules = new ArrayList<Module>();
        var edges = new ArrayList<Edge>();
        for (var model : models.values()) {
            var dependencies = new ArrayList<Dependency>();
            for (var declaration : model.declared.values()) {
                var node = declaration.node();
                var sources = new LinkedHashSet<Source>();
                sources.add(source(declaration.path(), node));
                String group = interpolate(node.value("groupId", ""), model.properties, sources, new HashSet<>());
                String artifact = interpolate(node.value("artifactId", ""), model.properties, sources, new HashSet<>());
                String type = interpolate(node.value("type", "jar"), model.properties, sources, new HashSet<>());
                String classifier = interpolate(node.value("classifier", ""), model.properties, sources, new HashSet<>());
                String key = group + ":" + artifact + ":" + type + ":" + classifier;
                Declaration management = model.managed.get(key);
                String expression = node.value("version", "");
                String scope = node.value("scope", "");
                String optional = node.value("optional", "");
                if (management != null) {
                    if (expression.isBlank()) { expression = management.node().value("version", ""); sources.add(source(management.path(), management.node())); }
                    if (scope.isBlank()) scope = management.node().value("scope", "compile");
                    if (optional.isBlank()) optional = management.node().value("optional", "false");
                }
                String version = interpolate(expression, model.properties, sources, new HashSet<>());
                scope = interpolate(scope.isBlank() ? "compile" : scope, model.properties, sources, new HashSet<>());
                optional = interpolate(optional.isBlank() ? "false" : optional, model.properties, sources, new HashSet<>());
                var exclusions = new ArrayList<String>();
                var exclusionNode = node.child("exclusions");
                if (exclusionNode != null) for (var exclusion : exclusionNode.children("exclusion")) {
                    exclusions.add(interpolate(exclusion.value("groupId", "") + ":" + exclusion.value("artifactId", ""), model.properties, sources, new HashSet<>()));
                }
                boolean resolved = known(version) && !version.matches(".*[\\[\\](),].*") && known(group) && known(artifact)
                        && List.of("compile","runtime","test","provided","system").contains(scope) && !optional.contains("${");
                String internal = null;
                if (resolved && type.equals("jar")) {
                    for (var candidate : models.values()) {
                        if (candidate.group.equals(group) && candidate.artifact.equals(artifact) && candidate.version.equals(version)) internal = modulePath(candidate.path);
                    }
                }
                if (!resolved) warnings.add("UNRESOLVED_DEPENDENCY:" + modulePath(model.path) + ":" + group + ":" + artifact);
                var proven = sources.stream().map(prove).toList();
                var dependency = new Dependency(key, group + ":" + artifact, version, expression, scope,
                        Boolean.parseBoolean(optional), exclusions.stream().sorted().toList(), resolved ? "RESOLVED" : "UNKNOWN", internal, proven);
                dependencies.add(dependency);
                if (internal != null) edges.add(new Edge(modulePath(model.path), internal, scope, dependency.optional(), dependency.exclusions(), proven));
            }
            var opaque = new ArrayList<String>();
            for (var declaration : model.imports) {
                var node = declaration.node();
                var sources = new LinkedHashSet<Source>();
                String coordinate = interpolate(node.value("groupId", "") + ":" + node.value("artifactId", "") + ":" + node.value("version", ""), model.properties, sources, new HashSet<>());
                opaque.add(coordinate);
                model.modelSources.add(source(declaration.path(),node)); model.modelSources.addAll(sources);
                warnings.add("UNRESOLVED_BOM:" + modulePath(model.path) + ":" + coordinate);
            }
            modules.add(new Module(modulePath(model.path), model.group, model.artifact, model.version, model.service,
                    model.parent == null ? null : modulePath(model.parent), List.copyOf(dependencies), List.copyOf(opaque),
                    model.modelSources.stream().map(prove).toList()));
        }
        return new Graph(List.copyOf(modules), List.copyOf(edges), List.copyOf(warnings));
    }

    private void discover(String path, Map<String,String> files, Map<String,PomXml.Node> poms, Set<String> warnings, Set<String> visiting) {
        if (!visiting.add(path)) return;
        String text = files.get(path);
        if (text == null) { warnings.add("MISSING_MODULE_POM:" + path); return; }
        try {
            var node = PomXml.parse(text); poms.put(path, node);
            if (node.child("profiles") != null) warnings.add("PROFILES_NOT_EVALUATED:" + path);
            var modules = node.child("modules");
            if (modules != null) for (var module : modules.children("module")) {
                String nested = local(path, module.value() + "/pom.xml");
                if (nested == null || nested.contains("${")) warnings.add("UNRESOLVED_MODULE_PATH:" + path);
                else discover(nested, files, poms, warnings, visiting);
            }
        } catch (IllegalArgumentException error) { warnings.add("INVALID_OR_UNSAFE_POM:" + path); }
    }

    private Effective resolve(String path, Map<String,PomXml.Node> poms, Map<String,String> files,
                              Map<String,Effective> ready, Set<String> visiting, Set<String> warnings) {
        if (ready.containsKey(path)) return ready.get(path);
        if (!visiting.add(path)) { warnings.add("PARENT_CYCLE:" + path); return null; }
        var xml = poms.get(path);
        var result = new Effective(); result.path = path;
        var parent = xml.child("parent");
        Effective inherited = null;
        if (parent != null) {
            String relative = parent.value("relativePath", "../pom.xml");
            String candidate = relative.isBlank() ? null : local(path, relative);
            if (candidate != null && poms.containsKey(candidate)) {
                inherited = resolve(candidate, poms, files, ready, visiting, warnings);
                if (inherited != null && (!parent.value("artifactId", "").equals(inherited.artifact)
                        || !parent.value("groupId", "").equals(inherited.group)
                        || !interpolate(parent.value("version", ""), inherited.properties, new LinkedHashSet<>(), new HashSet<>()).equals(inherited.version))) {
                    warnings.add("LOCAL_PARENT_COORDINATE_MISMATCH:" + path); inherited = null;
                }
            }
            if (inherited == null) warnings.add("UNRESOLVED_PARENT:" + path);
            result.modelSources.add(source(path, parent));
        }
        if (inherited != null) {
            result.parent = inherited.path;
            result.properties.putAll(inherited.properties); result.managed.putAll(inherited.managed);
            result.declared.putAll(inherited.declared); result.imports.addAll(inherited.imports);
            result.modelSources.addAll(inherited.modelSources);
        }
        var properties = xml.child("properties");
        if (properties != null) for (var property : properties.children) result.properties.put(property.name, new Property(property.value(), source(path, property)));
        result.group = xml.value("groupId", parent == null ? "" : parent.value("groupId", ""));
        result.artifact = xml.value("artifactId", "");
        result.version = xml.value("version", parent == null ? "" : parent.value("version", ""));
        Source modelSource = source(path, xml.child("artifactId") == null ? xml : xml.child("artifactId"));
        Source groupSource=xml.child("groupId")!=null?source(path,xml.child("groupId")):
                parent!=null?source(path,parent):modelSource;
        Source versionSource=xml.child("version")!=null?source(path,xml.child("version")):
                parent!=null?source(path,parent):modelSource;
        result.properties.put("project.groupId", new Property(result.group, groupSource));
        result.properties.put("project.artifactId", new Property(result.artifact, modelSource));
        result.properties.put("project.version", new Property(result.version, versionSource));
        result.properties.put("pom.groupId", result.properties.get("project.groupId"));
        result.properties.put("pom.artifactId", result.properties.get("project.artifactId"));
        result.properties.put("pom.version", result.properties.get("project.version"));
        if (parent != null) {
            for (String key : List.of("groupId", "artifactId", "version")) result.properties.put("project.parent." + key, new Property(parent.value(key, ""), source(path,parent)));
        }
        result.group = interpolate(result.group, result.properties, new LinkedHashSet<>(), new HashSet<>());
        result.version = interpolate(result.version, result.properties, new LinkedHashSet<>(), new HashSet<>());
        result.service = !xml.value("packaging", "jar").equals("pom") && files.keySet().stream().anyMatch(f ->
                f.startsWith(prefix(path) + "src/main/") && (f.matches(".*application\\.(yml|yaml|properties)$")
                || (f.endsWith(".java") && files.get(f).contains("@SpringBootApplication"))));
        var management = xml.child("dependencyManagement");
        if (management != null) consume(management.child("dependencies"), path, result.managed, result.imports, result.properties);
        consume(xml.child("dependencies"), path, result.declared, null, result.properties);
        ready.put(path, result); visiting.remove(path); return result;
    }

    private void consume(PomXml.Node dependencies, String path, Map<String,Declaration> destination,
                         List<Declaration> imports, Map<String,Property> properties) {
        if (dependencies == null) return;
        for (var node : dependencies.children("dependency")) {
            var declaration = new Declaration(node, path);
            if (imports != null && node.value("type", "jar").equals("pom") && node.value("scope", "compile").equals("import")) {
                // Child BOM declarations with the same identity replace the inherited declaration.
                imports.removeIf(old -> old.node().value("groupId", "").equals(node.value("groupId", ""))
                        && old.node().value("artifactId", "").equals(node.value("artifactId", "")));
                imports.add(declaration); continue;
            }
            String key = interpolate(node.value("groupId", "") + ":" + node.value("artifactId", "") + ":"
                    + node.value("type", "jar") + ":" + node.value("classifier", ""), properties, new LinkedHashSet<>(), new HashSet<>());
            destination.put(key, declaration);
        }
    }

    private static String interpolate(String raw, Map<String,Property> properties, Set<Source> sources, Set<String> stack) {
        var matcher = PROPERTY.matcher(raw);
        var output = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1); Property property = properties.get(key);
            String value = matcher.group();
            if (property != null && stack.size() < 24 && stack.add(key)) {
                sources.add(property.source()); value = interpolate(property.value(), properties, sources, stack); stack.remove(key);
            }
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output); return output.toString();
    }
    private static boolean known(String value) { return !value.isBlank() && !value.contains("${") && !value.contains("[REDACTED]"); }
    private static Source source(String path, PomXml.Node node) { return new Source(path, node.start, node.end, ""); }
    public static String modulePath(String pom) { return pom.equals("pom.xml") ? "." : pom.substring(0, pom.length() - "/pom.xml".length()); }
    private static String prefix(String pom) { return pom.equals("pom.xml") ? "" : pom.substring(0, pom.lastIndexOf('/') + 1); }
    private static String local(String pom, String relative) {
        if (relative.contains("\\") || relative.startsWith("/") || relative.contains(":")) return null;
        Path base = Path.of(pom).getParent();
        Path resolved = (base == null ? Path.of(relative) : base.resolve(relative)).normalize();
        if (resolved.isAbsolute() || resolved.startsWith("..")) return null;
        return resolved.toString().replace('\\','/');
    }
}
