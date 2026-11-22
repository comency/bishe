CREATE TABLE notification_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recipient_user_id BIGINT NOT NULL,
  claim_id BIGINT NOT NULL,
  item_type_snapshot VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6),
  last_error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin,
  UNIQUE KEY uk_notification_outbox_event_key (event_key),
  INDEX ix_notification_outbox_pending (processed_at, next_attempt_at, id),
  CONSTRAINT fk_notification_outbox_user FOREIGN KEY (recipient_user_id) REFERENCES users(id),
  CONSTRAINT fk_notification_outbox_claim FOREIGN KEY (claim_id) REFERENCES claims(id),
  CONSTRAINT ck_notification_outbox_type CHECK (item_type_snapshot IN ('FOUND','LOST')),
  CONSTRAINT ck_notification_outbox_event CHECK (event_type IN (
    'RESPONSE_RECEIVED','RESPONSE_ACCEPTED','RESPONSE_REJECTED','RESPONSE_WITHDRAWN',
    'HANDOVER_CONFIRMED','RECEIPT_CONFIRMED','RETURN_COMPLETED')),
  CONSTRAINT ck_notification_outbox_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  source_event_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(240) NOT NULL,
  related_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  related_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  read_at DATETIME(6),
  UNIQUE KEY uk_notification_source (user_id, source_event_key),
  INDEX ix_notification_user_created (user_id, created_at, id),
  INDEX ix_notification_user_unread (user_id, read_at, created_at, id),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_notification_claim FOREIGN KEY (related_id) REFERENCES claims(id),
  CONSTRAINT ck_notification_type CHECK (type IN (
    'RESPONSE_RECEIVED','RESPONSE_ACCEPTED','RESPONSE_REJECTED','RESPONSE_WITHDRAWN',
    'HANDOVER_CONFIRMED','RECEIPT_CONFIRMED','RETURN_COMPLETED')),
  CONSTRAINT ck_notification_related_type CHECK (related_type = 'CLAIM')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
