# Supabase Realtime setup runbook

Module 1 keeps Realtime **disabled** until every hosted gate below has evidence.
Nothing here is applied by Flyway or by application startup.

**Hosted status: UNVERIFIED.** The Task 14 smoke test uses an invocation-owned
`verify_event_foundation_<random>` PostgreSQL database with stub `realtime` and
`auth` schemas. It proves SQL behavior, not hosted JWT acceptance, WebSocket
authorization, Data API lockdown, public-channel rejection, or provider quotas.
It never applies this operator script to the application project. The commands
below are future operator procedures, not authorization to change cloud settings.
Keep both `app.realtime.enabled=false` and `app_realtime_control.enabled=false`
until the approved hosted gates have evidence.

## 1. Data API lockdown review

The browser talks to Spring and to Realtime Broadcast only. It never needs the
Data API.

1. Inventory current exposure with operator approval:
   - `public.users`, `public.orders`, `public.payments`, `public.notifications`,
     and transport tables must deny guest and authenticated reads/writes.
   - Postgres Changes must be disabled for those tables.
   - Inspect exposed REST/GraphQL schemas, table/sequence/function grants,
     inherited role membership, default privileges, publication membership,
     and existing consumers. Include `event_outbox`, `app_realtime_control`,
     `realtime_token_leases`, `realtime_signal_outbox`, and
     `realtime_send_accounting` in the inventory.
2. Prefer disabling the unused Data API entirely after approval. Record the
   decision and the date in the change ticket.
3. Probe with the publishable key plus each backend-issued guest/authenticated
   credential, not a service-role key or the Spring bearer token:
   - Direct REST/GraphQL reads of every listed business/transport table are denied.
   - `insert`/`update`/`delete` are denied using only approved disposable fixtures.
   - Raw Postgres Changes subscriptions cannot expose fixture row payloads.
     Verify publication membership and role access as well as a negative probe.
   - An empty table or a silent socket alone is not evidence of denied access.
     Establish a controlled fixture and an authorized positive control first.
4. Do not weaken an existing policy to make a probe pass.
5. If another client needs the Data API or a conflicting publication, block
   rollout and obtain an approved restricted-exposure design. Do not silently
   revoke unrelated clients' grants or remove publications. Disabling the Data
   API does not disable Postgres Changes.

## 2. Signing key

- Import a **dedicated ES256 (P-256) key** for Realtime. Use a new key; do not
  rotate or replace keys other services depend on.
- Keep the PKCS#8 private key backend-only. It belongs in
  `SUPABASE_REALTIME_PRIVATE_KEY`, never in the frontend bundle, a log line, or
  this repository.
- Set `SUPABASE_REALTIME_SIGNING_KEY_ID` to the key's `kid`.
- Leave `app.realtime.enabled=false` until step 5 passes.
- Inventory existing signing keys and consumers first. A Supabase-generated
  private key is not exportable; backend minting needs the operator-approved
  imported private key. Do not reuse the Spring `jwt.secret`.
- **Unresolved hosted key gate:** the official import workflow creates a standby
  key and currently instructs rotating to activate it. This task does not
  authorize rotating or revoking existing keys. Do not assume importing a
  standby key makes custom JWTs acceptable. Obtain a supported, separately
  approved trust configuration and prove acceptance on an isolated hosted
  environment; otherwise remain blocked.
- Verify ES256, `kid`, the JSON-string claim `aud="realtime"` for both roles,
  the `anon`/`authenticated` role,
  `iat`, `exp` (300 seconds), `jti`, `stock_product_ids`, `inbox_allowed`, and
  the authenticated decimal-string `app_user_id`. Omit `sub`; these identities
  are not `auth.users` UUIDs. Guest tokens omit `app_user_id` and never grant
  inbox access. Verify wrong-key, expired, and wrong-audience rejection without
  recording tokens or private material. Hosted acceptance of this audience is
  unverified; keep Realtime disabled until separately approved evidence exists.

## 3. Apply the SQL foundation

Apply as the backend role that owns the application objects:

```bash
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction -f src/main/resources/db/supabase/notification_realtime.sql
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 --single-transaction -f src/main/resources/db/supabase/notification_realtime.sql
```

The second run proves idempotence. The script fails closed when
`realtime.messages` or the V33 tables are missing. It creates receive policies
only; there is no client insert/update/delete policy, and `PUBLIC` cannot
execute the helpers. `authenticated` can execute only the active-inbox helper;
the applying backend role can execute the send wrapper and dispatcher. Use one
transaction and stop on errors so a failed privilege check cannot leave a
partially installed security boundary.

Prerequisites include the V33 Realtime transport/control tables and the control singleton,
`users.id`/`users.user_status`, existing `anon`/`authenticated` roles,
`realtime.messages.extension`, `realtime.topic()`, and
`realtime.send(jsonb,text,text,boolean)`. No `users.inbox_revision` or inbox
trigger is needed at this stage. Never create, drop, or change cluster roles to
make a scratch test pass. Inventory and notifications install their own triggers
only after their migrations.

Before applying, inspect the managed table without changing its ownership or RLS:

```sql
select pg_get_userbyid(relowner) as owner, relrowsecurity, relforcerowsecurity
  from pg_class where oid = 'realtime.messages'::regclass;
select policyname, permissive, roles, cmd, qual, with_check
  from pg_policies where schemaname = 'realtime' and tablename = 'messages';
```

Hosted `relrowsecurity` must already be true. If it is not, stop for provider/operator
review; do not run `ALTER TABLE` ownership/RLS changes on Supabase-managed tables.
Only the scratch fixture enables RLS on its own stub table. The script replaces
only its two named receive policies. Other permissive policies applying to
`PUBLIC`, either browser role, or an inherited parent block installation for
review, even if their topic condition looks unrelated. Restrictive and
backend-only policies remain untouched. Recheck policies after every rollout;
a later permissive grant can reopen receive, publish, or Presence access.

Inspect function ACLs and default grants, including inherited access:

```sql
select p.oid::regprocedure as function, p.prosecdef, p.proconfig,
       case when a.grantee = 0 then 'PUBLIC'
            else pg_get_userbyid(a.grantee) end as grantee,
       a.privilege_type, a.is_grantable
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
 where n.nspname = 'app_private'
   and p.proname in ('realtime_inbox_allowed', 'send_realtime_signal', 'dispatch_realtime_signals');
select defaclrole::regrole, defaclnamespace::regnamespace, defaclobjtype, defaclacl
  from pg_default_acl;
select browser.rolname as browser_role, parent.rolname as inherited_role
  from pg_roles browser cross join pg_roles parent
 where browser.rolname in ('anon', 'authenticated') and browser.oid <> parent.oid
   and pg_has_role(browser.oid, parent.oid, 'USAGE');
select browser.rolname, p.oid::regprocedure as function,
       has_function_privilege(browser.oid, p.oid, 'EXECUTE') as effective_execute
  from pg_roles browser cross join pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where browser.rolname in ('anon', 'authenticated') and n.nspname = 'app_private'
   and p.proname in ('realtime_inbox_allowed', 'send_realtime_signal', 'dispatch_realtime_signals');
```

All three functions must use `SECURITY DEFINER` and `search_path=""`. Only their
owner has execution, except `authenticated` on `realtime_inbox_allowed(text)`.
Browser roles must not be superusers, bypass RLS, own the managed table, or
inherit the backend owner's privileges. The script clears explicit/default ACL
grants on only these three functions, leaves global default privileges and role
membership unchanged, and blocks unsafe effective inherited execution. Apply as
the function owner. If a distinct backend runtime role is approved, grant only
schema usage and dispatcher execution to that role after installation; record
that exception and repeat the audit. Reapplying clears such additional function
grants, so repeat the approved runtime grant after each apply.

The outbound wrapper accepts only `stock.changed` on `stock:<productId>` with a
matching nonblank string `productId` (at most 255 characters), or `inbox.changed`
on a canonical positive bigint `inbox:<userId>` with a nonnegative bigint JSON
integer `revision`. Signed own-inbox receive authorization still comes from the
policy, not caller-supplied payload fields. The wrapper reconstructs only
`{ productId }` or `{ revision }`, strips all extra fields, and rejects signals
larger than 1024 UTF-8 bytes after reconstruction. Invalid shapes/topics/events
raise SQLSTATE `22023` without echoing payloads; a failed dispatch rolls back
its reservations, local sends, accounting, and processed-hint updates together.
Malformed hints may retry until bounded stale cleanup handles them; never widen
the wrapper or discard durable business facts to clear a bad hint.

## 4. Budget

1. Read the verified unused Free-plan message allowance.
   Verify organization-wide usage, egress, storage, active sockets, and all other
   senders first. Unknown allowance means zero budget, not an estimated refill.
2. Set the starting reserve, never above the unused allowance:

```sql
update app_realtime_control
   set remaining_message_budget = <verified-unused-allowance>,
       updated_at = clock_timestamp()
 where id = 1;
```

3. Keep `enabled=false` until the two-client smoke test passes.
4. The dispatcher locks the control row, reserves one send plus every unretired
   recipient lease, and shares a rolling 60-message/second window across stock
   and inbox. It claims at most 100 unlocked hints, preserves newer/skipped
   hints, and discards stale hints without sending. It never refills the budget.
   One socket per lease and no unrelated sender are required assumptions, not
    provider-wide abuse protection. Unknown fan-out blocks the hosted run.
5. Normal no-argument dispatch samples one effective database time after acquiring
   the control-row lock. Explicit `p_now` callers retain their supplied time for
   deterministic checks. Stale cleanup and fresh dispatch share one at-most-100-ID
   `SKIP LOCKED` claim, including while paused or unbudgeted. A stale backlog may
   require multiple ticks; no unbounded stale pre-pass runs under the control lock.

## 5. Two-client hosted smoke test

**NOT RUN by Task 14.** Obtain separate operator approval for an isolated hosted
environment, fixture writes, keys, settings, and a small capped message reserve.
Do not enable the application project to manufacture smoke-test evidence.
Positive credential issuance and Broadcast delivery cannot work while both
switches are disabled: an isolated verification window needs separate approval.
If that window or the dedicated-key trust path is unavailable, record BLOCKED
and leave normal traffic disabled. A local stub result does not waive this gate.

In Realtime Settings, verify **Allow public access is disabled**. RLS governs
private channels, not public-channel joins. Every application subscription uses
`config: { private: true }`. Do not assume that receive policies, a private send,
or lack of a public-channel test message disables public channels.

Use at most two simultaneous owned browser sessions, switching test identities
between guest, customer A, and customer B. Disconnect before switching identities
and use fresh backend-issued leases. Test authorization and positive delivery
with controlled minimal signals through the budgeted dispatcher, never by an
unaccounted direct `realtime.send` call. Record subscription status, publish
result, delivery evidence, fixture ownership, allowance, and cleanup without
keys or tokens. Absence of a message alone is not proof of rejection.

| Check | Expected |
|---|---|
| Guest subscribes to an admitted `stock:<id>` | receives a broadcast |
| Guest/customer subscribes to a non-admitted stock topic | authorization denied; no data |
| Customer A subscribes to `inbox:<A>` | receives own broadcast |
| Customer B subscribes to `inbox:<A>` | authorization denied; no data |
| Guest subscribes to any inbox; inactive/missing account joins own inbox | denied |
| Any client publishes Broadcast to a private channel | denied; no peer delivery |
| Any client tracks or receives Presence | denied |
| Any client joins public `stock:<id>` or `inbox:<id>`, including admitted topics | public join rejected |
| Guest requests credentials while disabled | `503` |
| Customer A requests credentials in approved isolated enabled window | `200`, `Cache-Control: no-store`, 300-second JWT |
| Guest/authenticated Data API reads/writes and raw Postgres Changes | denied independently of Broadcast success |

Only after every boundary/key/private-channel gate has recorded hosted evidence
may an operator approve normal activation. Verify the backend flag and the
allocated remaining allowance as well. This is not a Task 14 execution step:

```sql
update app_realtime_control
   set enabled = true, updated_at = clock_timestamp()
 where id = 1;
```

## 6. Pause and rollback

Pause sends and new credentials immediately:

```sql
update app_realtime_control
   set enabled = false, updated_at = clock_timestamp()
 where id = 1;
```

Also set `app.realtime.enabled=false` and restart the backend if the signing
path must stop. Existing receiver sockets may keep their receive permission
until their token expires (at most 300 seconds). REST reconciliation continues.

Rollback keeps data:

- Keep `event_outbox`, `realtime_signal_outbox`, `realtime_token_leases`, and
  `realtime_send_accounting`.
- Do not delete unresolved hints or unpublished order events.
- Do not drop or recreate `realtime.messages`.
- Record the pause time and the last accounting row for the incident review.

## 7. Secrets and logs

- Never log a bearer token, private key, publishable key, or customer PII.
- Never paste the signed JWT into a ticket.
- Rotate the signing key only through the approved operator process.

## 8. Reference and local evidence

- [Realtime authorization and private-only setting](https://supabase.com/docs/guides/realtime/authorization)
- [JWT signing keys and imported-key activation](https://supabase.com/docs/guides/auth/signing-keys)
- [Data API hardening](https://supabase.com/docs/guides/database/hardening-data-api)
- Local smoke: `./mvnw.cmd -q "-Dtest=NotificationRealtimeScriptSmokeTest" test`.
  Load `TEST_POSTGRES_*` from `.env` into the same PowerShell process; never print
  their values. The test uses `ExternalPostgresDatabase`, checks missing
  prerequisites, reapplies twice, exercises RLS under existing browser roles,
  checks `aclexplode` and effective function grants, and verifies its exact
  scratch database is gone. Missing test configuration is a skip, not a pass.
