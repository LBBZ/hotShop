DELIMITER $$
CREATE PROCEDURE hotshop_take_over_legacy_tables()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'user'
    ) THEN
        INSERT INTO app_user (
            user_id, username, password_hash, email, role, status, version, created_at, updated_at
        )
        SELECT
            user_id, username, password, email, role, 'ACTIVE', 0,
            COALESCE(created_at, CURRENT_TIMESTAMP(6)),
            COALESCE(created_at, CURRENT_TIMESTAMP(6))
        FROM `user`;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'product'
    ) THEN
        INSERT INTO catalog_product (
            product_id, sku, name, price, stock, category, description, status, version, created_at, updated_at
        )
        SELECT
            product_id, CONCAT('LEGACY-', product_id), name, price, stock, category, description,
            'ACTIVE', 0,
            COALESCE(created_at, CURRENT_TIMESTAMP(6)),
            COALESCE(created_at, CURRENT_TIMESTAMP(6))
        FROM product;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'order'
    ) THEN
        INSERT INTO sales_order (
            order_id, user_id, total_amount, currency, status, version, created_at, updated_at
        )
        SELECT
            order_id, user_id, total_amount, 'CNY', status, 0,
            COALESCE(created_at, CURRENT_TIMESTAMP(6)),
            COALESCE(created_at, CURRENT_TIMESTAMP(6))
        FROM `order`;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'order_item'
    ) THEN
        INSERT INTO sales_order_item (
            order_item_id, order_id, product_id, quantity, price, line_amount, created_at
        )
        SELECT
            order_item_id, order_id, product_id, quantity, price, price * quantity, CURRENT_TIMESTAMP(6)
        FROM order_item;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'order_item'
    ) THEN
        DROP TABLE order_item;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'order'
    ) THEN
        DROP TABLE `order`;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'product'
    ) THEN
        DROP TABLE product;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'user'
    ) THEN
        DROP TABLE `user`;
    END IF;
END$$
DELIMITER ;

CALL hotshop_take_over_legacy_tables();
DROP PROCEDURE hotshop_take_over_legacy_tables;
DROP PROCEDURE IF EXISTS delete_and_reset;
