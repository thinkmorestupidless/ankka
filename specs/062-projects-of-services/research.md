# Research: Projects of several services

Decisions made while planning, numbered; what to verify at implementation; declined remediations.
The facts each decision rests on were read from the tree on 2026-10-31; file paths are given so
the build can re-read them.

## What exists today, in brief

- **One service per repository, everywhere.** The Scala template is Giter8 (`ankka.g8/`), expanded
  by `sbt new` through `ankka init`; the seven other languages' templates are the CLI's own under
  `cli/src/main/templates/<lang>/`, rendered by `Scaffold` with exact tokens (`{{name}}`,
  `{{ankka_version}}`, `{{protocol_version}}`, `{{package}}`…). Every template has one
  `service.json`, one `ci.yml` (`sbt test` or the language's equivalent, no path filters) and one
  `deploy.yml` on `v*` tags that builds one image, pushes it to GHCR and runs `ankka services
  deploy <name> "$IMAGE"` after `thinkmorestupidless/ankka-action@v1`. No template derives a version
  from git: Scala's `build.sbt` reads `SERVICE_VERSION`, set by the job from the tag.
- **Three JVM build tools.** Scala is sbt, Java is Maven (`mvnw`, Jib), Kotlin is Gradle.
- **Nothing ships spec-kit.** `ankka init` writes `.claude/skills`, `.mcp.json`, the hooks and the
  workflows; never `.specify/`. `docs/get-started/design-and-build.md` says so and names
  `specify init --here --ai claude --ai-skills`.
- **A descriptor declares no topic.** Topics and contracts are declared on the project
  (`ankka projects topics set … --contract order.v1 --schema …`, `TopicDeclarationRequest` in
  `controlplane-api`); a component states its contract in code (`Publication`, `TopicOptions`),
  and the runtime compares the two at start from the file `ANKKA_PROJECT_DECLARATIONS` names, when
  it is set (`ProjectDeclarations.scala`, `ProjectionRuntime.rejectUndeclared`). Only a contract
  that differs from a declared one is refused; an undeclared topic is reported in the service's
  status, not refused. Locally nothing is declared and nothing is checked.
- **Several services on one machine already find each other.** A service started outside
  Kubernetes announces itself in `~/.ankka/running/<pid>.json` under its actor system's name
  (`ServiceRegistration.scala`), and `HttpServiceClients` resolves a `services("<name>")` call by
  that name, after the config key `ankka.local-services."<name>"`. The local console reads the same
  directory. The name announced is the one given to `Ankka.start(name)`, `"ankka"` by default; the
  single-service template passes none.
- **One database by default, and no broker.** `ANKKA_DB_NAME` defaults to `ankka`; the run-locally
  page says two services must never share one. No compose file in the tree runs Kafka; a topic
  needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` or `ProjectionRuntime.withKafka`.
- **Traces cross services.** `traceparent` goes on every service-to-service HTTP call and, as a
  Kafka header, on every published message; the consumer span continues the publisher's trace.
  Each service keeps its own ring of recent spans, read by `local_traces` one service at a time;
  nothing merges them locally. `docs/build/calling-services.md` lines 445-447 say the opposite of
  the code and must be corrected.
- **The local MCP tools** are `list_local_services`, `describe_local_service`, `local_traces`,
  `query_local_entity`, `call_local_endpoint`, `local_agent_session`; none reads a topic.
- **Documentation and skill scenarios are proven by munit suites that read files**
  (`DesignAndBuildDocumentationSuite`, `ServiceCallsDocumentationSuite`…), one test per scenario,
  named after it. Agent-run scenarios are proven by recorded runs. A changed skill is rendered by
  `just docs-sync` into `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`
  (with `$` escaped), and the CLI's templates take the marketplace copy at build time.
- **Two test kits in one JVM** is done (`ServiceCallsSuite`, `CrossProjectTopicSuite`), never two
  different services wired to each other over a shared broker. `InMemoryBroker` with
  `ProjectionRuntime.withBroker(broker, broker)` can be handed to two kits; `AnkkaTestKit.start`
  takes `localServices` for the HTTP side.

## Decisions

### R1. The project template is the command line's own, rendered by `Scaffold`

**Decision**: The project template lives in `cli/src/main/templates/project/` and a Scala service
skeleton for use inside a project in `cli/src/main/templates/project-service-scala/`, both
rendered by `Scaffold` with the existing tokens plus `{{project}}`, `{{service}}` and
`{{services_json}}` where the project's files need them. No Giter8, no `sbt new`, no new
repository to subtree-push. The single-service Scala template (`ankka.g8`) and the seven language
templates do not change (FR-012).

**Rationale**: The CLI already renders seven templates this way and ships them in its jar and
native image; a Giter8 template needs every `$` escaped, a repository of its own and `sbt` on PATH
to render. A project holds files no Giter8 template could carry unescaped (workflows with `${{ }}`
for several services). The Scala service skeleton inside a project is not the `ankka.g8` tree:
it has no `build.sbt`, `project/`, compose file or workflows of its own, since the project owns
those.

**Alternatives considered**: a second Giter8 template `ankka-project.g8` (a second subtree push,
the `\$` trap doubled); composing the project from `sbt new ankka.g8` per service (each expansion
is a whole build; stripping it is more fragile than a skeleton).

### R2. One command: `ankka init --project` starts a project, `ankka init` inside one adds a service

**Decision**: `ankka init <name> --project --services <a>,<b>[,<c>…] [--language scala|…]
[--package …] [--dir …]` renders the project at `<dir>/<name>` with one service per name, all in
the one language given (Scala by default). `--services` is required with `--project` and takes at
least two names, each a valid service name and distinct. `ankka init <service> [--language …]`
run where the current directory (or `--dir`) holds `context-map.json` adds a service to that
project: it renders the language's service files into `services/<service>/`, gives it the next
free port, records it in the context map, and prints the service's next steps. Run elsewhere the
command is exactly what it is today. `--project` inside a project, or `--services` without
`--project`, is refused. An optional flag `--add` says the person means to add to a project: inside
one it changes nothing, outside one it is refused with "`<dir>` holds no project started from the
project template (no context-map.json); nothing was written". Without the flag, `ankka init stock`
in an empty directory must keep making a single-service project (FR-012), so the flag is what lets
the scenario "adding a service in a directory that holds no project is refused" be met, and what
tells a person who stood in the wrong directory, rather than handing them a new project.

**Rationale**: Chosen by the person at planning. One front door; detection by the one file a
project cannot be without. The `--add` flag exists only to make the mistake in the scenario
refusable: without a flag, `ankka init stock` in an empty directory must keep making a
single-service project (FR-012), so it cannot also refuse.

**Alternatives considered**: `ankka project init` / `ankka project add` (a second command group to
document); detection by `services/` (a directory any repository may have).

### R3. The layout of a project

```text
shop/
├── context-map.json              # the context map (R4); its presence is what makes this a project
├── CONTEXT-MAP.md                # rendered from it; `ankka context-map render` writes it
├── contracts/
│   └── order.v1.json             # one JSON Schema per contract a crossing names
├── build.sbt                     # the Scala build: shared, tests, and every Scala service found under services/
├── project/{build.properties,plugins.sbt,Services.scala}
├── shared/src/main/scala/…       # the shared module: the contracts' types, Contract values from contracts/
├── services/
│   ├── orders/{service.json,src/main/…,src/test/…}     # a Scala service: no build file of its own
│   ├── billing/…
│   └── stock/{service.json,pyproject.toml,…}            # a service in another language: its own build
├── tests/src/test/scala/…        # the tests module: cross-service tests and ContextMapSuite
├── docker-compose.yml            # postgres and kafka, one of each
├── scripts/{run.sh,databases.sh,changed-services.sh,service-language.sh}
├── .github/workflows/{ci.yml,service-checks.yml,deploy.yml}
├── .specify/{extensions/{bdd,review,design-build}/…,extensions.yml}
├── .claude/skills/{ankka*,speckit-design,speckit-build}/
├── .mcp.json, .githooks/, .github/pull_request_template.md, .gitignore, README.md
```

**Decision**: As drawn. A service's directory is `services/<name>/`; its descriptor is
`services/<name>/service.json` with `"name": "<name>"`, `"image": "<name>:latest"` and a `port`
distinct within the project (9000, 9001, … in the order services were added), so that the local
run needs no port table. Scala services are discovered by the build (R6); any other language is a
self-contained directory rendered from that language's template without the parts the project
owns (`.github/`, `.githooks/`, `.claude/`, `.mcp.json`, the compose file, the PR template).

**Rationale**: FR-007 (own directory, descriptor, image, database, tests, named for the service);
one place for the build so the shared module is a plain `dependsOn`; one place for workflows so
a service is wired in by being there.

### R4. The context map is one JSON file, `context-map.json`, at the root

**Decision**: JSON, strictly decoded (an unknown field is refused, as a descriptor's is):

```json
{
  "project": "shop",
  "services": [
    {"name": "orders",  "owns": "orders and their lines", "boundary": {"criterion": "ownership", "why": "the orders team releases weekly"}},
    {"name": "billing", "owns": "invoices", "boundary": {"criterion": "ownership", "why": "finance releases on its own"}}
  ],
  "crossings": [
    {"from": "orders", "to": "billing", "topic": "orders-placed", "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"from": "orders", "to": "billing", "route": "POST /invoices"}
  ]
}
```

`criterion` is one of `ownership`, `scaling`, `consistency`. A crossing has either `topic`,
`contract` and `schema`, or `route`; anything else is refused. `from` and `to` name services of
the map, or `<project>/<service>` for a service of another project (the cross-project edge
case). `owns` and `why` are free text and may be empty; the design skill fills them, `ankka init`
leaves them empty for a service it adds. The parser and the static checks live in `core`
(`com.thinkmorestupidless.ankka.core.ContextMap`, jsoniter, no other dependency) so the CLI, the
testkit and the inspect skill read one definition.

**Rationale**: The person chose a structured file. JSON is what every other ankka file is
(`service.json`, `.mcp.json`); the prose fields are short. The file names only the project's own
design, nothing the control plane applies, so FR-021 holds: it is not a descriptor for a set of
services. `project` is in it because the rendering and the local declarations (R8) need it.

**Alternatives considered**: YAML (kinder to prose, a second parser in `core`); a Markdown page
with a table (not machine-checkable without a second grammar).

### R5. The checks: `ankka context-map check` statically, the tests module at run time

**Decision**: Two new CLI commands, `ankka context-map check [--dir .]` and `ankka context-map
render [--dir .]`, with the reference page sections `just docs-reference` demands. `check` fails,
naming each problem, when: the file does not parse or has an unknown field; a service in the map
has no `services/<name>/service.json`, or such a directory exists for a name not in the map
("`shop` has no service `shipping`", "the context map does not name `billing`"); a crossing's
`from` or `to` names no service of the map and is not `<project>/<service>`; a topic crossing's
`schema` file is missing or is not a JSON document, or its `contract` is not a valid contract name;
two crossings are identical; a crossing is of neither kind; `CONTEXT-MAP.md` differs from what
`render` would write. `render` writes `CONTEXT-MAP.md`: the project, a table of services (name,
owns, criterion, why), a table of crossings (from, to, what passes, contract or route). What a
service states in code is compared by the tests module's `ContextMapSuite` (R7) and by the inspect
skill (R10), not by `check`.

**Rationale**: Chosen by the person at planning after the premise was corrected. The static facts
are all in the tree; the stated contracts exist only in a running service. Logic in the CLI and
`core` is upgraded with the CLI, where a script copied into every project could not be.

**Alternatives considered**: a script in the template (a copy per project); a check only in the
tests module (the inspect skill and a developer without sbt could not run it).

### R6. The Scala build discovers services; nothing in `build.sbt` is edited to add one

**Decision**: `project/Services.scala` is an `AutoPlugin` whose `extraProjects` lists every
`services/<name>/` that holds no build file of its own and a `src/main/scala`, making a project
`<name>` in that directory with `JavaAppPackaging`, `DockerPlugin`, the template's docker settings
(`Docker / packageName := <name>`, `dockerRepository` from `DOCKER_REPOSITORY`, platforms from
`ANKKA_IMAGE_PLATFORMS`), `.dependsOn(LocalProject("shared"))`, and the version of R9. `build.sbt`
declares `shared` and `tests`; `tests` depends on every discovered service (`LocalProject` by
name, listed by the same plugin). Adding a Scala service is therefore creating its directory.

**Rationale**: FR-008's "nothing edited by hand" with the fewest moving parts: the CLI edits no
`build.sbt`. sbt's `extraProjects` is the supported way for a plugin to add projects.

**Verify at implementation**: `extraProjects` with `dependsOn(LocalProject("shared"))` resolves
under sbt 1.12 and 2.0 launchers (the `template-scala` job runs both); `sbt <name>/test` and
`sbt <name>/Docker/publishLocal` address a discovered project.

**Alternatives considered**: `ankka init` inserting a `lazy val` between markers in `build.sbt`
(an edit of a Scala file by string surgery); a `services.sbt` the CLI regenerates whole (a second
file to keep in step with the directory).

### R7. The tests module: two kits in one JVM over a shared in-memory broker

**Decision**: `tests/` is an sbt project depending on every Scala service and `ankka-testkit`.
Each Scala service in a project exposes its wiring as a value a test can start:
`object OrdersService { val descriptors: Seq[ComponentDescriptor]; def extensions(broker:
Broker): Seq[RuntimeExtension] }` (the service's `Main` calls `Ankka.service … .start(name =
"orders")` from the same object). A cross-service test starts one `AnkkaTestKit` per service it
needs, each under its own service name, all on one `InMemoryBroker` through
`ProjectionRuntime.withBroker(broker, broker)`, with `localServices` wiring the route crossings
between kits. The template ships `tests/src/test/scala/<pkg>/OrderInvoicedSuite.scala` as the
worked example (orders publishes to `orders-placed`, billing invoices), and `ContextMapSuite`,
which reads `context-map.json` and, for each Scala service, starts its wiring and asserts that
every topic it publishes to or reads is a crossing of the map from or to it with the same contract
name, and that every topic crossing naming it is stated by one of its components. The comparison
is `ankka-testkit`'s `ContextMapCheck` over `core`'s `ContextMap`, so the generated suite is a few
lines.

**Rationale**: The scenario "a test of the scenario starts orders and billing and passes" and
FR-022's run-time half. An in-memory broker keeps the project's tests free of Docker beyond the
Postgres every kit already uses; Kafka itself is the deployed platform's and the local run's.

**Verify at implementation**: two `AnkkaTestKit.start` calls alive at once in one JVM, each with
a different `serviceIdentity` name, sharing one `InMemoryBroker`, with a consumer in one kit
reading what a consumer in the other published; `restartService()` on one kit leaves the other
running. If `LocalInstallations` or the shared Postgres template conflicts, the fallback is one
kit per service in sequence with a Kafka testcontainer, and the research is updated.

### R8. Running a project on one machine

**Decision**: `docker-compose.yml` runs one `postgres:17-alpine` and one `apache/kafka:3.8.0`
(KRaft, one node, `localhost:9092`). `scripts/databases.sh` creates a database named for every
service under `services/` that does not yet exist and applies the DDL to it (`sbt schema`'s
`target/ddl` for the Scala build, the sidecar image's for a process-hosted service, as the single
templates do). `scripts/run.sh <service>` runs `databases.sh`, then starts the service with
`ANKKA_DB_NAME=<service>`, `ANKKA_HTTP_PORT=<its service.json port>`,
`ANKKA_SERVICE_NAME=<service>` and `ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, by the
language's own run command (`sbt <service>/run`, `uv run …`, …). Each Scala service starts its
actor system under its own name, so it is announced under it and `services("billing")` resolves
with nothing configured; the local console lists them all. The runtime's default database name
stays `ankka`.

**Rationale**: The person's answer at clarify (the project's recipe and compose, not the
runtime). Templates carry no Justfile today and `just` is not a tool a developer is asked for, so
the recipes are scripts.

**Verify at implementation**: the name `Ankka.start(name)` takes is the one announced (read
`ServiceRegistration.announce`); the sidecar's announced name for a process-hosted service is
`ANKKA_SERVICE_NAME`.

### R9. Service tags and versions

**Decision**: A service tag is `<service>/v<version>`, for example `orders/v1.4.2`. `deploy.yml`
triggers on `tags: ['*/v*']` and by hand; its first job reads the service from the tag, fails
when `services/<service>/service.json` does not exist ("shop has no service shipping"), and
determines the language with `scripts/service-language.sh`. It then runs that service's checks
through the reusable `service-checks.yml` (so a failing test deploys nothing), builds and pushes
the image with the language's steps from today's single templates (`sbt <service>/Docker/publish`
with `SERVICE_VERSION` for Scala; `docker build` or Jib or Gradle for the others), installs the CLI
with `ankka-action@v1`, and runs `ankka services deploy <service> "$IMAGE" -f
services/<service>/service.json`. A plain `v*` tag matches nothing in a project and is said to in
the README. Each Scala service's version is `SERVICE_VERSION` when set, else derived by
`Services.scala` from `git describe --tags --match '<service>/v*'`: `1.4.2` at the tag,
`1.4.2+3-<sha>-SNAPSHOT` past it, `0.0.0+<n>-<sha>-SNAPSHOT` with no tag; `Docker / version`
replaces `+` with `-` as the template does. Services in other languages have no local version
derivation; their image version is the tag's, as today.

**Rationale**: FR-010, FR-011 and the releases feature. A slash keeps the service and the version
apart without a second separator convention; GitHub's `*` does not match `/`, so `*/v*` is
exactly one service's tags. Deriving from the matching tags alone makes "orders follows 1.4.2
whatever billing was tagged" true.

**Alternatives considered**: `<service>-v<version>` (a service name may hold hyphens); sbt-dynver
(derives from any tag).

### R10. The pull request's checks run what a change touched

**Decision**: `ci.yml` has a `changes` job that runs `scripts/changed-services.sh <base> <head>`,
which maps changed paths to work: `services/<n>/**` → `n`; `shared/**`, `build.sbt`,
`project/**` → every Scala service and `tests`; `tests/**`, `contracts/**`, `context-map.json` →
`tests`; anything else → nothing. It prints a JSON matrix of `{service, language}` plus a `tests`
flag. A `checks` job runs the matrix through the reusable `.github/workflows/service-checks.yml`
(inputs `service`, `language`; one step group per language, as the single templates' `ci.yml`
test steps), and a `tests` job runs `sbt tests/test` when the flag is set, which includes
`ContextMapSuite` and so the static check too (it calls `core`'s `ContextMap.check` first). The
`commits` job is the single templates'. Adding a service edits no workflow: the script finds it by
its directory and `service-language.sh` by its build files (`pyproject.toml` Python,
`package.json` TypeScript or web, `Cargo.toml` Rust, `go.mod` Go, `pom.xml` Java,
`build.gradle.kts` Kotlin, none of those with `src/main/scala` Scala).

**Rationale**: FR-009 and SC-004: a service's own tests run for its own changes; the shared module
runs the Scala services that depend on it (all of them, by construction, R6) and the tests module,
which starts them. The scenario in which `stock` does not use the shared module is a `stock` in
another language.

### R11. The skills

**Decision**: `ankka-design` gains, before the component table, a step "One service, or several"
that decides by the three criteria, writes `context-map.json` when it is several (every service,
what it owns, its criterion, every crossing as a declared topic with a contract and schema or a
route with its caller), merges two services that would have to be released together and says so,
names a cycle of calls and asks which direction becomes a topic, and names a crossing of any
other kind as a defect; its review smells gain the cycle and the crossing without a contract; its
pages gain `concepts/projects.md`, `build/topics.md` and `build/calling-services.md`; step 10
points at the new step. `ankka-inspect` gains a section "A scenario that crosses services": read
`context-map.json` for the services and the crossing the scenario passes, require each service
listed by `list_local_services`, drive the first service's route, wait on the second's declared
query, take the trace id from the first service's `local_traces` and ask the second service for
the same id (the consumer span continues it), compare each side's stated contract from
`describe_local_service` with the crossing's and report a mismatch or a service not running as
*not met* naming the service, and keep the met scenario in `tests/` as R7 describes; its report
names the crossing. Both skills are re-rendered by `just docs-sync`.

**Rationale**: FR-001 to FR-004, FR-018 to FR-020, FR-024. Everything the inspect section needs
exists in the MCP tools; what was published is evidenced by the producer span in the first
service's trace and the consumer span in the second's, since no tool reads a topic.

**How proven**: `ProjectsDocumentationSuite` (munit, in `testkit`'s tests, beside the other
documentation suites), one test per scenario of `features/projects/designing.feature`,
`specifying.feature` and `features/documentation/projects.feature`, asserting the skill and page
texts say each thing, as `DesignAndBuildDocumentationSuite` does; the agent-run behaviour
(SC-001) is proven by a recorded design run on three domain descriptions kept under
`specs/062-projects-of-services/runs/`, the way feature 061 recorded its runs.

### R12. The documentation

**Decision**: Two new pages and five edits.
- `docs/concepts/projects.md` (concept, "A project of several services"): what a project is; the
  rule that services deployed together are one service and that the platform deploys each on its
  own, deliberately; the context map, boundaries and crossings; one repository per project and why
  per-service releases keep it from tying deployments together; when a service moves out and what
  stays; a change that crosses services as one spec, the contract first, an incompatible change as
  a new contract read beside the old.
- `docs/build/projects.md` (guide, "Build a project of several services"): start one with `ankka
  init --project`; what it holds; add a service; the shared module and `contracts/`; the context
  map file, `ankka context-map check` and `render`; run every service on one machine and how they
  find each other; the tests module and a cross-service test; service tags.
- `docs/concepts/designing-services.md` "Service boundaries": the rule, a pointer to the context
  map page. `docs/deploy/ci.md`: a section "A project of several services" on service tags and
  the reusable checks. `docs/deploy/run-locally.md`: a pointer under the database section.
  `docs/reference/limitations.md`: no deploying several services as one; the tests module starts
  Scala services only; no tool reads a topic locally. `docs/reference/cli.md`: the two new
  commands' hand-written sections. `docs/reference/glossary.md`: project (changed), context map,
  crossing, service tag, shared module, project template. `docs/get-started/coding-agents.md`: the
  design and inspect rows name projects. `docs/build/calling-services.md` lines 445-447: the trace
  does continue into the service called.
- `mkdocs.yml` nav: Concepts after "Designing a service"; Build after "Calling other services".
  Skills: `ankka-design` carries `concepts/projects.md`; `ankka-deploy` carries `build/projects.md`
  (it carries the deploy and run-locally pages).

**Rationale**: The contributing page's rule: a concept page says how and why, a guide gets one
task done; a page in `nav` and in a skill or `docs check` fails.

### R13. spec-kit in the project

**Decision**: The project template carries `.specify/extensions/{bdd,review,design-build}/`,
`.specify/extensions.yml` (installed and hooks as this repository's) and the two skills
`.claude/skills/speckit-{design,build}/`, copied from this repository's tree onto the CLI's
classpath by the same resource generator that copies the marketplace skills. `ankka init
--project` ends by running `specify init --here --ai claude --ai-skills` in the project when
`specify` is on PATH, and otherwise prints that command as the first next step. The bdd
extension's config names the glossary and features paths; the template ships an empty
`features/` with a `README.md` and a `GLOSSARY.md` seeded with the project's terms, since the
checker refuses a run that read no scenario.

**Rationale**: The person's answer at planning. spec-kit's own files change with its releases
and are installed by its own tool; the family's extensions are this repository's to ship.

**Verify at implementation**: `specify init --here` into a directory that already holds
`.specify/extensions/` keeps them; the bdd extension's hooks work with an empty `features/`.

### R14. How this feature is proven in ankka's own build

**Decision**:
- `ProjectTemplateSuite` in `cli`'s tests, under `-Dankka.template.tests=project` (a new word
  of the switch; CI job `template-project`, Docker and `sbt` needed): renders `shop` with
  `orders` and `billing`; asserts the layout, the descriptors (names, images, distinct ports), the
  context map's two services and crossing, `CONTEXT-MAP.md` in step; runs `sbt test` (both
  services' suites and the tests module, two kits on one broker); `sbt orders/Docker/publishLocal`
  gives `orders:latest`; `sbt 'show orders/version'` follows an `orders/v1.4.2` tag made in the
  temp repository and not a `billing/v3.1.0` one; `ankka context-map check` passes, then fails
  with the expected message after each of: a service directory removed, a service added to the map,
  a schema file renamed, the rendering edited; `ankka init stock --language python` inside the
  project writes `services/stock/`, port 9002, the map entry, and `scripts/changed-services.sh`
  maps a change under it to `stock` alone, a change under `shared/` to `orders`, `billing` and
  `tests`; `ankka init stock --add` in an empty directory refuses with the scenario's message; the
  workflows survive rendering (balanced `${{`, the tag glob, `services deploy`, the action, no
  secret in `ci.yml`).
- `InitSuite` gains the fast cases: argument shapes, `--services` validation, detection of a
  project, the next-free-port rule, the `specify` next step when it is not on PATH (scripted).
- `ContextMapSuite` in `core`'s tests: the parser, every static problem message, the rendering.
- `ContextMapCheckSuite` in `testkit`'s tests: the run-time comparison over two small services.
- `ProjectsDocumentationSuite` (R11).
- The release scenarios (what a tag builds and deploys) are proven by the workflow-shape
  assertions and the scripts' tests, as every template's deploy is today; no real GitHub run.

### R15a. Decided at analyze

- **Initial services are Scala** (finding I1): `--project` with `--language` other than `scala`
  is refused: "`--project` starts Scala services; add a service in another language inside the
  project with `ankka init <service> --language <lang>`". The template's worked example (two
  Scala services, the shared module, the cross-service test) is what every project starts with.
- **A sidecar or runtime service keeps a compose file of its own** (finding U1): `ankka init
  <service> --language <python|typescript|go|rust|web>` inside a project keeps that template's
  `docker-compose.yml` in `services/<service>/`, trimmed of its `postgres` service and pointed at
  the project's Postgres and Kafka on the host (`host.docker.internal`, the compose network's
  gateway on Linux), its `schema` service copying the DDL as today; `scripts/run.sh <service>`
  runs `docker compose -f services/<service>/docker-compose.yml up -d` before the process. The
  root compose file is never edited by the CLI.
- **`specify init` is switchable** (finding U2): the system property `ankka.init.specify`
  (`off` never runs it, `on` the default runs it when on PATH) is forwarded to forked tests, and
  `ProjectTemplateSuite` sets `off`, so no suite downloads spec-kit.
- **The releases are proven live once** (finding C1): with Trevor's go-ahead, the rendered
  project is pushed to a scratch repository under his account, a pull request changing one service
  shows `changes` and `checks` run for that service alone, and a tag `orders/v0.1.0` shows
  `resolve` and `checks` run and `deploy` decline for want of a token; the run links are recorded
  and the repository deleted. SC-003's deploy itself is proven at the first real release of a
  project, as every template's deploy is today.

### R15. What stays out

Nothing deploys several services as one, no descriptor names a set of services, no command
applies several descriptors (FR-021). A tool that reads a topic locally, a merged local trace view,
a Kafka testcontainer in the testkit, and `ankka init` wiring a service in another language into
the *Scala* build are not built; the first three go to the limitations page.

## Verify at implementation

1. R6: `extraProjects` discovery under both sbt launcher lines; `sbt <name>/test` addresses a
   discovered project.
2. R7: two kits alive at once in one JVM on one `InMemoryBroker`; the service wiring as a value
   the kit can start under a name.
3. R8: the announced name is `Ankka.start`'s `name`; the sidecar announces `ANKKA_SERVICE_NAME`.
4. R9: GitHub's tag glob `*/v*` matches `orders/v1.4.2` and not `v1.4.2`; `git describe --match`
   with a slash in the pattern.
5. R13: `specify init --here` keeps an existing `.specify/extensions/`.
6. The CLI's native image carries `ankka/templates/project/**` and the extensions' files
   (`META-INF/native-image` globs; `native-smoke.sh` renders a project).
7. `docs check` accepts the new pages' frontmatter and the two reference sections.

## Declined remediations

(none yet)

## Design finished

2026-10-31: analyze reported nothing above LOW after one round of remediations (two decided by
the person: Scala initial services; a sidecar service keeps a trimmed compose file of its own).

## Found during implementation

2026-10-31, from rendering the project by hand (T025) and running it:

- **R6 holds under sbt 1.12.** `project/Services.scala`'s `extraProjects` makes a project per
  `services/<name>/` holding `src/main/scala`, `dependsOn(LocalProject("shared"))` resolves, and
  `sbt orders/test` and `sbt orders/Docker/publishLocal` address it; the latter built `orders:1.4.2`
  at the tag `orders/v1.4.2` and no billing image. `build.properties` pins sbt 1.12.9, so an sbt 2
  launcher runs the same sbt and the plugin compiles once, under Scala 2.12. Native-packager's keys
  are `com.typesafe.sbt.packager.Keys`, except `dockerBuildxPlatforms` and `Docker`, which are only
  in `DockerPlugin.autoImport`; importing both wholesale is ambiguous.
- **R7 holds, with the wiring as a function of the extensions.** Two kits in one JVM on one
  `InMemoryBroker`, each under `ServiceIdentity.local(<service>)`, with `localServices` naming the
  other's port: the crossing suite went green, and red when billing recorded a wrong count. There is
  no `Broker` type, so the service object exposes `descriptors`, `endpoint(clients)` and
  `extensions(projections: ProjectionRuntime, http: HttpServer)`; the test passes
  `ProjectionRuntime.withBroker(broker, broker)` and a server on port 0, `start()` the environment's.
- **The tests module's map check needs no running service.** `ContextMapCheck.problems` gained an
  overload over a service's descriptors, read through the same `DeclaredTopics`; the template's
  `ContextMapSuite` finds each Scala service of the map by the naming `ankka init` uses
  (`<pkg>.<name without ->.<Name>Service`), so a service added later is checked with nothing edited.
- **The sample's names are generic.** Services are named by the person, so the sample crossing is
  the topic `<first>-events` carrying the contract `<first>.v1`, and the route `GET /<second>/{id}`
  called by the first; the cross-service suite is `ItemAddedIsRecordedSuite`. The skeleton carries
  the role differences as three variant directories (`first/`, `second/`, `plain/`) beside
  `common/`, which `Scaffold` renders by prefix (T020's choice).
- **The contract's schema is not mirrored by hand.** `shared`'s build copies `contracts/*.json`
  onto its classpath under `contracts/`, so the fingerprint is of the file the map names.
- **A sidecar in a container reaches the project's Kafka on a second listener.** The project's Kafka
  advertises `localhost:9092` for services run on the machine and `host.docker.internal:9094` for
  containers; a trimmed compose file points its sidecar at the latter and at the host's Postgres.
  It carries no `schema` service: `scripts/databases.sh` applies `target/ddl` to every service's
  database, writing it with `sbt schema` when it is missing, so a schema copy beside the sidecar
  would be read by nothing.
- **An add answers 204.** `POST /<service>/{id}` replies `Done`, which the HTTP module answers with
  no content.

2026-11-01, from the recorded runs (T016, T042) and the rest of the build:

- **The design skill holds on three descriptions** (`runs/`). Different owners became two services by
  ownership, a context map first, every crossing a declared topic with a contract and a schema path;
  orders and stock under one rule became one service "because they must be consistent together"; a
  one-team shop stayed one service with no map. Nothing in the skill needed changing for these runs.
- **A topic crossing is not in the request's trace.** An event carries no trace, so the request's trace
  ends at the entity's write; the publishing consumer starts a trace and the reader continues it. The
  inspect skill joins a topic crossing on the publisher's trace and a route crossing on the request's.
- **`describe_local_service` does not state what a publisher declares for a topic.** R11 assumed it
  did. The contract each side states is read from the topology's `declared` connections through the
  local console (`/api/topology/<service>`); the reading side's also shows under `topicSources`.
- **Off the platform no contract is compared per message.** A reader stating `orders.v2` still read
  `orders.v1` messages locally, so a contract mismatch is caught only by comparing the topologies (and,
  for Scala, by the tests module's `ContextMapSuite`); the inspect skill compares before running.
- **`docs/build/calling-services.md` said a call starts a new trace in the service called.** It does
  not: the call carries `traceparent` and the callee continues it, as `operate/telemetry.md` says and
  the run showed. Corrected.
- **The CLI's templates are formatted as Scala 2.12 where sbt reads them.** `.scalafmt.conf` gained a
  `fileOverride` for `cli/src/main/templates/project/project/**`; the Scala 3 rewrites would have broken
  the rendered build's plugin. The native image needs no new glob: the project templates are under
  `ankka/templates/**`, which it already carries.
- **`docs check` wants a page's description under 240 characters**; the build page's was shortened.


## Measurements

2026-11-01, on the developer's Mac (OrbStack), against this branch's ankka published locally.

| What | Result |
|---|---|
| `sbt scalafmtCheckAll scalafmtSbtCheck` | clean |
| `core/testOnly *ContextMap*` | 25 passed |
| `testkit/testOnly *ContextMap* *ProjectsDocumentationSuite` | 34 passed (the documentation suite alone, after the last skill edit: 27) |
| `cli/testOnly *InitSuite *CliReferenceSuite *ContextMapCommandSuite` | 36 passed |
| `-Dankka.template.tests=project cli/testOnly *ProjectTemplateSuite` | 12 passed |
| `-Dankka.template.tests=scala cli/testOnly ...TemplateSuite` (FR-012, the single-service template unchanged) | passed |
| `just docs` | 110 pages, no problems |
| `just features` | 0 findings in 3183 scenarios and 63 specs |
| SC-002: `ankka init shop --project --services orders,billing,stock` in an empty directory, then `sbt test` | green in 43 s wall clock, nothing edited (under ten minutes); warm caches |

The first `sbt test` on a machine with no cached dependencies takes longer by the download; the
template's CI job measures that on every pull request that touches it.
