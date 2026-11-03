package io.github.qingyicode.repopilot.indexing;

import static io.github.qingyicode.repopilot.repository.RepositoryData.*;
import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.Hashes;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import io.github.qingyicode.repopilot.dependency.DependencyData.Graph;
import io.github.qingyicode.repopilot.repository.RepositoryStore;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class IndexStore {
    private final JdbcTemplate jdbc;
    private final RepositoryStore repositories;
    private final JsonMapper json = JsonMapper.builder().build();
    public IndexStore(JdbcTemplate jdbc, RepositoryStore repositories) { this.jdbc = jdbc; this.repositories = repositories; }
    public Blob blob(String hash) {
        var rows = jdbc.query("SELECT body,chunks_json FROM source_blob WHERE body_hash=?", (rs, row) -> {
            var parsed=json.readValue(rs.getString(2),SourceChunker.Result.class);
            return new Blob(hash,rs.getString(1),parsed.chunks(),parsed.warnings());
        },hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    @Transactional
    public void publish(Snapshot snapshot, String jobId, IndexProduct product) {
        String status = jdbc.queryForObject("SELECT status FROM repository_snapshot WHERE id=? FOR UPDATE", String.class, snapshot.id());
        if ("READY".equals(status)) { repositories.finishExisting(jobId, snapshot.id()); return; }
        if (Thread.currentThread().isInterrupted()) throw DomainException.conflict("SYNC_INTERRUPTED");
        batch("INSERT IGNORE INTO source_blob(body_hash,body,chunks_json) VALUES (?,?,?)", product.blobs().stream()
                .map(b -> new Object[]{b.bodyHash(), b.body(), json.writeValueAsString(new SourceChunker.Result(b.chunks(),b.warnings()))}).toList());
        jdbc.update("DELETE FROM source_file WHERE snapshot_id=?", snapshot.id());
        batch("INSERT INTO source_file(snapshot_id,path_hash,path,blob_sha,body_hash,module_path,language,line_count) VALUES (?,?,?,?,?,?,?,?)",
                product.files().stream().map(f -> new Object[]{snapshot.id(), Hashes.sha256(f.path()), f.path(), f.blobSha(), f.bodyHash(), f.modulePath(), f.language(), f.lineCount()}).toList());
        batch("INSERT IGNORE INTO evidence(id,repo_id,type,source_sha,parent_sha,path,symbol,module_path,start_line,end_line,locator_json,content_hash,excerpt,searchable,source_url,truncated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                product.evidence().stream().map(e -> new Object[]{e.id(), e.repoId(), e.type(), e.sourceSha(), e.parentSha(), e.path(), e.symbol(), e.modulePath(),
                        e.startLine(), e.endLine(), json.writeValueAsString(e.locator()), e.contentHash(), e.excerpt(),
                        e.symbol() + "\n" + e.path() + "\n" + e.excerpt(), e.sourceUrl(), e.truncated()}).toList());
        jdbc.update("DELETE FROM snapshot_evidence WHERE snapshot_id=?", snapshot.id());
        batch("INSERT INTO snapshot_evidence(snapshot_id,evidence_id) VALUES (?,?)", product.evidence().stream().map(e -> new Object[]{snapshot.id(), e.id()}).toList());
        batch("INSERT INTO git_commit(repo_id,sha,parents_json,committed_at,message) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE parents_json=VALUES(parents_json),committed_at=VALUES(committed_at),message=VALUES(message)", product.commits().stream()
                .map(c -> new Object[]{snapshot.repoId(), c.sha(), json.writeValueAsString(c.parents()), Timestamp.from(java.time.Instant.ofEpochSecond(c.committedAt())), c.message()}).toList());
        jdbc.update("DELETE FROM snapshot_commit WHERE snapshot_id=?", snapshot.id());
        batch("INSERT INTO snapshot_commit(snapshot_id,commit_sha) VALUES (?,?)", product.commits().stream().map(c -> new Object[]{snapshot.id(), c.sha()}).toList());
        jdbc.update("INSERT INTO dependency_graph(snapshot_id,graph_json) VALUES (?,?) ON DUPLICATE KEY UPDATE graph_json=VALUES(graph_json)",
                snapshot.id(), json.writeValueAsString(product.graph()));
        jdbc.update("UPDATE repository_snapshot SET status='READY',coverage_json=?,stats_json=? WHERE id=?",
                json.writeValueAsString(product.coverage()), json.writeValueAsString(product.stats()), snapshot.id());
        jdbc.update("UPDATE repository SET active_snapshot_id=? WHERE id=?", snapshot.id(), snapshot.repoId());
        repositories.finishExisting(jobId, snapshot.id());
    }
    private void batch(String sql, List<Object[]> rows) {
        for (int start = 0; start < rows.size(); start += 200) jdbc.batchUpdate(sql, rows.subList(start, Math.min(rows.size(), start + 200)));
    }
    public Graph graph(long repoId, long snapshotId) {
        repositories.snapshot(repoId, snapshotId);
        return json.readValue(jdbc.queryForObject("SELECT graph_json FROM dependency_graph WHERE snapshot_id=?", String.class, snapshotId), Graph.class);
    }
    public List<IndexedFile> files(long repoId, long snapshotId) {
        repositories.snapshot(repoId, snapshotId);
        return jdbc.query("SELECT * FROM source_file WHERE snapshot_id=? ORDER BY path", (rs,row) -> new IndexedFile(rs.getString("path"),
                rs.getString("blob_sha"), rs.getString("body_hash"), rs.getString("module_path"), rs.getString("language"), rs.getInt("line_count")), snapshotId);
    }
    public FileRange file(long repoId, long snapshotId, String path, int start, int end) {
        var snapshot = repositories.snapshot(repoId, snapshotId);
        RepositoryPolicy.path(path);
        if (start < 1 || end < start || end - start >= 200) throw DomainException.invalid("INVALID_LINE_RANGE");
        var rows = jdbc.query("SELECT f.*,b.body FROM source_file f JOIN source_blob b ON b.body_hash=f.body_hash WHERE f.snapshot_id=? AND f.path_hash=? AND f.path=?",
                (rs,row) -> {
                    String body = rs.getString("body");
                    String expected = Hashes.sha256(snapshot.indexVersion() + "|" + rs.getString("language") + "|" + body);
                    if (!expected.equals(rs.getString("body_hash"))) throw DomainException.conflict("SOURCE_HASH_MISMATCH");
                    int lines = rs.getInt("line_count");
                    if (start > lines) throw DomainException.invalid("LINE_OUT_OF_RANGE");
                    String content = EvidenceFactory.range(body,start,end);
                    return new FileRange(snapshot.commitSha(), path, start, Math.min(end,lines), lines, content, Hashes.sha256(content), start > 1 || end < lines);
                }, snapshotId, Hashes.sha256(path), path);
        if (rows.isEmpty()) throw DomainException.missing("INDEXED_FILE_NOT_FOUND");
        return rows.getFirst();
    }
    public Evidence evidence(long repoId, long snapshotId, String id) {
        repositories.snapshot(repoId, snapshotId);
        if (id == null || !id.matches("[a-f0-9]{64}")) throw DomainException.invalid("INVALID_EVIDENCE_ID");
        var rows = jdbc.query("SELECT e.* FROM evidence e JOIN snapshot_evidence se ON se.evidence_id=e.id WHERE se.snapshot_id=? AND e.repo_id=? AND e.id=?",
                this::evidenceRow, snapshotId, repoId, id);
        if (rows.isEmpty()) throw DomainException.missing("EVIDENCE_NOT_IN_SNAPSHOT");
        return rows.getFirst();
    }
    public SearchPage search(long repoId, long snapshotId, String query, String type, String path, String symbol,
                             String sha, String module, int limit, String cursor) {
        var snapshot = repositories.snapshot(repoId, snapshotId);
        if (query == null || query.isBlank() || query.length() > 200 || limit < 1 || limit > 100) throw DomainException.invalid("INVALID_SEARCH_QUERY");
        if (type != null && !List.of("FILE","COMMIT","DIFF").contains(type)) throw DomainException.invalid("INVALID_EVIDENCE_TYPE");
        if (path != null) RepositoryPolicy.path(path);
        if (sha != null) RepositoryPolicy.sha(sha);
        String fingerprint = Hashes.sha256(snapshotId + "|" + query + "|" + type + "|" + path + "|" + symbol + "|" + sha + "|" + module);
        String after = ""; String mode = "EXACT";
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":",3);
                if (parts.length != 3 || !parts[0].equals(fingerprint) || !List.of("EXACT","FULLTEXT").contains(parts[1]) || !parts[2].matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                mode = parts[1]; after = parts[2];
            } catch (IllegalArgumentException invalid) { throw DomainException.invalid("INVALID_SEARCH_CURSOR"); }
        }
        var rows = searchRows(repoId,snapshotId,query,type,path,symbol,sha,module,limit,after,mode);
        if ((cursor == null || cursor.isBlank()) && rows.isEmpty()) {
            mode = "FULLTEXT";
            rows = searchRows(repoId,snapshotId,query,type,path,symbol,sha,module,limit,after,mode);
        }
        boolean more = rows.size() > limit;
        var page = List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString((fingerprint + ":" + mode + ":" + page.getLast().id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new SearchPage(page,next,more,snapshot.coverage(),mode.equals("EXACT") ? "EXACT" : "MYSQL_FULLTEXT");
    }
    private List<Evidence> searchRows(long repoId,long snapshotId,String query,String type,String path,String symbol,
                                     String sha,String module,int limit,String after,String mode) {
        StringBuilder sql = new StringBuilder("SELECT e.* FROM snapshot_evidence se JOIN evidence e ON e.id=se.evidence_id WHERE se.snapshot_id=? AND e.repo_id=? AND e.id>? ");
        var args = new ArrayList<Object>(List.of(snapshotId, repoId, after));
        if (mode.equals("EXACT")) {
            sql.append("AND (");
            if (query.matches("[a-fA-F0-9]{40}")) { sql.append("e.source_sha=? OR ");args.add(query.toLowerCase(java.util.Locale.ROOT)); }
            sql.append("LOCATE(?,e.excerpt)>0 OR LOCATE(?,e.path)>0 OR LOCATE(?,e.symbol)>0) ");
            for (int i=0;i<3;i++) args.add(query);
        } else {
            sql.append("AND (MATCH(e.searchable) AGAINST (? IN NATURAL LANGUAGE MODE)>0 OR MATCH(e.excerpt) AGAINST (? IN NATURAL LANGUAGE MODE)>0) ");
            args.add(query); args.add(query);
        }
        for (var filter : List.of(new String[]{"type",type}, new String[]{"path",path}, new String[]{"symbol",symbol}, new String[]{"source_sha",sha}, new String[]{"module_path",module})) {
            if (filter[1] != null) { sql.append("AND e.").append(filter[0]).append("=? "); args.add(filter[1]); }
        }
        sql.append("ORDER BY e.id LIMIT ?"); args.add(limit + 1);
        return jdbc.query(sql.toString(), this::evidenceRow, args.toArray());
    }
    private Evidence evidenceRow(ResultSet rs, int row) throws SQLException {
        String excerpt = rs.getString("excerpt"); String hash = rs.getString("content_hash");
        if (!Hashes.sha256(excerpt).equals(hash)) throw DomainException.conflict("EVIDENCE_HASH_MISMATCH");
        return new Evidence(rs.getString("id"),rs.getLong("repo_id"),rs.getString("type"),rs.getString("source_sha"),rs.getString("parent_sha"),
                rs.getString("path"),rs.getString("symbol"),rs.getString("module_path"),rs.getInt("start_line"),rs.getInt("end_line"),
                json.readValue(rs.getString("locator_json"),new TypeReference<Map<String,Object>>(){}),hash,excerpt,rs.getString("source_url"),rs.getBoolean("truncated"));
    }
}
