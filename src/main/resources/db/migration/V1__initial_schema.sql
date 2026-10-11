-- =========================================================
-- V1: Initial schema — PostgreSQL port of project.sql (MySQL).
--
-- Original: docs/../db/project.sql (InnoDB, utf8mb4).
-- Target: Supabase (PostgreSQL 17), chay qua Flyway, profile `dev`.
--
-- Khac biet chinh so voi MySQL (nhat ky chuyen doi):
--   * BIGINT UNSIGNED AUTO_INCREMENT -> BIGINT GENERATED ALWAYS AS IDENTITY
--   * ENUM(...)                       -> VARCHAR(20) + CHECK (col IN (...))
--   * TIMESTAMP                       -> TIMESTAMPTZ (co timezone, khuyen dung)
--   * ON UPDATE CURRENT_TIMESTAMP      -> trigger set_updated_at() (ben duoi)
--   * DROP/CREATE DATABASE ecom; USE ecom; -> BO hoan toan. Schema nam trong
--     database `postgres` cua project Supabase (Postgres khong co USE).
--   * COLLATE utf8mb4_0900_ai_ci (case-insensitive) -> UNIQUE index tren
--     lower(col) cho cac cot chu "con nguoi" — dat o V2. Cot business-id va
--     hash giu UNIQUE thuong (case-sensitive, giong utf8mb4_bin truoc day).
--   * InnoDB tu tao index cho FK; Postgres KHONG -> xem V2__add_indexes.sql.
--   * Business-id la dich den cua FK => BUOC phai co UNIQUE (MySQL tu lo,
--     Postgres khong). Da bo sung day du.
--
-- Giu nguyen thiet ke goc: PK surrogate `id BIGINT` + business id VARCHAR(255),
-- FK tro den business id (khong tro den id so). Soft-delete nhu ban goc.
-- =========================================================

-- ---------------------------------------------------------
-- Trigger cap nhat updated_at (thay cho ON UPDATE CURRENT_TIMESTAMP)
-- ---------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------
-- RBAC (tao truoc: users phu thuoc roles)
-- ---------------------------------------------------------

CREATE TABLE roles (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id          VARCHAR(255) NOT NULL UNIQUE,
    role_code        VARCHAR(50)  NOT NULL,
    role_name        VARCHAR(255) NOT NULL,
    role_description TEXT         NULL,
    role_is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- role_code UNIQUE case-insensitive -> V2 (uq_roles_code lower)

CREATE TABLE permissions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_id       VARCHAR(255) NOT NULL UNIQUE,  -- FK target (role_permissions)
    permission_code     VARCHAR(100) NOT NULL,
    permission_name     VARCHAR(255) NOT NULL,
    permission_resource VARCHAR(50)  NOT NULL,
    permission_action   VARCHAR(50)  NOT NULL,
    permission_description TEXT      NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- permission_code UNIQUE case-insensitive -> V2 (uq_permissions_code lower)

CREATE TABLE role_permissions (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_permission_id VARCHAR(255) NOT NULL,
    role_id           VARCHAR(255) NOT NULL,
    permission_id     VARCHAR(255) NOT NULL,
    granted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (role_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions (permission_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---------------------------------------------------------
-- AUTH / ACCOUNTS
-- ---------------------------------------------------------

CREATE TABLE users (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          VARCHAR(255) NOT NULL UNIQUE,   -- FK target (refresh_tokens, admins, customers, employees, notifications)
    user_email       VARCHAR(255) NOT NULL,
    user_password_hash VARCHAR(255) NOT NULL,        -- case-sensitive nhu utf8mb4_bin goc
    user_status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_users_status CHECK (user_status IN ('ACTIVE', 'LOCKED', 'DELETED')),
    role_id          VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_last_login  TIMESTAMPTZ  NULL,
    CONSTRAINT fk_users_role
        FOREIGN KEY (role_id) REFERENCES roles (role_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
-- user_email UNIQUE case-insensitive -> V2 (uq_users_email lower)

CREATE TABLE refresh_tokens (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_id         VARCHAR(255) NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    token_hash       VARCHAR(255) NOT NULL UNIQUE,   -- case-sensitive nhu utf8mb4_bin goc
    token_expires_at TIMESTAMPTZ  NOT NULL,
    token_revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    token_user_agent VARCHAR(255) NULL,
    token_ip         VARCHAR(45)  NULL,              -- du cho IPv6
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---------------------------------------------------------
-- PROFILES (1-1 voi users)
-- ---------------------------------------------------------

CREATE TABLE admins (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_id     VARCHAR(255) NOT NULL,
    user_id      VARCHAR(255) NOT NULL UNIQUE,
    admin_name   VARCHAR(255) NOT NULL,
    admin_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_admins_status CHECK (admin_status IN ('ACTIVE', 'INACTIVE')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_admins_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE customers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     VARCHAR(255) NOT NULL UNIQUE,    -- FK target (carts, orders, reviews)
    user_id         VARCHAR(255) NOT NULL UNIQUE,
    customer_name   VARCHAR(255) NOT NULL,
    customer_address VARCHAR(255) NULL,
    customer_phone  VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_customers_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE employees (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id     VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL UNIQUE,
    employee_name   VARCHAR(255) NOT NULL,
    employee_address VARCHAR(255) NULL,
    employee_phone  VARCHAR(20)  NOT NULL,
    employee_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_employees_status CHECK (employee_status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_employees_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---------------------------------------------------------
-- CATALOG (gia + stock nam o variants, khong nam o products)
-- ---------------------------------------------------------

CREATE TABLE categories (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id       VARCHAR(255) NOT NULL UNIQUE,  -- FK target (products)
    category_name     VARCHAR(255) NOT NULL,
    category_slug     VARCHAR(255) NOT NULL,
    parent_category_id VARCHAR(255) NULL,
    is_deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_category_id) REFERENCES categories (category_id)
        ON DELETE SET NULL ON UPDATE CASCADE
);
-- category_slug UNIQUE case-insensitive -> V2 (uq_categories_slug lower)

CREATE TABLE attributes (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attribute_id   VARCHAR(255) NOT NULL UNIQUE,      -- FK target (attribute_values, product_attribute_values, variant_attribute_values)
    attribute_name VARCHAR(255) NOT NULL,
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- attribute_name UNIQUE case-insensitive -> V2 (uq_attributes_name lower)
-- (Muc dich goc: chong 'Red' va 'red' trung ten thuoc tinh)

CREATE TABLE attribute_values (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attribute_value_id VARCHAR(255) NOT NULL,
    attribute_id       VARCHAR(255) NOT NULL,
    attribute_value    VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_attribute_value_nonblank CHECK (LENGTH(TRIM(attribute_value)) > 0),
    -- Composite unique nay la dich den cua FK fk_variant_values_attr_pair
    CONSTRAINT uq_attribute_value_id UNIQUE (attribute_id, attribute_value_id),
    CONSTRAINT fk_attribute_values_attribute
        FOREIGN KEY (attribute_id) REFERENCES attributes (attribute_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
-- UNIQUE (attribute_id, lower(attribute_value)) -> V2 (uq_attribute_value lower)

CREATE TABLE products (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id         VARCHAR(255) NOT NULL UNIQUE,  -- FK target (variants, media, product_attribute_values)
    product_name       VARCHAR(255) NOT NULL,
    product_description TEXT        NOT NULL,
    product_thumbnail  VARCHAR(500) NULL,
    product_status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_products_status CHECK (product_status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DELETED')),
    category_id        VARCHAR(255) NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories (category_id)
        ON DELETE SET NULL ON UPDATE CASCADE
);
-- products khong co cot is_deleted: soft-delete bang product_status='DELETED'

CREATE TABLE variants (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id    VARCHAR(255) NOT NULL UNIQUE,       -- FK target (media, variant_attribute_values, cart_items, order_items, reviews)
    variant_sku   VARCHAR(255) NOT NULL,
    variant_price NUMERIC(10,2) NOT NULL
        CONSTRAINT chk_variants_price CHECK (variant_price >= 0),
    variant_stock INTEGER NOT NULL DEFAULT 0
        CONSTRAINT chk_variants_stock CHECK (variant_stock >= 0),
    product_id    VARCHAR(255) NOT NULL,
    is_deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_variants_product
        FOREIGN KEY (product_id) REFERENCES products (product_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);
-- variant_sku UNIQUE case-insensitive -> V2 (uq_variants_sku lower)

CREATE TABLE media (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    media_id   VARCHAR(255) NOT NULL,
    media_url  VARCHAR(500) NOT NULL,
    product_id VARCHAR(255) NULL,
    variant_id VARCHAR(255) NULL,
    is_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_media_product
        FOREIGN KEY (product_id) REFERENCES products (product_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_media_variant
        FOREIGN KEY (variant_id) REFERENCES variants (variant_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE variant_attribute_values (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_value_id   VARCHAR(255) NOT NULL,
    variant_id         VARCHAR(255) NOT NULL,
    attribute_id       VARCHAR(255) NOT NULL,
    attribute_value_id VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_variant_attribute UNIQUE (variant_id, attribute_id),
    CONSTRAINT fk_variant_values_variant
        FOREIGN KEY (variant_id) REFERENCES variants (variant_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    -- FK composite: chan ghep sai cap (Color + Size-L)
    CONSTRAINT fk_variant_values_attr_pair
        FOREIGN KEY (attribute_id, attribute_value_id)
        REFERENCES attribute_values (attribute_id, attribute_value_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
-- NOTE (fix tu ban MySQL): constraint goc tro den bang `variant_values`
-- khong ton tai; day chinh la `variant_attribute_values`.

CREATE TABLE product_attribute_values (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_value_id   VARCHAR(255) NOT NULL,
    product_id         VARCHAR(255) NOT NULL,
    attribute_id       VARCHAR(255) NOT NULL,
    attribute_value_id VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------
-- CART & ORDER
-- ---------------------------------------------------------

CREATE TABLE carts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id     VARCHAR(255) NOT NULL UNIQUE,        -- FK target (cart_items)
    customer_id VARCHAR(255) NOT NULL UNIQUE,        -- 1 customer - 1 gio
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_carts_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE cart_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_item_id VARCHAR(255) NOT NULL,
    cart_id    VARCHAR(255) NOT NULL,
    variant_id VARCHAR(255) NOT NULL,
    quantity   INTEGER NOT NULL
        CONSTRAINT chk_cart_items_qty CHECK (quantity > 0),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_variant UNIQUE (cart_id, variant_id),
    CONSTRAINT fk_cart_items_cart
        FOREIGN KEY (cart_id) REFERENCES carts (cart_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_cart_items_variant
        FOREIGN KEY (variant_id) REFERENCES variants (variant_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE TABLE orders (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id           VARCHAR(255) NOT NULL UNIQUE,  -- FK target (order_items, payments)
    customer_id        VARCHAR(255) NOT NULL,
    order_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_orders_status CHECK (order_status IN ('PENDING', 'CONFIRMED', 'SHIPPING', 'COMPLETED', 'CANCELLED')),
    order_total        NUMERIC(10,2) NOT NULL
        CONSTRAINT chk_orders_total CHECK (order_total >= 0),
    order_address      VARCHAR(255) NOT NULL,
    order_recipient_name VARCHAR(255) NOT NULL,
    order_phone        VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_orders_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);
-- orders khong soft-delete: dung order_status='CANCELLED'

CREATE TABLE order_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_item_id VARCHAR(255) NOT NULL UNIQUE,      -- FK target (reviews)
    order_id    VARCHAR(255) NOT NULL,
    variant_id  VARCHAR(255) NOT NULL,
    quantity    INTEGER NOT NULL
        CONSTRAINT chk_order_items_qty CHECK (quantity > 0),
    unit_price  NUMERIC(10,2) NOT NULL
        CONSTRAINT chk_order_items_price CHECK (unit_price >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_order_items_variant
        FOREIGN KEY (variant_id) REFERENCES variants (variant_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

CREATE TABLE payments (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id            VARCHAR(255) NOT NULL,
    order_id              VARCHAR(255) NOT NULL UNIQUE,   -- 1 order - 1 payment
    payment_method        VARCHAR(20)  NOT NULL
        CONSTRAINT chk_payments_method CHECK (payment_method IN ('CREDIT_CARD', 'DEBIT_CARD', 'BANK_TRANSFER', 'E_WALLET', 'COD')),
    payment_status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_payments_status CHECK (payment_status IN ('PENDING', 'CONFIRMED', 'FAILED', 'REFUNDED')),
    payment_amount        NUMERIC(10,2) NOT NULL
        CONSTRAINT chk_payments_amount CHECK (payment_amount >= 0),
    payment_transaction_id VARCHAR(255) NULL UNIQUE,      -- idempotency key chong thu tien 2 lan
    paid_at               TIMESTAMPTZ  NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id) REFERENCES orders (order_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE reviews (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id      VARCHAR(255) NOT NULL,
    variant_id     VARCHAR(255) NOT NULL,
    customer_id    VARCHAR(255) NOT NULL,
    order_item_id  VARCHAR(255) NULL UNIQUE,             -- 1 dong don hang chi review 1 lan; NULL neu review khong kem don
    review_rating  SMALLINT NOT NULL
        CONSTRAINT chk_reviews_rating CHECK (review_rating BETWEEN 1 AND 5),
    review_comment TEXT NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reviews_customer_variant UNIQUE (customer_id, variant_id),
    CONSTRAINT fk_reviews_variant
        FOREIGN KEY (variant_id) REFERENCES variants (variant_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_reviews_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_reviews_order_item
        FOREIGN KEY (order_item_id) REFERENCES order_items (order_item_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---------------------------------------------------------
-- NOTIFICATIONS
-- ---------------------------------------------------------

CREATE TABLE notifications (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    user_id             VARCHAR(255) NOT NULL,
    notification_message TEXT       NOT NULL,
    notification_type   VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
        CONSTRAINT chk_notifications_type CHECK (notification_type IN ('ORDER', 'PROMOTION', 'SYSTEM')),
    notification_status VARCHAR(20) NOT NULL DEFAULT 'UNREAD'
        CONSTRAINT chk_notifications_status CHECK (notification_status IN ('UNREAD', 'READ')),
    is_deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- ---------------------------------------------------------
-- Trigger updated_at cho moi bang co cot updated_at
-- (tat ca tru role_permissions — bang nay chi co granted_at)
-- ---------------------------------------------------------

CREATE TRIGGER set_users_updated_at
    BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_refresh_tokens_updated_at
    BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_roles_updated_at
    BEFORE UPDATE ON roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_permissions_updated_at
    BEFORE UPDATE ON permissions FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_admins_updated_at
    BEFORE UPDATE ON admins FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_customers_updated_at
    BEFORE UPDATE ON customers FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_employees_updated_at
    BEFORE UPDATE ON employees FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_categories_updated_at
    BEFORE UPDATE ON categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_attributes_updated_at
    BEFORE UPDATE ON attributes FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_attribute_values_updated_at
    BEFORE UPDATE ON attribute_values FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_products_updated_at
    BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_variants_updated_at
    BEFORE UPDATE ON variants FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_media_updated_at
    BEFORE UPDATE ON media FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_variant_attribute_values_updated_at
    BEFORE UPDATE ON variant_attribute_values FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_product_attribute_values_updated_at
    BEFORE UPDATE ON product_attribute_values FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_carts_updated_at
    BEFORE UPDATE ON carts FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_cart_items_updated_at
    BEFORE UPDATE ON cart_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_orders_updated_at
    BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_order_items_updated_at
    BEFORE UPDATE ON order_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_payments_updated_at
    BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_reviews_updated_at
    BEFORE UPDATE ON reviews FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER set_notifications_updated_at
    BEFORE UPDATE ON notifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();
