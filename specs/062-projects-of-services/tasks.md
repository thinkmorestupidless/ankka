# Tasks: Projects of several services

**Input**: Design documents from `specs/062-projects-of-services/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R15), data-model.md, contracts/
(context-map.md, cli.md, project-layout.md, workflows.md), quickstart.md

**Tests**: The spec's acceptance scenarios are living features, and `CLAUDE.md` asks of every
check whether it could pass while the thing is false, so each story carries the suite that proves
it, written before or beside the thing it checks. Every test is named after its scenario.

**Organization**: by user story. US1 is the design skill and the rule; US2 the template, the
command line and the releases; US3 the guidance for a change that crosses services; US4 running
and inspecting a project. The context map's logic is foundational because three stories read it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 to US4 from spec.md
- Paths are from the repository root; a generated project's paths are given as
  `<project>/…` and live in the template under `cli/src/main/templates/project/`

## Phase 1: Setup

**Purpose**: the places every later task writes to, and the switch that runs the new suite

- [X] T001 Create the template directories `cli/src/main/templates/project/` and `cli/src/main/templates/project-service-scala/`, each with an empty `.gitkeep` to be removed by the first file, and extend the `cli` resource generator in `build.sbt` (the block at `Compile / resourceGenerators`, lines 947 to 1018) so that `project` and `project-service-scala` are rendered onto the classpath under `ankka/templates/<name>/` with an `index.txt` like the language templates, and so that `project` also carries `.specify/extensions/{bdd,review,design-build}/**`, `.specify/extensions.yml` and `.claude/skills/speckit-{design,build}/**` from this repository's tree (hidden files walked by hand, as the skills are), refusing a duplicate path as the generator already does
- [X] T002 [P] Add the word `project` to `TemplateSwitch` in `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/TemplateSwitch.scala` (named: fail without `sbt` and Docker; unset: run when present; `off`: skip) and make `templateArtifacts` in `build.sbt` (lines 1020 to 1088) publish the local Ivy artifacts for `project` as it does for `scala`
- [X] T003 [P] Add the globs `ankka/templates/project/**` and `ankka/templates/project-service-scala/**` to `cli/src/main/resources/META-INF/native-image/` resource config, and extend `cli/native-smoke.sh` to render `ankka init shop --project --services orders,billing` into a temp directory and assert `context-map.json` and `services/orders/service.json` exist

---

## Phase 2: Foundational

**Purpose**: the context map's one definition, read by the CLI, the testkit and the skills

- [X] T004 Write `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/ContextMap.scala` per `contracts/context-map.md`: the case classes (`ContextMap`, `Service`, `Boundary`, `Crossing` with the topic and route shapes), a strict jsoniter codec (unknown field refused with the contract's message), `read(root)`, `check(root, map)` producing the problem lines in the contract's table order, `render(map)` producing `CONTEXT-MAP.md` byte for byte as the contract shows, and `topicCrossings(map, service)`; move the project, service, topic and contract name rules `controlplane-api` applies into `core` (one definition; `controlplane-api` delegates) where `core` does not already hold them
- [X] T005 Write `modules/core/src/test/scala/com/thinkmorestupidless/ankka/core/ContextMapSuite.scala`: one test per row of the contract's problem table over a temp directory (each producing exactly that line, and only that line, for a map that has only that defect), a test that a well-formed map over a matching tree yields no problem, a test that the rendering equals the contract's example, a test that an unknown field is refused; run `sbt 'core/testOnly *ContextMapSuite'` green
- [X] T006 Write `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/ContextMapCheck.scala`: `problems(map, service, kit)` reading the kit's service registry for every component's publication and topic source (name and stated contract name) and comparing them with `topicCrossings(map, service)`, producing the four line shapes of `contracts/context-map.md`
- [X] T007 Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ContextMapCheckSuite.scala`: two small services (a consumer publishing to `orders-placed` as `order.v1`; a consumer reading it) under kits, and tests for: no problem when the map matches; a publication the map lacks; a read the map lacks; a contract name that differs; a crossing no component states; run `sbt 'testkit/testOnly *ContextMapCheckSuite'` green
- [X] T008 Write `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/ContextMapCommand.scala` and wire `ankka context-map check [--dir]` and `ankka context-map render [--dir]` into `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Main.scala` (the command list and names, as `local` is wired): `check` prints `core.ContextMap`'s problems and exits 1 when any, 0 silently otherwise; `render` writes `CONTEXT-MAP.md` and prints its path; the no-file message of the contract; no control plane, no `-p`
- [X] T009 Add the two hand-written sections for `ankka context-map check` and `render` to `docs/reference/cli.md` and run `just docs-reference` so `CliReferenceSuite` rewrites the generated table; run `sbt 'cli/testOnly *CliReferenceSuite'` green

---

## Phase 3: User Story 1 - A project designed with its boundaries written down (Priority: P1) 🎯 MVP

**Goal**: the design skill decides one service or several before any component, writes the
context map when several, merges services that would release together, and the documentation
states the rule.

**Independent Test**: quickstart §5 (`ProjectsDocumentationSuite`'s designing tests and
documentation scenario 1) and §6 (the recorded design run over three domains).

- [X] T010 [US1] Write `docs/concepts/projects.md` (frontmatter `kind: concept`, title "A project of several services", a description under 240 characters ending in a full stop, `related:` designing-services, consistency, build/topics, build/calling-services): sections "What a project is" (the glossary's definition), "Deployed together means one service" (the platform deploys each service on its own and never several as one, deliberately; a rollout that must be coordinated says the boundary is wrong), "The context map" (the file, its services, boundaries by the three criteria, crossings as a declared topic with its contract or a route with its caller; a shared database or a read of another's events is a defect, not a crossing; a cycle of calls is named and one direction becomes a topic; one service needs no map), with the contract's JSON example as a fenced block; leave headings for US3's sections ("One repository per project", "A change that crosses services") to be filled by T034
- [X] T011 [P] [US1] Edit `docs/concepts/designing-services.md` "Service boundaries" (lines 172 to 196): add that services which must be deployed together are one service and that the platform deploys each service on its own and never several as one, deliberately; a sentence that a design of several services starts with a context map, linking `projects.md`
- [X] T012 [P] [US1] Edit `tools/docs/skill/ankka-design/SKILL.md`: add `concepts/projects.md`, `build/topics.md` and `build/calling-services.md` to `pages:`; insert before "## The component table" a section "## One service, or several" with the step: decide by ownership and release cadence, scaling, consistency; when several, write `context-map.json` at the root (every service, what it owns, its criterion and why; every crossing as a declared topic with contract and schema or a route with its caller) before any component table and then design each service; two services that must be released together are one, said so; a cycle of calls is named and the agent asks which direction becomes a topic; a crossing of any other kind is named a defect to remove; when one service, no map; rewrite step 10 to point at this section; add to "## Reviewing a design" the smells "a synchronous call cycle between services" and "a crossing that is neither a declared topic with a contract nor a route"
- [X] T013 [P] [US1] Add to `docs/reference/glossary.md`, in alphabetical place: the changed `### Project` text (the group of services that deliver what is required of them when they run together, each deployed on its own; services that must be deployed together are one service), and `### Context map`, `### Crossing`, `### Boundary`, `### Shared module`, `### Service tag`, `### Project template`, each as the root `GLOSSARY.md` defines it
- [ ] T014 [US1] Register the page: `mkdocs.yml` nav, Concepts, after "Designing a service"; run `just docs-sync` (re-renders the design skill into `marketplace/plugins/ankka/skills/ankka-design/` and `ankka.g8/src/main/g8/.claude/skills/ankka-design/`) and `just docs`, green
- [ ] T015 [US1] Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ProjectsDocumentationSuite.scala` in the shape of `DesignAndBuildDocumentationSuite` (root by walking up, `page` and `says` helpers, one test per scenario named after it): for each scenario of `features/projects/designing.feature`, a test asserting the design skill's text says the thing (the section exists before the component table; each criterion named; the crossing kinds; the defect; the merge rule; the cycle; no map for one service); for `features/documentation/projects.feature` scenario 1, a test over `concepts/projects.md` and `concepts/designing-services.md`; a test that `concepts/projects.md` is in `nav` and in the design skill's `pages:`; run green
- [ ] T016 [US1] Record the design run for SC-001: write three domain descriptions under `specs/062-projects-of-services/runs/` (`shop-ordering-invoicing.md` where ownership differs; `orders-stock.md` where orders and stock must be consistent together; `one-team-shop.md` which needs no boundary), give each to an agent holding only the rendered `ankka-design` skill, keep the context map each produced beside it, and note in `research.md` under "Found during implementation" whether every crossing names a contract or a route, whether the coupled pair became one service, and whether the third produced no map; fix the skill's wording for what the runs show

---

## Phase 4: User Story 2 - A project in one repository, each service released on its own (Priority: P1)

**Goal**: `ankka init --project` renders a project; `ankka init` inside it adds a service in any
language; a change runs what it touched; a service tag releases one service.

**Independent Test**: quickstart §3, `ProjectTemplateSuite` end to end, and §2, `InitSuite`.

### The template

- [X] T017 [US2] Write the Scala service skeleton `cli/src/main/templates/project-service-scala/` per `contracts/project-layout.md`: `service.json` (`{{service}}`, image `{{service}}:latest`, `runtime` `{{ankka_version}}`, `port` `{{port}}`), `src/main/resources/{application.conf,logback.xml}` from the single template's, `src/main/scala/{{package_path}}/{{service}}/{Main.scala,{{Service}}Service.scala,api/,application/,domain/}` where `Main` calls `{{Service}}Service.start()` and the service object exposes `descriptors`, `extensions(broker)` and `start()` calling `Ankka.service … .start(name = "{{service}}")` with `ProjectionRuntime.fromEnv()`, the entity, view and endpoint of the single template renamed for the service; `src/test/scala/…` with the single template's three suites adapted; the `index.txt` entries written by the generator
- [X] T018 [US2] Write the project template's build: `cli/src/main/templates/project/build.sbt` (`scalaVersion`, the `shared` project, the `tests` project depending on `ankka-testkit` and on every discovered service through the plugin's list, the `schema` task from the single template), `project/build.properties`, `project/plugins.sbt` (sbt-native-packager), and `project/Services.scala`: an `AutoPlugin` whose `extraProjects` makes a project per `services/<name>/` holding `src/main/scala` and no build file of its own, with `JavaAppPackaging`, `DockerPlugin`, the single template's docker settings (`Docker / packageName := name`, `dockerRepository` from `DOCKER_REPOSITORY`, `dockerBuildxPlatforms` from `ANKKA_IMAGE_PLATFORMS`, base image, exposed port from its descriptor, `Docker / version` with `+` → `-`), `dependsOn(LocalProject("shared"))`, `Test / fork := true`, and `version` = `SERVICE_VERSION` or `git describe --tags --match "<name>/v*"` as data-model.md tabulates
- [X] T019 [P] [US2] Write `cli/src/main/templates/project/shared/src/main/scala/{{package_path}}/contracts/{{First}}V1.scala` (the message type of the first service's events with its serializer, and `val {{first}}V1 = Contract.fromSchema("{{first}}.v1", <the schema read from the module's resources>)`), `shared/src/main/resources/contracts/{{first}}.v1.json` mirrored from `contracts/{{first}}.v1.json`, and the project's `contracts/{{first}}.v1.json` JSON Schema
- [X] T020 [P] [US2] Write the project's sample crossing into the two initial services as the skeleton of T017 is rendered twice: the first service's consumer publishes `{{first}}-events` as `{{first}}.v1`; the second service's consumer reads it with `TopicOptions(contract = Some({{first}}V1))` into an entity, and its endpoint serves `GET /{{second}}/{id}`; the first service's endpoint has a route that calls `services("{{second}}")` for that (the route crossing). Keep this as the differences the renderer applies for `{{role}} = first|second`, or as two skeleton variants, whichever `Scaffold` carries more plainly; record the choice in `research.md`
- [X] T021 [P] [US2] Write `cli/src/main/templates/project/context-map.json` (the two services with `owns` and `why` filled for the sample, the topic crossing and the route crossing per `contracts/context-map.md`), and have the renderer write `CONTEXT-MAP.md` from it at init (call `core.ContextMap.render`), never as a template file that could drift
- [X] T022 [P] [US2] Write `cli/src/main/templates/project/tests/src/test/scala/{{package_path}}/{OrderInvoicedSuite,ContextMapSuite}.scala` per research R7: the first starts one `AnkkaTestKit` per service under its own service identity, both on one `InMemoryBroker` through `ProjectionRuntime.withBroker(broker, broker)` from each service's `extensions(broker)`, with `localServices` wiring the route crossing, drives the first service's route and waits (`eventually` on the value that changes) for the second's declared query; the second asserts `ContextMapCheck.problems(map, service, kit)` is empty for each Scala service; name the first suite after the project's sample scenario in its scenario words
- [X] T023 [P] [US2] Write `cli/src/main/templates/project/docker-compose.yml` (postgres:17-alpine and apache/kafka:3.8.0 KRaft single node on 9092, as `contracts/project-layout.md`), `scripts/databases.sh`, `scripts/run.sh` (including `docker compose -f services/<service>/docker-compose.yml up -d` when that file exists, research R15a), `scripts/changed-services.sh`, `scripts/service-language.sh` exactly as `contracts/workflows.md` specifies, executable (`.scaffold-modes` marks them `exec`), with `set -euo pipefail` and the stated messages
- [X] T024 [P] [US2] Write `cli/src/main/templates/project/.github/workflows/{ci.yml,service-checks.yml,deploy.yml}` per `contracts/workflows.md` (the single templates' per-language steps moved into the reusable workflow; `deploy.yml` on `'*/v*'` and dispatch; `resolve` → `checks` → `deploy`; the error line for an unknown service; `ankka services deploy "$SERVICE" "$IMAGE" -f "services/$SERVICE/service.json"`), plus `.github/pull_request_template.md`, `.githooks/`, `.mcp.json`, `.gitignore` from `common/`, `README.md` (what it is, run, add a service, check the map, release one service with a service tag, a plain `v*` tag does nothing, `specify init` when `ankka init` could not run it), `GLOSSARY.md` seeded with the project's words, `features/README.md`, `specs/.gitkeep`, `.specify/extensions.yml` (installed bdd, review, design-build; the three hooks)
- [X] T025 [US2] Render and run the template by hand once before the suite exists: `sbt 'cli/run init shop --project --services orders,billing --dir /tmp/x'`, then in it `docker compose up -d`, `sbt test`, `sbt orders/Docker/publishLocal`; fix what fails; note in `research.md` under "Found during implementation" what R6 and R7's verify list settled (`extraProjects` under both launchers, two kits on one broker, the announced name)

### The command line

- [ ] T026 [US2] Extend `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/Init.scala` and `Main.scala` per `contracts/cli.md`: options `--project`, `--services`, `--add`; `Init.problems` for every refusal line in the contract (`--services` required and at least two distinct valid names, not `shared` or `tests`; `--template` with `--project`; `--language` other than `scala` with `--project`; `--add` with `--project`; `--add` outside a project; a service already present); project detection by `context-map.json` under `--dir`; the next free port from 9000 over the existing descriptors; `Scaffold.renderProject(request, version)` (the project files with `{{project}}`, `{{services_json}}`, then the Scala skeleton per service with `{{service}}`, `{{Service}}`, `{{port}}`, `{{role}}`), `Scaffold.renderServiceInto(project, request, version)` (a language's template without `.github/`, `.githooks/`, `.claude/`, `.mcp.json`, the README's deploy section, with `port` set and its compose file trimmed and pointed at the project's Postgres and Kafka per `contracts/project-layout.md`; or the Scala skeleton), the context map appended and re-rendered through `core.ContextMap`; the next-steps text of the contract; `specify init --here --ai claude --ai-skills` run through the existing process runner when `specify` is on PATH and the system property `ankka.init.specify` is not `off`, else printed first; forward `ankka.init.specify` in `cli`'s `Test / javaOptions` in `build.sbt`
- [ ] T027 [US2] Extend `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/InitSuite.scala`: the argument shapes and every refusal message of `contracts/cli.md` (the `--language` refusal with `--project` among them); project detection; the port rule (9002 after 9000 and 9001; a gap filled); `--add` in an empty directory refuses and writes nothing; a Python service added inside a project keeps a compose file with no `postgres` service and with the project's host addresses; the `specify` line when it is not on PATH (scripted runner, as `McpInstall`'s tests script `claude`); run green
- [ ] T028 [US2] Write `cli/src/test/scala/com/thinkmorestupidless/ankka/cli/ProjectTemplateSuite.scala` (munit, `munitTimeout` 15 minutes, `requireTools("sbt", "docker")` under `TemplateSwitch`), with `-Dankka.init.specify=off` set for the rendered command, the eleven steps of quickstart §3 in order, each a test that fails for the right reason: layout; descriptors and ports; the map and rendering; `sbt test`; billing broken once fails `OrderInvoicedSuite`; a publication added to orders with no crossing in the map fails `ContextMapSuite` (SC-007's run-time half); `orders/Docker/publishLocal` and no billing image; versions from tags after `git init`, a commit and the two tags in the rendered project; `ankka context-map check` green then each of four breaks red with the contract's line; `ankka init stock --language python` inside (files, port 9002, map entry, `git status` names only those paths); `changed-services.sh` over four synthetic diffs; `service-language.sh`; `--add` outside refuses; the workflows' shape; run `sbt -Dankka.template.tests=project 'cli/testOnly *ProjectTemplateSuite'` green

### The pipeline in ankka and the documentation

- [ ] T029 [P] [US2] Add a `template-project` job to `.github/workflows/ci.yml` beside `template-scala` (one runner, `sbt -Dankka.cluster.tests=off -Dankka.template.tests=project 'cli/testOnly *ProjectTemplateSuite'`, Docker present), its `changes` filter (the template directories, `Init.scala`, `Scaffold.scala`, `ContextMapCommand.scala`, `core/…/ContextMap.scala`, `testkit/…/ContextMapCheck.scala`, `.specify/extensions/**`, `.claude/skills/speckit-*/**`), and, in the pull request body, the new required check's name for Trevor to add to branch protection; run `python3 .github/ci-coverage.py` and claim every new file
- [ ] T030 [P] [US2] Write `docs/build/projects.md` (frontmatter `kind: guide`, title "Build a project of several services", `related:` concepts/projects, deploy/ci, deploy/run-locally, build/topics, build/testing): "Start a project" (`ankka init shop --project --services orders,billing`, what it holds with the layout tree, `specify init` when printed), "Add a service" (`ankka init stock --language python`; what is written and what is not; complete the map's entry), "The shared module and the contracts" (Scala services depend on it; another language reads `contracts/<name>.json`), "The context map" (`ankka context-map check` and `render`, what each check holds, the tests module's run-time check), "Release one service" (a service tag `orders/v1.4.2`; what the deploy job does and does not do; a tag naming no service fails saying so; a plain `v*` tag does nothing; each service's version follows its own tags), leaving "Run every service on one machine" and "A test that crosses services" headings for T038
- [ ] T031 [P] [US2] Edit `docs/deploy/ci.md`: a section "A project of several services" (service tags, the reusable checks run before the deploy, one image and one `services deploy` per tag, the same four secrets), linking `build/projects.md`; edit `docs/reference/limitations.md` "Platform": the platform deploys each service on its own and offers no way to roll several out as one, deliberately; the project template's tests module starts Scala services only
- [ ] T032 [US2] Register `build/projects.md` in `mkdocs.yml` nav (Build, after "Calling other services") and in `tools/docs/skill/ankka-deploy/SKILL.md` `pages:`; edit `docs/get-started/coding-agents.md` skills table rows for `ankka-design` and `ankka-inspect` to name projects of several services; run `just docs-sync` and `just docs` green
- [ ] T033 [US2] Extend `ProjectsDocumentationSuite` with one test per scenario of `features/projects/template.feature` and `features/projects/releases.feature` that a file read can prove over the *template's* files (the tag glob and the service-from-tag expression in `deploy.yml`; `service-checks.yml` before `deploy`; `changed-services.sh` maps per data-model.md, run as bash over a temp tree as `ActionSuite` runs the action's steps; `ci.yml` names no secret) and over `build/projects.md` and `deploy/ci.md` for `features/documentation/projects.feature` scenario 2's service-tag line; the behavioural scenarios (what a tag builds and deploys) are covered by those shape tests and `ProjectTemplateSuite`, and the suite's doc comment says so

---

## Phase 5: User Story 3 - A feature that spans services, specified once (Priority: P2)

**Goal**: the documentation says where a cross-service feature's spec lives, how its plan and work
list are ordered, how a contract changes compatibly, and what changes when a service moves out.

**Independent Test**: quickstart §5, `ProjectsDocumentationSuite`'s specifying tests and
documentation scenario 2.

- [ ] T034 [US3] Fill the two sections of `docs/concepts/projects.md` left by T010: "One repository per project" (every service, the context map, the contracts and the specs in one repository; per-service tags are why it does not tie deployments together; a service moves to a repository of its own when its owner or its toolchain differs; what stays: the map, the contracts, the cross-service spec; what the moved service's repository holds: a spec naming the project's spec it implements) and "A change that crosses services" (one spec in the project's repository; its plan names each service's part and the contracts it changes; its work list changes each contract before either side depends on it; an incompatible change is a new contract name and each side reads both until neither publishes the old one; each service released on its own tag when its part is done)
- [ ] T035 [P] [US3] Add to `tools/docs/skill/ankka-design/SKILL.md` a short section "## A change that crosses services" stating the same four rules for the plan and the work list, pointing at `references/concepts/projects.md`; run `just docs-sync`
- [ ] T036 [US3] Extend `ProjectsDocumentationSuite`: one test per scenario of `features/projects/specifying.feature` over the page and the skill; one for `features/documentation/projects.feature` scenario 2 over `concepts/projects.md` (recommends one repository holding every service, its context map, its contracts and its specs; a service tag deploys one service alone; when a service moves out and what stays); run green with `just docs`

---

## Phase 6: User Story 4 - A project run and checked on one machine (Priority: P3)

**Goal**: every service runs on one machine on its own database and finds the others; the inspect
skill checks a scenario across services and keeps it as a test.

**Independent Test**: quickstart §4 by hand with the inspect skill, and §5's documentation
scenario 3 tests.

- [ ] T037 [P] [US4] Edit `docs/deploy/run-locally.md`: under "Configure the database and HTTP port", a paragraph that a project started from the project template gives each service its own database and port through `scripts/run.sh`, linking `build/projects.md`; under "Run two nodes as one cluster" or beside it, how services on one machine find each other (announced under their names; the local console; `ankka.local-services` where the console's directory is not shared, as `calling-services.md` shows); correct `docs/build/calling-services.md` lines 445 to 447 so the page says the trace continues into the service called under the same trace id, as `operate/telemetry.md` says
- [ ] T038 [P] [US4] Fill the two sections of `docs/build/projects.md` left by T030: "Run every service on one machine" (`docker compose up -d`; `scripts/run.sh orders`, `scripts/run.sh billing`; one database per service made by `scripts/databases.sh`; distinct ports from the descriptors; Kafka on 9092; `ankka local console` lists them; how each finds the others) and "A test that crosses services" (the tests module; two kits on one in-memory broker; where a kept scenario goes; that it starts the services it needs; that it covers Scala services, and a service in another language is checked by the inspect skill against the running service)
- [ ] T039 [P] [US4] Edit `tools/docs/skill/ankka-inspect/SKILL.md`: add `concepts/projects.md` and `build/projects.md` to `pages:`; a section "## A scenario that crosses services" per research R11 (read `context-map.json` for the services and the crossing; every service listed by `list_local_services` or *not met* naming it; `describe_local_service` on each side and compare the stated contract with the crossing's, a difference *not met* naming the contract each side states; drive the first service's route; `query_local_entity` on the second until it answers; `local_traces` on the first for the trace id, then the same id on the second, the consumer span continuing it; the report row names the crossing `from → to by topic`, the request, the producer and consumer spans as what was published, the query's answer; keep a met scenario in `tests/` as `build/projects.md` shows); extend the test-commands table with `sbt tests/test` and `scripts/run.sh <service>`; run `just docs-sync`
- [ ] T040 [US4] Edit `docs/reference/limitations.md` "Observability": no local tool reads the messages on a topic; a trace across services is read one service at a time by its id; and register nothing new (no new page); run `just docs` green
- [ ] T041 [US4] Extend `ProjectsDocumentationSuite`: one test per scenario of `features/projects/running-locally.feature` and `features/projects/inspecting.feature` that a file read can prove (the skill says each step above; `run.sh` sets `ANKKA_DB_NAME` to the service and the port from the descriptor; the compose file has Kafka) and one for `features/documentation/projects.feature` scenario 3 over `build/projects.md` and `deploy/run-locally.md`; the suite's doc comment names the behavioural half (quickstart §4) as proven by the recorded run of T042
- [ ] T042 [US4] Run quickstart §4 by hand on the rendered project: both services up through `scripts/run.sh`, `ankka local console` listing both, the request answered across the route crossing with the trace holding the call, then with a coding agent holding the rendered `ankka-inspect` skill: "check the scenario an order placed is invoiced" met with the crossing named and one trace id on both sides; billing killed → not met naming billing; billing rebuilt stating `orders.v2` → not met naming both contracts; record the three reports under `specs/062-projects-of-services/runs/inspect.md` and what the skill had to say differently in `research.md` "Found during implementation"; fix the skill

---

## Phase 7: Polish

- [ ] T043 Run `python3 .github/ci-coverage.py` and `python3 .github/cluster-suites.py`; claim every new file (the template directories under the `template-project` filter and `build (rest)`; the runs under `unchecked` with the reason); run `gh workflow run ci` is not needed, a pull request builds what it touched
- [ ] T044 [P] Add the traps learned to the rule files, dated: `.claude/rules/build-and-release.md` (the project template is the CLI's, `extraProjects` discovery, service tags and `git describe --match`, `SERVICE_VERSION` precedence, the `project` word of the switch) and `.claude/rules/docs.md` (the context map's rendering is written by the CLI, never a template file; the two new reference sections)
- [ ] T045 [P] Update the root `GLOSSARY.md` only if implementation changed a term's meaning (none expected); run `just features` green
- [ ] T046 Run `sbt scalafmtAll scalafmtSbt`, `sbt scalafmtCheckAll`, `sbt 'core/testOnly *ContextMap*' 'testkit/testOnly *ContextMap* *ProjectsDocumentationSuite' 'cli/testOnly *InitSuite *CliReferenceSuite'`, `sbt -Dankka.template.tests=project 'cli/testOnly *ProjectTemplateSuite'`, `sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'` (FR-012: the single-service template unchanged), `just docs`, `just features`; measure SC-002 (an empty directory to a three-service project with every service's tests green, wall clock) and record every result in `research.md` under "Measurements"
- [ ] T047 Fill `research.md`'s "Found during implementation" with everything the "Verify at implementation" list settled (R6, R7, R8, R9, R13, the native image globs, `docs check`), and leave the tick count ready for `/speckit-build`'s pull request
- [ ] T048 With Trevor's go-ahead (ask through AskUserQuestion before creating anything outside this repository), prove the pipeline live once per research R15a: `gh repo create` a private scratch repository, push the rendered project, open a pull request changing `services/orders/` only and confirm `changes` lists orders alone and `checks` runs once; push the tag `orders/v0.1.0` and confirm `resolve` names orders, `checks` runs and `deploy` declines for want of `ANKKA_TOKEN`; push `shipping/v0.1.0` and confirm `resolve` fails saying the project has no service shipping; record the three run links in `research.md` under "Measurements" and delete the repository

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 (T001 to T003) first; T002 and T003 parallel with T001.
- Phase 2 (T004 to T009) after Phase 1: T004 before T005, T006, T008; T006 before T007; T008
  before T009. T006 and T008 are parallel once T004 is in.
- Phase 3 (US1) after T004 (the skill names the file's shape) and T009 is not needed; T010 to
  T013 parallel; T014 after them; T015 after T014; T016 after T014.
- Phase 4 (US2) after Phase 2: T017 and T018 first, T019 to T024 parallel with each other after
  T017; T025 after T017 to T024; T026 after T025 (the renderer's shape is settled by running it);
  T027 and T028 after T026; T029 to T031 parallel with T026; T032 after T030 and T031; T033 after
  T028 and T032.
- Phase 5 (US3) after T010 and T012; T034 and T035 parallel; T036 after both.
- Phase 6 (US4) after T025 (a project to run) and T030; T037 to T039 parallel; T040 after T039;
  T041 after T038 to T040; T042 after T039 and T028.
- Phase 7 last; T048 after T028 and needs Trevor's go-ahead.

### User Story Dependencies

- US1 needs only the foundation; it is the MVP: the rule and the skill change behaviour today
  without the template.
- US2 needs the foundation; it does not need US1 (the template ships a written map).
- US3 needs US1's page; it writes two sections and two tests.
- US4 needs US2's rendered project to run and inspect.

### Parallel Opportunities

- T010, T011, T012, T013 (four files) at once.
- T019 to T024 (six template files or groups) at once after T017.
- T029, T030, T031 beside T026.
- T037, T038, T039 at once.

## Parallel Example: User Story 2's template

```text
After T017 and T018:
  T019 shared module and contract     T020 the sample crossing in both services
  T021 context-map.json               T022 tests module suites
  T023 compose and scripts            T024 workflows, hooks, README, GLOSSARY, .specify
Then T025 (run it by hand), then T026 (the command line).
```

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1, Phase 2 (the context map's logic is small and proven by its suites).
2. Phase 3: the page, the skill, the glossary, the suite, the recorded run.
3. Stop and validate: `just docs`, `ProjectsDocumentationSuite`, the three runs.

### Incremental Delivery

- US1: a developer with the new skill gets a context map and the rule, today.
- US2: the template, the command line and the releases; `ProjectTemplateSuite` green.
- US3: the guidance for a change across services; two sections, two tests.
- US4: running and inspecting; the recorded inspect run.
- Polish: coverage, traps, formatting, measurements.

## Notes

- The context map's parser, checks and rendering exist once, in `core`; the CLI, the testkit and
  the generated `ContextMapSuite` call it. `CONTEXT-MAP.md` is always written by `render`.
- Nothing under `ankka.g8/` changes except the re-rendered skills; `TemplateSuite` keeps proving
  the single-service template unchanged (FR-012).
- A test named after a scenario must be able to fail: break the thing once (quickstart §3 step 3,
  the four map breaks, billing invoicing nothing).
- Commit after each task with `<type>(062): …`; `feat(062)` for code and template, `docs(062)`
  for pages and skills, `test(062)` for a suite alone.
