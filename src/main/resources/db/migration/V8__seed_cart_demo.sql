-- =========================================================
-- V8: Seed cart demo data (roles/users/customers/carts/items)
--
-- Purpose: give cart owner runnable demo rows to exercise all
--   /api/v1/cart endpoints without hand-inserting FK chains.
-- Design:
--   * Deterministic business keys (demo-*) so re-runs are idempotent
--     via ON CONFLICT (same convention as V5-V7).
--   * demo-customer-1 HAS cart with 2 items (GET shows totals,
--     POST same variant bumps, PATCH/DELETE work, checkout works).
--   * demo-customer-2 has NO cart row (GET auto-creates empty).
--   * demo-customer-3 HAS empty cart row (checkout -> 400 empty).
--   * Variants referenced are V6/V7 seeds (stock 50 each):
--       ember-tetra-default  15000.00
--       neon-tetra-default    8000.00  (2*15000 + 1*8000 = 38000, qty 3)
--   * Password hashes are fake placeholders (auth is permitAll + JWT
--     TODO; never use these rows for real login).
-- =========================================================

-- ---------- ROLES ----------
INSERT INTO roles (role_id, role_code, role_name, role_description, role_is_system, is_deleted) VALUES
    ('role-customer', 'customer', 'Customer', 'Customer role for storefront', TRUE, FALSE),
    ('role-admin',    'admin',    'Admin',    'Admin role',                    TRUE, FALSE),
    ('role-employee', 'employee', 'Employee', 'Employee role',                TRUE, FALSE)
ON CONFLICT (role_id) DO UPDATE SET
    role_code        = EXCLUDED.role_code,
    role_name        = EXCLUDED.role_name,
    role_description = EXCLUDED.role_description;

-- ---------- USERS (demo logins, fake hashes) ----------
INSERT INTO users (user_id, user_email, user_password_hash, user_status, role_id) VALUES
    ('user-cart-demo-1', 'cart-demo-1@example.com', 'DEMO-HASH-NOT-FOR-LOGIN', 'ACTIVE', 'role-customer'),
    ('user-cart-demo-2', 'cart-demo-2@example.com', 'DEMO-HASH-NOT-FOR-LOGIN', 'ACTIVE', 'role-customer'),
    ('user-cart-demo-3', 'cart-demo-3@example.com', 'DEMO-HASH-NOT-FOR-LOGIN', 'ACTIVE', 'role-customer')
ON CONFLICT (user_id) DO UPDATE SET
    user_email = EXCLUDED.user_email,
    role_id    = EXCLUDED.role_id;

-- ---------- CUSTOMERS ----------
INSERT INTO customers (customer_id, user_id, customer_name, customer_address, customer_phone) VALUES
    ('demo-customer-1', 'user-cart-demo-1', 'Demo Customer 1', '123 Demo St, HCMC', '0901111111'),
    ('demo-customer-2', 'user-cart-demo-2', 'Demo Customer 2', '456 Demo St, HCMC', '0902222222'),
    ('demo-customer-3', 'user-cart-demo-3', 'Demo Customer 3', '789 Demo St, HCMC', '0903333333')
ON CONFLICT (customer_id) DO UPDATE SET
    customer_name    = EXCLUDED.customer_name,
    customer_address = EXCLUDED.customer_address,
    customer_phone   = EXCLUDED.customer_phone;

-- ---------- CARTS (1 per customer; demo-2 intentionally missing) ----------
INSERT INTO carts (cart_id, customer_id) VALUES
    ('demo-cart-1', 'demo-customer-1'),
    ('demo-cart-3', 'demo-customer-3')
ON CONFLICT (customer_id) DO NOTHING;

-- ---------- CART ITEMS (demo-cart-1: 2 lines, qty 3, total 38000) ----------
INSERT INTO cart_items (cart_item_id, cart_id, variant_id, quantity) VALUES
    ('demo-cart-item-1', 'demo-cart-1', 'ember-tetra-default', 2),
    ('demo-cart-item-2', 'demo-cart-1', 'neon-tetra-default',  1)
ON CONFLICT (cart_id, variant_id) DO UPDATE SET
    quantity = EXCLUDED.quantity;
