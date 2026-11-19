# RabbitMQ local setup

This runbook starts a RabbitMQ broker for local backend development. Use either
Docker or a native Windows install. It is not a production guide: a single
broker node is not highly available. Production operations are covered in
`notification-delivery-operations.md`.

The backend expects:

| Item | Value |
|---|---|
| Broker | RabbitMQ 4.4.x |
| AMQP | `127.0.0.1:5672`, vhost `/` |
| Management API | `http://127.0.0.1:15672`, plugin enabled |
| Version check | `RABBITMQ_EXPECTED_VERSION=4.4` (default) |
| Delivery switch | `NOTIFICATIONS_DELIVERY_ENABLED=true` |

With delivery disabled, the backend only writes `event_outbox` rows. It does
not connect to the broker, declare topology, or start consumers. When delivery
is enabled, the application declares all ten queues and their bindings. Do not
create them by hand.

Wait until the broker answers a ping and the management UI responds before
starting the backend. The startup verifier has a 20-second budget.

## Option A: Docker (recommended)

Docker Desktop with the Linux engine must be running. The image runs RabbitMQ
as a Linux container.

### Start with `docker run`

```powershell
docker run -d --name dacn-rabbitmq `
  -p 127.0.0.1:5672:5672 -p 127.0.0.1:15672:15672 `
  -e RABBITMQ_DEFAULT_USER=dacn `
  -e RABBITMQ_DEFAULT_PASS=dacn-dev `
  -v dacn-rabbitmq-data:/var/lib/rabbitmq `
  --restart unless-stopped `
  rabbitmq:4.4-management
```

`RABBITMQ_DEFAULT_USER` and `RABBITMQ_DEFAULT_PASS` are read only on the first
start, when the data volume is empty. They create an administrator user for
vhost `/`. The `-management` tag enables the management plugin; no extra
command is needed. Do not use `guest` over the published port: the built-in
guest user is restricted to localhost connections.

If the volume already exists from an earlier run, these variables are ignored.
Use a fresh volume or create the user manually.

Check the broker:

```powershell
docker exec dacn-rabbitmq rabbitmq-diagnostics -q ping
docker exec dacn-rabbitmq rabbitmq-diagnostics -q server_version
```

The second command must print a `4.4.x` version.

### Start with Compose

Save this as `rabbitmq-compose.yml`:

```yaml
services:
  rabbitmq:
    image: rabbitmq:4.4-management
    container_name: dacn-rabbitmq
    ports:
      - "127.0.0.1:5672:5672"
      - "127.0.0.1:15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: dacn
      RABBITMQ_DEFAULT_PASS: dacn-dev
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 12

volumes:
  rabbitmq-data:
```

Run it:

```powershell
docker compose -f rabbitmq-compose.yml up -d
docker compose -f rabbitmq-compose.yml ps
```

### Backend settings for Docker

Add these to the gitignored `.env` in the repository root, or set them in the
shell before starting the backend:

```dotenv
NOTIFICATIONS_DELIVERY_ENABLED=true
RABBITMQ_HOST=127.0.0.1
RABBITMQ_USERNAME=dacn
RABBITMQ_PASSWORD=dacn-dev
RABBITMQ_MANAGEMENT_URL=http://127.0.0.1:15672
RABBITMQ_MANAGEMENT_USERNAME=dacn
RABBITMQ_MANAGEMENT_PASSWORD=dacn-dev
```

Open the management UI at `http://127.0.0.1:15672` and sign in as `dacn`.

### Reset the Docker broker

Stop and remove the container while keeping its data:

```powershell
docker rm -f dacn-rabbitmq
```

Delete the container **and every local queue and message**:

```powershell
docker rm -f dacn-rabbitmq
docker volume rm dacn-rabbitmq-data
```

For Compose, `docker compose -f rabbitmq-compose.yml down` keeps the volume.
`down -v` deletes the volume and all broker data.

## Option B: Native Windows install

Run the installation commands from an elevated PowerShell. RabbitMQ requires
Erlang to be installed by an administrative account so the Windows service can
find it.

### 1. Install Erlang/OTP

RabbitMQ 4.4 supports Erlang/OTP 27.0 through 29.x. Install the current 64-bit
Erlang/OTP:

```powershell
winget install --id Erlang.ErlangOTP -e --accept-package-agreements --accept-source-agreements
```

Only one Erlang version should be installed. Remove older versions before
installing a new one.

### 2. Install RabbitMQ

Download and run the official 4.4.1 installer as an administrator:

<https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.4.1/rabbitmq-server-4.4.1.exe>

The installer creates and starts the `RabbitMQ` Windows service. Use an
installation path with ASCII characters. The official documentation recommends
avoiding spaces in the path.

If Chocolatey is already installed, `choco install rabbitmq -y` is an
alternative. Chocolatey releases can lag behind; confirm that it installed
4.4.x before continuing.

### 3. Check the service

```powershell
Get-Service RabbitMQ
Start-Service RabbitMQ
rabbitmqctl.bat status
rabbitmq-diagnostics.bat -q server_version
```

If `rabbitmqctl.bat` is not on `PATH`, open the RabbitMQ command prompt from the
Start Menu, or run it from
`C:\Program Files\RabbitMQ Server\rabbitmq_server-4.4.1\sbin`.

### 4. Enable the management plugin

```powershell
rabbitmq-plugins.bat enable rabbitmq_management
```

The plugin starts without restarting the broker. Open
`http://127.0.0.1:15672` and sign in as `guest` / `guest`.

### 5. Backend settings for native install

The native broker satisfies the application defaults, so the only required
setting is:

```dotenv
NOTIFICATIONS_DELIVERY_ENABLED=true
```

If `.env` still contains the Docker `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD`, or `RABBITMQ_MANAGEMENT_*` values, remove them or change
them back to `guest`.

## Start the backend

Restart the backend after changing `.env`; the file is read once at JVM start.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Starting with delivery enabled runs these steps in order:

1. Declare the exchanges, queues, and bindings.
2. Verify broker version, `stream_queue` feature flag, queues, and bindings
   through the management API.
3. Start the consumers.

Any failed check aborts startup. The exception code names the failed check.

## Verify

1. Ping the broker with the command for your option.
2. Confirm the management UI opens and reports version `4.4.x`.
3. Start the backend and confirm it stays up.
4. In the management UI, open **Queues and Streams**. The application declares
   ten quorum queues: two main queues, six retry queues, and two dead-letter
   queues.
5. Place a test order and confirm the in-app queue drains and the
   notification row is created. With Realtime enabled, the admin bell updates
   without a refresh.

The queue names, arguments, and bindings are documented in
`rabbitmq-notification-topology.md`.

## Troubleshooting

| Symptom | Check |
|---|---|
| `Connection refused` on `5672` | Container or service is stopped, or the port is mapped elsewhere |
| `ACCESS_REFUSED ... PLAIN` | Guest user used from a non-localhost address, or the `dacn` user was not created because the volume already existed |
| Management UI returns `401` | Management username/password do not match the broker user |
| Management API unreachable | Management plugin is disabled, or port `15672` is not published |
| Startup fails with `BROKER_VERSION` | Broker is not 4.4.x, or `RABBITMQ_EXPECTED_VERSION` is wrong |
| Startup fails with `FEATURE_FLAG` | `stream_queue` is disabled on an upgraded node; run `rabbitmqctl.bat enable_feature_flag stream_queue` |
| Startup fails with `QUEUE_POLICY` or a precondition error | Existing `dacn.*` queues have different arguments; delete them or reset the local broker |
| No relay or consumer activity | `NOTIFICATIONS_DELIVERY_ENABLED` is still `false`, or the backend was not restarted |
| `rabbitmqctl.bat` cannot reach the node | Service is stopped, or the Erlang cookie does not match; reinstall as an administrator |
| Port already allocated | Stop the old container/broker, or change both the published port and `RABBITMQ_PORT` |

## Scope

This setup uses plain AMQP and the default vhost. It is suitable for local
development only. Do not expose `5672` or `15672` to the internet. Production
requires TLS, secret management, monitoring, and a highly available cluster;
see `notification-delivery-operations.md`.

## References

- Windows install: <https://www.rabbitmq.com/docs/install-windows>
- Erlang compatibility: <https://www.rabbitmq.com/docs/which-erlang>
- Docker image: <https://hub.docker.com/_/rabbitmq>
- Topology: `rabbitmq-notification-topology.md`
- Delivery operations: `notification-delivery-operations.md`
