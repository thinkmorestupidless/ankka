# Research: A project started on one machine with one command

Decisions the plan rests on, each with why and what was set aside. Facts are from the tree at
`c5238ad07`; file and line references are where each was read.

## What the tree already gives

- **A project's definition** (spec 062): `context-map.json` at the root, parsed and checked by
  `core`'s `ContextMap` (strict codec, `check(root, map)`), and `services/<name>/service.json` for
  each service, read strictly by the CLI's `Descriptors.read`. Nothing else finds a service.
- **A descriptor says** image, `env` (a literal `value` or a `secretKeyRef`), `resources.instanceType`
  (`small` 512 MiB, `medium` 1024, `large` 2048), `database` (`"none"` for none), `http`, `port`,
  `grpc`, `grpcPort`, `runtime`, `hosting` (`embedded`, `process`, `wasm`, `web`), `protocol`,
  `mounts`, `callers`, `processPort`.
- **A service outside a cluster finds another by name** through `ankka.local-services."<name>"`
  (a base URL; `ankka.local-grpc-services."<name>"` for gRPC), else through the entry the other
  announced in `~/.ankka/running` (`HttpServiceClients.localAddress`, `GrpcClients.defaultLocate`,
  `ServiceRegistration`). The key is configuration only: no environment variable sets it.
- **The local console lists what is announced**: a file per process in `~/.ankka/running`
  (`-Dankka.running.dir`), naming an observability address the console asks for everything else.
  The CLI's reader (`LocalSource.entries`) deletes an entry nothing answers for. The endpoint binds
  loopback on an ephemeral port unless `ANKKA_OBSERVABILITY_ADDRESS` names another, which exists
  so Docker can publish it, and it always reports `http://127.0.0.1:<port>` for itself and for the
  service's HTTP address.
- **The schema of a runtime version** is in two published places: the `ankka-runtime` jar
  (`ankka/ddl/*.sql`) and the sidecar image (`/opt/docker/ddl`). Every file is
  `CREATE ... IF NOT EXISTS`. The CLI carries none today.
- **A topic is declared** only by `ankka projects topics set <name> --partitions ...` to
  `PUT /projects/{id}/topics/{name}` (`TopicDeclarationRequest`). No file form exists. A service
  hands the broker `ANKKA_KAFKA_TOPIC_PREFIX` + the declared name; the operator sets the prefix
  `<project>.` and a local run sets none.
- **The CLI's dependencies** are `controlplane-api`, `proxy-core` and decline: no Docker client, no
  Kafka client, no database driver, no YAML. It shells out with `ProcessBuilder` and ships as a
  GraalVM native image whose resources are named by glob and proved by `cli/native-smoke.sh`.
- **The proxy image serves mutual TLS only** (`proxy/Main` requires cert-manager's directory); the
  plain engine over loopback is `proxy-core`'s, which `ankka local web` runs inside the CLI.
- **SDK processes listen on loopback**, as in a pod, where the sidecar shares their network.

## R1. The command drives `docker compose` over a file it writes

**Decision**: `ankka local up` renders one compose document for the project into
`.ankka/local/compose.json` (JSON is YAML, so no YAML writer is needed) and runs
`docker compose -p ankka-<project> -f <file> ...`. The CLI talks to nothing but the `docker`
executable (`-Dankka.docker` names another, for tests).

**Rationale**: compose already does what FR-007 and FR-008 ask: it starts what is not running,
recreates a container whose image or environment changed and leaves the rest, stops everything by
project name, and removes volumes with `down -v`. It gives `logs` and `ps` for FR-028 and FR-029.
A developer can read the file. No client library enters the native image.

**Alternatives considered**: `docker run` per container with labels (reimplements change
detection, ordering and teardown); a Docker client library (reflection in the native image, and a
dependency for one command); testcontainers (a test library, and it removes what it started).

**Consequences**: Docker Compose v2 is required beside Docker; absent, the command is refused
saying so. The rendered file holds the local values in clear, which is why `.ankka/` is ignored
by git (the template already ignores it) and why no command prints the file.

## R2. Pure plan, one executor

**Decision**: three layers in `cli/local`. `LocalProject.read(dir)` reads the four kinds of file
into values or problems. `LocalPlan.of(project, selection, state, machine)` is pure: it answers
every static problem together, or a plan (the compose document, the databases, the topics, the
announcements, the readiness checks, what to print). `Engine` is the one thing that touches the
machine; `DockerEngine` is its implementation and a scripted one drives the offline suites.

**Rationale**: the family's rule (effects are inert data; pure `render`, one executor), and
FR-010: every problem reported together is a property of a pure function over everything read.
`LocalPlanSuite` proves the environment, the refusals and the compose shape of each hosting with
no Docker.

## R3. The files a project's repository holds

**Decision** (asked; Trevor, 2026-11-17): two new files at the project's root beside
`context-map.json`.

- `topics.json`: the topic declarations, the same file `ankka projects topics apply` sends to an
  installation.
- `local.json`: what is only true on a developer's machine: the local values, the companions, and
  the command that runs a service from its source where the default does not fit.

Both are read strictly (an unknown field is refused by name), as the context map and a descriptor
are. `contracts/project-files.md` has the shapes.

**Alternatives considered**: sections in `context-map.json` (the design document would carry a
laptop's values, and every reader of the map would see them); three files.

## R4. Topic declarations: one file for the machine and the installation

**Decision**: `core` gains `TopicDeclarations` (the file's types, strict codec and its own
problems), beside `ContextMap`, because `ContextMap.check` must read it for FR-015 and `core`
cannot see `controlplane-api`. The CLI maps each declaration to the existing
`TopicDeclarationRequest`, applies `ProjectTopics.problems` (the control plane's own rules) to it,
and either makes it on the local broker or sends it.

`ankka projects topics apply [--dir] [-p project]` (asked; Trevor, 2026-11-17) sends every
declaration through the existing declare route, one at a time, with `set`'s removal question. It
removes no topic the file does not name. Nothing on the control plane changes.

`ContextMap.check` gains three problems: a topic crossing whose sender is a service of this
project and whose topic `topics.json` does not declare (or there is no `topics.json`); a crossing
whose contract or schema differs from the declaration's; and whatever `TopicDeclarations` finds in
the file itself. A crossing sent by another project's service names that project's topic and is
not held to this file.

**Consequence, deliberate**: a project made with the 062 template has topic crossings and no
`topics.json`, so `ankka context-map check` from this release fails in it until the file is added.
The message says what to add. FR-015 asks for exactly this; the pull request and the
documentation say it.

**Alternatives considered**: keeping the file local only (the two could then differ, which FR-011
forbids); reusing the operator's `ANKKA_PROJECT_DECLARATIONS` file form (it holds a fingerprint,
not a schema, and no settings).

## R5. Topics on the local broker

**Decision**: one `apache/kafka:3.8.0` broker in KRaft mode (the image the template and the test
kits already use) with `auto.create.topics.enable=false` and a data volume. Topics are made by the
broker's own tools inside its container (`kafka-topics.sh`), after the broker is ready and before
any service container is created:

1. one `--describe` of every topic;
2. each declared topic that is missing is created with its partitions, replication factor 1, its
   cleanup policy (`compact` when `compacted`, else `cleanup` when stated) and the retention and
   compaction settings it states, creations run together in one shell;
3. a topic that exists with other partitions or another cleanup policy than declared is a problem
   naming the topic and both values and `ankka local down --volumes`, and no service is started
   (FR-014).

A topic's name on the broker is `<project>.<name>`, and every service is given
`ANKKA_KAFKA_TOPIC_PREFIX=<project>.`, so the name on the wire is the installation's (the spec's
assumption). `copies` and `minInSync` are an installation's and are not applied to one broker.

**Rationale**: no Kafka client in the CLI (a native image with kafka-clients is a project of its
own). A second `up` with nothing changed costs one describe, which keeps SC-003's thirty seconds.

**Not done**: mounting a declarations file and setting `ANKKA_PROJECT_DECLARATIONS`, which would
make the runtime refuse an undeclared topic and check contracts at start. The living feature has a
running service publish to an undeclared topic and be refused by the broker, which a start
refusal would prevent; and the limitations page is the honest place for "contracts are checked by
`context-map check` and the project's tests, not by a local project".

## R6. One database server, and the schema of each service's runtime

**Decision**: one `postgres:17-alpine` with a data volume. For each service whose descriptor does
not say `"database": "none"`, and each companion that asks, the command creates a database named
for it if none exists (`psql` inside the container), then applies the schema of the service's
runtime version.

The schema of version `v` comes from, in order:

1. the CLI's own copy, when `v` is the CLI's version or the descriptor names no runtime: the build
   copies `kustomization/components/postgres/ddl/` onto the CLI's classpath as `ankka/ddl/`, by
   glob, as the templates are copied;
2. `~/.ankka/cache/ddl/<v>/` (`-Dankka.cache.dir`);
3. the sidecar image of that version, `/opt/docker/ddl`, copied out with `docker create` and
   `docker cp` as the SDK test kits do, into the cache.

What a database holds is recorded on it: `COMMENT ON DATABASE "<service>" IS 'ankka-schema
<version> <sha256 of the files>'`. On a later `up`: the same digest, nothing is done; a newer
version of the same major, the files are applied (every statement is `IF NOT EXISTS`, and within a
supported range the schema is additive) and the command says the service's schema moved; an older
version or another major is a problem naming the service, both versions and the command that
removes that one database, and no service is started. Nothing is ever dropped by the command
except by `down --volumes`.

**Rationale**: FR-003 and the two schema edge cases. The CLI's own copy means a project at the
CLI's version (every project just made from the template) pulls no sidecar image for an all-Scala
project and works offline.

**Alternatives considered**: reading the jar out of each service's image (the layout is the
image's own; a Java or Kotlin image differs from a Scala one); fetching the runtime jar from Maven
Central (a second network path, and nothing for an unreleased version); one schema for all (the
spec refuses it).

**A new DDL file** is picked up by the glob; the seven lists in `kubernetes.md` are unchanged.

## R7. One address for every service, whoever runs it

**Decision**: every service of the project, whether the command started it or the developer runs
it from its source, answers on the host at the port its descriptor names. A container's port is
published on the host at the same number. Every container is told where every other service is
with the one address that reaches the host from a container:

```text
JAVA_TOOL_OPTIONS=-Dankka.local-services.billing=http://host.docker.internal:9001 ...
                  -Dankka.local-grpc-services.billing=host.docker.internal:9091 ...
```

A service run from its source finds the others through `~/.ankka/running`, as it does today,
where the command has written an entry for each container (R8).

**Rationale**: FR-005 and FR-026, and SC-007's "without another service restarted". Because a
service's address does not depend on who runs it, moving one between a container and the
developer's terminal changes no other container's environment, so compose restarts nothing.
`JAVA_TOOL_OPTIONS` is read by every JVM, whatever the image's start script, so it works for a
Scala, Java or Kotlin image and for the sidecar, at every runtime version that knows
`ankka.local-services` (feature 025 on). A value the descriptor or the local values give for
`JAVA_TOOL_OPTIONS` is kept and the command's options are appended.

**Linux**: `host.docker.internal` is added to each container as `host-gateway`, and a port
published on `127.0.0.1` alone is not reachable at the gateway's address, so on Linux each port is
published on `127.0.0.1` and on the default bridge's gateway address, never on every interface.
On macOS (Docker Desktop, OrbStack) `127.0.0.1` alone is enough. CI proves the Linux path;
quickstart §6 is the macOS one.

**Alternatives considered**: compose's own names (`http://billing:9001`) for containers and
`host.docker.internal` for a service run from source (moving a service restarts every other
one); a forwarding container standing in for a service left out (another image and another
process per service); a new `ANKKA_LOCAL_SERVICES` variable in the runtime (only a service built
on this release would read it).

## R8. The local console and a service run from source find containers

**Decision**: each runtime container is given `ANKKA_OBSERVABILITY_ADDRESS=0.0.0.0:7630`, published
on `127.0.0.1` at a port the command chooses and keeps. When a service is ready the command writes
`~/.ankka/running/<project>.<service>.json` with that address and an `"owner"` field naming the
project's directory. `ankka local down` removes the project's entries.

`LocalSource` (the console's, `ankka mcp`'s and `ankka local web`'s reader) stops deleting an
entry that has an `owner` when nothing answers for it: it leaves it out of the listing and keeps
the file, so a container that is restarting is listed again when it answers. An entry without an
owner is swept as before. `ankka local up` and `ankka local status` rewrite the entries of what is
running.

**Rationale**: FR-030 and FR-026 with no change to any released runtime: the entry is the form
the runtime already reads, and the endpoint already reports loopback addresses that are true on
the host once the ports are published at the same numbers. A container cannot announce itself:
its file would be `<pid>.json`, every container's pid is 1, and its home is not the host's.

**Readiness** is the same endpoint: a service is ready when `GET /observability/service` answers
and, for a service that serves HTTP, lists an HTTP address. A web-hosted service has no runtime
and is ready when its public port accepts a connection; it is not listed by the console, as with
`ankka local web` today.

## R9. The environment of a service

**Decision**: a service container's environment is built in this order, later winning, and the
last group can be given by nobody else:

1. the descriptor's literal variables;
2. the local values for every service (`"*"`), then for this service;
3. the command's own: `ANKKA_SERVICE_NAME`, `ANKKA_HTTP_PORT`, `ANKKA_GRPC_PORT`, `ANKKA_DB_HOST`,
   `ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER`, `ANKKA_DB_PASSWORD` (or `ANKKA_DATABASE=none`),
   `ANKKA_KAFKA_BOOTSTRAP_SERVERS`, `ANKKA_KAFKA_TOPIC_PREFIX`, `ANKKA_SECRET_KEY`,
   `ANKKA_OBSERVABILITY_ADDRESS`, the hosting's own (`ANKKA_PROCESS_*`, `ANKKA_SIDECAR_*`,
   `ANKKA_WASM_MODULE`, `PORT`, `ANKKA_SERVICES_URL`), and the appended `JAVA_TOOL_OPTIONS`.

Problems, all static and all reported together: a variable read from a secret with no local value
(naming service and variable, FR-018); a local value named by `PlatformVariables.PlatformOnly`,
by `ANKKA_SERVICE_NAME`, or beginning `ANKKA_DB_`, `ANKKA_KAFKA_`, `ANKKA_CLUSTER_`,
`ANKKA_SIDECAR_`, `ANKKA_PROCESS_` or `ANKKA_WASM_`, or `ANKKA_SECRET_KEY`, `ANKKA_DATABASE`,
`PORT` or `ANKKA_SERVICES_URL` (naming service and variable, FR-019); a local value for a service
the context map does not name. A descriptor that sets `ANKKA_DB_*` or `ANKKA_KAFKA_*` (its own
database or broker on an installation) has those dropped locally, and the command says so once.
The list of the command's own names is one value, `LocalVariables`, which the plan and the
refusal both read.

`ANKKA_SECRET_KEY` is made once per service and kept in `.ankka/local/state.json`, so a service's
secret store reads what it kept across `down` and `up`; `down --volumes` removes it with the data.

A local value may hold `{{companion.<name>}}` (host and port) or `{{companion.<name>.url}}`
(`http://` in front), which the command writes as the address that reaches the companion from
where the service runs (FR-024): `host.docker.internal:<port>` in a container, `localhost:<port>`
for a service run from its source.

**Never printed** (FR-020): no command prints an environment, the compose file or a local value;
a refusal names a variable, never its value; `docker compose`'s own output is passed through only
for `logs`, which is the service's own log.

## R10. Each hosting, as a pod is composed

**Decision**: the command composes each hosting from the same parts the operator renders
(`Rendering.containersFor`), with the split of the environment done by the same function
(`PlatformVariables.runtimeOnly`).

| Hosting | Containers | Notes |
|---|---|---|
| `embedded` | `<service>`: the descriptor's image | memory limit from `instanceType` |
| `process` | `<service>`: the sidecar image, `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, `ANKKA_SIDECAR_PORT=9011`; `<service>-app`: the descriptor's image in the sidecar's network (`network_mode: service:<service>`), `ANKKA_PROCESS_PORT=9010`, `ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011` | the pod's shared loopback |
| `wasm` | `<service>-module`: the descriptor's image run once into a volume; `<service>`: the sidecar image with `ANKKA_WASM_MODULE` | as the init container does |
| `web` | `<service>`: the proxy image in its new local mode; `<service>-app`: the descriptor's image in the proxy's network, `PORT`, `ANKKA_SERVICES_URL=http://127.0.0.1:7630` | below |

The sidecar image is `ghcr.io/thinkmorestupidless/ankka-sidecar:<v>`, `v` the descriptor's
`runtime` or the CLI's version; the proxy image is the CLI's version (a web descriptor may name no
runtime). `ANKKA_SIDECAR_IMAGE` and `ANKKA_PROXY_IMAGE` in the developer's environment name others,
as they do for the templates' compose files and the operator; a development build of the CLI
(a version that is no release) defaults to `ankka-sidecar:latest` and `ankka-proxy:latest`, as the
SDK test kits do.

**The proxy gains a local mode**, the one platform change outside the CLI and `core`:
`ANKKA_PROXY_LOCAL=on` makes `proxy/Main` run the engine with `Transport.plain`, no certificate,
every caller the local machine, and a locator over `ANKKA_PROXY_LOCAL_SERVICES`
(`name=url,name=url`). It is the engine `ankka local web` already runs in the CLI, in the image
that already holds it. The operator never sets either variable, and a descriptor cannot reach the
proxy's environment.

**Alternatives considered**: running `ankka local web`'s engine from the CLI (the command returns;
nothing would be left to serve); leaving web-hosted services out (the spec's edge case names them).

## R11. Memory

**Decision**: each service container's memory limit is its `instanceType`'s (512, 1024 or 2048
MiB), a process container's is `resources.process.memory` (128 MiB when unset), the web proxy's
192 MiB, as the operator renders them. No CPU limit is set: half a core, which `small` is on an
installation, would make a start several times slower for no gain on a machine the developer
owns. The broker runs with a 512 MiB heap, Postgres with its defaults. A companion states its own
`memory` or has none.

Six `small` services are 3 GiB of limits; SC-002's 8 GiB leaves the broker, the database server
and a companion more than 4 GiB. **SC-002 to SC-004 are kept as written and measured during the
build** against a six-service project made from the template with one companion, on the 10-core
32 GB machine, and recorded in `specs/066-local-up/runs/measurements.md`. Nothing exists to
measure before the feature does; if a figure is missed the build asks whether to change the figure
or the design.

## R12. One service from its source: `ankka local run`

**Decision** (asked; Trevor, 2026-11-17): `ankka local run <service> [-- command...]` replaces the
template's `scripts/run.sh`. It:

1. starts what the service needs and nothing else: the database server, the broker, its database
   with its schema, the project's topics, and the companions it needs;
2. sets the environment R9 describes, with the addresses that are true on the host
   (`ANKKA_DB_HOST=localhost` and the port the command published, `ANKKA_KAFKA_BOOTSTRAP_SERVERS`
   the broker's host listener);
3. runs the service in the foreground: the command after `--`, else the one `local.json` names
   for it under `run`, else the default for its language, found as the template's
   `service-language.sh` finds it (`sbt <service>/run`, `uv run python -m <module>.main`,
   `npm start`, `go run .`, `./mvnw -q exec:java`, `./gradlew --no-daemon -q run`).

A process-hosted service's sidecar, and a module's runtime, are started as containers by the same
command (the sidecar dials the host process at `host.docker.internal`, as the templates' compose
files have it today; a Rust service runs `cargo module` and its runtime loads the built module).
A web-hosted service is run behind `LocalWeb`'s engine, as `ankka local web -- <command>` does.

The service announces itself as it always has, so containers reach it at the address they
already hold (R7) and the console lists it. `ankka local up --without billing` prints
`ankka local run billing` for each service left out (FR-009, FR-027). Run with no project up, it
is "one service on its own with nothing configured" (FR-025's new-template form).

**FR-025 for a project made from the earlier template**: its `docker-compose.yml` and scripts are
its own files and keep working; nothing the runtime reads has changed. The documentation says how
to move to the command and that the two should not be up at once (both want 5432 and 9092, which
is the port refusal).

**The template** drops `docker-compose.yml`, `scripts/databases.sh` and `scripts/run.sh`, and
`ankka init <service>` inside a project stops writing a compose file for a process or module
service (`Projects.sidecarCompose`, `runtimeCompose`). It gains `topics.json` declaring the one
topic its context map crosses. It keeps `scripts/service-language.sh` and
`scripts/changed-services.sh`, which its workflows use, and `sbt schema` goes with the scripts
that needed it.

## R13. Ports

**Decision**: a service's ports are its descriptor's, never chosen. Before anything starts the
plan refuses two services naming one port (naming both) and a port that something else on the
machine holds (naming the service and the port; a port held by this project's own running
container, or by this project's service run from source and left out of this `up`, is not taken).

The command chooses, once, and keeps in `.ankka/local/state.json`: the host ports of the
database server (5432 when free) and the broker (9092 when free), each service's observability
port, a process service's ports when it is run from source, and a companion port that names no
host port. A second project on the machine therefore gets its own database server and broker on
other ports and meets the first only where two services name one port, which is the refusal.

## R14. What is checked, and when

**Decision**: FR-010 in two tiers, because two of the checks need the thing they check to be
running.

- **Before anything is started**, together: Docker and Compose present and running; the context
  map read and checked (`ContextMap.check`, which now includes the topics); every named service's
  descriptor read strictly and valid; `topics.json` and `local.json` read strictly; services named
  on the command line are the project's; ports; the environment (R9); companions (names, images,
  what they need); images present or pulled. Any problem: all are printed, one a line, nothing is
  started, exit 1.
- **After the database server and the broker are ready, before any service or companion is
  created**, together: a topic that exists otherwise than declared (R5); a database whose schema
  cannot be moved to the service's runtime (R6). Any problem: no service is started; the database
  server and the broker are left running, and the command says so.

The spec's SC-006 (a missing local value, a taken port and an undeclared topic in one run) is all
first tier.

The command also says at start, without failing, which services named by the context map's
crossings it does not hold (another project's), per the edge case.

## R15. Order, readiness and failure

**Decision**: `up` runs in this order: first-tier checks; render; database server and broker up
and ready (compose health checks); databases and schemas; topics; second-tier checks; companions
up, each waited for by its `ready` check from the host; services up, all together, leaving out
any whose companion was not ready; each service waited for (R8); entries
written; the table printed (name, where it answers, whether the command restarted it).

`--timeout` (default five minutes) bounds each wait. A service or companion that is not ready in
time fails the command naming it, with the last forty lines of its log, and what did start is
left running (FR-006). A companion that is not ready stops the services that need it from being
started (the living feature), and the rest are started.

A service the context map no longer names is stopped by compose's `--remove-orphans`, and the
command says its database is kept. A service added is given a database and started.

## R16. Proof

**Decision**: four levels, each with what it could miss.

| Suite | Module | Docker | Proves |
|---|---|---|---|
| `TopicDeclarationsSuite`, `ContextMapSuite` (extended) | `core` | no | the file, its problems, FR-015's three |
| `LocalFileSuite`, `LocalPlanSuite` | `cli` | no | `local.json`; every refusal and the environment scenarios; the compose document of each hosting; SC-006 |
| `LocalCommandSuite` | `cli` | no, scripted `Engine` | order of steps, what is printed and that no value is, `--without`, names, `down`, `status`, `logs`, the edge cases |
| `LocalUpSuite` | `cli` | yes | the living features' scenarios against real Docker with a stand-in image |
| `ProjectTemplateSuite` (extended) | `cli` | yes, sbt | a rendered project's real services: one command, two databases, a call that crosses, kept and removed data, a service from its source both ways, the console |
| `LocalModeSuite` | `proxy` | no | the proxy's local mode |
| `TopicsApplySuite` | `controlPlane` | no | `projects topics apply` against the real route |

`LocalUpSuite` uses **a stand-in image**, built by the suite from `cli/src/test/resources/standin/`
(a JDK image running one source file): it serves `/observability/service` at
`ANKKA_OBSERVABILITY_ADDRESS` and HTTP at `ANKKA_HTTP_PORT`, fails at start when told to, and
writes what it is asked to record into its database. It proves the command's orchestration
quickly and can pass while a real runtime would not start, which is why every scenario about a
real service's behaviour (calls by name, databases, what was recorded, source-run services) is
`ProjectTemplateSuite`'s, against images the rendered project builds. The scenario "the local
broker makes no topic that the project does not declare" is proved in halves: `LocalUpSuite` shows
the broker refuses and holds no such topic; that a service's log names the topic is
`KafkaConnectionSuite`'s existing case against a broker with the same setting.

`cli` cannot see `testkit`, so there is no `GherkinSuite`; each scenario is a test named after it,
as `features/projects/` is proved today. `LocalUpSuite` is gated by `-Dankka.local.tests` (`on`
fails rather than skips without Docker; unset, it runs when Docker answers; `off` skips), forwarded
in `Test / javaOptions` like every switch. CI gains a `local-up` job; the template job already
has Docker and sbt.

## R17. Documentation and skills

**Decision**: one new page, `docs/build/local-projects.md` ("Run a whole project on one machine"):
the two commands, what is read, the three files, companions, one service from its source, adopting
it in a project made outside the template, and what it does not stand in for. Edited:
`deploy/run-locally.md` and `build/projects.md` (the recipe becomes the command),
`build/topics.md` (the file and `topics apply`), `build/calling-services.md` and
`operate/local-console.md` (containers are listed and found), `reference/limitations.md`
(FR-033's list, and that contracts are not checked by a local project),
`reference/cli.md` (generated block and a hand-written section), `reference/configuration.md`
(the proxy's two variables), `reference/glossary.md`. The page is in `nav` and in the
`ankka-inspect` and `ankka-deploy` skills' `pages:`; `just docs-sync` re-renders the skills.

**Languages reached**: the feature has no SDK surface. Every language is reached as a hosting, and
the page's commands are the same in each, so no per-language tab or sample is owed; the page says
which default command runs each language from its source.

## Verify at implementation

- `docker compose` accepts the rendered JSON file with `network_mode: service:<name>` and
  `depends_on` conditions as written; `up --no-recreate` is never needed (compose recreates only
  what changed).
- `JAVA_TOOL_OPTIONS` reaches the JVM in the sidecar image and in a template Java and Kotlin image.
- The gateway address on Linux, and that a port published there is reached at
  `host.docker.internal` from a container on the project's network.
- `kafka-topics.sh --describe` output for partitions and `cleanup.policy`, and its exit when a
  topic does not exist.
- The native image carries `ankka/ddl/**`; `native-smoke.sh` gains a step.
- The Kafka producer's log line for an undeclared topic names the declared name or the prefixed
  one; the documentation quotes whichever it is.

## Declined alternatives, in one place

- A long-lived `ankka local` daemon watching the project: the command returns, and compose holds
  the state.
- Building images from `local up`: FR-034. The page says `sbt Docker/publishLocal` (or the
  service's own build) comes first, and a missing image is the refusal that names it.
- Telemetry containers: the spec's assumption; the console is the telemetry.

## Design finished

2026-11-17: analyze reported nothing above LOW.

## Found during implementation

- 2026-11-17: `up --without <service>` on a project that is up stops and removes that service's containers (its database and topics kept), and removes its console entry, so `local run` can take its place; without it `run` refused the still-running container and Story 5's independent test could not pass. FR-009 and the contract said only what is started; this is what follows from Story 5, not a new decision.
- 2026-11-17: `local run` hands its own `-Dankka.running.dir`, when set, to the service's JVM through `JAVA_TOOL_OPTIONS`, so the console entry the runtime writes lands where `status` and the console look.
- 2026-11-17: the implementation goes into pull request #282, the branch's spec-only pull request; at the end it is retitled `feat(066)`, its body rewritten from the template, and it is a draft until its checks are green (Trevor).
