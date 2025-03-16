/*USE hotShop;

DROP TABLE IF EXISTS `order_item`;
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS `product`;
DROP TABLE IF EXISTS `user`;
DROP PROCEDURE IF EXISTS `delete_and_reset`;

DROP DATABASE hotShop;

CREATE DATABASE hotShop;*/

USE hotShop;

-- 用户表
CREATE TABLE `user` (
                        `user_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                        `username` VARCHAR(50) UNIQUE NOT NULL,
                        `password` VARCHAR(100) NOT NULL,
                        `email` VARCHAR(100) UNIQUE,
                        `role` VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER' COMMENT '用户角色（USER/ADMIN）',
                        `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_user_role ON `user`(role);
CREATE INDEX idx_user_created ON `user`(created_at);

-- 商品表
CREATE TABLE `product` (
                           `product_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                           `name` VARCHAR(200) NOT NULL,
                           `price` DECIMAL(10,2) NOT NULL,
                           `stock` INT NOT NULL,
                           `category` VARCHAR(50),
                           `description` TEXT,
                           `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `order` (
                         `order_id` VARCHAR(50) PRIMARY KEY COMMENT '订单号（如 UUID）',
                         `user_id` BIGINT NOT NULL COMMENT '用户ID',
                         `total_amount` DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
                         `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '订单状态（PENDING/PAID/SHIPPED/COMPLETED/CANCELED）',
                         `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                         FOREIGN KEY (`user_id`) REFERENCES `user`(`user_id`)  -- 外键关联用户表
);
CREATE INDEX idx_order_status_created ON `order`(status, created_at);

CREATE TABLE `order_item` (
                              `order_item_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '订单项ID',
                              `order_id` VARCHAR(50) NOT NULL COMMENT '订单号',
                              `product_id` BIGINT NOT NULL COMMENT '商品ID',
                              `quantity` INT NOT NULL COMMENT '购买数量',
                              `price` DECIMAL(10,2) NOT NULL COMMENT '商品单价',
                              FOREIGN KEY (`order_id`) REFERENCES `order`(`order_id`),      -- 外键关联订单表
                              FOREIGN KEY (`product_id`) REFERENCES `product`(`product_id`)  -- 外键关联商品表
);

DELIMITER //
CREATE PROCEDURE delete_and_reset(IN table_name VARCHAR(255))
BEGIN
    SET @s = CONCAT('ALTER TABLE ', table_name, ' AUTO_INCREMENT = 1;');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
END; //
DELIMITER ;

INSERT INTO `user` (user_id, username, password, role, created_at) VALUES
    (1 ,'admin', '$2a$10$ZRt.NDqicRWiXIh3guRV5.5QDOn6FpzhZIHiRGJhjvrpve7H/TekC', 'ROLE_ADMIN', '2024-10-01 10:00:00');

INSERT INTO product (product_id, name, price, stock, category, description, created_at) VALUES
    (1, 'Product 1', 100.50, 100, 'Electronics', 'This is product 1', '2024-10-01 10:00:00'),
    (2, 'Product 2', 200.75, 200, 'Clothing', 'This is product 2', '2024-10-02 11:00:00'),
    (3, 'Product 3', 300.99, 300, 'Home & Garden', 'This is product 3', '2024-10-03 12:00:00'),
    (4, 'Product 4', 400.45, 400, 'Electronics', 'This is product 4', '2024-10-04 13:00:00'),
    (5, 'Product 5', 500.25, 500, 'Clothing', 'This is product 5', '2024-10-05 14:00:00'),
    (6, 'Product 6', 600.50, 600, 'Home & Garden', 'This is product 6', '2024-10-06 15:00:00'),
    (7, 'Product 7', 700.75, 700, 'Electronics', 'This is product 7', '2024-10-07 16:00:00'),
    (8, 'Product 8', 800.99, 800, 'Clothing', 'This is product 8', '2024-10-08 17:00:00'),
    (9, 'Product 9', 900.45, 900, 'Home & Garden', 'This is product 9', '2024-10-09 18:00:00'),
    (10, 'Product 10', 1000.25, 1000, 'Electronics', 'This is product 10', '2024-10-10 19:00:00'),
    (11, 'Product 11', 1100.50, 1100, 'Clothing', 'This is product 11', '2024-10-11 20:00:00'),
    (12, 'Product 12', 1200.75, 1200, 'Home & Garden', 'This is product 12', '2024-10-12 21:00:00'),
    (13, 'Product 13', 1300.99, 1300, 'Electronics', 'This is product 13', '2024-10-13 22:00:00'),
    (14, 'Product 14', 1400.45, 1400, 'Clothing', 'This is product 14', '2024-10-14 23:00:00'),
    (15, 'Product 15', 1500.25, 1500, 'Home & Garden', 'This is product 15', '2024-10-15 00:00:00'),
    (16, 'Product 16', 1600.50, 1600, 'Electronics', 'This is product 16', '2024-10-16 01:00:00'),
    (17, 'Product 17', 1700.75, 1700, 'Clothing', 'This is product 17', '2024-10-17 02:00:00'),
    (18, 'Product 18', 1800.99, 1800, 'Home & Garden', 'This is product 18', '2024-10-18 03:00:00'),
    (19, 'Product 19', 1900.45, 1900, 'Electronics', 'This is product 19', '2024-10-19 04:00:00'),
    (20, 'Product 20', 2000.25, 2000, 'Clothing', 'This is product 20', '2024-10-20 05:00:00'),
    (21, 'Product 21', 2100.50, 2100, 'Home & Garden', 'This is product 21', '2024-10-21 06:00:00'),
    (22, 'Product 22', 2200.75, 2200, 'Electronics', 'This is product 22', '2024-10-22 07:00:00'),
    (23, 'Product 23', 2300.99, 2300, 'Clothing', 'This is product 23', '2024-10-23 08:00:00'),
    (24, 'Product 24', 2400.45, 2400, 'Home & Garden', 'This is product 24', '2024-10-24 09:00:00'),
    (25, 'Product 25', 2500.25, 2500, 'Electronics', 'This is product 25', '2024-10-25 10:00:00'),
    (26, 'Product 26', 2600.50, 2600, 'Clothing', 'This is product 26', '2024-10-26 11:00:00'),
    (27, 'Product 27', 2700.75, 2700, 'Home & Garden', 'This is product 27', '2024-10-27 12:00:00'),
    (28, 'Product 28', 2800.99, 2800, 'Electronics', 'This is product 28', '2024-10-28 13:00:00'),
    (29, 'Product 29', 2900.45, 2900, 'Clothing', 'This is product 29', '2024-10-29 14:00:00'),
    (30, 'Product 30', 3000.25, 3000, 'Home & Garden', 'This is product 30', '2024-10-30 15:00:00'),
    (31, 'Product 31', 3100.50, 3100, 'Electronics', 'This is product 31', '2024-10-31 16:00:00'),
    (32, 'Product 32', 3200.75, 3200, 'Clothing', 'This is product 32', '2024-11-01 17:00:00'),
    (33, 'Product 33', 3300.99, 3300, 'Home & Garden', 'This is product 33', '2024-11-02 18:00:00'),
    (34, 'Product 34', 3400.45, 3400, 'Electronics', 'This is product 34', '2024-11-03 19:00:00'),
    (35, 'Product 35', 3500.25, 3500, 'Clothing', 'This is product 35', '2024-11-04 20:00:00'),
    (36, 'Product 36', 3600.50, 3600, 'Home & Garden', 'This is product 36', '2024-11-05 21:00:00'),
    (37, 'Product 37', 3700.75, 3700, 'Electronics', 'This is product 37', '2024-11-06 22:00:00'),
    (38, 'Product 38', 3800.99, 3800, 'Clothing', 'This is product 38', '2024-11-07 23:00:00'),
    (39, 'Product 39', 3900.45, 3900, 'Home & Garden', 'This is product 39', '2024-11-08 00:00:00'),
    (40, 'Product 40', 4000.25, 4000, 'Electronics', 'This is product 40', '2024-11-09 01:00:00'),
    (41, 'Product 41', 4100.50, 4100, 'Clothing', 'This is product 41', '2024-11-10 02:00:00'),
    (42, 'Product 42', 4200.75, 4200, 'Home & Garden', 'This is product 42', '2024-11-11 03:00:00'),
    (43, 'Product 43', 4300.99, 4300, 'Electronics', 'This is product 43', '2024-11-12 04:00:00'),
    (44, 'Product 44', 4400.45, 4400, 'Clothing', 'This is product 44', '2024-11-13 05:00:00'),
    (45, 'Product 45', 4500.25, 4500, 'Home & Garden', 'This is product 45', '2024-11-14 06:00:00'),
    (46, 'Product 46', 4600.50, 4600, 'Electronics', 'This is product 46', '2024-11-15 07:00:00'),
    (47, 'Product 47', 4700.75, 4700, 'Clothing', 'This is product 47', '2024-11-16 08:00:00'),
    (48, 'Product 48', 4800.99, 4800, 'Home & Garden', 'This is product 48', '2024-11-17 09:00:00'),
    (49, 'Product 49', 4900.45, 4900, 'Electronics', 'This is product 49', '2024-11-18 10:00:00'),
    (50, 'Product 50', 5000.25, 5000, 'Clothing', 'This is product 50', '2024-11-19 11:00:00'),
    (51, 'Product 51', 5100.50, 5100, 'Home & Garden', 'This is product 51', '2024-11-20 12:00:00'),
    (52, 'Product 52', 5200.75, 5200, 'Electronics', 'This is product 52', '2024-11-21 13:00:00'),
    (53, 'Product 53', 5300.99, 5300, 'Clothing', 'This is product 53', '2024-11-22 14:00:00'),
    (54, 'Product 54', 5400.45, 5400, 'Home & Garden', 'This is product 54', '2024-11-23 15:00:00'),
    (55, 'Product 55', 5500.25, 5500, 'Electronics', 'This is product 55', '2024-11-24 16:00:00'),
    (56, 'Product 56', 5600.50, 5600, 'Clothing', 'This is product 56', '2024-11-25 17:00:00'),
    (57, 'Product 57', 5700.75, 5700, 'Home & Garden', 'This is product 57', '2024-11-26 18:00:00'),
    (58, 'Product 58', 5800.99, 5800, 'Electronics', 'This is product 58', '2024-11-27 19:00:00'),
    (59, 'Product 59', 5900.45, 5900, 'Clothing', 'This is product 59', '2024-11-28 20:00:00'),
    (60, 'Product 60', 6000.25, 6000, 'Home & Garden', 'This is product 60', '2024-11-29 21:00:00'),
    (61, 'Product 61', 6100.50, 6100, 'Electronics', 'This is product 61', '2024-11-30 22:00:00'),
    (62, 'Product 62', 6200.75, 6200, 'Clothing', 'This is product 62', '2024-12-01 23:00:00'),
    (63, 'Product 63', 6300.99, 6300, 'Home & Garden', 'This is product 63', '2024-12-02 00:00:00'),
    (64, 'Product 64', 6400.45, 6400, 'Electronics', 'This is product 64', '2024-12-03 01:00:00'),
    (65, 'Product 65', 6500.25, 6500, 'Clothing', 'This is product 65', '2024-12-04 02:00:00'),
    (66, 'Product 66', 6600.50, 6600, 'Home & Garden', 'This is product 66', '2024-12-05 03:00:00'),
    (67, 'Product 67', 6700.75, 6700, 'Electronics', 'This is product 67', '2024-12-06 04:00:00'),
    (68, 'Product 68', 6800.99, 6800, 'Clothing', 'This is product 68', '2024-12-07 05:00:00'),
    (69, 'Product 69', 6900.45, 6900, 'Home & Garden', 'This is product 69', '2024-12-08 06:00:00'),
    (70, 'Product 70', 7000.25, 7000, 'Electronics', 'This is product 70', '2024-12-09 07:00:00'),
    (71, 'Product 71', 7100.50, 7100, 'Clothing', 'This is product 71', '2024-12-10 08:00:00'),
    (72, 'Product 72', 7200.75, 7200, 'Home & Garden', 'This is product 72', '2024-12-11 09:00:00'),
    (73, 'Product 73', 7300.99, 7300, 'Electronics', 'This is product 73', '2024-12-12 10:00:00'),
    (74, 'Product 74', 7400.45, 7400, 'Clothing', 'This is product 74', '2024-12-13 11:00:00'),
    (75, 'Product 75', 7500.25, 7500, 'Home & Garden', 'This is product 75', '2024-12-14 12:00:00'),
    (76, 'Product 76', 7600.50, 7600, 'Electronics', 'This is product 76', '2024-12-15 13:00:00'),
    (77, 'Product 77', 7700.75, 7700, 'Clothing', 'This is product 77', '2024-12-16 14:00:00'),
    (78, 'Product 78', 7800.99, 7800, 'Home & Garden', 'This is product 78', '2024-12-17 15:00:00'),
    (79, 'Product 79', 7900.45, 7900, 'Electronics', 'This is product 79', '2024-12-18 16:00:00'),
    (80, 'Product 80', 8000.25, 8000, 'Clothing', 'This is product 80', '2024-12-19 17:00:00'),
    (81, 'Product 81', 8100.50, 8100, 'Home & Garden', 'This is product 81', '2024-12-20 18:00:00'),
    (82, 'Product 82', 8200.75, 8200, 'Electronics', 'This is product 82', '2024-12-21 19:00:00'),
    (83, 'Product 83', 8300.99, 8300, 'Clothing', 'This is product 83', '2024-12-22 20:00:00'),
    (84, 'Product 84', 8400.45, 8400, 'Home & Garden', 'This is product 84', '2024-12-23 21:00:00'),
    (85, 'Product 85', 8500.25, 8500, 'Electronics', 'This is product 85', '2024-12-24 22:00:00'),
    (86, 'Product 86', 8600.50, 8600, 'Clothing', 'This is product 86', '2024-12-25 23:00:00'),
    (87, 'Product 87', 8700.75, 8700, 'Home & Garden', 'This is product 87', '2024-12-26 00:00:00'),
    (88, 'Product 88', 8800.99, 8800, 'Electronics', 'This is product 88', '2024-12-27 01:00:00'),
    (89, 'Product 89', 8900.45, 8900, 'Clothing', 'This is product 89', '2024-12-28 02:00:00'),
    (90, 'Product 90', 9000.25, 9000, 'Home & Garden', 'This is product 90', '2024-12-29 03:00:00'),
    (91, 'Product 91', 9100.50, 9100, 'Electronics', 'This is product 91', '2024-12-30 04:00:00'),
    (92, 'Product 92', 9200.75, 9200, 'Clothing', 'This is product 92', '2024-12-31 05:00:00'),
    (93, 'Product 93', 9300.99, 9300, 'Home & Garden', 'This is product 93', '2024-01-01 06:00:00'),
    (94, 'Product 94', 9400.45, 9400, 'Electronics', 'This is product 94', '2024-01-02 07:00:00'),
    (95, 'Product 95', 9500.25, 9500, 'Clothing', 'This is product 95', '2024-01-03 08:00:00'),
    (96, 'Product 96', 9600.50, 9600, 'Home & Garden', 'This is product 96', '2024-01-04 09:00:00'),
    (97, 'Product 97', 9700.75, 9700, 'Electronics', 'This is product 97', '2024-01-05 10:00:00'),
    (98, 'Product 98', 9800.99, 9800, 'Clothing', 'This is product 98', '2024-01-06 11:00:00'),
    (99, 'Product 99', 9900.45, 9900, 'Home & Garden', 'This is product 99', '2024-01-07 12:00:00'),
    (100, 'Product 100', 10000.25, 10000, 'Electronics', 'This is product 100', '2024-01-08 13:00:00');
