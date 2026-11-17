# Contract: what runs, and what each container is given

What `ankka local up` composes for a project `shop`. The compose project is `ankka-shop`; every
name below is a compose service in it. Nothing else runs: no part of Kubernetes, the control
plane, the operator, the gateway or telemetry (FR-002).

## The two the platform supplies

| Service | Image | Published on the host | Volume | Ready |
|---|---|---|---|---|
| `ankka-postgres` | `postgres:17-alpine`, user and password `ankka` | `127.0.0.1:<chosen, 5432 when free>:5432` | `pgdata` | `pg_isready` |
| `ankka-kafka` | `apache/kafka:3.8.0`, KRaft, one node, `auto.create.topics.enable=false`, 512 MiB heap | `127.0.0.1:<chosen, 9092 when free>` (listener `HOST`, advertised `localhost:<that port>`) | `kafkadata` | the broker answers its own API |

Containers reach them as `ankka-postgres:5432` and `ankka-kafka:9094` (listener `CONTAINERS`).

## A service, by hosting

Every runtime container (the image for `embedded`, the sidecar for `process` and `wasm`):

| Variable | Value |
|---|---|
| `ANKKA_SERVICE_NAME` | the service's name |
| `ANKKA_HTTP_PORT`, `ANKKA_GRPC_PORT` | the descriptor's `port`, `grpcPort` (when it serves them) |
| `ANKKA_DB_HOST`, `_PORT`, `_NAME`, `_USER`, `_PASSWORD` | `ankka-postgres`, `5432`, the service's name, `ankka`, `ankka`; or `ANKKA_DATABASE=none` |
| `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, `ANKKA_KAFKA_TOPIC_PREFIX` | `ankka-kafka:9094`, `shop.` |
| `ANKKA_SECRET_KEY` | the service's, from `state.json` |
| `ANKKA_OBSERVABILITY_ADDRESS` | `0.0.0.0:7630` |
| `JAVA_TOOL_OPTIONS` | what the descriptor or local values gave, then `-Dankka.local-services.<other>=http://host.docker.internal:<port>` and `-Dankka.local-grpc-services.<other>=host.docker.internal:<grpcPort>` for every other service of the project |

plus the descriptor's literals and the local values (research R9). `extra_hosts` gives
`host.docker.internal` as `host-gateway`. Published: the HTTP and gRPC ports at their own numbers
and `7630` at the chosen observability port, on `127.0.0.1` (and the bridge gateway's address on
Linux, research R7). `ANKKA_CLUSTER_MODE` is unset: each service is a cluster of one.

| Hosting | Services | Memory limit |
|---|---|---|
| `embedded` | `<name>`: the descriptor's image | the `instanceType`'s |
| `process` | `<name>`: the sidecar image, with `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, `ANKKA_SIDECAR_PORT=9011`, and the runtime's share of the environment; `<name>-app`: the descriptor's image, `network_mode: service:<name>`, `ANKKA_PROCESS_PORT=9010`, `ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011`, and the process's share | the `instanceType`'s; `resources.process.memory` or 128 MiB |
| `wasm` | `<name>-module`: the descriptor's image, run once, writing the module into a volume; `<name>`: the sidecar image with the volume read-only and `ANKKA_WASM_MODULE`, started when the first has ended well | the `instanceType`'s |
| `web` | `<name>`: the proxy image with `ANKKA_PROXY_LOCAL=on`, `ANKKA_PROXY_SERVICE`, `ANKKA_PROXY_PORT`, `ANKKA_PROXY_PROCESS_PORT`, `ANKKA_PROXY_MOUNTS`, and `ANKKA_PROXY_LOCAL_SERVICES=<service>=http://host.docker.internal:<port>,...`; `<name>-app`: the descriptor's image, `network_mode: service:<name>`, `PORT`, `ANKKA_SERVICES_URL=http://127.0.0.1:7630`, its literals and local values | 192 MiB; none |

The environment's split between a sidecar and its process is `PlatformVariables.runtimeOnly`, the
operator's.

A service is created only after the companions it needs have answered their `ready` check, which
the command asks from the host; the order is the command's, not compose's (research R15).

## The proxy's local mode

| Variable | Meaning |
|---|---|
| `ANKKA_PROXY_LOCAL` | `on`: serve plain HTTP, read no certificate, treat every caller as the local machine. Anything else, or unset: today's behaviour, unchanged |
| `ANKKA_PROXY_LOCAL_SERVICES` | `name=url` pairs, comma-separated: where each service the mounts and the process name is. A service not listed is answered 503 naming it, as `ankka local web` answers |

With `ANKKA_PROXY_LOCAL=on`, `ANKKA_PROXY_PROJECT` defaults to `local` and `ANKKA_PROXY_CALLERS`
is not read. The operator sets neither variable; `ProxyEnvironmentSuite` holds that.

## A companion

`<name>`: its image, command, environment (tokens written), files mounted read-only, ports
published on `127.0.0.1` (and the gateway's address on Linux), its memory limit if it states one.
With `database: true`, a database `<name>` exists in `ankka-postgres` before it starts.

## Not an installation

What a local project does not have, which the limitations page says in these words (FR-033): a
service has no identity and no certificate; every caller is the local machine; a topic is not
authorised; a contract is not checked when a service starts; nothing is exposed through a
gateway; there is one instance of each service; nothing is rolled out, rolled back or scaled.
