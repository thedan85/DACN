# Step 1 Result — Filter Fix, Permissions, Migration V29

**Status:** Step 1 code changes are complete. The full clean test suite is **not green** because one pre-existing payment test fails. The new regression test passes. I stopped before any Step 2 work.

No migration was run against any database. V29 is written but **not verified on PostgreSQL**.

---

## a) JwtAuthenticationFilter fix and regression test

### RED — test failed before the fix

Command:

```powershell
.\mvnw.cmd "-Dtest=AuthServiceTest#lockedUser_oldAccessToken_rejectedByMe" test
```

Result:

```text
Status expected:<401> but was:<200>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

The response body was still a normal `/auth/me` 200 response for the locked user.

### Fix

`src/main/java/com/project/dacn/security/JwtAuthenticationFilter.java:46-47`

```java
if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
    SecurityContextHolder.clearContext();
} else {
    // build and set the authentication
}
```

The filter still loads the user from the DB on every request, but a disabled/locked principal is no longer placed into the security context. The existing `AuthenticationEntryPoint` therefore returns 401 on the next request.

### GREEN — test passed after the fix

Same command after the fix:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Regression test added at `src/test/java/com/project/dacn/AuthServiceTest.java:279`:

```java
@Test
void lockedUser_oldAccessToken_rejectedByMe() throws Exception {
    authService.register(registerRequest("locked-access@test.com"));
    LoginResponse login = authService.login(
            new LoginRequest("locked-access@test.com", "password123"), null, null);

    User user = userRepository.findByEmail("locked-access@test.com").orElseThrow();
    user.setStatus(UserStatus.LOCKED);
    userRepository.save(user);
    entityManager.flush();
    entityManager.clear();

    mockMvc.perform(get("/api/v1/auth/me")
                    .header("Authorization", "Bearer " + login.accessToken()))
            .andExpect(status().isUnauthorized());
}
```

Surefire confirms the test is present and passing:

```text
<testcase name="lockedUser_oldAccessToken_rejectedByMe"
          classname="com.project.dacn.AuthServiceTest" time="0.154"/>
```

`AuthServiceTest` full result: **32 tests, 0 failures, 0 errors, 0 skipped**.

---

## b) Permissions constants, entities, and migration V29

### Permissions constants

`src/main/java/com/project/dacn/security/Permissions.java:29-32`

```java
public static final String USER_READ = "user:read";
public static final String USER_CREATE = "user:create";
public static final String USER_UPDATE = "user:update";
public static final String USER_DELETE = "user:delete";
```

### Admin entity

`src/main/java/com/project/dacn/entity/identity/Admin.java:42-46`

```java
@Column(name = "admin_phone", length = 20)
private String phone;

@Column(name = "admin_address")
private String address;
```

Both are nullable, matching the approved decision. No mapper/DTO changes were needed for the existing tests.

### Employee entity

`src/main/java/com/project/dacn/entity/identity/Employee.java:45-46`

```java
@Column(name = "employee_phone", length = 20)
private String phone;
```

`nullable = false` was removed, matching the V29 `DROP NOT NULL`.

### Migration

Created `src/main/resources/db/migration/V29__admin_user_management.sql`.

Contents:

- `ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_phone VARCHAR(20) NULL;`
- `ALTER TABLE admins ADD COLUMN IF NOT EXISTS admin_address VARCHAR(255) NULL;`
- `ALTER TABLE employees ALTER COLUMN employee_phone DROP NOT NULL;`
- Inserts `user:read`, `user:create`, `user:update`, `user:delete` with deterministic IDs `550e8400-e29b-41d4-a716-446655440801` through `...0804`.
- Grants them to the admin role only, joining `roles` by `lower(role_code) = 'admin'` and `permissions` by code.
- Uses `ON CONFLICT DO NOTHING` without naming columns, following V16.

The migration was **not executed**. No dev profile and no Flyway run against any database.

### DELETE behavior decision recorded for Step 3

Per your change, DELETE will follow the existing DELETE endpoints, not force 204.

Existing examples:

- `ProductController.java:109-114` returns `ApiResponse<Void>` with `ApiResponse.ok(null)`.
- `CategoryController.java:62-67` returns the same.
- `CartController.java:39-43` returns the same.

So `DELETE /api/v1/admin/users/{userId}` will return **200** with the standard `ApiResponse` body (`code: 200`, `message: "OK"`, `data: null`).

---

## c) Test result

### First full run (without clean)

```text
Tests run: 538, Failures: 1, Errors: 5, Skipped: 10
```

The 5 errors were `StaffOrderPersistenceTest` context failures caused by missing `OrderService.class` in the stale `target/classes` output. A clean build regenerated it.

### Clean full run

Command:

```powershell
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 538, Failures: 1, Errors: 0, Skipped: 10
BUILD FAILURE
```

Surefire totals:

```text
files=67 tests=538 failures=1 errors=0 skipped=10
```

The only failing test:

```text
com.project.dacn.service.payment.PaymentServiceTest
createUrlSignsOrderIdAndWholeVndAmount:67
Expected URL to contain: vnp_TxnRef=ORD_1
Actual URL contains:     vnp_TxnRef=PAY_1
```

### Why this failure is not caused by Step 1

No payment file was modified in Step 1.

The source and test disagree independently of the admin-user work:

- `PaymentService.java:51` builds the VNPay URL with `payment.getPaymentId()` as the transaction reference.
- `PaymentService.java:81` resolves an IPN by `paymentRepository.findByPaymentId(txnRef)`, so the service is internally consistent using `paymentId`.
- `PaymentServiceTest.java:67` still expects `vnp_TxnRef=ORD_1`.

The baseline 530-pass run was reading stale compiled output. A clean compile exposes the existing mismatch.

This needs your approval because payment is owned by another team member. I have **not** changed the test or the payment service.

---

## Files created or changed in Step 1

Created:

- `src/main/resources/db/migration/V29__admin_user_management.sql`
- `docs/step_1_result.md`

Changed:

- `src/main/java/com/project/dacn/security/JwtAuthenticationFilter.java`
- `src/test/java/com/project/dacn/AuthServiceTest.java`
- `src/main/java/com/project/dacn/security/Permissions.java`
- `src/main/java/com/project/dacn/entity/identity/Admin.java`
- `src/main/java/com/project/dacn/entity/identity/Employee.java`

No scratch scripts, logs, or editor settings were created.

---

## Decision needed before Step 2

The test gate in your instruction requires all existing tests to pass. One pre-existing payment test blocks that.

My recommendation:

- Update `PaymentServiceTest.java:67` from `vnp_TxnRef=ORD_1` to `vnp_TxnRef=PAY_1`, and rename the test to reflect that the VNPay transaction reference is the payment ID.
- This matches the service's IPN lookup (`findByPaymentId`) and is a test-only correction.

Alternative:

- If the intended contract is that `vnp_TxnRef` must be the order ID, the payment owner needs to change `PaymentService` and the IPN lookup together. I will not touch it without approval.

I stopped here as instructed. Step 2 has not started.
