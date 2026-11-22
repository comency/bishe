-- Allow the existing return workflow to represent responses to LOST notices.
-- Existing FOUND claims remain unchanged; contact snapshots are still private until ACCEPTED.
ALTER TABLE claims
  DROP CHECK ck_claim_snapshot,
  ADD CONSTRAINT ck_claim_snapshot CHECK (
    item_type_snapshot IN ('FOUND', 'LOST')
    AND item_content_version_snapshot >= 1
    AND CHAR_LENGTH(TRIM(item_title_snapshot)) > 0
  );

-- Both FOUND claims and LOST leads end in the same two-party return confirmation.
ALTER TABLE items
  DROP CHECK ck_items_close_type,
  ADD CONSTRAINT ck_items_close_type CHECK (close_reason <> 'FOUND_BY_OWNER' OR type = 'LOST');
