# Implementation Plan: A project started on one machine with one command

**Branch**: `066-local-up` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/066-local-up/spec.md`

## Summary

`ankka local up`, run in a project's directory, reads the context map, each service's descriptor
and two new files (`topics.json`, the topic declarations; `local.json`, the local values and the
companions), checks everything it can before starting anything, and reports every problem
together. It then renders one compose document and drives `docker compose`: one Postgres with a
database per service holding the schema of that service's runtime, one Kafka with the declared
topics made under the project's prefix and no topic made by accident, the companions, and every
service from its image as its hosting is composed in a pod. Every service answers on the host at
its descriptor's port whoever runs it, so a service moved to the developer's terminal with
`ankka local run` takes its place and nothing else restarts. The command writes the local
console's entries for its containers, so the console and a service run from source find them.
`down`, `status` and `logs` complete the group; `ankka projects topics apply` sends the same
`topics.json` to an installation; `ankka context-map check` holds topic crossings to it. The
project template drops its compose file and two scripts for the command. The one change outside
the CLI and `core` is a plain local mode in the proxy image, for web-hosted services.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (CLI, `core`, `proxy`); JSON (the two files, the
rendered compose document, the state); a single Java source file for the test stand-in image;
Markdown (pages, skills); YAML (CI).

**Primary Dependencies**: the `docker` executable with Compose v2, run through `ProcessBuilder`
(no client library); images `postgres:17-alpine`, `apache/kafka:3.8.0`,
`ghcr.io/thinkmorestupidless/ankka-sidecar` and `ankka-proxy`; jsoniter (already the CLI's,
through `core`); decline; `proxy-core`'s `ProxyEngine` and `Transport.plain`; the existing
`ControlPlaneClient.declareTopic`. No new library in any module.

**Storage**: the project's repository (`topics.json`, `local.json`); `.ankka/local/` in it
(rendered compose file, chosen ports, secret keys); Docker volumes of the compose project; entries
in `~/.ankka/running`; a schema cache in `~/.ankka/cache`. Nothing new in the platform's databases;
no DDL file added.

**Testing**: `TopicDeclarationsSuite`, `ContextMapSuite` (core); `LocalFileSuite`,
`LocalPlanSuite`, `SchemasSuite`, `BrokerTopicsSuite`, `SourceCommandsSuite`,
`LocalCommandSuite`, `DiscoverySuite`, `CliReferenceSuite`, `InitSuite`, `LocalUpSuite`
(Docker, `-Dankka.local.tests`), `ProjectTemplateSuite` (cli);
`LocalModeSuite` (proxy); `ProxyEnvironmentSuite`, `TopicsApplySuite` (controlPlane);
`ProjectsDocumentationSuite` (testkit); `just features`, `just docs`, `cli/native-smoke.sh`; the
recorded measurements for SC-002 to SC-004. Research R16 says what each can and cannot miss.

**Target Platform**: a developer's machine, macOS or Linux, with Docker (Docker Desktop, OrbStack
or Docker Engine) and Compose v2; GitHub's Ubuntu runners for the Docker-backed suites.

**Project Type**: command-line tooling with a file-format addition in `core`, one mode added to
the proxy image, a template change, documentation and skills. No runtime, SDK, control plane,
operator or protocol change.

**Languages reached**: no SDK surface is added, so no per-language sample or tab is owed. Every
language is reached as a hosting: Scala, Java and Kotlin (`embedded`), Python, TypeScript and Go
(`process`), Rust (`wasm`), and a web-hosted interface (`web`), each started by `up` and each with
a default command for `ankka local run`. The page states the commands once; they do not differ by
language.

**Performance Goals**: SC-002 (six Scala services, the two platform containers and a companion
within 8 GiB, idle), SC-003 (up within five minutes with images present; an unchanged second `up`
within thirty seconds), SC-004 (survives a four-core neighbour for ten minutes). Kept as written
and measured during the build (research R11, quickstart §7).

**Constraints**: nothing of Kubernetes or the platform's own services runs (FR-002); no local
value is ever printed (FR-020); every static problem is reported together before anything starts
(FR-010); running one service from source keeps working in a project made from the earlier
template (FR-025); nothing is built, deployed or applied to an installation but topic
declarations (FR-034, R4); the CLI stays a native image with no client library; every home-reading
path is overridable by a system property; the released runtimes are not changed, so what a
container is given must be something they already read.

**Scale/Scope**: five `local` subcommands, one `projects topics` subcommand, three new problems in
the context map's check; about ten new source files in `cli/local`, one in `core`, a mode in
`proxy/Main`; the CLI's resources gain the schema files; the template loses three files and gains
one; one new page and nine edited; ten suites new and seven extended; one CI job.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; ankka's `CLAUDE.md` and the family's
principles stand in.

| Principle | Holds? | How |
|---|---|---|
| Effects are inert data; one executor | yes | `LocalPlan.of` is pure and answers a plan or every problem; `Engine` alone runs Docker (R2) |
| Explicit registration, never scanning | yes | services are the context map's, each with a descriptor; nothing is discovered by looking at running containers |
| No second copy to keep in step | yes | one `topics.json` for the machine and the installation (R4); the environment's split is `PlatformVariables.runtimeOnly`, the operator's; the command's own variable names are one value read by the plan and the refusal (R9); the schema is the canonical directory, copied by glob (R6); the proxy's engine is `proxy-core`'s |
| No BSL on the path | yes | no new dependency; Kafka's and Postgres's images are the ones already used |
| Ask of every check: could it pass while the thing is false? | yes | the stand-in image proves orchestration only and R16 says so; real behaviour is proved against real services; `-Dankka.local.tests=on` fails without Docker; each refusal is asserted by breaking the thing once |
| Tests serialised; a test binds no fixed port another needs | watch | `LocalUpSuite` and the template suite bind the descriptors' ports and the chosen ones on loopback; they choose service ports outside 9000 to 9010 and let the command choose the rest, so they run beside a developer's `sbt shoppingCart/run` and compose |
| Anything reading `$HOME` is overridable | yes | `-Dankka.running.dir` (existing), `-Dankka.cache.dir`, `-Dankka.docker` |
| A CLI's `main` is a wrapper; no `sys.exit` in logic | yes | commands return a code or throw `ExitWith` |
| The Justfile is thin | yes | no recipe added |
| Only a tag publishes | yes | the proxy image's mode ships with the next release; a development CLI names `:latest` images |
| Every spec's scenarios live in living features; the checker is green | yes | written at specify, green before this plan (`0 findings`) |
| A page stands alone, is in `nav` and in a skill | yes | R17 |
| The honest not-implemented list | yes | FR-033's list and "contracts are not checked by a local project" go on the limitations page |
| Traps written the day they cost time | yes | the verify-at-implementation list becomes traps in `control-plane.md` (the CLI's) and `web-hosting.md` (the proxy's mode) when they bite |
| Nothing installation-specific in a public repo | yes | none is involved |

One thing to say plainly, not a violation: `ankka context-map check` from this release fails in a
project made from the 062 template until it holds `topics.json` (R4). FR-015 asks for it.

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/066-local-up/
├── plan.md              # this file
├── research.md          # R1 to R17, verify-at-implementation, declined alternatives
├── data-model.md        # what is read, what is built, what touches the machine, what is left
├── quickstart.md        # how the feature is proven, and the measurements
├── contracts/
│   ├── cli.md           # the six commands, their refusals and output; the check's new problems
│   ├── project-files.md # topics.json and local.json, and what the command writes
│   └── local-project.md # what runs, what each container is given, the proxy's local mode
├── runs/                # measurements.md, written by the build (SC-002 to SC-004)
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
├── TopicDeclarations.scala              # topics.json: types, strict codec, problems (R4)
└── ContextMap.scala                     # check: the three topic problems (FR-015)
modules/core/src/test/scala/…/TopicDeclarationsSuite.scala, ContextMapSuite.scala

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                           # local up|down|status|logs|run; projects topics apply
├── TopicsCommand.scala                  # apply: each declaration through declare (R4)
├── Projects.scala                       # no compose file for a service added to a project (R12)
├── console/LocalSource.scala            # an owned entry is kept when nothing answers (R8)
└── local/
    ├── LocalFile.scala                  # local.json: types, strict codec, problems
    ├── LocalProject.scala               # the four kinds of file read into one value
    ├── LocalVariables.scala             # the command's own variable names (R9)
    ├── LocalState.scala                 # .ankka/local/state.json: ports and keys, chosen once
    ├── LocalPlan.scala                  # pure: problems, or the plan (R2, R7, R9, R10, R13, R14)
    ├── Compose.scala                    # the compose document as a value, and its JSON
    ├── Engine.scala                     # the trait, and DockerEngine over ProcessBuilder (R1)
    ├── Schemas.scala                    # a runtime version's schema files (R6)
    ├── BrokerTopics.scala               # describe, create, compare on the local broker (R5)
    ├── ConsoleEntries.scala             # the entries the command writes and removes (R8)
    ├── SourceCommands.scala             # a service's language and its default command (R12)
    └── LocalUp.scala, LocalDown.scala, LocalStatus.scala, LocalLogs.scala, LocalRun.scala
cli/src/main/templates/project/          # − docker-compose.yml, scripts/run.sh, scripts/databases.sh;
                                         # + topics.json; README, build.sbt (no `schema` task)
cli/src/main/resources/META-INF/native-image/…/reachability-metadata.json   # + ankka/ddl/**
cli/native-smoke.sh                      # the schema files; the no-project refusal
cli/src/test/scala/…/
├── LocalFileSuite.scala, LocalPlanSuite.scala, LocalCommandSuite.scala
├── LocalUpSuite.scala                   # Docker; scenarios by name (R16)
├── ScriptedEngine.scala                 # the offline suites' engine
├── ProjectTemplateSuite.scala           # the real project, brought up (R16)
└── DiscoverySuite.scala, CliReferenceSuite.scala, InitSuite.scala   # extended
cli/src/test/resources/standin/          # Dockerfile and Standin.java: the stand-in image

proxy/src/main/scala/com/thinkmorestupidless/ankka/proxy/
├── Main.scala                           # ANKKA_PROXY_LOCAL: plain transport, the local locator (R10)
└── LocalLocator.scala                   # ANKKA_PROXY_LOCAL_SERVICES
proxy/src/test/scala/…/LocalModeSuite.scala
controlplane/src/test/scala/…/TopicsApplySuite.scala   # the real CLI against the real route

build.sbt                                # cli: the schema files as resources (ankka/ddl/);
                                         # -Dankka.local.tests forwarded
.github/workflows/ci.yml, .github/ci-coverage.py   # a `local-up` job; the changes filter
modules/testkit/src/test/scala/…/ProjectsDocumentationSuite.scala   # the template's and pages' words

docs/build/local-projects.md             # new (R17)
docs/deploy/run-locally.md, docs/build/projects.md, docs/build/topics.md,
docs/build/calling-services.md, docs/operate/local-console.md, docs/reference/limitations.md,
docs/reference/cli.md, docs/reference/configuration.md, docs/reference/glossary.md   # edits
mkdocs.yml                               # nav
tools/docs/skill/ankka-inspect/SKILL.md, tools/docs/skill/ankka-deploy/SKILL.md   # pages
marketplace/plugins/ankka/skills/, ankka.g8/src/main/g8/.claude/skills/   # re-rendered by `just docs-sync`
CLAUDE.md, .claude/rules/control-plane.md, .claude/rules/web-hosting.md   # the commands; traps as they bite
GLOSSARY.md, features/projects/, features/local-console/, features/documentation/   # already written
```

**Structure Decision**: The command is the CLI's, in `cli/local` beside `ankka local web`, split
into files read, a pure plan and one engine so that everything but Docker itself is proved
offline. The topic declarations' file form sits in `core` beside the context map because the
map's check must read it and three readers (the check, `local up`, `topics apply`) must agree.
The proxy's local mode is in `proxy`, where the image's `Main` is; it reuses `proxy-core`'s
engine, as `ankka local web` does. Nothing is added to `runtime`, `sdk`, `sidecar`,
`controlplane` or `operator`: a container is given only what released runtimes already read.

## Phases for the task list

The order the stories can be built and proved in, each ending green on its own.

1. **Files and plan** (US3 and the static half of US1, US2): `TopicDeclarations`, the check's
   problems, `LocalFile`, `LocalProject`, `LocalState`, `LocalVariables`, `LocalPlan` and `Compose`
   for `embedded`, with `LocalPlanSuite`.
2. **Up and down for embedded services** (US1): `Engine`, `Schemas`, the commands, the wiring in
   `Main`, the stand-in image and `LocalUpSuite`'s first scenarios.
3. **Topics** (US2): `BrokerTopics`, the second-tier check, `projects topics apply`.
4. **Companions** (US4).
5. **From source** (US5): `--without`, names, `ConsoleEntries`, `LocalSource`'s kept entries,
   `ankka local run`, `SourceCommands`.
6. **Seeing** (US6): `status`, `logs`, the console scenario.
7. **The other hostings**: `process`, `wasm`, and `web` with the proxy's local mode.
8. **The template and the documentation** (US7): the template's files, `Projects`,
   `ProjectTemplateSuite`, the pages, the skills, the reference, CI.
9. **Measurements**: quickstart §7, recorded.

## Complexity Tracking

No violations.
