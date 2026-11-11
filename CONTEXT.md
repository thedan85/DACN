# DACN — Project Context

> Onboarding/context document for the DACN backend repository.
> Verified against source and local docs on **2026-11-10**. The latest test
> status comes from a fresh `.\mvnw.cmd clean test` run: **659 tests, 0 failures,
> 0 errors, 10 skipped**. The user confirmed that V29 is applied on the shared
> Supabase database; V30 is new in this repository and has not been confirmed
> applied there.

---

## 1. What this repository is

`DACN` is the **backend** of an enterprise-style, headless e-commerce platform
for an aquarium/fish store. It covers:

- Product Information Management (PIM): category tree, products, variants,
  attributes, media.
- Storefront catalog: search, filtering, sorting, product detail with reviews.
- Accounts and RBAC: JWT access/refresh tokens, Google sign-in, roles and
  permissions, customer/staff/admin profiles, admin user management
  (list/detail/create/lock-unlock/change-role/soft-delete), and admin
  role/permission management.
- Cart and checkout with stock validation, concurrency protection, and a
  fish-compatibility advisory on the cart.
- Orders: customer history, staff accept/reject, admin completion.
- Online payments: VNPay (implemented) and MoMo IPN settlement (implemented);
  MoMo payment creation and VietQR are not implemented.
- Cloudinary uploads for product/variant images.
- Redis-backed catalog cache and auth rate limiting.

The intended frontend is a **Next.js** storefront, but it is not part of this
repository (`/FE-DACN` is gitignored).

---

## 2. Snapshot / status

| Area | State |
|---|---|
| Build system | Maven Wrapper (`mvnw`, `mvnw.cmd`), Spring Boot parent `4.1.1`, Java `21` |
| Main app class | `src/main/java/com/project/dacn/DacnApplication.java` |
| Server | `127.0.0.1:8080` (intentionally loopback-only; Cloudflare Tunnel is the expected public entry) |
| Database | PostgreSQL in dev/prod (`ddl-auto: validate`), H2 `MODE=PostgreSQL` + `create-drop` in tests |
| Migrations | Flyway `V1`–`V30` plus repeatable migrations; **V29 is applied on the shared Supabase DB (2026-11-06 07:14:49, success)**; V30 is new locally |
| Auth | Implemented: JWT access/refresh, HttpOnly refresh cookie, Google ID token, RBAC, rate limiting |
| Admin users | Implemented: list, detail, create staff, lock/unlock, change role (including custom roles), soft delete; last-active-admin guard |
| Admin roles | Implemented: list permissions, list/detail roles, update role, replace permission set, create role, soft delete; admin guard |
| Catalog | Complete vertical: CRUD, search, filtering, Redis cache, Cloudinary uploads |
| Cart → checkout → order | Implemented, including stock decrement, concurrency tests, and a fish-compatibility advisory |
| VNPay | URL creation, IPN and return handling, idempotent settlement |
| MoMo | Webhook signature verification + settlement implemented; `MomoClient` (payment creation) still a stub |
| Shipping (GHTK/GHN) | Stubs only |
| Notifications | Entity/repository/DTOs exist; no service or controller |
| Statistics/reporting | Not implemented |
| Wishlist | Not implemented |
| Mail, WebSocket, RabbitMQ | Mail/WebSocket dependencies are unused placeholders; RabbitMQ is not in `pom.xml` at all |
| Tests | Latest fresh local run: **659 tests, 0 failures, 0 errors, 10 skipped** (see §12) |

---

## 3. Tech stack

From `pom.xml`:

- Spring Boot `4.1.1`, Java `21`
- Spring Web MVC (`spring-boot-starter-webmvc`), Bean Validation, Actuator,
  AspectJ, Data JPA, Mail, Thymeleaf, Security, WebSocket
- OAuth2 authorization server / resource server / client starters
- springdoc OpenAPI `3.1.0` (Swagger UI)
- MapStruct `1.6.3` (annotation processor)
- JJWT `0.12.6`
- Flyway + `flyway-database-postgresql`
- PostgreSQL driver; H2 and Testcontainers for tests
- Cloudinary `cloudinary-http5` / `cloudinary-taglib` `2.4.0`
- Spring Data Redis (Lettuce)
- `dotenv-java` `3.0.0`
- Lombok
- `com.ainrif.apiator:jakson-plugin-core-json-renderer` (compile scope)

Not actually wired despite being mentioned in docs: RabbitMQ, mail sending,
WebSocket endpoints.

---

## 4. Running the project

### Prerequisites

- Java 21
- PostgreSQL (dev/prod) and Redis (dev/prod for cache + rate limiting)
- A local `.env` for credentials

### Commands

```powershell
# Run tests
.\mvnw.cmd clean test

# Run one test class
.\mvnw.cmd "-Dtest=ProductServiceTest" test

# Run the app with dev profile (Flyway + demo seed data)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Health: `http://localhost:8080/actuator/health`
- Redis health: `http://localhost:8080/api/v1/health/redis`

### Configuration profiles

| File | Purpose |
|---|---|
| `src/main/resources/application.yml` | Base config. Flyway **disabled**, JPA `validate`, JWT, Cloudinary, payment, CORS, shipping, multipart limits |
| `src/main/resources/application-dev.yml` | Flyway enabled, locations `db/migration` + `db/dev`; Swagger demo data enabled; mail health disabled |
| `src/main/resources/application-prod.yml` | Flyway enabled; secure cookies (`SameSite=None`); Swagger/OpenAPI disabled; secrets required from env |
| `src/test/resources/application-test.yml` | H2 `MODE=PostgreSQL`, `create-drop`, Flyway off, placeholder Cloudinary/JWT values |

`.env` is loaded by `DacnApplication` through `dotenv-java`, which writes values
into JVM system properties. It also maps `SPRING_PROFILES_ACTIVE` to
`spring.profiles.active`.

### Environment variables currently referenced

Application config reads:

`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `SPRING_PROFILES_ACTIVE`, `MAIL_HOST`,
`MAIL_PORT`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`,
`REDIS_PASSWORD`, `REDIS_SSL_ENABLED`, `REDIS_COMMAND_TIMEOUT`,
`REDIS_CONNECT_TIMEOUT`, `JWT_SECRET`, `CLOUDINARY_CLOUD_NAME`,
`CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `CLOUDINARY_SECURE`,
`VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`,
`VNPAY_RETURN_URL`, `VNPAY_IPN_URL`, `VNPAY_FRONTEND_RETURN_URL`,
`MOMO_PARTNER_CODE`, `MOMO_ACCESS_KEY`, `MOMO_SECRET_KEY`, `MOMO_ENDPOINT`,
`MOMO_REDIRECT_URL`, `MOMO_IPN_URL`, `CORS_ALLOWED_ORIGINS`,
`AUTH_COOKIE_SECURE`, `AUTH_COOKIE_SAME_SITE`, `GOOGLE_CLIENT_ID`,
`GHTK_BASE_URL`, `GHTK_TOKEN`, `GHN_BASE_URL`, `GHN_TOKEN`, `GHN_SHOP_ID`.

`MAIL_USERNAME` and `MAIL_APP_PASSWORD` are **not** read by the current
configuration. `NEXT_PUBLIC_GOOGLE_CLIENT_ID` belongs to the frontend and is
only mentioned in `docs/commands.md`; the backend reads `GOOGLE_CLIENT_ID`.

There is **no `.env.example`**; onboarding currently depends on the existing
`.env` or the docs that describe the variables.

---

## 5. Source layout

```text
src/main/java/com/project/dacn/
├── DacnApplication.java          # @SpringBootApplication + @EnableScheduling + dotenv
├── config/                       # Security, CORS, Jackson, Redis, OpenAPI, Web, Google, sort converter
├── common/
│   ├── constant/                 # 10 enums (statuses, methods, sort)
│   ├── exception/                # domain exceptions + GlobalExceptionHandler
│   ├── response/                 # ApiResponse, PageResponse
│   └── util/                     # BusinessId
├── controller/                   # auth (incl. AdminUserController), cart, catalog, engagement, order, payment, health
├── dto/request/<domain>/         # inbound records
├── dto/response/<domain>/        # outbound records
├── entity/<domain>/              # 23 JPA entities + BaseEntity
├── mapper/<domain>/              # MapStruct mappers
├── repository/<domain>/          # Spring Data repositories + native query repositories
├── service/<domain>/             # business logic; cart also has CompatibilityService/Calculator/TraitParser
├── security/                     # JWT, filters, handlers, RBAC, rate limiting, cookies, origin validation
├── spec/                         # JPA Specifications (product search)
├── integration/
│   ├── cloudinary/               # upload client/properties
│   ├── payment/                  # settlement + VNPay + MoMo
│   └── shipping/                 # GHTK/GHN stubs
└── aspect/ audit/ event/ listener/ messaging/ scheduler/ validation/ exception/ health/ util/  # .gitkeep placeholders
```

`exception/`, `health/`, and `util/` at the root are placeholders separate from
`common/exception`, `controller/HealthController`, and `common/util`.

Domain packages: `auth`, `identity`, `catalog`, `cart`, `order`, `payment`,
`engagement`.

---

## 6. Domain model and persistence conventions

### Dual ID model

- `BaseEntity` defines a surrogate `Long id` (`@Id`,
  `GenerationType.IDENTITY`), `createdAt`, `updatedAt`.
- Every aggregate also has a **unique business ID string** (`user_id`,
  `product_id`, `order_id`, …) exposed by the API. These are generated in
  `@PrePersist` via `BaseEntity.generateBusinessId()` and
  `common/util/BusinessId`.
- **Important:** since migration `V12__fk_to_surrogate_id.sql`, foreign keys
  reference the surrogate `id`, **not** the business ID. Entities use
  `@JoinColumn(..., referencedColumnName = "id")`. Older design notes in
  `docs/specs.md` describing business-key FKs are outdated.
- External API IDs are opaque strings. Conventionally new rows get readable
  prefixes such as `USR_<email-slug>`, `PRD_<surrogate-id>`, `VAR_<sku>`,
  `CAT_<slug>`, `ORD_...`, but seeded rows may use slug-style IDs instead
  (for example `ember-tetra-default`). Do not assume a prefix for seeded data.

### Entities (23)

| Domain | Entities |
|---|---|
| `auth` | `User`, `Role`, `Permission`, `RolePermission`, `RefreshToken` |
| `identity` | `Admin`, `Customer`, `Employee` (1:1 with `User`) |
| `catalog` | `Category`, `Product`, `Attribute`, `AttributeValue`, `Variant`, `Media`, `VariantAttributeValue`, `ProductAttributeValue` |
| `cart` | `Cart`, `CartItem` |
| `order` | `Order`, `OrderItem` |
| `payment` | `Payment` |
| `engagement` | `Review`, `Notification` |

### Soft delete

Hibernate `@SQLRestriction` hides rows by default:

- `User`: `user_status <> 'DELETED'`
- `Product`: `product_status <> 'DELETED'`
- `Category`, `Attribute`, `Variant`, `Media`, `Notification`: `is_deleted = false`
- `Role`: `is_deleted = false`

Native queries bypass these restrictions, so native query repositories must
filter explicitly (e.g. `AdminUserQueryRepository` does).

### Prices, stock, money

- Money is `BigDecimal` (`precision=10, scale=2`).
- Price and stock live on **`Variant`**, not `Product`.
- `OrderItem.unitPrice` is a snapshot taken at checkout.
- `VariantAttributeValue` and `ProductAttributeValue` use a composite FK to
  prevent pairing an attribute with a value from another attribute.

### Seed catalog

The catalog is an aquarium store. `docs/data.md` defines the category tree
(livestock, equipment, consumables, decor/substrate). Migrations seed fish,
plants, categories, attributes, demo cart/order, reviews, and RBAC data.

---

## 7. HTTP API

All routes are under `/api/v1`. Responses are wrapped:

```json
{ "code": 200, "message": "...", "data": ... }
```

Pagination uses Spring conventions:

- `page` is **zero-based**
- `size` default `20`, maximum `100`
- response shape: `{ content, page, size, totalElements, totalPages }`

> `docs/endpoint.yaml` shows 1-based `page` and `limit`, which does **not**
> match the implementation. `BE_FE_CONTRACT_DIFF.md` is the most accurate
> contract for the endpoints it covers, but it predates the admin user write
> endpoints.

### Main endpoint groups

| Group | Base paths | Notes |
|---|---|---|
| Auth | `/api/v1/auth/*` | register, login, google, refresh, logout, GET/PATCH me, change-password |
| Health | `/api/v1/health/redis` | public |
| Products | `/api/v1/products` | list/search, detail by slug, create, PUT replace, PATCH merge, DELETE |
| Variants | `/api/v1/variants`, `/api/v1/products/{slug}/variants` | CRUD; public reads |
| Categories | `/api/v1/categories` | CRUD; public reads |
| Attributes | `/api/v1/attributes`, `/api/v1/attribute-values` | CRUD; public reads |
| Catalog junctions | `/api/v1/product-attributes`, `/api/v1/variant-attributes` | create/delete/read |
| Media | `/api/v1/media`, scoped product/variant media lists | CRUD; public reads |
| Uploads | `/api/v1/uploads/products/{productId}/thumbnail`, `/api/v1/uploads/variants/{variantId}` | multipart field `file`, Cloudinary |
| Cart | `/api/v1/cart`, `/api/v1/cart/items*`, `/api/v1/cart/checkout` | authenticated customer |
| Customer orders | `/api/v1/orders`, `/api/v1/orders/{orderId}` | authenticated customer, ownership enforced; list filters `status`, `q`, `createdAfter` |
| Payments | `/api/v1/payments/vnpay/{orderId}`, `/api/v1/payments/orders/{orderId}` | authenticated owner |
| Reviews | `/api/v1/variants/{variantId}/reviews`, `/api/v1/reviews/{reviewId}`, `/api/v1/orders/{orderId}/reviews`, `/api/v1/staff/orders/{orderId}/reviews` | verified-purchase rule |
| Staff orders | `/api/v1/staff/orders`, `/api/v1/staff/orders/{orderId}/status` | `order:read` / `order:update` |
| Admin orders | `/api/v1/admin/orders/{orderId}/complete` | `order:complete` |
| Admin users | `/api/v1/admin/users`, `/api/v1/admin/users/{userId}`, `.../{userId}/status`, `.../{userId}/role` | list/detail (`user:read`), create (`user:create`), lock/unlock + change role (`user:update`), soft delete (`user:delete`); role changes accept any existing, non-deleted role except `customer` |
| Admin roles | `/api/v1/admin/permissions`, `/api/v1/admin/roles`, `/api/v1/admin/roles/{roleId}`, `.../{roleId}/permissions` | read (`role:read`), update (`role:update`), create (`role:create`), delete (`role:delete`); `PUT` replaces the whole permission set |
| Webhooks | `/api/v1/webhooks/vnpay/{ipn,return}`, `/api/v1/webhooks/momo` | public but signature-verified |

### Product create/update shape

`ProductCreateRequest` and `ProductUpdateRequest` carry product-level fields only
(`name`, `description`, `detailDescription`, `categoryId`, `slug`, `status`,
plus `thumbnailMediaId` on update). They do **not** accept nested variants or
media URLs. Create the product first, then variants via `POST /api/v1/variants`
and images via the media/upload endpoints.

```json
{ "name": "...", "description": "...", "categoryId": "...", "slug": "...", "status": "ACTIVE" }
```

### Product search parameters

`GET /api/v1/products` accepts:

- `keyword`
- `category_id`
- `min_price`, `max_price` (based on variant prices)
- `status` (privileged callers only; others are pinned to `ACTIVE`)
- `sort` (`newest`, `price_asc`, `price_desc`)
- `page`, `size`

`GET /api/v1/products/search` is an older/simpler search variant using
`name`, `status`, `categoryId`.

### Product detail

`GET /api/v1/products/{slug}` returns product summary, attributes, variants
(with attributes/media/stock), and a paginated review block. Public callers
only see `ACTIVE` products and non-deleted variants.

---

## 8. Authentication, authorization, and security

### Tokens

- Access token: JWT, 15 minutes, subject = `userId` (`USR_...`), claim `type=access`.
- Refresh token: JWT, 7 days, claim `type=refresh`, also stored in DB
  (`refresh_tokens`) and rotated.
- Access token is returned in the JSON body (`data.accessToken`).
- Refresh token is set as an **HttpOnly cookie** named `refresh_token`,
  path `/api/v1/auth` (`RefreshTokenCookie`).
- `JwtService` explicitly rejects refresh tokens used as access tokens.
- Refresh rotation serializes on the token row. A replayed old token within a
  30-second grace window returns the already-rotated replacement; replay
  outside that window revokes every session for that user.
- Changing a password deletes the other refresh tokens, keeping only the
  session that performed the change.
- Expired refresh tokens are purged daily at 03:00 by `RefreshTokenCleanup`.

### Authentication flow

- `POST /api/v1/auth/login` → access token + refresh cookie.
- `POST /api/v1/auth/refresh` → reads cookie, rotates refresh token, returns
  new access token.
- `POST /api/v1/auth/logout` → revokes refresh token and clears cookie.
- `GET /api/v1/auth/me`, `PATCH /api/v1/auth/me` → read/update the customer
  profile.
- `POST /api/v1/auth/google` → verifies Google ID token and creates/links a
  **customer** account; returns 403 for staff accounts and 503 if
  `GOOGLE_CLIENT_ID` is unset.
- `refresh` and `logout` also validate the request `Origin` against the CORS
  allow-list (`TrustedOriginValidator`).
- `CustomUserDetailsService` loads authorities from `role_permissions`.
- `JwtAuthenticationFilter` reloads the user from DB on each request and
  refuses to authenticate `LOCKED`/disabled users (a locked user's old access
  token becomes unusable immediately).

### Authorization

- `SecurityConfig` is **default-deny**: unmatched requests require a token.
- Public route allowlist is documented in `docs/follow-ups.md`.
- URL rules and `@PreAuthorize` both use constants from
  `security/Permissions` (e.g. `product:create`, `order:read`, `user:read`).
- Admin user endpoints use `user:read`, `user:create`, `user:update`, and
  `user:delete` at both URL and method level. Locking and soft-deleting revoke
  the target's refresh tokens; the service blocks self-lock, self-demote,
  self-delete, and removing the last active admin. Role changes accept any
  existing, non-deleted role except `customer`; custom roles use the Employee
  profile.
- Admin role endpoints use `role:read`, `role:create`, `role:update`, and
  `role:delete`. `GET /api/v1/admin/permissions` and `GET /api/v1/admin/roles`
  are read-only; `PUT /roles/{roleId}/permissions` replaces the whole set.
  The admin role must keep every `user:*` and `role:*` permission, and system
  or assigned roles cannot be deleted.
- Roles seeded in real migrations are lowercase: `admin`, `employee`,
  `customer`. Code compares role codes case-insensitively.
- Customers are resolved from the authenticated principal via
  `CustomerIdentity`; client-supplied customer IDs are never trusted.

### Rate limiting

`security/RateLimiter` uses Redis sorted sets:

- `rl:login:<ip>` — 10/min
- `rl:login-email:<email>` — 10/15 min
- `rl:register:<ip>` — 20/hour
- `rl:refresh:<ip>` — 30/min
- `rl:pwd:<email>` — 5/min
- `rl:google:<ip>` — 20/min

It **fails open** if Redis is unavailable, logging at most once per minute.
Auth rate-limit client IP comes from `CF-Connecting-IP`; requests without a
valid header share one `local` bucket. Payment URL creation uses
`X-Forwarded-For` separately and maps IPv6 loopback to `127.0.0.1`.

### CORS and cookies

- CORS origins come from `app.cors.allowed-origins`
  (`CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`).
- Credentials are allowed, so cross-origin refresh requires the frontend to
  send cookies and the origin to be allow-listed.
- Dev cookies: `Secure=false`, `SameSite=Strict`.
  Prod: `Secure=true`, `SameSite=None` (override when same-site).

---

## 9. Cart, checkout, orders, and payments

### Cart

- One cart per customer (`carts.customer_id` unique).
- Adding an existing variant bumps quantity instead of creating a new row.
- Quantity is capped by live variant stock.
- Only variants of `ACTIVE`, non-deleted products are sellable.
- Concurrent insert races are handled by catching the unique-constraint
  violation and merging the rows.
- `GET /api/v1/cart` also returns a fish-compatibility advisory
  (`CompatibilityService` / `CompatibilityCalculator`): shared temperature and
  pH range, temperament, predation risk, and recommended tank litres. It is
  informational only; checkout does not block on it.

### Checkout (`POST /api/v1/cart/checkout`)

1. Lock the cart row (`FOR UPDATE`).
2. Validate cart is non-empty and every variant is sellable.
3. Validate stock.
4. Atomically decrement stock (`variantRepository.decrementStock`); fail if
   the conditional update affects 0 rows.
5. Create `Order` (`PENDING`) and snapshot `OrderItem.unitPrice`.
6. Create `Payment` (`PENDING`, method from request; COD allowed).
7. Clear cart items.
8. Return the created order.

Checkout is covered by `CartCheckoutTest` (6 tests: nested unit + H2
persistence) and `CartCheckoutConcurrencyTest` (1 test).

### Order status

`OrderStatus`: `PENDING`, `CONFIRMED`, `SHIPPING`, `COMPLETED`, `CANCELLED`.

Implemented transitions:

| Actor | Action | Result |
|---|---|---|
| Customer | checkout | creates `PENDING` |
| Staff | `PATCH /staff/orders/{id}/status` with `CONFIRMED`/`CANCELLED` | only from `PENDING`; cancel restocks and marks pending payment `FAILED` |
| VNPay/MoMo IPN | successful settlement | `PENDING` → `CONFIRMED` |
| Admin | `POST /admin/orders/{id}/complete` | non-terminal → `COMPLETED`; does not alter payment/stock |

`SHIPPING` exists as an enum value but no shipping integration moves orders
into it. Admin completion is currently the only path to `COMPLETED`, which
matters because reviews require a completed purchase.

### Payments

`PaymentStatus`: `PENDING`, `CONFIRMED`, `FAILED`, `REFUNDED`, plus
`REFUND_REQUIRED` used by settlement when money arrives for a cancelled/failed
order.

`PaymentMethod`: `CREDIT_CARD`, `DEBIT_CARD`, `BANK_TRANSFER`, `E_WALLET`, `COD`.

#### VNPay

- `POST /api/v1/payments/vnpay/{orderId}` builds a signed sandbox URL.
- `vnp_TxnRef` is currently **the payment business ID** (`PAY_...`), and IPN
  lookup uses `findByPaymentId`.
- `GET /api/v1/webhooks/vnpay/ipn` verifies the signature and settles
  idempotently using a conditional UPDATE. Duplicate/concurrent IPNs settle at
  most once.
- `GET /api/v1/webhooks/vnpay/return` verifies the signature, also settles
  (so a missing IPN does not leave a paid order pending), and redirects the
  browser (`303`) to the configured frontend return URL with
  `?payment=success|failed|invalid`.
- Settlement compares the provider amount in minor units with the payment
  amount.

#### MoMo

- `POST /api/v1/webhooks/momo` verifies the MoMo HMAC signature, validates the
  amount, then calls `PaymentSettlementService`.
- `MomoClient` (creating a payment URL) is still a stub.

#### Payment reference note

`vnp_TxnRef` is the payment business ID (`PAY_...`), and IPN lookup uses
`findByPaymentId`. `PaymentServiceTest` now expects `PAY_1` and passes; the
service, IPN lookup, and test agree.

### Reviews

- A customer may review a variant only after buying it in a `COMPLETED` order.
- Matching order line is stored, marking the review as a verified purchase.
- One review per `(customer, variant)`.
- Reviews can be listed per variant (public for active products), per customer
  order, and per staff order.
- Product detail includes a review page.

---

## 10. Catalog caching and media

### Redis catalog cache

`CatalogCache` is fail-open: Redis errors are logged and reads fall back to
PostgreSQL. Keys are generation-scoped (`pim:product:gen`,
`pim:variant:gen`, `pim:category:gen`), so invalidation bumps a counter instead
of scanning keys.

TTLs (`CatalogCacheKeys`):

| Key type | TTL |
|---|---|
| Product slug | 5 min |
| Product detail aggregate | 5 min |
| Product page | 2 min |
| Variant detail | 5 min |
| Variant page | 2 min |
| Category detail | 10 min |
| Category page | 5 min |

Details and operational notes: `docs/redis-cache-deployment.md`.

Important limits:

- Cached product stock is scrubbed before storing and refreshed on hit.
- Staff reads (`includeHidden`, DRAFT/INACTIVE) bypass the cache.
- Review changes are not cache-invalidated, so review data may lag up to 5 min.
- Cache writes can happen inside the DB transaction; bounded by the Redis
  command timeout (1 s in `application.yml`; code default 200 ms).

### Cloudinary uploads

- `POST /api/v1/uploads/products/{productId}/thumbnail` sets
  `Product.thumbnail`.
- `POST /api/v1/uploads/variants/{variantId}` creates variant `Media`.
- Multipart field is `file`; max upload 10 MB, request 11 MB.
- Cloudinary folder is enforced server-side by `ProductFolderResolver` as
  `home/<category-path>/<product-segment>`; the root category segment and the
  `PRD_` prefix are stripped.
- Only JPEG/PNG/WebP/GIF images up to 10 MB are accepted; content type,
  extension, and magic bytes must all match.
- `CloudinaryException` maps to HTTP 502.

---

## 11. Database and migrations

Migrations live in `src/main/resources/db/migration/`:

| Version | Purpose |
|---|---|
| `V1` | Initial schema |
| `V2` | Indexes (including case-insensitive unique indexes) |
| `V3` | Constraints |
| `V4` | Review rating alignment |
| `V5`–`V9` | Category/fish/cart/demo seeds, product-name canonicalization |
| `V10` | Product business ID from surrogate ID; drops name uniqueness |
| `V11` | Customer business ID from email |
| `V12` | **FK columns switched from business IDs to surrogate IDs** |
| `V13` | Drops `order_item` business ID |
| `V14` | Product slug |
| `V15` | Product thumbnail media FK |
| `V16` | RBAC seed |
| `V17`, `V21` | Order permission seeds |
| `V18`, `V27` | Fish and plant catalog seeds |
| `V19` | Review seeds |
| `V20` | Merge fish variants |
| `V22` | `REFUND_REQUIRED`, drop token hash |
| `V23`, `V24` | Google auth nullable fields, `google_sub` |
| `V25` | Refresh token replacement/rotation |
| `V26` | Customer order composite index (manual `CONCURRENTLY` guidance in `migration/README.md`) |
| `V28` | Lookup indexes |
| `V29` | Admin user management: admin phone/address, nullable employee phone, `user:*` permissions |
| `V30` | RBAC admin role API: four `role:*` permissions granted to the admin role |

Also:

- `R__refreshable_reference_data.sql` — repeatable migration, currently a no-op
  `SELECT 1;` placeholder.
- `src/main/resources/db/dev/R__swagger_demo_data.sql` — dev-only demo accounts
  and objects, loaded only when the `dev` profile adds `classpath:db/dev`.

**V29 is applied on the shared Supabase database.** The user confirmed
`flyway_schema_history` records version `29`, description
`admin user management`, `success = true`, applied **2026-11-06 07:14:49**.
`role_permissions` contains the four grants `rp-admin-user:read`,
`rp-admin-user:create`, `rp-admin-user:update`, and `rp-admin-user:delete`
for the `admin` role. The migration is idempotent (`ADD COLUMN IF NOT EXISTS`,
`DROP NOT NULL`, `ON CONFLICT DO NOTHING`).

`V30` adds `role:read`, `role:create`, `role:update`, and `role:delete` and
grants them to the admin role. It is part of the RBAC admin API work and has not
been confirmed applied on the shared database.

Because `ddl-auto: validate` is used, the app will fail to start if migrations
have not created the expected schema. Flyway is disabled in the base profile;
use `dev` or `prod`.

---

## 12. Tests and current status

- Test sources: `src/test/java/com/project/dacn/` (78 Java files; 77 test
  classes produced Surefire XML reports).
- Test stack: JUnit 5, Mockito, Spring Test, MockMvc, H2, Testcontainers.
- CI: `.github/workflows/backend-tests.yml` runs `./mvnw -B test` on pushes to
  `main`/`dev` and on pull requests, and fails if the PostgreSQL/Testcontainers
  reports are missing or skipped.

### Latest full run (`.\mvnw.cmd clean test`, 2026-11-10)

```text
files=77  tests=659  failures=0  errors=0  skipped=10
```

The `.txt` summaries show `0` tests for `CartCheckoutTest` and
`CartServiceTest` because all their tests live in `@Nested` classes; the XML
reports show 6 and 12 tests respectively. The XML totals are authoritative.

The 10 skipped tests are the container/Redis-dependent suites
(`CustomerOrderSearchPostgresTest` 6, `OrderIndexMigrationTest` 1,
`RateLimiterRedisTest` 3); CI requires the PostgreSQL ones to run.

There is **no failing test** in the latest reports. The old
`PaymentServiceTest.createUrlSignsOrderIdAndWholeVndAmount` mismatch is fixed:
the test is now `createUrlSignsPaymentIdAndWholeVndAmount` and expects
`vnp_TxnRef=PAY_1`. The newest suites are
`AdminUserMutationControllerTest` (28 tests), `AdminUserControllerTest` (12),
`AdminRoleMutationControllerTest` (19), `AdminRoleLifecycleControllerTest`
(11), and `AdminRoleControllerTest` (8).

The latest full run was executed while updating this document on 2026-11-10
with a JDK `26.0.1` runtime even though the POM targets Java `21`; that is the
local runtime, not a project requirement change.

### Testing gotchas

- `target/` can be stale after moving/adding classes; use `.\mvnw.cmd clean test`.
- Spring Boot 4.x repackaged test slice annotations; existing tests use
  standalone MockMvc for some controller tests.
- `application-test.yml` disables Flyway, so tests seed roles/permissions and
  data programmatically.
- Some tests require Docker/Testcontainers for PostgreSQL behavior.

---

## 13. Conventions and gotchas

### API conventions

- Always `/api/v1` prefix.
- JSON request/response; camelCase DTO fields in most newer APIs, though
  `docs/endpoint.yaml` and older FE discussions use snake_case. Verify against
  the actual DTO before building a client.
- `ApiResponse<T>` wrapper for almost everything; paginated data uses
  `PageResponse<T>`.
- `page` is zero-based; `size` max 100.
- Business IDs are strings, not numeric surrogate IDs.
- `PUT` and `PATCH` on products are now distinct: `PUT` replaces (`replace`),
  `PATCH` merges non-null fields (`update`). Some older docs say `PUT == PATCH`.
- Delete endpoints generally return `200 ApiResponse` with `data: null`
  (not 204), except a few provider/webhook responses.

### Error handling

`GlobalExceptionHandler` maps:

| Exception | HTTP |
|---|---|
| `ResourceNotFoundException`, `NoResourceFoundException`, `AccountNotFoundException` | 404 |
| `EmailAlreadyExistsException`, `DuplicateResourceException`, `DataIntegrityViolationException`, `OutOfStockException`, `InvalidOrderStateException`, `UserRuleViolationException` | 409 |
| Validation / malformed body / illegal argument / type mismatch / max upload size / missing multipart part | 400 |
| `InvalidPasswordException`, `InvalidTokenException`, authentication failures | 401 |
| `AccessDeniedException` / locked account / `GoogleLoginNotAllowedException` | 403 |
| `RateLimitExceededException` | 429 |
| `CloudinaryException` | 502 |
| `GoogleLoginNotConfiguredException` | 503 |
| anything else | 500 |

Messages are resolved through `MessageSource`. `error.email.exists` and the
admin-user rule keys are now present in both bundles, but the English bundle is
still incomplete: `error.user.locked`, `error.token.invalid`,
`error.unauthorized`, and `error.forbidden` exist only in Vietnamese and fall
back to code defaults or hardcoded handler messages.

### Concurrency

- Checkout locks the cart row and uses conditional stock decrements.
- Staff order decisions lock the order row and serialize with payment IPNs.
- Payment settlement uses conditional UPDATEs for idempotency.
- Admin user writes block self-lock, self-demote, self-delete, and removal of
  the last active admin; `findByRoleCodeAndStatusForUpdate` locks the active
  admin set while checking.

---

## 14. Known gaps / sensible next steps

V29, V30, admin user management, and the RBAC admin role API are complete.
Step 7 added custom-role assignment: `POST` and `PATCH` on the admin user API
accept any existing, non-deleted role except `customer`, custom roles use the
Employee profile, and their permissions apply on the next request with the same
token. Remaining gaps, ordered roughly by impact:

1. **Implement notifications.** Entity, repository, DTOs and constants exist;
   no `NotificationService`/`NotificationController`.
2. **Implement shipping (GHTK/GHN).** Clients only return names; no quote,
   create, track, or webhook flow. `SHIPPING` order status is unreachable.
3. **Implement MoMo payment URL creation** and VietQR.
4. **Statistics/reporting** (revenue, users, orders, inventory) — not started.
5. **Wishlist persistence** — mentioned in requirements, not implemented.
6. **Mail** (order confirmation, password reset) and **WebSocket** — dependencies
   only.
7. **Refresh stale docs / remove secrets.**
   - `docs/vnpay-payment-test.md` contains plaintext credentials for Supabase,
     Cloudinary, Redis, VNPay, and JWT, plus VNPay dashboard login details.
     Rotate them and scrub the file.
   - It also says the return URL returns JSON and that settlement only happens
     on IPN; the current controller redirects (`303`) and settles on return too.
   - `docs/endpoint.yaml` uses 1-based `page`/`limit`; actual API is zero-based
     `page`/`size`.
   - `docs/progress.md` (2026-10-15) is stale: it says auth/cart/order/payment/
     review are missing, but they now exist.
   - `docs/specs.md` predates V12 (business-key FKs) and V10 (product-name
     uniqueness); check current entities/migrations before relying on it.
   - `docs/step_1_result.md` and `docs/step_2_result.md` are historical: they
     say V29 was not applied, admin users are read-only, and the payment test
     fails. Those statements are now false.
   - `docs/admin-users-step0-analysis.md` lists V28 as the highest migration.
   - `src/main/resources/db/migration/README.md` stops at V26.
8. **Add `.env.example`** and document required variables without secrets.

---

## 15. Documentation map

| File | Use it for |
|---|---|
| `docs/Overview.md` | Product vision, features, original tech-stack list |
| `docs/data.md` | Expected category tree (aquarium taxonomy) |
| `docs/specs.md` | Original persistence design; still useful for entity invariants, but outdated on FK strategy and a few schema details |
| `docs/commands.md` | Minimal run command and env notes |
| `docs/endpoint.yaml` | Old OpenAPI sketch; paths mostly useful, paging/field names not authoritative |
| `BE_FE_CONTRACT_DIFF.md` | **Most accurate endpoint/contract comparison** for frontend integration, but it predates the admin user write endpoints |
| `docs/follow-ups.md` | Current public-route allowlist and security rules |
| `docs/progress.md` | Historical progress snapshot (2026-10-15); stale for auth/cart/order/payment/reviews |
| `docs/redis-cache-deployment.md` | Cache TTLs, eviction, generation counters, rate limiting, Cloudflare notes |
| `docs/seed-data.md` | Dev/Swagger demo accounts and reset SQL |
| `docs/vnpay-payment-test.md` | VNPay tunnel/manual test guide — **contains secrets; stale return behavior** |
| `docs/admin-users-api.md` | Frontend admin user API reference; role changes now accept custom roles |
| `docs/admin-roles-api.md` | Frontend admin role/permission API reference |
| `docs/rbac-admin-step0-analysis.md` … `docs/rbac-admin-step7-result.md` | RBAC admin API design, per-step evidence, and the Step 7 custom-role assignment report |
| `docs/admin-users-step0-analysis.md` | Historical step-0 admin user API design (written when V28 was the latest migration) |
| `docs/step_1_result.md` | Historical JWT filter lock fix, permissions, V29 text, and the then-failing payment test |
| `docs/step_2_result.md` | Historical admin user list/detail implementation and test evidence; superseded by the mutation endpoints |
| `src/main/resources/db/migration/README.md` | Migration index through V26 only; V26 index deployment note |
| `.superpowers/sdd/2026-10-13-catalog-crud/progress.md` | Old catalog CRUD implementation ledger |

---

## 16. Security notes for anyone working in this repo

- `.env` is gitignored and currently present locally. Never commit it.
- Several docs contain what appear to be **live credentials**. Do not copy them
  into new files, issues, logs, or commits. Rotate them if they were real.
- Dev demo accounts use a known password (`Swagger123!`) and are loaded only by
  the `dev` Flyway location. Never enable `classpath:db/dev` in production.
- The app binds to `127.0.0.1`; external access is expected through a
  Cloudflare Tunnel. Do not change `server.address` to `0.0.0.0` casually.
- Payment webhooks are public by URL rule and protected only by signature
  verification; keep it that way and add replay/idempotency tests when
  changing settlement code.
- Passwords, hashes, and tokens must never appear in DTOs or logs.

---

## 17. Suggested onboarding order

1. Read this file, then `docs/Overview.md` and `BE_FE_CONTRACT_DIFF.md`.
2. Read `SecurityConfig`, `JwtAuthenticationFilter`, `JwtService`,
   `AuthService`, `CustomerIdentity`.
3. Read `ProductService` + `ProductController` + `CatalogCache`.
4. Read `CartService.checkout`, `CompatibilityService`, `OrderService`,
   `PaymentService`, `PaymentSettlementService`.
5. Read `AdminUserController` + `AdminUserService`, then
   `AdminRoleController` + `AdminRoleService` for the newest auth slice.
6. Skim `BaseEntity` and the 23 entities, then `V1`/`V12`/`V29`/`V30`
   migrations; V29 is applied on the shared DB, V30 is new locally.
7. Run `.\mvnw.cmd clean test` and confirm the current suite state before
   changing anything.
