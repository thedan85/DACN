# Notification delivery operations runbook

This runbook covers order-event relay delivery, RabbitMQ outage recovery, DLQ
replay, age quarantine, Realtime pause, deployment networking, budget pressure,
and secret handling.

Delivery is disabled by default:
`app.notifications.delivery-enabled` reads
`NOTIFICATIONS_DELIVERY_ENABLED` and defaults to `false`. While it is false,
business writes still append `event_outbox` rows, but the relay, consumers,
topology declarations, and startup verifier are absent.

**A single broker node is not highly available.** Quorum queues on one node
provide queue semantics and at-least-once dead-lettering, not broker failover.

The exact broker topology is documented in
`docs/runbooks/rabbitmq-notification-topology.md`. The hosted Realtime setup,
signing-key gate, Data API lockdown, and budget activation procedure remain in
`docs/runbooks/supabase-realtime-setup.md`.

## 1. Control surface

| Setting | Environment variable | Default | Effect |
|---|---|---|---|
| `app.notifications.delivery-enabled` | `NOTIFICATIONS_DELIVERY_ENABLED` | `false` | Relay, listeners, topology declarations, and verifier |
| `app.notifications.relay-interval-ms` | `APP_NOTIFICATIONS_RELAY_INTERVAL_MS` | `500` | Relay polling delay |
| `app.notifications.rabbit.confirm-timeout` | `APP_NOTIFICATIONS_RABBIT_CONFIRM_TIMEOUT` | `5s` | Publisher confirm wait |
| `app.notifications.rabbit.management-url` | `RABBITMQ_MANAGEMENT_URL` | `http://localhost:15672` | Topology verifier management API |
| `app.notifications.rabbit.management-username` | `RABBITMQ_MANAGEMENT_USERNAME` | `guest` | Management API user |
| `app.notifications.rabbit.management-password` | `RABBITMQ_MANAGEMENT_PASSWORD` | `guest` | Management API password |
| `app.notifications.rabbit.expected-version-prefix` | `RABBITMQ_EXPECTED_VERSION` | `4.4` | Broker version prefix check |
| `spring.rabbitmq.host` | `RABBITMQ_HOST` | `localhost` | Broker AMQP host |
| `spring.rabbitmq.port` | `RABBITMQ_PORT` | `5672` | Broker AMQP port |
| `spring.rabbitmq.username` | `RABBITMQ_USERNAME` | `guest` | Broker AMQP user |
| `spring.rabbitmq.password` | `RABBITMQ_PASSWORD` | `guest` | Broker AMQP password |
| `spring.rabbitmq.dynamic` | `NOTIFICATIONS_DELIVERY_ENABLED` | `false` | RabbitAdmin declaration toggle |
| `spring.rabbitmq.publisher-confirm-type` | none | `correlated` | Publisher confirms |
| `spring.rabbitmq.publisher-returns` | none | `true` | Mandatory returns |
| `spring.rabbitmq.template.mandatory` | none | `true` | Unroutable detection |
| `spring.rabbitmq.listener.simple.acknowledge-mode` | none | `manual` | Consumer acknowledgement |
| `spring.rabbitmq.listener.simple.prefetch` | none | `10` | Consumer prefetch |
| `spring.rabbitmq.listener.simple.concurrency` | none | `1` | Consumer concurrency |
| `spring.rabbitmq.listener.simple.default-requeue-rejected` | none | `false` | No immediate requeue loop |
| `app.realtime.enabled` | `REALTIME_ENABLED` | `false` | Realtime credential signing path |
| `app.realtime.supabase-url` | `SUPABASE_URL` | blank | Hosted Realtime project URL |
| `app.realtime.signing-key-id` | `SUPABASE_REALTIME_SIGNING_KEY_ID` | blank | Imported ES256 key ID |
| `app.realtime.private-key` | `SUPABASE_REALTIME_PRIVATE_KEY` | blank | Backend-only PKCS#8 key; never log |
| `app.realtime.retention-enabled` | `REALTIME_RETENTION_ENABLED` | `true` | Lease, stale-hint, and accounting cleanup |
| `app.realtime.trusted-client-ip-header` | `REALTIME_TRUSTED_CLIENT_IP_HEADER` | blank | Trusted client IP header name |

`NOTIFICATIONS_DELIVERY_ENABLED=false` also sets
`spring.rabbitmq.dynamic=false`. The application therefore does not declare
topology while delivery is paused. Property changes require an application
restart because the beans are created conditionally at startup.

Guardrails for every procedure below:

- Never delete, truncate, or rewrite an unpublished `event_outbox` row.
- Never purge a retry queue, DLQ, or any unresolved broker work.
- Never replace a quorum queue with a classic queue.
- Never change dead-lettering to at-most-once or overflow to drop-head.
- Never change an event ID, `occurredAt`, `schemaVersion`, or order sequence to
  make work replayable.
- Never put keys, tokens, passwords, full event payloads, or customer PII in a
  log, ticket, chat, or metric label.

## 2. Outbox stalled

### Symptoms

- `dacn.outbox.pending.count` does not fall, or rises.
- `dacn.outbox.pending.oldest.age` rises.
- `dacn.outbox.publish.failure` rises.
- Main queue `messages_ready` or `messages_unacknowledged` does not move.
- Relay log lines such as `Outbox relay row <id> failed with
  <ExceptionSimpleName>` repeat.

`dacn.outbox.pending.count` counts only rows where `published_at is null and
quarantined_at is null`. Quarantined rows need the quarantine query in section
7. The oldest-pending-age gauge uses row `created_at`, not the event
`occurredAt`; the relay age guard uses `occurredAt` from the payload.

### Check oldest pending age

Use database time, not an operator workstation clock:

```sql
select
  count(*) as pending,
  min(created_at) as oldest_created_at,
  extract(epoch from (clock_timestamp() - min(created_at)))::bigint
    as oldest_age_seconds,
  count(*) filter (where available_at <= clock_timestamp()
                     and (lease_expires_at is null
                          or lease_expires_at < clock_timestamp())) as due_now,
  count(*) filter (where lease_expires_at > clock_timestamp()) as leased
from event_outbox
where published_at is null
  and quarantined_at is null;
```

Inspect the next due rows without selecting the payload:

```sql
select id, event_id, order_id, event_type, attempt_count,
       available_at, lease_expires_at, last_error_code
from event_outbox
where published_at is null
  and quarantined_at is null
order by available_at, id
limit 20;
```

### Check relay and broker health

- Relay: confirm the application process is running. Absence of `Outbox relay
  row` warnings is not proof of publish health; publisher failures are not
  logged on that path. Do not enable payload logging.
- Broker: `rabbitmq-diagnostics -q ping`, `rabbitmqctl status`, and
  `GET /api/overview` for listeners and queue totals. Check `disk_free_alarm`
  and `mem_alarm` with `rabbitmq-diagnostics -q alarms` or `GET /api/nodes`.
- Queue depth: read `messages_ready`, `messages_unacknowledged`, and
  `consumers` for `dacn.notifications.inapp` and
  `dacn.notifications.email`.
- The `rabbitTopology` health component reports DOWN when the broker or
  effective topology cannot be verified. If delivery was enabled at startup and
  verification failed, the application fails startup instead.
- The verifier failure codes are `BROKER_VERSION`, `FEATURE_FLAG`,
  `QUEUE_POLICY`, `BINDING`, and `VERIFICATION_TIMEOUT`.

### Interpret

| Observation | Meaning | Action |
|---|---|---|
| `pending > 0`, `due_now = 0` | Rows are in retry backoff | Check `last_error_code` and failure metrics; let the retry schedule run |
| `pending > 0`, `leased > 0`, `due_now > 0` | Relay holds active leases | Confirm the relay is alive; leases expire after 60 seconds |
| Expired leases, no progress | Relay crashed or is wedged | Restart only after broker topology verification; do not clear lease columns by hand |
| `last_error_code = PUBLISH_FAILED` | Publisher failed; reason is not persisted | Check broker health and topology; the relay always stores this bounded code |
| Quarantine count rising | Age or event validation failure | Go to section 7 |
| Queue depth flat, broker healthy, consumers present | Consumers are slow or blocked | Inspect consumer logs and database health; do not purge |

Relay claim size is 20 rows and the lease is 60 seconds. Publish retry delay
starts at 5 seconds and doubles to a 60-second cap. Do not edit `available_at`
to bypass this backoff.

### Stop and escalate

Stop and escalate when any of these is true:

- Oldest pending age is approaching or beyond 30 days.
- The broker is down and the backlog is growing.
- `disk_free_alarm` or `mem_alarm` is true.
- A queue has consumers but no progress for an unexplained period.
- `PUBLISH_FAILED` repeats after broker health and topology are restored.

Never resolve an outbox stall by deleting rows, clearing `quarantined_at`, or
marking rows published manually.

## 3. Publisher confirm timeout or unroutable return

### Symptoms

No relay warning is emitted for this path. Detect it from rising
`dacn.outbox.publish.failure`, a non-moving target queue, and rows retaining
`last_error_code=PUBLISH_FAILED`. The `Outbox relay row <id> failed with
<ExceptionSimpleName>` warning indicates a different failure. The relay does
not persist the detailed publisher reason code. The exception type carries one
of `PUBLISH_NACK`, `PUBLISH_UNROUTABLE`, `PUBLISH_TIMEOUT`, or
`BROKER_UNAVAILABLE`, but it is not stored in the outbox row.

Publisher settings in `application.yml`:

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

`app.notifications.rabbit.confirm-timeout` is `5s`.

A mandatory return proves that **no** queue accepted the message. It does not
prove that both main queues received it: if one binding is missing, the message
can route to the other queue and still be acknowledged. Always verify both main
bindings with the topology runbook.

### Procedure

1. If failures repeat, pause the relay:
   set `NOTIFICATIONS_DELIVERY_ENABLED=false` and restart the application.
   Business writes continue to append outbox rows.
2. Check broker health and the full topology: three exchanges with type,
   durability, and auto-delete, ten queues, twelve bindings, `stream_queue`
   feature flag, queue type and effective arguments.
3. Restore the missing binding, exchange, queue, or destination. Do not publish
   the outbox row manually and do not change its routing key.
4. After topology verification passes, restart with
   `NOTIFICATIONS_DELIVERY_ENABLED=true`.
5. Let the relay retry and the age guard decide. The relay retries after 5
   seconds, doubling to a 60-second cap. At or after `occurredAt + 30 days`, it
   quarantines the row instead of publishing.

### Stop and escalate

- Stop if the unroutable return persists after the topology is restored.
- Stop if a queue type or effective policy differs from the declared contract.
- Stop if confirm timeouts continue after broker health is restored.
- Do not mark the row published. A timeout may still have been accepted by the
  broker; duplicate delivery is expected and consumers deduplicate.

## 4. Broker outage

Business writes do not depend on the broker. `OrderEventOutbox.append` runs with
`Propagation.MANDATORY` in the caller's database transaction, and the broker is
not part of that transaction. Business flows that append outbox rows continue to
commit during a broker outage; a broker outage must not roll back a committed
business change.

While delivery is enabled and the broker is unavailable:

- Relay publish attempts fail and rows remain in the outbox with retry backoff.
- The `rabbitTopology` health component reports DOWN while verification fails.
- The application may remain running, but it is not ready for delivery work.

### Recovery order

1. Restore the broker and its persistent volume.
2. Verify the management API is reachable and the broker version prefix
   matches `app.notifications.rabbit.expected-version-prefix`.
3. Verify the `stream_queue` feature flag, all three exchanges, all ten quorum
   queues, and all twelve bindings. Use
   `docs/runbooks/rabbitmq-notification-topology.md`.
4. Start or restart the application with
   `NOTIFICATIONS_DELIVERY_ENABLED=true`. The startup verifier must pass.
5. Confirm the relay is running and each enabled channel has its expected
   consumer. This module ships only the reusable consumer support; if no channel
   consumer is deployed yet, queue depth will grow by design. Do not purge it.
6. Observe the outbox pending count and oldest pending age.

Do not start the relay or listeners before topology verification. If the
verifier fails, leave delivery disabled and fix the broker. Do not delete a
queue to force a redeclare.

If the broker lost definitions, verify them; do not assume recovery recreated
them. Durable queues and persistent messages require the persistent volume to
survive. A single node still has no failover.

If the outage exceeds 30 days, do not replay the backlog automatically. The age
guard will quarantine rows as `EVENT_TOO_OLD`; use section 7 for operator
reconciliation.

## 5. Quorum policy downgrade or queue-type change

A queue-type change is not an in-place operation. Treat any of these as a
delivery incident:

- Management API `type` is not `quorum`.
- `durable` is false, `auto_delete` is true, or `exclusive` is true.
- `dead-letter-strategy` is not `at-least-once`.
- `overflow` is not `reject-publish`.
- `dead-letter-exchange` or `dead-letter-routing-key` is missing or wrong.
- Main queue `delivery-limit` is missing or not `4`.
- Retry TTL is missing or wrong.
- `expires` appears on any notification queue.
- A policy overrides the declared effective arguments.

### Procedure

1. Pause delivery: `NOTIFICATIONS_DELIVERY_ENABLED=false` and restart. This
   stops the relay and listeners but does not delete queued work.
2. Capture the management API queue object and policy list for the incident
   record. Do not print credentials.
3. Do not delete, purge, or recreate the queue. Deletion destroys retained
   messages and any unpublished delivery work.
4. Escalate to the broker owner. A drain or migration needs written approval, a
   copy/confirm plan, and a rollback plan.
5. Migrate only after every retained message is accounted for. Never silently
   replace a quorum queue with a classic queue. Never switch to drop-head or
   at-most-once dead-lettering to clear a backlog.
6. After the approved migration, verify the full topology before re-enabling
   delivery.

If the application refuses to start because of `QUEUE_POLICY` or `BINDING`, that
is the correct behavior. Do not bypass the verifier.

## 6. DLQ replay

The two terminal queues are `dacn.notifications.inapp.dlq` and
`dacn.notifications.email.dlq`. There is no replay endpoint, scheduled job, or
operator command in this repository. Use only an approved replay tool that
preserves event identity. This runbook provides non-destructive inspection
commands and the required invariants; it does not authorize a bulk replay. The
management API peek requeues inspected messages and can reorder delivery; it is
not a read-only operation.

### Preconditions

- Incident owner approval and a written replay scope.
- The original failure is fixed and the channel consumer is healthy.
- The event is strictly before `occurredAt + 30 days`, measured with fresh
  database time.
- The event time is not in the future.
- For email, the prior delivery state is known. A replay can duplicate an
  email; check for `UNKNOWN` or already accepted delivery first.
- Deduplication evidence is intact. Do not replay merely because completed
  evidence was pruned.

### Peek at a DLQ (requeue mode)

Use the management URL and credentials from the topology runbook. Do not print
the password. This request peeks with requeue and can disturb delivery order, so
use a small count, pause any channel-specific consumer, and run it only during
an approved incident.

```bash
curl --silent --show-error --fail \
  --user "$RABBITMQ_MANAGEMENT_USERNAME:$RABBITMQ_MANAGEMENT_PASSWORD" \
  -H 'content-type: application/json' \
  -X POST \
  --data '{"count":10,"ackmode":"ack_requeue_true","encoding":"auto","truncate":50000}' \
  "${RABBITMQ_MANAGEMENT_URL%/}/api/queues/%2F/dacn.notifications.email.dlq/get" \
  | jq -r '.[]
      | (.payload | try fromjson catch {}) as $p
      | [.properties.message_id,
         (.properties.timestamp // ""),
         (.properties.headers["x-quarantine-reason"] // ""),
         ($p.eventId // ""),
         ($p.occurredAt // ""),
         ($p.eventType // "")]
      | @tsv'
```

This prints message ID, broker timestamp, quarantine reason, event ID,
`occurredAt`, and event type. Do not print the full body or headers; they can
carry customer and correlation data.

### Verify event age with database time

Set the observed `occurredAt` value (replace the example literal):

```sql
\set occurred_at '2026-10-20T12:00:00Z'
select
  clock_timestamp() as database_now,
  (clock_timestamp() < :'occurred_at'::timestamptz + interval '30 days')
    as within_window,
  (:'occurred_at'::timestamptz > clock_timestamp()) as future;
```

Replay only when `within_window` is true and `future` is false. At equality
with `occurredAt + 30 days`, the event is too old; do not replay it.

### Replay invariants

The approved tool must:

- Publish the original body unchanged.
- Preserve `eventId`, `occurredAt`, `schemaVersion`, `orderSequence`,
  `eventType`, and `correlationId`.
- Preserve the broker `message_id` (the event ID), the `timestamp` property
  (the event occurrence time), and the original headers needed for
  deduplication.
- Use persistent delivery and wait for publisher confirmation and no mandatory
  return before acknowledging or removing the DLQ message.
- Replay one message or a small bounded batch, verifying the consumer between
  batches.
- Route to the failed channel's main queue, or an approved route that
  preserves the same channel and identity. Do not publish to the shared topic
  exchange unless both channels are intentionally being replayed.
- Stop on the first failed transfer. Leave the DLQ message in place.

Never generate a new event ID, set `occurredAt` to the current time, rewrite the
payload, or strip the original correlation and dedup metadata. Do not remove a
DLQ message until the replay transfer is confirmed.

### Stop and escalate

- `EVENT_TOO_OLD`, `EVENT_FUTURE`, `EVENT_INVALID`, or an unsupported schema
  version: do not replay. Retain for reconciliation.
- Email delivery state `UNKNOWN`: do not automatically resend. Escalate.
- Missing original headers or a body that cannot be parsed: stop.
- Any transfer failure: stop; the DLQ message must remain.
- Any request to bulk-purge the DLQ or replay with a new event ID: refuse and
  escalate.

## 7. Age quarantine

The relay quarantines an outbox row when the event is not inside the delivery
window. The reusable `NotificationConsumerSupport.rejectIfOutsideWindow`
applies the same guard when a production channel consumer is added; no
production listener is deployed in this module.

| Code | Meaning |
|---|---|
| `EVENT_TOO_OLD` | `clock_timestamp() >= occurredAt + 30 days` |
| `EVENT_FUTURE` | `occurredAt` is after fresh database time |
| `EVENT_INVALID` | Payload cannot be decoded or validated |

### Inspect

```sql
select last_error_code, count(*) as rows,
       min(quarantined_at) as first_seen,
       max(quarantined_at) as last_seen
from event_outbox
where quarantined_at is not null
group by last_error_code
order by rows desc;
```

```sql
select id, event_id, order_id, event_type, attempt_count,
       last_error_code, quarantined_at, created_at,
       payload->>'occurredAt' as occurred_at
from event_outbox
where quarantined_at is not null
order by quarantined_at desc
limit 50;
```

Do not select the full payload for a ticket. `event_id`, `order_id`, type,
times, and the bounded error code are enough for triage.

### Rules

- Quarantined rows are retained. They are excluded from the normal due-claim
  query and from `dacn.outbox.pending.count`.
- Do not clear `quarantined_at`, do not set `published_at`, do not delete the
  row, and do not rewrite `occurredAt`.
- Do not resend an over-age confirmation, recreate a deleted inbox entry, or
  send obsolete email. Age quarantine is intentional.
- Operator reconciliation only: compare the order, payment, notification, and
  delivery records to decide whether the business outcome is already complete.
  If a new notice is required, use an approved business process that creates a
  new event with a new occurrence time. Do not revive the old event.
- `EVENT_INVALID` is a code or schema defect. Capture safe metadata and
  escalate; do not log the body.

A spike in `EVENT_TOO_OLD` after a long outage is expected. It is not a reason
to bypass the age guard.

## 8. Realtime pause

This is the application-side kill switch. Hosted activation, signing keys, and
budget allocation remain in `docs/runbooks/supabase-realtime-setup.md`.

### SQL kill switch

```sql
update app_realtime_control
   set enabled = false,
       updated_at = clock_timestamp()
 where id = 1;
```

Verify:

```sql
select enabled, remaining_message_budget, updated_at
from app_realtime_control
where id = 1;
```

Effect:

- New `POST /api/v1/realtime/credentials` requests return `503` while disabled
  or while the remaining message budget is zero.
- New hint insertion stops because `app_realtime_enqueue_signal` checks
  `enabled` and the remaining budget.
- Existing sockets may retain receive permission until their token expires, at
  most **300 seconds**. The token TTL is fixed at 300 seconds.
- REST reconciliation continues. Clients must fall back to the existing REST
  inbox and stock reads; do not tell clients that live delivery is healthy while
  the switch is off.
- Hints, leases, and accounting rows are not manually purged. Normal retention
  may remove expired leases, stale hints, and old accounting rows; that is not an
  incident cleanup.

To stop the signing path as well, set `REALTIME_ENABLED=false`
(`app.realtime.enabled=false`) and restart the backend. Do not change or rotate
`SUPABASE_REALTIME_SIGNING_KEY_ID` or `SUPABASE_REALTIME_PRIVATE_KEY` as part of
a pause. Keep `REALTIME_RETENTION_ENABLED=true` so expired leases and accounting
rows are pruned normally.

Cross-reference: pause and rollback behavior is also described in
`docs/runbooks/supabase-realtime-setup.md` section 6.

## 9. Deployment networking

The application binds to loopback:

```yaml
server:
  address: 127.0.0.1
  forward-headers-strategy: framework
```

The Cloudflare Tunnel connects over loopback. Never expose the Spring port
directly.

Behind the Cloudflare tunnel, set:

```text
REALTIME_TRUSTED_CLIENT_IP_HEADER=CF-Connecting-IP
```

This sets `app.realtime.trusted-client-ip-header`. `TrustedClientIpResolver`
uses that single header only when its value is a valid single IPv4 or IPv6
literal of at most 64 characters. It does not parse comma-separated lists,
`X-Forwarded-For`, or `Forwarded` by default. A blank or malformed value falls
back to `request.getRemoteAddr()`.

Confirm the Cloudflare tunnel overwrites `CF-Connecting-IP` on every request.
If the tunnel passes a client-supplied value through, an attacker can choose
the rate-limit identity. Do not set the property to `X-Forwarded-For`.

A blank `REALTIME_TRUSTED_CLIENT_IP_HEADER` plus
`server.forward-headers-strategy: framework` is **local-only** deployment
behavior. It is not spoof-resistant in a proxied deployment, because the
framework strategy can rewrite `request.getRemoteAddr()` from forwarding
headers before controller code runs.

Verification is a deployment gate, not a local unit test: from an external
client, send a forged `CF-Connecting-IP` and `X-Forwarded-For` and confirm the
effective rate-limit identity is the address Cloudflare observed, not the
forged value. Do not print bearer tokens or response bodies during this check.
This runbook does not perform hosted calls.

## 10. Budget and storage pressure

### Realtime budget

Signals:

- `app_realtime_control.remaining_message_budget` is zero or close to zero.
- Supabase Free-plan usage, egress, storage, or active sockets approach the
  verified allowance.
- Credential requests return `503` because the budget is exhausted.

Action:

1. Apply the SQL kill switch in section 8.
2. Keep `REALTIME_ENABLED=false` if the signing path must also stop.
3. Let REST reconciliation carry the degraded experience.
4. Do not upgrade the plan, buy message packs, add paid resources, or raise
   `remaining_message_budget` above the verified unused allowance.
5. Do not purge `realtime_signal_outbox`, `realtime_token_leases`, or
   `realtime_send_accounting` to hide a budget problem.
6. Escalate for an approved capacity decision. Unknown allowance means zero
   budget, not an estimated refill.

The dispatcher never refills the budget automatically. Budget activation and
the Free-plan preflight remain in
`docs/runbooks/supabase-realtime-setup.md` section 4.

### Broker storage

Signals:

- `disk_free_alarm=true` or `mem_alarm=true`.
- Sustained ready or unacknowledged growth with consumers down.
- Broker disk free space approaches the configured `disk_free_limit`.

Action:

1. Set `NOTIFICATIONS_DELIVERY_ENABLED=false` and restart. Business writes
   continue to the outbox.
2. Free disk space or expand the approved volume.
3. Do not disable the disk alarm, raise `disk_free_limit`, purge a queue, or
   delete a queue to force publishes.
4. Never delete unresolved outbox rows, retry messages, or DLQ messages to
   reclaim space.
5. Escalate if the backlog cannot drain inside the 30-day delivery window.

## 11. Secrets and logs

Never log, paste, or attach:

- `RABBITMQ_MANAGEMENT_PASSWORD`, `RABBITMQ_PASSWORD`, or AMQP connection URIs
  containing credentials.
- `SUPABASE_REALTIME_PRIVATE_KEY`, `SUPABASE_REALTIME_SIGNING_KEY_ID`, or a
  signed Realtime JWT.
- `JWT_SECRET`, access tokens, refresh tokens, or cookies.
- SMTP credentials or full SMTP transcripts.
- Full order-event payloads, DLQ bodies, email addresses, recipient names,
  delivery addresses, or customer identifiers.

Safe diagnostics are bounded: event ID, order ID, channel, event type,
attempt count, bounded reason code, queue name, and timestamps. Do not run
commands with `set -x`. Do not use `curl --verbose` with management
credentials. Rotate a secret only through the approved operator process.

## 12. Read-only verification summary

For a delivery incident, the minimum evidence set is:

```sql
select count(*) as pending,
       min(created_at) as oldest_created_at,
       extract(epoch from (clock_timestamp() - min(created_at)))::bigint
         as oldest_age_seconds
from event_outbox
where published_at is null and quarantined_at is null;
```

```sql
select last_error_code, count(*) as rows
from event_outbox
where quarantined_at is not null
group by last_error_code;
```

Plus:

- `GET /api/overview` for broker version, listeners, and queue totals;
  `GET /api/nodes` for `disk_free_alarm` and `mem_alarm`.
- Queue `type`, `durable`, `auto_delete`, `exclusive`, `arguments`,
  `effective_policy_definition`, `messages_ready`,
  `messages_unacknowledged`, and `consumers` for both channels.
- All twelve bindings.
- The `rabbitTopology` health component and the startup verifier result.
- The application log lines for bounded relay and dispatch failures.

Never collect a full payload, a DLQ body, a token, or a password as evidence.

## 13. Sources

- `src/main/java/com/project/dacn/messaging/outbox/OrderEventRelay.java`
- `src/main/java/com/project/dacn/messaging/outbox/OutboxRelayRepository.java`
- `src/main/java/com/project/dacn/messaging/outbox/OutboxTelemetry.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitEventPublisher.java`
- `src/main/java/com/project/dacn/messaging/rabbit/consumer/OrderEventAgeGuard.java`
- `src/main/java/com/project/dacn/messaging/rabbit/consumer/NotificationConsumerSupport.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopologyVerifier.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopologyHealthIndicator.java`
- `src/main/java/com/project/dacn/security/TrustedClientIpResolver.java`
- `src/main/java/com/project/dacn/integration/realtime/RealtimeAdmissionService.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V33__event_foundation.sql`
- `docs/runbooks/rabbitmq-notification-topology.md`
- `docs/runbooks/supabase-realtime-setup.md`
