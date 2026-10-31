# Flyway migrations

- `V1__initial_schema.sql` — base tables
- `V2__add_indexes.sql` — indexes
- `V3__add_constraints.sql` — constraints
- `V4`–`V9` — review-rating alignment, seed data, product-name canonicalization + unique index
- `V10`–`V15` — business IDs, slugs, media FKs
- `V16` — RBAC seed (renamed from `V9`; DB history already records it as 16)
- `V26__order_customer_index.sql` — order history composite index. Its
  `create index` blocks writes while it builds; before deploying against a
  database with live traffic, build it by hand with `CREATE INDEX CONCURRENTLY`
  (it cannot run inside Flyway's transaction — same note as `V2`).
- `R__refreshable_reference_data.sql` — repeatable reference data
