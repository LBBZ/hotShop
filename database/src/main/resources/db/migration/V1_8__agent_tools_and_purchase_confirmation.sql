CREATE TABLE purchase_draft (
    draft_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    parameter_digest CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    valid_until DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (draft_id),
    CONSTRAINT ck_purchase_draft_id CHECK (
        REGEXP_LIKE(
            draft_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_purchase_draft_user_id CHECK (user_id > 0),
    CONSTRAINT ck_purchase_draft_action CHECK (action_type = 'CREATE_ORDER'),
    CONSTRAINT ck_purchase_draft_digest CHECK (
        REGEXP_LIKE(parameter_digest, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_purchase_draft_status CHECK (
        status IN ('ACTIVE', 'CONFIRMATION_ISSUED', 'EXPIRED', 'CANCELLED')
    ),
    CONSTRAINT ck_purchase_draft_validity CHECK (valid_until > created_at),
    CONSTRAINT ck_purchase_draft_updated CHECK (updated_at >= created_at),
    KEY idx_purchase_draft_user_status_valid (
        user_id, status, valid_until, draft_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_draft_item (
    draft_id CHAR(36) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    product_name_snapshot VARCHAR(200) NOT NULL,
    unit_price_snapshot DECIMAL(19, 2) NOT NULL,
    line_amount_snapshot DECIMAL(19, 2) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (draft_id, product_id),
    CONSTRAINT ck_purchase_draft_item_draft_id CHECK (
        REGEXP_LIKE(
            draft_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_purchase_draft_item_product_id CHECK (product_id > 0),
    CONSTRAINT ck_purchase_draft_item_quantity CHECK (quantity BETWEEN 1 AND 100),
    CONSTRAINT ck_purchase_draft_item_name CHECK (
        CHAR_LENGTH(TRIM(product_name_snapshot)) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_purchase_draft_item_unit_price CHECK (unit_price_snapshot >= 0),
    CONSTRAINT ck_purchase_draft_item_line_amount CHECK (
        line_amount_snapshot >= 0
        AND line_amount_snapshot = unit_price_snapshot * quantity
    ),
    KEY idx_purchase_draft_item_product (product_id, draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_confirmation (
    confirmation_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    draft_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    parameter_digest CHAR(64) NOT NULL,
    parameters_json JSON NOT NULL,
    nonce CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ISSUED',
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    order_id VARCHAR(50) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (confirmation_id),
    UNIQUE KEY uk_purchase_confirmation_token_hash (token_hash),
    UNIQUE KEY uk_purchase_confirmation_draft (draft_id),
    UNIQUE KEY uk_purchase_confirmation_nonce (nonce),
    UNIQUE KEY uk_purchase_confirmation_order (order_id),
    CONSTRAINT ck_purchase_confirmation_id CHECK (
        REGEXP_LIKE(
            confirmation_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_purchase_confirmation_token_hash CHECK (
        REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_purchase_confirmation_draft_id CHECK (
        REGEXP_LIKE(
            draft_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_purchase_confirmation_user_id CHECK (user_id > 0),
    CONSTRAINT ck_purchase_confirmation_action CHECK (action_type = 'CREATE_ORDER'),
    CONSTRAINT ck_purchase_confirmation_digest CHECK (
        REGEXP_LIKE(parameter_digest, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_purchase_confirmation_parameters CHECK (
        JSON_SCHEMA_VALID(
            '{
                "type": "object",
                "additionalProperties": false,
                "required": ["items"],
                "properties": {
                    "items": {
                        "type": "array",
                        "minItems": 1,
                        "maxItems": 50,
                        "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "required": ["productId", "quantity"],
                            "properties": {
                                "productId": {"type": "integer", "minimum": 1},
                                "quantity": {"type": "integer", "minimum": 1, "maximum": 100}
                            }
                        }
                    }
                }
            }',
            parameters_json
        ) = 1
    ),
    CONSTRAINT ck_purchase_confirmation_nonce CHECK (
        REGEXP_LIKE(
            nonce,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_purchase_confirmation_status CHECK (
        status IN ('ISSUED', 'CONSUMED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_purchase_confirmation_validity CHECK (expires_at > issued_at),
    CONSTRAINT ck_purchase_confirmation_state CHECK (
        (status = 'ISSUED'
            AND consumed_at IS NULL AND revoked_at IS NULL AND order_id IS NULL)
        OR (status = 'CONSUMED'
            AND consumed_at IS NOT NULL AND revoked_at IS NULL AND order_id IS NOT NULL
            AND consumed_at >= issued_at AND consumed_at <= expires_at)
        OR (status = 'REVOKED'
            AND consumed_at IS NULL AND revoked_at IS NOT NULL AND order_id IS NULL
            AND revoked_at >= issued_at)
        OR (status = 'EXPIRED'
            AND consumed_at IS NULL AND revoked_at IS NULL AND order_id IS NULL)
    ),
    CONSTRAINT ck_purchase_confirmation_updated CHECK (updated_at >= issued_at),
    KEY idx_purchase_confirmation_user_status_expiry (
        user_id, status, expires_at, confirmation_id
    ),
    KEY idx_purchase_confirmation_draft_user (draft_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_configuration_draft (
    configuration_draft_id CHAR(36) NOT NULL,
    administrator_id BIGINT NOT NULL,
    configuration_key VARCHAR(64) NOT NULL,
    proposed_value JSON NOT NULL,
    reason VARCHAR(500) NOT NULL,
    risk_level VARCHAR(8) NOT NULL DEFAULT 'LOW',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (configuration_draft_id),
    CONSTRAINT ck_agent_configuration_draft_id CHECK (
        REGEXP_LIKE(
            configuration_draft_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            'c'
        )
    ),
    CONSTRAINT ck_agent_configuration_draft_admin CHECK (administrator_id > 0),
    CONSTRAINT ck_agent_configuration_draft_key CHECK (
        configuration_key IN (
            'AGENT_RESPONSE_STYLE',
            'AGENT_SUMMARY_WINDOW',
            'AGENT_TOOL_RESULT_LIMIT'
        )
    ),
    CONSTRAINT ck_agent_configuration_draft_value CHECK (
        (configuration_key = 'AGENT_RESPONSE_STYLE'
            AND JSON_TYPE(proposed_value) = 'STRING'
            AND JSON_UNQUOTE(proposed_value) IN ('CONCISE', 'BALANCED', 'DETAILED'))
        OR (configuration_key = 'AGENT_SUMMARY_WINDOW'
            AND JSON_TYPE(proposed_value) = 'INTEGER'
            AND CAST(JSON_UNQUOTE(proposed_value) AS UNSIGNED) BETWEEN 1 AND 24)
        OR (configuration_key = 'AGENT_TOOL_RESULT_LIMIT'
            AND JSON_TYPE(proposed_value) = 'INTEGER'
            AND CAST(JSON_UNQUOTE(proposed_value) AS UNSIGNED) BETWEEN 1 AND 100)
    ),
    CONSTRAINT ck_agent_configuration_draft_reason CHECK (
        CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500
    ),
    CONSTRAINT ck_agent_configuration_draft_risk CHECK (risk_level = 'LOW'),
    CONSTRAINT ck_agent_configuration_draft_status CHECK (
        status IN ('DRAFT', 'CANCELLED', 'APPLIED')
    ),
    CONSTRAINT ck_agent_configuration_draft_updated CHECK (updated_at >= created_at),
    KEY idx_agent_configuration_draft_admin_status (
        administrator_id, status, created_at, configuration_draft_id
    ),
    KEY idx_agent_configuration_draft_key_status (
        configuration_key, status, created_at, configuration_draft_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
