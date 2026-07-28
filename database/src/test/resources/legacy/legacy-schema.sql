CREATE TABLE `user` (
    user_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    email VARCHAR(100) NULL UNIQUE,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME NULL
);

CREATE TABLE product (
    product_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    stock INT NOT NULL,
    category VARCHAR(50) NULL,
    description TEXT NULL,
    created_at DATETIME NULL
);

CREATE TABLE `order` (
    order_id VARCHAR(50) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME NULL
);

CREATE TABLE order_item (
    order_item_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL
);

INSERT INTO `user` (user_id, username, password, email, role, created_at)
VALUES (1, 'legacy-admin', 'legacy-hash', NULL, 'ROLE_ADMIN', '2024-10-01 10:00:00');

INSERT INTO product (product_id, name, price, stock, category, description, created_at)
VALUES (1, 'Legacy product', 100.50, 10, 'Legacy', 'Legacy record', '2024-10-01 10:00:00');

INSERT INTO `order` (order_id, user_id, total_amount, status, created_at)
VALUES ('legacy-order', 1, 100.50, 'PENDING', '2024-10-01 10:00:00');

INSERT INTO order_item (order_item_id, order_id, product_id, quantity, price)
VALUES (1, 'legacy-order', 1, 1, 100.50);
