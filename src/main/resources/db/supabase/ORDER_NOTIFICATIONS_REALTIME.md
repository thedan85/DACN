# Order-notifications inbox hint trigger (operator stage)

This stage installs the private `inbox.changed` hint trigger. It is applied by
an operator, never by Flyway or application startup. Do not apply it to the
application project during implementation.

## Prerequisites and order

1. Application migrations through V37 (`users.inbox_revision` exists).
2. Foundation stage `notification_realtime.sql` for `app_realtime_control`,
   `realtime_signal_outbox`, and `public.app_realtime_enqueue_signal(text,text,jsonb)`.
   The trigger itself needs only V34/V37 and plain PostgreSQL: no Supabase
   `realtime` schema, no `anon`/`authenticated` roles, and no dispatcher.
3. The applying role must have `EXECUTE` on `app_realtime_enqueue_signal`.

## Install (run twice to prove idempotence)

```bash
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction \
  -f src/main/resources/db/supabase/order_notifications_realtime.sql
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction \
  -f src/main/resources/db/supabase/order_notifications_realtime.sql
```

The script fails closed before creating anything when a prerequisite is missing:
`public.realtime_signal_outbox`, the `app_realtime_control` row `id=1`,
`public.app_realtime_enqueue_signal(text,text,jsonb)` with effective `EXECUTE`,
`public.users.id`, or `public.users.inbox_revision`. Reapply leaves one function
(`public.enqueue_inbox_revision_signal()`) and one trigger
(`users_inbox_revision_signal` on `public.users`); the trigger is recreated, so
its OID changes, but its shape and count do not.

## Contract

- Trigger: `AFTER UPDATE OF inbox_revision ON users FOR EACH ROW`. A no-op
  update, an insert, or a stale User flush that does not touch `inbox_revision`
  produces no hint. Services already increment the revision once; they must not
  enqueue a second manual hint.
- Payload: exactly `{"revision": <bigint>}` on topic `inbox:<internalUserId>`,
  event `inbox.changed`, at most 1 KB. No message, unread count, email, order,
  or user data.
- The trigger only calls foundation's best-effort enqueue. It never calls
  `realtime.send`, never locks or updates `app_realtime_control`, and never
  writes leases or send accounting. An enqueue failure is caught as a warning
  so the business write still commits.
- Foundation's dispatcher coalesces pending hints per topic to the highest
  revision and sends once; hints stay pending when sending is disabled or the
  budget is zero, while revision and SQL counts remain correct.

## Verify installed shape

```sql
select p.oid::regprocedure, p.prosecdef, p.proconfig
  from pg_proc p
 where p.oid = 'public.enqueue_inbox_revision_signal()'::regprocedure;
select tgname, tgrelid::regclass
  from pg_trigger
 where tgname = 'users_inbox_revision_signal' and not tgisinternal;
```

Local proof (never hosted): `mvn -Dtest=InboxRevisionSignalTriggersPostgresTest,NotificationRevisionHintPostgresTest test`
with `TEST_POSTGRES_URL`, `TEST_POSTGRES_USERNAME`, and `TEST_POSTGRES_PASSWORD`
pointing at a PostgreSQL server where the test creates and drops its own
`verify_event_foundation_<12 hex>` scratch database. Missing configuration is a
skip, not a pass.
