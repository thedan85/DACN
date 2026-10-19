-- =========================================================
-- V11: align seeded customer ids with CUS_<slug(user_email)>.
--
-- Customer/Admin/Employee.buildBusinessId now derives the id from the
-- linked user's email (the only unique natural key those tables have; their
-- own *_name columns are not unique). V8 seeded customers with 'demo-*'
-- values, so rewrite them the same way for existing rows.
--
-- carts.customer_id, orders.customer_id and reviews.customer_id all use
-- ON UPDATE CASCADE, so children follow this update automatically.
-- Admins/employees have no seed rows.
-- =========================================================

UPDATE customers c
SET customer_id = 'CUS_' || btrim(regexp_replace(lower(u.user_email), '[^a-z0-9]+', '-', 'g'), '-')
FROM users u
WHERE u.user_id = c.user_id
  AND c.customer_id <> 'CUS_' || btrim(regexp_replace(lower(u.user_email), '[^a-z0-9]+', '-', 'g'), '-');
