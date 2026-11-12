# Admin Users API — Step 0 Analysis and Proposal

**Status:** Step 0 complete. No source code or migration was created in this step.

I ran only:

```powershell
.\mvnw.cmd test
```

This is the Windows equivalent of `./mvnw test`. I did **not** start the dev profile and did **not** run Flyway against any database.

One caveat: the shell wrapper reported exit code `1` for the piped command, but Maven's own output and the Surefire XML both say the build passed:

```text
[INFO] Tests run: 530, Failures: 0, Errors: 0, Skipped: 10
[INFO] BUILD SUCCESS
```

Surefire report totals:

```text
files=66 tests=530 failures=0 errors=0 skipped=10
```

---

## a) Highest migration version and proposed new version

**Highest current version: `V28`.**

Evidence from the folder:

```text
28
27
26
25
24
```

Source file: `src/main/resources/db/migration/V28__lookup_indexes.sql`

**Proposed new migration: `V29`** (`V29__admin_user_management.sql`).

I will not create it until the team confirms there is no V29 conflict.

Note: the old `V16` history comments (`src/main/resources/db/migration/V16__seed_auth_rbac.sql:3-5`) show this project has already had a version collision before, so I will only add, never rename or reuse, a version.

---

## b) Role codes and real role IDs

**Yes, the effective seeded roles are lowercase.**

Evidence:

- `V8__seed_cart_demo.sql:21-24` seeds:
  - `('role-customer', 'customer', ...)`
  - `('role-admin', 'admin', ...)`
  - `('role-employee', 'employee', ...)`
- `V2__add_indexes.sql:85-86` creates a unique index on `lower(role_code)`.
- `V16__seed_auth_rbac.sql:22-27` tries to insert uppercase `ADMIN/EMPLOYEE/CUSTOMER` with `ON CONFLICT DO NOTHING`. On a database with V8 history, those rows conflict with the lowercase unique index and are skipped. V16's own comment at lines 7-9 confirms V8 already seeded the lowercase roles.

**Actual effective role rows from migration order:**

| role_id | role_code |
|---|---|
| `role-customer` | `customer` |
| `role-admin` | `admin` |
| `role-employee` | `employee` |

I did **not** query the shared Supabase database, so this is migration evidence, not a live row query. The test profile instead seeds uppercase codes (`RbacIntegrationTest.java:71-93`), so the implementation must use `findByCodeIgnoreCase` / `lower(role_code)` consistently.

---

## c) Required profile columns

### Employees

`V1__initial_schema.sql:150-164`:

- `employee_id NOT NULL`
- `user_id NOT NULL UNIQUE`
- `employee_name NOT NULL`
- `employee_address NULL`
- `employee_phone NOT NULL`
- `employee_status NOT NULL DEFAULT 'ACTIVE'`

`Employee.java:45-46` still declares `employee_phone` as `nullable = false`.

`V23__google_auth_nullable_fields.sql:1-3` only relaxed `users.user_password_hash` and `customers.customer_phone`. It did **not** relax `employees.employee_phone`.

### Admins

`V1__initial_schema.sql:122-134`:

- `admin_id NOT NULL`
- `user_id NOT NULL UNIQUE`
- `admin_name NOT NULL`
- `admin_status NOT NULL DEFAULT 'ACTIVE'`

**There is no `admin_phone` and no `admin_address` column.**

`Admin.java:39-45` confirms the entity has only `name` and `status`.

### Customers

`V1__initial_schema.sql:136-148`:

- `customer_name NOT NULL`
- `customer_address NULL`
- `customer_phone NOT NULL` originally

`V23__google_auth_nullable_fields.sql:3` dropped the NOT NULL constraint on `customers.customer_phone`. `Customer.java:42-43` matches that.

### Consequence

The requested API requires a `phone` on create and returns `phone` from the matching profile table. That is possible for employees and customers, but **not for admins with the current schema**. This is a blocking design issue; see the decision section at the end.

---

## d) `User.@SQLRestriction` and soft-deleted emails

`User.java:29-30`:

```java
@Table(name = "users")
@SQLRestriction("user_status <> 'DELETED'")
```

Hibernate applies this restriction to normal JPQL/derived entity queries, including:

- `UserRepository.findByEmail` (`UserRepository.java:17-18`)
- `UserRepository.findByUserId` (`UserRepository.java:14-15`)
- `UserRepository.existsByEmail` (`UserRepository.java:31`)

So `findByEmail` **cannot** find a soft-deleted user.

The repository already has native escape hatches:

```java
// UserRepository.java:23-26
// Native queries bypass @SQLRestriction ...
@Query(value = "SELECT * FROM users WHERE lower(user_email) = lower(:email)", nativeQuery = true)
Optional<User> findByEmailIncludingDeleted(@Param("email") String email);
```

### What happens on re-registration with a soft-deleted email

`AuthService.register` checks `existsByEmail` (`AuthService.java:74-77`). Because of the restriction, that returns `false` for a DELETED row.

The insert then violates the unique lower-email index created in `V2__add_indexes.sql:82-83`:

```sql
create unique index if not exists uq_users_email
    on users (lower(user_email));
```

`AuthService.java:104-106` catches `DataIntegrityViolationException` and converts it to `EmailAlreadyExistsException`, which the handler maps to **409**.

So the current behaviour is: **re-registering a soft-deleted email is blocked with 409**, not allowed. The early duplicate check is misleading because it misses deleted rows, but the DB constraint prevents the duplicate.

For the admin API I will use `findByEmailIncludingDeleted` for the duplicate check so we return a clear 409 immediately, including when the existing account is DELETED.

For admin target lookups I will use `findByUserId`, which hides DELETED rows, so detail/lock/role/delete on a deleted user gives **404**, satisfying the "404 or 409" rule.

---

## e) Locked user with an unexpired access token — currently NOT rejected

**This is a real bug in the current main code.**

Evidence chain:

1. `JwtAuthenticationFilter.java:37-48` loads the current user from the DB:

```java
if (jwtService.validateAccessToken(token)) {
    UserDetails userDetails = userDetailsService
            .loadUserByUserId(jwtService.extractUserId(token));

    UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

    SecurityContextHolder.getContext().setAuthentication(authentication);
}
```

2. `CustomUserDetailsService.java:52-64` correctly marks the account:

```java
boolean enabled = user.getStatus() == UserStatus.ACTIVE;
boolean accountNonLocked = user.getStatus() != UserStatus.LOCKED;

return new org.springframework.security.core.userdetails.User(
        user.getUserId(),
        password,
        enabled,
        true,
        true,
        accountNonLocked,
        authorities
);
```

3. But the filter constructs `UsernamePasswordAuthenticationToken` **directly**. It does not go through `AuthenticationManager` / `DaoAuthenticationProvider`, which is the component that checks `isEnabled()` and `isAccountNonLocked()`.

4. `SecurityConfig.java:118` only requires:

```java
.anyRequest().authenticated()
```

and the authorization layer checks authentication and authorities, not those `UserDetails` flags.

5. `/auth/me` then loads the user by ID (`AuthService.java:341-345`). `findByUserId` filters only DELETED, not LOCKED. So a locked user's old access token still returns **200** from `/auth/me`.

Existing tests only cover:

- locked user cannot **refresh** (`AuthServiceTest.java:263-276`)
- locked user cannot **login via Google** (`AuthServiceTest.java:606-617`)

There is no test for "old access token after locking". So this must be fixed before the new tests.

**Proposed fix:** in `JwtAuthenticationFilter`, after loading `UserDetails`, check:

```java
if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
    SecurityContextHolder.clearContext();
    filterChain.doFilter(request, response);
    return;
}
```

Then no authentication is set, and the existing `AuthenticationEntryPoint` returns **401** on the next request. I will add an integration test that logs in, locks the user through the admin API, then calls `/auth/me` with the old access token and expects 401.

---

## f) Test baseline

Baseline recorded:

```text
Tests run: 530, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
```

Surefire XML totals: **66 files, 530 tests, 0 failures, 0 errors, 10 skipped.**

The new test count must be **greater than 530**, and no existing test may break.

---

## Proposed design

### 1. Package / file layout

Following the existing structure:

- Controller: `com.project.dacn.controller.auth.AdminUserController`
- Service: `com.project.dacn.service.auth.AdminUserService`
- Request DTOs:
  - `dto/request/auth/AdminUserCreateRequest`
  - `dto/request/auth/AdminUserStatusRequest`
  - `dto/request/auth/AdminUserRoleRequest`
  - `dto/request/auth/AdminUserFilter`
- Response DTOs:
  - `dto/response/auth/AdminUserResponse`
  - `dto/response/auth/AdminUserDetailResponse`
- Query repository: `repository/auth/AdminUserQueryRepository` for the native list projection, plus small additions to `UserRepository` and `RefreshTokenRepository`.
- New 409 exception: `common/exception/UserRuleViolationException`, handled in `GlobalExceptionHandler`.
- Security: add constants to `security/Permissions`, rules to `config/SecurityConfig`, and the locked-account check in `security/JwtAuthenticationFilter`.
- Migration: `V29__admin_user_management.sql`.
- Tests: `AdminUserControllerTest` (and possibly a small service test for the last-admin guard).
- Docs: `docs/admin-users-api.md`.

### 2. Endpoint contract

| Method | Path | Permission | Success |
|---|---|---|---|
| GET | `/api/v1/admin/users` | `user:read` | 200 `ApiResponse<PageResponse<AdminUserResponse>>` |
| GET | `/api/v1/admin/users/{userId}` | `user:read` | 200 `ApiResponse<AdminUserDetailResponse>` |
| POST | `/api/v1/admin/users` | `user:create` | 201 `ApiResponse<AdminUserResponse>` |
| PATCH | `/api/v1/admin/users/{userId}/status` | `user:update` | 200 `ApiResponse<AdminUserResponse>` |
| PATCH | `/api/v1/admin/users/{userId}/role` | `user:update` | 200 `ApiResponse<AdminUserResponse>` |
| DELETE | `/api/v1/admin/users/{userId}` | `user:delete` | 204 no body |

### 3. DTO shapes

`AdminUserResponse`:

```java
record AdminUserResponse(
    String userId,
    String email,
    String name,
    String phone,
    String roleCode,
    String roleName,
    UserStatus status,
    Instant lastLogin,
    Instant createdAt
) {}
```

`AdminUserDetailResponse` adds:

```java
String address,
List<String> permissions
```

`AdminUserCreateRequest`:

```java
record AdminUserCreateRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone,
    @Size(max = 255) String address,
    @NotBlank String roleCode
) {}
```

`AdminUserStatusRequest`:

```java
record AdminUserStatusRequest(@NotNull UserStatus status) {}
```

`AdminUserRoleRequest`:

```java
record AdminUserRoleRequest(@NotBlank String roleCode) {}
```

`AdminUserFilter`:

```java
record AdminUserFilter(String keyword, String roleCode, UserStatus status) {}
```

### 4. List query — no N+1

Use one native projection query plus one count query, joining:

```sql
users u
JOIN roles r ON r.id = u.role_id AND r.is_deleted = false
LEFT JOIN customers c ON c.user_id = u.id
LEFT JOIN employees e ON e.user_id = u.id
LEFT JOIN admins a ON a.user_id = u.id
```

Crucially, because role changes keep the old profile row, the name/phone/address must be selected by the **current role**, not by blind `COALESCE`:

```sql
CASE lower(r.role_code)
    WHEN 'customer' THEN c.customer_name
    WHEN 'employee' THEN e.employee_name
    WHEN 'admin' THEN a.admin_name
END
```

Same idea for phone and address. This prevents an old employee profile from overriding a new admin profile after `employee -> admin`.

Other query rules:

- Always add `u.user_status <> 'DELETED'` explicitly because native queries bypass `@SQLRestriction`.
- `keyword`: trim, lower-case, match `u.user_email` or the current-role name only, as requested (not phone).
- `roleCode`: trim, lower-case, compare `lower(r.role_code) = :roleCode`; unknown role simply returns an empty page.
- `status`: only `ACTIVE` or `LOCKED`; `DELETED` is rejected with 400.
- Sort whitelist only:
  - `email`
  - `name`
  - `roleCode`
  - `status`
  - `lastLogin`
  - `createdAt` (default `createdAt,desc`)
- Unknown sort field -> 400.
- Page default 20, max 100 (`WebConfig.java:11-16`); `PageResponse` fields are zero-based page, size, totalElements, totalPages (`PageResponse.java:13-18`).

### 5. Detail flow

One target request:

1. `userRepository.findByUserId` (filters DELETED; missing -> 404).
2. Load the profile **for the current role only**:
   - customer -> `CustomerRepository.findByUser_UserId`
   - employee -> `EmployeeRepository.findByUser_UserId`
   - admin -> `AdminRepository.findByUser_UserId`
3. Load permissions with `rolePermissionRepository.findByRole_RoleIdWithPermission` (already fetch-joins permission).
4. Return permissions sorted for stable JSON.

This is 3 queries, not N+1.

### 6. Create staff flow

`@Transactional`:

1. Normalize email: `trim().toLowerCase(Locale.ROOT)`.
2. `findByEmailIncludingDeleted`; if present -> `EmailAlreadyExistsException` -> 409.
3. Validate roleCode is `employee` or `admin` case-insensitively; `customer`/unknown -> 400.
4. `roleRepository.findByCodeIgnoreCase`.
5. BCrypt-encode password.
6. Save user with `saveAndFlush` inside a try/catch so a concurrent duplicate insert is mapped to 409, not 500.
7. Create exactly the matching profile:
   - employee -> `Employee` with name, phone, optional address, ACTIVE.
   - admin -> `Admin` with name, phone, optional address, ACTIVE **if the admin columns are approved**.
8. Log INFO: actor, target, `CREATE`.
9. Return `AdminUserResponse` only. Password/hash never appear in any DTO.

### 7. Status flow

`PATCH /{userId}/status`, `@Transactional`:

1. `findByUserId`; missing/deleted -> 404.
2. Only `ACTIVE` or `LOCKED`; `DELETED` in body -> 400.
3. If target is the actor and requested status is `LOCKED` -> 409 `error.user.self.lock`.
4. If target is an ACTIVE admin and requested status is `LOCKED`, check active-admin count after the change.
5. Set status.
6. If LOCKED, bulk-revoke all non-revoked refresh tokens for that user. Add to `RefreshTokenRepository`:

```java
@Modifying
@Query("UPDATE RefreshToken t SET t.revoked = true " +
       "WHERE t.user.userId = :userId AND t.revoked = false")
int revokeAllActiveByUserId(@Param("userId") String userId);
```

7. Unlock does not un-revoke old tokens; the user logs in again.
8. Log INFO: actor, target, `LOCK`/`UNLOCK`.

### 8. Role flow

`PATCH /{userId}/role`, `@Transactional`:

1. `findByUserId`; missing/deleted -> 404.
2. New roleCode must be `employee` or `admin`; `customer` -> 409 `error.user.customer.role`; unknown -> 400.
3. If current role is customer -> 409.
4. If target is the actor and the change is admin -> employee -> 409 `error.user.self.demote`.
5. If target is an active admin and is being demoted to employee, check active-admin count after the change.
6. Update `user.role`.
7. If the target profile for the new role is missing, create it from the current profile's name/phone/address. Do not delete the old profile.
8. Log INFO: actor, target, `CHANGE_ROLE`.
9. Return summary.

Because old profiles remain, list/detail must always select the profile by current role as described above.

### 9. Delete flow

`DELETE /{userId}`, `@Transactional`:

1. `findByUserId`; missing/deleted -> 404.
2. Self-delete -> 409 `error.user.self.delete`.
3. If target is an active admin, check active-admin count after deletion.
4. Set `user_status = DELETED`; never hard delete the row or profiles.
5. Revoke all refresh tokens.
6. Log INFO: actor, target, `DELETE`.
7. Return 204.

### 10. Last-active-admin guard

Add to `UserRepository`:

```java
@Query("SELECT count(u) FROM User u " +
       "WHERE lower(u.role.code) = 'admin' " +
       "AND u.status = :status " +
       "AND u.userId <> :excludeUserId")
long countActiveAdminsExcluding(@Param("excludeUserId") String excludeUserId,
                               @Param("status") UserStatus status);
```

For concurrency, before the count, lock the active admin rows in ID order:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT u FROM User u " +
       "WHERE lower(u.role.code) = 'admin' " +
       "AND u.status = :status ORDER BY u.id")
List<User> lockActiveAdmins(@Param("status") UserStatus status);
```

If the resulting count is 0, return 409 `error.user.last.admin`. This makes two admins locking each other serialize instead of both succeeding.

### 11. Self-check

Actor ID comes from `Authentication.getName()`, which is the immutable `userId` because `CustomUserDetailsService` puts `user.getUserId()` as the `UserDetails` username (`CustomUserDetailsService.java:57-58`).

- Self lock -> 409
- Self delete -> 409
- Self demote admin -> employee -> 409
- Self unlock / self role no-op is allowed

### 12. Security

`Permissions.java` additions:

```java
public static final String USER_READ = "user:read";
public static final String USER_CREATE = "user:create";
public static final String USER_UPDATE = "user:update";
public static final String USER_DELETE = "user:delete";
```

`SecurityConfig` rules, added after the existing admin-orders rule (`SecurityConfig.java:115-116`) and before `anyRequest()` (`SecurityConfig.java:118`):

```java
.requestMatchers(HttpMethod.GET, "/api/v1/admin/users", "/api/v1/admin/users/**")
    .hasAuthority(Permissions.USER_READ)
.requestMatchers(HttpMethod.POST, "/api/v1/admin/users")
    .hasAuthority(Permissions.USER_CREATE)
.requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/**")
    .hasAuthority(Permissions.USER_UPDATE)
.requestMatchers(HttpMethod.DELETE, "/api/v1/admin/users/**")
    .hasAuthority(Permissions.USER_DELETE)
```

Controller methods also get `@PreAuthorize("hasAuthority('...')")`, matching `ProductController.java:89-113`.

`OpenApiConfig.java:28-33` already defines the `bearerAuth` scheme and adds a global security requirement. I will add `@SecurityRequirement(name = "bearerAuth")` to the controller as requested.

### 13. Migration V29

#### Permissions

Same style as V16:

```sql
INSERT INTO permissions (
    permission_id, permission_code, permission_name,
    permission_resource, permission_action,
    permission_description, created_at, updated_at
) VALUES
  ('550e8400-e29b-41d4-a716-446655440801', 'user:read',   'Read User',   'user', 'read',   'View admin user accounts', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440802', 'user:create', 'Create User', 'user', 'create', 'Create staff accounts',    now(), now()),
  ('550e8400-e29b-41d4-a716-446655440803', 'user:update', 'Update User', 'user', 'update', 'Lock/unlock or change role', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440804', 'user:delete', 'Delete User', 'user', 'delete', 'Soft-delete user accounts', now(), now())
ON CONFLICT DO NOTHING;
```

IDs `0801-0804` are free; existing blocks are `0101-0604` (V16), `0701-0703` (V17/V21).

Grant to admin only:

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

This is deterministic and idempotent, and uses `r.id`/`p.id` as required after V12 changed the FKs to surrogate IDs.

#### Schema change needed for admin phone/address

Because `admins` has no phone/address columns, I recommend V29 also contains:

```sql
ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_phone VARCHAR(20) NULL;
ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_address VARCHAR(255) NULL;
```

Then `Admin.java` gets `phone` and `address` fields, and create/role-change populate them.

For legacy admins that have no phone (for example `R__swagger_demo_data.sql:23-29`), an `admin -> employee` change cannot create an employee profile while `employees.employee_phone` is NOT NULL. Two options are listed in the decision section.

### 14. Error handling

Reuse:

- `ResourceNotFoundException` / `AccountNotFoundException` -> 404
- `EmailAlreadyExistsException` -> 409
- `MethodArgumentNotValidException` / `IllegalArgumentException` -> 400
- `AccessDeniedException` -> 403
- `AuthenticationEntryPoint` -> 401

Add one handler:

- `UserRuleViolationException` -> 409, resolving its message key through `MessageSource`, matching the existing auth style (`GlobalExceptionHandler.java:129-174`).

New message keys in both `messages.properties` and `messages_vi.properties`:

- `error.user.self.lock`
- `error.user.self.delete`
- `error.user.self.demote`
- `error.user.last.admin`
- `error.user.customer.role`
- `error.user.role.invalid`
- `error.user.status.invalid`
- `error.user.profile.phone`
- `error.email.exists` also added to the English bundle for completeness

No password/token is ever logged.

### 15. Tests

New integration test class using `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Transactional`, seeding roles/permissions in `@BeforeEach` because `application-test.yml:10-11` disables Flyway.

Planned coverage:

- No token -> 401 for all endpoints
- Customer/employee without `user:*` -> 403
- Admin -> 200/201/204
- List filtering by keyword, roleCode, status
- Pagination and sort whitelist
- Password/hash never in JSON
- DELETED users hidden
- Create employee/admin -> correct profile row
- Duplicate email with different case -> 409
- Invalid input -> 400
- Lock:
  - new login -> 403
  - refresh -> 401
  - old access token `/auth/me` -> 401 after the JwtAuthenticationFilter fix
  - unlock -> login works
- Change role employee -> admin; `/auth/me` with the same access token shows new permissions
- Change customer role -> 409
- Self lock/delete/demote -> 409
- Last-admin guard -> 409
- Soft delete: hidden from list, login fails, refresh tokens revoked

The last-admin guard is mostly reachable in tests by granting `user:update` to an employee actor and targeting the only active admin, or via a service-level test.

### 16. Documentation

`docs/admin-users-api.md` will include:

- Each endpoint with request/response JSON captured from the tests
- Pagination/sort rules
- Required permission per endpoint
- 400/401/403/404/409 mapping
- The 409 rules: duplicate email, self lock/delete/demote, last active admin, customer role change
- Note that `roleCode` is returned lowercase and `page` is zero-based

---

## Deviations / decisions needed before Step 1

### 1. Admin phone/address columns — required

The spec says phone and address come from the matching profile table, but `admins` has neither column (`V1__initial_schema.sql:122-134`, `Admin.java:39-45`).

**Recommendation:** add `admin_phone` and `admin_address` in V29 and update `Admin`/`AdminUserService` accordingly. Without this, the admin create/detail/list contract cannot be met correctly.

### 2. Legacy admin -> employee role change

Existing admins such as the Swagger demo admin have no phone (`R__swagger_demo_data.sql:23-29`), while `employees.employee_phone` is NOT NULL (`V1__initial_schema.sql:156`, `Employee.java:45-46`).

Options:

- **A (recommended):** V29 also runs `ALTER TABLE employees ALTER COLUMN employee_phone DROP NOT NULL;`, and `Employee.phone` drops `nullable = false`. API validation still requires phone on create. This lets legacy admin -> employee changes work without fake data.
- **B:** keep `employee_phone NOT NULL`; if the source admin has no phone, return 409 `error.user.profile.phone` and require the data to be fixed first. This is stricter but makes some role changes fail.

Please choose A or B.

### 3. Confirm V29 is free

Please confirm with the team that `V29` is not taken before I create the migration.

### 4. Migration verification

I cannot execute V29 against Supabase under your constraints. After writing it, I will stop and ask whether you want it verified on a **separate scratch Postgres**; I will not run it against the shared DB.

### 5. Minor defaults I propose unless you object

- Response `roleCode` is normalized to lowercase (`admin/employee/customer`) even if a test/seed row stores uppercase.
- `status=DELETED` on the list filter returns 400; DELETED users are hidden otherwise.
- Unknown `roleCode` filter returns an empty page rather than an error.
- Role change creates the target profile if missing and updates it if it already exists; the old profile is never deleted.
- List/detail select profile fields by the **current role**, not `COALESCE` across all profiles.
- Delete returns 204 with no `ApiResponse` body.

---

**Step 0 is complete. No source files or migrations were created or changed.**
