# Swagger demo data

Seed source: `src/main/resources/db/dev/R__swagger_demo_data.sql`.

The `dev` profile adds `classpath:db/dev` to `spring.flyway.locations` in
`application-dev.yml`, so this repeatable migration is applied only there.
It is idempotent; Flyway reruns it when the file checksum changes, not on
every startup.

## Demo accounts

Password for all accounts: `Swagger123!`

| Role | Email | Business ID |
| --- | --- | --- |
| Admin | `swagger.admin@example.com` | `USR_swagger-admin-example-com` |
| Employee | `swagger.employee@example.com` | `USR_swagger-employee-example-com` |
| Customer | `swagger.customer@example.com` | `USR_swagger-customer-example-com` |

The admin and employee profiles have `order:read` and `order:update`
permissions from `V17__seed_order_permissions.sql`.

## Seeded objects

| Object | ID | Details |
| --- | --- | --- |
| Customer | `CUS_swagger-customer-example-com` | `Swagger Customer`, `0901234567` |
| Cart | `CART_CUS_swagger-customer-example-com` | Belongs to the demo customer |
| Cart item | `CI_SWAGGER_EMBER` | `ember-tetra-default`, quantity `2`, unit price `15000.00` |
| Cart item | `CI_SWAGGER_NEON` | `neon-tetra-default`, quantity `1`, unit price `8000.00` |
| Order | `ORD_SWAGGER_DEMO` | `PENDING`, total `30000.00` |
| Payment | `PAY_SWAGGER_DEMO` | `COD`, `PENDING`, amount `30000.00` |

The cart total is `38000.00` across both items. The demo order contains two
`ember-tetra-default` units, so staff endpoints have a pending order to act on.

## Swagger flow

1. Open `http://localhost:8080/swagger-ui/index.html`.
2. Call `POST /api/v1/auth/login` with a demo account. The request fields are
   prefilled with the customer account.
3. Copy `data.accessToken`, click **Authorize**, and paste the token.
4. Customer endpoints: `GET /api/v1/cart`, `POST /api/v1/cart/items`,
   `PATCH /api/v1/cart/items/{cartItemId}`, `POST /api/v1/cart/checkout`.
5. Staff endpoints: `GET /api/v1/staff/orders` and
   `PATCH /api/v1/staff/orders/{orderId}/status` using the admin or employee
   account.

Cart and order response schemas include full example objects. Cart request
bodies and the staff status request also have field examples.

## Reset after testing

A staff status update changes the demo order. To restore it to `PENDING`:

```sql
UPDATE orders
SET order_status = 'PENDING', updated_at = now()
WHERE order_id = 'ORD_SWAGGER_DEMO';
```

To restore the seeded cart quantities:

```sql
UPDATE cart_items SET quantity = 2 WHERE cart_item_id = 'CI_SWAGGER_EMBER';
UPDATE cart_items SET quantity = 1 WHERE cart_item_id = 'CI_SWAGGER_NEON';
```

Checkout consumes the cart. If checkout was tested, restore the cart by adding
the same variants through `POST /api/v1/cart/items`.

## Security

These accounts use a known password and exist only for local/private dev
testing. Do not enable `classpath:db/dev` in production, do not expose this dev
instance publicly, and delete or rotate the demo accounts before sharing the
database.
