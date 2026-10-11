-- =========================================================
-- V2: Indexes
--
-- 1) FK column indexes — MySQL/InnoDB tu dong tao index cho khoa ngoai,
--    Postgres KHONG. Thieu index => JOIN va ON DELETE CASCADE quet bang.
-- 2) UNIQUE index tren lower(col) — thay cho UNIQUE thuong tren cac cot chu
--    "con nguoi", giu tinh nang case-insensitive nhu collation
--    utf8mb4_0900_ai_ci cua MySQL (chong 'Red'/'red', chong trung email).
--    Cot business-id va hash giu UNIQUE thuong (case-sensitive) da dat o V1.
--
-- Luu y tuong lai: tren bang da co du lieu that, dung
-- CREATE INDEX CONCURRENTLY (khong chay duoc trong transaction cua Flyway —
-- can chay rieng, ngoai migration) de khong khoa bang.
-- =========================================================

-- ---------------------------------------------------------
-- 1) Index cho cot FK
--    (bo qua cot da co index dang phu hop tu UNIQUE constraint o V1,
--     vi du uq_carts_customer da index customer_id)
-- ---------------------------------------------------------

create index if not exists idx_users_role_id
    on users (role_id);

create index if not exists idx_refresh_tokens_user_id
    on refresh_tokens (user_id);

-- role_id da duoc index boi uq_role_permission (leading column)
create index if not exists idx_role_permissions_permission_id
    on role_permissions (permission_id);

create index if not exists idx_categories_parent
    on categories (parent_category_id);

create index if not exists idx_products_category_id
    on products (category_id);

create index if not exists idx_attribute_values_attribute_id
    on attribute_values (attribute_id);

create index if not exists idx_variants_product_id
    on variants (product_id);

create index if not exists idx_media_product_id
    on media (product_id);

create index if not exists idx_media_variant_id
    on media (variant_id);

-- FK composite (attribute_id, attribute_value_id): index dung thu tu FK
create index if not exists idx_variant_attribute_values_pair
    on variant_attribute_values (attribute_id, attribute_value_id);

create index if not exists idx_variant_attribute_values_variant_id
    on variant_attribute_values (variant_id);

-- cart_id da duoc index boi uq_cart_variant (leading column)
create index if not exists idx_cart_items_variant_id
    on cart_items (variant_id);

create index if not exists idx_orders_customer_id
    on orders (customer_id);

create index if not exists idx_order_items_order_id
    on order_items (order_id);

create index if not exists idx_order_items_variant_id
    on order_items (variant_id);

-- customer_id da duoc index boi uq_reviews_customer_variant (leading column)
-- order_item_id da duoc index boi uq_reviews_order_item
create index if not exists idx_reviews_variant_id
    on reviews (variant_id);

create index if not exists idx_notifications_user_id
    on notifications (user_id);

-- ---------------------------------------------------------
-- 2) UNIQUE case-insensitive (thay UNIQUE thuong cua MySQL ai_ci)
-- ---------------------------------------------------------

create unique index if not exists uq_users_email
    on users (lower(user_email));

create unique index if not exists uq_roles_code
    on roles (lower(role_code));

create unique index if not exists uq_permissions_code
    on permissions (lower(permission_code));

create unique index if not exists uq_categories_slug
    on categories (lower(category_slug));

create unique index if not exists uq_attributes_name
    on attributes (lower(attribute_name));

-- 'Color' + 'Red' chi co mot dong chuan, khong phan biet chu hoa/thuong
create unique index if not exists uq_attribute_value
    on attribute_values (attribute_id, lower(attribute_value));

create unique index if not exists uq_variants_sku
    on variants (lower(variant_sku));
