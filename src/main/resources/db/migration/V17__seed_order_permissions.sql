-- =========================================================
-- V17: Order permissions for shop owner/employee
--
-- Staff (ADMIN, EMPLOYEE) can list orders and accept/reject pending
-- ones. Permissions follow the {resource}:{action} convention from V16.
-- Idempotent: ON CONFLICT DO NOTHING.
-- =========================================================

INSERT INTO permissions (permission_id, permission_code, permission_name, permission_resource, permission_action, permission_description, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440701', 'order:read', 'Read Order', 'order', 'read', 'View all orders', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440702', 'order:update', 'Update Order', 'order', 'update', 'Accept or reject pending orders', now(), now())
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
CROSS JOIN permissions p
WHERE lower(r.role_code) IN ('admin', 'employee')
  AND p.permission_code IN ('order:read', 'order:update')
ON CONFLICT DO NOTHING;
