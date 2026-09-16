-- The prototype contains synthetic local accounts. Existing accounts remain UNVERIFIED.
-- TEST_CAMPUS is deliberate: changing campus/environment requires a reviewed data migration;
-- runtime eligibility rejects a campus or test-mode mismatch rather than promoting old results.
ALTER TABLE users
    MODIFY COLUMN role VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD COLUMN contact VARCHAR(100) NULL,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_users_row_version CHECK (row_version >= 0),
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER','ADMIN'));
-- Preserve the original phone column, including values too long for the new contact contract.
UPDATE users SET contact = CASE WHEN CHAR_LENGTH(phone) <= 100 THEN phone ELSE NULL END,
                 updated_at = UTC_TIMESTAMP(6);
ALTER TABLE users MODIFY updated_at DATETIME(6) NOT NULL;

CREATE TABLE campus_verifications (
    user_id BIGINT NOT NULL,
    campus_code VARCHAR(64) NOT NULL,
    stored_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNVERIFIED',
    current_application_id BIGINT NULL,
    last_application_version BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NULL,
    status_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_verification_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_verification_status CHECK (stored_status IN ('UNVERIFIED','PENDING','VERIFIED','REJECTED','REVOKED')),
    CONSTRAINT ck_verification_version CHECK (row_version >= 0 AND last_application_version >= 0),
    CONSTRAINT ck_verification_current CHECK (stored_status NOT IN ('PENDING','VERIFIED') OR current_application_id IS NOT NULL),
    CONSTRAINT ck_verification_expiry CHECK (stored_status <> 'VERIFIED' OR expires_at IS NOT NULL),
    INDEX idx_verification_status (stored_status,updated_at,user_id),
    INDEX idx_verification_expiry (expires_at,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE verification_applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    application_version BIGINT NOT NULL,
    campus_code VARCHAR(64) NOT NULL,
    real_name VARCHAR(80) NOT NULL,
    student_number VARCHAR(40) NULL,
    statement VARCHAR(500) NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_summary VARCHAR(500) NULL,
    valid_through DATE NULL,
    user_reason VARCHAR(500) NULL,
    internal_note VARCHAR(500) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    submitted_at DATETIME(6) NOT NULL,
    is_test BOOLEAN NOT NULL,
    pending_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN user_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_application_version UNIQUE (user_id,application_version),
    CONSTRAINT uk_application_owner UNIQUE (id,user_id),
    CONSTRAINT uk_pending_application UNIQUE (pending_user_id),
    CONSTRAINT fk_application_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_application_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT ck_application_version CHECK (application_version >= 1),
    CONSTRAINT ck_application_status CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
    CONSTRAINT ck_application_method CHECK (method IS NULL OR method IN ('IN_PERSON','ROSTER')),
    CONSTRAINT ck_application_review CHECK ((status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL) OR
        (status<>'PENDING' AND reviewed_by IS NOT NULL AND reviewed_by<>user_id AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_application_approved CHECK (status<>'VERIFIED' OR
        (method IS NOT NULL AND evidence_summary IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_summary))>0 AND valid_through IS NOT NULL)),
    CONSTRAINT ck_application_rejected CHECK (status<>'REJECTED' OR
        (user_reason IS NOT NULL AND CHAR_LENGTH(TRIM(user_reason))>0)),
    INDEX idx_application_user_time (user_id,submitted_at,id),
    INDEX idx_application_status_time (status,submitted_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE campus_verifications ADD CONSTRAINT fk_verification_current_application
    FOREIGN KEY (current_application_id,user_id) REFERENCES verification_applications(id,user_id);

CREATE TABLE business_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    actor_id BIGINT NULL,
    actor_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_user_id BIGINT NULL,
    verification_application_id BIGINT NULL,
    item_id BIGINT NULL,
    claim_id BIGINT NULL,
    from_state VARCHAR(24) NULL,
    to_state VARCHAR(24) NULL,
    object_version BIGINT NULL,
    content_version BIGINT NULL,
    reason_code VARCHAR(32) NULL,
    user_reason VARCHAR(500) NULL,
    resolution_conclusion VARCHAR(500) NULL,
    internal_note VARCHAR(500) NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_log_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_log_subject FOREIGN KEY (subject_user_id) REFERENCES users(id),
    CONSTRAINT fk_log_verification_application FOREIGN KEY (verification_application_id) REFERENCES verification_applications(id),
    CONSTRAINT fk_log_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT ck_log_actor CHECK ((actor_kind='SYSTEM' AND actor_id IS NULL) OR
        (actor_kind IN ('USER','ADMIN') AND actor_id IS NOT NULL)),
    INDEX idx_log_item (item_id,occurred_at,id),
    INDEX idx_log_claim (claim_id,occurred_at,id),
    INDEX idx_log_subject (subject_user_id,occurred_at,id),
    INDEX idx_log_time (occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- claim_id remains nullable and unused until the claims migration adds its table and FK.

INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at)
SELECT id,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM users;
