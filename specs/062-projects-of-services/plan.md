# Implementation Plan: Projects of several services

**Branch**: `062-projects-of-services` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/062-projects-of-services/spec.md`

## Summary

A project of several services gets what a single service has today: a template, a way to add to
it, checks and releases that need no hand edits, and skills that know it. `ankka init <name>
--project --services a,b` renders a repository from a CLI-owned template: a context map file,
`contracts/`, one Scala build whose services are discovered under `services/`, a shared module, a
tests module that starts several services over one in-memory broker, a compose file with Postgres
and Kafka, scripts that run each service on its own database and port, and workflows that check
what a change touched and release one service per `<service>/v<version>` tag. `ankka init
<service>` inside a project adds a service in any language. The context map is a JSON file the new
`ankka context-map check` holds true statically and the tests module and the inspect skill hold
true against what services state. The design skill writes it before any component; the inspect
skill follows a scenario across services by its crossing and the trace that continues through it.
Two documentation pages say why and how; the glossary's project is the group of services that run
together and deploy on their own.

## Technical Context

**Language/Version**: Scala 3.9.0 (CLI, `core`, `testkit`, the Scala build of a generated
project), bash (the project's scripts, tested as `ActionSuite` tests bash), YAML (workflows,
`mkdocs.yml`), JSON (the context map, descriptors), Markdown (skills, pages).

**Primary Dependencies**: the CLI's `Scaffold` and its templates; sbt 1.12/2.0 launchers and
sbt-native-packager 1.13 in the generated build; `ankka-testkit` with `InMemoryBroker` and
`ProjectionRuntime.withBroker`; jsoniter in `core` for the context map; Docker images
`postgres:17-alpine` and `apache/kafka:3.8.0` in the generated compose file; GitHub Actions
(reusable workflows, `dorny`-free path mapping by script); spec-kit's `specify` when on PATH; the
docs tool for pages and skills.

**Storage**: the repository tree of a generated project; nothing new in the platform's databases.

**Testing**: `ProjectTemplateSuite` (cli, `-Dankka.template.tests=project`), `InitSuite`,
`ContextMapSuite` (core), `ContextMapCheckSuite` (testkit), `ProjectsDocumentationSuite` (testkit),
`just docs`, `just features`, `native-smoke.sh`; a recorded design run for SC-001.

**Target Platform**: a developer's machine (macOS or Linux) with Docker and sbt, and GitHub
Actions for the generated project's checks and releases.

**Project Type**: command-line tooling, templates, documentation and skills; one small library
addition in `core` and `testkit`; no runtime, control plane or operator change.

**Performance Goals**: SC-002, a three-service project started, run and green in under ten
minutes from an empty directory; a pull request in a generated project runs one service's checks
for one service's change (SC-004).

**Constraints**: the single-service templates and their pipelines unchanged (FR-012); no project
descriptor, no deploying several as one, no batch apply (FR-021); the runtime's default database
name unchanged (FR-017); nothing edited by hand to add a service (FR-008); every page in `nav` and
in a skill; no `$` unescaped if anything lands in `ankka.g8` (only re-rendered skills do).

**Scale/Scope**: one new template with a Scala service skeleton; two CLI subcommands and two
`init` options; a parser and checker in `core`, a comparison in `testkit`; two pages, seven page
edits, two skills edited; five suites new or extended; the generated project's four workflows and
four scripts.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; ankka's `CLAUDE.md` principles stand
in.

| Principle | Holds? | How |
|---|---|---|
| Effects are inert; explicit registration | n/a | no component changes |
| No second copy to keep in step | yes | the context map's parser and checks are in `core`, read by the CLI, the testkit and the skill; services are discovered by the build and the scripts, never listed twice; skills are rendered from one source |
| Ask of every check: could it pass while the thing is false? | yes | `ProjectTemplateSuite` breaks each static check once and asserts the message; the tests module's example suite is proven by making billing invoice nothing; a template switch naming `project` fails rather than skips without its tools |
| The Justfile is thin | yes | no new recipe here; the generated project has scripts, not a Justfile |
| Only a tag publishes; downstream pins releases | yes | a service tag is the only thing that deploys; the template pins the CLI's version |
| Every spec's scenarios live in living features; the checker is green | yes | eight feature files under `features/projects/` and `features/documentation/`, green at specify and clarify |
| A page stands alone, is in `nav` and in a skill | yes | R12 |
| Traps written the day they cost time | yes | the plan's verify-at-implementation list goes into `build-and-release.md` and `docs.md` as traps when they bite |
| Nothing installation-specific in a public repo | yes | the generated project reads its deploy settings from secrets |

No violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/062-projects-of-services/
├── plan.md              # this file
├── research.md          # R1 to R15, verify-at-implementation list, declined remediations
├── data-model.md        # the context map, the project layout, the service tag, the change-to-work map
├── quickstart.md        # how the feature is proven: the suites, a generated project end to end, the docs check
├── contracts/
│   ├── context-map.md   # the file's schema, the static problems and their messages, the rendering
│   ├── cli.md           # `ankka init --project`, `ankka init --add`, `ankka context-map check|render`
│   ├── project-layout.md # every file the template writes and what each service directory holds
│   └── workflows.md     # ci.yml, service-checks.yml, deploy.yml and the scripts' inputs and outputs
├── checklists/requirements.md
├── runs/                # the recorded design run for SC-001 (written by the build)
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
└── ContextMap.scala                     # the file's types, strict codec, static checks, rendering (R4, R5)
modules/core/src/test/scala/…/ContextMapSuite.scala
modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/
└── ContextMapCheck.scala                # a service's stated topics and contracts against the map (R7)
modules/testkit/src/test/scala/…/
├── ContextMapCheckSuite.scala
└── ProjectsDocumentationSuite.scala     # designing, specifying and documentation/projects scenarios (R11)
cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Init.scala                           # --project, --services, --add; project detection; the next free port; specify
├── Scaffold.scala                       # {{project}}, {{service}}, {{services_json}} tokens; a language's service files without the project-owned parts
├── ContextMapCommand.scala              # `ankka context-map check|render`
└── Main.scala                           # wiring, reference docs
cli/src/main/templates/
├── project/                             # the project template (R3): context-map.json, contracts/, build.sbt, project/, shared/, tests/, compose, scripts/, .github/, .specify/extensions.yml, README
└── project-service-scala/               # a Scala service inside a project: service.json, src/main, src/test, no build files
cli/src/test/scala/…/
├── ProjectTemplateSuite.scala           # R14
└── InitSuite.scala                      # fast cases
cli/src/main/resources/META-INF/native-image/   # globs for the new template and the extensions
cli/native-smoke.sh                      # renders a project too
build.sbt                                # the cli resource generator copies project/, project-service-scala/, .specify/extensions/{bdd,review,design-build}, .claude/skills/speckit-*; TemplateSwitch gains `project`
.github/workflows/ci.yml                 # template-project job; changes filter; ci-coverage
tools/docs/skill/ankka-design/SKILL.md   # the step before the table, the smells, the pages (R11)
tools/docs/skill/ankka-inspect/SKILL.md  # the cross-service section (R11)
tools/docs/skill/ankka-deploy/SKILL.md   # pages: + build/projects.md
docs/concepts/projects.md                # new (R12)
docs/build/projects.md                   # new (R12)
docs/concepts/designing-services.md, docs/deploy/ci.md, docs/deploy/run-locally.md,
docs/reference/limitations.md, docs/reference/cli.md, docs/reference/glossary.md,
docs/get-started/coding-agents.md, docs/build/calling-services.md   # edits (R12)
mkdocs.yml                               # nav
marketplace/plugins/ankka/skills/, ankka.g8/src/main/g8/.claude/skills/   # re-rendered by `just docs-sync`
GLOSSARY.md, features/projects/, features/documentation/projects.feature   # already written
```

**Structure Decision**: The context map's logic sits in `core` so that three readers share it
(R4). Everything a generated project is lives under `cli/src/main/templates/project/`, beside the
language templates it is made of, and is rendered by the same `Scaffold`; the proof is the same
shape as the existing template suites. The skills and pages change in their one source and are
rendered.

## Phase 0 and Phase 1 outputs

`research.md` resolves every unknown (R1 to R15) and lists what to verify at implementation.
`data-model.md`, `contracts/` and `quickstart.md` are beside this file. The Constitution Check
above was re-evaluated after the design: unchanged.

## Complexity Tracking

None.
