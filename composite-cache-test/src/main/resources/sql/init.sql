-- ========================================
-- Composite Cache Test 数据库初始化脚本
-- ========================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS composite_cache_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE composite_cache_test;

-- 创建用户表
CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username    VARCHAR(50) NOT NULL COMMENT '用户名',
    email       VARCHAR(100) COMMENT '邮箱',
    age         INT COMMENT '年龄',
    address     VARCHAR(200) COMMENT '地址',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username),
    KEY idx_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';

-- 插入用户测试数据
INSERT INTO t_user (username, email, age, address)
VALUES ('Alice', 'alice@example.com', 25, 'Beijing'),
       ('Bob', 'bob@example.com', 30, 'Shanghai'),
       ('Charlie', 'charlie@example.com', 28, 'Guangzhou'),
       ('David', 'david@example.com', 35, 'Shenzhen'),
       ('Eve', 'eve@example.com', 22, 'Hangzhou'),
       ('Frank', 'frank@example.com', 27, 'Chengdu'),
       ('Grace', 'grace@example.com', 29, 'Wuhan'),
       ('Henry', 'henry@example.com', 31, 'Xian'),
       ('Ivy', 'ivy@example.com', 24, 'Nanjing'),
       ('Jack', 'jack@example.com', 33, 'Chongqing');


-- 创建订单表
CREATE TABLE IF NOT EXISTS `t_order`
(
    `id`               BIGINT(20)     NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    `order_no`         VARCHAR(50)    NOT NULL COMMENT '订单编号',
    `user_id`          BIGINT(20)     NOT NULL COMMENT '用户ID',
    `product_id`       BIGINT(20)     NOT NULL COMMENT '商品ID',
    `quantity`         INT(11)        NOT NULL DEFAULT 1 COMMENT '商品数量',
    `total_amount`     DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    `status`           TINYINT(4)     NOT NULL DEFAULT 0 COMMENT '订单状态：0-待支付，1-已支付，2-已发货，3-已完成，4-已取消',
    `shipping_address` VARCHAR(200) COMMENT '收货地址',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单表';

-- 插入订单测试数据
INSERT INTO `t_order` (`order_no`, `user_id`, `product_id`, `quantity`, `total_amount`, `status`, `shipping_address`,
                       `create_time`, `update_time`)
VALUES ('ORD20260125001', 1, 1, 2, 5998.00, 1, '北京市朝阳区xxx街道xxx号', NOW(), NOW()),
       ('ORD20260125002', 1, 2, 1, 4999.00, 1, '北京市朝阳区xxx街道xxx号', NOW(), NOW()),
       ('ORD20260125010', 5, 4, 3, 597.00, 1, '杭州市西湖区xxx街xxx号', NOW(), NOW());


-- 创建商品表
CREATE TABLE IF NOT EXISTS `t_product`
(
    `id`           BIGINT(20)     NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '商品ID',
    `product_name` VARCHAR(100)   NOT NULL COMMENT '商品名称',
    `category`     VARCHAR(50)    NOT NULL COMMENT '商品分类',
    `price`        DECIMAL(10, 2) NOT NULL COMMENT '商品价格',
    `stock`        INT(11)        NOT NULL DEFAULT 0 COMMENT '库存数量',
    `description`  TEXT COMMENT '商品描述',
    `status`       TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '状态：0-下架，1-上架',
    `create_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_product_name` (`product_name`),
    KEY `idx_category` (`category`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品表';

-- 插入商品测试数据
INSERT INTO `t_product` (`id`, `product_name`, `category`, `price`, `stock`, `description`, `status`)
VALUES (1, 'iPhone 15 Pro', '手机', 7999.00, 100, 'Apple iPhone 15 Pro 256GB 钛金属', 1),
       (2, 'MacBook Pro 14', '电脑', 14999.00, 50, 'Apple MacBook Pro 14英寸 M3 芯片', 1),
       (3, 'AirPods Pro 2', '耳机', 1899.00, 200, 'Apple AirPods Pro 第二代 主动降噪', 1),
       (4, 'iPad Air', '平板', 4799.00, 80, 'Apple iPad Air 10.9英寸 M1 芯片', 1),
       (5, 'Apple Watch Series 9', '手表', 2999.00, 120, 'Apple Watch Series 9 GPS 45mm', 1);


-- 创建库存表
CREATE TABLE IF NOT EXISTS `t_stock`
(
    `id`          BIGINT(20) NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '库存ID',
    `product_id`  BIGINT(20) NOT NULL COMMENT '商品ID',
    `quantity`    INT(11)    NOT NULL DEFAULT 0 COMMENT '库存数量',
    `version`     BIGINT(20) NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_product_id` (`product_id`),
    KEY `idx_quantity` (`quantity`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='库存表';

-- 插入库存测试数据
INSERT INTO `t_stock` (`product_id`, `quantity`, `version`)
VALUES (1, 150, 1),
       (2, 75, 1),
       (3, 320, 1),
       (4, 120, 1),
       (5, 180, 1),
       (6, 500, 1),
       (7, 280, 1),
       (8, 90, 1),
       (9, 450, 1),
       (10, 220, 1);



-- 查询验证
SELECT o.id,
       o.order_no,
       u.username,
       p.product_name,
       o.quantity,
       o.total_amount,
       o.status
FROM t_order o
         LEFT JOIN t_user u ON o.user_id = u.id
         LEFT JOIN t_product p ON o.product_id = p.id
ORDER BY o.create_time DESC;