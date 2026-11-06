-- =========================================================
-- V29: Admin user management
--
-- Reconstructed from the applied live migration on 2026-11-06.
-- The original V29 file was missing from the repository.
-- Effects: admin contact columns, user-management permissions,
-- and grants to the admin role.
-- =========================================================

alter table admins add column if not exists admin_phone varchar(20);
alter table admins add column if not exists admin_address varchar(255);

insert into permissions (
    permission_id,
    permission_code,
    permission_name,
    permission_resource,
    permission_action,
    permission_description
)
values
    ('550e8400-e29b-41d4-a716-446655440801', 'user:read',   'Read User',   'user', 'read',   'View admin user accounts'),
    ('550e8400-e29b-41d4-a716-446655440802', 'user:create', 'Create User', 'user', 'create', 'Create staff accounts'),
    ('550e8400-e29b-41d4-a716-446655440803', 'user:update', 'Update User', 'user', 'update', 'Lock, unlock, or change the role of a user'),
    ('550e8400-e29b-41d4-a716-446655440804', 'user:delete', 'Delete User', 'user', 'delete', 'Soft-delete user accounts')
on conflict (permission_id) do nothing;

insert into role_permissions (role_permission_id, role_id, permission_id)
select v.role_permission_id, r.id, p.id
from (values
    ('rp-admin-user:read',   '550e8400-e29b-41d4-a716-446655440801'),
    ('rp-admin-user:create', '550e8400-e29b-41d4-a716-446655440802'),
    ('rp-admin-user:update', '550e8400-e29b-41d4-a716-446655440803'),
    ('rp-admin-user:delete', '550e8400-e29b-41d4-a716-446655440804')
) as v(role_permission_id, permission_id)
join roles r on r.role_code = 'admin'
join permissions p on p.permission_id = v.permission_id
on conflict (role_id, permission_id) do nothing;
