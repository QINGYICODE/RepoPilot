ALTER TABLE analysis_run
    ADD COLUMN instance_id CHAR(36) CHARACTER SET ascii NULL,
    ADD COLUMN lease_until TIMESTAMP(6) NULL,
    ADD KEY ix_analysis_lease (status, lease_until);
