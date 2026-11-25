-- =========================================================
-- V39: Review moderation permissions
--
-- Seeds review read/delete and grants them to the admin role
-- only. No schema change.
--
-- Idempotent: ON CONFLICT DO NOTHING.
-- =========================================================

INSERT INTO permissions (permission_id, permission_code, permission_name,
                         permission_resource, permission_action,
                         permission_description, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655441001', 'review:read', 'Read Review',
   'review', 'read', 'View customer reviews in the admin portal', now(), now()),
  ('550e8400-e29b-41d4-a716-446655441002', 'review:delete', 'Delete Review',
   'review', 'delete', 'Permanently delete customer reviews', now(), now())
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p
  ON p.permission_code IN ('review:read', 'review:delete')
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;
