# Admin Account & RBAC API Contract

Single reference for the frontend team covering the **admin user management** and
**admin role/permission** endpoints that already exist in this backend.

- **Scope:** only the endpoints listed below. Catalog, cart, orders, payments,
  reviews, notifications, shipping, and the rest of `/auth/*` are out of scope.
- **Status:** documentation only. No endpoint, field, query parameter, or
  permission in this document is new or proposed; everything here is taken from
  the current working tree.
- **Not covered:** anything marked "not present" does not exist in the source.

---

## 1. Overview

### Base path and envelope

All paths below are relative to **`/api/v1`**.

Every response (success and error) uses the standard envelope:

```json
{
  "code": 200,
  "message": "OK",
  "data": {}
}
```

- `code` mirrors the HTTP status.
- Errors always have `data: null`.
- JSON bodies use `Content-Type: application/json`.

### Pagination

Only `GET /admin/users` is paginated. It returns a `PageResponse` inside `data`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

- `page` is **zero-based** (`page=0` is the first page).
- `size` defaults to **20**. `spring.data.web.pageable.max-page-size` is **100**;
  a larger requested size is capped at 100 by the server.
- `GET /admin/roles` and `GET /admin/permissions` return plain arrays, not pages.

### Authentication

Every endpoint in this document requires an access token:

```http
Authorization: Bearer <access-token>
```

A missing, malformed, expired, or otherwise invalid token returns `401` with
`message: "Unauthorized"`. A token whose user has been locked or deleted is
also rejected on the next request with `401` (see §5).

### Permissions used by this feature area

The permission codes are:

`user:read`, `user:create`, `user:update`, `user:delete`,
`role:read`, `role:create`, `role:update`, `role:delete`

| Method | Path | Required permission |
|---|---|---|
| `GET` | `/api/v1/admin/users` | `user:read` |
| `GET` | `/api/v1/admin/users/{userId}` | `user:read` |
| `POST` | `/api/v1/admin/users` | `user:create` |
| `PATCH` | `/api/v1/admin/users/{userId}/status` | `user:update` |
| `PATCH` | `/api/v1/admin/users/{userId}/role` | `user:update` |
| `DELETE` | `/api/v1/admin/users/{userId}` | `user:delete` |
| `GET` | `/api/v1/admin/permissions` | `role:read` |
| `GET` | `/api/v1/admin/roles` | `role:read` |
| `GET` | `/api/v1/admin/roles/{roleId}` | `role:read` |
| `PATCH` | `/api/v1/admin/roles/{roleId}` | `role:update` |
| `PUT` | `/api/v1/admin/roles/{roleId}/permissions` | `role:update` |
| `POST` | `/api/v1/admin/roles` | `role:create` |
| `DELETE` | `/api/v1/admin/roles/{roleId}` | `role:delete` |

The same rules are enforced twice: in `SecurityConfig` for the URL and with
`@PreAuthorize` on each controller method.

**Where the frontend gets these codes:** `GET /api/v1/auth/me` returns them in
`data.permissions[]` for the current user:

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "userId": "USR_...",
    "email": "admin@example.com",
    "name": "Admin Actor",
    "phone": "0900000001",
    "address": "Admin HQ",
    "roleCode": "admin",
    "roleName": "Administrator",
    "permissions": ["role:read", "user:create", "user:delete", "user:read", "user:update"],
    "hasPassword": true
  }
}
```

`MeResponse` is the source of that shape; the values above are illustrative
(only `permissions[]` is normative for this document). The access token itself
contains no permission claims; authorities are loaded from the database on every
request.

---

## 2. Admin users (`/api/v1/admin/users`)

### Shared conventions

- `roleCode` in these responses is **lowercase** (`customer`, `employee`,
  `admin`, or a custom role code). The service normalizes it before returning.
- Profile `name`, `phone`, and `address` always come from the user's **current**
  role profile (Admin, Employee, or Customer); stale profiles from a previous
  role are ignored.
- `password` and `passwordHash` are **never returned** by any response.
- Users with `status = DELETED` are hidden from both list and detail.
- Timestamps are ISO-8601 UTC `Instant` values.
- `lastLogin` is nullable.
- `permissions[]` (detail responses only) is a sorted list of permission codes.
- Locking a user revokes that user's refresh tokens; the existing access token
  also stops working on the next request.
- Permission changes take effect on the **next request with the same access
  token**; no re-login is required.

### 2.1 `GET /api/v1/admin/users`

Required permission: **`user:read`**

Lists users. Soft-deleted users are always excluded. If `sort` is omitted, rows
are ordered by `createdAt DESC`, then internal `id ASC`.

#### Query parameters

| Parameter | Type | Required | Rules |
|---|---|---|---|
| `keyword` | string | No | Case-insensitive. Matches `email` or the name on the user's **current-role** profile. `%` and `_` are matched literally. |
| `roleCode` | string | No | Case-insensitive. Unknown role returns an empty page, not an error. |
| `status` | string (`ACTIVE` \| `LOCKED`) | No | `DELETED` returns `400`. Any other enum value returns `400`. |
| `page` | integer | No | Zero-based. Default `0`. |
| `size` | integer | No | Default `20`; server maximum `100`. |
| `sort` | string | No | `field,asc` or `field,desc`. Whitelist below. Any other field returns `400`. |

**Sort whitelist:** `email`, `name`, `roleCode`, `status`, `lastLogin`,
`createdAt`.

No request body.

#### `400` cases

| Condition | Exact message |
|---|---|
| `status=DELETED` | `DELETED is not a valid status filter` |
| `sort` field not in the whitelist | `unsupported sort field: <field>` |
| `status` value is not a `UserStatus` enum member | `invalid value for status` |
| `page` or `size` is non-numeric | `invalid value for page` / `invalid value for size` |

The last two are produced by `MethodArgumentTypeMismatchException` in
`GlobalExceptionHandler`; the first two are thrown by `AdminUserService`.
`page`/`size` type-mismatch messages are handler-derived and are not covered by
this feature's tests.

#### Success response schema

`data` is `PageResponse<AdminUserResponse>`:

| Field | Type | Notes |
|---|---|---|
| `content` | array of user summary | See summary shape below |
| `page` | integer | Zero-based page index |
| `size` | integer | Effective page size |
| `totalElements` | integer | Total matching rows |
| `totalPages` | integer | Total pages |

Each `content[]` item is `AdminUserResponse`:

| Field | Type | Notes |
|---|---|---|
| `userId` | string | Opaque business ID |
| `email` | string | User email |
| `name` | string | Current-role profile name |
| `phone` | string \| null | Current-role profile phone |
| `roleCode` | string | Lowercase role code |
| `roleName` | string | Role display name |
| `status` | string | `ACTIVE` or `LOCKED` |
| `lastLogin` | string \| null | ISO-8601 UTC |
| `createdAt` | string | ISO-8601 UTC |

#### Example response — `200 OK`

Captured from the H2 test run recorded in `docs/admin-users-api.md` (two of its
four rows are shown, with totals adjusted); IDs and timestamps are fixture
values. The shape matches `AdminUserResponse` and is asserted (including
`data.page=0`, `data.size=20`, lowercase `roleCode`) in
`AdminUserControllerTest`.

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
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | `status=DELETED` | `DELETED is not a valid status filter` |
| `400` | Unsupported `sort` field | `unsupported sort field: <field>` |
| `400` | Invalid enum/type value | `invalid value for <param>` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Authenticated but missing `user:read` | `Access denied` |
| `500` | Unhandled server error | `internal server error` |

### 2.2 `GET /api/v1/admin/users/{userId}`

Required permission: **`user:read`**

Returns the full detail, including `address` and the role's `permissions[]`.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `userId` | string | Opaque user business ID. Missing or soft-deleted users return `404`. |

No request body.

#### Success response schema

`data` is `AdminUserDetailResponse`:

| Field | Type | Notes |
|---|---|---|
| `userId` | string | |
| `email` | string | |
| `name` | string \| null | Current-role profile name |
| `phone` | string \| null | Current-role profile phone |
| `address` | string \| null | Current-role profile address |
| `roleCode` | string | Lowercase |
| `roleName` | string | |
| `status` | string | `ACTIVE` or `LOCKED` |
| `lastLogin` | string \| null | |
| `createdAt` | string | |
| `permissions` | string[] | Sorted permission codes for the current role |

#### Example response — `200 OK`

Captured from the H2 test run in `docs/admin-users-api.md`; the field set matches
`AdminUserDetailResponse`. The captured fixture's role had only the four `user:*`
grants; a role with `role:*` grants returns those too.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `user:read` | `Access denied` |
| `404` | User does not exist or is soft-deleted | `user not found: <userId>` |
| `500` | Unhandled server error | `internal server error` |

### 2.3 `POST /api/v1/admin/users`

Required permission: **`user:create`**

Creates a staff user for any existing, non-deleted role **except `customer`**.
The `roleCode` is trimmed and matched case-insensitively; the stored role is
unchanged and responses return it lowercase. `admin` creates an Admin profile;
every other staff role, including a custom role, creates an Employee profile.
The email is trimmed and lowercased before the duplicate check, and the
duplicate check **includes soft-deleted users**. The password is stored as a
BCrypt hash.

Authority rule (see §4): creating an `admin` requires the actor to be an active
`admin`. For any other role, the actor must already hold every permission that
role grants. `user:create` alone is therefore enough to create an employee only
when the employee role grants nothing that the actor lacks.

#### Request body schema

`AdminUserCreateRequest` (all validation messages are defined in the record):

| Field | Type | Required | Validation (exact) |
|---|---|---|---|
| `email` | string | Yes | `@NotBlank` "email is required"; `@Email` "email must be valid"; `@Size(max=255)` "email must not exceed 255 characters" |
| `password` | string | Yes | `@NotBlank` "password is required"; `@Size(min=8,max=128)` "password must be 8-128 characters" |
| `name` | string | Yes | `@NotBlank` "name is required"; `@Size(max=255)` "name must not exceed 255 characters" |
| `phone` | string | Yes | `@NotBlank` "phone is required"; `@Pattern(^\+?[0-9]{10,15}$)` "phone must be 10-15 digits" |
| `address` | string | No | `@Size(max=255)` "address must not exceed 255 characters"; blank becomes `null` |
| `roleCode` | string | Yes | `@NotBlank` "roleCode is required"; service-level rule below |

`roleCode` has no max-length constraint in the DTO (not present).

#### Example request

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

The password above is a test fixture, not a real credential.

#### Success response — `201 Created`

`data` is an `AdminUserDetailResponse` (same schema as §2.2). The example values
below are the ones asserted in `AdminUserMutationControllerTest` and captured in
`docs/admin-users-api.md`; `userId`/`createdAt` are fixtures.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | Bean-validation failure | `field: message` pairs joined by `; `, sorted by field. Example: `email: email is required; name: name is required; password: password must be 8-128 characters; phone: phone must be 10-15 digits; roleCode: roleCode is required` |
| `400` | Malformed JSON | `malformed request body` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `user:create` | `Access denied` |
| `403` | Actor is not an active `admin` and requests `roleCode=admin` | `You don't have permission to assign this role` |
| `403` | Actor does not hold every permission granted to the requested role | `You don't have permission to assign this role` |
| `409` | Duplicate email | `Email already exists` |
| `409` | Unknown/soft-deleted role, or requested `customer` role | `Invalid role; only employee and admin are supported` |
| `409` | Phone blank after trimming at the service layer | `A phone number is required for a staff profile` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. Email duplicates an existing row, **including a soft-deleted row**;
   comparison is case-insensitive (both sides lowercased).
2. `roleCode` is `customer` after trimming.
3. `roleCode` does not match any existing, non-deleted role.
4. `phone` is blank after trimming when the service builds the profile
   (defensive; the DTO already requires a 10–15 digit phone).

### 2.4 `PATCH /api/v1/admin/users/{userId}/status`

Required permission: **`user:update`**

Locks or unlocks a user. The value is trimmed and uppercased, so `active` and
`ACTIVE` behave the same. Only `ACTIVE` and `LOCKED` are accepted.

- Locking revokes **all** refresh tokens for the target.
- Unlocking does not restore revoked refresh tokens; the user must log in again.
- Locking an account whose role is `admin` requires the actor to be an active
  `admin`. Unlocking an admin is not restricted by this rule.
- The existing self-lock and last-active-admin `409` checks run before the
  authority check, so their status and message are unchanged.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `userId` | string | Opaque user business ID. Missing/soft-deleted → `404`. |

#### Request body

`AdminUserStatusRequest`:

| Field | Type | Required | Validation |
|---|---|---|---|
| `status` | string | Yes | `@NotBlank` "status is required"; service accepts only `ACTIVE`/`LOCKED` |

```json
{"status": "LOCKED"}
```

#### Success response — `200 OK`

`data` is an `AdminUserDetailResponse` (schema in §2.2). Captured from the H2
test run in `docs/admin-users-api.md`; `data.status` is asserted in
`AdminUserMutationControllerTest`.

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

Unlock uses `{"status": "ACTIVE"}` and returns the same shape with
`"status": "ACTIVE"`.

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | Missing/blank `status` | `status: status is required` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `user:update` | `Access denied` |
| `403` | Actor is not an active `admin` and locks an `admin` account | `You don't have permission to assign this role` |
| `404` | User does not exist or is soft-deleted | `user not found: <userId>` |
| `409` | Invalid/blank/`DELETED` status at the service layer | `Invalid status; only ACTIVE and LOCKED are supported` |
| `409` | Admin locks their own account | `You cannot lock your own account` |
| `409` | Lock would leave zero active admins | `Cannot modify the last active administrator` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. The requested status is blank or is not `ACTIVE`/`LOCKED` (including
   `DELETED`).
2. The actor locks their own account.
3. The target is an active admin and locking would leave zero active admins.

### 2.5 `PATCH /api/v1/admin/users/{userId}/role`

Required permission: **`user:update`**

Changes the user's role. Accepts any existing, non-deleted role code **except
`customer`**, trimmed and matched case-insensitively.

Behavior details:

- A `customer` target returns `409` and cannot be changed.
- Changing to the **same** role is a no-op that returns the current detail.
- `admin` targets use the Admin profile; every other role, including a custom
  role, uses the Employee profile.
- A missing target profile is created from the current profile's
  name/phone/address. The old profile row is **not** deleted.
- A custom role's permissions are visible on the next request with the same
  access token.
- Moving a user **to or from** `admin` requires the actor to be an active
  `admin`.
- Nobody may change their own role. The one exception is the pre-existing
  self-demote `409`, which runs first and keeps its status and message.
- Assigning any other role requires the actor to already hold every permission
  that role grants.
- The customer-role, self-demote and last-active-admin `409` checks run before
  the authority check.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `userId` | string | Opaque user business ID. Missing/soft-deleted → `404`. |

#### Request body

`AdminUserRoleRequest`:

| Field | Type | Required | Validation |
|---|---|---|---|
| `roleCode` | string | Yes | `@NotBlank` "roleCode is required"; service rule: existing, non-deleted, not `customer` |

```json
{"roleCode": "admin"}
```

#### Success response — `200 OK`

`data` is an `AdminUserDetailResponse` (schema in §2.2). Captured from the H2
test run in `docs/admin-users-api.md`; the role/permission change is asserted in
`AdminUserMutationControllerTest`.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | Missing/blank `roleCode` | `roleCode: roleCode is required` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `user:update` | `Access denied` |
| `403` | Actor is not an active `admin` and changes a role to or from `admin` | `You don't have permission to assign this role` |
| `403` | Actor tries to change their own role | `You don't have permission to assign this role` |
| `403` | Actor does not hold every permission granted to the requested role | `You don't have permission to assign this role` |
| `404` | User does not exist or is soft-deleted | `user not found: <userId>` |
| `409` | Unknown/soft-deleted role, or requested `customer` role | `Invalid role; only employee and admin are supported` |
| `409` | Target user is a customer | `Customer role cannot be changed` |
| `409` | Admin changes their own role to a non-admin role | `You cannot demote your own administrator account` |
| `409` | Demotion would leave zero active admins | `Cannot modify the last active administrator` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. Requested code is `customer`.
2. Requested code does not match an existing, non-deleted role.
3. Target user's current role is `customer`.
4. Target is the authenticated actor and is being demoted from `admin`.
5. Target is the last active administrator being demoted.

Note the check order: a request whose `roleCode` is `customer` returns
`Invalid role...` even when the target is also a customer.

### 2.6 `DELETE /api/v1/admin/users/{userId}`

Required permission: **`user:delete`**

Soft-deletes the user by setting `status = DELETED` and revokes all refresh
tokens. Returns **`200`** with `data: null` — **not `204`**.

After deletion the user is hidden from list and detail, and login returns
`404`. There is no customer-specific delete rule; the only `409` guards are
self-delete and the last-active-admin rule.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `userId` | string | Opaque user business ID. Missing/already deleted → `404`. |

No request body.

#### Success response — `200 OK`

Captured from the H2 test run in `docs/admin-users-api.md`; asserted as
`data: null` in `AdminUserMutationControllerTest`.

```json
{"code":200,"message":"OK","data":null}
```

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `user:delete` | `Access denied` |
| `403` | Actor is not an active `admin` and deletes an `admin` account | `You don't have permission to assign this role` |
| `404` | User does not exist or is already soft-deleted | `user not found: <userId>` |
| `409` | Admin deletes their own account | `You cannot delete your own account` |
| `409` | Delete would leave zero active admins | `Cannot modify the last active administrator` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. The actor deletes their own account.
2. The target is an active admin and deleting would leave zero active admins.

---

## 3. Admin roles and permissions (`/api/v1/admin`)

### Shared conventions and call-outs

- **`roleId` is opaque.** Use the value returned by `GET /roles`,
  `GET /roles/{roleId}`, or `POST /roles`. Never build it from a code.
- Existing seeded roles may use IDs such as `role-admin`, `role-customer`, and
  `role-employee` (migration `V8`). Roles created through this API get an ID
  generated from the code.
- **Underscores in a new code become hyphens in the generated `roleId`.**
  Code `AUDITOR_NEW` is stored as `auditor_new` and the generated ID is
  `ROLE_auditor-new`. Always use the returned `roleId`.
- `POST /roles` trims and lowercases the code. **`code` and `system` are
  immutable**; no endpoint can change them. The API returns the stored `code`,
  which for roles created through this API is lowercase; comparisons are
  case-insensitive.
- `description` is nullable. Blank input clears it to `null`.
- Timestamps are ISO-8601 UTC `Instant` values.
- `GET /roles` and `GET /roles/{roleId}` hide soft-deleted roles.
- `DELETE` is a soft delete and returns `200` with `data: null`, not `204`.
  Existing `role_permissions` rows are retained in storage.
- A soft-deleted role's code cannot be reused; duplicate checks are
  case-insensitive and include soft-deleted roles.
- **`PUT /roles/{roleId}/permissions` replaces the whole set**, it does not
  merge. Send every permission the role should keep.
- Permission changes apply on the **next request with the same access token**;
  no re-login is required. The JWT carries no permission snapshot.
- **Authority rule:** only an active `admin` actor may modify the `admin` role
  or change a `user:*` / `role:*` grant. A non-admin with `role:update` may add
  only permissions it already holds, and only to roles other than `admin`.
  Violations return `403` with `You don't have permission to assign this role`.

### 3.1 `GET /api/v1/admin/permissions`

Required permission: **`role:read`**

Returns every permission row, sorted by `resource ASC, action ASC, code ASC`.
Not paginated and accepts no query parameters (page/size/filter are not
present). There is no request body.

#### Success response schema

`data` is `PermissionResponse[]`:

| Field | Type | Notes |
|---|---|---|
| `permissionId` | string | Opaque ID used by `PUT /roles/{roleId}/permissions` |
| `code` | string | Permission code such as `user:read` |
| `name` | string | Display name |
| `resource` | string | e.g. `user`, `role` |
| `action` | string | e.g. `read`, `create` |
| `description` | string \| null | |
| `createdAt` | string | |
| `updatedAt` | string | |

#### Example response — `200 OK`

Captured from the H2 test run in `docs/admin-roles-api.md`; the capture is
trimmed to seven rows and the sort order is asserted in `AdminRoleControllerTest`.
The endpoint returns every permission row in the environment.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:read` | `Access denied` |
| `500` | Unhandled server error | `internal server error` |

### 3.2 `GET /api/v1/admin/roles`

Required permission: **`role:read`**

Returns active roles only (soft-deleted excluded), sorted by `system DESC,
code ASC` — system roles first, then custom roles, each ordered by code.
Not paginated and accepts no query parameters (not present). There is no
request body.

#### Success response schema

`data` is `AdminRoleSummaryResponse[]`:

| Field | Type | Notes |
|---|---|---|
| `roleId` | string | Opaque; use this for all role endpoints |
| `code` | string | Stored role code |
| `name` | string | Display name |
| `description` | string \| null | |
| `system` | boolean | `true` for built-in roles |
| `createdAt` | string | |
| `updatedAt` | string | |

#### Example response — `200 OK`

Captured from the H2 test run in `docs/admin-roles-api.md`; IDs and timestamps
are fixtures. The `system DESC, code ASC` order and deletion exclusion are
asserted in `AdminRoleControllerTest`.

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

Seeded data in real migrations may use IDs such as `role-admin` instead of the
fixture's `ROLE_admin`. Treat both as opaque strings.

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:read` | `Access denied` |
| `500` | Unhandled server error | `internal server error` |

### 3.3 `GET /api/v1/admin/roles/{roleId}`

Required permission: **`role:read`**

Returns one role with its permissions sorted by permission `code`. A missing or
soft-deleted role returns `404`.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `roleId` | string | Opaque role ID returned by the list/detail/create endpoints. Do not derive it from `code`. |

No request body.

#### Success response schema

`data` is `AdminRoleDetailResponse`: all `AdminRoleSummaryResponse` fields plus

| Field | Type | Notes |
|---|---|---|
| `permissions` | `PermissionResponse[]` | Sorted by permission code; full objects as in §3.1 |

#### Example response — `200 OK`

Captured from the H2 test run in `docs/admin-roles-api.md`; the permission sort
order is asserted in `AdminRoleControllerTest`.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:read` | `Access denied` |
| `404` | Role does not exist or is soft-deleted | `role not found: <roleId>` |
| `500` | Unhandled server error | `internal server error` |

### 3.4 `PATCH /api/v1/admin/roles/{roleId}`

Required permission: **`role:update`**

Updates `name` and/or `description` only. At least one field must be present.
`code` and `system` cannot be changed by any endpoint.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `roleId` | string | Opaque. Missing/soft-deleted → `404`. |

#### Request body

`AdminRoleUpdateRequest`:

| Field | Type | Required | Rules |
|---|---|---|---|
| `name` | string | One of `name` or `description` | `@Size(max=255)`; not blank after trimming |
| `description` | string | One of `name` or `description` | `@Size(max=2000)`; blank string clears it to `null` |

```json
{
  "name": "Support Desk",
  "description": "Handles tickets"
}
```

#### Success response — `200 OK`

`data` is an `AdminRoleDetailResponse` (§3.3). Captured from the H2 test run in
`docs/admin-roles-api.md`; `code` remaining unchanged is asserted in
`AdminRoleMutationControllerTest`.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | Neither field supplied | `at least one of name or description is required` |
| `400` | `name` is blank after trimming | `name must not be blank` |
| `400` | Bean-validation size failure | `field: <Bean Validation default message>` (defaults are not overridden in the DTO; exact text not verified) |
| `400` | Malformed JSON | `malformed request body` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:update` | `Access denied` |
| `403` | Actor is not an active `admin` and targets the `admin` role | `You don't have permission to assign this role` |
| `404` | Role does not exist or is soft-deleted | `role not found: <roleId>` |
| `500` | Unhandled server error | `internal server error` |

Renaming or describing the `admin` role is admin-only; every other role can
still be edited by any actor holding `role:update`.

### 3.5 `PUT /api/v1/admin/roles/{roleId}/permissions`

Required permission: **`role:update`**

**Replaces** the role's entire permission set with the supplied
`permissionId` values. It is not a partial add. Send every permission the role
should keep; an empty array is valid for a custom role and clears all grants.

- IDs come from **`GET /api/v1/admin/permissions`** (`permissionId`), never
  permission codes.
- The **admin role** must keep every permission whose `resource` is `user` or
  `role`; omitting any of them returns `409`. This guard applies only to the
  system `admin` role.
- Retained grants keep their original row and `grantedAt`; removed grants are
  deleted; new grants are inserted.
- Changes apply on the next request with the same access token.
- **Authority rule:** replacing grants on the `admin` role is admin-only.
  Granting or revoking any permission whose `resource` is `user` or `role` is
  also admin-only. A non-admin holding `role:update` may add only permissions it
  already holds, and only ordinary permissions (for example `product:create`).
  Revoking an ordinary permission does not require holding it.
- The admin-role must-keep `409` check runs before the authority check for an
  admin actor; a non-admin targeting the admin role receives `403` first.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `roleId` | string | Opaque. Missing/soft-deleted → `404`. |

#### Request body

`AdminRolePermissionsUpdateRequest`:

| Field | Type | Required | Rules |
|---|---|---|---|
| `permissionIds` | string[] | Yes | `@NotNull`; each element `@NotBlank`; duplicates rejected with `400`; every ID must exist or the request returns `404` |

```json
{"permissionIds": ["PERM_product-update"]}
```

#### Success response — `200 OK`

`data` is an `AdminRoleDetailResponse` (§3.3). Captured from the H2 test run in
`docs/admin-roles-api.md`; replacement behavior is asserted in
`AdminRoleMutationControllerTest`.

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

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | `permissionIds` null or containing a blank ID | `permission ids must not be blank` |
| `400` | Duplicate IDs | `duplicate permission ids` |
| `400` | Bean-validation failure (`null` list / blank element) | `permissionIds: <message>` (Bean Validation default, not verified) |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:update` | `Access denied` |
| `403` | Actor is not an active `admin` and targets the `admin` role | `You don't have permission to assign this role` |
| `403` | Actor is not an active `admin` and grants or revokes a `user:*` / `role:*` permission | `You don't have permission to assign this role` |
| `403` | Actor is not an active `admin` and grants a permission it does not hold | `You don't have permission to assign this role` |
| `404` | Role does not exist, or a supplied permission ID does not exist | `role not found: <roleId>` / `permission not found: <missingId>` |
| `409` | Admin role would lose a `user:*` or `role:*` permission | `The admin role must keep every user and role permission` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rule

The only `409` on this endpoint is the admin-role safety rule. It fires when the
target role is the system `admin` role and the submitted set omits any existing
permission with resource `user` or `role` (including `user:read`,
`user:create`, `user:update`, `user:delete`, `role:read`, `role:create`,
`role:update`, `role:delete`).

### 3.6 `POST /api/v1/admin/roles`

Required permission: **`role:create`**

Creates a custom role. The code is trimmed and stored lowercase. New roles
always have `system: false` and no permissions.

#### Request body

`AdminRoleCreateRequest`:

| Field | Type | Required | Rules |
|---|---|---|---|
| `code` | string | Yes | `@NotBlank`; `@Size(max=50)`; `@Pattern(^[A-Za-z][A-Za-z0-9_]{1,49}$)` — starts with a letter, then letters/digits/underscores, 2–50 chars |
| `name` | string | Yes | `@NotBlank`; `@Size(max=255)` |
| `description` | string | No | `@Size(max=2000)`; blank becomes `null` |

```json
{
  "code": "SUPPORT_DESK",
  "name": "Support Desk",
  "description": "Handles tickets"
}
```

#### Success response — `201 Created`

`data` is an `AdminRoleDetailResponse` (§3.3). `roleId`, `code`, `name`,
`description`, `system`, and the empty permission list are asserted in
`AdminRoleLifecycleControllerTest`; timestamps are illustrative.

```json
{
  "code": 201,
  "message": "Created",
  "data": {
    "roleId": "ROLE_support-desk",
    "code": "support_desk",
    "name": "Support Desk",
    "description": "Handles tickets",
    "system": false,
    "createdAt": "2026-11-09T14:21:34.122670Z",
    "updatedAt": "2026-11-09T14:21:34.122670Z",
    "permissions": []
  }
}
```

`SUPPORT_DESK` is stored as `support_desk`; the generated `roleId` is
`ROLE_support-desk`. Always use the returned `roleId`.

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `400` | Missing/blank/invalid `code`, blank `name`, or size limit exceeded | `code: must match "^[A-Za-z][A-Za-z0-9_]{1,49}$"` (captured) or `field: <Bean Validation default>` for other constraints |
| `400` | Malformed JSON | `malformed request body` |
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:create` | `Access denied` |
| `409` | Code already exists, case-insensitively, including a soft-deleted role's code | `Role code already exists` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. The submitted code duplicates an existing role, case-insensitively.
2. The duplicate check includes the code of a soft-deleted role.
3. A concurrent insert that trips the unique constraint maps to the same
   message.

### 3.7 `DELETE /api/v1/admin/roles/{roleId}`

Required permission: **`role:delete`**

Soft-deletes a custom role by setting its deleted flag. Returns **`200`** with
`data: null` — not `204`. The role disappears from `GET /roles`, and
`GET /roles/{roleId}` then returns `404`. Existing permission grants are
retained in storage.

#### Path parameter

| Parameter | Type | Rules |
|---|---|---|
| `roleId` | string | Opaque. Missing/already deleted → `404`. |

No request body.

#### Success response — `200 OK`

Captured from the H2 test run in `docs/admin-roles-api.md`; `data: null` and the
hidden-after-delete behavior are asserted in `AdminRoleLifecycleControllerTest`.

```json
{"code":200,"message":"OK","data":null}
```

#### Errors

| Status | Condition | Exact message |
|---|---|---|
| `401` | Missing/invalid/expired token, or locked/deleted principal | `Unauthorized` |
| `403` | Missing `role:delete` | `Access denied` |
| `404` | Role does not exist or is already soft-deleted | `role not found: <roleId>` |
| `409` | Role is a system role (`system: true`) | `System roles cannot be deleted` |
| `409` | Role is assigned to any user, including a `DELETED` user | `Role is assigned to a user and cannot be deleted` |
| `500` | Unhandled server error | `internal server error` |

#### `409` rules

1. The role is a system role (`system: true`).
2. The role is assigned to any user, including users whose status is `DELETED`.

---

## 4. Shared error contract

### Status meanings

| Status | Meaning |
|---|---|
| `400` | Bean-validation failure, malformed JSON, invalid enum/type value, unsupported sort field, `status=DELETED` on the user list, blank PATCH name, PATCH with no fields, or duplicate/blank permission IDs |
| `401` | Token missing, malformed, expired, or invalid; or the token's principal is locked/deleted and is therefore not authenticated |
| `403` | Authenticated but missing the required permission, or the actor's authority is below the change requested (see the message-key table); also used for a locked-account login, which is not part of this feature area |
| `404` | User, role, or permission not found; soft-deleted users and roles also return `404` |
| `409` | Business rule violation (listed below), or a database constraint violation that escapes the duplicate checks |
| `500` | Unhandled server error |

Error bodies always keep the envelope shape, for example:

```json
{"code":401,"message":"Unauthorized","data":null}
```

```json
{"code":403,"message":"Access denied","data":null}
```

```json
{"code":500,"message":"internal server error","data":null}
```

`401` and `403` messages are resolved through `MessageSource` as well. The
English bundle does not define `error.unauthorized` or `error.forbidden`, so an
English client sees the handler defaults shown above; `messages_vi.properties`
contains Vietnamese translations for those keys.

### `409` message keys

These are resolved through `MessageSource` from
`src/main/resources/messages/messages.properties` and returned with HTTP `409`.
The text below is the English bundle text verbatim; if the request selects the
Vietnamese bundle (`messages_vi.properties`), the translated text is returned
instead.

| Message key | English message (verbatim) | Triggering rule | Endpoints |
|---|---|---|---|
| `error.email.exists` | `Email already exists` | Create email duplicates an existing row, case-insensitively, including a soft-deleted row | `POST /admin/users` |
| `error.user.role.invalid` | `Invalid role; only employee and admin are supported` | Create/PATCH requests an unknown or soft-deleted role, or requests the `customer` role | `POST /admin/users`, `PATCH /admin/users/{userId}/role` |
| `error.user.customer.role` | `Customer role cannot be changed` | PATCH role targets a user whose current role is `customer` | `PATCH /admin/users/{userId}/role` |
| `error.user.status.invalid` | `Invalid status; only ACTIVE and LOCKED are supported` | PATCH status value is blank or not `ACTIVE`/`LOCKED` (including `DELETED`) | `PATCH /admin/users/{userId}/status` |
| `error.user.self.lock` | `You cannot lock your own account` | Admin locks their own account | `PATCH /admin/users/{userId}/status` |
| `error.user.self.demote` | `You cannot demote your own administrator account` | Admin changes their own role from `admin` to any non-admin role | `PATCH /admin/users/{userId}/role` |
| `error.user.self.delete` | `You cannot delete your own account` | Admin deletes their own account | `DELETE /admin/users/{userId}` |
| `error.user.last.admin` | `Cannot modify the last active administrator` | Lock, demotion, or delete would leave zero active administrators | `PATCH /admin/users/{userId}/status`, `PATCH /admin/users/{userId}/role`, `DELETE /admin/users/{userId}` |
| `error.user.profile.phone` | `A phone number is required for a staff profile` | Service-level defensive check when the staff phone is blank after trimming | `POST /admin/users` |
| `error.role.code.exists` | `Role code already exists` | `POST /roles` code duplicates an existing role, case-insensitively, including a soft-deleted role; a concurrent insert tripping the unique constraint maps to the same message | `POST /admin/roles` |
| `error.role.system.delete` | `System roles cannot be deleted` | Delete targets a role with `system: true` | `DELETE /admin/roles/{roleId}` |
| `error.role.in.use` | `Role is assigned to a user and cannot be deleted` | Delete targets a role referenced by any user, including a `DELETED` user | `DELETE /admin/roles/{roleId}` |
| `error.role.admin.permissions` | `The admin role must keep every user and role permission` | `PUT /permissions` on the admin role omits any permission whose resource is `user` or `role` | `PUT /admin/roles/{roleId}/permissions` |

### `403` authority message key

Returned when the actor has the endpoint permission but not the authority to
hand out the privilege involved. It is resolved through `MessageSource` like the
other business messages.

| Message key | English message (verbatim) | Triggering rule | Endpoints |
|---|---|---|---|
| `error.admin.authority` | `You don't have permission to assign this role` | Non-admin creates an admin, moves a user to/from admin, changes their own role, locks/deletes/demotes an admin, modifies the admin role, changes a `user:*`/`role:*` grant, or grants a permission it does not hold | `POST /admin/users`, `PATCH /admin/users/{userId}/status`, `PATCH /admin/users/{userId}/role`, `DELETE /admin/users/{userId}`, `PATCH /admin/roles/{roleId}`, `PUT /admin/roles/{roleId}/permissions` |

The pre-existing `403 Access denied` still applies when the actor lacks the
endpoint permission itself. The two layers are independent: `user:update` gets
an actor through the controller, but it does not let that actor promote anyone
to `admin`.

Fallback `409` without a message key (hardcoded in `GlobalExceptionHandler`):

| Status | Message (verbatim) | Trigger |
|---|---|---|
| `409` | `resource already exists or is still referenced` | A `DataIntegrityViolationException` that is not caught and re-mapped by the service (defensive fallback) |

---

## 5. Frontend integration notes

- Attach the access token as `Authorization: Bearer <access-token>` on every
  request in this area; bodies use `Content-Type: application/json`.
- Read the permission codes from **`GET /api/v1/auth/me` → `data.permissions[]`**.
  Gate admin screens/buttons on `user:*` and `role:*` from that list; the token
  contains no permission claims.
- The user list `page` is **zero-based**. If the UI shows page 1, send `page=0`;
  `size` defaults to 20 and is capped at 100.
- A locked user's existing access token is rejected on its **next request**
  with `401`; the frontend should treat that as "session no longer valid" and
  route to login. Unlocking does not restore old refresh tokens.
- Permission changes take effect on the **next request with the same access
  token**; no re-login and no token refresh are required.
- `DELETE` returns **`200`** with `"data": null`, not `204`. Do not treat a
  successful delete as an empty response.
- `roleCode` on admin user responses is **lowercase**; `roleId` is **opaque**.
  Never build a `roleId` from a code, and never assume a prefix.
- `PUT /admin/roles/{roleId}/permissions` **replaces** the set; always send the
  full `permissionIds` array, using IDs from `GET /admin/permissions`, not codes.
- A `403` whose message is **`You don't have permission to assign this role`**
  means the endpoint permission was present but the actor's role/authority is
  too low for this specific change. Do not retry it; hide admin-only role
  choices and `user:*` / `role:*` permission grants for non-admin actors.
- The role picker should only offer roles whose permissions are a subset of the
  actor's own `permissions[]` when the actor is not an `admin`.
- The roles list and permissions list are plain arrays and are not paginated.

---

## Example provenance

| Endpoint | Example source |
|---|---|
| `GET /admin/users` | Captured H2 test run (`docs/admin-users-api.md`); shape matches `AdminUserResponse`; page/size/lowercase asserted in `AdminUserControllerTest` |
| `GET /admin/users/{userId}` | Captured H2 test run (`docs/admin-users-api.md`); shape matches `AdminUserDetailResponse` |
| `POST /admin/users` | Shape from `AdminUserDetailResponse`; field values asserted in `AdminUserMutationControllerTest` and captured in `docs/admin-users-api.md`; IDs/timestamps illustrative |
| `PATCH /admin/users/{userId}/status` | Captured H2 test run (`docs/admin-users-api.md`); `status` asserted in `AdminUserMutationControllerTest` |
| `PATCH /admin/users/{userId}/role` | Captured H2 test run (`docs/admin-users-api.md`); role/permission result asserted in `AdminUserMutationControllerTest` |
| `DELETE /admin/users/{userId}` | Captured H2 test run (`docs/admin-users-api.md`); `data: null` asserted in tests |
| `GET /admin/permissions` | Captured H2 test run (`docs/admin-roles-api.md`), trimmed to seven rows; order asserted in `AdminRoleControllerTest` |
| `GET /admin/roles` | Captured H2 test run (`docs/admin-roles-api.md`); order/exclusion asserted in `AdminRoleControllerTest`; seeded IDs may differ |
| `GET /admin/roles/{roleId}` | Captured H2 test run (`docs/admin-roles-api.md`); permission order asserted in `AdminRoleControllerTest` |
| `PATCH /admin/roles/{roleId}` | Captured H2 test run (`docs/admin-roles-api.md`); immutability of `code` asserted in `AdminRoleMutationControllerTest` |
| `PUT /admin/roles/{roleId}/permissions` | Captured H2 test run (`docs/admin-roles-api.md`); replacement behavior asserted in `AdminRoleMutationControllerTest` |
| `POST /admin/roles` | Field values (`ROLE_support-desk`, `support_desk`, `system=false`, empty permissions) asserted in `AdminRoleLifecycleControllerTest`; timestamps illustrative |
| `DELETE /admin/roles/{roleId}` | Captured H2 test run (`docs/admin-roles-api.md`); `data: null` and hiding asserted in `AdminRoleLifecycleControllerTest` |

**Source of truth used:** `AdminUserController`, `AdminRoleController`,
`AdminUserCreateRequest`, `AdminUserRoleRequest`, `AdminUserStatusRequest`,
`AdminRoleCreateRequest`, `AdminRoleUpdateRequest`,
`AdminRolePermissionsUpdateRequest`, `AdminUserResponse`,
`AdminUserDetailResponse`, `AdminRoleSummaryResponse`,
`AdminRoleDetailResponse`, `PermissionResponse`, `MeResponse`, `ApiResponse`,
`PageResponse`, `UserStatus`, `Permissions`, `SecurityConfig`,
`JwtAuthenticationFilter`, `AuthenticationEntryPoint`,
`CustomAccessDeniedHandler`, `GlobalExceptionHandler`, `AdminUserService`,
`AdminRoleService`, `AdminAuthorityPolicy`, `AdminAuthorityException`,
`AdminUserQueryRepository`, `BusinessId`, `Role`,
`messages.properties`, `messages_vi.properties`, `V8__seed_cart_demo.sql`,
`V29__admin_user_management.sql`, `V30__rbac_role_permissions.sql`, and the
admin user/role controller tests.
