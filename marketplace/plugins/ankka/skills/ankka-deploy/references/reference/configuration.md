# Runtime configuration

> Every environment variable and configuration key a running ankka service reads, their defaults, how configuration is layered, and which variables the platform sets for you.

Source: https://docs.ankka.cloud/reference/configuration/
An ankka service needs no configuration to run on a laptop: with no environment variables it connects to
Postgres on `localhost:5432` as `ankka`/`ankka`, serves HTTP on port 9000, and forms a one-node cluster by
joining itself. Deployed on the platform, it needs no configuration either, because the platform sets
every variable that says where it is running. This page lists what can be changed and what the platform
controls.

## How configuration is layered

The runtime reads HOCON configuration, stacked from four layers. A key is taken from the highest layer
that sets it:

1. JVM system properties, such as `-Dankka.ask-timeout=20s`.
2. The service's own `application.conf`.
3. The cluster overlay for where the process runs, chosen by `ANKKA_CLUSTER_MODE`.
4. The runtime's `reference.conf`, which holds every default.

The cluster overlay decides only how a node finds its peers. `ANKKA_CLUSTER_MODE` selects it:

| Mode | Set by | How nodes form a cluster |
|---|---|---|
| `local`, the default | nobody | Join the nodes named in `ANKKA_CLUSTER_SEED_NODES`, or join yourself; bind loopback on a random port. |
| `kubernetes` | the platform | Discover peers through the Kubernetes API; bind the pod's address on fixed ports. |

Any other value stops the service at startup with a message naming the known modes. Because the service's
`application.conf` sits above the overlay, it can override any choice the platform's overlay made.

Environment variables reach the configuration through `${?VARIABLE}` substitutions, so an environment
variable overrides a default only where the table lists one. In the `kubernetes` overlay the substitutions
are required rather than optional: a missing `POD_IP` is a startup failure naming it, not a node that
quietly binds loopback.

## Settings

The table is generated from the runtime's configuration files.

| Variable | Configuration key | Default | Applies in |
|---|---|---|---|
| `ANKKA_SERVICE_NAME` | `ankka.service.name` | `""` | every service |
| `ANKKA_SECRET_KEY` | `ankka.secrets.key` | `""` | every service |
| `ANKKA_DATABASE` | `ankka.database` | `""` | every service |
| `ANKKA_SERVICE_CLIENT_TIMEOUT` | `ankka.service-client.timeout` | `30s` | every service |
| `ANKKA_DB_HOST` | `pekko.persistence.r2dbc.connection-factory.host` | `"localhost"` | every service |
| `ANKKA_DB_PORT` | `pekko.persistence.r2dbc.connection-factory.port` | `5432` | every service |
| `ANKKA_DB_NAME` | `pekko.persistence.r2dbc.connection-factory.database` | `"ankka"` | every service |
| `ANKKA_DB_USER` | `pekko.persistence.r2dbc.connection-factory.user` | `"ankka"` | every service |
| `ANKKA_DB_PASSWORD` | `pekko.persistence.r2dbc.connection-factory.password` | `"ankka"` | every service |
| `ANKKA_DB_SSL_MODE` | `pekko.persistence.r2dbc.connection-factory.ssl.mode` | `""` | every service |
| `ANKKA_DB_SSL_ROOT_CERT` | `pekko.persistence.r2dbc.connection-factory.ssl.root-cert` | `""` | every service |
| `ANKKA_DB_SSL_CERT` | `pekko.persistence.r2dbc.connection-factory.ssl.cert` | `""` | every service |
| `ANKKA_DB_SSL_KEY` | `pekko.persistence.r2dbc.connection-factory.ssl.key` | `""` | every service |
| `ANKKA_HTTP_INTERFACE` | `ankka.http.interface` | `"0.0.0.0"` | every service |
| `ANKKA_HTTP_PORT` | `ankka.http.port` | `9000` | every service |
| `ANKKA_SOCKET_MAX_FRAME_SIZE` | `ankka.http.socket.max-frame-size` | `64KiB` | every service |
| `ANKKA_SOCKET_UNREAD_FRAMES` | `ankka.http.socket.unread-frames` | `64` | every service |
| `ANKKA_SOCKET_KEEP_ALIVE` | `ankka.http.socket.keep-alive` | `20s` | every service |
| `ANKKA_GRPC_INTERFACE` | `ankka.grpc.interface` | `"0.0.0.0"` | a service that serves gRPC |
| `ANKKA_GRPC_PORT` | `ankka.grpc.port` | `9090` | a service that serves gRPC |
| `ANKKA_OTLP_ENDPOINT` | `ankka.telemetry.endpoint` | `""` | a service that exports telemetry |
| `ANKKA_OTLP_HEADERS` | `ankka.telemetry.headers` | `""` | a service that exports telemetry |
| `ANKKA_MCP_CONNECT_TIMEOUT` | `ankka.agent.mcp.connect-timeout` | `10s` | a service with agents |
| `ANKKA_MCP_CALL_TIMEOUT` | `ankka.agent.mcp.call-timeout` | `60s` | a service with agents |
| `ANKKA_CLUSTER_SEED_NODES` | `ankka.cluster.seed-nodes` | `""` | local mode |
| `ANKKA_CLUSTER_PORT` | `pekko.remote.artery.canonical.port` | `0` | local mode |
| `POD_IP` | `pekko.remote.artery.canonical.hostname` | required, set by the platform | kubernetes mode |
| `POD_IP` | `pekko.management.http.hostname` | required, set by the platform | kubernetes mode |
| `ANKKA_CLUSTER_SERVICE` | `pekko.management.cluster.bootstrap.contact-point-discovery.service-name` | required, set by the platform | kubernetes mode |
| `ANKKA_CLUSTER_CONTACT_POINTS` | `pekko.management.cluster.bootstrap.contact-point-discovery.required-contact-point-nr` | required, set by the platform | kubernetes mode |
| `ANKKA_CLUSTER_POD_SELECTOR` | `pekko.discovery.kubernetes-api.pod-label-selector` | required, set by the platform | kubernetes mode |

Settings with no environment variable, overridable in the service's own `application.conf`:

| Configuration key | Default | Applies in |
|---|---|---|
| `ankka.ask-timeout` | `10s` | every service |
| `ankka.query-resend-after` | `2s` | every service |
| `ankka.tls.cluster-directory` | `""` | every service |
| `ankka.tls.service-directory` | `""` | every service |
| `ankka.tls.reload-interval` | `1m` | every service |
| `ankka.http.tls.enabled` | `off` | every service |
| `ankka.probe.enabled` | `off` | every service |
| `ankka.probe.port` | `7627` | every service |
| `ankka.observability.ring-capacity` | `4096` | every service |
| `ankka.observability.max-counted-handlers` | `1024` | every service |
| `ankka.observability.call-window` | `10m` | every service |
| `ankka.observability.call-buckets` | `60` | every service |
| `ankka.observability.observe.enabled` | `off` | every service |
| `ankka.observability.observe.port` | `7628` | every service |
| `ankka.observability.observe.peer` | `"ankka://platform/controlplane"` | every service |
| `ankka.observability.max-external-services` | `32` | every service |
| `ankka.observability.max-external-methods` | `256` | every service |
| `ankka.http.body-timeout` | `10s` | every service |
| `ankka.grpc.max-message-size` | `4MiB` | a service that serves gRPC |
| `ankka.grpc.max-connection-age` | `2m` | a service that serves gRPC |
| `ankka.grpc.shutdown-grace` | `5s` | a service that serves gRPC |
| `ankka.grpc.keepalive-time` | `30s` | a service that serves gRPC |
| `ankka.grpc.keepalive-timeout` | `10s` | a service that serves gRPC |
| `ankka.telemetry.interval` | `1s` | a service that exports telemetry |
| `ankka.telemetry.batch-size` | `512` | a service that exports telemetry |
| `ankka.telemetry.export-timeout` | `5s` | a service that exports telemetry |
| `ankka.telemetry.max-backoff` | `30s` | a service that exports telemetry |
| `ankka.telemetry.metric-interval` | `10s` | a service that exports telemetry |
| `ankka.telemetry.shutdown-timeout` | `3s` | a service that exports telemetry |
| `ankka.telemetry.service-name` | `""` | a service that exports telemetry |
| `ankka.telemetry.project` | `""` | a service that exports telemetry |
| `ankka.cluster.formation` | `join-self-or-seeds` | local mode |
| `ankka.join-self-if-no-seed-nodes` | `on` | local mode |
| `ankka.cluster.formation` | `bootstrap` | kubernetes mode |
| `ankka.join-self-if-no-seed-nodes` | `off` | kubernetes mode |
| `ankka.tls.cluster-directory` | `"/var/run/secrets/ankka/cluster"` | kubernetes mode |
| `ankka.tls.service-directory` | `"/var/run/secrets/ankka/service"` | kubernetes mode |
| `ankka.http.tls.enabled` | `on` | kubernetes mode |
| `ankka.probe.enabled` | `on` | kubernetes mode |
| `ankka.observability.observe.enabled` | `on` | kubernetes mode |
## What each variable means

### HTTP

- `ANKKA_HTTP_INTERFACE` is the address the HTTP server binds, `0.0.0.0` by default.
- `ANKKA_HTTP_PORT` is the port the HTTP server binds, `9000` by default. On the platform it is set from
  the descriptor's `port`, and a descriptor may not set it directly. Set it locally to run a second
  service beside the first.
- `ANKKA_SOCKET_MAX_FRAME_SIZE` is the largest frame a socket's client may send, as the UTF-8 bytes of the
  whole message, `64KiB` by default. A larger one closes the socket `1009`, too large, without being read.
  Behind a sidecar it may be at most `3MiB`, since each frame crosses to the process as one gRPC message.
- `ANKKA_SOCKET_UNREAD_FRAMES` is how many frames may wait for a socket's handler that has not received
  them, `64` by default. One more closes the socket `1008`, unread: a handler that only sends must still
  read.
- `ANKKA_SOCKET_KEEP_ALIVE` is how long a socket may be quiet before the platform pings it, `20s` by
  default. It must be shorter than the server's idle timeout, `pekko.http.server.idle-timeout` (`60s`),
  which would otherwise cut a quiet socket off; a service whose keep-alive is not shorter does not start.
  A process-hosted service's sidecar holds its sockets, so all three are given to the sidecar and never
  to the process.

### gRPC

These apply to a service that registers a `GrpcServer`; see [gRPC endpoints](../build/grpc-endpoints.md).

- `ANKKA_GRPC_INTERFACE` is the address the gRPC server binds, `0.0.0.0` by default.
- `ANKKA_GRPC_PORT` is the port the gRPC server binds, `9090` by default. On the platform it is set
  from the descriptor's `grpcPort` when the descriptor says `"grpc": true`, and a descriptor may not set
  it directly. A service started with it set and no `GrpcServer` registered refuses to start, saying why.

### Database

The runtime keeps its journal, snapshots, durable state, view rows, projection offsets and timers in one
Postgres database. On the platform these five are set from the database provisioned for the service, and a
descriptor that sets any `ANKKA_DB_*` variable brings its own database instead.

- `ANKKA_DB_HOST` is the Postgres host, `localhost` by default.
- `ANKKA_DB_PORT` is the Postgres port, `5432` by default.
- `ANKKA_DB_NAME` is the database, `ankka` by default.
- `ANKKA_DB_USER` is the user, `ankka` by default.
- `ANKKA_DB_PASSWORD` is the password, `ankka` by default. A provisioned database has none: its role logs
  in by certificate.
- `ANKKA_DB_SSL_MODE` turns on TLS to the database: `require`, `verify-ca` or `verify-full`. Empty, the
  default, the connection is plain. The platform sets `verify-full` for a provisioned database.
- `ANKKA_DB_SSL_ROOT_CERT` is the file of the authority the server's certificate is verified against.
- `ANKKA_DB_SSL_CERT` and `ANKKA_DB_SSL_KEY` are a client certificate and its PKCS#8 key to log in with
  instead of a password. The key is read when a connection is opened, so a renewed certificate reaches the
  next connection without a restart.

- `ANKKA_DATABASE` is `none` for a service that has no database at all: one made of consumers, endpoints
  and agents. The platform sets it for a descriptor that says `"database": "none"`. With it the runtime
  opens no connection — the secret store is unavailable and the timer scheduler refuses every timer — and
  an entity, a view, a workflow or a timed action registered in the service refuses the start, naming
  itself. Empty, the default, the service has a database, the platform's or its own.

Never point two services at one database. Timers, view tables and projection offsets are not separated by
service, so two services sharing a database delete each other's timers and overwrite each other's views.

### Calling other services

- `ANKKA_SERVICE_CLIENT_TIMEOUT` (`ankka.service-client.timeout`) is how long a call to another service
  waits for its answer, `30s` unless set; connecting has five seconds of its own and there is no per-call
  timeout. A descriptor may give it, and it goes to the runtime that makes the call — beside a Python or
  TypeScript process, the sidecar, never the process. See
  [Calling other services](../build/calling-services.md).

### Secret store

- `ANKKA_SECRET_KEY` (`ankka.secrets.key`) is the key the service's secret store encrypts the values it
  keeps with: the standard base64 of exactly 32 bytes, as `openssl rand -base64 32` writes it. On the
  platform the operator makes one per service unless the descriptor sets it, and gives it only to the
  platform's own program, never to a process or a module. Empty, the default, the service starts and
  keeping or reading a secret fails naming the variable. Set to anything that is not 32 bytes of base64,
  the service does not start. See [Secrets a service keeps](../build/secrets.md).

### Local clusters

- `ANKKA_CLUSTER_PORT` fixes the cluster's remoting port in `local` mode, which is random by default so
  that several services can share a machine. Fix it on the node that others will join.
- `ANKKA_CLUSTER_SEED_NODES` is a comma-separated list of node addresses to join in `local` mode, such as
  `pekko://ankka@127.0.0.1:17355`. Empty, the node joins itself.

```bash
ANKKA_CLUSTER_PORT=17355 sbt run
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt run
```

### Set by the platform

These are set by the platform on every deployed instance, and a descriptor that sets one is refused.

- `ANKKA_CLUSTER_MODE` selects the cluster overlay; the platform sets `kubernetes`.
- `POD_IP` is the pod's address, which the node binds and advertises to its peers.
- `ANKKA_CLUSTER_SERVICE` is the Kubernetes Service through which peers are discovered.
- `ANKKA_CLUSTER_POD_SELECTOR` is the label selector that identifies this service's pods, so a node never
  mistakes another service's pods for its own.
- `ANKKA_CLUSTER_CONTACT_POINTS` is how many peers must be found before a new cluster forms: the smaller of
  the instance count and two.
- `ANKKA_NAMESPACE_PREFIX` is how a project id becomes a namespace, `ankka-<project>`, which the service
  client uses to address another service by name.

In `kubernetes` mode the remoting port is fixed at 17355, the management port at 7626 and the readiness
port at 7627, and every one of them but readiness is mutual TLS with certificates the platform mounts under
`/var/run/secrets/ankka/`. See [Runtime endpoints](runtime-endpoints.md) and
[Networking and TLS](../platform/networking.md).

### Agents and models

- `ANTHROPIC_API_KEY` is the key for Anthropic's API. A Scala service reads it when it constructs its
  model provider. In a process-hosted service it belongs to the sidecar, which runs the agent loop; the
  platform routes it there.
- `ANKKA_MODEL_NAME` names the Anthropic model a process-hosted service's agents use when they name none.
  It is read by the sidecar.
- `ANKKA_MODEL_SCRIPT` gives the sidecar a scripted model instead of a real one, for tests: a JSON array
  of turns, or the path of a file holding one. Each turn is `{"text": "..."}`, a tool call
  `{"tool": "name", "arguments": {...}}`, several tool calls `{"tools": [...]}`, or `{"refusal": "..."}`,
  consumed in order; `{"when": "<part of the user's message>", "text": "..."}` is a standing rule used once
  the turns run out. With both a key and a script set, the key wins.

Every variable beginning `ANTHROPIC_` or `ANKKA_MODEL_` in a process-hosted service's descriptor goes to the
sidecar, never to the process.

- `ANKKA_MCP_CONNECT_TIMEOUT` (`ankka.agent.mcp.connect-timeout`) is how long the runtime waits for each MCP
  server an agent lists to answer when the service starts, `10s` unless set; a server that does not answer in
  time fails the start, naming it.
- `ANKKA_MCP_CALL_TIMEOUT` (`ankka.agent.mcp.call-timeout`) is how long a call to an MCP server's tool waits
  for its answer, `60s` unless set; a call that takes longer reaches the model as the tool's error.
- `ANKKA_MCP_<SERVER>_URL` is where the MCP server of that name is, required when its declaration gives no
  address and replacing one when it does; `<SERVER>` is the name in upper case with `-` as `_`. Any other
  variable beginning `ANKKA_MCP_` holds the value of a header a declaration names, such as a server's token.
  Like the model's variables, every `ANKKA_MCP_` variable goes only to the platform's program — beside a
  Python or TypeScript process, the sidecar, which connects to the servers — and a WebAssembly module's
  `config` answers it absent. See [MCP servers](../build/mcp-servers.md).

- `TYPESAFE_API_KEY` is the key for TypeSafe AI's API, which answers [judgments](../build/judgments.md). A
  Scala service reads it when it constructs `JevProvider.fromEnv()`, which fails at startup when it is not
  set.
- `TYPESAFE_BASE_URL` is optional: where that provider's requests go instead of `https://api.typesafe.ai`,
  such as a gateway that passes them through unchanged.

Neither is routed to a sidecar: judgments are available to Scala services only.

### Broker topics

- `ANKKA_KAFKA_BOOTSTRAP_SERVERS` is the Kafka bootstrap address. `ProjectionRuntime.fromEnv()` connects
  a Scala service to it, and the sidecar connects a process-hosted service to it. The sidecar needs it only
  for a view sourced from a topic or a consumer that produces to one, and refuses to start without it when
  the service has either, naming the variable. A Scala service may pass its broker to
  `ProjectionRuntime.withKafka` in code instead.
- `ANKKA_KAFKA_TLS_DIRECTORY` is where the certificate a service presents to the broker is, as
  `tls.key`, `tls.crt` and `ca.crt`. With it the service connects over TLS, presenting that certificate
  and picking up its renewal on the next connection; without it, in plain text.
- `ANKKA_KAFKA_TOPIC_PREFIX` is what the broker's names for the project's topics start with. A component
  names a topic as its project declared it, and the prefix is added where the topic is handed to the
  broker.

On an installation with a broker the platform sets all three on every service with components, naming
the installation's broker, the service's own certificate and `<project>.`; for a process-hosted service it
sets them on the sidecar and on the process. A descriptor that sets any variable beginning
`ANKKA_KAFKA_` names a broker of its own instead: the platform sets none of them, and the service connects
as the descriptor says. See [The installation's broker](../platform/broker.md).

- `ANKKA_PROJECT_DECLARATIONS` names the file of the project's declarations — its topics with their
  partitions, compaction and contract, and its declared brokers — which the platform mounts at
  `/var/run/ankka/project/topics.json`. The runtime reads it once when the service starts and refuses a
  component whose stated contract is not the declared one, or that names a broker the project does not
  declare. Unset, nothing is checked. See [Contracts](../build/topics.md#contracts).
- `ANKKA_TOPIC_BROKER_<NAME>_BOOTSTRAP_SERVERS`, `ANKKA_TOPIC_BROKER_<NAME>_SHAPE`,
  `ANKKA_TOPIC_BROKER_<NAME>_SECRET_DIRECTORY` and `ANKKA_TOPIC_BROKER_<NAME>_NAME` are a broker the
  project declares, one set per broker with its name upper-cased and `-` as `_`: its address, the shape
  of its credential (`certificate` or `sasl`), the directory the project secret holding the credential
  is mounted at, and its declared name. The platform sets them on the platform's container of every
  service in the project, and never on a process. A component names the broker for one topic; see
  [A topic on another broker](../build/topics.md#a-topic-on-another-broker).
- `ANKKA_SERVICE_NAME`, or `ankka.service.name`, is a service's name when it runs on a developer's
  machine. Each view or consumer that reads a topic reads under a consumer group named for it, so two
  services on one broker never share one; with no name stated, a group is named for its component alone.
  A deployed service's name is read from the certificate the platform issued it, so the key is not read
  there, and a descriptor that sets the variable is refused. See
  [Broker topics](../build/topics.md#consumer-groups).

### Token verification

A service that verifies its users' tokens lists the issuers it accepts as a named set, read by
`Oidc.authenticate()` in a Scala service and by the sidecar for every other language. The set is not a
setting of `reference.conf`, so it has no row in the table above. For a process-hosted service the
variables go to the sidecar container only; the process never sees them, and a WebAssembly module's
`config` call answers each as absent.

| Variable | Required | Meaning |
|---|---|---|
| `ANKKA_AUTH_ISSUERS` | to verify anything | The issuers' names, comma separated. Each is a word of letters, digits and `-`, starting with a letter, and listed once. |
| `ANKKA_AUTH_<NAME>_ISSUER` | for each name | The issuer a token must name, exactly. A trailing `/` is ignored. |
| `ANKKA_AUTH_<NAME>_JWKS_URL` | for each name | Where the issuer's keys are fetched. |
| `ANKKA_AUTH_<NAME>_AUDIENCE` | for each name | The audience a token must be for. |
| `ANKKA_AUTH_<NAME>_CA` | no | A file of PEM certificates the keys fetch trusts, and nothing else. Without it, the JVM's own trust store. |
| `ANKKA_AUTH_<NAME>_TYP` | no | A `typ` claim a token must carry, such as `Bearer` for Keycloak. Without it, the claim is not checked. |
| `ANKKA_AUTH_<NAME>_CLOCK_SKEW` | no | The tolerance on a token's expiry and start, `60s` by default. It accepts `ms`, `s` and `m` suffixes; a bare number is seconds. |
| `ANKKA_AUTH_REALM` | no | The realm a challenge names, `ankka` by default. |

`<NAME>` is the issuer's name upper-cased, with `-` as `_`. Variables of names that are not listed are not
read, so the control plane's own `ANKKA_AUTH_ISSUER`, `ANKKA_AUTH_JWKS_URL` and `ANKKA_AUTH_JWKS_CA`,
described on [Identity and machine accounts](../platform/identity.md), can sit beside a service's set. A
name listed twice, a name that is not a word, a required variable missing, a CA that is not a file or a
skew that is not a duration stops the service starting, with every problem named at once.

### Process-hosted services

A service in another language runs as a process beside the sidecar, and the two find each other on
loopback. The platform sets these variables on the two containers, and a descriptor may not.

- `ANKKA_PROCESS_PORT` is the port the process serves the protocol on, `9010` by default. The Python SDK
  reads it when it starts listening.
- `ANKKA_PROCESS_ADDRESS` is where the sidecar finds the process, `127.0.0.1:9010` by default.
- `ANKKA_SIDECAR_PORT` is the port the sidecar serves its client API on for the process, `9011` by
  default.
- `ANKKA_SIDECAR_ADDRESS` is where the process finds the sidecar, `127.0.0.1:9011` by default. The Python
  SDK's component client reads it.
- `ANKKA_SIDECAR_BIND` is the address the sidecar's client API binds, `127.0.0.1` by default. It differs
  only when the sidecar runs in a container and the process on the host, as in local development with
  Docker Compose.
- `ANKKA_SIDECAR_DISCOVERY_TIMEOUT` is how long the sidecar waits for the process to answer the discovery
  handshake before giving up, `60s` by default. It accepts `ms`, `s` and `m` suffixes; a bare number is
  seconds.

`ANKKA_LOCAL_CALLER_TOKEN` is the secret a test presents to name a caller outside a cluster. The Python and
TypeScript integration testkits generate one and pass it to the sidecar they start; nothing sets it in a
cluster, where the caller comes from a certificate and the header is not read.

The Python and TypeScript integration testkits read `ANKKA_SIDECAR_IMAGE` to choose the sidecar image they
start. Without it, a released SDK starts `ghcr.io/thinkmorestupidless/ankka-sidecar` at its own version, and an
unreleased one (version `0.0.0`, from a checkout of the repository) starts `ankka-sidecar:latest`.

### Web-hosted services

A web-hosted service's process is given two variables, and a descriptor may not declare either:

- `PORT` is the port the process listens on: the descriptor's `processPort`, `8080` by default.
- `ANKKA_SERVICES_URL` is where the process calls other services by name, `http://127.0.0.1:7630` in a
  cluster.

The platform's proxy beside the process is configured by the operator through variables starting
`ANKKA_PROXY_`; they are the operator's to write. The operator itself reads two more on its own
Deployment: `ANKKA_PROXY_IMAGE`, the proxy image it runs beside every web-hosted service, without which
such a service fails with `operator has no proxy image`; and `ANKKA_HTTPS_PORT`, the port the gateway is
reached on, from which the proxy states the address a request from the internet was sent to. The
installation's overlays set both. [The web hosting reference](web-hosting.md) is what the process is told.

## Settings without a variable

These are overridden in the service's `application.conf` or with a system property.

- `ankka.ask-timeout` is how long a component client call waits before failing with the `Timeout` error
  code, `10s` by default. A process-hosted service's sidecar uses it as the time it waits for the process to
  answer a command.
- `ankka.http.body-timeout` is how long the HTTP server waits for a request body to arrive in full, `10s`
  by default.
- `ankka.grpc.max-message-size` is the largest request a gRPC call may send, `4MiB` by default. A larger
  one is refused `RESOURCE_EXHAUSTED` before any handler runs.
- `ankka.grpc.max-connection-age` is how long a caller's connection to the gRPC server lasts before it is
  asked to reconnect, `2m` by default; it is what brings an instance added to a service into the rotation
  of callers already connected.
- `ankka.grpc.shutdown-grace` is how long calls in progress, streams included, are given to finish when a
  connection reaches its age or the service stops, `5s` by default. What is left then ends `UNAVAILABLE`.
- `ankka.grpc.keepalive-time` and `ankka.grpc.keepalive-timeout` are how often an idle gRPC connection is
  asked whether its caller is still there, `30s`, and how long the answer is waited for, `10s`. A caller
  that went away without saying so is found within the two, and its calls are cancelled.
- `ankka.local-grpc-services."<name>"`, set to `host:port`, is where a service on this machine calls the
  gRPC endpoint of the service called `<name>`, before looking for it among the services running here.
- `ankka.observability.ring-capacity` is how many spans each instance keeps in memory for the local
  console and the metrics endpoint, `4096` by default. The oldest are overwritten; nothing is persisted.
- `ankka.observability.call-window` is how far back a service's topology counts the calls between its
  components, `10m` by default. A call made before the window is forgotten, and two handlers that have not
  called each other inside it are not shown as calling each other.
- `ankka.observability.call-buckets` is how many slices the window is kept as, `60` by default. A call
  leaves the window when its slice does, so more slices forget in finer steps and use more memory for each
  pair of handlers.
- `ankka.observability.max-external-services` is how many other services a topology shows by name, `32`
  by default. Calls to any service beyond that are counted together as other services.
- `ankka.observability.max-external-methods` is how many gRPC methods of other services a call is recorded
  under by name, `256` by default; calls to any method beyond that are recorded together as other methods.
- `ankka.observability.max-counted-handlers` is how many pairs of component and handler the invocation
  counts name, `1024` by default; any beyond are counted together as `(other)`. These are the counts a
  service exports as its metrics, since it started.
- `ankka.telemetry.endpoint`, from `ANKKA_OTLP_ENDPOINT`, is the OpenTelemetry collector a service exports
  its spans and metrics to, `http://host:4318` or `https://host[:port]`; empty, the default, exports
  nothing and starts nothing. `ankka.telemetry.headers`, from `ANKKA_OTLP_HEADERS`, is what is sent with
  every export, as `name=value,name=value`, and is never logged. On the platform the operator sets both
  from the installation's settings and a descriptor may not; read only by a service with the
  `ankka-telemetry-otlp` module. See [Telemetry](../operate/telemetry.md).
- `ankka.telemetry.interval`, `1s`, is how often recorded spans are read and sent, sooner when half the
  trace window is unread; `ankka.telemetry.batch-size`, `512`, is the most spans in one export; and
  `ankka.telemetry.export-timeout`, `5s`, is how long one export may take.
- `ankka.telemetry.max-backoff`, `30s`, is the longest wait between tries while the collector cannot be
  reached; `ankka.telemetry.metric-interval`, `10s`, is how often the metrics are sent; and
  `ankka.telemetry.shutdown-timeout`, `3s`, is how long a stopping instance spends sending what it holds.
- `ankka.telemetry.service-name` and `ankka.telemetry.project` say who an instance is where it has no
  certificate to say so, such as a developer's machine; on the platform the certificate is used instead.
- `ankka.observability.observe.enabled`, `ankka.observability.observe.port` and
  `ankka.observability.observe.peer` start the listener the installation's control plane reads a deployed
  instance's topology from: off by default, on in `kubernetes` mode, on port `7628`, admitting only the peer
  `ankka://platform/controlplane`. Leave them to the overlay.
- `ankka.cluster.formation` and `ankka.cluster.seed-nodes` are set by the cluster overlays. Leave them to
  the overlay.
- `ankka.join-self-if-no-seed-nodes` is `on` in `local` mode and `off` in `kubernetes` mode, where joining
  itself would split the service into several clusters.
- `ankka.tls.cluster-directory` and `ankka.tls.service-directory` are where the cluster and service
  certificates are read from, empty in `local` mode and set by the `kubernetes` overlay.
  `ankka.tls.reload-interval`, `1m`, is how often a changed certificate file is noticed.
- `ankka.http.tls.enabled` makes the HTTP server mutual TLS; `off` locally, `on` in `kubernetes` mode.
- `ankka.probe.enabled` and `ankka.probe.port`, `7627`, are the plain readiness listener, on only in
  `kubernetes` mode.
- `ankka.local-services.<name>` is the address of another service on this machine for the service client,
  for every component that calls one and for the runtime beside a Python or TypeScript process, which is
  given it as a JVM option (`JAVA_OPTS=-Dankka.local-services.<name>=…`),
  such as `"http://127.0.0.1:9001"`. Without it the client asks the local console's registry.

The runtime also sets Pekko's own settings. Two of them shape how a service behaves:

- `pekko.cluster.split-brain-resolver.active-strategy = keep-majority`: after a network partition the side
  with the majority of instances survives. This is why instance counts should be odd.
- `pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout = 120s`: an entity idle for
  two minutes is unloaded from memory, and rebuilt from its journal on its next command.

## The CLI

The `ankka` CLI reads `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA` and `ANKKA_CONFIG`. They are
described with the CLI's other settings in [CLI](cli.md).
