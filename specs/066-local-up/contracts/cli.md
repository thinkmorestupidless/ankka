# Contract: the command line

Five subcommands under `ankka local`, one under `ankka projects topics`, and three more problems
from `ankka context-map check`. Every `local` command here is local: no control plane, no login,
no `-p`. Each takes `--dir <path>` (default `.`), the project's directory, as `ankka context-map`
does. Each has a hand-written section in `docs/reference/cli.md`, and the generated table is
rewritten by `just docs-reference`.

## `ankka local up [<service>...] [--without <service>]... [--timeout <duration>] [--dir <path>]`

Brings the project up (research R15) and returns when everything it started is ready.

| Rule | Message on refusal (stderr, one line each, exit 1, nothing started) |
|---|---|
| Docker answers and has Compose v2 | `docker is not running on this machine` / `docker compose is not installed (Compose v2 is needed)` — checked before anything is read |
| `.ankka/local/state.json` reads, when it exists | `.ankka/local/state.json is not readable: <why>; ankka local down --volumes removes it with what was recorded` — the file is never replaced, since the secret keys it holds are what each service's kept secret store reads |
| `<dir>` holds `context-map.json` | `<dir> holds no project: a project's directory holds context-map.json and services/<name>/service.json for each service` |
| the context map is true of the project | each line of `ankka context-map check`, unchanged |
| every service has a descriptor that reads and is valid | `services/<name>/service.json: <the descriptor's problem>` |
| `topics.json`, `local.json` read | contracts/project-files.md's problems |
| a name on the command line, or after `--without`, is a service of the project | `"<name>" is not a service of <project>` |
| a name is not both given and left out | `"<name>" is both named and left out` |
| no two services name one port | `"<a>" and "<b>" both name port <n>` |
| no port is held by something else, the ports the command chose once included | `"<name>" names port <n>, which is in use on this machine` (and `; it is running from its source` when the project's own service holds it); for a chosen port, `<name>` is what it was chosen for: `ankka-postgres`, `ankka-kafka`, a service or a companion |
| the environment | `"<service>" reads <VARIABLE> from a secret and local.json gives it no value`; `local.json gives "<service>" <VARIABLE>, which the command line sets itself` |
| every image is on the machine or can be pulled | `"<name>" names the image <image>, which is not on this machine and could not be pulled` |

Every problem above found in one run is printed in that run.

After the database server and the broker are ready (research R14), also exit 1 with no service
started, saying `the database server and the broker of <project> are left running`:

| Rule | Message |
|---|---|
| a declared topic exists as declared | `the topic "<name>" has <n> partition(s) on the local broker and <m> declared; ankka local down --volumes removes it` (likewise `is not compacted ... and is declared compacted`) |
| a database's schema can be moved to the service's runtime | `the database of "<name>" holds the schema of ankka <old> and its descriptor names <new>; remove it with: <the one command>` |

With no names, every service the context map names is started. With names, only those and what
they need (the database server, the broker, the topics, their companions). `--without` (repeatable)
starts every service but those named.

`--timeout` (default `5m`) bounds each wait for readiness. A service or companion not ready in
time: stderr `"<name>" was not ready within <duration>; the end of its log:` and its last forty
lines, exit 1, everything that started left running. A companion not ready: the services that
need it are not started and are named.

**Standard output on success**, and nothing else:

```text
shop is up
  orders    http://127.0.0.1:9000    started
  billing   http://127.0.0.1:9001    restarted (its image changed)
  identity  http://127.0.0.1:8081    running      (a companion)
left out: payments — run it from its source with: ankka local run payments
```

The third column is `started`, `restarted (<why>)` or `running` (not touched). A service that
serves gRPC shows that address too. Notices, on stderr and not failures: services of other
projects the crossings name and this directory does not hold; a service the context map no longer
names, stopped, `its database is kept`; a descriptor's `ANKKA_DB_*` or `ANKKA_KAFKA_*` variables
not used on this machine; a service's schema moved from one version to another.

No local value is printed by any path, a failure's included.

## `ankka local down [--volumes] [--dir <path>]`

Stops and removes every container of the project and removes its entries from the local
console's directory. Keeps the volumes. With `--volumes`, removes the volumes (every database,
every topic's messages) and `.ankka/local/`.

| Case | Output |
|---|---|
| nothing of the project is up | `nothing of <project> is running`, exit 0 |
| success | `<project> is down` (`; what it recorded is kept` or `; what it recorded is removed`) |
| no context map | the refusal above, exit 1 |

## `ankka local status [--dir <path>]`

For each service the context map names and each companion, one line: name, `ready`, `running, not
ready` or `not running`, where it answers when running, and `started by ankka local up` or `run
from its source`. Exit 0 whatever the lines say; exit 1 only when the project cannot be read.
Rewrites the console entries of what is running (research R8).

## `ankka local logs <service> [--follow] [--tail <n>] [--dir <path>]`

The log of a service or companion the command started (`docker compose logs`), from the start or
its last `<n>` lines; `--follow` stays until interrupted. A process-, module- or web-hosted
service's log is both of its containers'. `"<name>" is not a service or companion of <project>`
and `"<name>" was not started by ankka local up` are refusals, exit 1.

## `ankka local run <service> [--timeout <duration>] [--dir <path>] [-- <command>...]`

Runs one service from its source, in the foreground, among whatever of the project is up
(research R12). Starts the database server, the broker, the service's database, the project's
topics and the companions the service needs when they are not running, and leaves them running
when the service ends. Exits with the service's exit code; interrupted, 130.

| Rule | Message on refusal (exit 1) |
|---|---|
| everything `up` checks, for this one service | as `up` |
| the service is not already a container of the project | `"<name>" is running from its image; leave it out with: ankka local up --without <name>` |
| a command is known | `cannot tell how to run "<name>" from its source; give the command after --, or name it under "run" in local.json` |

The command is, in order: what follows `--`; `local.json`'s `run.<service>`; the language's
default (research R12). It is run in the project's directory for Scala (one build for the
project) and in `services/<service>` otherwise.

## `ankka projects topics apply [--dir <path>] [-p <project>]`

Sends every declaration in `<dir>/topics.json` to the project on the installation, in the file's
order, as `ankka projects topics set` sends one: the same route, the same rules, the same question
before a change that removes messages (`--removes "<what>"` answers it for a script, and applies to
the one declaration whose removal it restates). Prints `<name>: declared` or `<name>: unchanged`
for each. Stops at the first refusal, naming the topic, exit 1; those before it stay declared.
Removes no topic the file does not name. The project is `-p`, else the configured one; it is not
taken from the context map.

## `ankka context-map check`: three more problems

| Problem | Line |
|---|---|
| a topic crossing sent by a service of this project names a topic `topics.json` does not declare | `the crossing from "<a>" to "<b>" names the topic "<t>", which topics.json does not declare` |
| there are such crossings and no `topics.json` | `<project> has topic crossings and no topics.json; declare each topic there` (once) |
| a crossing and the declaration disagree on the contract or the schema | `the crossing from "<a>" to "<b>" carries "<c>" on "<t>", and topics.json declares "<d>"` |

and every problem `topics.json` has of its own (contracts/project-files.md).

## Exit codes

As the CLI's: 0 success, 1 a refusal or failure the person can act on, 2 usage; `local run` passes
its service's code through.

## Switches and overrides

| Name | What |
|---|---|
| `-Dankka.docker` | the `docker` executable, for the suites' scripted one |
| `-Dankka.cache.dir` | where schemas are cached, else `~/.ankka/cache` |
| `-Dankka.running.dir` | the local console's directory, as today |
| `ANKKA_SIDECAR_IMAGE`, `ANKKA_PROXY_IMAGE` | the platform's images, as the templates and the operator read them |
| `-Dankka.local.tests` | `on` runs `LocalUpSuite` and fails without Docker; unset, runs when Docker answers; `off` skips. Forwarded in `Test / javaOptions` |
