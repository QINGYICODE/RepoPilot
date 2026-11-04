package io.github.qingyicode.repopilot.repository;

import static io.github.qingyicode.repopilot.config.RepositorySettings.INDEX_VERSION;
import static io.github.qingyicode.repopilot.config.RepositorySettings.PROFILE_HASH;
import static io.github.qingyicode.repopilot.repository.RepositoryData.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.qingyicode.repopilot.common.DomainException;
import io.github.qingyicode.repopilot.common.RepositoryPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RepositoryStore {
    private final RepositoryMapper mapper;
    private final JdbcTemplate jdbc;
    private final RepositoryPolicy policy;
    private final RepositoryGrantStore grants;
    private final String profileHash;
    private final JsonMapper json = JsonMapper.builder().build();
    public RepositoryStore(RepositoryMapper mapper, JdbcTemplate jdbc, RepositoryPolicy policy,
                           io.github.qingyicode.repopilot.config.RepositorySettings settings,RepositoryGrantStore grants) {
        this.mapper = mapper; this.jdbc = jdbc; this.policy = policy;
        this.profileHash = settings.profileHash();
        this.grants=grants;
    }
    public RepositoryRecord repository(long id) {
        var record = mapper.selectById(id);
        if (record == null) throw DomainException.missing("REPOSITORY_NOT_FOUND");
        policy.allowedName(record.getOwner(), record.getName());
        return record;
    }
    public RepositoryRecord find(String owner, String name) {
        // JDBC writes below must be visible immediately, including inside the same transaction.
        var rows = jdbc.query("SELECT * FROM repository WHERE owner=? AND name=?", (rs, row) -> {
            var record = new RepositoryRecord();
            record.setId(rs.getLong("id")); record.setOwner(rs.getString("owner")); record.setName(rs.getString("name"));
            record.setDefaultRef(rs.getString("default_ref")); record.setGithubId(rs.getObject("github_id", Long.class));
            record.setActiveSnapshotId(rs.getObject("active_snapshot_id", Long.class)); return record;
        }, owner, name);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public List<RepositoryRecord> repositories() {
        return mapper.selectList(new QueryWrapper<RepositoryRecord>().orderByAsc("id")).stream()
                .filter(r -> { try { policy.allowedName(r.getOwner(), r.getName()); return true; }
                    catch (DomainException error) { return false; } }).toList();
    }
    @Transactional
    public RepositoryRecord register(String owner, String name, Metadata metadata) {
        var previous=find(owner,name);
        if(previous!=null && previous.getGithubId()!=null && previous.getGithubId()!=metadata.githubId()) throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
        jdbc.update("INSERT INTO repository(owner,name,default_ref,github_id) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE default_ref=IF(github_id IS NULL OR github_id=VALUES(github_id),VALUES(default_ref),default_ref),github_id=IF(github_id IS NULL,VALUES(github_id),github_id)", owner, name, metadata.defaultBranch(), metadata.githubId());
        var registered=find(owner,name);
        if(registered.getGithubId()!=metadata.githubId()) throw DomainException.conflict("GITHUB_IDENTITY_CHANGED");
        return registered;
    }
    @Transactional
    public RepositoryRecord authorizeAndRegister(String owner,String name,Metadata metadata) {
        String fullName=RepositoryPolicy.fullName(owner,name);
        // Both statements roll back if identity validation or repository persistence fails.
        var record=register(owner,name,metadata);grants.grant(fullName,metadata.githubId());return record;
    }
    @Transactional
    public SyncJob enqueue(long repoId, String sha) {
        repository(repoId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT IGNORE INTO sync_job(id,repo_id,target_sha,index_version,profile_hash,status) VALUES (?,?,?,?,?,'QUEUED')",
                id, repoId, sha, INDEX_VERSION, profileHash);
        jdbc.update("UPDATE sync_job SET status='QUEUED',error_code=NULL,lease_until=NULL WHERE repo_id=? AND target_sha=? "
                + "AND index_version=? AND profile_hash=? AND status='FAILED'", repoId, sha, INDEX_VERSION, profileHash);
        return jdbc.queryForObject("SELECT * FROM sync_job WHERE repo_id=? AND target_sha=? AND index_version=? AND profile_hash=?",
                this::jobRow, repoId, sha, INDEX_VERSION, profileHash);
    }
    public SyncJob job(String id) {
        var rows = jdbc.query("SELECT * FROM sync_job WHERE id=?", this::jobRow, id);
        if (rows.isEmpty()) throw DomainException.missing("SYNC_JOB_NOT_FOUND");
        repository(rows.getFirst().repoId()); return rows.getFirst();
    }
    public List<SyncJob> queued() {
        return jdbc.query("SELECT * FROM sync_job WHERE status='QUEUED' ORDER BY created_at LIMIT 8", this::jobRow);
    }
    public boolean claim(String id, int leaseSeconds) {
        return jdbc.update("UPDATE sync_job SET status='FETCHING',attempts=attempts+1,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) "
                + "WHERE id=? AND status='QUEUED'", leaseSeconds, id) == 1;
    }
    public void heartbeat(String id, int leaseSeconds) {
        jdbc.update("UPDATE sync_job SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=? AND status IN ('FETCHING','INDEXING')",
                leaseSeconds, id);
    }
    public void release(String id) {
        jdbc.update("UPDATE sync_job SET status='QUEUED',lease_until=NULL WHERE id=? AND status='FETCHING'", id);
    }
    public void recover() {
        jdbc.update("UPDATE sync_job SET status=IF(attempts<3,'QUEUED','FAILED'),error_code='LEASE_EXPIRED',lease_until=NULL "
                + "WHERE status IN ('FETCHING','INDEXING') AND lease_until<CURRENT_TIMESTAMP(6)");
    }
    public void indexing(String jobId, long snapshotId) {
        jdbc.update("UPDATE sync_job SET status='INDEXING',snapshot_id=? WHERE id=? AND status='FETCHING'", snapshotId, jobId);
    }
    @Transactional
    public Snapshot prepare(long repoId, String sha) {
        jdbc.update("INSERT IGNORE INTO repository_snapshot(repo_id,commit_sha,index_version,profile_hash,status,coverage_json,stats_json) "
                + "VALUES (?,?,?,?,'BUILDING','{}','{}')", repoId, sha, INDEX_VERSION, profileHash);
        return jdbc.queryForObject("SELECT * FROM repository_snapshot WHERE repo_id=? AND commit_sha=? AND index_version=? AND profile_hash=?",
                this::snapshotRow, repoId, sha, INDEX_VERSION, profileHash);
    }
    public Snapshot snapshot(long repoId, long id) {
        repository(repoId);
        var rows = jdbc.query("SELECT * FROM repository_snapshot WHERE repo_id=? AND id=? AND status='READY'", this::snapshotRow, repoId, id);
        if (rows.isEmpty()) throw DomainException.conflict("SNAPSHOT_NOT_READY");
        return rows.getFirst();
    }
    public List<Snapshot> snapshots(long repoId) {
        repository(repoId);
        return jdbc.query("SELECT * FROM repository_snapshot WHERE repo_id=? AND status='READY' ORDER BY id DESC", this::snapshotRow, repoId);
    }
    public void fail(String id, String code) {
        jdbc.update("UPDATE sync_job SET status='FAILED',error_code=?,lease_until=NULL WHERE id=? AND status IN ('FETCHING','INDEXING')", code, id);
    }
    public void finishExisting(String jobId, long snapshotId) {
        jdbc.update("UPDATE sync_job SET status='READY',snapshot_id=?,error_code=NULL,lease_until=NULL WHERE id=?", snapshotId, jobId);
    }
    private SyncJob jobRow(ResultSet rs, int row) throws SQLException {
        return new SyncJob(rs.getString("id"), rs.getLong("repo_id"), rs.getString("target_sha"),
                rs.getObject("snapshot_id", Long.class), rs.getString("status"), rs.getInt("attempts"),
                rs.getString("error_code"), rs.getTimestamp("updated_at").toInstant().toString());
    }
    private Snapshot snapshotRow(ResultSet rs, int row) throws SQLException {
        return new Snapshot(rs.getLong("id"), rs.getLong("repo_id"), rs.getString("commit_sha"), rs.getString("index_version"),
                rs.getString("status"), json.readValue(rs.getString("coverage_json"), new TypeReference<Map<String,Object>>() {}),
                json.readValue(rs.getString("stats_json"), new TypeReference<Map<String,Object>>() {}));
    }
}
