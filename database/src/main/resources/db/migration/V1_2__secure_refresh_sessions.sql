ALTER TABLE refresh_token
    ADD COLUMN session_type VARCHAR(16) NULL AFTER user_id,
    ADD COLUMN csrf_hash CHAR(64) NULL AFTER token_hash;

UPDATE refresh_token
SET
    revoked_at = CASE
        WHEN status = 'ACTIVE' THEN COALESCE(revoked_at, CURRENT_TIMESTAMP(6))
        ELSE revoked_at
    END,
    status = CASE WHEN status = 'ACTIVE' THEN 'REVOKED' ELSE status END,
    session_type = CASE
        WHEN audience LIKE '%admin%' THEN 'ADMIN'
        ELSE 'USER'
    END,
    csrf_hash = SHA2(CONCAT('task05-legacy-revoked:', refresh_token_id), 256);

ALTER TABLE refresh_token
    MODIFY COLUMN session_type VARCHAR(16) NOT NULL,
    MODIFY COLUMN csrf_hash CHAR(64) NOT NULL,
    ADD CONSTRAINT ck_refresh_token_session_type CHECK (session_type IN ('USER', 'ADMIN')),
    ADD CONSTRAINT ck_refresh_token_csrf_hash CHECK (csrf_hash REGEXP '^[0-9a-f]{64}$'),
    ADD KEY idx_refresh_token_family_session (family_id, session_type, status);

ALTER TABLE audit_log
    DROP CHECK ck_audit_actor_type,
    ADD CONSTRAINT ck_audit_actor_type
        CHECK (actor_type IN ('USER', 'ADMIN', 'AGENT', 'SERVICE', 'SYSTEM'));
