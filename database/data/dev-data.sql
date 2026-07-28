-- Deterministic local-development data. This file is never a Flyway location.
-- Re-running it converges the named records without changing production migrations.
INSERT INTO app_user (
    user_id, username, password_hash, email, role, status, version, created_at, updated_at
) VALUES
    (900001, 'dev-admin', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5ZQY0Qe2YbY5fQ7B4B0y6R1J8Q6R2aW',
     'dev-admin@hotshop.invalid', 'ROLE_ADMIN', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000'),
    (900002, 'dev-user', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5ZQY0Qe2YbY5fQ7B4B0y6R1J8Q6R2aW',
     'dev-user@hotshop.invalid', 'ROLE_USER', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000')
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    email = VALUES(email),
    role = VALUES(role),
    status = VALUES(status),
    deleted_at = NULL,
    updated_at = VALUES(updated_at);

INSERT INTO catalog_product (
    product_id, sku, name, price, stock, category, description, status, version, created_at, updated_at
) VALUES
    (900001, 'DEV-PHONE-001', '开发数据手机', 1999.00, 100, 'Electronics',
     'Deterministic local development record', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000'),
    (900002, 'DEV-SHIRT-001', '开发数据衬衫', 99.00, 200, 'Clothing',
     'Deterministic local development record', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    price = VALUES(price),
    stock = VALUES(stock),
    category = VALUES(category),
    description = VALUES(description),
    status = VALUES(status),
    deleted_at = NULL,
    updated_at = VALUES(updated_at);

INSERT INTO flash_sale_activity (
    activity_id, activity_code, product_id, sale_price, total_stock, available_stock,
    per_user_limit, status, starts_at, ends_at, version, created_at, updated_at
) VALUES (
    900001, 'DEV-ACTIVITY-001', 900001, 1599.00, 50, 50,
    1, 'SCHEDULED', '2030-01-01 00:00:00.000000', '2030-01-02 00:00:00.000000',
    0, '2026-01-01 00:00:00.000000', '2026-01-01 00:00:00.000000'
)
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id),
    sale_price = VALUES(sale_price),
    total_stock = VALUES(total_stock),
    available_stock = VALUES(available_stock),
    per_user_limit = VALUES(per_user_limit),
    status = VALUES(status),
    starts_at = VALUES(starts_at),
    ends_at = VALUES(ends_at),
    updated_at = VALUES(updated_at);
