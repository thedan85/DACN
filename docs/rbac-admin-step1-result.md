# RBAC Admin API — Step 1 Result

**Status:** Step 1 complete. Migration seed, permission constants, and URL rules added.

**Scope completed:**

1. New V30 migration with the four `role:*` permissions, granted to the admin role only.
2. Four constants in `security/Permissions`.
3. Seven URL authorization rules in `config/SecurityConfig`.

No admin user API file was changed.

---

## Changed files

### 1. `src/main/resources/db/migration/V30__rbac_role_permissions.sql` — new

Adds four permissions:

| Permission | ID | Resource | Action |
|---|---|---|---|
| `role:read` | `550e8400-e29b-41d4-a716-446655440901` | `role` | `read` |
| `role:create` | `550e8400-e29b-41d4-a716-446655440902` | `role` | `create` |
| `role:update` | `550e8400-e29b-41d4-a716-446655440903` | `role` | `update` |
| `role:delete` | `550e8400-e29b-41d4-a716-446655440904` | `role` | `delete` |

Grants:

```sql
INSERT INTO role_permissions (role_permission_id, role_id, permission_id, granted_at)
SELECT
  'rp-' || lower(r.role_code) || '-' || p.permission_code,
  r.id,
  p.id,
  now()
FROM roles r
JOIN permissions p
  ON p.permission_code IN ('role:read', 'role:create', 'role:update', 'role:delete')
WHERE lower(r.role_code) = 'admin'
ON CONFLICT DO NOTHING;
```

Properties:

- Seed-only. No schema change.
- Idempotent: `ON CONFLICT DO NOTHING`.
- Uses `r.id` and `p.id`, matching V12's surrogate-FK conversion and V29's grant style.
- No existing migration was modified.
- V30 was **not executed** against any database.

### 2. `src/main/java/com/project/dacn/security/Permissions.java` — edited

Added at lines 33-36:

```java
public static final String ROLE_READ = "role:read";
public static final String ROLE_CREATE = "role:create";
public static final String ROLE_UPDATE = "role:update";
public static final String ROLE_DELETE = "role:delete";
```

### 3. `src/main/java/com/project/dacn/config/SecurityConfig.java` — edited

Added at lines 127-139, after the existing admin-user rules and before `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/admin/permissions")
.hasAuthority(Permissions.ROLE_READ)
.requestMatchers(HttpMethod.GET,
        "/api/v1/admin/roles", "/api/v1/admin/roles/**")
.hasAuthority(Permissions.ROLE_READ)
.requestMatchers(HttpMethod.POST, "/api/v1/admin/roles")
.hasAuthority(Permissions.ROLE_CREATE)
.requestMatchers(HttpMethod.PATCH, "/api/v1/admin/roles/*")
.hasAuthority(Permissions.ROLE_UPDATE)
.requestMatchers(HttpMethod.PUT, "/api/v1/admin/roles/*/permissions")
.hasAuthority(Permissions.ROLE_UPDATE)
.requestMatchers(HttpMethod.DELETE, "/api/v1/admin/roles/*")
.hasAuthority(Permissions.ROLE_DELETE)
```

Matcher placement:

- Existing admin-user rules remain at lines 117-125.
- New role rules are at lines 127-139.
- `.anyRequest().authenticated()` remains at line 141.
- No existing matcher was reordered or removed.

Step 5 will verify the full 401/403/2xx matrix and matcher order against the existing admin rules.

### 4. `docs/rbac-admin-step1-result.md` — new

This report.

---

## Verification

Command:

```powershell
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 613, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
```

Maven finished at `2026-11-09T20:35:56+07:00`; total time `01:52 min`.

Notes:

- The test profile uses H2 with `ddl-auto: create-drop` and Flyway disabled.
- No dev profile was started.
- No Flyway migration was executed against any database.
- No git command was run.
- The new role rules are not yet exercised by controller tests; those arrive in Steps 2-4, and the full matrix is Step 5.

---

## Scope guard

Not changed:

- `controller/auth/AdminUserController.java`
- `service/auth/AdminUserService.java`
- `repository/auth/AdminUserQueryRepository.java`
- Any catalog, product, cart, order, payment, or review file.
- Any existing migration.

Custom-role assignment through the admin user API is still pending and will not be started without a separate approval at Step 6.

**Step 1 complete. Waiting for OK before Step 2.**
