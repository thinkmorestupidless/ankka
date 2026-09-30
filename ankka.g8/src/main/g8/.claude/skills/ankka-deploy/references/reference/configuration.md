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

Environment variables reach the configuration through `\${?VARIABLE}` substitutions, so an environment
variable overrides a default only where the table lists one. In the `kubernetes` overlay the substitutions
are required rather than optional: a missing `POD_IP` is a startup failure naming it, not a node that
quietly binds loopback.

## Settings

The table is generated from the runtime's configuration files.

| Variable | Configuration key | Default | Applies in |
|---|---|---|---|
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
| `ankka.tls.cluster-directory` | `""` | every service |
| `ankka.tls.service-directory` | `""` | every service |
| `ankka.tls.reload-interval` | `1m` | every service |
| `ankka.http.tls.enabled` | `off` | every service |
| `ankka.probe.enabled` | `off` | every service |
| `ankka.probe.port` | `7627` | every service |
| `ankka.observability.ring-capacity` | `4096` | every service |
| `ankka.http.body-timeout` | `10s` | every service |
| `ankka.cluster.formation` | `join-self-or-seeds` | local mode |
| `ankka.join-self-if-no-seed-nodes` | `on` | local mode |
| `ankka.cluster.formation` | `bootstrap` | kubernetes mode |
| `ankka.join-self-if-no-seed-nodes` | `off` | kubernetes mode |
| `ankka.tls.cluster-directory` | `"/var/run/secrets/ankka/cluster"` | kubernetes mode |
| `ankka.tls.service-directory` | `"/var/run/secrets/ankka/service"` | kubernetes mode |
| `ankka.http.tls.enabled` | `on` | kubernetes mode |
| `ankka.probe.enabled` | `on` | kubernetes mode |
## What each variable means

### HTTP

- `ANKKA_HTTP_INTERFACE` is the address the HTTP server binds, `0.0.0.0` by default.
- `ANKKA_HTTP_PORT` is the port the HTTP server binds, `9000` by default. On the platform it is set from
  the descriptor's `port`, and a descriptor may not set it directly. Set it locally to run a second
  service beside the first.

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

Never point two services at one database. Timers, view tables and projection offsets are not separated by
service, so two services sharing a database delete each other's timers and overwrite each other's views.

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

- `TYPESAFE_API_KEY` is the key for TypeSafe AI's API, which answers [judgments](../build/judgments.md). A
  Scala service reads it when it constructs `JevProvider.fromEnv()`, which fails at startup when it is not
  set.
- `TYPESAFE_BASE_URL` is optional: where that provider's requests go instead of `https://api.typesafe.ai`,
  such as a gateway that passes them through unchanged.

Neither is routed to a sidecar: judgments are available to Scala services only.

### Broker topics

- `ANKKA_KAFKA_BOOTSTRAP_SERVERS` is the Kafka bootstrap address for a process-hosted service. The sidecar
  needs it only for a view sourced from a topic or a consumer that produces to one, and refuses to start
  without it when the service has either, naming the variable. A Scala service passes its broker to
  `ProjectionRuntime.withKafka` in code instead.

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

## Settings without a variable

These are overridden in the service's `application.conf` or with a system property.

- `ankka.ask-timeout` is how long a component client call waits before failing with the `Timeout` error
  code, `10s` by default. A process-hosted service's sidecar uses it as the time it waits for the process to
  answer a command.
- `ankka.http.body-timeout` is how long the HTTP server waits for a request body to arrive in full, `10s`
  by default.
- `ankka.observability.ring-capacity` is how many spans each instance keeps in memory for the local
  console and the metrics endpoint, `4096` by default. The oldest are overwritten; nothing is persisted.
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
  such as `"http://127.0.0.1:9001"`. Without it the client asks the local console's registry.

The runtime also sets Pekko's own settings. Two of them shape how a service behaves:

- `pekko.cluster.split-brain-resolver.active-strategy = keep-majority`: after a network partition the side
  with the majority of instances survives. This is why instance counts should be odd.
- `pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout = 120s`: an entity idle for
  two minutes is unloaded from memory, and rebuilt from its journal on its next command.

## The CLI

The `ankka` CLI reads `ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`, `ANKKA_CA` and `ANKKA_CONFIG`. They are
described with the CLI's other settings in [CLI](cli.md).
