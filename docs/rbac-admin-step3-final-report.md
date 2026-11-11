# RBAC Admin API — Step 3 Final Report

Step 3 is complete and green. Stopping here for review.

## Test evidence

`.\mvnw.cmd clean test` (H2 test profile only):

```
Tests run: 640, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
```

Step 2 baseline was 621 tests, so Step 3 adds 19 tests with 0 failures.

## Files created

| File | Evidence |
|---|---|
| `src/main/java/com/project/dacn/dto/request/auth/AdminRoleUpdateRequest.java` | `:5-7` — name/description only, no code field |
| `src/main/java/com/project/dacn/dto/request/auth/AdminRolePermissionsUpdateRequest.java` | `:8-9` — `@NotNull List<@NotBlank String>` |
| `src/main/java/com/project/dacn/common/exception/RoleRuleViolationException.java` | `:7-24` — message key + args |
| `src/test/java/com/project/dacn/AdminRoleMutationControllerTest.java` | 19 tests at `:124-368` |
| `docs/rbac-admin-step3-result.md` | Step 3 result report |
| `docs/rbac-admin-step3-final-report.md` | This final report |

## Files changed

| File | Change / evidence |
|---|---|
| `GlobalExceptionHandler.java` | `:78-84` — `RoleRuleViolationException` -> 409, same pattern as user rules |
| `messages.properties` | `:14-17` — four role keys |
| `messages_vi.properties` | `:18-21` — four matching keys |
| `RoleRepository.java` | `:16-18` — `@Lock(PESSIMISTIC_WRITE)` `findByRoleIdForUpdate` |
| `PermissionRepository.java` | `:17` — `findByPermissionIdIn` |
| `AdminRoleService.java` | `:71-95` updateRole; `:97-154` replacePermissions; `:100` lock; `:109-112` duplicate check; `:114-124` missing ID 404; `:126` admin guard; `:128-151` diff replacement + flush; `:156-170` admin rule |
| `AdminRoleController.java` | `:53-59` PATCH; `:61-67` PUT; `@PreAuthorize` at `:54,62` |

## Coverage highlights

- Plan items 7–23 are covered, including failed PUT leaving grants unchanged, both admin guard 409 cases, and the same-token test where `product:create` removal turns the next request with the same JWT into 403.
- `put_retainsGrantedAtAndRowIdForRetainedGrants` (`:250`) verifies retained grants keep the same row ID and `grantedAt`.
- 401 without token and 403 for customer/permission-less employee on both PATCH and PUT.

## Constraints

- No dev profile, no Flyway execution, no migrations touched, no git, no secrets printed.
- Admin user API and all catalog/product/cart/order/payment/review code untouched.

## Next step (not started)

Step 4: `POST /admin/roles` and `DELETE /admin/roles/{roleId}`, pending approval.
