package io.github.qingyicode.repopilot.indexing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.github.qingyicode.repopilot.dependency.PomXml;
import io.github.qingyicode.repopilot.repository.RepositoryData.Chunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SourceChunker {
    public record Result(List<Chunk> chunks, List<String> warnings) {}
    public Result chunk(String path, String body) {
        var chunks = new ArrayList<Chunk>(); var warnings = new ArrayList<String>();
        int lines = body.split("\n", -1).length;
        if (path.endsWith(".java")) {
            var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            var parsed = parser.parse(body);
            if (parsed.isSuccessful() && parsed.getResult().isPresent()) {
                var unit = parsed.getResult().orElseThrow();
                String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
                unit.findAll(TypeDeclaration.class).forEach(type -> type.getRange().ifPresent(range -> {
                    int headerEnd = Math.min(range.end.line, range.begin.line + 12);
                    add(chunks, range.begin.line, headerEnd, packageName + type.getNameAsString());
                }));
                unit.findAll(CallableDeclaration.class).forEach(method -> method.getRange().ifPresent(range -> {
                    String owner = method.getParentNode().filter(t -> t instanceof TypeDeclaration<?>)
                            .map(t -> ((TypeDeclaration<?>) t).getNameAsString()).orElse("");
                    add(chunks, range.begin.line, range.end.line, packageName + owner + "#" + method.getSignature().asString());
                }));
            } else warnings.add("JAVA_PARSE_FALLBACK");
        } else if (path.endsWith("pom.xml")) {
            try {
                var root = PomXml.parse(body);
                for (var node : root.children) {
                    if (Set.of("dependencies", "dependencyManagement", "properties", "parent", "profiles", "modules").contains(node.name)) {
                        if (node.name.equals("dependencies")) {
                            for (var dep : node.children("dependency")) add(chunks, dep.start, dep.end,
                                    "dependencies." + dep.value("groupId", "") + ":" + dep.value("artifactId", ""));
                        } else add(chunks, node.start, node.end, node.name);
                    }
                }
            } catch (IllegalArgumentException invalid) { warnings.add("POM_PARSE_FALLBACK"); }
        } else if (path.endsWith(".yml") || path.endsWith(".yaml")) {
            String[] text = body.split("\n", -1); int start = 1; String symbol = "configuration";
            for (int index = 0; index < text.length; index++) {
                if (text[index].matches("[A-Za-z_][A-Za-z0-9_.-]*:.*")) {
                    if (index + 1 > start) add(chunks, start, index, symbol);
                    start = index + 1; symbol = text[index].substring(0, text[index].indexOf(':'));
                }
            }
            add(chunks, start, lines, symbol);
        }
        // Window coverage preserves imports, fields, comments and gaps not covered by AST ranges.
        boolean[] covered = new boolean[lines + 1];
        for (var chunk : chunks) for (int i = chunk.startLine(); i <= Math.min(lines, chunk.endLine()); i++) covered[i] = true;
        int cursor = 1;
        while (cursor <= lines) {
            if (covered[cursor]) { cursor++; continue; }
            int start = cursor;
            while (cursor <= lines && !covered[cursor]) cursor++;
            add(chunks, start, cursor - 1, "file");
        }
        chunks.sort(Comparator.comparingInt(Chunk::startLine).thenComparing(Chunk::symbol));
        return new Result(List.copyOf(chunks), List.copyOf(warnings));
    }
    private static void add(List<Chunk> chunks, int start, int end, String symbol) {
        for (int line = start; line <= end; line += 68) {
            chunks.add(new Chunk(line, Math.min(end, line + 79), symbol, end - start + 1 > 80));
            if (line + 79 >= end) break;
        }
    }
}
