ALTER TABLE audit_log
    ADD COLUMN delegated_actor_type VARCHAR(32) NULL AFTER actor_id,
    ADD COLUMN delegated_actor_id VARCHAR(128) NULL AFTER delegated_actor_type,
    ADD COLUMN source VARCHAR(32) NULL AFTER trace_id;

UPDATE audit_log
SET source = CASE
    WHEN action = 'AGENT_DELEGATION_ISSUED' THEN 'AGENT_API'
    WHEN actor_type = 'ADMIN' THEN 'ADMIN_API'
    ELSE 'PORTAL_API'
END;

ALTER TABLE audit_log
    MODIFY COLUMN source VARCHAR(32) NOT NULL,
    ADD CONSTRAINT ck_audit_delegated_actor_type
        CHECK (delegated_actor_type IS NULL
            OR delegated_actor_type IN ('USER', 'ADMIN', 'AGENT', 'SERVICE', 'SYSTEM')),
    ADD CONSTRAINT ck_audit_delegated_actor_pair
        CHECK ((delegated_actor_type IS NULL AND delegated_actor_id IS NULL)
            OR (delegated_actor_type IS NOT NULL AND delegated_actor_id IS NOT NULL)),
    ADD CONSTRAINT ck_audit_source
        CHECK (source IN ('PORTAL_API', 'ADMIN_API', 'AGENT_API')),
    DROP KEY idx_audit_actor_occurred,
    DROP KEY idx_audit_resource_occurred,
    ADD KEY idx_audit_actor_occurred
        (actor_type, actor_id, occurred_at, audit_id),
    ADD KEY idx_audit_delegated_actor_occurred
        (delegated_actor_type, delegated_actor_id, occurred_at, audit_id),
    ADD KEY idx_audit_action_occurred
        (action, occurred_at, audit_id),
    ADD KEY idx_audit_resource_occurred
        (resource_type, resource_id, occurred_at, audit_id),
    ADD KEY idx_audit_result_occurred
        (result, occurred_at, audit_id),
    ADD KEY idx_audit_source_occurred
        (source, occurred_at, audit_id),
    ADD KEY idx_audit_trace (trace_id);

DELIMITER $$
CREATE TRIGGER audit_log_prevent_update
BEFORE UPDATE ON audit_log
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END$$

CREATE TRIGGER audit_log_prevent_delete
BEFORE DELETE ON audit_log
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END$$
DELIMITER ;
