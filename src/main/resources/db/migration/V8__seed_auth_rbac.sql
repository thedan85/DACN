-- =========================================================
-- V8: Seed RBAC — roles, permissions, role_permissions
--
-- Tao 3 roles (ADMIN, EMPLOYEE, CUSTOMER), permissions cho catalog,
-- va mapping role-permission. Idempotent: chay lai khong loi.
--
-- Date: 2026-10-15
-- =========================================================

-- ---------------------------------------------------------
-- 1. ROLES (3 system roles)
-- ---------------------------------------------------------
-- KHONG ghi cot id (de DB GENERATED ALWAYS AS IDENTITY tu sinh)
-- Chi ghi role_id (UUID co dinh), role_code (UPPERCASE), role_is_system=true

INSERT INTO roles (role_id, role_code, role_name, role_description, role_is_system, is_deleted, created_at, updated_at)
VALUES 
  ('550e8400-e29b-41d4-a716-446655440001', 'ADMIN', 'Administrator', 'Full system access', true, false, now(), now()),
  ('550e8400-e29b-41d4-a716-446655440002', 'EMPLOYEE', 'Employee', 'Catalog management access', true, false, now(), now()),
  ('550e8400-e29b-41d4-a716-446655440003', 'CUSTOMER', 'Customer', 'Read-only catalog access', true, false, now(), now())
ON CONFLICT (role_code) DO NOTHING;

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
ON CONFLICT (permission_code) DO NOTHING;

-- ---------------------------------------------------------
-- 3. ROLE-PERMISSION MAPPING
-- ---------------------------------------------------------
-- ADMIN: all 24 permissions
-- EMPLOYEE: all 24 permissions (catalog write + read)
-- CUSTOMER: 6 read-only permissions

-- Helper: generate role_permission_id from role + permission UUID
-- Format: role UUID last 4 chars + permission UUID last 4 chars = 8 chars unique

-- ADMIN (role ...0001) -> ALL permissions
INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
VALUES 
  ('550e8400-e29b-41d4-a716-446655441101', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440101', now()),
  ('550e8400-e29b-41d4-a716-446655441102', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440102', now()),
  ('550e8400-e29b-41d4-a716-446655441103', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440103', now()),
  ('550e8400-e29b-41d4-a716-446655441104', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440104', now()),
  ('550e8400-e29b-41d4-a716-446655441201', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440201', now()),
  ('550e8400-e29b-41d4-a716-446655441202', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440202', now()),
  ('550e8400-e29b-41d4-a716-446655441203', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440203', now()),
  ('550e8400-e29b-41d4-a716-446655441204', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440204', now()),
  ('550e8400-e29b-41d4-a716-446655441301', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440301', now()),
  ('550e8400-e29b-41d4-a716-446655441302', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440302', now()),
  ('550e8400-e29b-41d4-a716-446655441303', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440303', now()),
  ('550e8400-e29b-41d4-a716-446655441304', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440304', now()),
  ('550e8400-e29b-41d4-a716-446655441401', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440401', now()),
  ('550e8400-e29b-41d4-a716-446655441402', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440402', now()),
  ('550e8400-e29b-41d4-a716-446655441403', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440403', now()),
  ('550e8400-e29b-41d4-a716-446655441404', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440404', now()),
  ('550e8400-e29b-41d4-a716-446655441501', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440501', now()),
  ('550e8400-e29b-41d4-a716-446655441502', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440502', now()),
  ('550e8400-e29b-41d4-a716-446655441503', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440503', now()),
  ('550e8400-e29b-41d4-a716-446655441504', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440504', now()),
  ('550e8400-e29b-41d4-a716-446655441601', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440601', now()),
  ('550e8400-e29b-41d4-a716-446655441602', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440602', now()),
  ('550e8400-e29b-41d4-a716-446655441603', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440603', now()),
  ('550e8400-e29b-41d4-a716-446655441604', '550e8400-e29b-41d4-a716-446655440001', '550e8400-e29b-41d4-a716-446655440604', now()),

  -- EMPLOYEE (role ...0002) -> ALL permissions (same as ADMIN for catalog)
  ('550e8400-e29b-41d4-a716-446655442101', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440101', now()),
  ('550e8400-e29b-41d4-a716-446655442102', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440102', now()),
  ('550e8400-e29b-41d4-a716-446655442103', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440103', now()),
  ('550e8400-e29b-41d4-a716-446655442104', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440104', now()),
  ('550e8400-e29b-41d4-a716-446655442201', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440201', now()),
  ('550e8400-e29b-41d4-a716-446655442202', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440202', now()),
  ('550e8400-e29b-41d4-a716-446655442203', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440203', now()),
  ('550e8400-e29b-41d4-a716-446655442204', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440204', now()),
  ('550e8400-e29b-41d4-a716-446655442301', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440301', now()),
  ('550e8400-e29b-41d4-a716-446655442302', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440302', now()),
  ('550e8400-e29b-41d4-a716-446655442303', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440303', now()),
  ('550e8400-e29b-41d4-a716-446655442304', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440304', now()),
  ('550e8400-e29b-41d4-a716-446655442401', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440401', now()),
  ('550e8400-e29b-41d4-a716-446655442402', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440402', now()),
  ('550e8400-e29b-41d4-a716-446655442403', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440403', now()),
  ('550e8400-e29b-41d4-a716-446655442404', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440404', now()),
  ('550e8400-e29b-41d4-a716-446655442501', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440501', now()),
  ('550e8400-e29b-41d4-a716-446655442502', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440502', now()),
  ('550e8400-e29b-41d4-a716-446655442503', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440503', now()),
  ('550e8400-e29b-41d4-a716-446655442504', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440504', now()),
  ('550e8400-e29b-41d4-a716-446655442601', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440601', now()),
  ('550e8400-e29b-41d4-a716-446655442602', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440602', now()),
  ('550e8400-e29b-41d4-a716-446655442603', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440603', now()),
  ('550e8400-e29b-41d4-a716-446655442604', '550e8400-e29b-41d4-a716-446655440002', '550e8400-e29b-41d4-a716-446655440604', now()),

  -- CUSTOMER (role ...0003) -> READ-ONLY (6 permissions: *:read)
  ('550e8400-e29b-41d4-a716-446655443102', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440102', now()),
  ('550e8400-e29b-41d4-a716-446655443202', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440202', now()),
  ('550e8400-e29b-41d4-a716-446655443302', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440302', now()),
  ('550e8400-e29b-41d4-a716-446655443402', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440402', now()),
  ('550e8400-e29b-41d4-a716-446655443502', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440502', now()),
  ('550e8400-e29b-41d4-a716-446655443602', '550e8400-e29b-41d4-a716-446655440003', '550e8400-e29b-41d4-a716-446655440602', now())
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- =========================================================
-- End of V8
-- =========================================================
