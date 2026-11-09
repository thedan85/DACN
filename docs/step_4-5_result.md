# Step 4–5 Result — Verification and API Documentation

## Step 4 — verification

### 1. Admin order matcher isolation

The existing order rule is:

- `SecurityConfig.java:113-114` — `GET`/`PATCH /api/v1/staff/orders/**`
- `SecurityConfig.java:115-116` — `POST /api/v1/admin/orders/*/complete` → `ORDER_COMPLETE`

The admin-user rules were added after it:

- `SecurityConfig.java:117-118` — `GET /api/v1/admin/users`, `/api/v1/admin/users/**` → `USER_READ`
- `SecurityConfig.java:119-120` — `POST /api/v1/admin/users` → `USER_CREATE`
- `SecurityConfig.java:121-123` — `PATCH /api/v1/admin/users/*/status`, `/api/v1/admin/users/*/role` → `USER_UPDATE`
- `SecurityConfig.java:124-125` — `DELETE /api/v1/admin/users/*` → `USER_DELETE`

There is no `/api/v1/admin/orders/**` matcher in `SecurityConfig.java`; the only admin-order rule is `POST /api/v1/admin/orders/*/complete`. It has a different path prefix from every admin-user matcher, so `/api/v1/admin/users/**` rules cannot match an admin-order request. The order rule also appears earlier in the filter chain than the user rules. `AdminOrderControllerTest` passed in the final suite.

**Result: the admin-order matcher is unaffected.**

### 2. `401` without a token

| Endpoint | Evidence |
|---|---|
| `GET /api/v1/admin/users` | `AdminUserControllerTest.java:132-136` |
| `GET /api/v1/admin/users/{userId}` | `AdminUserControllerTest.java:139-143` |
| `POST /api/v1/admin/users` | `AdminUserMutationControllerTest.java:143-148` |
| `PATCH /api/v1/admin/users/{userId}/status` | `AdminUserMutationControllerTest.java:150-154` |
| `PATCH /api/v1/admin/users/{userId}/role` | `AdminUserMutationControllerTest.java:156-160` |
| `DELETE /api/v1/admin/users/{userId}` | `AdminUserMutationControllerTest.java:162-164` |

All six endpoints assert HTTP `401` and body `code = 401`.

**Result: fully covered.**

### 3. `403` for a customer and an employee without `user:*`

Read endpoints:

- `AdminUserControllerTest.java:146-161` logs in as `customer@test.com` and `employee@test.com` and checks both list and detail return `403`.

Mutation endpoints:

- `AdminUserMutationControllerTest.java:167-196` checks `403` for the customer actor on POST, PATCH status, PATCH role, and DELETE.
- `AdminUserMutationControllerTest.java:198-242` checks `403` for a separate permission-less employee actor on POST, PATCH status, PATCH role, and DELETE. The helper creates role `EMPLOYEE_NO_USER_PERMISSIONS` and user `employee-no-access@test.com`, so it does not reuse the seeded employee role that grants `user:update` and `user:delete` for the last-admin tests.
- The tests are split into four methods (`post_permissionlessEmployee_403`, `status_permissionlessEmployee_403`, `role_permissionlessEmployee_403`, `delete_permissionlessEmployee_403`) so each endpoint is a separate case.

**Result: customer and employee-without-permission coverage is complete for all six endpoints.**

### 4. `2xx` for an admin

| Endpoint | Evidence |
|---|---|
| `GET /api/v1/admin/users` | `AdminUserControllerTest.java:164-178` |
| `GET /api/v1/admin/users/{userId}` | `AdminUserControllerTest.java:181-199` |
| `POST /api/v1/admin/users` (employee and admin) | `AdminUserMutationControllerTest.java:199-227`, `230-253` |
| `PATCH /api/v1/admin/users/{userId}/status` | `AdminUserMutationControllerTest.java:315-358` |
| `PATCH /api/v1/admin/users/{userId}/role` | `AdminUserMutationControllerTest.java:384-416` |
| `DELETE /api/v1/admin/users/{userId}` | `AdminUserMutationControllerTest.java:511-548` |

The admin actor receives all four permissions in setup at `AdminUserMutationControllerTest.java:114-117`.

**Result: fully covered.**

### 5. Original checklist items not covered

From the Step 0 planned coverage (`docs/admin-users-step0-analysis.md:611-632`):

1. **`Customer/employee without user:* -> 403` is now fully covered for mutations.** The original verification found that the seeded employee role intentionally grants `user:update` and `user:delete` for the last-admin test, so a separate permission-less employee actor was added to `AdminUserMutationControllerTest`. All four mutation endpoints now return `403` for that actor.
2. **`Admin -> 200/201/204` is not literally met.** The approved DELETE contract is `200` with `data: null`, not `204` (`docs/step_1_result.md:136-146`; `AdminUserMutationControllerTest.java:515-519`). This is a documented deviation, not a regression.
3. **No other planned item is missing.** Keyword/role/status filters, pagination, sort whitelist, password/hash exclusion, hidden `DELETED` users, create profiles, duplicate and soft-deleted email, validation, lock/unlock token behavior, employee→admin permissions on `/auth/me`, customer role change, self lock/delete/demote, last-admin guard, and soft-delete token revocation all have tests.

Only `AdminUserMutationControllerTest.java` was changed for this follow-up; no production code was touched.

## Step 5 — API documentation

Created `docs/admin-users-api.md`.

It documents:

- The required permission for each of the six endpoints.
- Request JSON for POST, PATCH status, and PATCH role; query parameters for the list.
- Real response JSON captured from an actual H2 test run for list, detail, create, lock, unlock, role change, delete, and representative `400`/`401`/`403`/`404`/`409` responses.
- The error status mapping: `400`, `401`, `403`, `404`, `409`, `500`.
- All `409` rules with message keys and English messages.
- `DELETE` returns `200` with `data: null`, not `204`.
- An invalid `roleCode` returns `409`, not `400`.
- Unlock does not restore revoked refresh tokens.
- A soft-deleted email still causes a duplicate `409` on create.
- The list excludes `DELETED`, and `status=DELETED` returns `400`.
- The sort whitelist: `email`, `name`, `roleCode`, `status`, `lastLogin`, `createdAt`.
- `roleCode` is lowercase, `page` is zero-based, and `lastLogin` is nullable.
- Test fixture IDs, timestamps, and the test-only password are not real credentials.

Capture method: a temporary JUnit capture class was compiled under `target/` (never added to `src/`) and run with the H2 `test` profile. It wrote the raw response bodies to `target/capture-output.txt`; the temporary class and output were removed by the final `clean test`. No production code was changed.

No secrets or real credentials were added to the documentation.

## Step 3 correction — V29 status

`docs/step_3_result.md` previously ended with the incorrect statement that V29 had not been executed. That line has been corrected.

Actual state:

- V29 was applied on the shared Supabase database on `2026-11-06 07:14:49` with Flyway `success = true`.
- `role_permissions` already contains `rp-admin-user:read`, `rp-admin-user:create`, `rp-admin-user:update`, and `rp-admin-user:delete` for the admin role.
- **Do not re-run V29.**

## Final test result

Command:

```powershell
.\mvnw.cmd clean test
```

This is the clean form of the requested `.\mvnw.cmd test`; it removes stale compiled classes before running the same suite.

Result:

```text
Tests run: 613, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
Total time: 01:30 min
```

## Every file created or changed across all steps

Production:

| File | Step(s) |
|---|---|
| `src/main/java/com/project/dacn/security/JwtAuthenticationFilter.java` | 1 |
| `src/main/java/com/project/dacn/security/Permissions.java` | 1 |
| `src/main/java/com/project/dacn/entity/identity/Admin.java` | 1 |
| `src/main/java/com/project/dacn/entity/identity/Employee.java` | 1 |
| `src/main/java/com/project/dacn/repository/auth/AdminUserQueryRepository.java` | 2, 3 (follow-up A) |
| `src/main/java/com/project/dacn/service/auth/AdminUserService.java` | 2, 3 |
| `src/main/java/com/project/dacn/controller/auth/AdminUserController.java` | 2, 3 |
| `src/main/java/com/project/dacn/dto/response/auth/AdminUserResponse.java` | 2 |
| `src/main/java/com/project/dacn/dto/response/auth/AdminUserDetailResponse.java` | 2 |
| `src/main/java/com/project/dacn/config/SecurityConfig.java` | 2, 3 |
| `src/main/java/com/project/dacn/dto/request/auth/AdminUserCreateRequest.java` | 3 |
| `src/main/java/com/project/dacn/dto/request/auth/AdminUserStatusRequest.java` | 3 |
| `src/main/java/com/project/dacn/dto/request/auth/AdminUserRoleRequest.java` | 3 |
| `src/main/java/com/project/dacn/common/exception/UserRuleViolationException.java` | 3 |
| `src/main/java/com/project/dacn/config/MessageSourceConfig.java` | 3 |
| `src/main/java/com/project/dacn/repository/auth/UserRepository.java` | 3 |
| `src/main/java/com/project/dacn/common/exception/GlobalExceptionHandler.java` | 3 |
| `src/main/resources/messages/messages.properties` | 3 |
| `src/main/resources/messages/messages_vi.properties` | 3 |

Migration:

| File | Step |
|---|---|
| `src/main/resources/db/migration/V29__admin_user_management.sql` | 1 |

Tests:

| File | Step(s) |
|---|---|
| `src/test/java/com/project/dacn/AuthServiceTest.java` | 1 |
| `src/test/java/com/project/dacn/AdminUserControllerTest.java` | 2, 3 (follow-up A) |
| `src/test/java/com/project/dacn/AdminUserMutationControllerTest.java` | 3, follow-up (permission-less employee `403`) |

Documentation:

| File | Step(s) |
|---|---|
| `docs/step_1_result.md` | 1 |
| `docs/step_2_result.md` | 2 |
| `docs/step_3_result.md` | 3; V29 statement corrected in Step 5 |
| `docs/admin-users-api.md` | 5 |
| `docs/step_4-5_result.md` | 4–5 |

No other production or test files were changed by this work. The payment test rename and extra test count came from the user's pull, not from this work.
