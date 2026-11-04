CREATE TABLE repository_authorization (
    full_name VARCHAR(201) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    github_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE repository_connection (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    owner VARCHAR(100) CHARACTER SET ascii NOT NULL,
    name VARCHAR(100) CHARACTER SET ascii NOT NULL,
    authorize_access BOOLEAN NOT NULL,
    auto_import BOOLEAN NOT NULL,
    repo_id BIGINT NULL,
    sync_job_id CHAR(36) CHARACTER SET ascii NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    retry_at TIMESTAMP(6) NULL,
    active_key CHAR(64) CHARACTER SET ascii NULL,
    instance_id CHAR(36) CHARACTER SET ascii NOT NULL,
    lease_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_connection_active (active_key),
    KEY ix_connection_queue (status, lease_until),
    CONSTRAINT fk_connection_repo FOREIGN KEY (repo_id) REFERENCES repository(id) ON DELETE SET NULL,
    CONSTRAINT fk_connection_sync FOREIGN KEY (sync_job_id) REFERENCES sync_job(id) ON DELETE SET NULL
);
