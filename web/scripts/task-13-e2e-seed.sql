-- Deterministic TASK-13 Compose seed. This file is not a Flyway location.
-- Password: Task13Admin!2026 (local Compose verification only).
INSERT INTO app_user (
    user_id, username, password_hash, email, role, status,
    version, created_at, updated_at
) VALUES (
    913000, 'task13-admin',
    '$2a$10$pCwsASxqsJNlw9bsdoht4eGkKW4nqR3MyGJxjSUaiPYVXKljXQmqS',
    'task13-admin@hotshop.invalid', 'ROLE_ADMIN', 'ACTIVE',
    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash), email = VALUES(email),
    role = 'ROLE_ADMIN', status = 'ACTIVE', deleted_at = NULL,
    updated_at = UTC_TIMESTAMP(6);

INSERT INTO catalog_product (
    product_id, sku, name, price, stock, category, description,
    status, version, created_at, updated_at
) VALUES (
    913001, 'TASK13-DEMO-001', '高热交易收音机', 299.00, 50,
    '音频', '用于真实用户交易链路验证的确定性中文商品',
    'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), price = VALUES(price), stock = VALUES(stock),
    category = VALUES(category), description = VALUES(description),
    status = 'ACTIVE', deleted_at = NULL, updated_at = UTC_TIMESTAMP(6);

INSERT INTO flash_sale_activity (
    activity_id, activity_code, product_id, sale_price, total_stock,
    available_stock, per_user_limit, status, starts_at, ends_at,
    version, created_at, updated_at
) VALUES (
    913001, 'TASK13-LIVE-001', 913001, 199.00, 20,
    20, 1, 'ACTIVE',
    UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE,
    UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE,
    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id), sale_price = VALUES(sale_price),
    total_stock = VALUES(total_stock), available_stock = VALUES(available_stock),
    per_user_limit = VALUES(per_user_limit), status = 'ACTIVE',
    starts_at = UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE,
    ends_at = UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE,
    version = 0, updated_at = UTC_TIMESTAMP(6);

INSERT INTO flash_sale_activity (
    activity_id, activity_code, product_id, sale_price, total_stock,
    available_stock, per_user_limit, status, starts_at, ends_at,
    version, created_at, updated_at
) VALUES
(
    913002, 'TASK13-SOLD-OUT', 913001, 188.00, 1,
    0, 1, 'ACTIVE',
    UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE,
    UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE,
    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
),
(
    913003, 'TASK13-EXPIRING', 913001, 177.00, 1,
    1, 1, 'ACTIVE',
    UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE,
    UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,
    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id), sale_price = VALUES(sale_price),
    total_stock = VALUES(total_stock), available_stock = VALUES(available_stock),
    per_user_limit = VALUES(per_user_limit), status = 'ACTIVE',
    starts_at = VALUES(starts_at), ends_at = VALUES(ends_at),
    version = 0, updated_at = UTC_TIMESTAMP(6);
