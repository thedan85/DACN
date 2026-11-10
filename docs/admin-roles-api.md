# Admin Role API

Base path: `/api/v1/admin`

Authentication: every endpoint requires `Authorization: Bearer <access-token>`.
Requests with a body also require `Content-Type: application/json`.

All responses use the standard envelope:

```json
{
  "code": 200,
  "message": "OK",
  "data": {}
}
```

Error responses use the same envelope with `data: null`; the HTTP status matches `code`.

## Permissions

| Method | Endpoint | Required permission |
|---|---|---|
| `GET` | `/api/v1/admin/permissions` | `role:read` |
| `GET` | `/api/v1/admin/roles` | `role:read` |
| `GET` | `/api/v1/admin/roles/{roleId}` | `role:read` |
| `PATCH` | `/api/v1/admin/roles/{roleId}` | `role:update` |
| `PUT` | `/api/v1/admin/roles/{roleId}/permissions` | `role:update` |
| `POST` | `/api/v1/admin/roles` | `role:create` |
| `DELETE` | `/api/v1/admin/roles/{roleId}` | `role:delete` |

`SecurityConfig` enforces the same authority at the filter-chain level, and every controller method also has `@PreAuthorize`.

## Important behavior for the frontend

- **Use the returned `roleId`.** Never build a `roleId` from a role code. The `roleId` is an opaque business ID returned by `GET /roles`, `GET /roles/{roleId}`, or `POST /roles`. Existing seeded roles may use IDs such as `role-admin`, while roles created through this API use IDs generated from the code.
- **Underscores in a new code become hyphens in the generated `roleId`.** For example, code `AUDITOR_NEW` is stored as `auditor_new` and the API returns `roleId: "ROLE_auditor-new"`. Always use that returned value; do not construct it yourself.
- **Role code is stored lowercase and is immutable.** `POST` trims and lowercases the submitted code. No endpoint can change an existing role's code. The `code` returned by the API is authoritative.
- **The system flag is immutable.** No endpoint can change `system`.
- **`DELETE` returns HTTP `200` with `data: null`.** It is not `204`. Deletion is a soft delete: the role is marked deleted and hidden from `GET /roles` and `GET /roles/{roleId}` (which then returns `404`). Existing permission grants are retained in storage.
- **A soft-deleted role's code cannot be reused.** Duplicate checks are case-insensitive and include soft-deleted roles.
- **Permission changes apply on the next request with the same access token.** The JWT does not contain a stale permission snapshot; permissions are read from the database for each request. A user does not need to log in again.
- **`PUT` replaces the whole permission set.** It is not a partial add. Send every permission the role should keep. An empty array clears a custom role. For the admin role, the safety rule below still applies.
- **The admin role must keep every `user:*` and `role:*` permission.** A `PUT` that would remove any permission whose resource is `user` or `role` returns `409`.
- **System roles cannot be deleted.** Any role with `system: true` returns `409` on delete.
- **A role assigned to any user cannot be deleted.** This includes users whose status is `DELETED`; the check is not limited to active users.
- **Custom roles can be assigned to users through the admin user API.** `PATCH /api/v1/admin/users/{userId}/role` accepts any existing, non-deleted role code except `customer`. Assigned custom roles use the Employee profile, and their permissions apply on the next request with the same access token.

Other conventions:

- Roles are listed by `system DESC, code ASC`, and soft-deleted roles are excluded.
- Role detail permissions are sorted by permission code.
- `description` is nullable.
- Timestamps are ISO-8601 UTC values.
- The examples below were captured from an actual H2 test run. Role IDs, permission IDs, and timestamps are fixture values, not production data. In production, `GET /permissions` returns every permission row, and `GET /roles` returns your environment's roles.

## `GET /api/v1/admin/permissions`

Required permission: `role:read`

Returns every permission sorted by `resource ASC, action ASC, code ASC`. Use each item's `permissionId` in the `PUT /roles/{roleId}/permissions` request. Do not use the permission `code` there.

### Example request

```http
GET /api/v1/admin/permissions
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": [
    {
      "permissionId": "PERM_product-create",
      "code": "product:create",
      "name": "Create Product",
      "resource": "product",
      "action": "create",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.023724Z",
      "updatedAt": "2026-11-09T14:21:33.023724Z"
    },
    {
      "permissionId": "PERM_product-update",
      "code": "product:update",
      "name": "Update Product",
      "resource": "product",
      "action": "update",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.025728Z",
      "updatedAt": "2026-11-09T14:21:33.025728Z"
    },
    {
      "permissionId": "PERM_role-create",
      "code": "role:create",
      "name": "Create Role",
      "resource": "role",
      "action": "create",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.017696Z",
      "updatedAt": "2026-11-09T14:21:33.017696Z"
    },
    {
      "permissionId": "PERM_role-delete",
      "code": "role:delete",
      "name": "Delete Role",
      "resource": "role",
      "action": "delete",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.020723Z",
      "updatedAt": "2026-11-09T14:21:33.020723Z"
    },
    {
      "permissionId": "PERM_role-read",
      "code": "role:read",
      "name": "Read Role",
      "resource": "role",
      "action": "read",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.014696Z",
      "updatedAt": "2026-11-09T14:21:33.014696Z"
    },
    {
      "permissionId": "PERM_role-update",
      "code": "role:update",
      "name": "Update Role",
      "resource": "role",
      "action": "update",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.018696Z",
      "updatedAt": "2026-11-09T14:21:33.018696Z"
    },
    {
      "permissionId": "PERM_user-read",
      "code": "user:read",
      "name": "Read User",
      "resource": "user",
      "action": "read",
      "description": null,
      "createdAt": "2026-11-09T14:21:33.022723Z",
      "updatedAt": "2026-11-09T14:21:33.022723Z"
    }
  ]
}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:read` |
| `500` | Unhandled server error |

## `GET /api/v1/admin/roles`

Required permission: `role:read`

Returns active roles only, sorted by `system DESC, code ASC`. Soft-deleted roles are excluded.

### Example request

```http
GET /api/v1/admin/roles
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": [
    {
      "roleId": "ROLE_admin",
      "code": "admin",
      "name": "Administrator",
      "description": null,
      "system": true,
      "createdAt": "2026-11-09T14:21:32.920958Z",
      "updatedAt": "2026-11-09T14:21:32.920958Z"
    },
    {
      "roleId": "ROLE_customer",
      "code": "customer",
      "name": "Customer",
      "description": null,
      "system": true,
      "createdAt": "2026-11-09T14:21:32.963136Z",
      "updatedAt": "2026-11-09T14:21:32.963136Z"
    },
    {
      "roleId": "ROLE_employee",
      "code": "employee",
      "name": "Employee",
      "description": null,
      "system": true,
      "createdAt": "2026-11-09T14:21:32.961171Z",
      "updatedAt": "2026-11-09T14:21:32.961171Z"
    },
    {
      "roleId": "ROLE_archive",
      "code": "archive",
      "name": "Archive",
      "description": null,
      "system": false,
      "createdAt": "2026-11-09T14:21:33.002694Z",
      "updatedAt": "2026-11-09T14:21:33.002694Z"
    },
    {
      "roleId": "ROLE_support",
      "code": "support",
      "name": "Support",
      "description": "Support role",
      "system": false,
      "createdAt": "2026-11-09T14:21:32.965135Z",
      "updatedAt": "2026-11-09T14:21:32.989221Z"
    }
  ]
}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:read` |
| `500` | Unhandled server error |

## `GET /api/v1/admin/roles/{roleId}`

Required permission: `role:read`

Returns one role and its permissions sorted by permission code. A missing or soft-deleted role returns `404`. Use the `roleId` from the list or create response, not a value derived from `code`.

### Example request

```http
GET /api/v1/admin/roles/ROLE_support
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "roleId": "ROLE_support",
    "code": "support",
    "name": "Support",
    "description": "Support role",
    "system": false,
    "createdAt": "2026-11-09T14:21:32.965135Z",
    "updatedAt": "2026-11-09T14:21:32.989221Z",
    "permissions": [
      {
        "permissionId": "PERM_product-create",
        "code": "product:create",
        "name": "Create Product",
        "resource": "product",
        "action": "create",
        "description": null,
        "createdAt": "2026-11-09T14:21:33.023724Z",
        "updatedAt": "2026-11-09T14:21:33.023724Z"
      },
      {
        "permissionId": "PERM_product-update",
        "code": "product:update",
        "name": "Update Product",
        "resource": "product",
        "action": "update",
        "description": null,
        "createdAt": "2026-11-09T14:21:33.025728Z",
        "updatedAt": "2026-11-09T14:21:33.025728Z"
      }
    ]
  }
}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:read` |
| `404` | Role does not exist or is soft-deleted |
| `500` | Unhandled server error |

Real `404` example:

```json
{
  "code": 404,
  "message": "role not found: ROLE_missing",
  "data": null
}
```

## `PATCH /api/v1/admin/roles/{roleId}`

Required permission: `role:update`

Updates `name` and/or `description` only. At least one field must be present. `code` and `system` cannot be changed.

### Request body

| Field | Required | Rules |
|---|---|---|
| `name` | One of `name` or `description` | Not blank after trimming, maximum 255 characters |
| `description` | One of `name` or `description` | Maximum 2000 characters. A blank string clears it to `null` |

```json
{
  "name": "Support Desk",
  "description": "Handles tickets"
}
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "roleId": "ROLE_support",
    "code": "support",
    "name": "Support Desk",
    "description": "Handles tickets",
    "system": false,
    "createdAt": "2026-11-09T14:21:32.965135Z",
    "updatedAt": "2026-11-09T14:21:34.030611Z",
    "permissions": [
      {
        "permissionId": "PERM_product-create",
        "code": "product:create",
        "name": "Create Product",
        "resource": "product",
        "action": "create",
        "description": null,
        "createdAt": "2026-11-09T14:21:33.023724Z",
        "updatedAt": "2026-11-09T14:21:33.023724Z"
      },
      {
        "permissionId": "PERM_product-update",
        "code": "product:update",
        "name": "Update Product",
        "resource": "product",
        "action": "update",
        "description": null,
        "createdAt": "2026-11-09T14:21:33.025728Z",
        "updatedAt": "2026-11-09T14:21:33.025728Z"
      }
    ]
  }
}
```

The response `code` is unchanged because code is immutable.

### Errors

| Status | Condition |
|---|---|
| `400` | No fields supplied, or `name` is blank after trimming, or a field exceeds its size limit |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:update` |
| `404` | Role does not exist or is soft-deleted |
| `500` | Unhandled server error |

Real `400` examples:

```json
{
  "code": 400,
  "message": "name must not be blank",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "at least one of name or description is required",
  "data": null
}
```

## `PUT /api/v1/admin/roles/{roleId}/permissions`

Required permission: `role:update`

Replaces the role's **entire** permission set with the supplied `permissionId` values. This is not a partial update. Send every permission the role should keep. An empty list is valid for a normal custom role and clears all its permissions.

Permission IDs come from `GET /api/v1/admin/permissions`. Do not send permission codes.

The admin role has an extra rule: it must keep every permission whose resource is `user` or `role`. Removing any of them returns `409`.

Permission changes are visible on the next request made with the same access token; no re-login is required.

### Request body

| Field | Required | Rules |
|---|---|---|
| `permissionIds` | Yes | Non-null array of non-blank permission IDs. Duplicate IDs return `400`. Unknown IDs return `404`. |

```json
{
  "permissionIds": ["PERM_product-update"]
}
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "roleId": "ROLE_support",
    "code": "support",
    "name": "Support Desk",
    "description": "Handles tickets",
    "system": false,
    "createdAt": "2026-11-09T14:21:32.965135Z",
    "updatedAt": "2026-11-09T14:21:34.030611Z",
    "permissions": [
      {
        "permissionId": "PERM_product-update",
        "code": "product:update",
        "name": "Update Product",
        "resource": "product",
        "action": "update",
        "description": null,
        "createdAt": "2026-11-09T14:21:33.025728Z",
        "updatedAt": "2026-11-09T14:21:33.025728Z"
      }
    ]
  }
}
```

The earlier `product:create` grant is gone because `PUT` replaced the whole set. The retained `product:update` grant keeps its original row and `grantedAt`.

### Errors

| Status | Condition |
|---|---|
| `400` | `permissionIds` is null, contains a blank ID, or contains duplicates |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:update` |
| `404` | Role does not exist, or any supplied permission ID does not exist |
| `409` | The admin role would lose a permission whose resource is `user` or `role` |
| `500` | Unhandled server error |

Real `400` example:

```json
{
  "code": 400,
  "message": "duplicate permission ids",
  "data": null
}
```

Real `404` example:

```json
{
  "code": 404,
  "message": "permission not found: PERM_missing",
  "data": null
}
```

## `POST /api/v1/admin/roles`

Required permission: `role:create`

Creates a custom role. The code is trimmed and stored lowercase. New roles always have `system: false` and no permissions.

### Request body

| Field | Required | Rules |
|---|---|---|
| `code` | Yes | Maximum 50 characters; pattern `^[A-Za-z][A-Za-z0-9_]{1,49}$`; must start with a letter and contain only letters, digits, or underscores |
| `name` | Yes | Not blank, maximum 255 characters |
| `description` | No | Maximum 2000 characters; blank becomes `null` |

```json
{
  "code": "AUDITOR_NEW",
  "name": "Auditor New",
  "description": "Temporary role"
}
```

### Real response — `201 Created`

```json
{
  "code": 201,
  "message": "Created",
  "data": {
    "roleId": "ROLE_auditor-new",
    "code": "auditor_new",
    "name": "Auditor New",
    "description": "Temporary role",
    "system": false,
    "createdAt": "2026-11-09T14:21:34.122670Z",
    "updatedAt": "2026-11-09T14:21:34.122670Z",
    "permissions": []
  }
}
```

`AUDITOR_NEW` is stored as `auditor_new`; the generated ID is `ROLE_auditor-new`. Use the returned `roleId` in later calls; do not derive it.

### Errors

| Status | Condition |
|---|---|
| `400` | Bean-validation failure: missing/blank/invalid code, blank name, or a field over its size limit |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:create` |
| `409` | Code already exists, case-insensitively, including the code of a soft-deleted role |
| `500` | Unhandled server error |

Real `400` example:

```json
{
  "code": 400,
  "message": "code: must match \"^[A-Za-z][A-Za-z0-9_]{1,49}$\"",
  "data": null
}
```

## `DELETE /api/v1/admin/roles/{roleId}`

Required permission: `role:delete`

Soft-deletes a custom role by setting its deleted flag. Returns HTTP `200` with `data: null`, not `204`. The role disappears from `GET /roles`, and `GET /roles/{roleId}` returns `404`. Existing permission-grant rows are retained.

System roles and roles assigned to any user (including a `DELETED` user) cannot be deleted.

### Example request

```http
DELETE /api/v1/admin/roles/ROLE_auditor-new
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": null
}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `role:delete` |
| `404` | Role does not exist or is already soft-deleted |
| `409` | Role is a system role, or is assigned to any user including a deleted user |
| `500` | Unhandled server error |

Real `404` example:

```json
{
  "code": 404,
  "message": "role not found: ROLE_missing",
  "data": null
}
```

## 409 rules and message keys

`RoleRuleViolationException` is translated through `MessageSource` and returned as HTTP `409`.

| Message key | English message | Vietnamese message | Rule |
|---|---|---|---|
| `error.role.code.exists` | Role code already exists | Mã vai trò đã tồn tại | `POST /roles` code duplicates an existing role, case-insensitively, including a soft-deleted role. A concurrent insert that trips the unique constraint is mapped to the same message. |
| `error.role.system.delete` | System roles cannot be deleted | Không thể xóa vai trò hệ thống | `DELETE /roles/{roleId}` targets a role with `system: true`. |
| `error.role.in.use` | Role is assigned to a user and cannot be deleted | Vai trò đang được gán cho người dùng và không thể xóa | `DELETE /roles/{roleId}` targets a role referenced by any user, including users with status `DELETED`. |
| `error.role.admin.permissions` | The admin role must keep every user and role permission | Vai trò quản trị phải giữ tất cả quyền của người dùng và vai trò | `PUT /roles/{roleId}/permissions` on the admin role omits any permission whose resource is `user` or `role`. |

Real `409` examples from the test run:

Duplicate code, including a soft-deleted role's code:

```json
{
  "code": 409,
  "message": "Role code already exists",
  "data": null
}
```

System role delete:

```json
{
  "code": 409,
  "message": "System roles cannot be deleted",
  "data": null
}
```

Delete a role assigned to an active user, or to a deleted user (same message):

```json
{
  "code": 409,
  "message": "Role is assigned to a user and cannot be deleted",
  "data": null
}
```

Admin role loses a user/role permission:

```json
{
  "code": 409,
  "message": "The admin role must keep every user and role permission",
  "data": null
}
```

## Error status summary

| Status | Meaning |
|---|---|
| `400` | Validation failure, malformed body, blank PATCH name, PATCH with no fields, duplicate permission IDs |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing the required permission |
| `404` | Role or permission not found; soft-deleted roles also return `404` |
| `409` | Business rule violation listed above |
| `500` | Unhandled server error |

Real generic examples:

```json
{
  "code": 401,
  "message": "Unauthorized",
  "data": null
}
```

```json
{
  "code": 403,
  "message": "Access denied",
  "data": null
}
```

## Custom-role assignment

Custom roles created with `POST /api/v1/admin/roles` can be assigned to users through `PATCH /api/v1/admin/users/{userId}/role`. The admin user API accepts any existing, non-deleted role code except `customer`; unknown or soft-deleted codes return `409`. Custom roles use the Employee profile, and their permission set applies on the next request made with the same access token.
