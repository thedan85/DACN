# DACN Persistence Layer Spec — Entities / DTOs / Mappers / Repositories

Date: 2026-10-11
Approach: A — Faithful port (chosen)
Source of truth for behavior: `src/main/resources/db/project.sql` (MySQL dialect) + `docs/Overview.md` + `docs/data.md`
Target runtime: PostgreSQL (app `ddl-auto: validate`, Flyway enabled in dev, H2 `create-drop` in test)

## 1. Intent

Build the JPA persistence foundation for the headless e-commerce backend (PIM catalog, auth/RBAC, cart/order, engagement) so later services/controllers have a stable, compilable domain layer.

Success criteria:
- All 23 tables from `project.sql` have a JPA entity that boots under H2 `MODE=PostgreSQL` + validates against future Postgres Flyway migrations.
- Dual-ID rule preserved: surrogate `Long id` is `@Id`, business `xxx_id` is unique natural key; every FK joins on the business key via `referencedColumnName`.
- Full CRUD DTOs + MapStruct mappers + Spring Data repositories per entity.
- Known schema bugs fixed in both entities and future migrations (see §2.4).
- `mvn -DskipTests compile` + `@DataJpaTest` context load pass.

Out of scope: services, controllers, security config wiring, Flyway SQL rewrite itself (separate task), FE work.

## 2. Architecture

### 2.1 Package layout — modules per domain

Base package `com.project.dacn.modules`:

```
modules/common/entity/BaseEntity.java
modules/common/enums/*.java            (UserStatus, ProductStatus, OrderStatus, PaymentMethod, PaymentStatus, NotificationType, NotificationStatus, AdminStatus, EmployeeStatus)
modules/auth/entity/      dto/request/      dto/response/      mapper/      repository/
  User, RefreshToken, Role, Permission, RolePermission
modules/identity/entity/  ... (Admin, Customer, Employee)
modules/catalog/entity/   ... (Category, Product, Attribute, AttributeValue, Variant, Media, VariantAttributeValue, ProductAttributeValue)
modules/cart/entity/      ... (Cart, CartItem)
modules/order/entity/     ... (Order, OrderItem, Payment)
modules/engagement/entity/... (Review, Notification)
```

Rationale: matches `docs/Overview.md` bounded contexts (PIM/catalog, accounts, orders/fulfillment, notifications); keeps each module independently testable. Flat `domain.*` package was rejected — 23 aggregates in one package become unnavigable.

### 2.2 Base mapping rules

- `BaseEntity` (`@MappedSuperclass`): `Long id` `@Id @GeneratedValue(IDENTITY)`, `Instant createdAt` (`@CreationTimestamp`, `TIMESTAMPTZ NOT NULL DEFAULT NOW()`), `Instant updatedAt` (`@UpdateTimestamp`). Lombok `@Getter @Setter @NoArgsConstructor @SuperBuilder` — no `@Data`/`equals` on entities (proxy-safe).
- Exception: `RolePermission` does NOT extend `BaseEntity` (SQL has only `granted_at`, no `created/updated_at`). It has own `Long id` + `Instant grantedAt`.
- Business key: `String xxxId` per entity, `@NaturalId @Column(name="xxx_id", nullable=false, unique=true)` (never client-supplied, never updated). Generation via `@PrePersist generateBusinessId()` per entity (`UUID.randomUUID().toString()` if null); mappers declare `@Mapping(target="xxxId", ignore=true)` and never assign IDs. Rationale: mappers must stay pure transforms — `@PrePersist` survives repeated mapping calls and test conversions without producing divergent IDs.
- FKs: `@ManyToOne(fetch=LAZY, optional=...) @JoinColumn(name="...", referencedColumnName="xxx_id", foreignKey=@ForeignKey(name="..."))`. No bidirectional `@OneToMany` collections — DB owns `ON DELETE CASCADE/RESTRICT/SET NULL`; JPA declares no `CascadeType.REMOVE` to avoid double-cascade surprises. Never call `repository.delete(parent)` on soft-deleted aggregates (users, products, categories, variants, notifications) — change status flags instead; hard-delete only where SQL has no soft-delete path AND no `RESTRICT` blocker (e.g. `User → Customer → Orders` chain must be rejected while orders exist).
- Money: `BigDecimal`, `@Column(precision=10, scale=2)`, `@PositiveOrZero`.
- Stock/qty: `Integer`, `@PositiveOrZero` (stock) / `@Positive` (cart/order qty).
- Timestamps nullable (`lastLogin`, `paidAt`): `Instant`, nullable.
- Booleans: `Boolean` (wrapper) with `@Builder.Default false` for `isDeleted`, `revoked`, `isSystem`.
- Enums: `@Enumerated(STRING)` on `VARCHAR` columns. Postgres migrations must use `VARCHAR + CHECK`, NOT native `ENUM` type (keeps H2 test + `validate` portable).
- Table/column names kept verbatim from `project.sql` (`users`, `user_id`, `variant_sku`, …) via `@Table`/`@Column(name=...)` so `validate` matches Flyway DDL.

### 2.3 Module file inventory (~138 files)

Per entity: 1 entity + 2 requests (`CreateRequest`, `UpdateRequest`) + 1 response (`Response`) + 1 MapStruct mapper + 1 repository. `RolePermission` has no `UpdateRequest` (immutable grant; revoke = delete).

### 2.4 Mandatory dialect + schema-bug fixes (entities AND future Flyway V1–V3 must apply all)

1. MySQL → Postgres: drop `ENGINE=InnoDB CHARSET/COLLATE`; `BIGINT UNSIGNED AUTO_INCREMENT PK` → `BIGINT GENERATED ALWAYS AS IDENTITY PK`; `TIMESTAMP DEFAULT CURRENT_TIMESTAMP [ON UPDATE]` → `TIMESTAMPTZ DEFAULT NOW()` (+ Hibernate `@UpdateTimestamp` for `updated_at`); `TINYINT` → `SMALLINT`; `ENUM(...)` → `VARCHAR(n) + CHECK (col IN (...))`; boolean defaults kept.
2. Table-name bug: PART 1 creates `variant_attribute_values` but PART 2 alters `variant_values`. Canonical name: `variant_attribute_values`. All FK/unique names use `variant_attribute_values`.
3. Missing constraints for `product_attribute_values` (PART 2 silent): add `uq_product_attribute (product_id, attribute_id)` + `fk_product_values_product → products CASCADE` + `fk_product_values_attr_pair (attribute_id, attribute_value_id) → attribute_values RESTRICT`, mirroring the variant side.
4. Media target rule (MySQL 3823 note): exactly one of `product_id`/`variant_id` must be non-null. Enforce in BOTH layers: Postgres `CHECK (((product_id IS NOT NULL) AND (variant_id IS NULL)) OR ((product_id IS NULL) AND (variant_id IS NOT NULL)))` (valid with cascading FKs — invalid state only arises from bad writes, which CHECK rejects; cascade deletes remove the row rather than violating the check) AND Java guard in `MediaMapper`/service (`IllegalArgumentException` → 400) for fast feedback.
5. `payments.payment_transaction_id` nullable unique: Postgres allows multiple NULLs (unlike MySQL quirks) — keep `UNIQUE`, mappers leave null until gateway returns idempotency key.
6. `attribute_values` keeps BOTH uniques from SQL: `(attribute_id, attribute_value)` and `(attribute_id, attribute_value_id)`, plus `CHECK (LENGTH(TRIM(attribute_value)) > 0)`.
7. Unique on EVERY FK-target business key (Postgres rejects FKs referencing non-unique columns — `project.sql` PART 2 omits most of these; Flyway V3 must add all): `users(user_id)`, `roles(role_id)`, `permissions(permission_id)`, `admins(admin_id)`, `customers(customer_id)`, `employees(employee_id)`, `categories(category_id)`, `products(product_id)`, `attributes(attribute_id)`, `attribute_values(attribute_value_id)` (composite uniques already cover the pair form; single-column unique on `attribute_value_id` required for the composite-FK design below — see §3.3), `variants(variant_id)`, `media(media_id)`, `carts(cart_id)`, `orders(order_id)`, `order_items(order_item_id)`, `refresh_tokens(token_id)` (add; no FK targets it yet but keeps the convention uniform).

## 3. Components (entities)

### 3.1 `auth` — User, RefreshToken, Role, Permission, RolePermission

- `User`: `userId`, `email` (unique, `@Email`), `passwordHash` (`user_password_hash`, `@JsonIgnore` on entity + never in Response), `status` (`UserStatus` ACTIVE/LOCKED/DELETED, default ACTIVE), `ManyToOne Role` via `role_id` (`RESTRICT`), `lastLogin` nullable. Soft-delete via `status=DELETED`, never hard-delete when customer has orders (`customers → orders RESTRICT` blocks the chain — documented in SQL header).
- `RefreshToken`: `tokenId`, `ManyToOne User CASCADE`, `tokenHash` unique (`@JsonIgnore`), `expiresAt` (`token_expires_at`), `revoked` default false, `userAgent` (`token_user_agent` nullable), `ip` (`token_ip` nullable).
- `Role`: `roleId`, `code` (`role_code` unique, e.g. `admin/customer/employee`), `name`, `description` nullable TEXT, `isSystem` (`role_is_system`), `isDeleted`. Deletion blocked while referenced by users (`RESTRICT`).
- `Permission`: `permissionId`, `code` unique (e.g. `product:create`), `name`, `resource` (`permission_resource`), `action` (`permission_action`), `description` nullable.
- `RolePermission`: `rolePermissionId`, `ManyToOne Role CASCADE`, `ManyToOne Permission CASCADE`, unique `(role_id, permission_id)`, `grantedAt`. No update path.

### 3.2 `identity` — Admin, Customer, Employee (1–1 to User)

- `Admin`: `adminId`, `OneToOne User CASCADE` via unique `user_id`, `name` (`admin_name`), `status` (`AdminStatus` ACTIVE/INACTIVE).
- `Customer`: `customerId`, `OneToOne User CASCADE` via unique `user_id`, `name`, `address` nullable, `phone` (`customer_phone`). Owns one `Cart` + history of `Orders`.
- `Employee`: `employeeId`, `OneToOne User CASCADE` via unique `user_id`, `name`, `address` nullable, `phone`, `status` (`EmployeeStatus` ACTIVE/INACTIVE).

### 3.3 `catalog` — Category, Product, Attribute, AttributeValue, Variant, Media, VariantAttributeValue, ProductAttributeValue

Price/stock live ONLY on `Variant` (per SQL header), never on `Product`.

- `Category`: `categoryId`, `name`, `slug` unique (URL use), `ManyToOne self parent` via `parent_category_id` (`SET NULL`), `isDeleted`. `docs/data.md` seed tree (livestock/equipments/consumables/decors-substrate, 3 levels) fits this self-FK.
- `Product`: `productId`, `name`, `description` TEXT, `thumbnail` nullable (Cloudinary URL), `status` (`ProductStatus` DRAFT/ACTIVE/INACTIVE/DELETED, default ACTIVE — soft-delete via DELETED), `ManyToOne Category SET NULL` nullable.
- `Attribute`: `attributeId`, `name` unique (e.g. `Color`, case-insensitive unique via `ai_ci` collation → enforce lowercased lookup in repo `findByNameIgnoreCase`), `isDeleted`.
- `AttributeValue`: `attributeValueId`, `ManyToOne Attribute RESTRICT`, `value` (`attribute_value`, non-blank), uniques `(attribute_id, attribute_value)` + `(attribute_id, attribute_value_id)` + single-column `attribute_value_id` (FK target — see §2.4.7).
- `Variant`: `variantId`, `sku` (`variant_sku` unique), `price` + `stock` (`>= 0`), `ManyToOne Product CASCADE`, `isDeleted`. Hard-delete allowed only when no `cart_items`/`order_items` reference it (`RESTRICT`); otherwise soft-delete.
- `Media`: `mediaId`, `url` (`media_url`), nullable `ManyToOne Product CASCADE` + nullable `ManyToOne Variant CASCADE`, `isDeleted`. Exactly-one-target enforced in DB CHECK + Java (see §2.4.4).
- `VariantAttributeValue`: `variantValueId`, `ManyToOne Variant CASCADE`, `ManyToOne Attribute` (writes `attribute_id`) + `ManyToOne AttributeValue` via composite `@JoinColumns` with `attribute_id` `insertable=false, updatable=false` (read-only duplicate — avoids Hibernate repeated-column error while preserving the SQL composite FK `(attribute_id, attribute_value_id) → attribute_values RESTRICT`, which prevents pairing `Color` with `Size-L`), unique `(variant_id, attribute_id)` (one `Color` per variant). Composite mapping must be proven by an integration test early (first `@DataJpaTest` batch), not left to the end.
- `ProductAttributeValue`: same composite pattern for product-level specs: `productValueId`, `ManyToOne Product CASCADE`, attribute (writes `attribute_id`) + attributeValue (composite read-only on `attribute_id`) pair `RESTRICT`, unique `(product_id, attribute_id)`.

### 3.4 `cart` — Cart, CartItem

- `Cart`: `cartId`, `OneToOne Customer CASCADE` via unique `customer_id` (one cart per customer; multi-cart/wishlist later per SQL comment).
- `CartItem`: `cartItemId`, `ManyToOne Cart CASCADE`, `ManyToOne Variant RESTRICT`, `quantity` (`@Positive`), unique `(cart_id, variant_id)` (same variant → bump qty, no new row).

### 3.5 `order` — Order, OrderItem, Payment

- `Order`: `orderId`, `ManyToOne Customer RESTRICT` (keeps order history; blocks customer delete), `status` (`OrderStatus` PENDING/CONFIRMED/SHIPPING/COMPLETED/CANCELLED, default PENDING; no hard-delete — cancel via CANCELLED), `total` (`order_total >= 0`), `address` (`order_address`), `recipientName`, `phone` (`order_phone`).
- `OrderItem`: `orderItemId`, `ManyToOne Order CASCADE`, `ManyToOne Variant RESTRICT` (keeps sales history), `quantity` (`@Positive`), `unitPrice` (price snapshot at sale, `>= 0`).
- `Payment`: `paymentId`, `OneToOne Order CASCADE` via unique `order_id` (one payment per order per current SQL), `method` (`PaymentMethod` CREDIT_CARD/DEBIT_CARD/BANK_TRANSFER/E_WALLET/COD), `status` (`PaymentStatus` PENDING/CONFIRMED/FAILED/REFUNDED), `amount` (`>= 0`), `transactionId` nullable unique (gateway idempotency key), `paidAt` nullable.

### 3.6 `engagement` — Review, Notification

- `Review`: `reviewId`, `ManyToOne Variant CASCADE`, `ManyToOne Customer CASCADE`, nullable `ManyToOne OrderItem CASCADE` (verified-purchase link), `rating` 1–5, `comment` TEXT, uniques `(customer_id, variant_id)` (one review per customer/variant) + `order_item_id` (one review per order line; NULLs allowed for non-order reviews). Deleting a review is hard-delete (no soft-delete column in SQL).
- `Notification`: `notificationId`, `ManyToOne User CASCADE`, `message` TEXT, `type` (`NotificationType` ORDER/PROMOTION/SYSTEM, default SYSTEM), `status` (`NotificationStatus` UNREAD/READ), `isDeleted`.

## 4. Data flow (DTOs + mappers + repositories)

- Inbound: `CreateRequest` (no `xxxId`; Bean Validation: `@Email @NotBlank @Positive @Size`, rating `@Min(1) @Max(5)`) → MapStruct (ignores `xxxId`) → entity `@PrePersist` generates business ID → `save`. `UpdateRequest`: all fields nullable, no `xxxId`; mapper `updateEntity(@MappingTarget, UpdateRequest)` with `@BeanMapping(nullValuePropertyMappingStrategy=IGNORE)` for dirty-checking partial updates.
- Outbound: `Response` records carry flat `xxxId` strings + denormalized display names (`ProductResponse{productId, name, categoryId, categoryName}`, `OrderItemResponse{variantId, sku, unitPrice}`, …). No lazy entities, no bidirectional JSON — prevents N+1/`LazyInitializationException`. `passwordHash`/`tokenHash` never appear in any Response.
- Mappers: MapStruct `componentModel=SPRING`, `unmappedTargetPolicy=IGNORE`; explicit `@Mapping(source="role.roleId", target="roleId")`-style FK flattening; `MediaMapper` + `AttributeValueMapper` throw `IllegalArgumentException` on rule violations (media exactly-one-target as fast-feedback duplicate of the DB CHECK, blank attribute value).
- Repositories: `JpaRepository<Entity, Long>` + `Optional<Entity> findByXxxId(String xxxId)` + `existsBy...` for uniques. Soft-delete filtering defense-in-depth: `@SQLRestriction("is_deleted = false")` on `Category/Attribute/Variant/Media/Notification` + `@SQLRestriction("user_status <> 'DELETED'")` on `User` + `@SQLRestriction("product_status <> 'DELETED'")` on `Product`, PLUS explicit filtered finder methods (`findByIsDeletedFalse`, `findByStatusNot`) for queries that need them. Tests must prove every storefront query excludes soft-deleted rows (see §6). No custom `@Query` needed at this layer; filtering/sorting endpoints build on these later.

## 5. Error handling

- `DataIntegrityViolationException` (duplicate email/sku/slug/code, RESTRICT delete of customer-with-orders / variant-in-cart, rating CHECK) → 409 via `GlobalExceptionHandler` extension with `messages_vi.properties` keys (e.g. `error.duplicate.email`, `error.restrict.customer.orders`). New handler method required (exists today only for 400/500).
- Bean Validation on `Create/UpdateRequest` → 400 through existing `ApiResponse` path.
- Java-only rules (media target, blank attribute value) → `IllegalArgumentException` → existing 400 handler.
- Soft-delete reads: repositories exclude `DELETED`/`isDeleted=true` by default so locked/deleted products and users never leak to storefront queries.

## 6. Testing

- Test profile (`application-test.yml`, H2 `MODE=PostgreSQL`, `create-drop`): entities must be self-consistent — context-load test fails fast on wrong `referencedColumnName`/enum/precision.
- Per-module `@DataJpaTest`: persist parent→child through business-key FKs (Role→User→Customer→Cart→CartItem; Category→Product→Variant→Media; Order→OrderItem→Review); assert uniques throw (`email`, `sku`, `slug`, `cart(customer,variant)`); assert RESTRICT blocks (delete variant present in cart, delete customer with orders); assert soft-delete filters hide rows.
- Mapper unit tests: `@PrePersist` generates non-null `xxxId` (not the mapper), enum passthrough, `updateEntity` skips nulls, media/attribute guards throw.
- No Testcontainers for this layer; H2 suffices for DDL validation. Postgres Testcontainers reserved for later Flyway migration tests.

## 7. Open items for implementation plan (writing-plans next step)

1. Execution order (migrations BEFORE entities — entities `validate` against the schema, so the schema must exist first; composite attribute mapping proven in the first `@DataJpaTest` batch): Flyway V1–V3 rewrite (Postgres, §2.4 fixes incl. §2.4.7 uniques + §2.4.4 media CHECK) → `common` (BaseEntity, enums) → `auth` → `identity` → `catalog` (composite-attribute integration test here) → `cart` → `order` → `engagement` → DTOs/mappers per module → repository tests → 409 handler → category seed.
2. Also fix `project.sql` header: replace `PK: business id VARCHAR(255), khong dung INT surrogate` with `PK: surrogate BIGINT id / Business keys: VARCHAR xxx_id, UNIQUE, used by foreign keys` so the header stops contradicting the actual DDL (spec follows the DDL, not the stale header).
3. `GlobalExceptionHandler` 409 extension + `messages_vi.properties` keys.
4. Category seed from `docs/data.md` as `R__` repeatable migration (optional, after entities).
