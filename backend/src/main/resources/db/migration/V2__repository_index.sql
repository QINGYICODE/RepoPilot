ALTER TABLE repository
    ADD COLUMN github_id BIGINT NULL,
    ADD COLUMN active_snapshot_id BIGINT NULL;

CREATE TABLE repository_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    commit_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_version VARCHAR(32) NOT NULL,
    profile_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    status VARCHAR(16) NOT NULL,
    coverage_json JSON NOT NULL,
    stats_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_snapshot (repo_id, commit_sha, index_version, profile_hash),
    CONSTRAINT fk_snapshot_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE CASCADE
);

CREATE TABLE sync_job (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    target_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    index_version VARCHAR(32) NOT NULL,
    profile_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    snapshot_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_sync_target (repo_id, target_sha, index_version, profile_hash),
    KEY ix_job_queue (status, lease_until),
    CONSTRAINT fk_job_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE CASCADE
);

CREATE TABLE source_blob (
    body_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY,
    body MEDIUMTEXT NOT NULL,
    chunks_json JSON NOT NULL
);

CREATE TABLE source_file (
    snapshot_id BIGINT NOT NULL,
    path_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    path VARCHAR(1024) COLLATE utf8mb4_0900_as_cs NOT NULL,
    blob_sha CHAR(40) CHARACTER SET ascii NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    module_path VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL,
    line_count INT NOT NULL,
    PRIMARY KEY (snapshot_id, path_hash),
    CONSTRAINT fk_file_snapshot FOREIGN KEY (snapshot_id) REFERENCES repository_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_blob FOREIGN KEY (body_hash) REFERENCES source_blob(body_hash)
);

CREATE TABLE evidence (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_sha CHAR(40) CHARACTER SET ascii NULL,
    path VARCHAR(1024) COLLATE utf8mb4_0900_as_cs NOT NULL,
    symbol VARCHAR(512) COLLATE utf8mb4_0900_as_cs NOT NULL,
    module_path VARCHAR(512) NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    locator_json JSON NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    excerpt TEXT NOT NULL,
    searchable TEXT NOT NULL,
    source_url TEXT NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    KEY ix_evidence_sha (repo_id, source_sha),
    KEY ix_evidence_symbol (repo_id, symbol(160)),
    FULLTEXT KEY ft_evidence (searchable),
    FULLTEXT KEY ft_evidence_ngram (excerpt) WITH PARSER ngram,
    CONSTRAINT fk_evidence_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE CASCADE
);

CREATE TABLE snapshot_evidence (
    snapshot_id BIGINT NOT NULL,
    evidence_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (snapshot_id, evidence_id),
    CONSTRAINT fk_se_snapshot FOREIGN KEY (snapshot_id) REFERENCES repository_snapshot(id) ON DELETE CASCADE,
    CONSTRAINT fk_se_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(id) ON DELETE CASCADE
);

CREATE TABLE git_commit (
    repo_id BIGINT NOT NULL,
    sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parents_json JSON NOT NULL,
    committed_at TIMESTAMP NOT NULL,
    message TEXT NOT NULL,
    PRIMARY KEY (repo_id, sha),
    CONSTRAINT fk_commit_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE CASCADE
);

CREATE TABLE snapshot_commit (
    snapshot_id BIGINT NOT NULL,
    commit_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (snapshot_id, commit_sha),
    CONSTRAINT fk_sc_snapshot FOREIGN KEY (snapshot_id) REFERENCES repository_snapshot(id) ON DELETE CASCADE
);

CREATE TABLE dependency_graph (
    snapshot_id BIGINT NOT NULL PRIMARY KEY,
    graph_json JSON NOT NULL,
    CONSTRAINT fk_graph_snapshot FOREIGN KEY (snapshot_id) REFERENCES repository_snapshot(id) ON DELETE CASCADE
);
