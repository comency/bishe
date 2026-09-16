-- Preserve legacy local timestamps; new UTC columns are separate.
-- Unexplained historical CLOSED rows intentionally fail the CHECK below.
ALTER TABLE items
 ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN reviewed_content_version BIGINT NULL,
 ADD COLUMN reviewed_by BIGINT NULL,
 ADD COLUMN reviewed_at DATETIME(6) NULL,
 ADD COLUMN review_reason VARCHAR(500) NULL,
 ADD COLUMN close_reason VARCHAR(32) NULL,
 ADD COLUMN close_note VARCHAR(500) NULL,
 ADD COLUMN closed_at DATETIME(6) NULL,
 ADD COLUMN created_at_utc DATETIME(6) NULL,
 ADD COLUMN updated_at_utc DATETIME(6) NULL,
 ADD CONSTRAINT fk_items_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
 ADD CONSTRAINT ck_items_type CHECK (type IN ('LOST','FOUND')),
 ADD CONSTRAINT ck_items_state CHECK (status IN ('PENDING','APPROVED','REJECTED','CLOSED')),
 ADD CONSTRAINT ck_items_version CHECK (row_version >= 0 AND content_version >= 1),
 ADD CONSTRAINT ck_items_closed CHECK ((status='CLOSED' AND close_reason IS NOT NULL AND closed_at IS NOT NULL) OR (status<>'CLOSED' AND close_reason IS NULL AND closed_at IS NULL)),
 ADD CONSTRAINT ck_items_close_reason CHECK (close_reason IS NULL OR close_reason IN ('RETURNED','FOUND_BY_OWNER','WITHDRAWN','ADMIN_REMOVED')),
 ADD CONSTRAINT ck_items_close_type CHECK ((close_reason <> 'RETURNED' OR type='FOUND') AND (close_reason <> 'FOUND_BY_OWNER' OR type='LOST')),
 ADD INDEX ix_items_public (status,type,created_at,id),
 ADD INDEX ix_items_category (status,category,created_at,id),
 ADD INDEX ix_items_owner (publisher_id,created_at,id),
 ADD UNIQUE KEY uk_items_owner (id,publisher_id);

CREATE TABLE media_files (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 uploader_id BIGINT NOT NULL,
 storage_key VARCHAR(255) NOT NULL UNIQUE,
 mime_type VARCHAR(32) NOT NULL,
 size_bytes BIGINT NOT NULL,
 width INT NOT NULL,
 height INT NOT NULL,
 sha256 CHAR(64) NOT NULL,
 lifecycle VARCHAR(16) NOT NULL,
 expires_at DATETIME(6),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 deleted_at DATETIME(6),
 row_version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_media_uploader FOREIGN KEY (uploader_id) REFERENCES users(id),
 CONSTRAINT ck_media_type CHECK (mime_type IN ('image/jpeg','image/png')),
 CONSTRAINT ck_media_size CHECK (size_bytes BETWEEN 1 AND 5242880 AND width>0 AND height>0),
 CONSTRAINT ck_media_state CHECK (lifecycle IN ('TEMPORARY','BOUND','REMOVED','PURGING','DELETED')),
 CONSTRAINT ck_media_expiry CHECK (lifecycle<>'TEMPORARY' OR expires_at IS NOT NULL),
 INDEX ix_media_owner (uploader_id,created_at,id),
 INDEX ix_media_expiry (lifecycle,expires_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE item_images (
 item_id BIGINT NOT NULL,
 media_id BIGINT NOT NULL,
 display_order INT NOT NULL,
 bound_at DATETIME(6) NOT NULL,
 PRIMARY KEY (item_id,media_id),
 UNIQUE KEY uk_image_binding (media_id),
 UNIQUE KEY uk_image_order (item_id,display_order),
 CONSTRAINT fk_image_item FOREIGN KEY (item_id) REFERENCES items(id),
 CONSTRAINT fk_image_media FOREIGN KEY (media_id) REFERENCES media_files(id),
 CONSTRAINT ck_image_order CHECK (display_order BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX ix_item_history ON business_logs(item_id,id);
