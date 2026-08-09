CREATE TABLE order_purchase_intent (
    purchase_intent_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    order_id VARCHAR(50) NULL,
    request_id VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (purchase_intent_id),
    UNIQUE KEY uk_order_purchase_intent_user_key (user_id, idempotency_key_hash),
    UNIQUE KEY uk_order_purchase_intent_order (order_id),
    CONSTRAINT ck_order_purchase_intent_user_id CHECK (user_id > 0),
    CONSTRAINT ck_order_purchase_intent_key_hash CHECK (
        REGEXP_LIKE(idempotency_key_hash, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_order_purchase_intent_fingerprint CHECK (
        REGEXP_LIKE(request_fingerprint, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_order_purchase_intent_status CHECK (
        status IN ('PROCESSING', 'ORDER_CREATED')
    ),
    CONSTRAINT ck_order_purchase_intent_state CHECK (
        (status = 'PROCESSING' AND order_id IS NULL)
        OR (status = 'ORDER_CREATED' AND order_id IS NOT NULL)
    ),
    CONSTRAINT ck_order_purchase_intent_request_id CHECK (
        request_id IS NULL
        OR request_id REGEXP '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    KEY idx_order_purchase_intent_user_created (user_id, created_at, purchase_intent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_transaction_timeline (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    reservation_no VARCHAR(64) NULL,
    order_id VARCHAR(50) NULL,
    event_type VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NULL,
    traceparent VARCHAR(55) NULL,
    tracestate VARCHAR(512) NULL,
    detail_code VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_user_timeline_fact (resource_type, resource_id, event_type),
    CONSTRAINT ck_user_timeline_user_id CHECK (user_id > 0),
    CONSTRAINT ck_user_timeline_resource_type CHECK (
        resource_type IN ('RESERVATION', 'ORDER')
    ),
    CONSTRAINT ck_user_timeline_event_type CHECK (
        event_type IN (
            'RESERVED', 'ORDER_CREATED', 'PENDING_PAYMENT', 'PAYMENT_FAILED',
            'PAID', 'CLOSED', 'CANCELED', 'COMPENSATING', 'COMPENSATED',
            'LATE_SUCCEEDED'
        )
    ),
    CONSTRAINT ck_user_timeline_resource_identity CHECK (
        (resource_type = 'ORDER'
            AND order_id = resource_id
            AND reservation_no IS NULL)
        OR (resource_type = 'RESERVATION'
            AND reservation_no = resource_id)
    ),
    CONSTRAINT ck_user_timeline_order_created CHECK (
        event_type <> 'ORDER_CREATED' OR order_id IS NOT NULL
    ),
    CONSTRAINT ck_user_timeline_request_id CHECK (
        request_id IS NULL
        OR request_id REGEXP '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_user_timeline_traceparent CHECK (
        traceparent IS NULL
        OR (
            REGEXP_LIKE(
                traceparent,
                '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$',
                'c'
            )
            AND SUBSTRING(traceparent, 1, 2) <> 'ff'
            AND SUBSTRING(traceparent, 4, 32) <> '00000000000000000000000000000000'
            AND SUBSTRING(traceparent, 37, 16) <> '0000000000000000'
        )
    ),
    KEY idx_user_timeline_user_event (user_id, event_id),
    KEY idx_user_timeline_resource_stream (user_id, resource_type, resource_id, event_id),
    KEY idx_user_timeline_reservation_event (reservation_no, event_id),
    KEY idx_user_timeline_order_event (order_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
