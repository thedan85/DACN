-- =========================================================
-- V12: FK columns tro ve surrogate id thay vi business id
--
-- * Backfill gia tri FK tu business id sang <parent>.id
-- * Doi kieu FK columns VARCHAR(255) -> BIGINT
-- * Tao lai FK constraints tro den <parent>(id)
-- * Giu nguyen business-id columns (ke ca order_items.order_item_id)
-- * Bo sung FK con thieu cho product_attribute_values
-- * Composite attr pair FK tro den attribute_values(id, attribute_id)
-- =========================================================

-- ---------------------------------------------------------
-- 1) Drop FK constraints cu (business-id targets)
-- ---------------------------------------------------------

ALTER TABLE users DROP CONSTRAINT fk_users_role;
ALTER TABLE refresh_tokens DROP CONSTRAINT fk_refresh_tokens_user;
ALTER TABLE role_permissions
    DROP CONSTRAINT fk_role_permissions_role,
    DROP CONSTRAINT fk_role_permissions_permission;
ALTER TABLE admins DROP CONSTRAINT fk_admins_user;
ALTER TABLE customers DROP CONSTRAINT fk_customers_user;
ALTER TABLE employees DROP CONSTRAINT fk_employees_user;
ALTER TABLE categories DROP CONSTRAINT fk_categories_parent;
ALTER TABLE products DROP CONSTRAINT fk_products_category;
ALTER TABLE attribute_values DROP CONSTRAINT fk_attribute_values_attribute;
ALTER TABLE variants DROP CONSTRAINT fk_variants_product;
ALTER TABLE media
    DROP CONSTRAINT fk_media_product,
    DROP CONSTRAINT fk_media_variant;
ALTER TABLE variant_attribute_values
    DROP CONSTRAINT fk_variant_values_variant,
    DROP CONSTRAINT fk_variant_values_attr_pair;
ALTER TABLE carts DROP CONSTRAINT fk_carts_customer;
ALTER TABLE cart_items
    DROP CONSTRAINT fk_cart_items_cart,
    DROP CONSTRAINT fk_cart_items_variant;
ALTER TABLE orders DROP CONSTRAINT fk_orders_customer;
ALTER TABLE order_items
    DROP CONSTRAINT fk_order_items_order,
    DROP CONSTRAINT fk_order_items_variant;
ALTER TABLE payments DROP CONSTRAINT fk_payments_order;
ALTER TABLE reviews
    DROP CONSTRAINT fk_reviews_variant,
    DROP CONSTRAINT fk_reviews_customer,
    DROP CONSTRAINT fk_reviews_order_item;
ALTER TABLE notifications DROP CONSTRAINT fk_notifications_user;

-- Hom nay la dich den cua composite FK cu; thay bang (id, attribute_id).
ALTER TABLE attribute_values DROP CONSTRAINT uq_attribute_value_id;

-- ---------------------------------------------------------
-- 2) Backfill FK columns: business id -> parent.id (dang text)
--    Backfill child attr pair truoc khi doi attribute_values.attribute_id.
-- ---------------------------------------------------------

UPDATE users x SET role_id = p.id::text
FROM roles p WHERE x.role_id = p.role_id;

UPDATE refresh_tokens x SET user_id = p.id::text
FROM users p WHERE x.user_id = p.user_id;

UPDATE role_permissions x SET role_id = p.id::text
FROM roles p WHERE x.role_id = p.role_id;
UPDATE role_permissions x SET permission_id = p.id::text
FROM permissions p WHERE x.permission_id = p.permission_id;

UPDATE admins x SET user_id = p.id::text
FROM users p WHERE x.user_id = p.user_id;

UPDATE customers x SET user_id = p.id::text
FROM users p WHERE x.user_id = p.user_id;

UPDATE employees x SET user_id = p.id::text
FROM users p WHERE x.user_id = p.user_id;

UPDATE notifications x SET user_id = p.id::text
FROM users p WHERE x.user_id = p.user_id;

UPDATE categories x SET parent_category_id = p.id::text
FROM categories p
WHERE x.parent_category_id IS NOT NULL
  AND x.parent_category_id = p.category_id;

UPDATE products x SET category_id = p.id::text
FROM categories p
WHERE x.category_id IS NOT NULL
  AND x.category_id = p.category_id;

UPDATE variants x SET product_id = p.id::text
FROM products p WHERE x.product_id = p.product_id;

UPDATE media x SET product_id = p.id::text
FROM products p
WHERE x.product_id IS NOT NULL
  AND x.product_id = p.product_id;
UPDATE media x SET variant_id = p.id::text
FROM variants p
WHERE x.variant_id IS NOT NULL
  AND x.variant_id = p.variant_id;

UPDATE variant_attribute_values x SET variant_id = p.id::text
FROM variants p WHERE x.variant_id = p.variant_id;
UPDATE variant_attribute_values x SET attribute_value_id = av.id::text
FROM attribute_values av
WHERE x.attribute_value_id = av.attribute_value_id
  AND x.attribute_id = av.attribute_id;
UPDATE variant_attribute_values x SET attribute_id = a.id::text
FROM attributes a WHERE x.attribute_id = a.attribute_id;

UPDATE product_attribute_values x SET product_id = p.id::text
FROM products p WHERE x.product_id = p.product_id;
UPDATE product_attribute_values x SET attribute_value_id = av.id::text
FROM attribute_values av
WHERE x.attribute_value_id = av.attribute_value_id
  AND x.attribute_id = av.attribute_id;
UPDATE product_attribute_values x SET attribute_id = a.id::text
FROM attributes a WHERE x.attribute_id = a.attribute_id;

UPDATE attribute_values x SET attribute_id = a.id::text
FROM attributes a WHERE x.attribute_id = a.attribute_id;

UPDATE carts x SET customer_id = p.id::text
FROM customers p WHERE x.customer_id = p.customer_id;

UPDATE cart_items x SET cart_id = p.id::text
FROM carts p WHERE x.cart_id = p.cart_id;
UPDATE cart_items x SET variant_id = p.id::text
FROM variants p WHERE x.variant_id = p.variant_id;

UPDATE orders x SET customer_id = p.id::text
FROM customers p WHERE x.customer_id = p.customer_id;

UPDATE order_items x SET order_id = p.id::text
FROM orders p WHERE x.order_id = p.order_id;
UPDATE order_items x SET variant_id = p.id::text
FROM variants p WHERE x.variant_id = p.variant_id;

UPDATE payments x SET order_id = p.id::text
FROM orders p WHERE x.order_id = p.order_id;

UPDATE reviews x SET variant_id = p.id::text
FROM variants p WHERE x.variant_id = p.variant_id;
UPDATE reviews x SET customer_id = p.id::text
FROM customers p WHERE x.customer_id = p.customer_id;
UPDATE reviews x SET order_item_id = p.id::text
FROM order_items p
WHERE x.order_item_id IS NOT NULL
  AND x.order_item_id = p.order_item_id;

-- ---------------------------------------------------------
-- 3) Doi kieu FK columns sang BIGINT
-- ---------------------------------------------------------

ALTER TABLE users ALTER COLUMN role_id TYPE BIGINT USING role_id::bigint;

ALTER TABLE refresh_tokens ALTER COLUMN user_id TYPE BIGINT USING user_id::bigint;

ALTER TABLE role_permissions
    ALTER COLUMN role_id TYPE BIGINT USING role_id::bigint,
    ALTER COLUMN permission_id TYPE BIGINT USING permission_id::bigint;

ALTER TABLE admins ALTER COLUMN user_id TYPE BIGINT USING user_id::bigint;
ALTER TABLE customers ALTER COLUMN user_id TYPE BIGINT USING user_id::bigint;
ALTER TABLE employees ALTER COLUMN user_id TYPE BIGINT USING user_id::bigint;
ALTER TABLE notifications ALTER COLUMN user_id TYPE BIGINT USING user_id::bigint;

ALTER TABLE categories ALTER COLUMN parent_category_id TYPE BIGINT USING parent_category_id::bigint;
ALTER TABLE products ALTER COLUMN category_id TYPE BIGINT USING category_id::bigint;

ALTER TABLE attribute_values ALTER COLUMN attribute_id TYPE BIGINT USING attribute_id::bigint;

ALTER TABLE variants ALTER COLUMN product_id TYPE BIGINT USING product_id::bigint;

ALTER TABLE media
    ALTER COLUMN product_id TYPE BIGINT USING product_id::bigint,
    ALTER COLUMN variant_id TYPE BIGINT USING variant_id::bigint;

ALTER TABLE variant_attribute_values
    ALTER COLUMN variant_id TYPE BIGINT USING variant_id::bigint,
    ALTER COLUMN attribute_id TYPE BIGINT USING attribute_id::bigint,
    ALTER COLUMN attribute_value_id TYPE BIGINT USING attribute_value_id::bigint;

ALTER TABLE product_attribute_values
    ALTER COLUMN product_id TYPE BIGINT USING product_id::bigint,
    ALTER COLUMN attribute_id TYPE BIGINT USING attribute_id::bigint,
    ALTER COLUMN attribute_value_id TYPE BIGINT USING attribute_value_id::bigint;

ALTER TABLE carts ALTER COLUMN customer_id TYPE BIGINT USING customer_id::bigint;

ALTER TABLE cart_items
    ALTER COLUMN cart_id TYPE BIGINT USING cart_id::bigint,
    ALTER COLUMN variant_id TYPE BIGINT USING variant_id::bigint;

ALTER TABLE orders ALTER COLUMN customer_id TYPE BIGINT USING customer_id::bigint;

ALTER TABLE order_items
    ALTER COLUMN order_id TYPE BIGINT USING order_id::bigint,
    ALTER COLUMN variant_id TYPE BIGINT USING variant_id::bigint;

ALTER TABLE payments ALTER COLUMN order_id TYPE BIGINT USING order_id::bigint;

ALTER TABLE reviews
    ALTER COLUMN variant_id TYPE BIGINT USING variant_id::bigint,
    ALTER COLUMN customer_id TYPE BIGINT USING customer_id::bigint,
    ALTER COLUMN order_item_id TYPE BIGINT USING order_item_id::bigint;

-- ---------------------------------------------------------
-- 4) Index cho FK composite moi
-- ---------------------------------------------------------

DROP INDEX IF EXISTS idx_variant_attribute_values_pair;
CREATE INDEX idx_variant_attribute_values_pair
    ON variant_attribute_values (attribute_value_id, attribute_id);

CREATE INDEX IF NOT EXISTS idx_product_attribute_values_pair
    ON product_attribute_values (attribute_value_id, attribute_id);

-- ---------------------------------------------------------
-- 5) Unique index cho composite FK (id, attribute_id)
-- ---------------------------------------------------------

ALTER TABLE attribute_values
    ADD CONSTRAINT uq_attribute_values_id_attribute UNIQUE (id, attribute_id);

-- ---------------------------------------------------------
-- 6) Tao lai FK constraints tro den surrogate id
-- ---------------------------------------------------------

ALTER TABLE users ADD CONSTRAINT fk_users_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_role
    FOREIGN KEY (role_id) REFERENCES roles(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE admins ADD CONSTRAINT fk_admins_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE customers ADD CONSTRAINT fk_customers_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE employees ADD CONSTRAINT fk_employees_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE notifications ADD CONSTRAINT fk_notifications_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE categories ADD CONSTRAINT fk_categories_parent
    FOREIGN KEY (parent_category_id) REFERENCES categories(id)
    ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE products ADD CONSTRAINT fk_products_category
    FOREIGN KEY (category_id) REFERENCES categories(id)
    ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE attribute_values ADD CONSTRAINT fk_attribute_values_attribute
    FOREIGN KEY (attribute_id) REFERENCES attributes(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE variants ADD CONSTRAINT fk_variants_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE media ADD CONSTRAINT fk_media_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE media ADD CONSTRAINT fk_media_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE variant_attribute_values ADD CONSTRAINT fk_variant_attribute_values_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE variant_attribute_values ADD CONSTRAINT fk_variant_attribute_values_attr_pair
    FOREIGN KEY (attribute_value_id, attribute_id) REFERENCES attribute_values(id, attribute_id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE product_attribute_values ADD CONSTRAINT fk_product_values_product
    FOREIGN KEY (product_id) REFERENCES products(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE product_attribute_values ADD CONSTRAINT fk_product_values_attr_pair
    FOREIGN KEY (attribute_value_id, attribute_id) REFERENCES attribute_values(id, attribute_id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE carts ADD CONSTRAINT fk_carts_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE cart_items ADD CONSTRAINT fk_cart_items_cart
    FOREIGN KEY (cart_id) REFERENCES carts(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE cart_items ADD CONSTRAINT fk_cart_items_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE orders ADD CONSTRAINT fk_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order
    FOREIGN KEY (order_id) REFERENCES orders(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE payments ADD CONSTRAINT fk_payments_order
    FOREIGN KEY (order_id) REFERENCES orders(id)
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE reviews ADD CONSTRAINT fk_reviews_variant
    FOREIGN KEY (variant_id) REFERENCES variants(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_customer
    FOREIGN KEY (customer_id) REFERENCES customers(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE reviews ADD CONSTRAINT fk_reviews_order_item
    FOREIGN KEY (order_item_id) REFERENCES order_items(id)
    ON DELETE CASCADE ON UPDATE CASCADE;
