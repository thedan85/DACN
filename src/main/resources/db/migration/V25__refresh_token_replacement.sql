-- Track the rotated successor so a concurrent tab can reuse it within the
-- grace window instead of triggering the stolen-token replay alarm.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by_token_id VARCHAR(255);
