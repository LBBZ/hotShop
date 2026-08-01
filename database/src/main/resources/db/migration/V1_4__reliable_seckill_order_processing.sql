ALTER TABLE sale_reservation
    ADD COLUMN unit_price DECIMAL(19, 2) NULL AFTER quantity,
    ADD COLUMN currency CHAR(3) NULL AFTER reserved_amount,
    ADD COLUMN activity_version INT NULL AFTER currency,
    ADD COLUMN idempotency_key_hash CHAR(64) NULL AFTER activity_version,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER idempotency_key_hash,
    ADD COLUMN reserved_at DATETIME(6) NULL AFTER request_fingerprint,
    ADD CONSTRAINT ck_reservation_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
    ADD CONSTRAINT ck_reservation_currency CHECK (currency IS NULL OR currency = 'CNY'),
    ADD CONSTRAINT ck_reservation_activity_version CHECK (
        activity_version IS NULL OR activity_version >= 0
    ),
    ADD CONSTRAINT ck_reservation_idempotency_hash CHECK (
        idempotency_key_hash IS NULL OR idempotency_key_hash REGEXP '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_reservation_fingerprint CHECK (
        request_fingerprint IS NULL OR request_fingerprint REGEXP '^[0-9a-f]{64}$'
    );

ALTER TABLE audit_log
    DROP CHECK ck_audit_source,
    ADD CONSTRAINT ck_audit_source CHECK (
        source IN ('PORTAL_API', 'ADMIN_API', 'AGENT_API', 'TASK')
    );

CREATE TABLE seckill_event_processing (
    processing_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    stream_key VARCHAR(255) NOT NULL,
    stream_entry_id VARCHAR(64) NOT NULL,
    reservation_no VARCHAR(64) NULL,
    activity_id BIGINT NULL,
    user_id BIGINT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    order_id VARCHAR(50) NULL,
    compensation_id VARCHAR(64) NULL,
    reason_code VARCHAR(64) NULL,
    last_error VARCHAR(512) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (processing_id),
    UNIQUE KEY uk_seckill_processing_event (event_id),
    UNIQUE KEY uk_seckill_processing_stream_entry (stream_key, stream_entry_id),
    CONSTRAINT ck_seckill_processing_status CHECK (
        status IN (
            'RETRYING',
            'ORDER_CREATED',
            'COMPENSATING',
            'COMPENSATED',
            'QUARANTINED',
            'MANUAL_REVIEW'
        )
    ),
    CONSTRAINT ck_seckill_processing_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_seckill_processing_version CHECK (version >= 0),
    CONSTRAINT ck_seckill_processing_payload_hash CHECK (
        payload_hash REGEXP '^[0-9a-f]{64}$'
    ),
    KEY idx_seckill_processing_retry (status, next_attempt_at, processing_id),
    KEY idx_seckill_processing_reservation (reservation_no, status, processing_id),
    KEY idx_seckill_processing_manual (status, updated_at, processing_id),
    KEY idx_seckill_processing_pending (stream_key, status, updated_at, stream_entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE seckill_reconciliation_issue (
    issue_id BIGINT NOT NULL AUTO_INCREMENT,
    issue_key CHAR(64) NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    activity_id BIGINT NULL,
    reservation_no VARCHAR(64) NULL,
    stream_key VARCHAR(255) NULL,
    stream_entry_id VARCHAR(64) NULL,
    evidence_version INT NOT NULL DEFAULT 1,
    evidence_summary JSON NOT NULL,
    occurrences INT NOT NULL DEFAULT 1,
    first_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (issue_id),
    UNIQUE KEY uk_seckill_reconciliation_issue_key (issue_key),
    CONSTRAINT ck_seckill_reconciliation_issue_status CHECK (
        status IN ('OPEN', 'RESOLVED', 'IGNORED')
    ),
    CONSTRAINT ck_seckill_reconciliation_severity CHECK (
        severity IN ('INFO', 'WARNING', 'CRITICAL')
    ),
    CONSTRAINT ck_seckill_reconciliation_evidence_version CHECK (evidence_version > 0),
    CONSTRAINT ck_seckill_reconciliation_occurrences CHECK (occurrences > 0),
    CONSTRAINT ck_seckill_reconciliation_version CHECK (version >= 0),
    KEY idx_seckill_reconciliation_open (
        status, severity, last_seen_at, issue_id
    ),
    KEY idx_seckill_reconciliation_reservation (
        reservation_no, status, issue_id
    ),
    KEY idx_seckill_reconciliation_activity (
        activity_id, status, issue_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE seckill_reconciliation_checkpoint (
    checkpoint_name VARCHAR(64) NOT NULL,
    cursor_value VARCHAR(255) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (checkpoint_name),
    CONSTRAINT ck_seckill_reconciliation_checkpoint_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
