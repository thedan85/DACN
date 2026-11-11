# RBAC Admin API — Step 4 Result

**Status:** Step 4 complete. Ready for approval. No later step has started.

## Scope delivered

Two lifecycle endpoints under `/api/v1/admin`:

| Method | Path | Guard | Response |
|---|---|---|---|
| POST | `/api/v1/admin/roles` | `role:create` | 201 `ApiResponse<AdminRoleDetailResponse>` |
| DELETE | `/api/v1/admin/roles/{roleId}` | `role:delete` | 200 `ApiResponse<Void>` with `data: null` |

Behavior implemented exactly as Step 0 specified:

- `AdminRoleCreateRequest` validates `code` with `@NotBlank`, `@Size(max=50)`, and `^[A-Za-z][A-Za-z0-9_]{1,49}$`.
- `createRole` trims the code and stores it lowercase.
- Duplicate codes are rejected ignoring case, including the code of a soft-deleted role (native count query bypasses `@SQLRestriction`).
- `DataIntegrityViolationException` during insert is caught and mapped to the same 409 (concurrent inserts / unique index).
- New roles always have `system=false` and no permissions.
- `deleteRole` loads the role with a pessimistic write lock.
- System roles -> 409 using `error.role.system.delete`.
- Roles assigned to any user -> 409 using `error.role.in.use`; the native count includes `user_status = 'DELETED'` rows.
- Delete is soft only: `deleted=true`, and `role_permissions` rows are left untouched.
- Deleted roles disappear from `GET /roles` and `GET /roles/{id}` returns 404.
- No message keys were added; the existing `error.role.code.exists`, `error.role.system.delete`, and `error.role.in.use` keys were reused in both languages.

## Files created

| File | Evidence |
|---|---|
| `src/main/java/com/project/dacn/dto/request/auth/AdminRoleCreateRequest.java` | `:7-11` record with code pattern/size, name, description |
| `src/test/java/com/project/dacn/AdminRoleLifecycleControllerTest.java` | 11 tests at `:113-286`; helpers from `:288` |
| `docs/rbac-admin-step4-result.md` | This report |

## Files changed

| File | Change | Evidence |
|---|---|---|
| `src/main/java/com/project/dacn/service/auth/AdminRoleService.java` | Added `createRole` and `deleteRole`; injected `UserRepository` | `:161-196` create; `:163` trim/lowercase; `:165-167` duplicate 409; `:185` `system(false)`; `:189-193` integrity 409; `:198-213` delete; `:200-201` pessimistic lock; `:203-205` system 409; `:207-209` in-use 409; `:211-212` soft delete |
| `src/main/java/com/project/dacn/repository/auth/RoleRepository.java` | Added native duplicate-code count including soft-deleted roles | `:24-26` `countByCodeIgnoreCaseIncludingDeleted` |
| `src/main/java/com/project/dacn/repository/auth/UserRepository.java` | Added native role-assignment count including deleted users | `:66-67` `countByRoleIdIncludingDeleted` |
| `src/main/java/com/project/dacn/controller/auth/AdminRoleController.java` | Added POST and DELETE mappings with `@PreAuthorize` | `:73-79` POST 201; `:81-86` DELETE 200; `@PreAuthorize` at `:74,82` |

No admin user, catalog, product, cart, order, payment, review, migration, security constant, security config, or message file was changed. `@SecurityRequirement("bearerAuth")` is inherited from the class-level annotation.

## Test coverage

`AdminRoleLifecycleControllerTest` seeds ADMIN (all role permissions plus `user:read`), permission-less EMPLOYEE, CUSTOMER, an active user on SUPPORT, a DELETED user on ARCHIVE, an unused AUDITOR role with a `product:create` grant, and a soft-deleted LEGACY role.

| Step 0 item | Test | Requirement covered |
|---|---|---|
| 24 | `createRole_returns201WithLowercaseCodeAndNoPermissions` (`:151`) | 201, lowercase code, `system=false`, empty permissions, deterministic `ROLE_support-desk` ID |
| 25 | `createRole_duplicateCodeDifferentCase_409` (`:172`) | Duplicate `support` vs existing `SUPPORT` -> 409 |
| 26 | `createRole_invalidCode_400` (`:200`) | Leading digit, space, too short, and blank codes -> 400 |
| 27 | `deleteSystemRole_409` (`:216`) | System role delete -> 409 |
| 28 | `deleteRoleAssignedToActiveUser_409` (`:226`) | Role assigned to an active user -> 409 |
| 29 | `deleteUnusedCustomRole_returns200AndHidesRoleButKeepsGrants` (`:246`) | 200 with `data: null`; role absent from list; detail 404; native `role_permissions` count unchanged |
| 30 | `deleteUnknownRole_404` (`:279`) | Unknown role -> 404 |
| — | `createRole_duplicateCodeOfSoftDeletedRole_409` (`:186`) | Soft-deleted code still blocks reuse -> 409 |
| — | `deleteRoleAssignedToDeletedUser_409` (`:236`) | Native count catches a DELETED user -> 409 |
| — | `createAndDeleteEndpoints_withoutToken_return401` (`:114`) | 401 without token on POST and DELETE |
| — | `customerAndPermissionlessEmployee_forbiddenOnCreateAndDelete` (`:129`) | 403 for CUSTOMER and permission-less EMPLOYEE on both methods |

## TDD evidence

**RED** — tests written before production code:

```
Tests run: 651, Failures: 9, Errors: 0, Skipped: 10
BUILD FAILURE
```

All nine behavior tests failed with 500 because the POST/DELETE handlers did not exist. The 401 and 403 tests passed already because the Step 1 URL rules were in place.

**First GREEN attempt** — one assertion was wrong, not the production code:

```
Tests run: 651, Failures: 1, Errors: 0, Skipped: 10
AdminRoleLifecycleControllerTest.createRole_returns201WithLowercaseCodeAndNoPermissions:163
JSON path "$.data.roleId" expected:<ROLE_support_desk> but was:<ROLE_support-desk>
```

`BusinessId.slug` converts underscores to hyphens, so the generated ID is `ROLE_support-desk`. The test expectation was corrected and the suite rerun.

**Final GREEN**:

```
Tests run: 651, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
Total time:  01:34 min
```

Command run: `.\mvnw.cmd clean test` (H2 test profile only). Test count is 651, higher than the Step 3 count of 640, with 0 failures.

## Constraints observed

- No dev profile was run.
- No Flyway migration was executed against any database; no migration was added or changed.
- No git command was run.
- No secret was printed.
- Only `.\mvnw.cmd clean test` was used for verification.
- The admin user API was not modified.

## Pending (not started)

- Remaining RBAC admin plan steps after Step 4 require approval.
- Custom-role assignment through the admin user API remains pending and requires separate approval; it will be raised again at Step 6.
