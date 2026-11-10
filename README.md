# Aquarium Store — Backend

Spring Boot API for **Aquarium Store**, a headless aquarium e-commerce platform: product information management (PIM), multi-variant catalog, order processing with touchless fulfillment, and digital payments. The Next.js frontend lives in the companion repo [`FE-DACN`](https://github.com/thedan85/FE-DACN).

University coursework project (đồ án chuyên ngành).

## Stack

- **Java 21** · **Spring Boot** (Web, Security, Data JPA, Validation, Actuator, Mail)
- **PostgreSQL** (Supabase-hosted in dev) · **Redis** · **RabbitMQ**
- **Flyway** migrations (`src/main/resources/db/migration`)
- **MapStruct** · refresh + access token auth · RBAC with seeded roles/permissions
- **Cloudinary** (media) · **VNPAY / MoMo / VietQR** (payments, sandbox) · **GHTK / GHN** (shipping, sandbox)
- **JUnit / Mockito** for tests

## Run

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # Windows: .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)

Configuration is read from `.env` (never committed). Key variables:

| Group | Variables |
| --- | --- |
| Database | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` |
| Redis | `REDIS_*` |
| Media | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `CLOUDINARY_SECURE` |
| Auth | `GOOGLE_CLIENT_ID` (must equal the frontend `NEXT_PUBLIC_GOOGLE_CLIENT_ID`; customers only), `MAIL_HOST`, `MAIL_PORT` |
| Payments | `MOMO_*` (VNPAY/VietQR configured similarly) |
| Shipping | `GHTK_*`, `GHN_*` |

The `dev` profile additionally applies `db/dev` (repeatable demo-data migration). Demo accounts and reset procedures: [`docs/seed-data.md`](docs/seed-data.md) — **local/private dev only, never enable in production.**

## Test

```bash
./mvnw test
```

## Documentation

- `docs/Overview.md` — project goals and functional requirements
- `docs/specs.md` — backend design specs
- `docs/data.md` — catalog seed data
- `docs/commands.md` — run/ops cheatsheet
- `docs/seed-data.md` — demo accounts and seeded objects

## Architecture

Headless: this API is the only backend. The storefront and admin portal are client applications that consume it. Business rules and permission enforcement (`product:*`, `variant:*`, `order:*`, ...) live here; UIs only reflect them. Soft-delete is used throughout — rows are never physically removed.
