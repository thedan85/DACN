-- V21: Admin-only permission to manually mark an order completed.
-- Staff keep order:update, which only permits CONFIRMED/CANCELLED transitions.

INSERT INTO permissions (permission_id, permission_code, permission_name,
                         permission_resource, permission_action,
                         permission_description, created_at, updated_at)
VALUES
  ('550e8400-e29b-41d4-a716-446655440703', 'order:complete', 'Complete Order',
   'order', 'complete', 'Mark orders completed (admin only)', now(), now())
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p ON p.permission_code = 'order:complete'
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;
