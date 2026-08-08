ALTER TABLE payment_order
    DROP CHECK ck_payment_order_status,
    ADD CONSTRAINT ck_payment_order_status CHECK (
        status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CLOSED', 'LATE_SUCCEEDED')
    ),
    ADD KEY idx_payment_order_order_status (order_id, status, payment_id);

CREATE TABLE payment_callback_ledger (
    callback_ledger_id BIGINT NOT NULL AUTO_INCREMENT,
    callback_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    provider_transaction_no VARCHAR(128) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    receive_result VARCHAR(24) NOT NULL,
    business_result VARCHAR(32) NOT NULL,
    previous_status VARCHAR(20) NULL,
    new_status VARCHAR(20) NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (callback_ledger_id),
    UNIQUE KEY uk_payment_callback_id (callback_id),
    CONSTRAINT ck_payment_callback_id CHECK (
        callback_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_payment_callback_payload_hash CHECK (payload_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payment_callback_provider CHECK (provider = 'MOCK'),
    CONSTRAINT ck_payment_callback_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payment_callback_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_callback_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_payment_callback_receive CHECK (receive_result IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_payment_callback_business CHECK (
        business_result IN ('PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'PAYMENT_LATE_SUCCEEDED', 'IDEMPOTENT')
    ),
    KEY idx_payment_callback_payment (provider, payment_no, received_at, callback_ledger_id),
    KEY idx_payment_callback_result (business_result, processed_at, callback_ledger_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_callback_nonce (
    nonce_id BIGINT NOT NULL AUTO_INCREMENT,
    nonce_hash CHAR(64) NOT NULL,
    callback_id CHAR(36) NOT NULL,
    accepted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (nonce_id),
    UNIQUE KEY uk_payment_callback_nonce_hash (nonce_hash),
    CONSTRAINT ck_payment_callback_nonce_hash CHECK (nonce_hash REGEXP '^[0-9a-f]{64}$'),
    KEY idx_payment_callback_nonce_callback (callback_id, accepted_at, nonce_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE audit_log
    DROP CHECK ck_audit_source,
    ADD CONSTRAINT ck_audit_source CHECK (
        source IN ('PORTAL_API', 'ADMIN_API', 'AGENT_API', 'TASK', 'MOCK_PROVIDER')
    );
