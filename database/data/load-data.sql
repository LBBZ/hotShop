-- Deterministic performance-data entry point. This file is never a Flyway location.
-- Optional session inputs:
--   @hotshop_seed (default 42)
--   @hotshop_user_count (default 10000, maximum 100000)
--   @hotshop_product_count (default 1000, maximum 100000)
SET @hotshop_seed = COALESCE(@hotshop_seed, 42);
SET @hotshop_user_count = LEAST(COALESCE(@hotshop_user_count, 10000), 100000);
SET @hotshop_product_count = LEAST(COALESCE(@hotshop_product_count, 1000), 100000);

CREATE TEMPORARY TABLE hotshop_load_number (
    n INT NOT NULL PRIMARY KEY
);

INSERT INTO hotshop_load_number (n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000 + ten_thousands.n * 10000 + 1
FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands;

INSERT INTO app_user (
    username, password_hash, email, role, status, version, created_at, updated_at
)
SELECT
    CONCAT('load-', @hotshop_seed, '-user-', LPAD(n, 6, '0')),
    '$2a$10$7EqJtq98hPqEX7fNZaFWoO5ZQY0Qe2YbY5fQ7B4B0y6R1J8Q6R2aW',
    CONCAT('load-', @hotshop_seed, '-', LPAD(n, 6, '0'), '@hotshop.invalid'),
    'ROLE_USER',
    'ACTIVE',
    0,
    TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND,
    TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
FROM hotshop_load_number
WHERE n <= @hotshop_user_count
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO catalog_product (
    sku, name, price, stock, category, description, status, version, created_at, updated_at
)
SELECT
    CONCAT('LOAD-', @hotshop_seed, '-', LPAD(n, 6, '0')),
    CONCAT('压测商品 ', LPAD(n, 6, '0')),
    CAST(10 + MOD(n * 37 + @hotshop_seed, 100000) / 100 AS DECIMAL(19, 2)),
    100000,
    CONCAT('LOAD-CATEGORY-', MOD(n, 20)),
    CONCAT('Deterministic load record seed=', @hotshop_seed, ', n=', n),
    'ACTIVE',
    0,
    TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND,
    TIMESTAMP('2026-01-01 00:00:00') + INTERVAL n SECOND
FROM hotshop_load_number
WHERE n <= @hotshop_product_count
ON DUPLICATE KEY UPDATE
    price = VALUES(price),
    stock = VALUES(stock),
    status = 'ACTIVE',
    deleted_at = NULL;

DROP TEMPORARY TABLE hotshop_load_number;
