CREATE TABLE app_user (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    email VARCHAR(254) NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_app_user_username (username),
    UNIQUE KEY uk_app_user_email (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_app_user_version CHECK (version >= 0),
    KEY idx_app_user_role_status_created (role, status, created_at),
    KEY idx_app_user_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE catalog_product (
    product_id BIGINT NOT NULL AUTO_INCREMENT,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    stock INT NOT NULL,
    category VARCHAR(64) NULL,
    description TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_catalog_product_sku (sku),
    CONSTRAINT ck_catalog_product_price CHECK (price >= 0),
    CONSTRAINT ck_catalog_product_stock CHECK (stock >= 0),
    CONSTRAINT ck_catalog_product_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_catalog_product_version CHECK (version >= 0),
    KEY idx_catalog_product_status_category_id (status, category, product_id),
    KEY idx_catalog_product_category_price_id (category, price, product_id),
    KEY idx_catalog_product_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE flash_sale_activity (
    activity_id BIGINT NOT NULL AUTO_INCREMENT,
    activity_code VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    sale_price DECIMAL(19, 2) NOT NULL,
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    per_user_limit INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (activity_id),
    UNIQUE KEY uk_flash_sale_activity_code (activity_code),
    CONSTRAINT ck_activity_sale_price CHECK (sale_price >= 0),
    CONSTRAINT ck_activity_total_stock CHECK (total_stock >= 0),
    CONSTRAINT ck_activity_available_stock CHECK (available_stock >= 0 AND available_stock <= total_stock),
    CONSTRAINT ck_activity_per_user_limit CHECK (per_user_limit > 0),
    CONSTRAINT ck_activity_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED', 'CANCELED')),
    CONSTRAINT ck_activity_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_activity_version CHECK (version >= 0),
    KEY idx_activity_product_status_time (product_id, status, starts_at, ends_at),
    KEY idx_activity_status_window (status, starts_at, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sale_reservation (
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_no VARCHAR(64) NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    reserved_amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    order_id VARCHAR(50) NULL,
    expires_at DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    effective_slot TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('RESERVED', 'ORDER_CREATED', 'COMPENSATING') THEN 1
            ELSE NULL
        END
    ) STORED,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_sale_reservation_no (reservation_no),
    UNIQUE KEY uk_sale_reservation_effective (activity_id, user_id, effective_slot),
    CONSTRAINT ck_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservation_amount CHECK (reserved_amount >= 0),
    CONSTRAINT ck_reservation_status CHECK (
        status IN ('RESERVED', 'ORDER_CREATED', 'COMPENSATING', 'COMPENSATED', 'EXPIRED', 'CANCELED')
    ),
    CONSTRAINT ck_reservation_version CHECK (version >= 0),
    KEY idx_reservation_user_created (user_id, created_at),
    KEY idx_reservation_activity_status_created (activity_id, status, created_at),
    KEY idx_reservation_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sales_order (
    order_id VARCHAR(50) NOT NULL,
    user_id BIGINT NOT NULL,
    reservation_id BIGINT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_sales_order_reservation (reservation_id),
    CONSTRAINT ck_sales_order_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_sales_order_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_sales_order_status CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_sales_order_version CHECK (version >= 0),
    KEY idx_sales_order_user_created (user_id, created_at, order_id),
    KEY idx_sales_order_status_created (status, created_at, order_id),
    KEY idx_sales_order_status_expires (status, expires_at, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sales_order_item (
    order_item_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(50) NOT NULL,
    product_id BIGINT NOT NULL,
    sku VARCHAR(64) NULL,
    product_name VARCHAR(200) NULL,
    quantity INT NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    line_amount DECIMAL(19, 2) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_item_id),
    UNIQUE KEY uk_sales_order_item_product (order_id, product_id),
    CONSTRAINT ck_sales_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sales_order_item_price CHECK (price >= 0),
    CONSTRAINT ck_sales_order_item_line_amount CHECK (line_amount >= 0 AND line_amount = price * quantity),
    KEY idx_sales_order_item_product_order (product_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_order (
    payment_id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(64) NOT NULL,
    order_id VARCHAR(50) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'MOCK',
    provider_transaction_no VARCHAR(128) NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_payment_order_no (payment_no),
    UNIQUE KEY uk_payment_order_order_provider (order_id, provider),
    UNIQUE KEY uk_payment_provider_transaction (provider, provider_transaction_no),
    CONSTRAINT ck_payment_order_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_order_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_order_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    CONSTRAINT ck_payment_order_version CHECK (version >= 0),
    KEY idx_payment_order_status_expires (status, expires_at, payment_id),
    KEY idx_payment_order_status_created (status, created_at, payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refresh_token (
    refresh_token_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    family_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    parent_token_id BIGINT NULL,
    issuer VARCHAR(64) NOT NULL,
    audience VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (refresh_token_id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    UNIQUE KEY uk_refresh_token_parent (parent_token_id),
    CONSTRAINT ck_refresh_token_hash CHECK (token_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_token_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED', 'REUSED')),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_token_not_self_parent CHECK (
        parent_token_id IS NULL OR parent_token_id <> refresh_token_id
    ),
    KEY idx_refresh_token_family_created (family_id, created_at),
    KEY idx_refresh_token_user_status_expiry (user_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    publish_attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_publish_attempts CHECK (publish_attempts >= 0),
    KEY idx_outbox_dispatch (status, available_at, outbox_id),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id, outbox_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE processed_event (
    consumer_name VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_name, event_id),
    KEY idx_processed_event_id (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NULL,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NULL,
    result VARCHAR(20) NOT NULL,
    request_id VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    state_summary JSON NOT NULL,
    PRIMARY KEY (audit_id),
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER', 'ADMIN', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    KEY idx_audit_occurred (occurred_at, audit_id),
    KEY idx_audit_actor_occurred (actor_type, actor_id, occurred_at),
    KEY idx_audit_resource_occurred (resource_type, resource_id, occurred_at),
    KEY idx_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
