# Step 2 Result — Admin User List and Detail

## Summary

Implemented the two read endpoints and their access rule:

- `GET /api/v1/admin/users`
- `GET /api/v1/admin/users/{userId}`

Both require `user:read`.

The list uses native SQL with a single count query plus a single page query, selects profile fields with a `CASE` on the user's **current** role, hides `DELETED` users, normalizes `roleCode` to lowercase, rejects `status=DELETED` with 400, rejects unknown sort fields with 400, and returns an empty page for an unknown role code.

## Changed / added files

Production:

- `src/main/java/com/project/dacn/repository/auth/AdminUserQueryRepository.java`
- `src/main/java/com/project/dacn/service/auth/AdminUserService.java`
- `src/main/java/com/project/dacn/controller/auth/AdminUserController.java`
- `src/main/java/com/project/dacn/dto/response/auth/AdminUserResponse.java`
- `src/main/java/com/project/dacn/dto/response/auth/AdminUserDetailResponse.java`
- `src/main/java/com/project/dacn/config/SecurityConfig.java` — added `GET /api/v1/admin/users` and `/api/v1/admin/users/**` → `Permissions.USER_READ`

Tests:

- `src/test/java/com/project/dacn/AdminUserControllerTest.java`

No payment, order, cart, catalog, or review code was changed. `PaymentServiceTest` was not touched.

## Endpoint behavior

### `GET /api/v1/admin/users`

Supported query parameters:

- `keyword` — matches email or the current-role profile name
- `roleCode` — case-insensitive; unknown role returns an empty page
- `status` — `ACTIVE` or `LOCKED`; `DELETED` returns 400
- `page`, `size` — pagination
- `sort` — allowed fields: `email`, `name`, `roleCode`, `status`, `lastLogin`, `createdAt`

Behavior:

- Deleted users are always hidden.
- Profile fields are selected by current role only; stale old-role profiles are not used.
- `roleCode` is returned in lowercase.
- Unknown sort fields return 400.
- List read path is one count query plus one page query.

### `GET /api/v1/admin/users/{userId}`

- Returns user summary, current-role profile name/phone/address, role, status, and permissions.
- Deleted or missing users return 404.
- Permissions are sorted and returned as permission codes.
- Password hash is never included in the response.

## Test evidence

Command:

```powershell
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 548, Failures: 1, Errors: 0, Skipped: 10
```

The only failing suite is the known payment test:

```text
com.project.dacn.service.payment.PaymentServiceTest
Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
PaymentServiceTest.createUrlSignsOrderIdAndWholeVndAmount:67
```

The new suite is green:

```text
com.project.dacn.AdminUserControllerTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

Test count increased from the 538 baseline to **548** (+10). No new failures were introduced.

Coverage includes:

- 401 without token
- 403 for customer and employee without `user:*`
- 200 for admin
- keyword by email and current-role name
- status and role filters
- unknown role returns an empty page
- pagination
- valid and rejected sort fields
- deleted users hidden from list and detail
- no `passwordHash` in JSON
- detail returns permissions
- current-role profile selection for a user with a stale old-role profile

## `V29__admin_user_management.sql` (full text)

```sql
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
```

V29 has **not** been executed against PostgreSQL/Supabase; Flyway was not run.
