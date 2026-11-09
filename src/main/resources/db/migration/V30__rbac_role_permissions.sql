-- =========================================================
-- V30: RBAC administration permissions
--
-- Seeds the four role-management permissions and grants them to
-- the admin role only. No schema change.
--
-- Permission IDs follow the V16/V17/V21/V29 convention. The
-- 09xx block is free: 01xx-06xx are catalog, 07xx are order,
-- 08xx are user.
--
-- Idempotent: ON CONFLICT DO NOTHING. Grants join roles by
-- lower(role_code) and permissions by code, as V16/V17/V21/V29
-- did. role_permissions uses the surrogate roles.id /
-- permissions.id after V12.
-- =========================================================

-- ---------------------------------------------------------
-- 1. ROLE permissions (resource block 09)
-- ---------------------------------------------------------

INSERT INTO permissions (permission_id, permission_code, permission_name,
                         permission_resource, permission_action,
                         permission_description, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440901', 'role:read', 'Read Role',
   'role', 'read', 'View roles and permissions', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440902', 'role:create', 'Create Role',
   'role', 'create', 'Create non-system roles', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440903', 'role:update', 'Update Role',
   'role', 'update', 'Edit role details and permissions', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440904', 'role:delete', 'Delete Role',
   'role', 'delete', 'Soft-delete unused non-system roles', now(), now())
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------
-- 2. Grants: ADMIN only
-- ---------------------------------------------------------

INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p
  ON p.permission_code IN ('role:read', 'role:create', 'role:update', 'role:delete')
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;

-- =========================================================
-- End of V30
-- =========================================================
