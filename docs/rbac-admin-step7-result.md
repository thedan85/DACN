# RBAC Admin API — Step 7 Result

**Status:** Step 7 complete. Admin users can now be assigned any existing, non-deleted role except `customer`. Custom roles map to the Employee profile and their permissions apply on the next request with the same token. Waiting for approval; nothing after Step 7 has started.

## Scope delivered

- `POST /api/v1/admin/users` accepts any existing, non-deleted role code except `customer`, matched ignoring case.
- `PATCH /api/v1/admin/users/{userId}/role` accepts the same set.
- Profile mapping: `admin` -> Admin profile; every other staff role, including a custom role -> Employee profile.
- The customer target rule is unchanged: a customer's role cannot be changed.
- Demotion guards generalized: a change is a demotion whenever the current role is `admin` and the requested role is not `admin`. Self-demote and last-active-admin checks therefore also cover admin -> custom role.
- Unknown or soft-deleted role codes still return `409` with `error.user.role.invalid`.
- List and detail profile lookup now fall back to the Employee profile for any role that is neither `customer` nor `admin`.
- Existing endpoint response shapes are unchanged.

## TDD evidence

### RED

Command: `.\mvnw.cmd test "-Dtest=AdminUserControllerTest,AdminUserMutationControllerTest"`

```text
Tests run: 40, Failures: 6, Errors: 0, Skipped: 0
BUILD FAILURE
```

The six failures were the new behavior tests, each failing for the expected reason:

| Test | RED failure |
|---|---|
| `createCustomRole_201_createsEmployeeProfile` | expected `201`, got `409` |
| `role_employeeToCustomRole_sameAccessTokenSeesCustomPermissions` | expected `200`, got `409` |
| `role_customRoleToAdmin_200_createsAdminProfile` | name was the email because the custom-role profile lookup returned empty |
| `selfDemoteToCustomRole_409` | invalid-role message instead of the self-demote message |
| `lastActiveAdmin_cannotBeDemotedToCustomRole_409` | invalid-role message instead of the last-admin message |
| `listAndDetail_customRoleUserShowEmployeeProfile` | list total `0` because the SQL CASE had no custom-role fallback |

The two negative tests (`create_unknownRole_409`, `role_softDeletedRole_409`) passed before the change because that behavior was intentionally preserved.

### GREEN

Same targeted command after the minimal implementation:

```text
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Full suite

Command: `.\mvnw.cmd clean test`

```text
Tests run: 659, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
Total time: 01:36 min
```

Step 6 was 651 tests, so Step 7 adds 8 tests with 0 failures and no existing assertion was changed or loosened.

## Production files changed

| File | Change | Evidence |
|---|---|---|
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | `create` rejects only `customer` and resolves any non-deleted role by code ignoring case | `:126-132` |
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | `updateRole` rejects only a requested `customer`, keeps the customer-target rule, and resolves any non-deleted role | `:204-208`, `:224-225` |
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | Demotion is now `currentRole == admin && requestedRole != admin`, covering custom targets | `:215` |
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | Profile lookup: `customer` -> customer, `admin` -> admin, everything else -> employee | `:313-327` |
| `src/main/java/com/project/dacn/repository/auth/AdminUserQueryRepository.java` | List CASE expressions now use `ELSE` for the Employee profile | `:35-57` |

No production file was created, no endpoint signature or response DTO changed, and no migration was added or edited.

## Tests changed

All additions are in the existing admin user test classes.

| File | Added test | Evidence |
|---|---|---|
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `createCustomRole_201_createsEmployeeProfile` | `:302` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `role_employeeToCustomRole_sameAccessTokenSeesCustomPermissions` | `:327` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `role_customRoleToAdmin_200_createsAdminProfile` | `:364` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `create_unknownRole_409` | `:395` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `role_softDeletedRole_409` | `:409` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `selfDemoteToCustomRole_409` | `:424` |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | `lastActiveAdmin_cannotBeDemotedToCustomRole_409` | `:439` |
| `src/test/java/com/project/dacn/AdminUserControllerTest.java` | `listAndDetail_customRoleUserShowEmployeeProfile` | `:343` |

Helper additions: `customRole`/`deletedRole` in the mutation test and `customRole` in the read test. No existing test method or assertion was modified.

## Documentation changed

| File | Change | Evidence |
|---|---|---|
| `docs/admin-users-api.md` | `roleCode` convention now includes custom role codes | `:35` |
| `docs/admin-users-api.md` | Create rules: any existing, non-deleted role except `customer`; Admin profile for `admin`, Employee profile otherwise | `:201`, `:212` |
| `docs/admin-users-api.md` | PATCH role rules generalized; custom permissions apply with the same token | `:338` |
| `docs/admin-users-api.md` | 409 tables and message-key rules describe unknown/soft-deleted roles and the generalized self-demote rule | `:376`, `:441` |
| `docs/admin-roles-api.md` | Removed the "cannot yet be assigned" note; custom roles are assignable now | `:47` |
| `docs/admin-roles-api.md` | Replaced the pending status section with the working assignment behavior | `:675-677` |

The literal `Invalid role; only employee and admin are supported` text remains only in the captured error responses and the message-key table because it is still the actual wire message produced by `error.user.role.invalid`; no existing test assertion was changed.

## Constraints

- No dev profile used.
- No Flyway execution and no migration change.
- No git operations.
- No secrets or real credentials added.
- Final verification used `.\mvnw.cmd clean test`.
