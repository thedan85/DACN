# Follow-ups

## Authorization runs after request validation on catalog writes

- **Tests:** `RbacIntegrationTest.protectedEndpoint_customerToken_403` (expects 403),
  `SecurityRulesIntegrationTest.catalogWriteRequiresToken` (expects 401).
- **Symptom:** both receive 400.
- **Cause:** `POST /api/v1/products` with body `{}` is rejected by request/argument
  validation before method security runs, so the response is 400 instead of 401/403.
- **Evidence:** the same assertion (`Status expected:<403> but was:<400>` and
  `Status expected:<401> but was:<400>`) fails on `9fbff5a` and after the Redis catalog
  cache branch. Only per-run metadata (tests-run count, elapsed time) differs.
- **Not fixed with the cache work:** it predates that branch; whether a malformed body
  should be reported before authentication is a security design decision.
