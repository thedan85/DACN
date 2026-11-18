# RabbitMQ local setup for order notifications

For running the order-notification delivery path on your own machine. This is a
local, disposable broker only; it is not a deployment guide. The queue contract
lives in [`rabbitmq-notification-topology.md`](rabbitmq-notification-topology.md).
Never put credentials, customer data, or message contents in a ticket.

## 1. Start the broker

Docker Desktop, then:

```powershell
docker run -d --name dacn-rabbitmq --hostname dacn-rabbitmq -p 127.0.0.1:5672:5672 -p 127.0.0.1:15672:15672 -v dacn-rabbitmq-data:/var/lib/rabbitmq rabbitmq:4.4-management-alpine
```

Ports bind to `127.0.0.1` only. The `4.4` tag matches the app default
`RABBITMQ_EXPECTED_VERSION=4.4`.

Verify:

```powershell
docker exec dacn-rabbitmq rabbitmq-diagnostics -q ping
docker exec dacn-rabbitmq rabbitmqctl version
docker exec dacn-rabbitmq rabbitmqctl list_feature_flags
```

Expect `Ping succeeded`, a `4.4.x` version, and `stream_queue=enabled`. If the
flag is disabled on your own broker:

```powershell
docker exec dacn-rabbitmq rabbitmqctl enable_feature_flag stream_queue
```

Feature flags can be irreversible. Never touch them on a shared broker.

Management UI: `http://localhost:15672`, user `guest`, password `guest`.
Native install instead of Docker: install Erlang and RabbitMQ 4.4, run
`rabbitmq-plugins enable rabbitmq_management`, then start the node. Keep the
same ports.

## 2. Configure `.env`

`.env` is gitignored. With the default local broker, the only key you need is:

```properties
NOTIFICATIONS_DELIVERY_ENABLED=true
```

The `RABBITMQ_*` keys are optional overrides. Their defaults already are
`localhost:5672`, `guest` / `guest`, management at `http://localhost:15672`, and
version prefix `4.4`. Add one only when you change that default, for example:

```properties
RABBITMQ_PORT=5673
RABBITMQ_MANAGEMENT_URL=http://localhost:15673
```

- `NOTIFICATIONS_DELIVERY_ENABLED=true` is what makes the app connect. When it
  is `false`, the broker is not needed, but no notification or email is sent.
- AMQP and management credentials are separate settings. If you replace
  `guest`, update both and give the new user the `management` tag.
- `guest` only accepts loopback connections. If the backend runs in another
  container or WSL, create a local user instead.
- Do not commit `.env` or reuse deployed credentials.

## 3. Start the backend

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

With delivery enabled, the app declares the topology and verifies it. A healthy
start logs `Started DacnApplication` with no `TopologyVerificationException`.

Check the queues:

```powershell
docker exec dacn-rabbitmq rabbitmqctl list_queues -p / name type durable auto_delete exclusive
```

Expect ten queues, all `quorum`, durable, not auto-delete, not exclusive: two
main, six retry, two dead-letter. Any other type or argument means the contract
is wrong. Do not delete or recreate queues to force a pass on a shared broker.

## 4. Troubleshooting

| Symptom | Fix |
|---|---|
| `Connection refused` | Start the broker; check `docker ps`, `RABBITMQ_HOST`, `RABBITMQ_PORT`. |
| `ACCESS_REFUSED` for `guest` | Use loopback, or create a dedicated local user. |
| Management `401` | Check `RABBITMQ_MANAGEMENT_*` and the user's `management` tag. |
| `BROKER_VERSION` failure | Use the `4.4-management-alpine` image, or set `RABBITMQ_EXPECTED_VERSION` to the local minor. Do not loosen it in a deployed environment. |
| `FEATURE_FLAG` failure | Enable `stream_queue` and restart the broker and backend. |
| `QUEUE_POLICY` / `PRECONDITION_FAILED` | A stale queue has different arguments. Reset only your disposable local broker (section 5). |
| Port `5672` or `15672` in use | Map alternate host ports, for example `5673:5672` and `15673:15672`, then update the matching env values. |
| No notifications move | Confirm delivery is enabled and consumers appear in the management UI. |

Do not bypass the verifier. For shared or deployed brokers, follow the stop
rules in the topology runbook.

## 5. Stop and reset

```powershell
docker stop dacn-rabbitmq
docker start dacn-rabbitmq
```

Keep data when removing the container:

```powershell
docker rm dacn-rabbitmq
```

Destroy all local broker data:

```powershell
docker rm -f dacn-rabbitmq
docker volume rm dacn-rabbitmq-data
```

The last command deletes every local queue, message, user, and definition. Use
it only on a broker you created, when no local work matters.

## 6. Local rules

- Bind ports to `127.0.0.1`. Never expose `5672` or `15672` to the LAN or a
  public tunnel.
- `guest` / `guest` is local only. Never reuse it elsewhere.
- One node is not HA. Quorum queues do not provide broker failover.
- At-least-once delivery can duplicate a message. Do not disable dedup or switch
  a queue to at-most-once to hide it.
- Do not point a local backend at a shared broker unless the team approves it.
