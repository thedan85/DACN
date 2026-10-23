-- =========================================================
-- V16: Seed RBAC — roles, permissions, role_permissions
-- (renamed from V8 to V9, then to V16 on 2026-10-22: V9 was already taken by
-- V9__lowercase_product_names.sql on this branch, and Flyway requires a unique
-- version per migration)
--
-- Huong B (2026-10-16): khong viet cung role_id. V8 da seed 3 roles
-- lowercase (role-customer/role-admin/role-employee); V9 chi bo sung
-- permissions + grants, join theo lower(role_code) + permission_code.
-- Idempotent: chay lai khong loi (ON CONFLICT DO NOTHING tran).
--
-- Date: 2026-10-15
-- =========================================================

-- ---------------------------------------------------------
-- 1. ROLES (3 system roles)
-- ---------------------------------------------------------
-- V8 da co customer/admin/employee (lowercase). Insert nay giu
-- de DB trong (fresh) van co roles; neu lower(role_code) da ton
-- tai (uq_roles_code) thi DO NOTHING tran -> giu lai rows cua V8.

INSERT INTO roles (role_id, role_code, role_name, role_description, role_is_system, is_deleted, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440001', 'ADMIN', 'Administrator', 'Full system access', true, false, now(), now()),
  ('550e8400-e29b-41d4-a716-446655440002', 'EMPLOYEE', 'Employee', 'Catalog management access', true, false, now(), now()),
  ('550e8400-e29b-41d4-a716-446655440003', 'CUSTOMER', 'Customer', 'Read-only catalog access', true, false, now(), now())
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------
-- 2. PERMISSIONS (24 permissions: 6 resources × 4 actions)
-- ---------------------------------------------------------
-- Resources: product, category, variant, attribute, attribute-value, media
-- Actions: create, read, update, delete
-- Permission code format: {resource}:{action}

INSERT INTO permissions (permission_id, permission_code, permission_name, permission_resource, permission_action, permission_description, created_at, updated_at)
VALUES
  -- product permissions (0101-0104)
  ('550e8400-e29b-41d4-a716-446655440101', 'product:create', 'Create Product', 'product', 'create', 'Create new products', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440102', 'product:read', 'Read Product', 'product', 'read', 'View products', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440103', 'product:update', 'Update Product', 'product', 'update', 'Edit products', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440104', 'product:delete', 'Delete Product', 'product', 'delete', 'Delete products', now(), now()),

  -- category permissions (0201-0204)
  ('550e8400-e29b-41d4-a716-446655440201', 'category:create', 'Create Category', 'category', 'create', 'Create new categories', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440202', 'category:read', 'Read Category', 'category', 'read', 'View categories', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440203', 'category:update', 'Update Category', 'category', 'update', 'Edit categories', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440204', 'category:delete', 'Delete Category', 'category', 'delete', 'Delete categories', now(), now()),

  -- variant permissions (0301-0304)
  ('550e8400-e29b-41d4-a716-446655440301', 'variant:create', 'Create Variant', 'variant', 'create', 'Create product variants', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440302', 'variant:read', 'Read Variant', 'variant', 'read', 'View variants', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440303', 'variant:update', 'Update Variant', 'variant', 'update', 'Edit variants', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440304', 'variant:delete', 'Delete Variant', 'variant', 'delete', 'Delete variants', now(), now()),

  -- attribute permissions (0401-0404)
  ('550e8400-e29b-41d4-a716-446655440401', 'attribute:create', 'Create Attribute', 'attribute', 'create', 'Create product attributes', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440402', 'attribute:read', 'Read Attribute', 'attribute', 'read', 'View attributes', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440403', 'attribute:update', 'Update Attribute', 'attribute', 'update', 'Edit attributes', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440404', 'attribute:delete', 'Delete Attribute', 'attribute', 'delete', 'Delete attributes', now(), now()),

  -- attribute-value permissions (0501-0504)
  ('550e8400-e29b-41d4-a716-446655440501', 'attribute-value:create', 'Create Attribute Value', 'attribute-value', 'create', 'Create attribute values', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440502', 'attribute-value:read', 'Read Attribute Value', 'attribute-value', 'read', 'View attribute values', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440503', 'attribute-value:update', 'Update Attribute Value', 'attribute-value', 'update', 'Edit attribute values', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440504', 'attribute-value:delete', 'Delete Attribute Value', 'attribute-value', 'delete', 'Delete attribute values', now(), now()),

  -- media permissions (0601-0604)
  ('550e8400-e29b-41d4-a716-446655440601', 'media:create', 'Create Media', 'media', 'create', 'Upload media files', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440602', 'media:read', 'Read Media', 'media', 'read', 'View media', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440603', 'media:update', 'Update Media', 'media', 'update', 'Edit media', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440604', 'media:delete', 'Delete Media', 'media', 'delete', 'Delete media', now(), now())
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------
-- 3. ROLE-PERMISSION MAPPING (khong viet cung role_id)
-- ---------------------------------------------------------
-- ADMIN: all 24 permissions
-- EMPLOYEE: all 24 permissions (catalog write + read)
-- CUSTOMER: 6 read-only permissions (*:read)
-- Join theo lower(role_code) + permission_code; role_permission_id
-- xac dinh: 'rp-' || lower(role_code) || '-' || permission_code.

-- ADMIN + EMPLOYEE -> ALL permissions (24 + 24)
INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
CROSS JOIN permissions p
WHERE lower(r.role_code) IN ('admin', 'employee')
ON CONFLICT DO NOTHING;

-- CUSTOMER -> READ-ONLY (6 permissions: *:read)
INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'product:read',
  'category:read',
  'variant:read',
  'attribute:read',
  'attribute-value:read',
  'media:read'
)
WHERE lower(r.role_code) = 'customer'
ON CONFLICT DO NOTHING;

-- =========================================================
-- End of V16
-- =========================================================
