# Admin User API

Base path: `/api/v1/admin/users`

Authentication: every endpoint requires `Authorization: Bearer <access-token>`.
Requests with a JSON body also require `Content-Type: application/json`.

All responses use the standard envelope:

```json
{
  "code": 200,
  "message": "OK",
  "data": {}
}
```

Error responses use the same envelope with `data: null` and an HTTP status that matches `code`.

## Permissions

| Endpoint | Method | Required permission |
|---|---|---|
| `/api/v1/admin/users` | `GET` | `user:read` |
| `/api/v1/admin/users/{userId}` | `GET` | `user:read` |
| `/api/v1/admin/users` | `POST` | `user:create` |
| `/api/v1/admin/users/{userId}/status` | `PATCH` | `user:update` |
| `/api/v1/admin/users/{userId}/role` | `PATCH` | `user:update` |
| `/api/v1/admin/users/{userId}` | `DELETE` | `user:delete` |

`SecurityConfig` applies the same rules at the filter-chain level, and each controller method also has `@PreAuthorize`.

## Conventions

- `roleCode` is returned in lowercase: `customer`, `employee`, or `admin`.
- `status` is returned as `ACTIVE` or `LOCKED`. Soft-deleted users are never returned by these endpoints.
- `page` is zero-based.
- `lastLogin` is nullable.
- Timestamps are ISO-8601 UTC values.
- `permissions` is a sorted list of permission codes.
- Passwords and password hashes are never returned.
- The response examples below were captured from the H2 test run. User IDs, timestamps, and session data are test values, not production data.
- The example request password is a test-only fixture and is not a real credential.

## `GET /api/v1/admin/users`

Required permission: `user:read`

### Query parameters

| Parameter | Required | Rules |
|---|---|---|
| `keyword` | No | Case-insensitive. Matches email or the name on the user's **current-role** profile. `%` and `_` are treated literally. |
| `roleCode` | No | Case-insensitive. An unknown role returns an empty page, not an error. |
| `status` | No | `ACTIVE` or `LOCKED`. `DELETED` returns `400`. |
| `page` | No | Zero-based. Default `0`. |
| `size` | No | Default `20`. |
| `sort` | No | Whitelist: `email`, `name`, `roleCode`, `status`, `lastLogin`, `createdAt`. Format: `field,asc` or `field,desc`. Any other field returns `400`. |

Soft-deleted users are always excluded. The list read path is one count query plus one page query.

### Example request

```http
GET /api/v1/admin/users?page=0&size=20
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "content": [
      {
        "userId": "USR_customer-test-com-5b27f0c7-078",
        "email": "customer@test.com",
        "name": "Customer User",
        "phone": "0900000004",
        "roleCode": "customer",
        "roleName": "Customer",
        "status": "ACTIVE",
        "lastLogin": "2026-11-09T09:08:59.710636Z",
        "createdAt": "2026-11-09T09:08:59.051124Z"
      },
      {
        "userId": "USR_employee-test-com-fa9a534f-28a",
        "email": "employee@test.com",
        "name": "Employee User",
        "phone": "0900000003",
        "roleCode": "employee",
        "roleName": "Employee",
        "status": "ACTIVE",
        "lastLogin": null,
        "createdAt": "2026-11-09T09:08:58.991413Z"
      },
      {
        "userId": "USR_second-admin-test-com-6245ed64-546",
        "email": "second-admin@test.com",
        "name": "Second Admin",
        "phone": "0900000002",
        "roleCode": "admin",
        "roleName": "Administrator",
        "status": "ACTIVE",
        "lastLogin": null,
        "createdAt": "2026-11-09T09:08:58.932861Z"
      },
      {
        "userId": "USR_admin-test-com-5b37040e-620",
        "email": "admin@test.com",
        "name": "Admin Actor",
        "phone": "0900000001",
        "roleCode": "admin",
        "roleName": "Administrator",
        "status": "ACTIVE",
        "lastLogin": "2026-11-09T09:08:59.438520Z",
        "createdAt": "2026-11-09T09:08:58.870714Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 4,
    "totalPages": 1
  }
}
```

### Errors

| Status | Condition |
|---|---|
| `400` | `status=DELETED`, an unsupported `sort` field, an invalid enum/type value, or an invalid pageable parameter |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:read` |

Real `400` examples:

```json
{"code":400,"message":"DELETED is not a valid status filter","data":null}
```

```json
{"code":400,"message":"unsupported sort field: passwordHash","data":null}
```

## `GET /api/v1/admin/users/{userId}`

Required permission: `user:read`

Returns the user summary, current-role profile name/phone/address, role, status, and sorted permissions. A missing or soft-deleted user returns `404`.

### Example request

```http
GET /api/v1/admin/users/USR_admin-test-com-5b37040e-620
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": "USR_admin-test-com-5b37040e-620",
    "email": "admin@test.com",
    "name": "Admin Actor",
    "phone": "0900000001",
    "address": "Admin HQ",
    "roleCode": "admin",
    "roleName": "Administrator",
    "status": "ACTIVE",
    "lastLogin": "2026-11-09T09:08:59.438519900Z",
    "createdAt": "2026-11-09T09:08:58.870714Z",
    "permissions": ["user:create", "user:delete", "user:read", "user:update"]
  }
}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:read` |
| `404` | User does not exist or is soft-deleted |

Real `404` example:

```json
{"code":404,"message":"user not found: USR_deleted-test-com-2d9f396e-4f9","data":null}
```

## `POST /api/v1/admin/users`

Required permission: `user:create`

Creates only an `employee` or `admin`. The email is trimmed and lowercased before the duplicate check. The duplicate check includes soft-deleted users. The matching employee or admin profile is created in the same transaction, and the password is stored as a BCrypt hash.

### Request body

| Field | Required | Rules |
|---|---|---|
| `email` | Yes | Valid email, maximum 255 characters |
| `password` | Yes | 8–128 characters |
| `name` | Yes | Not blank, maximum 255 characters |
| `phone` | Yes | 10–15 digits, optional leading `+` |
| `address` | No | Maximum 255 characters |
| `roleCode` | Yes | `employee` or `admin` only |

```json
{
  "email": "new-employee@test.com",
  "password": "newpassword123",
  "name": "New Staff",
  "phone": "0912345678",
  "address": "New Address",
  "roleCode": "employee"
}
```

The password above is a test-only fixture, not a real credential.

### Real response — `201 Created`

```json
{
  "code": 201,
  "message": "Created",
  "data": {
    "userId": "USR_new-employee-test-com-b1aeaedd-3b5",
    "email": "new-employee@test.com",
    "name": "New Staff",
    "phone": "0912345678",
    "address": "New Address",
    "roleCode": "employee",
    "roleName": "Employee",
    "status": "ACTIVE",
    "lastLogin": null,
    "createdAt": "2026-11-09T09:08:59.990117Z",
    "permissions": []
  }
}
```

### Errors

| Status | Condition |
|---|---|
| `400` | Bean-validation failure, including a blank/invalid field |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:create` |
| `409` | Duplicate email, invalid role, or missing staff phone at the service layer |

Real `400` validation example:

```json
{"code":400,"message":"email: email is required; name: name is required; password: password must be 8-128 characters; phone: phone must be 10-15 digits; roleCode: roleCode is required","data":null}
```

Real `409` duplicate-email example (also used for a soft-deleted email):

```json
{"code":409,"message":"Email already exists","data":null}
```

Real `409` invalid-role example:

```json
{"code":409,"message":"Invalid role; only employee and admin are supported","data":null}
```

## `PATCH /api/v1/admin/users/{userId}/status`

Required permission: `user:update`

Accepts only `ACTIVE` or `LOCKED`. The value is trimmed and case-insensitive. Locking revokes all refresh tokens for the target. Unlocking does **not** restore revoked refresh tokens; the user must log in again.

### Request body

```json
{"status": "LOCKED"}
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": "USR_employee-test-com-fa9a534f-28a",
    "email": "employee@test.com",
    "name": "Employee User",
    "phone": "0900000003",
    "address": "Employee Desk",
    "roleCode": "employee",
    "roleName": "Employee",
    "status": "LOCKED",
    "lastLogin": null,
    "createdAt": "2026-11-09T09:08:58.991413Z",
    "permissions": []
  }
}
```

Unlock uses `{"status": "ACTIVE"}` and returns the same shape with `"status": "ACTIVE"`.

### Errors

| Status | Condition |
|---|---|
| `400` | Missing/blank `status` |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:update` |
| `404` | User does not exist or is soft-deleted |
| `409` | Invalid status, self-lock, or last-active-admin rule |

Real `409` invalid-status example:

```json
{"code":409,"message":"Invalid status; only ACTIVE and LOCKED are supported","data":null}
```

Real `409` self-lock example:

```json
{"code":409,"message":"You cannot lock your own account","data":null}
```

## `PATCH /api/v1/admin/users/{userId}/role`

Required permission: `user:update`

Accepts only `employee` or `admin`. The value is trimmed and case-insensitive. A customer target cannot be changed. Changing to the same role is a no-op that returns the current detail. Changing role creates the missing target profile from the current profile's name/phone/address; the old profile is not deleted.

### Request body

```json
{"roleCode": "admin"}
```

### Real response — `200 OK`

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": "USR_employee-test-com-fa9a534f-28a",
    "email": "employee@test.com",
    "name": "Employee User",
    "phone": "0900000003",
    "address": "Employee Desk",
    "roleCode": "admin",
    "roleName": "Administrator",
    "status": "ACTIVE",
    "lastLogin": null,
    "createdAt": "2026-11-09T09:08:58.991413Z",
    "permissions": ["user:create", "user:delete", "user:read", "user:update"]
  }
}
```

### Errors

| Status | Condition |
|---|---|
| `400` | Missing/blank `roleCode` |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:update` |
| `404` | User does not exist or is soft-deleted |
| `409` | Invalid role, customer target, self-demote, or last-active-admin rule |

Real `409` invalid-role example:

```json
{"code":409,"message":"Invalid role; only employee and admin are supported","data":null}
```

Real `409` customer-target example:

```json
{"code":409,"message":"Customer role cannot be changed","data":null}
```

Real `409` self-demote example:

```json
{"code":409,"message":"You cannot demote your own administrator account","data":null}
```

## `DELETE /api/v1/admin/users/{userId}`

Required permission: `user:delete`

Soft-deletes the user by setting `status = DELETED`, revokes all refresh tokens, and returns **`200`** with `data: null`. It does not return `204`. A soft-deleted user is hidden from the list and detail endpoints, and login returns `404`.

### Example request

```http
DELETE /api/v1/admin/users/USR_new-employee-test-com-b1aeaedd-3b5
Authorization: Bearer <access-token>
```

### Real response — `200 OK`

```json
{"code":200,"message":"OK","data":null}
```

### Errors

| Status | Condition |
|---|---|
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing `user:delete` |
| `404` | User does not exist or is already soft-deleted |
| `409` | Self-delete or last-active-admin rule |

Real `409` self-delete example:

```json
{"code":409,"message":"You cannot delete your own account","data":null}
```

## 409 rules and message keys

`UserRuleViolationException` is translated through `MessageSource` and returned as HTTP `409`.

| Message key | English message | Rule |
|---|---|---|
| `error.email.exists` | Email already exists | Create email duplicates an existing row, including a soft-deleted row; comparison is case-insensitive |
| `error.user.role.invalid` | Invalid role; only employee and admin are supported | Create role or PATCH role is not `employee`/`admin` |
| `error.user.customer.role` | Customer role cannot be changed | PATCH role targets a customer |
| `error.user.status.invalid` | Invalid status; only ACTIVE and LOCKED are supported | PATCH status is invalid, blank at the service layer, or `DELETED` |
| `error.user.self.lock` | You cannot lock your own account | Admin locks their own account |
| `error.user.self.demote` | You cannot demote your own administrator account | Admin changes their own role to employee |
| `error.user.self.delete` | You cannot delete your own account | Admin deletes their own account |
| `error.user.last.admin` | Cannot modify the last active administrator | Lock, demote, or delete would leave zero active admins |
| `error.user.profile.phone` | A phone number is required for a staff profile | Service-level defensive check when the phone is blank after trimming |

Vietnamese translations exist in `messages_vi.properties`.

Real `409` last-admin example (same body for lock, demote, and delete):

```json
{"code":409,"message":"Cannot modify the last active administrator","data":null}
```

## Error status summary

| Status | Meaning |
|---|---|
| `400` | Validation failure, invalid enum/type, `status=DELETED`, unsupported sort, malformed body |
| `401` | Missing, invalid, expired, or locked token |
| `403` | Authenticated but missing the required permission |
| `404` | User not found or soft-deleted |
| `409` | Business rule violation listed above |
| `500` | Unhandled server error |

Real `401` example:

```json
{"code":401,"message":"Unauthorized","data":null}
```

Real `403` example:

```json
{"code":403,"message":"Access denied","data":null}
```
