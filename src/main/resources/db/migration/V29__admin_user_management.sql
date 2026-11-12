-- =========================================================
-- V29: Admin user management
--
-- Adds the profile columns needed to store an admin's phone/address and
-- seeds the user-management permissions for the admin role only.
--
-- employee_phone becomes nullable for legacy admins that were created
-- before admin phone storage existed. The create API still requires a
-- phone when creating either staff profile.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS / DROP NOT NULL / ON CONFLICT
-- DO NOTHING. Permission IDs follow the V16/V17/V21 convention and are
-- deterministic; role grants join roles by lower(role_code), as V16 did.
-- =========================================================

-- ---------------------------------------------------------
-- 1. Profile columns
-- ---------------------------------------------------------

ALTER TABLE admins
    ADD COLUMN IF NOT EXISTS admin_phone VARCHAR(20) NULL;

ALTER TABLE admins
    ADD COLUMN IF NOT EXISTS admin_address VARCHAR(255) NULL;

ALTER TABLE employees
    ALTER COLUMN employee_phone DROP NOT NULL;

-- ---------------------------------------------------------
-- 2. USER permissions (resource block 08)
-- ---------------------------------------------------------

INSERT INTO permissions (permission_id, permission_code, permission_name,
                         permission_resource, permission_action,
                         permission_description, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440801', 'user:read', 'Read User',
   'user', 'read', 'View admin user accounts', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440802', 'user:create', 'Create User',
   'user', 'create', 'Create staff accounts', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440803', 'user:update', 'Update User',
   'user', 'update', 'Lock, unlock, or change the role of a user', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440804', 'user:delete', 'Delete User',
   'user', 'delete', 'Soft-delete user accounts', now(), now())
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------
-- 3. Grants: ADMIN only
-- ---------------------------------------------------------

INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p
  ON p.permission_code IN ('user:read', 'user:create', 'user:update', 'user:delete')
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;

-- =========================================================
-- End of V29
-- =========================================================
