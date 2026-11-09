# Step 3 Result — Admin User Mutations

## Summary

Implemented the admin user mutation endpoints:

- `POST /api/v1/admin/users`
- `PATCH /api/v1/admin/users/{userId}/status`
- `PATCH /api/v1/admin/users/{userId}/role`
- `DELETE /api/v1/admin/users/{userId}`

Permissions:

- `POST` requires `user:create`
- `PATCH` requires `user:update`
- `DELETE` requires `user:delete`

The branch switch had reverted all Step 3 files and the approved Step 2 follow-up A SQL. Both were restored and verified.

## Changed / added files

Production:

- `src/main/java/com/project/dacn/dto/request/auth/AdminUserCreateRequest.java`
- `src/main/java/com/project/dacn/dto/request/auth/AdminUserStatusRequest.java`
- `src/main/java/com/project/dacn/dto/request/auth/AdminUserRoleRequest.java`
- `src/main/java/com/project/dacn/common/exception/UserRuleViolationException.java`
- `src/main/java/com/project/dacn/config/MessageSourceConfig.java`
- `src/main/java/com/project/dacn/service/auth/AdminUserService.java`
- `src/main/java/com/project/dacn/controller/auth/AdminUserController.java`
- `src/main/java/com/project/dacn/repository/auth/UserRepository.java`
- `src/main/java/com/project/dacn/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/project/dacn/config/SecurityConfig.java`
- `src/main/resources/messages/messages.properties`
- `src/main/resources/messages/messages_vi.properties`

Tests:

- `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` — 17 tests

Step 2 follow-up A:

- `src/main/java/com/project/dacn/repository/auth/AdminUserQueryRepository.java` — restored conditional, escaped native SQL
- `src/test/java/com/project/dacn/AdminUserControllerTest.java` — added literal `%` / `_` regression test; now 11 tests

`V29__admin_user_management.sql`, `Permissions.java`, and the Step 1 token-filter work were already present and unchanged.

No payment, order, cart, catalog, or review code was changed. No report files were created before this documentation request.

## Endpoint behavior

### `POST /api/v1/admin/users`

- Creates only `employee` or `admin`.
- Email is trimmed and lowercased.
- Duplicate check includes soft-deleted users through `findByEmailIncludingDeleted`.
- `saveAndFlush` maps `DataIntegrityViolationException` to `409 error.email.exists`.
- Creates the matching employee or admin profile in the same transaction.
- Stores a BCrypt password hash.
- Never returns a password or password hash.
- Returns `201` with `ApiResponse` and the created user detail.

### `PATCH /api/v1/admin/users/{userId}/status`

- Accepts only `ACTIVE` or `LOCKED`.
- Locking revokes all refresh tokens for the target.
- Unlocking does not restore revoked tokens.
- Self-lock returns `409 error.user.self.lock`.
- Last active administrator guard returns `409 error.user.last.admin`.
- Deleted or missing target returns `404`.
- Returns `200` with the updated user detail.

### `PATCH /api/v1/admin/users/{userId}/role`

- Accepts only `employee` and `admin`.
- Customer targets return `409 error.user.customer.role`.
- Invalid role values return `409 error.user.role.invalid`.
- Creating a target profile from the current profile when missing.
- The old profile is never deleted.
- Self-demote returns `409 error.user.self.demote`.
- Last active administrator guard returns `409 error.user.last.admin`.
- Returns `200` with the updated user detail, including permissions.

### `DELETE /api/v1/admin/users/{userId}`

- Soft deletes by setting `DELETED`.
- Revokes all refresh tokens.
- Self-delete returns `409 error.user.self.delete`.
- Last active administrator guard returns `409 error.user.last.admin`.
- Deleted or missing target returns `404`.
- Returns `200` with `ApiResponse` and `data: null`.

## Safety and audit rules

- Self lock / delete / demote checks run before the last-admin guard.
- The last-admin guard locks active admin rows with `@Lock(PESSIMISTIC_WRITE)`.
- Every mutation logs at INFO with actor, target, and action:
  - `CREATE`
  - `STATUS_ACTIVE` / `STATUS_LOCKED`
  - `ROLE_ADMIN` / `ROLE_EMPLOYEE`
  - `DELETE`
- Passwords and tokens are never logged.
- `GlobalExceptionHandler` resolves `UserRuleViolationException` message keys through `MessageSource` and returns HTTP 409.
- `SecurityConfig` adds POST/PATCH/DELETE rules after the existing admin rules.
- Controller methods also carry `@PreAuthorize`, and the controller has `@SecurityRequirement(name = "bearerAuth")`.

## Step 2 follow-up A — native SQL

The native count and list queries share a base join/where clause and append optional filters only when nonblank. There is no `(:param IS NULL OR ...)` and no empty-string sentinel.

```sql
SELECT count(*)
FROM users u
JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
LEFT JOIN customers c ON c.user_id = u.id
LEFT JOIN employees e ON e.user_id = u.id
LEFT JOIN admins a ON a.user_id = u.id
WHERE u.user_status <> 'DELETED'
```

Optional clauses, appended only when supplied:

```sql
AND (lower(u.user_email) LIKE :keywordPattern ESCAPE '!'
     OR lower(CASE lower(r.role_code)
                WHEN 'customer' THEN c.customer_name
                WHEN 'employee' THEN e.employee_name
                WHEN 'admin' THEN a.admin_name
              END) LIKE :keywordPattern ESCAPE '!')
AND lower(r.role_code) = :roleCode
AND u.user_status = :status
```

```sql
SELECT u.user_id,
       u.user_email,
       CASE lower(r.role_code)
           WHEN 'customer' THEN c.customer_name
           WHEN 'employee' THEN e.employee_name
           WHEN 'admin' THEN a.admin_name
       END AS name,
       CASE lower(r.role_code)
           WHEN 'customer' THEN c.customer_phone
           WHEN 'employee' THEN e.employee_phone
           WHEN 'admin' THEN a.admin_phone
       END AS phone,
       CASE lower(r.role_code)
           WHEN 'customer' THEN c.customer_address
           WHEN 'employee' THEN e.employee_address
           WHEN 'admin' THEN a.admin_address
       END AS address,
       lower(r.role_code) AS role_code,
       r.role_name AS role_name,
       u.user_status AS status,
       u.user_last_login AS last_login,
       u.created_at AS created_at
FROM users u
JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
LEFT JOIN customers c ON c.user_id = u.id
LEFT JOIN employees e ON e.user_id = u.id
LEFT JOIN admins a ON a.user_id = u.id
WHERE u.user_status <> 'DELETED'
-- optional clauses above
ORDER BY u.created_at DESC, u.id ASC
LIMIT :limit OFFSET :offset
```

`keywordPattern` is always bound as `"%" + escapeLike(keyword) + "%"`. `escapeLike` maps:

- `!` → `!!`
- `%` → `!%`
- `_` → `!_`

`ESCAPE '!'` then treats user wildcards literally. The keyword is lowercased in the service and repository; it is never concatenated into SQL.

PostgreSQL cannot reliably infer a bare parameter type in `:param IS NULL`; the conditional append avoids that and also avoids the `OR` shape that can defeat index use. No real database was contacted.

## Step 2 follow-up B — V29 vs V21

V29 uses the same grant shape as V21: deterministic text key `rp-...`, surrogate `r.id` / `p.id`, lowercase role filter, and `ON CONFLICT DO NOTHING`.

V29 grant:

```sql
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
```

V21 statement:

```sql
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
```

V12 converted `role_permissions.role_id` and `permission_id` to `BIGINT`, so V29’s `r.id` / `p.id` usage matches V21 exactly.

## Test evidence

Focused admin suites:

```powershell
.\mvnw.cmd "-Dtest=AdminUserControllerTest,AdminUserMutationControllerTest" test
```

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

Full clean suite:

```powershell
.\mvnw.cmd clean test
```

```text
Tests run: 609, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
Total time: 01:37 min
```

The old allowed payment failure name is not present on this branch; the current equivalent `PaymentServiceTest.createUrlSignsPaymentIdAndWholeVndAmount` passed.

Coverage includes:

- 401 without token for POST/PATCH/DELETE
- 403 for customer without `user:*`
- 2xx for admin on each endpoint
- create employee profile
- create admin profile
- BCrypt password storage
- no password/password hash in JSON
- duplicate email with different case → 409
- soft-deleted email → 409
- invalid input → 400
- customer role create → 409
- lock → new login 403, refresh 401, old access token 401
- unlock → login works
- employee → admin shows new permissions on `/auth/me` with the same access token
- customer role change → 409
- invalid role/status → 409
- self-lock / self-delete / self-demote → 409
- last active admin guard → 409
- soft delete → hidden from list, login fails, refresh/access tokens rejected
- deleted target mutations → 404

V29 was applied to the shared Supabase database on 2026-11-06 07:14:49 (Flyway `success = true`). `role_permissions` contains `rp-admin-user:read`, `rp-admin-user:create`, `rp-admin-user:update`, and `rp-admin-user:delete` for the admin role. Do not re-run V29.
