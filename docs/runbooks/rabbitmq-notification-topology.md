# RabbitMQ notification topology runbook

This runbook documents the broker topology declared by
`RabbitTopology` and `RabbitTopologyConfig`. It is an operator reference, not a
migration plan.

Delivery is disabled by default: `app.notifications.delivery-enabled` reads
`NOTIFICATIONS_DELIVERY_ENABLED` and defaults to `false`. While it is false,
the relay, listeners, topology declarations, and startup verifier are absent,
and `spring.rabbitmq.dynamic` is also false. Business writes continue to append
`event_outbox` rows.

The default vhost is `/`. The application declares the topology in the active
AMQP vhost, and the startup verifier reads the same vhost from the connection
factory.

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

DLQs are durable quorum queues declared with no TTL, expiration, dead-letter
exchange, or dead-letter routing key. The application has no automatic DLQ
consumer. An operator must preserve unresolved DLQ work, including when a
managed idle-expiry policy applies (section 4).

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
not a stream queue deployment. The only exception is an explicitly allowlisted
HTTP 403 from `GET /api/feature-flags` in Little Lemur mode (section 6.3). RabbitMQ
4.3.6 declares `stream_queue` a hard-required feature flag.

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
  in section 2, except for the explicit idle-expiry allowance below. A policy
  that changes `dead-letter-strategy` to `at-most-once`, changes `overflow` to
  `drop-head`, or changes a message TTL fails the verifier or violates the
  delivery contract.
- Main queues and DLQs must have no `message-ttl`.
- Retry queues must have exactly `10000`, `30000`, or `120000` ms
  `message-ttl`, matched to their queue name.
- By default, `expires` must be absent on every notification queue. Use
  `RABBITMQ_EXPECTED_QUEUE_IDLE_EXPIRY=28d` only to accept the Little Lemur
  platform policy. Effective `expires` may be absent or exactly `2419200000` ms
  (28 days). A different, fractional, or malformed value fails verification.
  The opt-in does not declare `x-expires` or change any message TTL. Queue
  expiration deletes an unused queue, not just individual messages; do not
  rely on quorum queue redeclaration to renew the lease. Plan for retry and DLQ
  retention before enabling delivery.

The verifier failure code is `QUEUE_POLICY` with the queue name when an
effective value differs or the queue response is unavailable. `BINDING` means
the bindings response is unavailable or at least one required binding is missing.
`FEATURE_FLAG` means the check did not prove `stream_queue` is enabled and no
exception applied. `BROKER_VERSION` means the overview could not be read or
the version check failed.
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
7. The application declares no DLQ expiry and provides no DLQ consumer.
   Preserve DLQ work if a managed idle-expiry policy applies.

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
- `app.notifications.rabbit.cloudamqp-little-lemur`
  (`RABBITMQ_CLOUDAMQP_LITTLE_LEMUR`, default `false`)
- `app.notifications.rabbit.cloud-allow-forbidden-checks`
  (`RABBITMQ_CLOUD_ALLOW_FORBIDDEN_CHECKS`, comma-separated, empty by default).
  The only names are `feature-flags`, `policies`, and `operator-policies`.
  Unknown names fail configuration binding; the allowlist cannot skip checks
  outside Little Lemur mode.
- `app.notifications.rabbit.expected-queue-idle-expiry`
  (`RABBITMQ_EXPECTED_QUEUE_IDLE_EXPIRY`, unset by default)

For a future deployment on the free Little Lemur plan, set `RABBITMQ_HOST`
and `RABBITMQ_PORT` from the provider's AMQPS settings. Set
`RABBITMQ_SSL_ENABLED=true` and put the assigned, unencoded vhost in
`RABBITMQ_VHOST`. An address URI can override `RABBITMQ_VHOST`; use
the separate host, TLS, and vhost inputs without a `spring.rabbitmq.addresses`
override (`SPRING_RABBITMQ_ADDRESSES`). Set the provider's HTTPS management URL
separately. Turn on `RABBITMQ_CLOUDAMQP_LITTLE_LEMUR` for the version range.
Set `RABBITMQ_EXPECTED_QUEUE_IDLE_EXPIRY=28d` if the platform policy applies.
Add only the individual allowlist entries whose HTTP 403 responses preflight
confirms. Local defaults remain non-TLS, vhost `/`, strict checks, and no
expiry.

Run these from an approved admin host. Do not use `set -x`, do not use
`curl --verbose` with credentials, and never paste the password or an
`Authorization` header into a ticket. The examples expand the password into the
local `curl` process arguments; if that is not acceptable on the host, use the
approved credential helper instead.

```bash
set -o pipefail
MGMT="${RABBITMQ_MANAGEMENT_URL%/}"
VHOST="${RABBITMQ_VHOST:-/}" # Use the effective AMQP vhost if an address URI overrides it.
VHOST_PATH="$(jq -nr --arg value "$VHOST" '$value | @uri')"

mgmt() {
  curl --silent --show-error --fail \
    --user "$RABBITMQ_MANAGEMENT_USERNAME:$RABBITMQ_MANAGEMENT_PASSWORD" \
    "$MGMT$1"
}

mgmt_status() {
  curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
    --user "$RABBITMQ_MANAGEMENT_USERNAME:$RABBITMQ_MANAGEMENT_PASSWORD" \
    "$MGMT$1"
}
```

`VHOST_PATH` encodes one URL path segment. For the default `/`, it is `%2F`.
The overview and feature-flag paths are not vhost scoped.

### 6.1 Broker version

```bash
version="$(mgmt /api/overview | jq -er '.rabbitmq_version | strings')" || exit 1
if [[ "${RABBITMQ_CLOUDAMQP_LITTLE_LEMUR:-false}" == true ]]; then
  version="$(printf '%s' "$version" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [[ "$version" =~ ^4\.(3|4)\.[0-9]+$ ]] || { printf 'Unsupported broker version\n' >&2; exit 1; }
else
  prefix="${RABBITMQ_EXPECTED_VERSION:-4.4}"
  [[ "$version" == "$prefix"* ]] || { printf 'Broker version prefix mismatch\n' >&2; exit 1; }
fi
printf 'broker version ok: %s\n' "$version"
```

Little Lemur mode requires a readable `rabbitmq_version` and accepts stable
`4.3.x`–`4.4.x` after trimming, with no prerelease suffix. The verifier tests
cover `4.3.6` as the reference patch, not a pin or proof of live compatibility.
Outside Little Lemur mode, the configured prefix still applies. A blank local
prefix skips the application's version comparison; keep it set in deployed
environments.

`GET /api/nodes` shows `disk_free_alarm` and `mem_alarm`; `GET /api/overview`
shows listener state. Those fields are safe to print; credentials are not in
the response.

### 6.2 Exchanges

The startup verifier checks the version, feature flag, queues, and bindings,
not exchange properties. Check all three exchanges explicitly:

```bash
while read -r exchange type; do
  mgmt "/api/exchanges/$VHOST_PATH/$exchange" \
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

Record the HTTP status with `mgmt_status /api/feature-flags`. If it returns
200, require `stream_queue=enabled`:

```bash
mgmt /api/feature-flags \
  | jq -er '.[] | select(.name == "stream_queue" and .state == "enabled") | "\(.name)=\(.state)"'
```

A 403 is eligible for an exception only in Little Lemur mode with the exact
`feature-flags` allowlist entry. A 401, 404, 500, timeout, malformed response, or
disabled or missing flag is not an exception. After an approved skip, capture
the application's warning from startup logs in the preflight record:
`Skipping RabbitMQ management check /api/feature-flags: HTTP 403 (Little Lemur allowlist)`.
The app logs it once per verifier instance. Record the broker version and
endpoint/status with the warning; do not capture response bodies or credentials.

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
  mgmt "/api/queues/$VHOST_PATH/$q" \
    | jq '{name, type, durable, auto_delete, exclusive, arguments, effective_policy_definition}'
done
```

For every queue, require `type` equal to `quorum`, `durable` true,
`auto_delete` false, and `exclusive` false. Then compare `arguments` and
`effective_policy_definition` with sections 2 and 4. Management API queue
arguments include `x-` while policy definitions usually omit it. Every queue
response remains mandatory even if a policy-list endpoint is forbidden.

A compact type/durability check:

```bash
for q in "${queues[@]}"; do
  mgmt "/api/queues/$VHOST_PATH/$q" \
    | jq -e '.type == "quorum" and .durable == true and .auto_delete == false and .exclusive == false' \
    >/dev/null \
    || { printf 'queue contract failed: %s\n' "$q" >&2; exit 1; }
done
```

### 6.5 Bindings

```bash
mgmt "/api/bindings/$VHOST_PATH" \
  | jq -r --arg vhost "$VHOST" '.[]
      | select(.vhost == $vhost and (.source | startswith("dacn.")))
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

Check both policy lists independently. The application does not request
either list; these are operator preflight checks.

```bash
mgmt_status "/api/policies/$VHOST_PATH"
mgmt_status "/api/operator-policies/$VHOST_PATH"
```

For each 200, use `mgmt` on that same path and inspect the returned policies.
Expect no application-owned notification policy. For any platform policy,
inspect its `pattern`, `priority`, and effective arguments against section 4.
For a 403, allow a manual skip only in Little Lemur mode and only when the
exact entry is approved: `policies` for the first path, `operator-policies`
for the second. Record each skipped path and status separately. One entry
does not cover the other, and neither covers feature flags. Any other failure
blocks preflight. A skipped policy list never replaces the per-queue
effective-argument checks. Overview, queues, and bindings cannot be skipped.

### 6.7 `rabbitmqctl` alternatives

Run on the broker host, read-only:

```bash
rabbitmqctl list_exchanges -p "$VHOST" name type durable auto_delete
rabbitmqctl list_queues -p "$VHOST" name type durable auto_delete exclusive arguments \
  messages_ready messages_unacknowledged consumers
rabbitmqctl list_bindings -p "$VHOST" source_name destination_name routing_key
rabbitmqctl list_policies -p "$VHOST"
rabbitmqctl list_operator_policies -p "$VHOST"
rabbitmqctl list_feature_flags
rabbitmqctl version
rabbitmq-diagnostics -q ping
```

Check quorum membership for each queue:

```bash
rabbitmq-queues quorum_status --vhost "$VHOST" dacn.notifications.inapp
rabbitmq-queues quorum_status --vhost "$VHOST" dacn.notifications.email
rabbitmq-queues quorum_status --vhost "$VHOST" dacn.notifications.inapp.dlq
rabbitmq-queues quorum_status --vhost "$VHOST" dacn.notifications.email.dlq
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

The version range is a startup compatibility check, not a cutover approval.
Avoid RabbitMQ `4.3.0` for cutover: the
[4.3.1 release notes](https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.1)
document a fix for at-least-once quorum dead-letter commands reaching the
wrong replica. Use an approved patched broker. Before cutover, verify on the
actual broker: confirmed publish and routing to both channels, manual
acknowledgement after confirmed transfer, all three retry delays, and
at-least-once terminal dead-letter delivery. Record the actual broker version,
the effective topology, and any approved 403 skips.

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
   type or effective arguments differ from sections 2 and 4; repeated consumer
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
- [RabbitMQ 4.3 quorum queues and dead-lettering](https://www.rabbitmq.com/docs/4.3/quorum-queues)
- [RabbitMQ 4.3 TTL](https://www.rabbitmq.com/docs/4.3/ttl)
- [RabbitMQ 4.3 publisher confirms and acknowledgements](https://www.rabbitmq.com/docs/4.3/confirms)
- [RabbitMQ 4.3.6 required feature-flag definition](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.6/deps/rabbit/src/rabbit_core_ff.erl)
