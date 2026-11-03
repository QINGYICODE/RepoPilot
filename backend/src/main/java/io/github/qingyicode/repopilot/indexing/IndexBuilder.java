package io.github.qingyicode.repopilot.indexing;

import static io.github.qingyicode.repopilot.repository.RepositoryData.*;
import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.Hashes;
import io.github.qingyicode.repopilot.config.RepositorySettings;
import io.github.qingyicode.repopilot.dependency.DependencyData.Source;
import io.github.qingyicode.repopilot.dependency.PomAnalyzer;
import io.github.qingyicode.repopilot.repository.RepositoryRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

@Component
public class IndexBuilder {
    private final RepositorySettings settings;
    private final SourceChunker chunker;
    private final PomAnalyzer poms;
    private final IndexStore store;
    public IndexBuilder(RepositorySettings settings, SourceChunker chunker, PomAnalyzer poms, IndexStore store) {
        this.settings = settings; this.chunker = chunker; this.poms = poms; this.store = store;
    }
    public IndexProduct build(RepositoryRecord record, String sha, Repository repository) {
        long began = System.nanoTime();
        String fullName = record.getOwner() + "/" + record.getName();
        var warnings = new LinkedHashSet<String>();
        var text = new LinkedHashMap<String,String>(); var objectIds = new LinkedHashMap<String,String>();
        var evidence = new LinkedHashMap<String,Evidence>(); var blobs = new LinkedHashMap<String,Blob>();
        var files = new ArrayList<IndexedFile>(); var commits = new ArrayList<Commit>();
        int reused = 0; int bytes = 0; boolean historyComplete = true;
        try (var walk = new RevWalk(repository)) {
            RevCommit selected = walk.parseCommit(ObjectId.fromString(sha));
            try (var tree = new TreeWalk(repository)) {
                tree.addTree(selected.getTree()); tree.setRecursive(true);
                while (tree.next()) {
                    cancelled(); String path = tree.getPathString();
                    if (tree.getFileMode(0).equals(FileMode.GITLINK)) { warnings.add("SUBMODULE_NOT_INDEXED"); continue; }
                    if (tree.getFileMode(0).equals(FileMode.SYMLINK)) { warnings.add("SYMLINK_NOT_INDEXED"); continue; }
                    if (!supported(path)) continue;
                    if (text.size() >= settings.maxFiles()) { warnings.add("FILE_COUNT_LIMIT"); break; }
                    var object = repository.open(tree.getObjectId(0));
                    if (object.getSize() > settings.maxFileBytes()) { warnings.add("FILE_SIZE_LIMIT:" + path); continue; }
                    if (bytes + object.getSize() > 25 * 1024 * 1024) { warnings.add("SNAPSHOT_BYTE_LIMIT"); break; }
                    byte[] content = object.getBytes(settings.maxFileBytes());
                    if (binary(content)) { warnings.add("BINARY_TEXT_FILE:" + path); continue; }
                    String body;
                    try { body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString(); }
                    catch (java.nio.charset.CharacterCodingException invalid) { warnings.add("NON_UTF8_FILE:" + path); continue; }
                    String safe = Redactor.clean(body);
                    if (!safe.equals(body)) warnings.add("CONTENT_REDACTED");
                    text.put(path, safe); objectIds.put(path, tree.getObjectId(0).name()); bytes += content.length;
                }
            }
            for (var entry : text.entrySet()) {
                cancelled(); String path = entry.getKey(); String body = entry.getValue(); String language = language(path);
                String hash = Hashes.sha256(RepositorySettings.INDEX_VERSION + "|" + language + "|" + body);
                Blob blob = blobs.get(hash);
                if (blob == null) {
                    blob = store.blob(hash);
                    if (blob != null) reused++;
                    else {
                        var parsed = chunker.chunk(path, body);
                        blob = new Blob(hash, body, parsed.chunks(),parsed.warnings());
                    }
                    blobs.put(hash, blob);
                }
                if (!blob.body().equals(body)) throw DomainException.conflict("SOURCE_HASH_MISMATCH");
                warnings.addAll(blob.warnings().stream().map(w -> w + ":" + path).toList());
                String module = module(path, text.keySet());
                files.add(new IndexedFile(path, objectIds.get(path), hash, module, language, body.split("\n", -1).length));
                for (var chunk : blob.chunks()) {
                    if (evidence.size() >= settings.maxEvidence()) { warnings.add("EVIDENCE_LIMIT"); break; }
                    var item = EvidenceFactory.create(record.getId(), fullName, "FILE", sha, null, path, chunk.symbol(), module,
                            chunk.startLine(), chunk.endLine(), Map.of(), EvidenceFactory.range(body, chunk.startLine(), chunk.endLine()), chunk.truncated());
                    evidence.put(item.id(), item);
                }
            }
            var graph = poms.analyze(text, source -> {
                String body = text.get(source.path());
                if (body == null) return source;
                var item = EvidenceFactory.create(record.getId(), fullName, "FILE", sha, null, source.path(), "maven-declaration",
                        module(source.path(), text.keySet()), source.startLine(), source.endLine(), Map.of(),
                        EvidenceFactory.range(body, source.startLine(), source.endLine()), false);
                evidence.put(item.id(), item);
                return new Source(source.path(), source.startLine(), source.endLine(), item.id());
            });
            warnings.addAll(graph.warnings());
            walk.reset(); walk.markStart(walk.parseCommit(ObjectId.fromString(sha)));
            for (RevCommit commit : walk) {
                cancelled();
                if (commits.size() >= settings.maxHistoryCommits()) { historyComplete = false; warnings.add("HISTORY_LIMIT"); break; }
                List<String> parents = Arrays.stream(commit.getParents()).map(RevCommit::name).toList();
                String message = Redactor.clean(commit.getFullMessage());
                if (message.length() > 12000) { message = message.substring(0,12000); warnings.add("COMMIT_MESSAGE_TRUNCATED"); }
                commits.add(new Commit(commit.name(), parents, commit.getCommitTime(), message));
                var item = EvidenceFactory.create(record.getId(), fullName, "COMMIT", commit.name(), null, "", "commit", "",
                        0, 0, Map.of("parents", parents), message, false);
                evidence.put(item.id(), item);
                if (evidence.size() >= settings.maxEvidence()) { warnings.add("EVIDENCE_LIMIT"); continue; }
                if (commit.getParentCount() == 0) diff(repository, record, commit, null, text.keySet(), evidence, warnings);
                else for (var parent : commit.getParents()) {
                    try { diff(repository, record, commit, walk.parseCommit(parent), text.keySet(), evidence, warnings); }
                    catch (org.eclipse.jgit.errors.MissingObjectException missing) { historyComplete = false; warnings.add("SHALLOW_HISTORY"); }
                }
            }
            if (!repository.getObjectDatabase().getShallowCommits().isEmpty()) { historyComplete = false; warnings.add("SHALLOW_HISTORY"); }
            var coverage = new LinkedHashMap<String,Object>();
            coverage.put("level", warnings.isEmpty() ? "COMPLETE" : "PARTIAL");
            coverage.put("historyComplete", historyComplete); coverage.put("warnings", List.copyOf(warnings));
            coverage.put("sourceScope", "SUPPORTED_TEXT_FILES_AND_REACHABLE_HISTORY");
            var stats = new LinkedHashMap<String,Object>();
            stats.put("fileCount", files.size()); stats.put("commitCount", commits.size()); stats.put("evidenceCount", evidence.size());
            stats.put("hunkCount", evidence.values().stream().filter(e -> e.type().equals("DIFF")).count());
            stats.put("moduleCount", graph.modules().size()); stats.put("reusedBlobCount", reused);
            stats.put("treeSha", selected.getTree().name()); stats.put("durationMs", (System.nanoTime() - began) / 1_000_000);
            return new IndexProduct(List.copyOf(blobs.values()), List.copyOf(files), List.copyOf(evidence.values()),
                    List.copyOf(commits), graph, coverage, stats);
        } catch (IOException failure) { throw DomainException.conflict("GIT_INDEX_READ_FAILED"); }
    }

    private void diff(Repository repository, RepositoryRecord record, RevCommit commit, RevCommit parent, Set<String> paths,
                      Map<String,Evidence> evidence, Set<String> warnings) throws IOException {
        try (var reader = repository.newObjectReader(); var buffer = new ByteArrayOutputStream(); var formatter = new DiffFormatter(buffer)) {
            formatter.setRepository(repository); formatter.setContext(3); formatter.setDetectRenames(true);
            var next = new CanonicalTreeParser(); next.reset(reader, commit.getTree());
            org.eclipse.jgit.treewalk.AbstractTreeIterator old = new EmptyTreeIterator();
            if (parent != null) { var previous = new CanonicalTreeParser(); previous.reset(reader, parent.getTree()); old = previous; }
            for (var entry : formatter.scan(old, next)) {
                cancelled();
                String path = entry.getNewPath().equals(DiffEntry.DEV_NULL) ? entry.getOldPath() : entry.getNewPath();
                if (!supported(path)) continue;
                if (evidence.size() >= settings.maxEvidence()) { warnings.add("EVIDENCE_LIMIT"); return; }
                if ((!entry.getOldId().toObjectId().equals(ObjectId.zeroId()) && reader.getObjectSize(entry.getOldId().toObjectId(), org.eclipse.jgit.lib.Constants.OBJ_BLOB) > settings.maxFileBytes())
                        || (!entry.getNewId().toObjectId().equals(ObjectId.zeroId()) && reader.getObjectSize(entry.getNewId().toObjectId(), org.eclipse.jgit.lib.Constants.OBJ_BLOB) > settings.maxFileBytes())) {
                    warnings.add("DIFF_FILE_SIZE_LIMIT"); continue;
                }
                buffer.reset(); formatter.format(entry);
                // toFileHeader(entry) contains only a synthetic header/edit list in JGit.
                // Parse the actual formatted patch to preserve hunk bytes and both-side offsets.
                var parsedPatch = new org.eclipse.jgit.patch.Patch();
                parsedPatch.parse(new java.io.ByteArrayInputStream(buffer.toByteArray()));
                if (parsedPatch.getFiles().isEmpty()) { warnings.add("DIFF_PARSE_FAILED"); continue; }
                var header = parsedPatch.getFiles().getFirst();
                if (header.getPatchType() != org.eclipse.jgit.patch.FileHeader.PatchType.UNIFIED) continue;
                if (header.getHunks().isEmpty()) {
                    var locator = new LinkedHashMap<String,Object>();
                    locator.put("oldPath",entry.getOldPath());locator.put("newPath",entry.getNewPath());
                    locator.put("changeType",entry.getChangeType().name());locator.put("metadataOnly",true);
                    var item=EvidenceFactory.create(record.getId(),record.getOwner()+"/"+record.getName(),"DIFF",commit.name(),
                            parent==null?null:parent.name(),path,"diff-metadata",module(path,paths),0,0,locator,
                            buffer.toString(StandardCharsets.UTF_8),false);
                    evidence.put(item.id(),item);
                }
                for (var hunk : header.getHunks()) {
                    if (evidence.size() >= settings.maxEvidence()) { warnings.add("EVIDENCE_LIMIT"); return; }
                    int oldStart = hunk.getOldImage().getStartLine(); int oldCount = hunk.getOldImage().getLineCount();
                    int newStart = hunk.getNewStartLine(); int newCount = hunk.getNewLineCount();
                    var locator = new LinkedHashMap<String,Object>();
                    locator.put("oldPath", entry.getOldPath()); locator.put("newPath", entry.getNewPath());
                    locator.put("oldStartLine", oldStart); locator.put("oldLineCount", oldCount);
                    locator.put("newStartLine", newStart); locator.put("newLineCount", newCount);
                    locator.put("changeType", entry.getChangeType().name());
                    String patch = new String(header.getBuffer(), hunk.getStartOffset(), hunk.getEndOffset() - hunk.getStartOffset(), StandardCharsets.UTF_8);
                    var item = EvidenceFactory.create(record.getId(), record.getOwner() + "/" + record.getName(), "DIFF", commit.name(),
                            parent == null ? null : parent.name(), path, "diff", module(path, paths), newStart, newStart + Math.max(0,newCount - 1),
                            locator, patch, false);
                    evidence.put(item.id(), item);
                    if (item.truncated()) warnings.add("DIFF_HUNK_TRUNCATED");
                }
            }
        }
    }
    private static boolean binary(byte[] content) { for (byte value : content) if (value == 0) return true; return false; }
    private static void cancelled() { if (Thread.currentThread().isInterrupted()) throw DomainException.conflict("SYNC_INTERRUPTED"); }
    public static boolean supported(String path) {
        if (path.matches("(^|.*/)(node_modules|target|dist|vendor|\\.git|\\.idea)/.*")) return false;
        return path.matches(".*\\.(java|xml|yml|yaml|properties|md|ts|js|vue|sql)$") || path.endsWith("Dockerfile");
    }
    private static String language(String path) {
        if (path.endsWith("pom.xml")) return "pom";
        int dot = path.lastIndexOf('.'); return dot < 0 ? "text" : path.substring(dot + 1);
    }
    private static String module(String path, Set<String> files) {
        int slash = path.lastIndexOf('/');
        while (slash >= 0) {
            String directory = path.substring(0, slash);
            if (files.contains(directory + "/pom.xml")) return directory;
            slash = directory.lastIndexOf('/');
        }
        return files.contains("pom.xml") ? "." : "";
    }
}
