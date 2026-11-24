# RabbitMQ notification topology runbook

This runbook documents the broker topology declared by
`RabbitTopology` and `RabbitTopologyConfig`. It is an operator reference, not a
migration plan.

Delivery is disabled by default: `app.notifications.delivery-enabled` reads
`NOTIFICATIONS_DELIVERY_ENABLED` and defaults to `false`. While it is false,
the relay, listeners, topology declarations, and startup verifier are absent,
and `spring.rabbitmq.dynamic` is also false. Business writes continue to append
`event_outbox` rows.

All names below are in vhost `/`. The startup verifier reads only vhost `/`.
A single broker node is **not** highly available. Quorum queues on one node
provide quorum queue semantics and at-least-once dead-lettering, not broker
failover.

## 1. Exchanges

| Name | Type | Durable | Auto-delete | Purpose |
|---|---|---|---|---|
| `dacn.order.events` | `topic` | true | false | Order lifecycle events |
| `dacn.order.events.retry` | `direct` | true | false | Delayed retry routing |
| `dacn.order.events.dlx` | `direct` | true | false | Terminal dead-letter routing |

No alternate exchange, federation, or shovel is declared by the application.

## 2. Queues

All ten queues are durable, non-auto-delete, non-exclusive **quorum** queues.
No queue is declared with `x-queue-type=stream`, and no stream queue is used.
The `stream_queue` broker feature flag is separate and is covered in section 3.

### Main queues

| Queue | Type | Purpose |
|---|---|---|
| `dacn.notifications.inapp` | quorum | In-app notification work |
| `dacn.notifications.email` | quorum | Email notification work |

Main queue arguments:

| Argument | Value |
|---|---|
| `x-queue-type` | `quorum` |
| `x-dead-letter-strategy` | `at-least-once` |
| `x-overflow` | `reject-publish` |
| `x-dead-letter-exchange` | `dacn.order.events.dlx` |
| `x-dead-letter-routing-key` | `<main queue>.dlq` |
| `x-delivery-limit` | `4` |

There is no `x-message-ttl` and no `x-expires` on a main queue.

The declaration sets `x-queue-type=quorum`. The management API normally reports
this as `type=quorum`; do not require an `x-queue-type` key in the management
API `arguments` object.

`x-delivery-limit=4` is the quorum queue delivery limit on the main queues. It
is separate from the three application-level delayed retries described in
section 5.

### Retry queues

| Queue | TTL |
|---|---|
| `dacn.notifications.inapp.retry.10s` | `10000` ms (10 s) |
| `dacn.notifications.inapp.retry.30s` | `30000` ms (30 s) |
| `dacn.notifications.inapp.retry.120s` | `120000` ms (120 s) |
| `dacn.notifications.email.retry.10s` | `10000` ms (10 s) |
| `dacn.notifications.email.retry.30s` | `30000` ms (30 s) |
| `dacn.notifications.email.retry.120s` | `120000` ms (120 s) |

Retry queue arguments:

| Argument | Value |
|---|---|
| `x-queue-type` | `quorum` |
| `x-message-ttl` | the exact TTL in the table above |
| `x-dead-letter-strategy` | `at-least-once` |
| `x-overflow` | `reject-publish` |
| `x-dead-letter-exchange` | `dacn.order.events.retry` |
| `x-dead-letter-routing-key` | the matching main queue name |

There is no `x-expires` and no `x-delivery-limit` on a retry queue.

### Dead-letter queues

| Queue | Type | Purpose |
|---|---|---|
| `dacn.notifications.inapp.dlq` | quorum | Terminal in-app quarantine |
| `dacn.notifications.email.dlq` | quorum | Terminal email quarantine |

DLQs are durable quorum queues with no TTL, no expiration, no dead-letter
exchange, and no dead-letter routing key. The application has no automatic
consumer for a DLQ. DLQ work is retained until an approved operator action.

## 3. Routing pattern, bindings, and feature flags

The topic exchange binding pattern is exactly:

```text
order.lifecycle.#
```

`OrderEventType.routingKey()` publishes `order.lifecycle.<lowercase enum>`, for
example `order.lifecycle.order_placed` and `order.lifecycle.payment_failed`.

The application declares exactly these twelve bindings:

| Source exchange | Destination | Routing key | Count |
|---|---|---|---|
| `dacn.order.events` | `dacn.notifications.inapp` | `order.lifecycle.#` | 1 |
| `dacn.order.events` | `dacn.notifications.email` | `order.lifecycle.#` | 1 |
| `dacn.order.events.retry` | `dacn.notifications.inapp` | `dacn.notifications.inapp` | 1 |
| `dacn.order.events.retry` | `dacn.notifications.email` | `dacn.notifications.email` | 1 |
| `dacn.order.events.retry` | each retry queue | the exact retry queue name | 6 |
| `dacn.order.events.dlx` | each DLQ | the exact DLQ name | 2 |

The retry exchange serves two directions:

- A consumer sends a failed message to `dacn.order.events.retry` with the
  retry queue name as the routing key.
- When the retry TTL expires, the retry queue dead-letters to
  `dacn.order.events.retry` with the matching main queue name as the routing
  key, returning the message to the main queue.

`RabbitTopologyVerifier` checks the broker feature flag `stream_queue` and
requires state `enabled`. The queues themselves remain quorum queues; this is
not a stream queue deployment. The verifier also reads
`app.notifications.rabbit.expected-version-prefix` (`RABBITMQ_EXPECTED_VERSION`,
default `4.4`) and compares it with the prefix of `rabbitmq_version` from
`GET /api/overview`. A blank prefix skips the version comparison, so keep it set
in deployed environments.

Do not enable or disable broker feature flags on a live cluster without
approval. RabbitMQ feature flags can be irreversible.

## 4. Expected policies and effective arguments

The application declares **no RabbitMQ policies**. It supplies queue arguments
directly. The verifier merges the management API `arguments` object with
`effective_policy_definition` before comparing values, because queue arguments
use `x-` names and policy definitions normally do not.

Expected effective policy state:

- No application-owned policy targets `dacn.order.events`,
  `dacn.order.events.retry`, `dacn.order.events.dlx`, or any
  `dacn.notifications.*` queue.
- A platform policy is acceptable only if it preserves every effective value
  in section 2. A policy that changes `dead-letter-strategy` to `at-most-once`,
  changes `overflow` to `drop-head`, adds `expires`, or changes a TTL fails the
  verifier or violates the delivery contract.
- Main queues and DLQs must have no `message-ttl`.
- Retry queues must have exactly `10000`, `30000`, or `120000` ms
  `message-ttl`, matched to their queue name.
- `expires` must be absent on every notification queue.

The verifier failure code is `QUEUE_POLICY` with the queue name when an
effective value differs. `BINDING` means at least one of the twelve bindings is
missing. `FEATURE_FLAG` means `stream_queue` is not enabled.
`BROKER_VERSION` means the version prefix does not match.
`VERIFICATION_TIMEOUT` means the 20-second startup budget expired.

## 5. Delivery and dead-letter flow

Module 1 provides the reusable `NotificationConsumerSupport`, retry publisher,
age guard, and channel enum. It does not declare a production listener for
either main queue. Later notification modules attach the channel consumers.
Until a channel consumer is deployed, that queue retains published events by
design; do not purge it.

1. The relay publishes an order event to `dacn.order.events` with the event
   routing key. Both main queues receive it because both bind
   `order.lifecycle.#`.
2. A consumer that hits a transient failure publishes the original message to
   `dacn.order.events.retry` with the retry queue name, sets the
   `x-retry-attempt` header, waits for publisher confirmation and no mandatory
   return, then acknowledges the original.
3. The retry queue TTL expires. The message dead-letters to
   `dacn.order.events.retry` with the main queue name and returns to the main
   queue.
4. The application allows the initial delivery plus at most three delayed
   retries: 10 s, 30 s, and 120 s. The next transient failure goes to the
   channel DLQ with `x-quarantine-reason=RETRIES_EXHAUSTED`.
5. A permanent failure, invalid schema, future event time, or event at or past
   `occurredAt + 30 days` is published to `dacn.order.events.dlx` with the
   channel DLQ routing key, then acknowledged only after the confirmed
   transfer.
6. A main queue message that reaches `x-delivery-limit=4` is dead-lettered by
   the broker through the same DLX route. This is a broker safety net; it is
   not the application retry count.
7. A DLQ has no automatic expiry and no consumer. Retain it.

`at-least-once` dead-lettering means the source quorum queue retains an
expired or dead-lettered message until the destination queues confirm
acceptance. A missing binding, missing exchange, or rejecting destination must
leave the source work recoverable. Duplicate forwarding remains possible;
consumers deduplicate by event identity. Never change this to at-most-once
dead-lettering to clear a backlog.

The listener configuration is manual acknowledgement, prefetch `10`,
concurrency `1`, and `default-requeue-rejected=false`. Do not add immediate
requeue loops.

## 6. Management API verification

Use the configured values from `application.yml`:

- `app.notifications.rabbit.management-url` (`RABBITMQ_MANAGEMENT_URL`, default
  `http://localhost:15672`)
- `app.notifications.rabbit.management-username`
  (`RABBITMQ_MANAGEMENT_USERNAME`)
- `app.notifications.rabbit.management-password`
  (`RABBITMQ_MANAGEMENT_PASSWORD`)
- `app.notifications.rabbit.expected-version-prefix`
  (`RABBITMQ_EXPECTED_VERSION`, default `4.4`)

Run these from an approved admin host. Do not use `set -x`, do not use
`curl --verbose` with credentials, and never paste the password or an
`Authorization` header into a ticket. The examples expand the password into the
local `curl` process arguments; if that is not acceptable on the host, use the
approved credential helper instead.

```bash
MGMT="${RABBITMQ_MANAGEMENT_URL%/}"

mgmt() {
  curl --silent --show-error --fail \
    --user "$RABBITMQ_MANAGEMENT_USERNAME:$RABBITMQ_MANAGEMENT_PASSWORD" \
    "$MGMT$1"
}
```

### 6.1 Version prefix

```bash
version="$(mgmt /api/overview | jq -r '.rabbitmq_version')"
prefix="${RABBITMQ_EXPECTED_VERSION:-4.4}"
case "$version" in
  "$prefix"*) printf 'broker version ok: %s\n' "$version" ;;
  *) printf 'broker version mismatch: %s (expected prefix %s)\n' "$version" "$prefix" >&2; exit 1 ;;
esac
```

`GET /api/nodes` shows `disk_free_alarm` and `mem_alarm`; `GET /api/overview`
shows listener state. Those fields are safe to print; credentials are not in
the response.

### 6.2 Exchanges

The startup verifier checks the version, feature flag, queues, and bindings,
not exchange properties. Check all three exchanges explicitly:

```bash
while read -r exchange type; do
  mgmt "/api/exchanges/%2F/$exchange" \
    | jq -e --arg t "$type" \
        '.type == $t and .durable == true and .auto_delete == false' \
    >/dev/null \
    || { printf 'exchange contract failed: %s\n' "$exchange" >&2; exit 1; }
done <<'EOF'
dacn.order.events topic
dacn.order.events.retry direct
dacn.order.events.dlx direct
EOF
```

Expected: every check passes. A wrong exchange type can leave bindings present
while publishes become unroutable.

### 6.3 Feature flags

```bash
mgmt /api/feature-flags \
  | jq -r '.[] | select(.name == "stream_queue") | "\(.name)=\(.state)"'
```

Expected output: `stream_queue=enabled`.

### 6.4 Queues and effective arguments

```bash
queues=(
  dacn.notifications.inapp
  dacn.notifications.email
  dacn.notifications.inapp.retry.10s
  dacn.notifications.inapp.retry.30s
  dacn.notifications.inapp.retry.120s
  dacn.notifications.email.retry.10s
  dacn.notifications.email.retry.30s
  dacn.notifications.email.retry.120s
  dacn.notifications.inapp.dlq
  dacn.notifications.email.dlq
)

for q in "${queues[@]}"; do
  printf '%s\n' "--- $q"
  mgmt "/api/queues/%2F/$q" \
    | jq '{name, type, durable, auto_delete, exclusive, arguments, effective_policy_definition}'
done
```

For every queue, require `type` equal to `quorum`, `durable` true,
`auto_delete` false, and `exclusive` false. Then compare `arguments` and
`effective_policy_definition` with section 2. Remember that management API
queue arguments include the `x-` prefix while policy definitions usually omit
it.

A compact type/durability check:

```bash
for q in "${queues[@]}"; do
  mgmt "/api/queues/%2F/$q" \
    | jq -e '.type == "quorum" and .durable == true and .auto_delete == false and .exclusive == false' \
    >/dev/null \
    || { printf 'queue contract failed: %s\n' "$q" >&2; exit 1; }
done
```

### 6.5 Bindings

```bash
mgmt /api/bindings \
  | jq -r '.[]
      | select(.vhost == "/" and (.source | startswith("dacn.")))
      | [.source, .destination, .routing_key]
      | @tsv' \
  | sort
```

Expected rows, after sorting:

```text
dacn.order.events	dacn.notifications.email	order.lifecycle.#
dacn.order.events	dacn.notifications.inapp	order.lifecycle.#
dacn.order.events.dlx	dacn.notifications.email.dlq	dacn.notifications.email.dlq
dacn.order.events.dlx	dacn.notifications.inapp.dlq	dacn.notifications.inapp.dlq
dacn.order.events.retry	dacn.notifications.email	dacn.notifications.email
dacn.order.events.retry	dacn.notifications.email.retry.10s	dacn.notifications.email.retry.10s
dacn.order.events.retry	dacn.notifications.email.retry.120s	dacn.notifications.email.retry.120s
dacn.order.events.retry	dacn.notifications.email.retry.30s	dacn.notifications.email.retry.30s
dacn.order.events.retry	dacn.notifications.inapp	dacn.notifications.inapp
dacn.order.events.retry	dacn.notifications.inapp.retry.10s	dacn.notifications.inapp.retry.10s
dacn.order.events.retry	dacn.notifications.inapp.retry.120s	dacn.notifications.inapp.retry.120s
dacn.order.events.retry	dacn.notifications.inapp.retry.30s	dacn.notifications.inapp.retry.30s
```

Both main queue bindings to `dacn.order.events` are required. A publisher
confirmation alone does not prove both bindings: if one main binding is
missing, the message can still route to the other queue and be acknowledged.

### 6.6 Policies

```bash
mgmt /api/policies \
  | jq -r '.[]
      | select(.vhost == "/")
      | [.name, .pattern, (.definition | tostring)]
      | @tsv'
```

Expected: no application-owned notification policy. If a policy exists, inspect
its `pattern`, `priority`, and effective values. A policy with a higher
priority can override the queue arguments the verifier reads.

### 6.7 `rabbitmqctl` alternatives

Run on the broker host, read-only:

```bash
rabbitmqctl list_exchanges -p / name type durable auto_delete
rabbitmqctl list_queues -p / name type durable auto_delete exclusive arguments \
  messages_ready messages_unacknowledged consumers
rabbitmqctl list_bindings -p / source_name destination_name routing_key
rabbitmqctl list_policies -p /
rabbitmqctl list_feature_flags
rabbitmqctl version
rabbitmq-diagnostics -q ping
```

Check quorum membership for each queue:

```bash
rabbitmq-queues quorum_status dacn.notifications.inapp
rabbitmq-queues quorum_status dacn.notifications.email
rabbitmq-queues quorum_status dacn.notifications.inapp.dlq
rabbitmq-queues quorum_status dacn.notifications.email.dlq
```

Repeat for the retry queues. On a single node there is one member; that is
expected and is not HA.

## 7. Readiness

`RabbitTopologyHealthIndicator` contributes the health component named
`rabbitTopology`. Use it as the delivery readiness signal where the deployment
exposes health details.

- Delivery disabled: the component is UP with detail `delivery=disabled`.
- Delivery enabled: the component runs `RabbitTopologyVerifier.verify()` and
  caches the result for five seconds.
- Startup runs at most six verification attempts inside a 20-second budget. A
  failure stops startup with a bounded `TopologyVerificationException` code and
  optional queue name.

Do not disable or bypass the verifier to get a pod ready. Fix the broker
topology or leave delivery disabled.

## 8. Stop rules for backlog and storage growth

Stop the producers or delivery path first, then investigate. Never delete
unresolved work.

1. Set `NOTIFICATIONS_DELIVERY_ENABLED=false` and restart the application. This
   disables the relay and listeners but does not roll back business writes.
   Source `event_outbox` rows remain.
2. For Realtime pressure, use the SQL kill switch in
   `docs/runbooks/notification-delivery-operations.md`; it does not affect broker
   queues.
3. Do not run `rabbitmqctl purge_queue`, management API queue deletion, or any
   equivalent. Do not delete a queue to force redeclaration.
4. Do not replace a quorum queue with a classic queue, and do not change
   `dead-letter-strategy` to `at-most-once` or `overflow` to `drop-head`.
5. Do not raise `disk_free_limit` or disable the disk alarm to force publishes.
   Free disk space or expand the approved volume.
6. Treat as stop conditions: sustained ready or unacknowledged growth while
   consumers are down; `disk_free_alarm=true`; `mem_alarm=true`; a queue whose
   type or effective arguments differ from section 2; repeated consumer
   crashes; DLQ growth with no drain plan.
7. If backlog age approaches 30 days, stop replay plans. The age guard
   quarantines at `occurredAt + 30 days`; it does not authorize discarding the
   work.

## 9. Change control

A queue-type change or policy downgrade requires operator approval, a drain or
copy plan, and a rollback plan. It is not an in-place operation. Do not use
queue deletion, recreation, or a silent policy override as a shortcut. Preserve
unpublished outbox rows, retry work, DLQ work, and event identity throughout.

Topology declarations are scoped to `dacn.*`. Do not delete or modify unrelated
pre-existing broker data.

## 10. Sources

- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopology.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopologyConfig.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopologyVerifier.java`
- `src/main/java/com/project/dacn/messaging/rabbit/RabbitTopologyHealthIndicator.java`
- `src/main/java/com/project/dacn/messaging/rabbit/NotificationRabbitProperties.java`
- `src/main/resources/application.yml`
- `src/test/java/com/project/dacn/messaging/rabbit/RabbitTopologyTest.java`
