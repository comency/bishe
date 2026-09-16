ALTER TABLE items ADD COLUMN claim_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE claims (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 item_id BIGINT NOT NULL,
 publisher_id BIGINT NOT NULL,
 applicant_id BIGINT NOT NULL,
 item_title_snapshot VARCHAR(100) NOT NULL,
 item_type_snapshot VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 item_content_version_snapshot BIGINT NOT NULL,
 evidence VARCHAR(1000) NOT NULL,
 applicant_contact_snapshot VARCHAR(100) NOT NULL,
 publisher_contact_snapshot VARCHAR(100),
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 accepted_at DATETIME(6),
 handed_over_at DATETIME(6),
 received_at DATETIME(6),
 completed_at DATETIME(6),
 ended_at DATETIME(6),
 end_reason_code VARCHAR(32),
 end_reason VARCHAR(500),
 exception_terminated BOOLEAN NOT NULL DEFAULT FALSE,
 row_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 exclusive_item_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ACCEPTED','COMPLETED') THEN item_id ELSE NULL END) STORED,
 CONSTRAINT fk_claim_item_owner FOREIGN KEY (item_id,publisher_id) REFERENCES items(id,publisher_id),
 CONSTRAINT fk_claim_applicant FOREIGN KEY (applicant_id) REFERENCES users(id),
 UNIQUE KEY uk_claim_applicant (item_id,applicant_id),
 UNIQUE KEY uk_claim_exclusive (exclusive_item_id),
 INDEX ix_claim_item (item_id,status,id),
 INDEX ix_claim_applicant (applicant_id,created_at,id),
 INDEX ix_claim_publisher (publisher_id,created_at,id),
 CONSTRAINT ck_claim_people CHECK (publisher_id<>applicant_id),
 CONSTRAINT ck_claim_snapshot CHECK (item_type_snapshot='FOUND' AND item_content_version_snapshot>=1 AND CHAR_LENGTH(TRIM(item_title_snapshot))>0),
 CONSTRAINT ck_claim_evidence CHECK (CHAR_LENGTH(TRIM(evidence))>0 AND CHAR_LENGTH(TRIM(applicant_contact_snapshot))>0),
 CONSTRAINT ck_claim_version CHECK (row_version>=0),
 CONSTRAINT ck_claim_state CHECK (status IN ('APPLIED','ACCEPTED','REJECTED','CANCELLED','COMPLETED')),
 CONSTRAINT ck_claim_confirmation CHECK (
   (status IN ('APPLIED','REJECTED') AND handed_over_at IS NULL AND received_at IS NULL) OR
   (status='ACCEPTED' AND (handed_over_at IS NULL OR received_at IS NULL)) OR
   (status='COMPLETED' AND handed_over_at IS NOT NULL AND received_at IS NOT NULL) OR
   (status='CANCELLED' AND (handed_over_at IS NULL OR received_at IS NULL) AND
     ((handed_over_at IS NULL AND received_at IS NULL) OR
       (exception_terminated=TRUE AND end_reason_code IS NOT NULL AND end_reason_code='ADMIN_REMOVED')))),
 CONSTRAINT ck_claim_accepted CHECK (
   (accepted_at IS NULL AND publisher_contact_snapshot IS NULL AND status IN ('APPLIED','REJECTED','CANCELLED') AND handed_over_at IS NULL AND received_at IS NULL) OR
   (accepted_at IS NOT NULL AND publisher_contact_snapshot IS NOT NULL AND CHAR_LENGTH(TRIM(publisher_contact_snapshot))>0 AND status IN ('ACCEPTED','CANCELLED','COMPLETED'))),
 CONSTRAINT ck_claim_ended CHECK (
   (status IN ('APPLIED','ACCEPTED') AND ended_at IS NULL AND end_reason_code IS NULL AND end_reason IS NULL AND completed_at IS NULL) OR
   (status IN ('REJECTED','CANCELLED') AND ended_at IS NOT NULL AND end_reason_code IS NOT NULL AND end_reason IS NOT NULL AND CHAR_LENGTH(TRIM(end_reason))>0 AND completed_at IS NULL) OR
   (status='COMPLETED' AND ended_at IS NOT NULL AND completed_at IS NOT NULL AND end_reason_code IS NULL AND end_reason IS NULL)),
 CONSTRAINT ck_claim_exception CHECK (exception_terminated=FALSE OR
   (status='CANCELLED' AND end_reason_code IS NOT NULL AND end_reason_code='ADMIN_REMOVED' AND
     ((handed_over_at IS NOT NULL AND received_at IS NULL) OR (handed_over_at IS NULL AND received_at IS NOT NULL))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE business_logs ADD CONSTRAINT fk_log_claim FOREIGN KEY (claim_id) REFERENCES claims(id);
CREATE INDEX ix_claim_history ON business_logs(claim_id,id);
