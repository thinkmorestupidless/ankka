# Run a service locally

> Run an ankka service on your own machine against a local Postgres, configure its database and HTTP port, form a two-node cluster in two terminals, and run a Python service beside the sidecar.

Source: https://docs.ankka.cloud/deploy/run-locally/
A Scala service runs on a laptop with `sbt run` and a Postgres started by Docker Compose. It needs no
other configuration: the runtime's defaults point at a database named `ankka` on `localhost:5432` and
serve HTTP on port 9000. A Python service runs the same way, with ankka's runtime started beside it as a
container called the sidecar.

The service that runs locally is a real single-node cluster. It takes the same code path a multi-node
deployment takes, so what works on a laptop works deployed.

## Start the database

A service created from the template carries a `docker-compose.yml` for Postgres and an sbt task that
extracts ankka's database schema from the runtime library:

```bash
sbt schema                # writes the runtime's schema files to target/ddl
docker compose up -d      # Postgres 17 on localhost:5432, initialised from target/ddl
```

The schema is the journal, snapshot, durable state, projection offset and timer tables the runtime reads
and writes. It comes from the `ankka-runtime` artifact your build resolves, so it always matches the
runtime version the service runs.

**Postgres applies its initialisation directory only to an empty data volume.** After `sbt schema`
changes anything, for example after [upgrading ankka](upgrading.md), recreate the volume:

```bash
docker compose down -v && docker compose up -d
```

`down -v` deletes the local database's data. That is the intended cost of a schema change on a laptop.

## Run the service

```bash
sbt run                   # HTTP on http://localhost:9000
```

The service logs every route it serves as it starts, then answers:

```bash
curl -XPOST localhost:9000/items/i1 -H 'content-type: application/json' -d '{"name":"Widget","count":2}'
curl localhost:9000/items/i1
```

Stop it with ctrl-c and start it again: the item is still there, because an entity rebuilds its state
from the journal in Postgres, not from memory.

## Configure the database and HTTP port

Every setting a service needs on a laptop has a default and an environment variable that overrides it:

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_DB_HOST` | `localhost` | Postgres host |
| `ANKKA_DB_PORT` | `5432` | Postgres port |
| `ANKKA_DB_NAME` | `ankka` | database name |
| `ANKKA_DB_USER` | `ankka` | database user |
| `ANKKA_DB_PASSWORD` | `ankka` | database password |
| `ANKKA_HTTP_PORT` | `9000` | the port the HTTP server binds |
| `ANKKA_HTTP_INTERFACE` | `0.0.0.0` | the interface the HTTP server binds |

For example, a second service on the same machine, with its own database and port:

```bash
ANKKA_DB_NAME=orders ANKKA_HTTP_PORT=9100 sbt run
```

**Never point two services at the same database.** Timers, view tables and projection offsets are keyed
by component id alone, so two services sharing a database overwrite and delete each other's rows. Create
a database per service, locally as the platform does when deployed. See
[Databases](../platform/databases.md) for the reasons.

The complete list of variables and settings is in [Runtime configuration](../reference/configuration.md).
A service can also override any setting in its own `src/main/resources/application.conf`.

## Run two nodes as one cluster

Two copies of a service on one machine can form one cluster, which is the quickest way to see sharding
and hand-off without Kubernetes. The first node needs a fixed cluster port so the second can name it:

```bash
# terminal 1
ANKKA_CLUSTER_PORT=17355 sbt run

# terminal 2
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt run
```

An item written through `:9000` reads back through `:9001`, because both nodes share one set of
entities, sharded across them, and one journal.

| Variable | Default | Meaning |
|---|---|---|
| `ANKKA_CLUSTER_PORT` | random | the port this node's cluster remoting binds |
| `ANKKA_CLUSTER_SEED_NODES` | empty | comma-separated addresses of existing nodes to join, each `pekko://ankka@host:port` |

With neither set, a node picks a random remoting port and joins itself. That is why several services and
test suites can run on one laptop without knowing about each other, and why a plain `sbt run` needs no
cluster configuration at all. See [Clusters and instances](../concepts/clustering.md) for how the same
service forms its cluster when deployed.

## Run a Python service

A Python service is a process, and ankka's runtime runs beside it as the sidecar. Locally the sidecar is
a container: the ankka repository's `docker-compose.yml` has it under the `polyglot` profile, with a
Postgres for it to use.

```bash
docker compose --profile polyglot up -d      # Postgres and the sidecar; HTTP on localhost:9000
uv run python main.py                        # your process, listening on 9010; the sidecar finds it
curl localhost:9000/carts/c1
```

The compose file expects an image named `ankka-sidecar:latest` in the local Docker daemon. From a
checkout of the ankka repository, `sbt sidecar/Docker/publishLocal` builds it.

The sidecar reaches your process at `host.docker.internal:9010`. Docker Desktop provides that name; on
Linux the compose file maps it with `host-gateway`, which is also what any compose file of your own needs.
Your process never binds an HTTP port and never connects to the database: the sidecar serves the routes
your process declared, owns the journal, and forwards each request to your process over gRPC.

Stop both and start both, and the data is still there, in the sidecar's journal in Postgres. See
[Services in other languages](../concepts/polyglot.md) for how the two halves divide the work.

## Give agents a model key

A service with agents needs a model provider. For the Anthropic provider the key comes from the
environment:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
sbt run
```

In a Python service the agent loop runs in the sidecar, so the key is set on the sidecar's container, not
on your process. `ANKKA_MODEL_NAME` on the sidecar chooses the model. Tests need no key: see
[Testing](../build/testing.md).

## See what it is doing

The local console lists every ankka service running on the machine and shows each one's components,
routes, traces and agent sessions:

```bash
ankka local console       # http://localhost:9889
```

Start it before or after the service; the order does not matter. See
[The local console](../operate/local-console.md).
