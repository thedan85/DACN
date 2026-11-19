# RBAC Admin API — Step 5 Verification Result

**Status:** Step 5 complete. No production or test code changed. No missing security-matrix cell was found. Awaiting approval; no later step started.

**Scope:** verification only, per the approved Step 5 instructions.

## 1. Matcher order and overlap check

All admin matcher rules in `SecurityConfig.java` are declared in this order:

| Order | Method | Path | Authority | Lines |
|---|---|---|---|---|
| 1 | POST | `/api/v1/admin/orders/*/complete` | `order:complete` | `SecurityConfig.java:115-116` |
| 2 | GET | `/api/v1/admin/users`, `/api/v1/admin/users/**` | `user:read` | `SecurityConfig.java:117-118` |
| 3 | POST | `/api/v1/admin/users` | `user:create` | `SecurityConfig.java:119-120` |
| 4 | PATCH | `/api/v1/admin/users/*/status`, `/api/v1/admin/users/*/role` | `user:update` | `SecurityConfig.java:121-123` |
| 5 | DELETE | `/api/v1/admin/users/*` | `user:delete` | `SecurityConfig.java:124-125` |
| 6 | GET | `/api/v1/admin/permissions` | `role:read` | `SecurityConfig.java:127-128` |
| 7 | GET | `/api/v1/admin/roles`, `/api/v1/admin/roles/**` | `role:read` | `SecurityConfig.java:129-131` |
| 8 | POST | `/api/v1/admin/roles` | `role:create` | `SecurityConfig.java:132-133` |
| 9 | PATCH | `/api/v1/admin/roles/*` | `role:update` | `SecurityConfig.java:134-135` |
| 10 | PUT | `/api/v1/admin/roles/*/permissions` | `role:update` | `SecurityConfig.java:136-137` |
| 11 | DELETE | `/api/v1/admin/roles/*` | `role:delete` | `SecurityConfig.java:138-139` |
| 12 | any | any remaining path | `authenticated()` | `SecurityConfig.java:141` |

### Findings

- **No new role rule shadows an existing rule.** Rules 7–11 all target the `/api/v1/admin/roles` tree and are the only rules for that tree. Rules 1–6 match disjoint paths (`/admin/orders`, `/admin/users`, `/admin/permissions`).
- **`GET /admin/roles/**` does not shadow PATCH, PUT, or DELETE.** Rule 7 is scoped to `HttpMethod.GET` at `SecurityConfig.java:129-131`, so it cannot match PATCH (`:134-135`), PUT (`:136-137`), or DELETE (`:138-139`) requests regardless of declaration order.
- **The PATCH rule does not shadow the nested PUT rule.** Rule 9 uses `"/api/v1/admin/roles/*"` (`:134-135`), a single path segment, while rule 10 uses `"/api/v1/admin/roles/*/permissions"` (`:136-137`). They also use different HTTP methods, so neither can capture the other.
- **The GET `/**` rule is broader than the GET `/roles` rule but declared in the same matcher.** `"/api/v1/admin/roles"` and `"/api/v1/admin/roles/**"` are listed together at `:129-131` with the same authority, so there is no competing narrower GET rule.
- **The catch-all rule is last.** `anyRequest().authenticated()` at `:141` cannot shadow any admin rule.
- **URL rules and method annotations agree.** The controller uses the same authorities:
  - `GET /permissions` -> `ROLE_READ` (`AdminRoleController.java:39-43`, annotation `:40`)
  - `GET /roles`, `GET /roles/{roleId}` -> `ROLE_READ` (`:45-55`, annotations `:46,52`)
  - `PATCH /roles/{roleId}`, `PUT /roles/{roleId}/permissions` -> `ROLE_UPDATE` (`:57-71`, annotations `:58,66`)
  - `POST /roles` -> `ROLE_CREATE` (`:73-79`, annotation `:74`)
  - `DELETE /roles/{roleId}` -> `ROLE_DELETE` (`:81-86`, annotation `:82`)

**Conclusion:** no role rule shadows, or is shadowed by, another admin rule.

## 2. Security matrix — 7 endpoints x 4 checks

`Customer` and the `EMPLOYEE` actor are permission-less for the role API because no `role:*` permission is granted to either role in any of the three test classes:

- `AdminRoleControllerTest.java:101-103` creates ADMIN, EMPLOYEE, CUSTOMER; grants at `:115-120` go only to ADMIN and the custom role.
- `AdminRoleMutationControllerTest.java:89-92` creates the roles; grants at `:104-111` go only to ADMIN and the custom role.
- `AdminRoleLifecycleControllerTest.java:82-88` creates the roles; grants at `:97-102` go only to ADMIN and the unused custom role.

| Endpoint | 401 without token | 403 customer | 403 permission-less employee | 2xx admin |
|---|---|---|---|---|
| `GET /api/v1/admin/permissions` | `AdminRoleControllerTest.java:134-144` (path `:137`, request `:140-142`) | `AdminRoleControllerTest.java:146-162` (customer token `:148`, path `:153`, request `:156-159`) | `AdminRoleControllerTest.java:146-162` (employee token `:149`, same request) | `AdminRoleControllerTest.java:164-177` (path `:169`) |
| `GET /api/v1/admin/roles` | `AdminRoleControllerTest.java:134-144` (path `:138`) | `AdminRoleControllerTest.java:146-162` (path `:154`) | `AdminRoleControllerTest.java:146-162` (same request) | `AdminRoleControllerTest.java:164-177` (path `:170`) |
| `GET /api/v1/admin/roles/{roleId}` | `AdminRoleControllerTest.java:134-144` (path `:139`) | `AdminRoleControllerTest.java:146-162` (path `:155`) | `AdminRoleControllerTest.java:146-162` (same request) | `AdminRoleControllerTest.java:164-177` (path `:171`); detail behavior also at `:226-248` |
| `PATCH /api/v1/admin/roles/{roleId}` | `AdminRoleMutationControllerTest.java:123-130` | `AdminRoleMutationControllerTest.java:139-160` (customer token `:141`, patch `:145-150`) | `AdminRoleMutationControllerTest.java:139-160` (employee token `:142`, same request) | `AdminRoleMutationControllerTest.java:162-170` |
| `PUT /api/v1/admin/roles/{roleId}/permissions` | `AdminRoleMutationControllerTest.java:131-137` | `AdminRoleMutationControllerTest.java:139-160` (put `:152-158`) | `AdminRoleMutationControllerTest.java:139-160` (same loop) | `AdminRoleMutationControllerTest.java:227-238` |
| `POST /api/v1/admin/roles` | `AdminRoleLifecycleControllerTest.java:113-121` | `AdminRoleLifecycleControllerTest.java:128-148` (customer token `:130`, post `:134-141`) | `AdminRoleLifecycleControllerTest.java:128-148` (employee token `:131`, same request) | `AdminRoleLifecycleControllerTest.java:150-169` (asserts 201) |
| `DELETE /api/v1/admin/roles/{roleId}` | `AdminRoleLifecycleControllerTest.java:123-125` | `AdminRoleLifecycleControllerTest.java:128-148` (delete `:143-146`) | `AdminRoleLifecycleControllerTest.java:128-148` (same loop) | `AdminRoleLifecycleControllerTest.java:245-276` (asserts 200 and `data: null`) |

### Missing cells

**None.** All 28 cells are covered.

## 3. Immutability of role code/system and admin permission guard

### No endpoint can change an existing role's code or system flag

- **PATCH** accepts only `name` and `description`; the request record has no `code` or `system` field: `AdminRoleUpdateRequest.java:5-7`.
- `updateRole` only writes `name` and `description`: `AdminRoleService.java:85-96`. It never calls a code or system setter.
- **PUT permissions** accepts only `permissionIds`: `AdminRolePermissionsUpdateRequest.java:8-9`. `replacePermissions` only adds/deletes `RolePermission` rows: `AdminRoleService.java:133-156`. It does not mutate the `Role` entity's code or system flag.
- **POST** creates a new role with `system(false)` once, on the new entity: `AdminRoleService.java:181-187`. It does not modify an existing role.
- **DELETE** only sets `deleted=true`: `AdminRoleService.java:211`.
- A repository-wide search confirms there is no `setCode(...)` or `setSystem(...)` anywhere in `src/main/java`, and the only role writes are:
  - `role.setName(...)` at `AdminRoleService.java:90`
  - `role.setDescription(...)` at `AdminRoleService.java:95`
  - `role.setDeleted(true)` at `AdminRoleService.java:211`
  - builder calls `.code(code)` and `.system(false)` at `AdminRoleService.java:182,185`, used only when constructing a new role.

**Conclusion:** no endpoint can change the code or the system flag of an existing role.

### The admin role cannot lose any `user:*` or `role:*` permission through PUT

`replacePermissions` calls the guard before deleting or adding grants:

- Call site: `AdminRoleService.java:131`.
- Guard implementation: `AdminRoleService.java:215-229`.
  - It activates only when the role is a system role and its code is `admin` ignoring case: `:216-219`.
  - It loads every permission and requires every permission whose `resource` is `user` or `role` to be present in the requested target set: `:221-224`.
  - If any such permission is missing, it throws `RoleRuleViolationException("error.role.admin.permissions")`, mapped to HTTP 409: `:226-228`.
- The deletion of existing grants happens only after the guard passes: `:139-144`.

This protects every permission whose resource is `user` or `role`, which is a superset of the `user:*` and `role:*` codes.

Tests proving the guard:

- Removing `user:read` -> 409: `AdminRoleMutationControllerTest.java:302-313`.
- Removing `role:read` -> 409: `AdminRoleMutationControllerTest.java:315-326`.
- Sending every user/role permission -> 200: `AdminRoleMutationControllerTest.java:328-341`.

Because code and system are immutable through the API, the guard cannot be bypassed by renaming or de-system-ing the admin role.

## 4. Tests added in Step 5

**None.** The matrix has no missing cells, so no test change was needed. No production code was touched.

## 5. Full test run

Command: `.\mvnw.cmd clean test` (H2 test profile only)

```
[INFO] Tests run: 651, Failures: 0, Errors: 0, Skipped: 10
[INFO] BUILD SUCCESS
[INFO] Total time:  01:40 min
```

The count is unchanged from Step 4 (`651`) because Step 5 added no tests, and it remains higher than the Step 3 baseline (`640`), with 0 failures.

## Constraints observed

- No dev profile was run.
- No Flyway migration was executed against any database; no migration was added or changed.
- No git command was run.
- No secret was printed.
- Only `.\mvnw.cmd clean test` was used for verification.
- Admin user, catalog, product, cart, order, payment, and review code were not modified.

## Pending (not started)

- Later RBAC admin steps require approval.
- Custom-role assignment through the admin user API remains pending and requires separate approval; it will be raised again at Step 6.
