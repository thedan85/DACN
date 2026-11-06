-- =========================================================
-- V28: Missing lookup and FK indexes
--
-- Non-unique indexes for columns the app looks up directly and for
-- product_attribute_values.product_id, which was missed when the
-- surrogate-id FKs were introduced in V12.
--
-- PostgreSQL does not index FK columns automatically. These indexes
-- keep lookups and joins off sequential scans. They are not unique
-- constraints; adding those requires a separate duplicate check.
-- =========================================================

create index if not exists idx_refresh_tokens_token_id
    on refresh_tokens (token_id);

create index if not exists idx_role_permissions_role_permission_id
    on role_permissions (role_permission_id);

create index if not exists idx_admins_admin_id
    on admins (admin_id);

create index if not exists idx_employees_employee_id
    on employees (employee_id);

create index if not exists idx_attribute_values_attribute_value_id
    on attribute_values (attribute_value_id);

create index if not exists idx_product_attribute_values_product_id
    on product_attribute_values (product_id);

create index if not exists idx_product_attribute_values_product_value_id
    on product_attribute_values (product_value_id);

create index if not exists idx_variant_attribute_values_variant_value_id
    on variant_attribute_values (variant_value_id);

create index if not exists idx_media_media_id
    on media (media_id);

create index if not exists idx_cart_items_cart_item_id
    on cart_items (cart_item_id);

create index if not exists idx_payments_payment_id
    on payments (payment_id);

create index if not exists idx_reviews_review_id
    on reviews (review_id);

create index if not exists idx_notifications_notification_id
    on notifications (notification_id);
