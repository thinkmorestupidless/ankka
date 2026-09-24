---
title: Troubleshooting
description: Symptoms you are likely to meet building, running, deploying and operating ankka services, with the cause of each and what to do about it.
kind: guide
related: [operate/status-and-history.md, operate/logs.md, reference/lifecycle-states.md, reference/limitations.md]
---

# Troubleshooting

Each entry starts from what you see, says why it happens, and says what to do. Start with
`ankka services get <name>`: its `detail` line usually names the problem, and
[Status and history](status-and-history.md) explains every field. For a service that started and then
failed, read its output with `ankka services logs <name> --previous`.

## Running a service locally

### `Address already in use` on port 9000

A service's HTTP server binds port 9000 by default, and something else on the machine already has it,
often another service or a sample you left running. Stop the other process, or set the port for this one:

```bash
ANKKA_HTTP_PORT=9001 sbt run
```

In tests, bind an ephemeral port on loopback rather than the default, so that tests can run beside a
service you are developing. In Scala that is `HttpServer.at("127.0.0.1", 0)(...)` rather than
`HttpServer.of(...)`.

### The service starts but cannot reach Postgres

A locally run service expects the database from `docker compose up -d` on `localhost:5432`, database,
user and password all `ankka`. Check that the container is running. To point at another database, set
`ANKKA_DB_HOST`, `ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and `ANKKA_DB_PASSWORD`; see
[Runtime configuration](../reference/configuration.md).

### Tables are missing after upgrading ankka

Postgres runs its initialisation scripts only when its data volume is empty. After the schema changes —
when you upgrade ankka and regenerate it with `sbt schema`, for example — an existing volume keeps the
old schema. Recreate the volume:

```bash
docker compose down -v && docker compose up -d
```

This deletes your local data.

### A second local node does not join the first

`ANKKA_CLUSTER_SEED_NODES` must name the first node's exact address, and the first node's remoting port
is random unless you fix it with `ANKKA_CLUSTER_PORT`. The address also contains the name the service was
started with, which is `ankka` unless you passed another to `start`:
`pekko://ankka@127.0.0.1:17355`. See [Clusters and instances](../concepts/clustering.md#how-nodes-find-each-other).

### The local console shows no services

The console lists services that announced themselves in `~/.ankka/running` and still answer. A service
running in Kubernetes, including a kind cluster, does not announce itself; the console is for services
run directly on your machine. If a service you just started is missing, wait until it has finished
starting: it announces itself after its extensions, such as the HTTP server, have started.

## Tests

### An integration test times out after the laptop slept

Integration tests run Postgres, and sometimes Kafka or a Kubernetes node, in Docker. A sleeping machine
pauses Docker and every container in it while the test's own deadlines keep counting, so a suite that
spans a sleep fails with timeouts that have nothing to do with your code. A failure whose reported
duration is hours long is the machine, not the test. Keep the machine awake for long runs — on macOS,
`caffeinate -i sbt test` — and rerun the failure.

### An agent test fails with an exhausted script

`TestModelProvider` answers from a script and fails loudly when the script runs out, rather than
returning a default. Two things commonly drain a script unexpectedly. A workflow left running at the end
of one test keeps calling the model and consumes the next test's answers, so let a workflow finish before
the test ends. And one provider shared between an agent and compaction is a race, because compaction runs
asynchronously and may take an answer meant for the agent; give each its own provider.

## The CLI

### `no control plane at http://localhost:9000`

The CLI has no control plane URL configured, or the one configured is not answering. Set it:

```bash
ankka config set url https://api.127.0.0.1.sslip.io:8443
```

Settings are read from flags first, then the environment (`ANKKA_URL`, `ANKKA_TOKEN`, `ANKKA_PROJECT`,
`ANKKA_CA`), then `~/.ankka/config.json`. `ankka config get` shows the effective values.

### `could not verify https://…`

The CLI does not trust the certificate the control plane presented. For a local cluster, the platform's
certificate authority exists only inside that cluster; trust it by name:

```bash
ankka config set ca ~/.ankka/local-ca.crt
```

There is no option to turn certificate verification off. Tools other than the CLI need the same root:
`curl --cacert ~/.ankka/local-ca.crt …`.

### `rejected the login; run 'ankka login'`

The control plane did not accept your credential: you have never logged in to this URL, or your saved
login could no longer be renewed. Run `ankka login`. With `ANKKA_TOKEN` or `--token` set, the message is
`the token was rejected` instead, and the token itself is the problem — expired, or issued by a different
identity provider.

### `not permitted: owner role required …`

You are a member of the organization, and the action needs its owner role. Ask an owner. See
[Tenancy and access](../concepts/tenancy-and-access.md#roles).

### A service or project you know exists is `not found`

For anything in an organization you do not belong to, the control plane answers exactly as for something
that does not exist. Check `ankka whoami`, and whether you are acting in the right project
(`--project`, or `ankka config get`).

### `organization '…' is disabled`

A platform administrator has disabled the organization. Everything in it can be read, and every change
is refused until it is enabled again.

### `no project selected`

Service commands act on a project. Pass `--project <id>`, set `ANKKA_PROJECT`, or save one with
`ankka config set project <id>`.

### The CLI ignores the home directory you gave it

The CLI finds `~/.ankka/config.json` through the JVM's own idea of the home directory, which is fixed when
it starts. `HOME=/tmp/x ankka …` does not change it, and the CLI goes on reading your real configuration.
To use another configuration file, set `ANKKA_CONFIG=/path/to/config.json`.

## Deploying

### The descriptor is refused before anything is sent

`ankka services apply` validates a descriptor on your machine with the same rules the control plane
applies, and lists every problem at once. The common ones: a name that is not a DNS label (lowercase
letters, digits and `-`, starting with a letter); `ANKKA_HTTP_PORT` in `env`, which must be set with the
`port` field instead; a variable the platform sets, such as `ANKKA_CLUSTER_MODE` or `POD_IP`; and a
process-hosted service with no `protocol`. See [Service descriptor](../reference/service-descriptor.md).

### Stuck at `UpdateInProgress` with `no operator has reported on this service`

The control plane wrote the service's resource and nothing in the cluster has acted on it. The operator is
not installed, is not running, or is watching a different namespace prefix. Check the operator's pod in
the platform's namespace.

### `UpdateInProgress (unconfirmed)` with `could not reach the cluster`

The control plane cannot reach the Kubernetes API, so it is restating the last reading it had. The apply
is recorded and will be acted on when the cluster is reachable. This is a platform problem, not a problem
with the service.

### `Unavailable` with `runtime … is outside the platform's supported range`

The descriptor declares a `runtime` version the platform will not run: the platform accepts the same major
version and a minor version equal to its own or one below. No pod was started. Rebuild against a
supported ankka version and update `runtime`, or upgrade the platform. A `protocol` outside the supported
range is refused the same way. See [Upgrade ankka](../deploy/upgrading.md).

### The image cannot be pulled

`services get` reports a rollout problem, and the pods show `ErrImagePull` or `ImagePullBackOff`. The
cluster could not find the image the descriptor names. On a local kind cluster there is no registry: load
the image into the cluster after building it, under exactly the tag the descriptor names:

```bash
kind load docker-image cart:latest --name ankka
```

On any other cluster, push the image to a registry the cluster can pull from and name it in full.

### `Failed` after the rollout deadline, with an image that runs

An instance is ready only once it has joined its cluster and its HTTP server has bound the port the
descriptor declares, 9000 unless `port` says otherwise. An image that listens on nothing, or on a
different port, never becomes ready and is reported `Failed` when the rollout's deadline passes.

- A service that serves no HTTP needs `"http": false` in its descriptor.
- A service listening on another port needs `"port"` set to it.
- An image that is not an ankka service at all is never ready, by design: readiness is the runtime's own
  check. The same is true of an image built with an ankka version that predates the readiness endpoint.

### `operator has no sidecar image`

A process-hosted service needs the sidecar image, and the operator has not been told which image that is.
The platform's installation must set `ANKKA_SIDECAR_IMAGE` on the operator. See
[Install on a cloud cluster](../platform/install-cloud.md).

### A scaled-down Deployment comes back

The operator keeps each service's Deployment as its descriptor says, so scaling it with `kubectl` is
undone on the operator's next pass. Pause the service instead: `ankka services pause <name>`.

## Exposing

### The hostname answers 404 or 500

`ankka services get` shows `route rejected: <reason>` in `detail` when the gateway has not accepted the
service's route or cannot reach its backend. A `404` for every request usually means the gateway does not
admit routes from the service's namespace; a `500` usually means the route was accepted but its backend
reference was not permitted. Both are platform configuration; see
[Networking and TLS](../platform/networking.md).

### `expose` is refused

The hostname is derived as `<service>-<project>.<base domain>`, and `expose` refuses one that cannot work:
a first label longer than 63 characters, or one another exposed service already holds. `a-b` in project
`c` and `a` in project `b-c` both derive `a-b-c`. Rename one of them. See
[Expose a service](../deploy/expose.md).

### Names under `127.0.0.1.sslip.io` do not resolve

Some resolvers refuse addresses that resolve to loopback. Add the names to your hosts file and redeploy
the local platform with a matching base domain:

```bash
echo '127.0.0.1  api.ankka.local cart-checkout.ankka.local' | sudo tee -a /etc/hosts
ANKKA_BASE_DOMAIN=ankka.local ./kustomization/deploy-local.sh
```

## Databases

### `waiting for database` for a minute after the first deploy

A project's first service creates the project's Postgres cluster, which takes a minute or so to become
ready, and each service's database and role are then created in turn. While that happens, `detail` can
show errors that clear by themselves: `role "…" does not exist`, when the database is created a moment
before its owner role, or `secrets "…" is forbidden`, naming the role's password secret, until the
database operator has been told the secret may be read. Both resolve within a minute without anything
being done. An error that persists for several minutes is real.

### Two services delete each other's timers

Two services are sharing one database. The timer sweeper deletes timers that belong to no component it
knows, and view tables are named from their component id alone, so services sharing a database interfere
with each other. The platform provisions a database per service; this happens only when descriptors
supply their own `ANKKA_DB_*` settings and point at the same database. Give each service its own. See
[Databases](../platform/databases.md).

## Services in Python

### A test calling `.json()` on a response fails with `Expecting value`

A handler or route that returns a primitive — a `str`, an `int` — answers `text/plain`, not JSON. Only
records and sum types are JSON. Read such a response with `.text`, and post a `str` body as plain text.

### The sidecar cannot reach the process on Linux

When the sidecar runs in Docker and your process runs on the host, the sidecar reaches it at
`host.docker.internal`. Docker Desktop provides that name; plain Docker on Linux needs
`--add-host=host.docker.internal:host-gateway`, which the repository's compose file and the Python
integration testkit already pass.

### The sidecar gives up on start-up

The sidecar asks your process to describe itself when it starts, and waits up to 60 seconds for an answer
before giving up. Start the process, or check that it is listening on port 9010 and that the sidecar is
pointed at it. If your process answered and the sidecar refused what it described, every problem is
printed in your process's own log as well as the sidecar's.

### `ankka services logs` prints an error for every instance

A deployed Python service's pods have two containers, and `ankka services logs` does not yet choose
between them, which Kubernetes refuses. Read them with `kubectl logs` and a container name, as
[Logs](logs.md#a-service-in-another-language) shows.

## Accounts

### A new user cannot sign in: `Account is not fully set up`

A user created in the identity provider with no first or last name has a pending profile action, and
cannot complete a sign-in until it is resolved. Set both names on the user in the identity provider's
console.
