-- Diagnostic only: execute in an EMPTY DISPOSABLE MySQL instance, never project DB.
-- Minimal schema: proves SQL overwrite/formula, not a full HTTP/Flyway reproduction.
CREATE DATABASE review;
USE review;
CREATE TABLE catalog_product (
    product_id BIGINT PRIMARY KEY,
    name VARCHAR(100), price DECIMAL(10,2), stock INT,
    category VARCHAR(100), description TEXT,
    deleted_at DATETIME(6), version BIGINT DEFAULT 0
);
INSERT INTO catalog_product(product_id,name,price,stock,category)
VALUES(1,'original',10,100,'test');
SET @stale_editor_stock=100;
-- ProductMapper.reduceStock with product=1 and quantity=1.
UPDATE catalog_product SET stock=stock-1
WHERE product_id=1 AND deleted_at IS NULL AND 1>0 AND stock>=1;
SELECT 'ordinary_purchase' AS phase,stock FROM catalog_product WHERE product_id=1;
-- Activity was loaded at stock 100, with no successful seckill reservations.
SELECT 'reconciliation_after_ordinary_purchase' AS phase,
       stock AS actual_stock,100-0 AS expected_by_current_formula,
       stock=100-0 AS equation_holds
FROM catalog_product WHERE product_id=1;
START TRANSACTION;
-- Same lock and absolute replacement used by backend product editing.
SELECT product_id FROM catalog_product
WHERE product_id=1 AND deleted_at IS NULL FOR UPDATE;
UPDATE catalog_product SET name='rename only',price=10,stock=@stale_editor_stock,
       category='test',description=''
WHERE product_id=1 AND deleted_at IS NULL;
COMMIT;
SELECT 'stale_admin_edit_after_committed_purchase' AS phase,
       stock AS actual_stock,99 AS correct_stock,version
FROM catalog_product WHERE product_id=1;
