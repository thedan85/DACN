# RBAC privilege-escalation fix (RBAC-01/02/03) — Result

**Status:** Complete. Admin user and role mutations now go through one central
authority policy. The new escalation paths return `403`; the pre-existing
business-rule `409`s keep their status and message. No schema change.

## What changed

### Central policy

New `AdminAuthorityPolicy` (`src/main/java/com/project/dacn/service/auth/`),
used by both `AdminUserService` and `AdminRoleService`. It resolves the actor
from the database (so role, permissions and `ACTIVE` status are current) and
enforces:

| Rule | Enforcement point |
|---|---|
| Only an active `admin` may create a user with `roleCode=admin` | `AdminUserService.create` |
| Only an active `admin` may change a role to or from `admin` | `AdminUserService.updateRole` |
| Nobody may change their own role | `AdminUserService.updateRole` |
| Only an active `admin` may lock, delete or demote an `admin` account | `updateStatus` (lock), `delete`, `updateRole` (demote) |
| Only an active `admin` may modify the `admin` role (name/description or permissions) | `AdminRoleService.updateRole`, `replacePermissions` |
| Only an active `admin` may grant or revoke a `user:*` or `role:*` permission | `AdminRoleService.replacePermissions` |
| Assigning a role requires the actor to hold every permission that role grants | `create`, `updateRole` |
| Granting permissions requires the actor to hold every permission it adds | `replacePermissions` |

Every violation raises the new `AdminAuthorityException`, which
`GlobalExceptionHandler` maps to HTTP `403` with the message
`You don't have permission to assign this role` (message key
`error.admin.authority`, English and Vietnamese bundles).

Adding a role-permission row also required splitting the existing `toAdd`
calculation into an explicit `added` list so the policy can inspect only the
new grants.

### Preserved business rules (still `409`)

* Invalid/unknown/soft-deleted/customer role.
* Customer target role.
* Self-lock, self-delete, self-demote.
* Last active administrator.
* System role delete, role in use.
* Admin role must keep every `user:*` / `role:*` permission.

## Files changed

| File | Change |
|---|---|
| `src/main/java/com/project/dacn/service/auth/AdminAuthorityPolicy.java` | New central policy |
| `src/main/java/com/project/dacn/common/exception/AdminAuthorityException.java` | New `403` exception |
| `src/main/java/com/project/dacn/common/exception/GlobalExceptionHandler.java` | Maps the exception to `403` |
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | Applies the policy in `create`, `updateStatus`, `updateRole`, `delete`; log actor comes from the resolved actor |
| `src/main/java/com/project/dacn/service/auth/AdminRoleService.java` | Applies the policy in `updateRole` and `replacePermissions`; computes added/removed grants |
| `src/main/resources/messages/messages.properties` | `error.admin.authority` |
| `src/main/resources/messages/messages_vi.properties` | Vietnamese translation |
| `src/test/java/com/project/dacn/AdminAuthorityPolicyControllerTest.java` | New MockMvc coverage (12 tests) |
| `docs/admin/admin-api-contract.md` | New `403` cases and authority notes |

No migration or entity change.

## Tests

TDD evidence:

* **RED:** `.\mvnw.cmd test "-Dtest=AdminAuthorityPolicyControllerTest"` —
  `Tests run: 12, Failures: 8`; the eight escalation tests returned `200/201`
  instead of `403`. The four allowed-path tests already passed.
* **GREEN:** same command — `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`.
* **Admin regression:** the six admin controller test classes —
  `Tests run: 90, Failures: 0, Errors: 0, Skipped: 0`.
* **Full suite:** `.\mvnw.cmd test` —
  `Tests run: 1665, Failures: 0, Errors: 0, Skipped: 184`, `BUILD SUCCESS`.

All tests use the `test` profile (`@ActiveProfiles("test")`, H2 in-memory).
No command was run against the shared Supabase database.

New test coverage maps to the requested scenarios:

| Scenario | Test |
|---|---|
| only `user:create`: `admin` → `403` | `userCreateOnly_cannotCreateAdmin_403` |
| only `user:create`: `employee` → `201` | `userCreateOnly_canCreateEmployee_201` |
| only `user:update`: self-promote → `403` | `userUpdateOnly_cannotPromoteSelf_403` |
| only `user:update`: promote another user to admin → `403` | `userUpdateOnly_cannotPromoteAnotherUserToAdmin_403` |
| only `user:update`: demote an admin → `403` | `userUpdateOnly_cannotDemoteAnAdmin_403` |
| only `user:update`: assign a fully-held custom role → `200` | `userUpdateOnly_canAssignRoleWhosePermissionsItHolds_200` |
| only `user:update`: lock an admin → `403` | `userUpdateOnly_cannotLockAnAdmin_403` |
| only `user:delete`: delete an admin → `403` | `userDeleteOnly_cannotDeleteAnAdmin_403` |
| only `role:update`: grant itself `user:update` → `403` | `roleUpdateOnly_cannotGrantItselfUserUpdate_403` |
| only `role:update`: edit the admin role → `403` | `roleUpdateOnly_cannotEditAdminRole_403` |
| only `role:update`: grant a held permission to another role → `200` | `roleUpdateOnly_canGrantAPermissionItHoldsToAnotherRole_200` |
| admin keeps full access | `admin_keepsFullAccess` |

## Choices made where the spec was ambiguous

1. **Existing `409` rules keep precedence over the new `403`.** The existing
   self-lock/delete/demote and last-active-admin checks run first, so a
   non-admin who targets the last active admin still gets the historical `409`.
   No endpoint changes state either way; this preserves the existing tests and
   the documented status/message. The new `403` fires only when no existing
   `409` rule applies.
2. **An active `admin` is treated as holding every permission.** Rule 3 says
   the actor must hold every permission involved "(admin holds all)". In
   production the admin role holds all eight `user:*`/`role:*` permissions, but
   the test fixtures give the admin role only the permissions needed per test.
   The policy therefore short-circuits the subset check for an `admin`; a
   non-admin must actually hold every permission being handed out.
3. **Role assignment checks the requested role's permissions, not the role
   being left.** For a non-admin role change, the risk is granting the target
   role's authority. A demotion away from a role the actor does not fully hold
   removes authority and is not treated as escalation.
4. **Permission replacement checks only newly added grants.** Revoking an
   ordinary permission does not require holding it (removal cannot escalate the
   actor). Any add *or* remove of a `user:*` / `role:*` permission is
   admin-only. This still blocks the `role:update` actor from granting itself
   `user:update`.
5. **Unlocking an admin is not admin-only.** The spec lists "lock, delete or
   demote"; unlock is a state change in the opposite direction and was not
   named, so it remains governed only by `user:update`.
6. **Admin self-demote remains `409`.** "Nobody may change their own role"
   would otherwise conflict with the explicitly preserved
   `error.user.self.demote` `409`. The legacy check wins; non-admin self role
   changes return the new `403`.
7. **Documentation path.** The brief named `docs/admin-api-contract.md`, but
   the working tree has that file moved to `docs/admin/admin-api-contract.md`
   (the old path is staged as deleted/added by the in-progress docs reorg). The
   contract was updated at `docs/admin/admin-api-contract.md`.
8. **Self role-change is checked on a real change only.** `PATCH .../role`
   with the same role remains a `200` no-op; it is not a privilege change.
