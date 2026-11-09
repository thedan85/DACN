# Step 2 Answer — RBAC Admin Read API

Step 2 is complete and verified. Stopped for approval.

## Result

Command run:

```powershell
.\mvnw.cmd clean test
```

Output:

```text
Tests run: 621, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
```

Step 1 baseline was 613 tests; Step 2 adds 8 tests and raises the count to 621 with 0 failures.

TDD red was recorded first: the same suite ran 621 tests with 5 failures, all `Status expected:<200> but was:<404>` for the missing role/permission handlers.

## Files created

- `src/main/java/com/project/dacn/dto/response/auth/AdminRoleSummaryResponse.java` — record at line 5
- `src/main/java/com/project/dacn/dto/response/auth/AdminRoleDetailResponse.java` — record at line 6
- `src/main/java/com/project/dacn/service/auth/AdminRoleService.java` — `listPermissions` line 29, `listRoles` line 36, `getRole` line 43
- `src/main/java/com/project/dacn/controller/auth/AdminRoleController.java` — endpoints at lines 29, 35, 41; `@PreAuthorize` at 30, 36, 42
- `src/test/java/com/project/dacn/AdminRoleControllerTest.java` — 9 tests at lines 135–263
- `docs/rbac-admin-step2-result.md` — full evidence report

## Files changed

- `src/main/java/com/project/dacn/repository/auth/RoleRepository.java:18` — `findByDeletedFalseOrderBySystemDescCodeAsc()`
- `src/main/java/com/project/dacn/repository/auth/PermissionRepository.java:14` — `findAllByOrderByResourceAscActionAscCodeAsc()`

## Behavior covered by tests

- 401 without token on all three GET endpoints.
- 403 for customer and permission-less employee.
- 200 for admin.
- `/permissions` sorted by resource, action, code and includes all `role:*` permissions.
- `/roles` sorted `system DESC, code ASC`, excludes soft-deleted roles, exposes `system`.
- `/roles/{id}` returns permissions sorted by code.
- Unknown and soft-deleted role -> 404.
- No `deleted` field, surrogate `id`, or `passwordHash` in role responses.

## Constraints

No admin user, catalog, cart, order, payment, review, migration, or security-config code was changed. The old unsafe role DTOs were not reused. No dev profile, Flyway run, git command, or secret output was used. Only `.\mvnw.cmd clean test` was used for verification.
