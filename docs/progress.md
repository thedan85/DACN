# Progress — DACN Headless E-Commerce (Backend + FE-DACN)

Date: 2026-10-15
Branch: `feature/PIM/phat` (HEAD `c146bb1`)
Source scan: `src/main/java`, `src/main/resources`, `FE-DACN/app`, `docs/*`, git log/status, grep `TODO|UnsupportedOperationException`.

## 1. What exists today

### Backend foundation (Spring Boot 4.1.1, Java 21)
- `pom.xml:1-299` — webmvc, validation, actuator, aspectj, data-jpa, mail, thymeleaf, security, oauth2-auth-server + resource-server, websocket, springdoc, mapstruct, jjwt, flyway, postgres, cloudinary-http5 + taglib, redis, dotenv. No RabbitMQ dependency despite `docs/Overview.md:7` listing it.
- Config: `config/SecurityConfig.java:9-18`, `config/RedisConfig.java`, `config/CorsConfig.java`, `config/WebConfig.java`, `config/OpenApiConfig.java`, `config/JacksonConfig.java`.
- Profiles: `resources/application.yml` (validate, flyway off, Redis via `.env`), `application-dev.yml` (flyway on), `application-test.yml` (H2 `create-drop`, flyway off).
- Common: `common/response/{ApiResponse,PageResponse,ServiceResponse}`, `common/exception/{ResourceNotFoundException,GlobalExceptionHandler}`, 9 enums in `common/constant`.
- Exception envelope: 404 (`ResourceNotFoundException`, `NoResourceFoundException`), 409 (`DataIntegrityViolationException` → generic message), 400 (validation/constraint/illegal-arg/malformed), 500 generic + log. `GlobalExceptionHandler.java:32-37` logs 409 detail, returns generic string.

### Persistence layer (23 entities, per `docs/specs.md`)
- Entities/mappers/repos/DTOs exist for all 6 domains: auth (5), identity (3), catalog (8), cart (2), order (3), engagement (2).
- Dual-ID rule (surrogate `Long id` + unique business `xxx_id`, FK on business key), `@SQLRestriction` soft-delete on User/Role/Product/Category/Attribute/Variant/Media/Notification.
- Flyway `resources/db/migration`: V1 schema, V2 indexes, V3 constraints, V4 review-rating align, V5 115 categories, V6 fish products/variants/attributes, V7 product-detail column + 19 tetra products, plus `R__refreshable_reference_data.sql`. Note: `migration/README.md:1-6` is stale (lists only V1–V3+R).
- `resources/db/project.sql` retained as MySQL-dialect source of truth.

### Catalog PIM — the only complete vertical (CRUD + search)
- 8 controllers under `controller/catalog` + `controller/HealthController.java` (only `/api/health/redis`).
- 8 services under `service/catalog`: Category, Product, Variant, Attribute, AttributeValue, Media, ProductAttribute (junction), VariantAttribute (junction).
- Product list/search: `dto/request/catalog/ProductFilter.java` (keyword/status/categoryId/minPrice/maxPrice/sort) + `spec/ProductSpecs.java` (LIKE keyword, price range on lowest active variant price, price sort via min/max variant price, always excludes DELETED).
- Seed data live per ledger smoke test: `ember-tetra`/variants queryable, media empty page, scoped 404/409/400 envelopes verified at `d292bbf`.
- Cloudinary upload subsystem (new, this session): `controller/catalog/UploadController.java` — `POST /api/v1/uploads/products/{productId}/thumbnail` (sets `Product.thumbnail`) and `POST /api/v1/uploads/variants/{variantId}` (creates variant `Media`); moved under `/api/v1` per `docs/endpoint.yaml` team spec. `service/catalog/{UploadService,ProductFolderResolver}.java` + `dto/response/catalog/UploadResponse.java`; `CloudinaryService` gained an `upload(bytes, folder, publicId)` overload (backward-compatible); `application.yml` sets `spring.servlet.multipart` 10MB/11MB; `GlobalExceptionHandler` maps `CloudinaryException→502`, oversize/missing-part→400. Folder enforced server-side as `home/<category-path>/<productId>/`, verified live against Cloudinary (probe upload + delete). Live data backfill 2026-10-15: all 25 product thumbnails set via `PUT /api/products/{id}` matched to Cloudinary assets (incl. `racoon_tetra` typo asset, devil red, diamond/golden approximations per owner approval) + 3 devil variant `Media` rows via `POST /api/media`; 0 failures.

### Tests
- 23 test files under `src/test/java/com/project/dacn` (mapper ×4, repository ×7, service ×8 incl. new `UploadServiceTest`, `ProductFolderResolverTest`, controller ×1 new `UploadControllerTest`, integration ×1 new `CloudinaryServiceTest`, `CatalogExceptionHandlerTest` extended, `DacnApplicationTests`).
- Verified this session at HEAD: **101/101 green** (`.\mvnw clean test`, BUILD SUCCESS). Note: `target/` goes stale after file moves — use `clean` builds; Boot 4.1 moved slice-test packages and ships no mock-bean annotation (controller tests use standalone MockMvc).

### Frontend (FE-DACN, Next.js 16.3.8 + React 19 + Tailwind 4)
- Stock `create-next-app` template only: `app/page.tsx:1-69`, `app/layout.tsx`, `app/globals.css`, `public/*.svg`. No storefront, no API client, no auth/cart/checkout pages.
- Only real FE artifact: `FE-DACN/docs/design.md:1-100` (Figma color/type/shape tokens).
- Root `.gitignore:38` contains `/FE-DACN`, so FE work is untracked by this repo — history risk.

## 2. Stubs / skeleton (compiles, throws at runtime)

| Area | Files | State |
|---|---|---|
| JWT auth | `security/JwtService.java:9-16`, `security/JwtAuthenticationFilter.java:16-20`, `security/CustomUserDetailsService.java:11-15` | `UnsupportedOperationException` / passthrough / throws `UsernameNotFoundException`. No `PasswordEncoder`, no `JwtDecoder` bean. `SecurityConfig` is `permitAll` + CSRF off — every endpoint open. |
| Shipping | `integration/shipping/ShippingService.java:8-11` throws; `ghtk/GhtkClient.java`, `ghn/GhnClient.java` only implement `getName()`; `shipping/dto/` empty (`.gitkeep`) | No quote/create/track/webhook calls |
| MoMo payment | `integration/payment/momo/MomoClient.java:15-16`, `MomoSignatureService.java:15-21` throw; `MomoWebhookController.java:14-17` returns `"received"` without signature verify or order update | VNPay, VietQR, GHN webhook (per Overview) have no files at all |
| Missing service/controller layers | No `service/` or `controller/` for auth, identity, cart, order, engagement | Entities/DTOs/mappers/repos exist but unreachable via API: login/register, RBAC grant, cart sync, order placement/payment, reviews, notifications all unexposed |
| Mail/WebSocket/OAuth2 | Starters in pom; `templates/`, `static/`, `listener/`, `messaging/`, `event/`, `scheduler/`, `audit/`, `validation/` are `.gitkeep` only | Dead weight or future placeholders |

## 3. Bugs / issues needing fix

### P0 — security & correctness
1. **Open API**: `SecurityConfig.java:12-15` permits all requests; JWT filter is a no-op. Any catalog write (POST/PUT/PATCH/DELETE) is unauthenticated. Fix: implement `JwtService` (jjwt 0.12.6 already a dep), wire filter + `CustomUserDetailsService` + `PasswordEncoder`, lock down routes, keep `/api/health` + docs public.
2. **MoMo webhook acknowledges without verifying** (`MomoWebhookController.java:14-17`): returns `ok("received")`, never checks HMAC, never updates `Payment`/`Order`. Forged callbacks could mark orders paid (once wired) or real payments stay PENDING. Fix: implement `MomoSignatureService` (HMAC-SHA256), idempotent `transactionId` handling, order-status transition.
3. **i18n keys missing**: `messages_vi.properties` / `messages.properties` have 2 keys each (`app.name`, `error.notFound`); spec §5 expected `error.duplicate.email`, `error.restrict.*` etc. Handler returns hardcoded English strings. Fix: add keys, resolve via `MessageSource` with locale.

### P1 — incomplete features (Overview §1.1, 1.4, 1.6–1.8)
4. No auth endpoints (login/logout/register/refresh/lock), no RBAC enforcement despite `Role/Permission/RolePermission` model.
5. No cart/order/payment/shipment services or controllers — storefront checkout, GHTK/GHN fulfillment, VNPay/MoMo/VietQR reconciliation all unwired.
6. No review/notification endpoints; `Review`/`Notification` reachable only via repo tests.
7. No statistics/reporting (revenue/users/orders/inventory) and no permission-management endpoints.
8. RabbitMQ listed in Overview but absent from pom and code — either add starter + consumers (order/shipping/notify async) or remove from docs.
9. Mail + Thymeleaf starters unused — order confirmation / password-reset mails missing.

### P2 — known gaps / tech debt (partly accepted in prior review ledger)
10. `PUT == PATCH` (both call null-ignoring update, e.g. `ProductController.java:56-66`): true replace impossible; document or implement replace-vs-merge.
11. `CategoryService.java:42-52` cannot detach parent (null ignored) — no way to promote a subcategory to root; self-parent guarded, cycle-depth unguarded.
12. Media target immutable on update (`MediaService.java:69-73` ignores product/variant change; create enforces exactly-one in `MediaService.java:50-55` but update path has no equivalent guard) — intentional per ledger, but undiscoverable via API docs.
13. List endpoints for Variant/Attribute/AttributeValue/Media use bare `findAll(pageable)` (e.g. `VariantService.java:27-30`) — correct today only via `@SQLRestriction`; explicit filtered finders exist only for Category/Product/Role/User. Stale `List<>` finders remain (ledger-deferred).
14. `ProductSpecs.filter` hard-excludes DELETED even when `status=DELETED` explicitly requested — admin cannot list soft-deleted products.
15. `GlobalExceptionHandler.java:39-43` returns raw `ex.getMessage()` for validation failures (field-level detail + potentially large payloads); 409/500 sanitized, 400 not.
16. `HealthController.java:16-24` injects `StringRedisTemplate` unconditionally — app fails to start without Redis (no profile/conditional bean); local run without `.env` cloud-Redis creds breaks.
17. `migration/README.md` stale; `R__` repeatable vs V5 seed overlap unverified; `project.sql` header still contradicts DDL (spec §7.2 open item).
18. `.env` required (DB/Redis/Cloudinary/MoMo/GHTK secrets) but undocumented sample — no `.env.example`; onboarding blocked.
19. FE gitignored + untouched template: no API client, no pages, no auth token handling; design tokens not applied.
20. Dirty tree: `src/main/java/com/project/dacn/constant/.gitkeep` deleted (unstaged `D`); prior `.superpowers/` ledger dir now moved under `docs/superpowers/plans` with only plans left — progress ledger only in git history (`89723ce`), at risk of loss.

## 4. Suggested next steps (smallest order)
1. ~~Re-run `mvn -DskipTests compile` + full test suite at HEAD~~ — done 2026-10-15: 101/101 green.
2. Implement JWT vertical (service → filter → user-details → `PasswordEncoder` → `SecurityConfig` rules) + auth controller (login/refresh) — unblocks P0-1.
3. Implement MoMo signature + webhook → payment/order transition + idempotency on `transactionId` — unblocks P0-2.
4. Add cart → order → payment service/controller chain (minimum: place order, snapshot `unitPrice`, decrement stock, COD path) before touching shipping providers.
5. Add `.env.example`, fix `migration/README.md`, decide RabbitMQ in/out, un-ignore or split FE repo.
6. FE: scaffold API client + catalog list/detail pages against live `/api/products` search before building checkout.

## 5. What was deliberately not verified in this session
- No build/test execution; test counts above are from git-history ledger, not a fresh run.
- No live DB/Redis; Flyway V1–V7 SQL contents not line-audited (only filenames + app wiring).
- No review of `CatalogMapper`, request DTO validation annotations, or repository method lists beyond grep spot-checks.
