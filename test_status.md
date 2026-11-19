# Backend Investigation Report — INT‑04 and Input‑Length Reports (ADD‑08, ADD‑13, RA‑02, RA‑06) plus RBAC Review

**Scope:** static source inspection of the current working tree; no source/test/config/schema changes, no dependency installation, no database access, no test-suite execution. The only dynamic check was a standalone bcrypt probe run in a temp directory (no DB, no repo files) to confirm password-length behavior.

**Working tree at investigation time:** branch `dev`; `git status --porcelain` shows only the pre-existing staged file `A docs/admin-api-contract.md`. I did not modify it or any other file.

---

## 1. Executive summary

| ID | Finding | Severity | Responsibility | Status / Confidence |
|---|---|---|---|---|
| INT‑04 | A still-valid access token rejected during lock becomes valid again after unlock | **Medium** (High when lock is used for incident containment) | BACKEND | **Confirmed**, high confidence |
| ADD‑08 | Password max length is declared as 128 **characters**, but bcrypt rejects >72 **UTF‑8 bytes**; login silently truncates at 72 bytes | **Medium** | BACKEND (frontend truncation unconfirmed) | **Confirmed**, high confidence |
| ADD‑13 | Admin-create address limit is correctly 255; but `PATCH /auth/me` allows 500 chars against a `VARCHAR(255)` column → misleading 409 | **Low** primary, **Medium** for the `/auth/me` mismatch | BACKEND | **Confirmed**, high confidence |
| RA‑02 | Role code >50 chars or regex-invalid is rejected with 400; no backend truncation | **Low / not reproducible** | BACKEND (frontend behavior unconfirmed) | **Confirmed**, high confidence |
| RA‑06 | Role name/description limits align with DTO and DB; no truncation | **Low / not reproducible** | BACKEND | **Confirmed**, high confidence |
| RBAC‑01 | `user:create` alone can create a new **admin** account with a chosen password | **High** (when delegated) | BACKEND | **Confirmed**, high confidence |
| RBAC‑02 | `user:update` allows self-promotion (or promoting an accomplice) to `admin`; only self-demotion is blocked | **High** (when delegated) | BACKEND | **Confirmed**, high confidence |
| RBAC‑03 | `role:update` allows granting arbitrary existing permissions to the actor’s own role, then escalating | **High** (when delegated) | BACKEND | **Confirmed**, high confidence |
| RBAC‑04 | Last-active-admin guard exists and uses pessimistic locks, but there is no concurrency test and no actor-hierarchy model | **Medium** | BACKEND | Guard logic confirmed; concurrency behavior unverified |

“When delegated” means: with the default seed only `admin` has `user:*` / `role:*`, so the escalation is latent until an administrator grants any of those permissions to a custom role. Once delegated, the escalation path is direct and confirmed by code.

---

## 2. INT‑04 — Old access token becomes valid again after unlock

### 2.1 Root cause

Access tokens are stateless bearer JWTs with no revocation identifier or per-user version. Account status is checked **live** on every request, which makes locking effective immediately, but it also makes unlocking effective immediately for any still-unexpired token.

Evidence:

- `security/JwtService.java:30-39` — `generateAccessToken` sets only `sub`, `email`, `type`, `iat`, `exp`. No `jti`, no `token_version`/`auth_version`.
- `security/JwtService.java:73-75` — `validateAccessToken` accepts any correctly signed, unexpired token whose `type` is `access`.
- `security/JwtAuthenticationFilter.java:37-56` — after signature validation, the filter loads the principal from the DB and:
  - rejects it while `!isEnabled() || !isAccountNonLocked()` (`:46-48`);
  - authenticates it as soon as those become true again (`:49-55`).
- `security/CustomUserDetailsService.java:71-72` — `isEnabled = status == ACTIVE`, `isAccountNonLocked = status != LOCKED`.
- `service/auth/AdminUserService.java:176-197` — lock sets `LOCKED`, calls `revokeAllRefreshTokens` only when locking (`:190-192`); unlock sets `ACTIVE` and revokes nothing.
- `service/auth/AdminUserService.java:292-296` — revocation only flips `refresh_tokens.token_revoked`.
- `service/auth/AuthService.java:270-274` — refresh requires `status == ACTIVE`; a revoked refresh token stays unusable after unlock, so only the access token revives.

### 2.2 Reproduction sequence (deterministic)

1. User logs in; access token `A` (15 min TTL) and refresh token `R` are issued.
2. Admin locks the user. `A` is rejected on the next request → 401; `R` is revoked.
3. Admin unlocks the user within 15 minutes of issuance.
4. `A` is accepted again on the next request; `R` remains revoked. After `A` expires, the user must log in again.

This is exactly the gap in the existing test: `AdminUserMutationControllerTest.java:519-563` asserts that `A` is rejected **during** lock (`:544-547`), unlocks (`:549-555`), then only tests a fresh login (`:557`) — it never reuses `A` after unlock. `AuthServiceTest.java:317-331` likewise only covers the locked state.

### 2.3 Related systemic scope (same root cause)

Because there is no access-token revocation, the 15-minute window also applies to:

- **Logout** — `AuthService.logout` (`:326+`) revokes refresh tokens only; access tokens remain valid until expiry.
- **Password change** — `AuthService.changePassword` (`:429-449`) deletes other refresh tokens but leaves already-issued access tokens valid.
- **Deleted accounts** — deleted users are rejected because the principal query uses `@SQLRestriction`, not because the token is revoked.
- **Role/permission changes** — these reload live from the DB each request (documented as intended at `docs/admin-api-contract.md:136-137`), so this one is by design.

INT‑04 is therefore the most visible symptom of a missing access-token invalidation mechanism, not an isolated unlock bug.

### 2.4 Contract contradiction

`docs/admin-api-contract.md:134-135` says the existing access token “also stops working on the next request” after lock (true), and `:422-423` says “Unlocking does not restore revoked refresh tokens; the user must log in again” — which implies the old session is dead, but the old access token actually works again. The document is uncommitted and must be corrected alongside the fix.

### 2.5 Recommended fix

Preferred: per-user `token_version` (`auth_version`):

- Add `users.token_version BIGINT NOT NULL DEFAULT 0` (new Flyway migration, e.g. `V30`).
- Put `ver` in the access token (`JwtService.generateAccessToken`) and compare it to the DB value in `JwtAuthenticationFilter` after loading the principal.
- Increment on **lock and unlock**, password change/reset, logout-all, and (optionally) role changes that must terminate sessions.
- On mismatch → clear context / 401.

Alternatives, in order:

1. `tokens_valid_after` timestamp compared with `iat` — simpler, but second-granularity and clock-sensitive.
2. Redis `jti` denylist — add `jti` to access tokens and check fail-closed; introduces a hard dependency on Redis availability for all authenticated traffic.
3. Server-side sessions — larger redesign, not warranted here.

Migration needed only for options 1–2 if persisted; the Redis option needs no schema change but changes runtime dependencies. No API response shape changes. Contract wording must state that pre-lock/unlock access tokens are invalid after unlock and the client must re-authenticate.

### 2.6 Regression tests

- Lock → old token 401 (already covered).
- Unlock → **same old token still 401** (missing; add to `AdminUserMutationControllerTest` and/or a filter-level test).
- Unlock → fresh login works; old refresh still 401 (already covered).
- Logout/password change → old access token rejected (new, if the systemic fix is implemented).

**Confidence:** High. Root cause is directly readable in the filter and token generator; no runtime ambiguity.

---

## 3. ADD‑08 — Password length mismatch (128 chars vs 72 bytes)

### 3.1 Root cause

The DTOs validate password length in **characters**, while the configured encoder (`BCryptPasswordEncoder`, `config/SecurityConfig.java:30-31`) has a hard **72-byte** input limit:

- `dto/request/auth/AdminUserCreateRequest.java:14-16` — `@Size(min=8,max=128)`.
- `dto/request/auth/RegisterRequest.java:15-16` — `@Size(min=8,max=128)`.
- `dto/request/auth/ChangePasswordRequest.java:10-12` — `@Size(min=8,max=128)`.
- `dto/request/auth/LoginRequest.java:13-15` — no maximum at all.
- `service/auth/AuthService.java:80` (register), `:437` (change password), and `service/auth/AdminUserService.java:143` (admin create) call `passwordEncoder.encode(...)`.
- `AuthService.java:118-120` calls `passwordEncoder.matches(...)` on login.

Standalone probe against the project’s actual dependency (`spring-security-crypto 7.1.1`):

```
hashpw 72 ASCII bytes -> ok
hashpw 73/100 ASCII bytes -> IllegalArgumentException: password cannot be more than 72 bytes
hashpw 40 chars "é" (80 UTF-8 bytes) -> IllegalArgumentException
checkpw 72-byte password, input 73/100 bytes -> true
```

Consequences:

1. A password of 73–128 characters passes Bean Validation, then `encode` throws `IllegalArgumentException`.
2. `common/exception/GlobalExceptionHandler.java:105-109` maps that to **400** with the raw technical message `password cannot be more than 72 bytes` — a field-level contract violation presented as a service error.
3. Non-ASCII passwords are worse: a 30–40 character password can exceed 72 bytes and fail even though the documented limit is 128 characters.
4. **Login silently truncates at 72 bytes** (probe: `checkpw` returns true for 73/100-byte variants of a 72-byte password). Login never rejects long input, so a password sharing the first 72 bytes authenticates. This creates password-equivalence classes and asymmetry between registration and login.
5. There is no way to create a >72-byte password through supported APIs, so any observed “silently truncated stored password” cannot come from the backend service layer as written. If a client observed truncation, it is either client-side truncation or a non-API/imported hash. The frontend source is not in this repository (`CONTEXT.md:32-33`), so that part is **UNCONFIRMED** and attributed to FRONTEND for verification.

### 3.2 Responsibility

BACKEND: DTO/service validation does not match the encoder; login does not reject overlong input; the 400 message is not a validation message. FRONTEND: possible client-side truncation, unverified.

### 3.3 Recommended fix

- Short term: add explicit UTF‑8 byte validation (`password.getBytes(StandardCharsets.UTF_8).length <= 72`) in the DTO/service path and a matching login check that returns 400 rather than truncating. Update the documented limit to “8–128 characters **and** ≤72 UTF‑8 bytes”.
- Better: migrate to PBKDF2/Argon2 (Spring Security supports both; Argon2 needs a dependency) and rehash on successful login. This removes the byte limit entirely and is the durable fix.
- Keep `users.user_password_hash VARCHAR(255)`; bcrypt output is 60 chars, so no schema change is needed.
- API contract: field-level 400 messages, documented byte limit; if the encoder is changed, the password format contract must be announced to the frontend (no response shape change).

### 3.4 Regression tests

- Admin create: 72-byte ASCII → 201 and login works.
- Admin create: 73-byte ASCII, 129 chars, and 40×`é` → 400 with a field-level message (not a raw encoder exception).
- Register and change-password: same boundary cases.
- Login: 72-byte password + 1-byte suffix → 401 (after the fix; currently `true`).
- Login: 129+ chars → 400 or 401 consistently (decide and document).

**Confidence:** High for backend behavior (probe + source). The reported stored-value truncation is unconfirmed and likely client-side.

---

## 4. ADD‑13 — Address length

### 4.1 Admin create path

- `AdminUserCreateRequest.java:26-27` — `@Size(max=255)`.
- `V29__admin_user_management.sql:20-24` — `admin_address VARCHAR(255)`.
- `V1__initial_schema.sql:155` — `employee_address VARCHAR(255)`.
- `AdminUserService.create` (`:119-173`) only trims (`trimToNull` at `:139`); no truncation.

Result: 255 chars accepted; 256+ → Bean Validation **400** before any DB write. No bug in the admin-create path. The reported truncation is not reproducible from backend code.

### 4.2 Separate confirmed defect: `PATCH /api/v1/auth/me`

- `dto/request/auth/ProfileUpdateRequest.java:16-17` — `@Size(max=500)`.
- `V1__initial_schema.sql:141` — `customer_address VARCHAR(255)`.
- `AuthService.updateProfile` (`:387-398`) assigns and saves without a length check.

Result: 256–500 characters pass validation, the DB rejects the write, and `GlobalExceptionHandler.java:45-50` returns **409** with `resource already exists or is still referenced` — misleading, and a client-visible contract inconsistency (`500` in the DTO, `255` in the DB). Since `updateProfile` only supports customer profiles (`:389-390`), this affects customers, not staff.

### 4.3 Responsibility

BACKEND for the `/auth/me` mismatch. The admin-create truncation report is **not reproducible**; if observed, it is FRONTEND or an external writer — UNCONFIRMED.

### 4.4 Recommended fix and tests

- Align `ProfileUpdateRequest.address` to `@Size(max=255)`, or migrate `customers.customer_address` and `employees.employee_address` to `VARCHAR(500)`/`TEXT` if 500 is the intended product limit. Prefer aligning to 255 unless the product requires longer addresses; 500 needs a migration.
- Add tests: `PATCH /auth/me` with 255 → 200; 256 → 400; 500 → 400 (not 409). Admin create 255/256 → 201/400.
- Contract: update the documented profile address limit (`docs/admin-api-contract.md` currently documents 255 for admin create; the `/auth/me` document must match the chosen value).

**Confidence:** High.

---

## 5. RA‑02 — Role code length

### 5.1 Findings

- `AdminRoleCreateRequest.java:8-9` — `@Size(max=50)` plus `@Pattern("^[A-Za-z][A-Za-z0-9_]{1,49}$")`, so 2–50 characters.
- `V1__initial_schema.sql:43` — `role_code VARCHAR(50)`.
- `AdminRoleService.createRole` (`:161-196`) trims and lowercases only (`:163`); no truncation.
- `AdminRoleController.java:74` requires `role:create`.
- Bean Validation runs before the service (`AdminRoleController.java:74-80`), so 51+ chars or regex violations → **400**; no DB write.

Conclusion: no backend truncation and no length bug. The reported >50 code acceptance/truncation cannot be reproduced. If the UI displayed a truncated code, it is client-side; the frontend is not in this repo (`CONTEXT.md:32-33`) → FRONTEND, UNCONFIRMED.

Minor hardening only: ensure the 400 message is field-scoped (currently `@Size`/`@Pattern` without custom messages produce default text; `GlobalExceptionHandler.java:86-97` prefixes the field name, so it is acceptable).

**Severity:** Low / not reproducible. **Responsibility:** BACKEND for verification; FRONTEND for the client display claim.

---

## 6. RA‑06 — Role name and description length

### 6.1 Findings

- `AdminRoleCreateRequest.java:10-11` — name `@Size(max=255)`, description `@Size(max=2000)`.
- `AdminRoleUpdateRequest.java:5-7` — name `@Size(max=255)`, description `@Size(max=2000)`.
- `V1__initial_schema.sql:44-45` — `role_name VARCHAR(255)`, `role_description TEXT`.
- `AdminRoleService.createRole` (`:169-179`) trims; `updateRole` (`:76-99`) trims and maps blank description to `null`; both reject blank names with `IllegalArgumentException` → 400.
- No truncation anywhere; 256-char name / 2001-char description → **400** before persistence.

Conclusion: no backend defect. The description column is `TEXT`, so the 2000 limit is purely a product/API limit; changing it requires only the DTO and contract, no migration. Name 255 is a hard DB limit.

**Severity:** Low / not reproducible. **Responsibility:** BACKEND (contract/tests only).

---

## 7. RBAC privilege-escalation risks

### 7.1 RBAC‑01 — `user:create` can create an admin

- `AdminUserController.java:56-62` — `POST /api/v1/admin/users` requires only `user:create`.
- `AdminUserService.create` (`:119-173`) accepts any existing non-`customer` role code (`:126-132`), including `admin`, and sets a caller-chosen password (`:141-146`), creating an `admins` profile (`:154-160`).

Impact: a delegated role with `user:create` can mint a full administrator account and log in with it. This bypasses the entire permission model. Confirmed by code and by `AdminUserMutationControllerTest.java:275-299`, which shows admin creation is a supported operation with only the create permission.

Recommended fix:

- Only an actor who is an active `admin` (or holds an explicit `user:create:admin`-style authority) may set `roleCode=admin`.
- Alternatively, restrict creatable roles to roles whose permission set is a subset of the actor’s permissions (role-hierarchy model).
- Return 403 (not 409) when the target role exceeds the actor’s authority.
- Tests: custom role with `user:create` only → attempting `roleCode=admin` → 403; creating `employee` still 201.

### 7.2 RBAC‑02 — Self-promotion via `user:update`

- `AdminUserController.java:72-78` — role change requires only `user:update`.
- `AdminUserService.updateRole` (`:199-254`) blocks self-**demotion** from `admin` (`:217-219`) and customer targets (`:205-210`), but has no check for self-promotion or promotion above the actor’s own role.
- `:228-236` creates the `admins` profile, `:248-249` persists the new role.

Impact: an actor with `user:update` can change their own role from `employee`/custom to `admin`, effective on the same token on the next request (permissions reload live). They can also promote an accomplice account.

Recommended fix:

- Forbid changing your own role entirely (except perhaps display-only operations), or require an actor strictly “above” the target role.
- Require the actor to be an active `admin` to assign `admin`.
- Add a hierarchy check: actor cannot assign a role with permissions they do not hold.
- Tests: self-promotion → 403; non-admin with `user:update` promoting an accomplice to admin → 403.

### 7.3 RBAC‑03 — `role:update` can grant arbitrary permissions

- `AdminRoleController.java:65-70` — `PUT /admin/roles/{id}/permissions` requires only `role:update`.
- `AdminRoleService.replacePermissions` (`:102-159`) validates only that the permission IDs exist (`:119-129`); `ensureAdminKeepsUserAndRolePermissions` (`:215-229`) only prevents the **system admin** role from losing `user:*`/`role:*`.
- There is no check that the actor holds the permissions being granted, and no restriction on modifying the actor’s own role.

Impact: an actor with `role:update` can add `user:update` (or any other permission) to their own custom role, then use RBAC‑02 to become admin. This is a complete escalation chain from `role:update` alone. Effective immediately because `CustomUserDetailsService` reloads permissions on every request (`:51-55`).

Recommended fix:

- Only an `admin` may modify the `admin` role (already partially enforced) and only admins may grant `user:*`/`role:*` permissions.
- Enforce that granted permissions are a subset of the actor’s effective permissions (no privilege amplification).
- Forbid editing the actor’s own role’s permissions, or require a second approver for self-affecting changes.
- Tests: custom role with `role:update` granting itself `user:update` → 403; granting a permission the actor lacks to another role → 403; admin can still grant any.

### 7.4 RBAC‑04 — Last-admin guard and concurrency

- `AdminUserService.ensureNotLastActiveAdmin` (`:278-290`) correctly covers lock (`:184-186`), demotion from `admin` (`:215-222`), and delete (`:264`).
- `UserRepository.java:73-75` uses `PESSIMISTIC_WRITE` (`findByRoleCodeAndStatusForUpdate`), and `RoleRepository.java:16-18` locks roles similarly.
- Existing tests cover sequential last-admin lock/delete/demote (`AdminUserMutationControllerTest.java:439-457`, `:679-712`) and self-lock/delete/demote (`:651-668`).

Residual risk: no test exercises two concurrent demotions/locks; correctness depends on PostgreSQL `SELECT ... FOR UPDATE` re-evaluation under READ COMMITTED (probable, but unverified here). There is also no “actor cannot modify a peer/superior admin” rule, and `deleteRole`/`updateRole` do not consider who is acting (only `role:update`/`role:delete`). Severity Medium, defense-in-depth.

Recommended fix/tests:

- Add a concurrency integration test (two transactions, one must fail with 409) if the shared DB policy permits an isolated test schema; otherwise document the guarantee.
- Add an actor-hierarchy rule for admin-to-admin mutations.
- Keep the guard, but centralize it in one policy method to avoid drift.

---

## 8. Existing test coverage and gaps

| Area | Covered | Gap |
|---|---|---|
| Lock/unlock | Lock blocks login/refresh/old access token; unlock allows fresh login; old refresh remains 401 (`AdminUserMutationControllerTest:519-563`, `AuthServiceTest:317-331`) | **No assertion that the old access token stays rejected after unlock (INT‑04)** |
| Password length | Only generic short-password validation (`AdminUserMutationControllerTest:486-503`) | No 72/73-byte, 128-char, or non-ASCII boundary tests; no login truncation test |
| Address length | No boundary tests | 255/256 for admin create; 255/256/500 for `PATCH /auth/me` |
| Role code/name/description | No boundary tests | 50/51 code, 255/256 name, 2000/2001 description |
| RBAC create/update | Admin creation, custom-role creation, role changes, permissionless 403s, self-demote 409 | **No delegated-actor escalation tests** (create admin, self-promote, grant self permissions) |
| Last-admin | Sequential lock/delete/demote, self-actions | No concurrent mutation test, no peer-admin hierarchy test |
| Permission freshness | Role/permission changes visible on same token (`AdminUserMutationControllerTest:327-362`, `:589-622`) | This is intended, but it is also the mechanism that makes RBAC‑02/03 immediate |

No tests were run for this investigation; findings are based on test source. Do not run the suite against the shared database without an isolated schema.

---

## 9. Prioritized remediation plan

### P0 — Security boundary (next change window)

1. **RBAC‑01/02/03**: introduce a single authorization policy for admin mutations:
   - only active `admin` actors may create/promote to `admin` and grant `user:*`/`role:*`;
   - no self role changes;
   - granted permissions must be a subset of the actor’s permissions;
   - return 403 for authority violations.
2. **INT‑04**: add `users.token_version` + `ver` claim and filter check; increment on lock, unlock, password change/reset, and logout-all. New migration `V30`. Update the contract to say pre-lock tokens are invalid after unlock.
3. Tests for both, including the old-token-after-unlock regression.

### P1 — Input contract correctness

4. **ADD‑08**: enforce ≤72 UTF‑8 bytes at all password entry points and on login (reject, don’t truncate); update contract. Schedule PBKDF2/Argon2 migration as a follow-up.
5. **ADD‑13**: align `ProfileUpdateRequest.address` with the DB (255) or migrate the column if 500 is intended; ensure 400, not 409.

### P2 — Hardening and documentation

6. **RA‑02/06**: no code change required; add boundary tests and field-level messages; document that limits are character-based and enforced pre-persistence.
7. **RBAC‑04**: add the concurrency test (isolated DB) and peer-admin hierarchy rule.
8. Update `docs/admin-api-contract.md`:
   - unlock semantics (`:422-423`) and lock semantics (`:134-135`);
   - password byte limit;
   - `/auth/me` address limit;
   - 403 vs 409 behavior for authority violations.

### Migration and API-contract impact summary

| Change | Migration | API contract |
|---|---|---|
| `token_version` | Yes: `V30__user_token_version.sql` (`BIGINT NOT NULL DEFAULT 0`) | No response shape change; new 401 semantics after unlock |
| Redis `jti` denylist alternative | No | No response shape change; new Redis dependency |
| Password 72-byte validation | No | Documented limit changes; field-level 400 |
| Profile address 255 | No (align DTO), or yes if 500 chosen | Documented limit changes; 400 instead of 409 |
| RBAC policy | No | New 403 cases; frontend must handle 403 on admin mutations |

---

## 10. Frontend findings and communication list

The frontend is **not in this repository** (`CONTEXT.md:32-33`, `/FE-DACN` is gitignored), so client-side behavior could not be verified. Items to communicate:

1. **Do not truncate passwords client-side.** Send the exact value and display the backend’s 400 message. The backend rejects >72 UTF‑8 bytes; a character counter that says “128 max” is misleading for non-ASCII input.
2. **Address limits:** admin create is 255; `PATCH /auth/me` currently advertises 500 but fails at 256 with a 409. Until the backend is fixed, cap the customer profile address at 255 characters.
3. **Lock/unlock session handling:** after an account is locked, the refresh token is dead and the access token is rejected. After unlock, the *old access token* is accepted again until it expires (INT‑04). The client should treat lock as terminal for that session: clear local session state and require a fresh login, rather than assuming unlock restores the session.
4. **Role and permission changes are live:** a role/permission change takes effect on the next request with the same access token (`docs/admin-api-contract.md:136-137`). The UI should refresh `/auth/me` after such changes; it must not cache permissions client-side for authorization decisions.
5. **403 vs 409:** after the RBAC fixes, attempts to create/promote above the actor’s authority will return 403, not 409. The UI should show “you do not have permission for this role” rather than a generic conflict.
6. **Role code/name/description:** the UI should enforce the server limits (code 2–50 matching `^[A-Za-z][A-Za-z0-9_]{1,49}$`, name 255, description 2000) and surface 400 messages; no backend truncation exists.
7. **Contract document:** `docs/admin-api-contract.md` is currently uncommitted/staged. Its lock/unlock language at `:134-135` and `:422-423` must be corrected before it is published to the frontend team.

---

## 11. Limits and confidence

- All conclusions are from the current source tree; no runtime integration tests were executed against a database.
- The bcrypt behaviors (hashpw rejection at >72 bytes; checkpw truncation and match for 73/100-byte variants) were reproduced with a standalone probe using the project’s `spring-security-crypto 7.1.1`; this is deterministic.
- The last-admin concurrency guarantee is based on PostgreSQL `FOR UPDATE` semantics and was not empirically tested; treat RBAC‑04’s concurrency status as “probable, unverified”.
- Frontend observations are unverified because the frontend source is not present.

**No source, test, configuration, or schema files were changed.** `git status` still shows only the pre-existing staged `docs/admin-api-contract.md` plus this report file. No tests were run. Awaiting approval before any fixes or further verification.
