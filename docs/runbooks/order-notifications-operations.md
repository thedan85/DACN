# Order notifications — operations runbook

Safe, read-first operations for the order-notification module. No host names,
credentials, tokens, private keys, or customer data appear here. Use the isolated
scratch project for every drill.

## 1. Retention windows and never-cleaned work

`NotificationRetentionPruner` runs independently of live delivery. It deletes only
these bounded, eligible rows:

| Category | Table | Eligible when | Window |
|---|---|---|---|
| Notification content (visible + soft-deleted, read or unread) | `notifications` | `coalesce(occurred_at, created_at) <= now - 90 days` | 90 days |
| Published outbox payloads | `event_outbox` | `published_at <= now - 30 days` **and** `quarantined_at is null` | 30 days |
| Completed email evidence | `email_deliveries` | `state in ('SENT','SKIPPED')` **and** `completed_at <= now - 90 days` | 90 days |
| Completed in-app receipts | `notification_event_receipts` | `completed_at <= now - 90 days` | 90 days |

Never automatically cleaned — no retention path touches these:

- `users.inbox_revision` (never reset or decremented).
- Unpublished outbox (`published_at is null`) and quarantined outbox
  (`quarantined_at is not null`).
- Email in `PENDING`, `IN_FLIGHT`, `UNKNOWN`, or `FAILED` state, including old rows.
- Incomplete receipts (`completed_at is null`), including old claims.
- Notification read/delete state on rows still inside the window; read state never
  protects a row past its window, and pruning never resets a revision.
- Dedup evidence via cascade. Receipts and outbox payloads are independent tables.
  Deleting a notification or outbox row never deletes a receipt.
- Any unresolved work used to hide a backlog. Do not widen a window or purge
  unresolved rows to make a gauge turn green.

The strict 30-day source-event processing window still applies to every path after
evidence is pruned. At or after `occurredAt + 30 days`, work is retained/quarantined
with no new notification, no revision/read/delete change, and no mail — even with
no dedup row remaining.

## 2. Scheduler and batch behavior

Properties (defaults):

- `app.notifications.retention-enabled` — `true`. Set false to disable the pruner.
- `app.notifications.retention-interval-ms` — `600000` (10 minutes, fixed delay).
- `app.notifications.retention-batch-size` — `1000`; clamped to `1..10000`.
- Per-statement query timeout: 2 seconds. `limit ? for update skip locked` per
  table, so a locked row is skipped and picked up on a later tick.

Behavior:

- Each of the four tables is pruned in its own batch; one failed batch logs one
  fixed code `code=RETENTION_BATCH_FAILED` and does not stop the other three.
- No exception message, throwable stack trace, event/user id, recipient address,
  topic, order id, or SQL parameter is ever logged.
- Retention runs while `app.notifications.delivery-enabled=false`. It is not
  coupled to delivery.
- In the `test` profile retention is disabled (H2 lacks the PostgreSQL tables).

Metrics (bounded, fixed tags only): `dacn.retention.deleted` and
`dacn.retention.failure` tagged `table` with one of four fixed values; gauges
`dacn.retention.backlog` (`table`), `dacn.retention.email.unresolved` (`channel`),
`dacn.retention.outbox.quarantined` (`table`), and
`dacn.retention.outbox.unpublished.oldest.age` (`table`, seconds). No per-record
tag is emitted. Actuator is not exposed unauthenticated and no monitoring vendor
or tracing deployment is added.

## 3. Read-only inspection SQL

Run against an approved read replica or the scratch drill database. Never paste
host values or secrets into tickets.

### Retention backlog by category

```sql
select 'notifications' as category, count(*) as eligible
  from notifications
 where coalesce(occurred_at, created_at) <= clock_timestamp() - interval '90 days'
union all
select 'event_outbox', count(*)
  from event_outbox
 where published_at <= clock_timestamp() - interval '30 days'
   and quarantined_at is null
union all
select 'email_deliveries', count(*)
  from email_deliveries
 where state in ('SENT','SKIPPED')
   and completed_at <= clock_timestamp() - interval '90 days'
union all
select 'notification_event_receipts', count(*)
  from notification_event_receipts
 where completed_at <= clock_timestamp() - interval '90 days';
```

### Oldest unpublished outbox age (backlog/age stop rule)

```sql
select count(*) as unpublished,
       coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0) as oldest_seconds
  from event_outbox
 where published_at is null and quarantined_at is null;
```

### Quarantined outbox

```sql
select count(*) as quarantined,
       coalesce(extract(epoch from (clock_timestamp() - max(quarantined_at))), 0) as newest_age_seconds
  from event_outbox
 where quarantined_at is not null;
```

### Email UNKNOWN/FAILED and expired IN_FLIGHT claims

```sql
select state, count(*) as rows,
       coalesce(extract(epoch from (clock_timestamp() - max(created_at))), 0) as newest_age_seconds
  from email_deliveries
 where state in ('UNKNOWN','FAILED')
 group by state;

select count(*) as expired_in_flight
  from email_deliveries
 where state = 'IN_FLIGHT'
   and completed_at is null
   and claim_expires_at is not null
   and claim_expires_at <= clock_timestamp();
```

### Incomplete receipts

```sql
select count(*) as incomplete,
       coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0) as oldest_seconds
  from notification_event_receipts
 where completed_at is null;
```

### Inbox revision / lag checks

```sql
select count(*) as users_with_revision
  from users
 where inbox_revision > 0;

select max(inbox_revision) as highest_revision from users;

-- Unpublished/unquarantined outbox lag proxies inbox-side lag.
select count(*) as pending_events,
       coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0) as oldest_seconds
  from event_outbox
 where published_at is null and quarantined_at is null;
```

## 4. Stop-and-alert rules

Alert; never auto-purge unresolved work to hide a backlog.

- **Outbox lag/backlog:** unpublished + unquarantined count rises over consecutive
  scrapes, or oldest unpublished age grows past the team's accepted lag budget.
  Stop replay and escalate; confirm relay/distributor health.
- **Email UNKNOWN / DLQ:** any `UNKNOWN` or `FAILED` row, or a DLQ entry, is a
  review item. Do not change its state to a completed value or delete it to clear
  the gauge.
- **Inbox lag:** pending-event age grows while revisions stop advancing. Investigate
  before widening any window.
- **Supabase Free-plan pressure:** storage or bandwidth approaching plan limits —
  pause non-essential load, stop drills, and escalate. Never force saturation.
- **Storage headroom before exhaustion:** alert while headroom still exists. Do not
  wait for exhaustion, and do not delete unresolved rows (unpublished outbox,
  quarantined outbox, UNKNOWN/FAILED/IN_FLIGHT email, incomplete receipts) to
  reclaim space. Only the four bounded windows above are automatic targets.

## 5. Approved manual-review path

Manual review is read-first and needs an explicit human decision per case:

1. **UNKNOWN/FAILED email:** inspect the row with read-only SQL above. Determine
   actual delivery outcome from an authoritative source. Do not flip state to
   `SENT` by assumption.
2. **DLQ:** list entries, correlate by correlation id, and confirm no live claim
   owns the delivery before any action.
3. **Quarantined outbox:** inspect `quarantined_at` and `last_error_code`; resolve
   the root cause first.
4. **Active claims:** see the reconciliation rules below.

Resend/replay always requires explicit approval. A replay can duplicate mail if the
original side effect actually succeeded. Never replay on a guess.

### Email pause reapply on restart

`EmailTransportPause` is an in-memory gate and clears on application restart. It is
also only present when `app.notifications.delivery-enabled=true`. Before or
immediately after every restart, re-apply the pause (or keep delivery disabled) and
verify no sender is active. Do not assume a previous pause survived.

### Active-claim DLQ reconciliation

Before any manual DLQ replay:

1. Check the claim and expiry for the delivery:

   ```sql
   select id, state, claim_token, claim_expires_at, completed_at
     from email_deliveries
    where event_id = /* approved event id */;
   ```

2. A claim is active when `state = 'IN_FLIGHT'`, `completed_at is null`, and
   `claim_expires_at > clock_timestamp()`. Never resend while an unexpired claim
   owns the delivery.
3. An expired claim (`claim_expires_at <= clock_timestamp()`) must be reconciled
   and the work quarantined/reclaimed through the approved path before replay.
4. Only after those checks, with explicit approval, may a replay proceed — and
   only inside the strict 30-day source-event window. Older work stays visible but
   does not resume side effects automatically.

## 6. Hosted application-project restrictions

- No destructive hosted SQL (delete/update/truncate/drop), schema/migration change,
  setting, key, or Realtime change without explicit approval.
- Use an isolated scratch project and an invocation-owned database for every drill.
  Drop only the scratch database this invocation created; never touch hosted data.
- Never record tokens, private keys, DB passwords, or customer data in logs,
  tickets, or this runbook.
