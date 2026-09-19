-- =========================================================
-- Schema: E-commerce
-- Engine: InnoDB (required for foreign keys)
-- Charset: utf8mb4
-- Step 1: Create tables WITHOUT relationships
-- =========================================================

-- ---------- AUTH / ACCOUNTS ----------

CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    user_email VARCHAR(255) NOT NULL UNIQUE,
    user_password_hash VARCHAR(255) NOT NULL,
    user_role ENUM('admin','seller','customer') NOT NULL,
    user_status BOOLEAN NOT NULL DEFAULT TRUE,
    user_created_at DATETIME NOT NULL,
    user_last_login DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id INT AUTO_INCREMENT PRIMARY KEY,
    token_id INT NOT NULL UNIQUE,
    user_id INT NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    token_expires_at DATETIME NOT NULL,
    token_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    token_created_at DATETIME NOT NULL,
    token_user_agent VARCHAR(255) NULL,
    token_ip VARCHAR(45) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- PROFILES ----------

CREATE TABLE IF NOT EXISTS admins (
    id INT AUTO_INCREMENT PRIMARY KEY,
    admin_id INT NOT NULL UNIQUE,
    user_id INT NOT NULL,
    admin_name VARCHAR(255) NOT NULL,
    admin_status BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sellers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    seller_id INT NOT NULL UNIQUE,
    user_id INT NOT NULL,
    seller_name VARCHAR(255) NOT NULL,
    seller_phone VARCHAR(20) NOT NULL,
    seller_address VARCHAR(255),
    seller_status BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS customers (
    id INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL UNIQUE,
    user_id INT NOT NULL,
    customer_name VARCHAR(255) NOT NULL,
    customer_address VARCHAR(255),
    customer_phone VARCHAR(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
---
CREATE TABLE IF NOT EXISTS categories (
    id INT AUTO_INCREMENT PRIMARY KEY,
    category_id INT NOT NULL UNIQUE,
    category_slug VARCHAR(255) NOT NULL,
    parent_category_id INT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS products (
    id INT AUTO_INCREMENT PRIMARY KEY,
    product_id INT NOT NULL UNIQUE,
    product_name VARCHAR(255) NOT NULL,
    product_description TEXT NOT NULL,
    product_price DECIMAL(10, 2) NOT NULL,
    product_stock INT NOT NULL,
    category_id INT NOT NULL,
    seller_id INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS attributes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    attribute_id INT NOT NULL UNIQUE,
    attribute_name VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS variants (
    id INT AUTO_INCREMENT PRIMARY KEY,
    variant_id INT NOT NULL UNIQUE,
    variant_sku VARCHAR(255) NOT NULL,
    variant_price DECIMAL(10, 2) NOT NULL,
    variant_stock INT NOT NULL,
    product_id INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS variant_values (
    id INT AUTO_INCREMENT PRIMARY KEY,
    variant_value_id INT NOT NULL UNIQUE,
    variant_id INT NOT NULL,
    attribute_id INT NOT NULL,
    attribute_value VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS carts (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cart_id INT NOT NULL UNIQUE,
    customer_id INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cart_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cart_item_id INT NOT NULL UNIQUE,
    cart_id INT NOT NULL,
    variant_id INT NOT NULL,
    quantity INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orders (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_id INT NOT NULL UNIQUE,
    customer_id INT NOT NULL,
    order_status VARCHAR(50) NOT NULL,
    order_total DECIMAL(10, 2) NOT NULL,
    order_date DATETIME NOT NULL,
    order_address VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    order_item_id INT NOT NULL UNIQUE,
    order_id INT NOT NULL,
    variant_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    payment_id INT NOT NULL UNIQUE,
    order_id INT NOT NULL,
    payment_method VARCHAR(50) NOT NULL,
    payment_status VARCHAR(50) NOT NULL,
    payment_amount DECIMAL(10, 2) NOT NULL,
    payment_date DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reviews (
    id INT AUTO_INCREMENT PRIMARY KEY,
    review_id INT NOT NULL UNIQUE,
    product_id INT NOT NULL,
    customer_id INT NOT NULL,
    review_rating INT NOT NULL,
    review_comment TEXT NOT NULL,
    review_date DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notifications (
    id INT AUTO_INCREMENT PRIMARY KEY,
    notification_id INT NOT NULL UNIQUE,
    customer_id INT NOT NULL,
    notification_message TEXT NOT NULL,
    notification_status BOOLEAN NOT NULL DEFAULT FALSE,
    notification_date DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =========================================================
-- Step 2 (OPTIONAL): Add relationships via ALTER TABLE
-- Run this block ONLY when you want to enforce FKs.
-- =========================================================

-- ---------- One profile per user ----------

ALTER TABLE admins    ADD CONSTRAINT uq_admins_user    UNIQUE (user_id);
ALTER TABLE sellers   ADD CONSTRAINT uq_sellers_user   UNIQUE (user_id);
ALTER TABLE customers ADD CONSTRAINT uq_customers_user UNIQUE (user_id);

-- ---------- Business uniqueness ----------

ALTER TABLE variants ADD CONSTRAINT uq_variants_sku UNIQUE (variant_sku);

-- ---------- Optional: one review per customer/product ----------

ALTER TABLE reviews
    ADD CONSTRAINT uq_reviews_customer_product UNIQUE (customer_id, product_id);

-- Self-referencing hierarchy: categories.parent_category_id -> categories.id
ALTER TABLE categories
    ADD CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_category_id) REFERENCES categories(id)
        ON DELETE SET NULL
        ON UPDATE CASCADE;

-- products.category_id -> categories.id
ALTER TABLE products
    ADD CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- refresh_tokens.user_id -> users.id
ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- admins.user_id -> users.id
ALTER TABLE admins
    ADD CONSTRAINT fk_admins_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- sellers.user_id -> users.id
ALTER TABLE sellers
    ADD CONSTRAINT fk_sellers_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- customers.user_id -> users.id
ALTER TABLE customers
    ADD CONSTRAINT fk_customers_user
        FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- products.seller_id -> sellers.id
ALTER TABLE products
    ADD CONSTRAINT fk_products_seller
        FOREIGN KEY (seller_id) REFERENCES sellers(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- variants.product_id -> products.id
ALTER TABLE variants
    ADD CONSTRAINT fk_variants_product
        FOREIGN KEY (product_id) REFERENCES products(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- variant_values.variant_id -> variants.id
ALTER TABLE variant_values
    ADD CONSTRAINT fk_variant_values_variant
        FOREIGN KEY (variant_id) REFERENCES variants(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- variant_values.attribute_id -> attributes.id
ALTER TABLE variant_values
    ADD CONSTRAINT fk_variant_values_attribute
        FOREIGN KEY (attribute_id) REFERENCES attributes(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- carts.customer_id -> customers.id
ALTER TABLE carts
    ADD CONSTRAINT fk_carts_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- cart_items.cart_id -> carts.id
ALTER TABLE cart_items
    ADD CONSTRAINT fk_cart_items_cart
        FOREIGN KEY (cart_id) REFERENCES carts(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- cart_items.variant_id -> variants.id
ALTER TABLE cart_items
    ADD CONSTRAINT fk_cart_items_variant
        FOREIGN KEY (variant_id) REFERENCES variants(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- orders.customer_id -> customers.id
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- order_items.order_id -> orders.id
ALTER TABLE order_items
    ADD CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- order_items.variant_id -> variants.id
ALTER TABLE order_items
    ADD CONSTRAINT fk_order_items_variant
        FOREIGN KEY (variant_id) REFERENCES variants(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE;

-- payments.order_id -> orders.id
ALTER TABLE payments
    ADD CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id) REFERENCES orders(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- reviews.product_id -> products.id
ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_product
        FOREIGN KEY (product_id) REFERENCES products(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- reviews.customer_id -> customers.id
ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

-- notifications.customer_id -> customers.id
ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE;

