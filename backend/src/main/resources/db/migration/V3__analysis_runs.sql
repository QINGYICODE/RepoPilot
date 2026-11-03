CREATE TABLE analysis_run (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    task VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY ix_analysis_repo (repo_id, created_at),
    CONSTRAINT fk_analysis_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_snapshot FOREIGN KEY (snapshot_id) REFERENCES repository_snapshot(id) ON DELETE CASCADE
);
