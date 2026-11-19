# RBAC Admin API — Step 0 Analysis and Proposal

**Status:** Step 0 complete. Read-only. No source files, migrations, or tests were changed in this step.

Constraints observed:

- No app run with the dev profile.
- No Flyway execution against any database.
- No git commands.
- Only repository files were read. No command output is claimed here.

---

## a) Highest migration version and proposed next version

**Highest current version: `V29`.**

Evidence:

- `src/main/resources/db/migration/V29__admin_user_management.sql` exists.
- `V28__lookup_indexes.sql` and `V27__seed_plant_catalog.sql` are older.
- `docs/step_3_result.md:264` records that V29 was applied to the shared Supabase database on 2026-11-06. V29 must not be re-run.

**Proposed next migration: `V30__rbac_role_permissions.sql`.**

V30 is a seed-only migration for the new `role:*` permissions and their admin grants. It does not change the schema.

The team must confirm that V30 is free before the file is created. This project has had a migration-version collision before; `V16__seed_auth_rbac.sql:3-5` documents that history.

---

## b) How permissions are loaded per request

Permissions are loaded from the database on every authenticated request. They are not stored in the JWT and there is no permission cache.

Evidence chain:

1. `security/JwtService.java:30-38` builds an access token with only `sub` (user ID), `email`, and `type`. No permission claim is included.
2. `security/JwtAuthenticationFilter.java:37-40` validates the access token and calls `CustomUserDetailsService.loadUserByUserId(...)` on each request.
3. `security/CustomUserDetailsService.java:36-42` loads the principal for that user ID.
4. `security/CustomUserDetailsService.java:51-55` collects permission codes from the principal rows.
5. `repository/auth/UserRepository.java:32-43` joins `User -> Role -> RolePermission -> Permission` and returns one row per granted permission.
6. `security/AuthenticatedUser.java:37-39` converts those permission codes into Spring Security authorities.

Consequence: a committed change to `role_permissions` takes effect on the **next request with the same access token**. No cache invalidation and no token refresh is required. The implementation must include a test that proves this behavior.

---

## c) Role soft delete

Yes, the `roles` table has soft delete.

Evidence:

- `entity/auth/Role.java:46-48` declares the `deleted` field mapped to `is_deleted`.
- `entity/auth/Role.java:26` applies `@SQLRestriction("is_deleted = false")`.
- `db/migration/V1__initial_schema.sql:47` declares `is_deleted BOOLEAN NOT NULL DEFAULT FALSE`.
- `repository/auth/RoleRepository.java:16` already has `findByDeletedFalse()`.

Soft-deleted roles are hidden from normal JPA queries. `findByRoleId` returns an empty result for them, so the API returns 404.

---

## d) System roles

Effective seeded roles: **customer, admin, employee**. All three have `role_is_system = TRUE`.

Evidence:

- `db/migration/V8__seed_cart_demo.sql:21-24` seeds lowercase `customer`, `admin`, and `employee` with `role_is_system = TRUE`.
- `db/migration/V16__seed_auth_rbac.sql:22-27` attempts to insert uppercase `ADMIN`, `EMPLOYEE`, and `CUSTOMER` with `ON CONFLICT DO NOTHING`.
- `db/migration/V2__add_indexes.sql:85-86` creates a unique index on `lower(role_code)`.
- Because V8 runs before V16, the uppercase V16 inserts conflict with the lowercase V8 rows and are skipped.

The test profile seeds uppercase codes instead; for example, `AdminUserControllerTest.java:94-96` uses `ADMIN`, `EMPLOYEE`, and `CUSTOMER`.

Therefore:

- System-role protection must use the `system` flag, not a hardcoded role-code list.
- Code lookups must use `findByCodeIgnoreCase` or `lower(role_code)`.
- The admin-specific safety rule is the only place that compares the code, using `"admin".equalsIgnoreCase(code)`.

---

## e) Current gaps

- No role or permission controller or service exists. A repository search for `admin/roles` and `RoleService` returned no matches.
- Existing request DTOs are unused by controllers and are unsafe for this API:
  - `dto/request/auth/RoleCreateRequest.java:6-10` accepts a `system` flag.
  - `dto/request/auth/RoleUpdateRequest.java:3-7` accepts `system` and `deleted`.
  - `dto/request/auth/RoleGrantRequest.java:5-7` grants one permission; it is not a replace-set contract.
  - These DTOs are used only by mappers and mapper tests, for example `mapper/auth/RoleMapper.java:21-31` and `AuthMapperTest.java:26,40`. They will not be modified or reused.
- `repository/auth/RoleRepository.java:9-16` has no sorted list method and no pessimistic-lock finder.
- `repository/auth/PermissionRepository.java:8-12` has no bulk business-ID lookup.
- `repository/auth/RolePermissionRepository.java:11-17` has no replacement helper, but the existing fetch-join query is sufficient for reading.
- `config/SecurityConfig.java:117-127` has admin user rules only. There are no role or permission rules.
- `security/Permissions.java:29-32` has `user:*` constants but no `role:*` constants.
- V29 seeded `user:read`, `user:create`, `user:update`, and `user:delete`. V30 must seed the four `role:*` permissions.

---

## Proposed design

### Endpoint contract

| Method | Path | Required permission | Success response |
|---|---|---|---|
| GET | `/api/v1/admin/permissions` | `role:read` | 200 `ApiResponse<List<PermissionResponse>>` |
| GET | `/api/v1/admin/roles` | `role:read` | 200 `ApiResponse<List<AdminRoleSummaryResponse>>` |
| GET | `/api/v1/admin/roles/{roleId}` | `role:read` | 200 `ApiResponse<AdminRoleDetailResponse>` |
| PATCH | `/api/v1/admin/roles/{roleId}` | `role:update` | 200 `ApiResponse<AdminRoleDetailResponse>` |
| PUT | `/api/v1/admin/roles/{roleId}/permissions` | `role:update` | 200 `ApiResponse<AdminRoleDetailResponse>` |
| POST | `/api/v1/admin/roles` | `role:create` | 201 `ApiResponse<AdminRoleDetailResponse>` — optional |
| DELETE | `/api/v1/admin/roles/{roleId}` | `role:delete` | 200 `ApiResponse<Void>` — optional |

Conventions:

- `GET /api/v1/admin/roles` is unpaginated. The role table is small.
- Roles are sorted by `system DESC, code ASC` for a stable response.
- `GET /api/v1/admin/permissions` is unpaginated. Permissions are sorted by `resource ASC, action ASC, code ASC`.
- Role detail permissions are sorted by permission code.
- `roleId` is the existing business ID such as `role-admin` or `ROLE_ADMIN`.

### DTOs

Add new records under `dto/request/auth` and `dto/response/auth`. Do not modify the old unused DTOs.

```java
record AdminRoleCreateRequest(
    @NotBlank @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,49}$") String code,
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description) {}

record AdminRoleUpdateRequest(
    @Size(max = 255) String name,
    @Size(max = 2000) String description) {}

record AdminRolePermissionsUpdateRequest(
    @NotNull List<@NotBlank String> permissionIds) {}

record AdminRoleSummaryResponse(
    String roleId,
    String code,
    String name,
    String description,
    boolean system,
    Instant createdAt,
    Instant updatedAt) {}

record AdminRoleDetailResponse(
    String roleId,
    String code,
    String name,
    String description,
    boolean system,
    Instant createdAt,
    Instant updatedAt,
    List<PermissionResponse> permissions) {}
```

Reuse the existing `dto/response/auth/PermissionResponse.java:5-13`. It already exposes `permissionId`, `code`, `name`, `resource`, `action`, `description`, `createdAt`, and `updatedAt`.

The existing `RoleResponse` is not reused because it exposes a `deleted` field, which is always false in this API.

### Service behavior

Add `service/auth/AdminRoleService`.

#### `listPermissions`

- Load all permissions from `PermissionRepository`.
- Sort by resource, action, and code.
- Return `List<PermissionResponse>`.

#### `listRoles`

- Add `RoleRepository.findByDeletedFalseOrderBySystemDescCodeAsc()`.
- Return `List<AdminRoleSummaryResponse>`.

#### `getRole(roleId)`

1. `RoleRepository.findByRoleId(roleId)`; missing or soft-deleted -> 404.
2. Load grants with `RolePermissionRepository.findByRole_RoleIdWithPermission(roleId)`.
3. Sort permission codes.
4. Return `AdminRoleDetailResponse`.

#### `updateRole(roleId, request)`

1. Load role; missing or soft-deleted -> 404.
2. If both `name` and `description` are null -> 400.
3. If `name` is present and blank after trim -> 400.
4. Trim `name`.
5. Blank `description` clears the description to `null`.
6. Save and return the updated detail.
7. The role code is not part of this DTO. Code is immutable for all roles, not only system roles.

#### `replacePermissions(roleId, request)`

1. Load role with a pessimistic write lock; missing or soft-deleted -> 404.
2. If `permissionIds` is null -> 400.
3. If any ID is null or blank -> 400.
4. If the list contains duplicates -> 400.
5. Resolve all IDs with a new `PermissionRepository.findByPermissionIdIn(Collection<String>)`.
6. If any ID is missing -> 404.
7. If the role is a system role with code `admin`, verify the target set contains every permission whose resource is `user` or `role`. If any is missing -> 409.
8. Diff the current grants against the target set:
   - Delete grants whose permission is not in the target.
   - Insert grants for target permissions that are not currently granted.
   - Keep retained grants and their `grantedAt`.
9. Save and return the updated detail.

Using a diff instead of delete-all/reinsert preserves `grantedAt` and avoids unique-constraint churn on `uq_role_permission`.

#### `createRole(request)` — optional

1. Trim the code.
2. Normalize the stored code to lowercase.
3. Reject a duplicate code ignoring case -> 409.
4. `system` is always `false`; the API cannot create a system role.
5. Create the role with no permissions.
6. Catch `DataIntegrityViolationException` and map it to 409, covering the soft-deleted-code case and concurrent inserts.
7. Return the created detail.

A soft-deleted role code still occupies `uq_roles_code`, so the code cannot be reused after deletion. The API returns 409.

#### `deleteRole(roleId)` — optional

1. Load role with a pessimistic write lock; missing or soft-deleted -> 404.
2. If `role.system` is true -> 409.
3. Count all user rows assigned to the role, including users with `user_status = 'DELETED'`. If the count is greater than zero -> 409.
4. Set `deleted = true` and save.
5. Keep `role_permissions` rows for audit.

The in-use count must be a native query. `User` has `@SQLRestriction("user_status <> 'DELETED'")` at `entity/auth/User.java:32`, so a normal derived count would miss soft-deleted users.

### Safety rules

- System role cannot be deleted -> 409.
- System role code cannot change. This API also makes code immutable for custom roles by omitting `code` from the PATCH request.
- The `admin` role must always keep every permission whose resource is `user` or `role`. Otherwise the call returns 409. This prevents a permanent admin/RBAC lockout.
- A role assigned to any user, including a soft-deleted user, cannot be deleted -> 409. This prevents users from pointing at a hidden role.
- New roles are always `system = false`.
- Permission changes take effect on the next request because authorization is loaded from the database on every request.

### Security

Add constants to `security/Permissions.java`:

```java
public static final String ROLE_READ = "role:read";
public static final String ROLE_CREATE = "role:create";
public static final String ROLE_UPDATE = "role:update";
public static final String ROLE_DELETE = "role:delete";
```

Add URL rules in `config/SecurityConfig.java` before `.anyRequest().authenticated()` at line 127:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/admin/permissions")
    .hasAuthority(Permissions.ROLE_READ)
.requestMatchers(HttpMethod.GET, "/api/v1/admin/roles", "/api/v1/admin/roles/**")
    .hasAuthority(Permissions.ROLE_READ)
.requestMatchers(HttpMethod.POST, "/api/v1/admin/roles")
    .hasAuthority(Permissions.ROLE_CREATE)
.requestMatchers(HttpMethod.PATCH, "/api/v1/admin/roles/*")
    .hasAuthority(Permissions.ROLE_UPDATE)
.requestMatchers(HttpMethod.PUT, "/api/v1/admin/roles/*/permissions")
    .hasAuthority(Permissions.ROLE_UPDATE)
.requestMatchers(HttpMethod.DELETE, "/api/v1/admin/roles/*")
    .hasAuthority(Permissions.ROLE_DELETE)
```

Add the same checks with `@PreAuthorize("hasAuthority(...)")` on each controller method, matching `AdminUserController.java:40-85`.

### Migration V30

Add `src/main/resources/db/migration/V30__rbac_role_permissions.sql`. It is idempotent and uses the existing ID conventions.

```sql
INSERT INTO permissions (
    permission_id, permission_code, permission_name,
    permission_resource, permission_action,
    permission_description, created_at, updated_at
)
VALUES
  ('550e8400-e29b-41d4-a716-446655440901', 'role:read',
   'Read Role', 'role', 'read',
   'View roles and permissions', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440902', 'role:create',
   'Create Role', 'role', 'create',
   'Create non-system roles', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440903', 'role:update',
   'Update Role', 'role', 'update',
   'Edit role details and permissions', now(), now()),
  ('550e8400-e29b-41d4-a716-446655440904', 'role:delete',
   'Delete Role', 'role', 'delete',
   'Soft-delete unused non-system roles', now(), now())
ON CONFLICT DO NOTHING;
```

Grant them to the admin role only:

```sql
INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p
  ON p.permission_code IN (
    'role:read', 'role:create', 'role:update', 'role:delete'
  )
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;
```

The grant uses `r.id` and `p.id` because `V12__fk_to_surrogate_id.sql:159-161,238-243` converted `role_permissions.role_id` and `role_permissions.permission_id` to the surrogate `BIGINT` IDs. V29 uses the same convention at lines 51-61.

ID block `0901-0904` is free. Existing blocks are `0101-0604` (V16), `0701-0703` (V17/V21), and `0801-0804` (V29).

No permission create/update/delete endpoints are included in this slice. `docs/Overview.md:95-99` mentions adding, editing, and deleting permissions, but the requested endpoint list does not.

### Error mapping

| Status | Cases |
|---|---|
| 401 | Missing, invalid, or expired access token |
| 403 | Authenticated caller lacks the required `role:*` authority |
| 404 | Role path ID missing or soft-deleted; permission ID referenced by `PUT` is missing |
| 400 | Validation failure; blank name; PATCH with no fields; duplicate permission IDs; malformed JSON |
| 409 | Duplicate role code; delete system role; delete role in use; admin target missing `user:*` or `role:*`; database uniqueness fallback |
| 500 | Unexpected server error |

Add `common/exception/RoleRuleViolationException`, handled like `UserRuleViolationException` at `GlobalExceptionHandler.java:70-76`, returning 409 with a message resolved through `MessageSource`.

Add matching keys to both `src/main/resources/messages/messages.properties` and `messages/messages_vi.properties`:

- `error.role.code.exists`
- `error.role.system.delete`
- `error.role.in.use`
- `error.role.admin.permissions`

For 404s, use `ResourceNotFoundException("role not found: " + roleId)` and `ResourceNotFoundException("permission not found: " + permissionId)`, matching the existing raw-message style.

Do not change any existing admin user endpoint in phase 1.

### Tests

Add two integration test classes, following the style of `AdminUserControllerTest.java:47-50` and `AdminUserMutationControllerTest.java:52-56`:

- `AdminRoleControllerTest` for read endpoints.
- `AdminRoleMutationControllerTest` for PATCH, PUT, and optional POST/DELETE.

Test setup:

- `@SpringBootTest`
- `@AutoConfigureMockMvc`
- `@ActiveProfiles("test")`
- `@Transactional`
- Mock `RateLimiter` with `@MockitoBean`
- Seed roles, permissions, grants, and users in `@BeforeEach`
- Use repositories and `EntityManager.flush()` as the existing admin tests do

Planned coverage:

1. Every endpoint without a token returns 401.
2. Customer and employee without `role:*` return 403.
3. Admin can read permissions and roles.
4. `GET /permissions` returns 200 and stable sort, including `role:*`.
5. `GET /roles` returns 200, excludes soft-deleted roles, and exposes `system`.
6. `GET /roles/{roleId}` returns sorted permissions; unknown or deleted role -> 404.
7. PATCH updates name only.
8. PATCH updates description only.
9. PATCH clears description with a blank string.
10. PATCH blank name -> 400.
11. PATCH with no fields -> 400.
12. PATCH does not change the role code.
13. PATCH unknown role -> 404.
14. PUT adds permissions.
15. PUT removes permissions.
16. PUT with an empty list clears a custom role.
17. PUT unknown permission ID -> 404.
18. PUT duplicate permission IDs -> 400.
19. PUT failure leaves the old grant set unchanged.
20. PUT on `admin` without `user:read` -> 409.
21. PUT on `admin` without `role:read` -> 409.
22. PUT on `admin` with all `user:*` and `role:*` -> 200.
23. Same-token effect:
    - A user with a custom role has `product:create`.
    - Login and call `POST /api/v1/products` with `{}`; expect 400 from validation, proving authorization passed.
    - Admin removes `product:create` from that role.
    - Call `POST /api/v1/products` again with the same token; expect 403.
24. POST role -> 201, `system=false`.
25. POST duplicate code with different case -> 409.
26. POST invalid code -> 400.
27. DELETE system role -> 409.
28. DELETE role assigned to a user -> 409.
29. DELETE unused custom role -> 200 and role disappears from GET.
30. DELETE unknown role -> 404.

Run only:

```powershell
.\mvnw.cmd clean test
```

The test profile disables Flyway and uses H2 `create-drop`, so V30 is not executed by this command. V30 verification must be a separate decision on a scratch PostgreSQL database, never the shared Supabase database.

---

## Open question: assigning a newly created role

The current admin user API hardcodes `employee` and `admin`:

- `service/auth/AdminUserService.java:126-132`: create accepts only `employee` or `admin`.
- `service/auth/AdminUserService.java:205-210`: role change accepts only `employee` or `admin`; the customer role cannot change.
- `service/auth/AdminUserService.java:313-329`: profile lookup has cases for customer, employee, and admin only. Unknown roles return an empty profile.
- `repository/auth/AdminUserQueryRepository.java:35-57`: the native list query uses CASE on exactly customer, employee, and admin. A custom-role user would show an empty name, phone, and address.

A newly created custom role therefore cannot be assigned through the current user API, and even a direct assignment would render an empty profile in the admin user list.

### Option A — extend the admin user API for custom staff roles

Allow any existing non-customer role on create and role-change. Map the `admin` role to an `Admin` profile and every other staff role to an `Employee` profile.

Required changes:

- `AdminUserService.create`: accept a role that exists and is not `customer`; choose the profile by role code.
- `AdminUserService.updateRole`: accept a non-customer role; generalize the self-demote and last-active-admin guards to “current role is admin and target role is not admin”.
- `AdminUserService.profileFor`: customer -> customer profile, admin -> admin profile, everything else -> employee profile.
- `AdminUserQueryRepository`: customer -> customer columns, admin -> admin columns, otherwise employee columns.

Trade-offs:

- New roles become usable immediately.
- This changes admin user endpoints, which the current constraint restricts. It is the “strictly required” change if POST roles ships.
- Extra tests are needed for custom-role profile creation, list/detail profile selection, and role transitions.
- Cost if wrong: custom-role users show the wrong or empty profile, or existing admin user tests regress.

### Option B — keep the admin user API untouched

Create and assign custom roles only through direct database access or a future separate assignment endpoint.

Trade-offs:

- Respects the “do not modify admin user endpoints” constraint.
- POST roles produces roles that no API user can receive.
- Role management remains useful for editing the permissions of existing roles, but RBAC is incomplete until assignment is added.

### Recommendation

Implement **phase 1 now**:

- GET permissions
- GET roles
- GET role detail
- PATCH role name/description
- PUT role permissions

Defer **POST/DELETE roles** until the assignment question is decided. If POST/DELETE must ship in the same slice, approve **Option A** as a strictly required, separately tested change to the admin user service and query.

---

## Defaults proposed unless rejected

- New role codes are stored lowercase and returned lowercase. Existing rows are compared case-insensitively.
- `GET /admin/roles` and `GET /admin/permissions` are unpaginated.
- Unknown permission ID in `PUT` returns 404.
- DELETE returns 200 with `data: null`, matching `AdminUserController.delete` at `AdminUserController.java:80-85`.
- Role code is immutable for all roles, not only system roles.
- `POST /admin/roles` creates a role with no permissions; permissions are set by a follow-up `PUT`.

---

**Step 0 is complete. No source files, migrations, or tests were created or changed.**
