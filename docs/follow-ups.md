# Follow-ups

## Public route allowlist

`SecurityConfig` is default-deny: a request that matches none of the rules below
requires a valid access token, even if a handler is missing `@PreAuthorize`.
**Add new public endpoints to this list explicitly**; do not widen a wildcard.

| Method | Path | Reason |
|---|---|---|
| OPTIONS | `/**` | CORS preflight carries no credentials |
| POST | `/api/v1/auth/register`, `/login`, `/refresh`, `/logout` | Session setup and teardown |
| GET | `/api/v1/products/**` | Storefront catalog |
| GET | `/api/v1/variants/**` | Storefront catalog |
| GET | `/api/v1/categories/**` | Storefront catalog |
| GET | `/api/v1/attributes/**`, `/attribute-values/**` | Product filters |
| GET | `/api/v1/product-attributes/**`, `/variant-attributes/**` | Product detail |
| GET | `/api/v1/media/**` | Product and variant images |
| GET | both methods under `/api/v1/webhooks/vnpay/`; POST `/api/v1/webhooks/momo` | Payment provider callbacks, protected by signature verification |
| GET | `/actuator/health`, `/api/v1/health/**` | Health checks |
| GET | `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` | API docs |
| GET | `/error`, `/favicon.ico`, `/static/**`, `/css/**`, `/js/**`, `/images/**`, `/webjars/**` | Error page and static assets |

All catalog writes require the same authority at the URL level and in
`@PreAuthorize`, so an unauthorized caller is rejected before request-body
validation. Staff order routes require `order:read` / `order:update`.
