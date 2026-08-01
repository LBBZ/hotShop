ALTER TABLE outbox_event
    ADD COLUMN lease_token CHAR(36) NULL AFTER status,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER lease_token,
    ADD COLUMN consecutive_attempts INT NOT NULL DEFAULT 0 AFTER publish_attempts,
    ADD COLUMN failure_category VARCHAR(64) NULL AFTER last_error,
    ADD COLUMN manual_replay_count INT NOT NULL DEFAULT 0 AFTER failure_category,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER manual_replay_count,
    ADD CONSTRAINT ck_outbox_manual_replay_count CHECK (manual_replay_count >= 0),
    ADD CONSTRAINT ck_outbox_consecutive_attempts CHECK (consecutive_attempts >= 0),
    ADD CONSTRAINT ck_outbox_version CHECK (version >= 0),
    ADD CONSTRAINT ck_outbox_lease_pair CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    ADD KEY idx_outbox_claim (status, available_at, lease_expires_at, outbox_id),
    ADD KEY idx_outbox_failed_cursor (status, outbox_id);

UPDATE outbox_event
   SET failure_category = 'LEGACY_FAILURE', last_error = 'LEGACY_FAILURE'
 WHERE last_error IS NOT NULL OR status = 'FAILED';

-- V1.4 and earlier had no lease owner or fencing field. A row left in
-- PUBLISHING therefore cannot prove ownership and must enter a fresh reliable
-- publication round. Cumulative publish_attempts remain intact as evidence.
UPDATE outbox_event
   SET status = 'NEW',
       available_at = LEAST(available_at, UTC_TIMESTAMP(6)),
       consecutive_attempts = 0,
       failure_category = 'LEGACY_PUBLISHING_RECOVERED',
       last_error = 'LEGACY_PUBLISHING_RECOVERED',
       version = version + 1
 WHERE status = 'PUBLISHING';
