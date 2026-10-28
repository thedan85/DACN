-- =========================================================
-- Schema: E-commerce
-- Engine: InnoDB
-- Charset: utf8mb4 (utf8mb4_0900_ai_ci = case-insensitive UNIQUE chong Red/red)
-- PK: surrogate BIGINT id; FK chi tro den id (khong tro business id)
-- Business id VARCHAR(255): UNIQUE, chi dung cho API/external reference.
-- Copy: PART 1 tables (columns + PK, khong FK/UNIQUE/CHECK) -> PART 2 ALTER -> PART 3 indexes
-- Time: created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
-- Soft-delete: users dung user_status='DELETED'; products dung product_status='DELETED';
--   categories/attributes/variants/media/roles/notifications dung is_deleted.
--   admins/customers/employees soft-delete theo users (khong co cot rieng).
--   Don hang khong soft-delete (dung order_status='CANCELLED').
--   order_items khong co business id: chi can PK id, reviews.order_item_id tro den order_items.id.
-- Quy tac xoa cung: user co don hang chi duoc soft-delete (user_status='DELETED'), khong hard-delete
--   vi users -> customers CASCADE nhung customers -> orders RESTRICT se chan ca chuoi (giu lich su don).
--   Tuong tu: variant nam trong cart_items/order_items (RESTRICT) -> product/variant chi soft-delete.
--   Xoa cung product chi thanh cong khi moi variant khong con trong gio/don.
-- Cascade: user -> refresh_tokens/admins/customers/employees/notifications
--          product -> variants -> media/variant_attribute_values/reviews
--          order -> order_items/payments
-- Status: ENUM (order: PENDING,CONFIRMED,SHIPPING,COMPLETED,CANCELLED, khong dung cash)
-- =========================================================

DROP DATABASE IF EXISTS ecom;
CREATE DATABASE ecom CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ecom;
-- =========================================================
-- PART 1: TABLES (columns + PK only, copy block nay truoc, chay moi order)
-- =========================================================

-- ---------- AUTH / ACCOUNTS ----------

CREATE TABLE IF NOT EXISTS users (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id             VARCHAR(255) NOT NULL,
    user_email          VARCHAR(255) NOT NULL,
    user_password_hash VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    user_status         ENUM('ACTIVE','LOCKED','DELETED') NOT NULL DEFAULT 'ACTIVE',
    role_id             BIGINT UNSIGNED NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    user_last_login     TIMESTAMP NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    token_id            VARCHAR(255) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    token_expires_at    TIMESTAMP NOT NULL,
    token_revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    token_user_agent    VARCHAR(255) NULL,
    token_ip            VARCHAR(45) NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- RBAC ----------

CREATE TABLE IF NOT EXISTS roles (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_id             VARCHAR(255) NOT NULL,
    role_code           VARCHAR(50) NOT NULL,
    role_name           VARCHAR(255) NOT NULL,
    role_description    TEXT NULL,
    role_is_system      BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS permissions (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    permission_id       VARCHAR(255) NOT NULL,
    permission_code     VARCHAR(100) NOT NULL,
    permission_name     VARCHAR(255) NOT NULL,
    permission_resource VARCHAR(50) NOT NULL,
    permission_action   VARCHAR(50) NOT NULL,
    permission_description TEXT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS role_permissions (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_permission_id  VARCHAR(255) NOT NULL,
    role_id             BIGINT UNSIGNED NOT NULL,
    permission_id       BIGINT UNSIGNED NOT NULL,
    granted_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- PROFILES ----------

CREATE TABLE IF NOT EXISTS admins (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin_id            VARCHAR(255) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    admin_name          VARCHAR(255) NOT NULL,
    admin_status        ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS customers (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id         VARCHAR(255) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    customer_name       VARCHAR(255) NOT NULL,
    customer_address    VARCHAR(255),
    customer_phone      VARCHAR(20) NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS employees (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    employee_id         VARCHAR(255) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    employee_name       VARCHAR(255) NOT NULL,
    employee_address    VARCHAR(255),
    employee_phone      VARCHAR(20) NOT NULL,
    employee_status     ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- CATALOG (gia + stock nam o variants, khong nam o products) ----------

CREATE TABLE IF NOT EXISTS categories (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category_id         VARCHAR(255) NOT NULL,
    category_name       VARCHAR(255) NOT NULL,
    category_slug       VARCHAR(255) NOT NULL,
    parent_category_id  BIGINT UNSIGNED NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS products (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id          VARCHAR(255) NOT NULL,
    product_slug        VARCHAR(255) NOT NULL,
    product_name        VARCHAR(255) NOT NULL,
    product_description TEXT NOT NULL,
    product_thumbnail_media_id BIGINT UNSIGNED NULL,
    product_status      ENUM('DRAFT','ACTIVE','INACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE',
    category_id         BIGINT UNSIGNED NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS attributes (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attribute_id        VARCHAR(255) NOT NULL,
    attribute_name      VARCHAR(255) NOT NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS attribute_values (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attribute_value_id  VARCHAR(255) NOT NULL,
    attribute_id        BIGINT UNSIGNED NOT NULL,
    attribute_value     VARCHAR(255) NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS variants (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    variant_id          VARCHAR(255) NOT NULL,
    variant_sku         VARCHAR(255) NOT NULL,
    variant_price       DECIMAL(10,2) NOT NULL,
    variant_stock       INT NOT NULL DEFAULT 0,
    product_id          BIGINT UNSIGNED NOT NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS media (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    media_id            VARCHAR(255) NOT NULL,
    media_url           VARCHAR(500) NOT NULL,
    product_id          BIGINT UNSIGNED NULL,
    variant_id          BIGINT UNSIGNED NULL,
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS variant_attribute_values (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    variant_value_id    VARCHAR(255) NOT NULL,
    variant_id          BIGINT UNSIGNED NOT NULL,
    attribute_id        BIGINT UNSIGNED NOT NULL,
    attribute_value_id  BIGINT UNSIGNED NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS product_attribute_values (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_value_id    VARCHAR(255) NOT NULL,
    product_id          BIGINT UNSIGNED NOT NULL,
    attribute_id        BIGINT UNSIGNED NOT NULL,
    attribute_value_id  BIGINT UNSIGNED NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ---------- CART & ORDER ----------

CREATE TABLE IF NOT EXISTS carts (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cart_id             VARCHAR(255) NOT NULL,
    customer_id         BIGINT UNSIGNED NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cart_items (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cart_item_id        VARCHAR(255) NOT NULL,
    cart_id             BIGINT UNSIGNED NOT NULL,
    variant_id          BIGINT UNSIGNED NOT NULL,
    quantity            INT NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS orders (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id            VARCHAR(255) NOT NULL,
    customer_id         BIGINT UNSIGNED NOT NULL,
    order_status        ENUM('PENDING','CONFIRMED','SHIPPING','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    order_total         DECIMAL(10,2) NOT NULL,
    order_address       VARCHAR(255) NOT NULL,
    order_recipient_name VARCHAR(255) NOT NULL,
    order_phone         VARCHAR(20) NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS order_items (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT UNSIGNED NOT NULL,
    variant_id          BIGINT UNSIGNED NOT NULL,
    quantity            INT NOT NULL,
    unit_price          DECIMAL(10,2) NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS payments (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    payment_id          VARCHAR(255) NOT NULL,
    order_id            BIGINT UNSIGNED NOT NULL,
    payment_method      ENUM('CREDIT_CARD','DEBIT_CARD','BANK_TRANSFER','E_WALLET','COD') NOT NULL,
    payment_status      ENUM('PENDING','CONFIRMED','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    payment_amount      DECIMAL(10,2) NOT NULL,
    payment_transaction_id VARCHAR(255) NULL,
    paid_at             TIMESTAMP NULL DEFAULT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reviews (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id           VARCHAR(255) NOT NULL,
    variant_id          BIGINT UNSIGNED NOT NULL,
    customer_id         BIGINT UNSIGNED NOT NULL,
    order_item_id       BIGINT UNSIGNED NULL, -- 1 dong don hang (order_item) chi review 1 lan, NULL neu review khong kem don
    review_rating       TINYINT NOT NULL,
    review_comment      TEXT NOT NULL,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS notifications (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    notification_message TEXT NOT NULL,
    notification_type ENUM('ORDER','PROMOTION','SYSTEM') NOT NULL DEFAULT 'SYSTEM',
    notification_status ENUM('UNREAD','READ') NOT NULL DEFAULT 'UNREAD',
    is_deleted          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =========================================================
-- PART 2: CONSTRAINTS via ALTER (chay sau PART 1)
-- =========================================================

-- users.user_email UNIQUE: chong trung email dang nhap
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (user_email);
-- users.user_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE users ADD CONSTRAINT uq_users_user_id UNIQUE (user_id);

-- refresh_tokens.token_id UNIQUE: business key convention
ALTER TABLE refresh_tokens ADD CONSTRAINT uq_refresh_tokens_id UNIQUE (token_id);

-- roles.role_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE roles ADD CONSTRAINT uq_roles_id UNIQUE (role_id);

-- FK users.role_id -> roles.id RESTRICT: 1 user - 1 role, dang gan thi khong cho xoa role
ALTER TABLE users ADD CONSTRAINT fk_users_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- FK refresh_tokens.user_id -> users.id: xoa user thi xoa het token con
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- roles.role_code UNIQUE: ma role 'admin','customer' khong duoc trung
ALTER TABLE roles ADD CONSTRAINT uq_roles_code UNIQUE (role_code);

-- permissions.permission_code UNIQUE: ma quyen 'product:create' khong duoc trung
ALTER TABLE permissions ADD CONSTRAINT uq_permissions_code UNIQUE (permission_code);
-- permissions.permission_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE permissions ADD CONSTRAINT uq_permissions_id UNIQUE (permission_id);

-- role_permissions (role_id, permission_id) UNIQUE: chong gan trung quyen cho role
ALTER TABLE role_permissions ADD CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id);
-- FK role_permissions.role_id -> roles.id: xoa role thi xoa het phan quyen
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK role_permissions.permission_id -> permissions.id: xoa permission thi xoa het phan quyen
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- admins.user_id UNIQUE: moi user chi co 1 profile admin (1-1)
ALTER TABLE admins ADD CONSTRAINT uq_admins_user UNIQUE (user_id);
-- admins.admin_id UNIQUE: business key convention
ALTER TABLE admins ADD CONSTRAINT uq_admins_id UNIQUE (admin_id);
-- FK admins.user_id -> users.id: xoa user thi xoa profile admin
ALTER TABLE admins ADD CONSTRAINT fk_admins_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- customers.user_id UNIQUE: moi user chi co 1 profile customer (1-1)
ALTER TABLE customers ADD CONSTRAINT uq_customers_user UNIQUE (user_id);
-- customers.customer_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE customers ADD CONSTRAINT uq_customers_id UNIQUE (customer_id);
-- FK customers.user_id -> users.id: xoa user thi xoa profile customer
ALTER TABLE customers ADD CONSTRAINT fk_customers_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- employees.user_id UNIQUE: moi user chi co 1 profile employee (1-1)
ALTER TABLE employees ADD CONSTRAINT uq_employees_user UNIQUE (user_id);
-- employees.employee_id UNIQUE: business key convention
ALTER TABLE employees ADD CONSTRAINT uq_employees_id UNIQUE (employee_id);
-- FK employees.user_id -> users.id: xoa user thi xoa profile employee
ALTER TABLE employees ADD CONSTRAINT fk_employees_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- categories.category_slug UNIQUE: slug dung cho URL nen khong duoc trung
ALTER TABLE categories ADD CONSTRAINT uq_categories_slug UNIQUE (category_slug);
-- categories.category_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE categories ADD CONSTRAINT uq_categories_id UNIQUE (category_id);
-- FK categories.parent -> categories.id SET NULL: xoa cha thi con thanh goc, khong mat hang
ALTER TABLE categories ADD CONSTRAINT fk_categories_parent
    FOREIGN KEY (parent_category_id) REFERENCES categories(id)
    ON DELETE SET NULL ON UPDATE CASCADE;

-- FK products.category_id -> categories.id SET NULL: xoa category thi product mat nhom, khong mat hang
ALTER TABLE products ADD CONSTRAINT fk_products_category
    FOREIGN KEY (category_id) REFERENCES categories(id)
    ON DELETE SET NULL ON UPDATE CASCADE;

-- products.product_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE products ADD CONSTRAINT uq_products_id UNIQUE (product_id);

-- products.thumbnail_media_id -> media.id SET NULL: xoa media thi product mat thumbnail
ALTER TABLE products ADD CONSTRAINT fk_products_thumbnail_media
    FOREIGN KEY (thumbnail_media_id) REFERENCES media(id)
    ON DELETE SET NULL ON UPDATE CASCADE;

-- products.product_slug UNIQUE: slug cho public API (ai_ci nen khong phan biet hoa/thuong)
ALTER TABLE products ADD CONSTRAINT uq_products_slug UNIQUE (product_slug);

-- attributes.attribute_name UNIQUE: ten thuoc tinh 'Color' khong duoc trung (ai_ci chong Red/red)
ALTER TABLE attributes ADD CONSTRAINT uq_attributes_name UNIQUE (attribute_name);
-- attributes.attribute_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE attributes ADD CONSTRAINT uq_attributes_id UNIQUE (attribute_id);

-- attribute_values (attribute_id, value) UNIQUE: 'Color-Red' chi co 1 dong chuan
ALTER TABLE attribute_values ADD CONSTRAINT uq_attribute_value UNIQUE (attribute_id, attribute_value);

-- (id, attribute_id) UNIQUE: index cho composite FK tu variant/product_attribute_values
ALTER TABLE attribute_values
ADD CONSTRAINT uq_attribute_values_id_attribute UNIQUE (id, attribute_id);
-- attribute_values.attribute_value_id UNIQUE: business key cho API/external
ALTER TABLE attribute_values ADD CONSTRAINT uq_attribute_values_id UNIQUE (attribute_value_id);
-- CHECK attribute_value khong rong: chong luu gia tri trang
ALTER TABLE attribute_values ADD CONSTRAINT chk_attribute_value_nonblank CHECK (LENGTH(TRIM(attribute_value)) > 0);
-- FK attribute_values.attribute_id -> attributes.id RESTRICT: dang co gia tri thi khong cho xoa thuoc tinh
ALTER TABLE attribute_values ADD CONSTRAINT fk_attribute_values_attribute
    FOREIGN KEY (attribute_id) REFERENCES attributes(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- variants.variant_sku UNIQUE: ma SKU dung tru kho nen bat buoc duy nhat
ALTER TABLE variants ADD CONSTRAINT uq_variants_sku UNIQUE (variant_sku);
-- variants.variant_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE variants ADD CONSTRAINT uq_variants_id UNIQUE (variant_id);
-- CHECK variant_price >= 0: chong gia am
ALTER TABLE variants ADD CONSTRAINT chk_variants_price CHECK (variant_price >= 0);
-- CHECK variant_stock >= 0: chong ton kho am
ALTER TABLE variants ADD CONSTRAINT chk_variants_stock CHECK (variant_stock >= 0);
-- FK variants.product_id -> products.id: xoa product thi xoa het variants con
ALTER TABLE variants ADD CONSTRAINT fk_variants_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- MySQL 3823: khong duoc CHECK tren cot co FK CASCADE -> bo chk_media_target, check o app
-- FK media.product_id -> products.id: xoa product thi xoa anh product
ALTER TABLE media ADD CONSTRAINT fk_media_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK media.variant_id -> variants.id: xoa variant thi xoa anh variant
ALTER TABLE media ADD CONSTRAINT fk_media_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- media.media_id UNIQUE: business key convention
ALTER TABLE media ADD CONSTRAINT uq_media_id UNIQUE (media_id);

-- variant_attribute_values (variant_id, attribute_id) UNIQUE: 1 variant chi co 1 'Color'
ALTER TABLE variant_attribute_values ADD CONSTRAINT uq_variant_attribute UNIQUE (variant_id, attribute_id);
-- FK variant_attribute_values.variant_id -> variants.id: xoa variant thi xoa het gia tri
ALTER TABLE variant_attribute_values ADD CONSTRAINT fk_variant_attribute_values_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK composite (attribute_value_id, attribute_id) -> attribute_values(id, attribute_id): chan ghep sai cap (Color + Size-L)
ALTER TABLE variant_attribute_values ADD CONSTRAINT fk_variant_attribute_values_attr_pair
    FOREIGN KEY (attribute_value_id, attribute_id) REFERENCES attribute_values(id, attribute_id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- product_attribute_values (product_id, attribute_id) UNIQUE: 1 product chi co 1 gia tri cho moi attribute
ALTER TABLE product_attribute_values ADD CONSTRAINT uq_product_attribute UNIQUE (product_id, attribute_id);
-- FK product_attribute_values.product_id -> products.id: xoa product thi xoa het gia tri
ALTER TABLE product_attribute_values ADD CONSTRAINT fk_product_values_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK composite (attribute_value_id, attribute_id) -> attribute_values(id, attribute_id): chan ghep sai cap
ALTER TABLE product_attribute_values ADD CONSTRAINT fk_product_values_attr_pair
    FOREIGN KEY (attribute_value_id, attribute_id) REFERENCES attribute_values(id, attribute_id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- carts.customer_id UNIQUE: moi customer chi co 1 gio (them khi can multi-cart/wishlist)
ALTER TABLE carts ADD CONSTRAINT uq_carts_customer UNIQUE (customer_id);
-- carts.cart_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE carts ADD CONSTRAINT uq_carts_id UNIQUE (cart_id);
-- FK carts.customer_id -> customers.id: xoa customer thi xoa gio
ALTER TABLE carts ADD CONSTRAINT fk_carts_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- cart_items (cart_id, variant_id) UNIQUE: cung variant trong gio thi tang quantity, khong them dong
ALTER TABLE cart_items ADD CONSTRAINT uq_cart_variant UNIQUE (cart_id, variant_id);
-- CHECK quantity > 0: so luong gio phai duong
ALTER TABLE cart_items ADD CONSTRAINT chk_cart_items_qty CHECK (quantity > 0);
-- FK cart_items.cart_id -> carts.id: xoa gio thi xoa het dong hang
ALTER TABLE cart_items ADD CONSTRAINT fk_cart_items_cart
    FOREIGN KEY (cart_id) REFERENCES carts(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK cart_items.variant_id -> variants.id RESTRICT: dang trong gio thi khong cho xoa variant
ALTER TABLE cart_items ADD CONSTRAINT fk_cart_items_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- CHECK order_total >= 0: tong don khong am
ALTER TABLE orders ADD CONSTRAINT chk_orders_total CHECK (order_total >= 0);
-- orders.order_id UNIQUE: business key cho API/external, khong dung cho FK
ALTER TABLE orders ADD CONSTRAINT uq_orders_id UNIQUE (order_id);
-- FK orders.customer_id -> customers.id RESTRICT: giu lich su don, khong cho xoa customer con don
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- CHECK order_items.quantity > 0: so luong dat phai duong
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_qty CHECK (quantity > 0);
-- CHECK unit_price >= 0: don gia tai thoi diem ban khong am
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_price CHECK (unit_price >= 0);
-- order_items khong co business id, chi dung PK id
-- FK order_items.order_id -> orders.id: xoa order thi xoa het dong hang
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order
    FOREIGN KEY (order_id) REFERENCES orders(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK order_items.variant_id -> variants.id RESTRICT: giu lich su ban, khong cho xoa variant
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- payments.order_id UNIQUE: 1 order chi 1 payment (them khi can tach/lap thanh toan)
ALTER TABLE payments ADD CONSTRAINT uq_payments_order UNIQUE (order_id);
-- payments.transaction_id UNIQUE: idempotency key chong thu tien 2 lan tu cong thanh toan
ALTER TABLE payments ADD CONSTRAINT uq_payments_transaction UNIQUE (payment_transaction_id);
-- CHECK payment_amount >= 0: so tien thanh toan khong am
ALTER TABLE payments ADD CONSTRAINT chk_payments_amount CHECK (payment_amount >= 0);
-- FK payments.order_id -> orders.id: xoa order thi xoa payment
ALTER TABLE payments ADD CONSTRAINT fk_payments_order
    FOREIGN KEY (order_id) REFERENCES orders(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- reviews (customer_id, variant_id) UNIQUE: moi khach chi danh gia 1 lan / variant, xoa review la xoa cung luon
ALTER TABLE reviews ADD CONSTRAINT uq_reviews_customer_variant UNIQUE (customer_id, variant_id);
-- CHECK rating 1-5: chan thang diem ngoai 1-5 sao
ALTER TABLE reviews ADD CONSTRAINT chk_reviews_rating CHECK (review_rating BETWEEN 1 AND 5);
-- FK reviews.variant_id -> variants.id CASCADE: xoa variant (ke ca tu xoa product) thi xoa cung review
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- FK reviews.customer_id -> customers.id: xoa customer thi xoa review
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
-- reviews.order_item_id UNIQUE: 1 dong don hang (order_item) chi review 1 lan, chong spam (NULL cho review khong kem don)
ALTER TABLE reviews ADD CONSTRAINT uq_reviews_order_item UNIQUE (order_item_id);
-- FK reviews.order_item_id -> order_items.id CASCADE: review verified theo don, xoa don thi xoa cung review
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_order_item
    FOREIGN KEY (order_item_id) REFERENCES order_items(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

-- FK notifications.user_id -> users.id: xoa user thi xoa het thong bao
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
