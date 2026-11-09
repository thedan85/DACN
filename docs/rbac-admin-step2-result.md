# RBAC Admin API — Step 2 Result

**Status:** Step 2 complete. Ready for approval. No Step 3 work has started.

## Scope delivered

Three read-only endpoints under `/api/v1/admin`, each guarded by `role:read`:

| Method | Path | Handler |
|---|---|---|
| GET | `/api/v1/admin/permissions` | `AdminRoleController.listPermissions` |
| GET | `/api/v1/admin/roles` | `AdminRoleController.listRoles` |
| GET | `/api/v1/admin/roles/{roleId}` | `AdminRoleController.getRole` |

Behavior implemented exactly as Step 0 specified:

- Permissions sorted by `resource ASC, action ASC, code ASC`.
- Roles sorted by `system DESC, code ASC`; soft-deleted roles excluded.
- Role detail permissions sorted by permission code.
- Unknown or soft-deleted role -> `ResourceNotFoundException` -> HTTP 404.
- Response envelope is the existing `ApiResponse`.
- New response DTOs expose no `deleted` flag and no surrogate `id`.
- The old unused role DTOs (`RoleCreateRequest`, `RoleUpdateRequest`, `RoleGrantRequest`) were not reused or modified.

## Files created

| File | Evidence |
|---|---|
| `src/main/java/com/project/dacn/dto/response/auth/AdminRoleSummaryResponse.java` | `AdminRoleSummaryResponse.java:5` record definition |
| `src/main/java/com/project/dacn/dto/response/auth/AdminRoleDetailResponse.java` | `AdminRoleDetailResponse.java:6` record definition |
| `src/main/java/com/project/dacn/service/auth/AdminRoleService.java` | `AdminRoleService.java:22` class; `:29` `listPermissions`; `:36` `listRoles`; `:43` `getRole`; `:28,35,42` read-only transactions |
| `src/main/java/com/project/dacn/controller/auth/AdminRoleController.java` | `AdminRoleController.java:25` class; `:29-31` permissions GET; `:35-37` roles GET; `:41-43` role detail GET; `@PreAuthorize` on each at `:30,36,42` |
| `src/test/java/com/project/dacn/AdminRoleControllerTest.java` | Nine test methods at `:135-263` |
| `docs/rbac-admin-step2-result.md` | This report |

## Files changed

| File | Change | Evidence |
|---|---|---|
| `src/main/java/com/project/dacn/repository/auth/RoleRepository.java` | Added sorted active-role finder | `RoleRepository.java:18` `findByDeletedFalseOrderBySystemDescCodeAsc()` |
| `src/main/java/com/project/dacn/repository/auth/PermissionRepository.java` | Added sorted permission finder | `PermissionRepository.java:14` `findAllByOrderByResourceAscActionAscCodeAsc()` |

No admin user, catalog, product, cart, order, payment, review, migration, security constants, or security config file was changed in Step 2. `RolePermissionRepository.findByRole_RoleIdWithPermission` was reused for role detail.

## Test coverage

`AdminRoleControllerTest` seeds ADMIN (system), EMPLOYEE (system, permission-less), CUSTOMER (system), AUDITOR (custom, `system=false`), and LEGACY (soft-deleted). Permissions are granted in unsorted insertion order to prove sorting.

| Test | Requirement covered |
|---|---|
| `allGetEndpoints_withoutToken_return401` (`:135`) | 401 without a token on all three endpoints |
| `customerAndPermissionlessEmployee_forbiddenOnAllGetEndpoints` (`:147`) | 403 for customer and permission-less employee on all three endpoints |
| `admin_canReadPermissionsRolesAndRoleDetail` (`:165`) | 200 for admin on all three endpoints |
| `getPermissions_sortedByResourceActionCode_andIncludesEveryRolePermission` (`:180`) | Stable permission sort; all four `role:*` permissions present |
| `getRoles_excludesSoftDeletedRoles_andExposesSystemFlag` (`:201`) | `system DESC, code ASC`; custom role shows `system=false`; soft-deleted role excluded |
| `getRole_returnsPermissionsSortedByCode` (`:227`) | Detail permissions sorted by code, exact expected set |
| `getRole_unknownOrSoftDeleted_returns404` (`:251`) | Unknown role and soft-deleted role both 404 |
| `roleResponses_doNotExposeDeletedFlagOrInternalId` (`:263`) | No `deleted` field, no surrogate `id`, no `passwordHash` |

## TDD evidence

**RED** — tests written before production code:

```
Tests run: 621, Failures: 5, Errors: 0, Skipped: 10
AdminRoleControllerTest.admin_canReadPermissionsRolesAndRoleDetail:174 Status expected:<200> but was:<404>
AdminRoleControllerTest.getPermissions_sortedByResourceActionCode_andIncludesEveryRolePermission:183->getJson:286 Status expected:<200> but was:<404>
AdminRoleControllerTest.getRole_returnsPermissionsSortedByCode:230->getJson:286 Status expected:<200> but was:<404>
AdminRoleControllerTest.getRoles_excludesSoftDeletedRoles_andExposesSystemFlag:204->getJson:286 Status expected:<200> but was:<404>
AdminRoleControllerTest.roleResponses_doNotExposeDeletedFlagOrInternalId:268 Status expected:<200> but was:<404>
BUILD FAILURE
```

The 401 and 403 tests already passed at RED because the Step 1 URL rules were in place; the five 404 failures were the missing GET handlers.

**GREEN** — after adding repositories, DTOs, service, and controller:

```
Tests run: 621, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
Total time:  01:24 min
```

Command run: `.\mvnw.cmd clean test` (H2 test profile only). Test count is 621, higher than the Step 1 count of 613, with 0 failures.

## Constraints observed

- No dev profile was run.
- No Flyway migration was executed against any database; V30 was not changed.
- No git command was run.
- No secret was printed.
- Only `.\mvnw.cmd clean test` was used for verification.

## Pending (not started)

- Step 3 and later: PATCH/PUT/POST/DELETE endpoints, errors/messages, mutation tests.
- Custom-role assignment through the admin user API remains pending and requires separate approval; it will be raised again at Step 6.
