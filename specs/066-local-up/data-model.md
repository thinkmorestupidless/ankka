# Data model: A project started on one machine with one command

Nothing here is stored by the platform. The model is files in a project's repository, values the
command line builds from them, and what it leaves on the developer's machine.

## What is read

| Value | From | Type | Module |
|---|---|---|---|
| the context map | `context-map.json` | `ContextMap` (unchanged) | `core` |
| a descriptor | `services/<name>/service.json` | `ServiceDescriptor` (unchanged), read strictly | `controlplane-api` |
| the topic declarations | `topics.json` | `TopicDeclarations(topics: Vector[TopicDeclarations.Topic])` | `core`, new |
| the local file | `local.json` | `LocalFile(values, run, companions)` | `cli/local`, new |

`TopicDeclarations.Topic(name, partitions, compacted = false, contract: Option[String],
schema: Option[String], retention, retentionSize, cleanup, tombstoneWindow, minCompactionLag,
maxCompactionLag: Option[String], copies, minInSync: Option[Int])`. `TopicDeclarations.read(root)`
answers `Right(None)` when there is no file. `problems(root, declarations)` and
`crossingProblems(map, declarations)` are what `ContextMap.check` adds. The CLI's
`TopicDeclarationRequests.of(root, topic)` builds the existing `TopicDeclarationRequest`, reading
the schema document.

`LocalFile(values: Map[String, Map[String, String]], run: Map[String, Vector[String]],
companions: Vector[Companion])`; `Companion(name, image, command, env, ports: Vector[Port(port,
host: Option[Int])], files: Vector[Mount(from, to)], ready: Ready, database = false, neededBy:
Option[Vector[String]], memory: Option[String])`; `Ready` is `Http(port, path)` or `Tcp(port)`.

Shapes and problems: contracts/project-files.md.

## What is built (pure)

```text
LocalProject(root, map, descriptors: Map[String, ServiceDescriptor], topics, local)
      │  LocalProject.read(root): Either[Vector[String], LocalProject]
      ▼
LocalPlan.of(project, selection, state, machine): Either[Vector[String], LocalPlan]
```

- **`Selection`**: `All`, `Only(names)`, `Without(names)`, or `ForRun(name)`.
- **`LocalState`** (`.ankka/local/state.json`): `postgresPort`, `kafkaPort`,
  `observability: Map[service, Int]`, `processPorts: Map[service, Int]`,
  `companionPorts: Map[companion, Map[Int, Int]]`, `secretKeys: Map[service, String]`. Read, then
  completed by the plan for anything new (a chosen port is never changed once written), then
  written before anything starts.
- **`Machine`**: what the plan needs to know of the host and may not find out for itself: the
  operating system (for the gateway address), which ports are free, which of the project's
  services are announced from source, the CLI's version, the two image overrides.
- **`LocalPlan`**:
  - `compose`: the document (contracts/local-project.md), as a value with a JSON codec;
  - `databases: Vector[Database(name, schemaVersion: Option[String])]`, a companion's with no schema;
  - `topics: Vector[BrokerTopic(name = "<project>.<declared>", partitions, config: Map[String, String])]`;
  - `infrastructure`, `companions`, `services`: the compose services of each stage, in start order;
  - `ready: Map[name, ReadyCheck]`: `Observability(url, servesHttp)`, `Port(port)`, a companion's own;
  - `entries: Vector[ConsoleEntry(name, observabilityAddress)]`;
  - `leftOut: Vector[String]`, `notices: Vector[String]`;
  - `environment: Map[service, Map[String, String]]`, kept so a suite can assert it; never printed.

Static problems are the left side, all at once (research R14). `LocalPlan.forRun` answers the
same plan narrowed to one service's needs, plus `RunCommand(directory, words, environment)`.

## What touches the machine

`Engine` (trait; `DockerEngine`, and a scripted one in tests):

| Operation | Docker's |
|---|---|
| `available` | `docker version`, `docker compose version` |
| `imagePresent`, `pull` | `docker image inspect`, `docker pull` |
| `gatewayAddress` | `docker network inspect bridge` |
| `up(file, project, services, removeOrphans)` | `docker compose up -d` |
| `down(project, volumes)` | `docker compose down [-v]` |
| `ps(project)` | `docker compose ps --all --format json` → name, state, container id, image id |
| `logs(project, service, tail, follow, to)` | `docker compose logs` |
| `exec(project, service, words, stdin)` | `docker compose exec -T` |
| `copyOut(image, path, to)` | `docker create`, `docker cp`, `docker rm` |

`LocalUp`, `LocalDown`, `LocalStatus`, `LocalLogs`, `LocalRun` are the five commands over a plan
and an engine. `Schemas` finds a version's files (research R6). `ConsoleEntries` writes and
removes entries.

## What is left on the machine

| Where | What | Removed by |
|---|---|---|
| Docker: compose project `ankka-<project>` | containers, a network, volumes `pgdata`, `kafkadata`, one per module | `down` (containers, network), `down --volumes` (volumes) |
| each database | its tables; `COMMENT ON DATABASE` `ankka-schema <version> <digest>` | `down --volumes` |
| `<project>/.ankka/local/` | `compose.json`, `state.json` | `down --volumes` |
| `~/.ankka/running/<project>.<service>.json` | one entry per ready runtime container, with `owner` | `down`; kept, unlisted, while nothing answers |
| `~/.ankka/cache/ddl/<version>/` | a runtime version's schema files | nobody; it is a cache |

## States

A service of a local project, as `ankka local status` says it:

```text
not running ──up──▶ running, not ready ──answers──▶ ready
     ▲                     │                          │
     └──────── down, or the container exits ◀─────────┘
```

and beside the state, who runs it: `started by ankka local up` (a container of the compose
project) or `run from its source` (an entry in the console's directory with no owner). A service
is never both: `up` refuses a port its own project's source-run service holds, and `local run`
refuses a service that is a container.

A database's schema: none → `<version>` (first `up`); `<version>` → a newer one of the same major
(applied, noticed); anything else is the second-tier problem and changes nothing.

## Glossary

`local project`, `topic declaration`, `local values` and `companion` are in the root
`GLOSSARY.md` since the spec. The plan adds no term: `topics.json` and `local.json` are file
names, and "the command line" already names the CLI.
