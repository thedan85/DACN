# Flyway migrations

- `V1__initial_schema.sql` — base tables
- `V2__add_indexes.sql` — indexes
- `V3__add_constraints.sql` — constraints
- `V4`–`V9` — review-rating alignment, seed data, product-name canonicalization + unique index
- `V10`–`V15` — business IDs, slugs, media FKs
- `V16` — RBAC seed (renamed from `V9`; DB history already records it as 16)
- `V26__order_customer_index.sql` — order history composite index. Its
  `create index` blocks writes while it builds. Before deploying against a
  database with live traffic, run this once by hand, outside a transaction:

  ```sql
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_customer_id_id
      ON orders (customer_id, id desc);
  ```

  If a cancelled build left an invalid index, `IF NOT EXISTS` still sees it.
  Check for that before starting the app:

  ```sql
  select indisvalid from pg_index
  where indexrelid = 'idx_orders_customer_id_id'::regclass;
  ```

  If it returns false, drop and rebuild the index. Then drop the old index
  concurrently too:

  ```sql
  DROP INDEX CONCURRENTLY IF EXISTS idx_orders_customer_id;
  ```

  V26 then skips both steps. Never edit an applied V26: Flyway validates its
  checksum.
- `R__refreshable_reference_data.sql` — repeatable reference data
