# Tasks: A nakka Application Built Outside This Repository

**Input**: Design documents from `/specs/006-external-applications/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: required — the spec's proof (FR-019, SC-007) *is* the feature, and the pure parts get
tests first, as every feature here has.

**Organization**: Phase 2 verifies the five research items before any of the build is changed.
Then US1 (publish), US2 (template), US3 (the chain by hand), US4 (compatibility).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 depend from another build, US2 template, US3 the whole chain, US4 versions

---

## Phase 1: Setup

- [X] T001 Read `project/plugins.sbt`, `build.sbt` lines 1–60 (organization, version, `dockerSettings`, `commonSettings`) and the `publish / skip` lines already on the samples; note every project name in the build (`core sdk runtime http agent testkit controlPlaneApi crd operator controlPlane cli shoppingCart multiAgentPlanner root`) — the list T012 turns into publish/skip decisions.
- [X] T002 [P] Create `nakka.g8/` with `default.properties` (`name=my-service`, `package=com.example.$name;format="camel"$`, `nakka_version=0.1.0-SNAPSHOT` as a placeholder T024 replaces, `description=A nakka service`) and an empty `src/main/g8/` — the skeleton the US2 tasks fill.
- [X] T003 [P] Create `.github/workflows/ci.yml`: on push and pull request, JDK 21, `sbt -Dnakka.cluster.tests=off -Dnakka.template.tests=off scalafmtCheckAll compile test` — the non-Docker-in-Docker subset; a comment names what is not run in CI and why.

---

## Phase 2: Foundational — verify first (research R1, R4, R6, R7)

- [X] T004 Confirm the current `sbt-ci-release` version and its Central Portal requirements (`sonatypeCredentialHost`, the `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`/`PGP_SECRET`/`PGP_PASSPHRASE` secrets, whether `sonatypeProfileName` is still needed) from its README; record the version and the exact settings in `research.md` R1.
  - sbt-ci-release **1.12.1** (Central Portal is the default; no credential-host setting), sbt-buildinfo 0.13.2. Secrets: `PGP_PASSPHRASE`, `PGP_SECRET` (`gpg --armor --export-secret-keys | base64`), `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` (the *user token* parts). Tags must start with `v`; `main` commits publish `-SNAPSHOT`s.
- [X] T005 In a scratch copy of the build (`git stash` or a worktree): add `sbt-ci-release` and remove `ThisBuild / version`; run `sbt 'show version'` untagged (expect `0.0.0+N-sha-SNAPSHOT` or dynver's form from the nearest tag — there are none) and with a local throwaway tag `v0.2.0-test` (expect `0.2.0-test`); then `sbt publishLocal` and list `~/.ivy2/local/com.thinkmorestupidless/` — record which modules published *before* any skips are added, to know exactly what T012 must skip.
  - In a worktree: `ThisBuild / version` removed, plugin added. Untagged/dirty: `0.2.0-probe+0-sha+timestamp-SNAPSHOT`; clean at tag `v0.2.0-probe`: `0.2.0-probe`. `publishLocal` published **eleven** modules — the five platform-side ones (`cli`, `controlplane`, `controlplane-api`, `crd`, `operator`) need `publish / skip`. Probe tag, worktree and `~/.ivy2/local` artifacts removed afterwards.
- [X] T006 Same scratch: generate a throwaway GPG key (`gpg --batch --gen-key`), set `publishTo := Some(Resolver.file("local-release", file("/tmp/nakka-repo")))` under a `-Dnakka.release.local` switch, run `sbt publishSigned`; confirm six artifacts with `.asc`, `-sources.jar`, `-javadoc.jar` and POMs carrying licence/scm/developers. Record in R1 what was missing the first time (Central rejects a POM without them).
  - Throwaway ed25519 key; `publishTo` switched by `-Dnakka.release.local=<dir>` to a Maven-layout file resolver; `core/publishSigned` produced jar, `-sources`, `-javadoc`, POM, each with `.asc` and checksums; the POM carries homepage, Apache-2.0 licence, developers and scm (the latter filled by the plugin from the git remote). Nothing was missing once `homepage`/`licenses`/`developers` were in `ThisBuild`.
- [X] T007 `sbt new` forwarding: from `/tmp`, `sbt new file:///$PWD/template --name=probe` with a one-file placeholder template (`src/main/g8/README.md` containing `$name$`) — confirm expansion; then push nothing, and instead confirm `--directory` forwarding with a public template that has subdirectories, or by reading `sbt`'s `new` command source for its argument pass-through. Record in R4 whether `sbt new <git url> --directory=template` works or the public form must be `g8` directly.
  - **Corrected the design**: sbt's Giter8 resolver only matches `owner/repo.g8`, `file://…\.g8`, `….g8.git` (bytecode of sbt-giter8-resolver 0.18.0). `--directory` on a plain git URL never reaches Giter8. The template is `nakka.g8/`; local form `sbt new file:///…/nakka.g8`; public form `sbt new thinkmorestupidless/nakka.g8` via a subtree-pushed repository. Also: `default.properties` must be in `src/main/g8/`; spaces in `--name` must be quoted inside the sbt command string, and an empty cwd needs `sbt --allow-empty`.
- [X] T008 Giter8 name formatting: expand the placeholder with `--name="My Orders"` and `--name=orders-2` and read what `$name;format="norm"$` produces (expect `my-orders`, `orders-2`); confirm both satisfy `[a-z]([-a-z0-9]{0,61}[a-z0-9])?`. Record in R4 whether the template needs its own validation (FR-015) beyond `norm`.
  - `norm`: `My Orders`→`my-orders`, `orders-2`→`orders-2`, `Orders`→`orders`; package `format="word,lower"`→`myorders`. Valid service names in every case tried; `nakka init` validates before running and the generated `build.sbt` `require`s, for what `norm` cannot fix.
- [X] T009 The `schema` task by hand: in a scratch sbt project depending on `nakka-runtime` from `~/.ivy2/local` (T005's publish), write the task from [data-model.md](./data-model.md), run it, `docker compose up` with `./target/ddl` mounted, `psql -c '\dt'` — confirm the journal, snapshot, durable-state, projection and timer tables exist. Record in R6 the working task body.
  - Working body recorded in research R6: find the `nakka-runtime_*.jar` on `Compile / dependencyClasspath`, `IO.unzip` the `nakka/ddl/*.sql` entries into `target/ddl`, flatten. Postgres 17 from that directory: `durable_state event_journal nakka_timers projection_management projection_offset_store projection_timestamp_offset_store snapshot`.
- [X] T010 Time a cold external build: in T009's scratch project, `sbt test` with one `NakkaTestKit` test, from a cold sbt start — record the wall-clock in R7 and pick `TemplateSuite`'s `munitTimeout` from it.
  - Cold external `sbt test` with one `NakkaTestKit` case, artifacts from `~/.ivy2/local`: **10s** (dependencies already in the coursier cache; the first-ever resolve on a clean machine is dominated by downloads). `TemplateSuite` gets a 10-minute timeout.
- [X] T011 Update `research.md` (R1, R4, R6, R7 — "Verified" or corrected) and `data-model.md` if any shape changed; `plan.md`'s design notes if T007 changed the public form.
  - research R4/R6/R7 updated; data-model and plan paths renamed to `nakka.g8/`.

**Checkpoint**: every mechanism this feature relies on has been seen working outside this build.

---

## Phase 3: User Story 1 — depend on nakka from another build (P1)

**Goal**: six artifacts, one version from the tag, nothing else publishable; a release workflow
proven locally.

**Independent test**: Tier 2 of [quickstart.md](./quickstart.md); T006 repeated against the real build.

- [X] T012 [US1] Edit `build.sbt`: delete `ThisBuild / version`; add `ThisBuild / licenses`, `homepage`, `scmInfo`, `developers` (T006's findings); add `publish / skip := true` to `controlPlaneApi`, `crd`, `operator`, `controlPlane`, `cli` and the root project (the samples already have it); keep `versionScheme`.
- [X] T013 [US1] Edit `project/plugins.sbt`: add `sbt-ci-release` at T004's version and `sbt-buildinfo`. Edit `build.sbt`: `core` enables `BuildInfoPlugin` with `buildInfoKeys := Seq(version)`, `buildInfoPackage := "nakka.core"`, `buildInfoObject := "BuildInfo"`; `-Dnakka.release.local=<dir>` switches `publishTo` to a file resolver (T006's shape) for the local proof.
- [X] T014 [US1] `sbt publishLocal` from a clean checkout; `ls ~/.ivy2/local/com.thinkmorestupidless/` is exactly the six (SC-004) — record the listing in this task. `sbt 'show version'` and `sbt compile` warning-free.
  - `~/.ivy2/local/com.thinkmorestupidless/`: `nakka-agent_3 nakka-core_3 nakka-http_3 nakka-runtime_3 nakka-sdk_3 nakka-testkit_3` — exactly six. Untagged, dynver says `0.0.0+12-e7365ae9+<timestamp>-SNAPSHOT`: the first real version arrives with the first `v*` tag.
- [X] T015 [P] [US1] Create `.github/workflows/release.yml`: on tag `v*`, JDK 21, `sbt ci-release` with the four secrets; a comment naming the one-time namespace claim as the step this workflow waits on.
  - Two jobs: `publish` (`sbt templateVersion ci-release` on main and tags) and `template` (tags only, after publish: `git subtree split --prefix nakka.g8` pushed to `thinkmorestupidless/nakka.g8` with a `TEMPLATE_REPO_TOKEN` secret) — the second exists because sbt's Giter8 resolver only takes `owner/repo.g8` (T007).
- [X] T016 [US1] Repeat T006 against the real build: `sbt -Dnakka.release.local=/tmp/nakka-repo publishSigned` with the throwaway key — six signed artifacts; then confirm `sbt 'show version'` on an uncommitted change ends in `-SNAPSHOT` and note that `ci-release` publishes snapshots to the snapshot repository and only tags to releases (SC-006).
  - Real build, throwaway ed25519 key: six modules to `/tmp/nakka-repo`, each with jar, `-sources`, `-javadoc`, POM and four `.asc` signatures. Removed afterwards.
- [X] T017 [US1] `CLAUDE.md`: a "Publishing" section — the six, the skips, dynver (never set the version), `publishLocal` as the loop, `ci-release` on tags, the namespace claim; the trap "`ThisBuild / version` set anywhere silently overrides the tag".

---

## Phase 4: User Story 4 — versions and compatibility (P2, but the template writes `runtime`, so before US2)

**Goal**: one version in code; a declared runtime version checked at projection.

**Independent test**: Tier 1 of the quickstart.

- [X] T018 [P] [US4] Test `controlplane-api/src/test/scala/nakka/controlplane/api/CompatibilitySuite.scala`: `Version.parse` of `0.2.0`, `0.2.0+3-abc1234-SNAPSHOT`, `1.10.3`, and refusal of `x`, `0.2`, ``; `supports(0.3.1, 0.3.0)`, `supports(0.3.1, 0.2.9)` true; `supports(0.3.1, 0.1.0)`, `supports(0.3.1, 0.4.0)`, `supports(1.0.0, 0.9.0)` false; `describe(0.3.1) == "runtimes 0.2.x–0.3.x (platform 0.3.1)"`; `describe(0.0.5)` handles minor 0 (`0.0.x` only).
- [X] T019 [US4] Create `controlplane-api/src/main/scala/nakka/controlplane/api/Compatibility.scala` per [data-model.md](./data-model.md): `Version(major, minor, patch)`, `Version.parse: Either[String, Version]`, `supports`, `describe`; doc comment stating the rule and that it is deliberately coarse.
- [X] T020 [P] [US4] Tests in `controlplane-api/src/test/scala/nakka/controlplane/api/DescriptorSuite.scala`: `service.runtime` round-trips and is absent when `None`; `"runtime": "nope"` is a problem naming the format; absent is no problem.
- [X] T021 [US4] Edit `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala`: `ServiceSpec.runtime: Option[String] = None` with the doc comment from data-model; `problems` adds a parse failure.
- [X] T022 [P] [US4] Tests in `controlplane/src/test/scala/nakka/controlplane/ServiceProjectionSuite.scala`: with a platform version passed in (`DeployConfig.platformVersion`, default `BuildInfo.version`): a supported declared runtime projects; an unsupported one is `Left` naming the declared version and `Compatibility.describe`; absent projects. And in `ControlPlaneHttpSuite`: apply a descriptor with `"runtime": "9.0.0"` → 200 (an apply records intent), then `services get` within 30s shows `Unavailable` and a `detail` containing `9.0.0` and the supported range; re-apply without `runtime` → the service proceeds.
  - The in-process case lives in `ProjectorSuite` (case 12), which already drives the real projector against a fake resource client: apply `9.0.0` → `Unavailable`, confirmed, detail names both, and the resource in the cluster is unchanged; apply `0.2.0` → proceeds. A new `ClusterView.Refused` carries it — the old `Unreachable` prefix ("could not reach the cluster") would have misdescribed a decision.
- [X] T023 [US4] Edit `controlplane/src/main/scala/nakka/controlplane/deploy/DeployConfig.scala` (`platformVersion: String = nakka.core.BuildInfo.version`) and `ServiceProjection.scala` (the check → `Left`); confirm the projector's existing `Unreachable(problems)` path produces `Unavailable` + detail with no resource written (feature 001's mechanism, unchanged). Edit `cli/src/main/scala/nakka/cli/Main.scala`: `nakka version` prints `BuildInfo.version`. Edit `modules/runtime/src/main/scala/nakka/runtime/Nakka.scala`: log the version at start; serve it at `/nakka/version` on the management routes (`ExtensionsReadiness` is where the management registration lives — add a route beside it).
- [X] T024 [US4] `nakka.g8/src/main/g8/default.properties`: `nakka_version` becomes the build's version — an sbt task `templateVersion` in `build.sbt` that writes the file's `nakka_version=` line from `version.value`, run by `publishLocal` and `ci-release` (`publishLocal := (publishLocal dependsOn templateVersion).value`), so the template can never name a version that was not published alongside it.
  - Rewrites only a release version: a dirty-tree snapshot changes on every publish and would churn the file. `nakka init` and `TemplateSuite` pass `--nakka_version` explicitly; the checked-in default (`0.0.0` until the first tag) is what `sbt new thinkmorestupidless/nakka.g8` gets — the last release.

---

## Phase 5: User Story 2 — start a service from a template (P1)

**Goal**: `nakka.g8/` expands into a project that tests, runs, and builds an image with no edit.

**Independent test**: `TemplateSuite`, then Tier 5's first half by hand.

- [X] T025 [P] [US2] `nakka.g8/src/main/g8/build.sbt`: `scalaVersion` 3.9.0; `val nakkaVersion = "$nakka_version$"`; the five dependencies from [contracts/build-contract.md](./contracts/build-contract.md) (`nakka-agent` commented, with the line to uncomment); munit; `JavaAppPackaging`, `DockerPlugin`, `dockerBaseImage := "eclipse-temurin:21-jre"`, `dockerUpdateLatest := true`, `Docker / packageName := "$name;format="norm"$"`; `Test / fork := true`; the `schema` task from T009; a `require` on the name against the service-name regex with the rule as the message (T008's answer decides whether this is needed). `project/build.properties` (sbt 1.12.x), `project/plugins.sbt` (sbt-native-packager).
  - A top-level `require(...)` is not an sbt DSL entry (`required: sbt.internal.DslEntry`); the name check is a `val serviceNameChecked` that `sys.error`s. The Giter8 escapes: every `$` in interpolations is `\$`.
- [X] T026 [P] [US2] `nakka.g8/src/main/g8/src/main/scala/$package$/domain/Item.scala` (`Item(name, count)`, events `ItemAdded`, the fold), `application/ItemEntity.scala` (event sourced entity: `add-item` command, `get-item` query; codecs in the companion), `application/ItemRows.scala` (view: `ItemRow(id, name, count)`, listing), `api/ItemEndpoint.scala` (`POST /items/{id}`, `GET /items/{id}`, `GET /items`; `acl = Acl.AllowAll` with the feature-005 sentence), `Main.scala` (explicit `register` list, `HttpServer.of(clients => ItemEndpoint(clients.componentClient, clients.viewClient))`, shutdown hook). Modelled on `samples/shopping-cart`, every doc comment rewritten for a reader who has never seen this repository.
  - Plus a `JsonValueCodec[Vector[ItemRow]]` for the listing — the first compile of the expansion found it missing.
- [X] T027 [P] [US2] `nakka.g8/src/main/g8/src/main/resources/application.conf` (a comment naming what an application configures here and pointing at `nakka-runtime`'s `reference.conf`; `nakka.db` keys with the compose file's values) and `logback.xml` (from the sample).
- [X] T028 [P] [US2] `nakka.g8/src/main/g8/src/test/scala/$package$/ItemEntitySuite.scala` (`EventSourcedTestKit`: add then get; the query cannot persist — one assertion per idea), `ItemHttpSuite.scala` (`NakkaTestKit` + `HttpServer.at(…, 0)`: POST then GET by id, GET listing eventually), `ItemIntegrationSuite.scala` (`NakkaTestKit`: write, `restartService()`, read — the durability proof).
  - Expanded by hand and run against a fresh `publishLocal`: **10/10** — after a *publishing* fix: the pekko-http family mismatch feature 004 solved with `dependencyOverrides` recurred in the external build, because an override never reaches a POM. `nakka-runtime` now declares the family (minus testkit) as direct dependencies, and the consumer's eviction picks 1.4.0.
- [X] T029 [P] [US2] `nakka.g8/src/main/g8/docker-compose.yml` (postgres:17-alpine, `nakka/nakka/nakka`, `./target/ddl:/docker-entrypoint-initdb.d:ro`, healthcheck), `service.json` per [contracts/template.md](./contracts/template.md), `.gitignore` (`target/`), `README.md` with exactly the command list in the contract, in order, plus an "Upgrading nakka" note (both version sites).
  - `sbt schema` → three files; `Docker/publishLocal` → `orders:0.1.0-SNAPSHOT` and `orders:latest`; the compose path proven on a scratch port (this machine's own `nakka-postgres` holds 5432): seven tables from `target/ddl`, `sbt run` with `NAKKA_DB_PORT=15432`, POST 204, GET, one row in *that* journal. The README's curls all executed.
- [X] T030 [US2] `cli/src/main/scala/nakka/cli/Init.scala` and `Main.scala`: `nakka init <name> [--template <ref>] [--package <pkg>] [--dir <path>]` per the contract: validates the name with `ServiceDescriptor.ValidName`'s rule (same message as apply), refuses a non-empty target, checks `sbt` on `PATH` (`ProcessBuilder("sbt", "--version")` or a PATH scan), runs `sbt new <ref> --name=<name> [--package=<pkg>]` with inherited IO in `<dir>`, prints the path and the first README commands on success. Default `<ref>` = `thinkmorestupidless/nakka.g8` (or T007's answer).
  - `Init.command`/`newCommand` build the `sbt --allow-empty -batch "new <ref> --name=… --nakka_version=<BuildInfo.version> [--package=…]"` invocation as a value; `Init.problems` reuses `ServiceDescriptor.nameProblems` (new, the one rule) and refuses a non-empty target; `sbtOnPath` scans PATH. The default template is `thinkmorestupidless/nakka.g8`.
- [X] T031 [P] [US2] Tests in `cli/src/test/scala/nakka/cli/InitSuite.scala`: an invalid name exits 1 with the rule; a non-empty directory exits 1 naming it; `--template` and `--package` reach the argument vector (`Init.command(...)` is a pure function returning the `sbt` argument list — test that, not the process).
- [X] T032 [US2] `cli/src/test/scala/nakka/cli/TemplateSuite.scala` per research R7: `munitIgnore` on `-Dnakka.template.tests=off` or no `sbt` on `PATH`; `beforeAll` expands via `Init.run(name = "probe", template = file://<repoRoot>/nakka.g8, dir = tmp)`; cases: (1) `grep`-equivalent walk asserts no file contains `thinkmorestupidless/nakka` except the README's git URL, nor `modules/`, nor `my-service`/`MyService`/`myservice` (FR-014, SC-003); (2) `service.json` parses as a `ServiceDescriptor` with no problems and `runtime == BuildInfo.version`; (3) `sbt -batch test` in the expansion exits 0 (subprocess, timeout from T010); (4) `sbt -batch Docker/publishLocal` exits 0 and `docker image inspect probe:latest` succeeds. `build.sbt`: `cli / Test / test` depends on `publishLocal` (root) unless the switch is off, the `sampleImageForClusterTests` shape.
  - Publishes locally through a `templateArtifacts` task hooked on both `Test / test` and `Test / testOnly` — the first run found `testOnly` bypasses a dependency on `test`.
- [X] T033 [US2] Run `sbt 'cli/testOnly nakka.cli.TemplateSuite'`; fix the template until green; record the wall-clock.
  - 5/5 from an empty `~/.ivy2/local`: publish, expand through `nakka init` against `file://`, the leak checks, then the expansion's own `sbt test` (10/10) and `Docker/publishLocal` (`probe:latest`) — **29s** for the suite itself. Two plumbing fixes on the way: `testOnly` bypasses a `test` dependency, and a task dependency on `root / publishLocal` publishes nothing (aggregation is command-line only).

---

## Phase 6: User Story 3 — the whole chain, from outside (P1)

**Goal**: an application not in this build goes through test → image → apply → Ready → expose →
hostname on the kind cluster.

- [X] T034 [US3] Perform Tier 5 of [quickstart.md](./quickstart.md) in full on `kind-nakka` (feature 005's deployment is there): `nakka init orders --template file://…` in a temp directory, `sbt test`, `sbt schema && docker compose up -d && sbt run` with the two curls, `sbt Docker/publishLocal`, `kind load`, `apply`, `Ready`, `expose`, `curl --cacert` by hostname, `restart`, the read survives. Record every output in this task.
  - Done on `kind-nakka` with the staged CLI over TLS (no port-forward): `nakka init orders --template file://…` in an empty temp directory; `sbt test` 10/10; `Docker/publishLocal` → `orders:latest`; `kind load`; `apply` (first attempt 500 — a transient right after the control plane's rollout, the re-apply succeeded); `Ready 1/1`, `database provisioned`; `expose` → `https://orders-checkout.127.0.0.1.sslip.io:8443`; POST 204 and GET by hostname with `--cacert`; `restart` → `Ready` and the same item read back. Two platform findings first: Docker refuses `+` in a tag (dynver snapshots) and `JavaAppPackaging` on `cli` enabled an unwanted CLI image — both fixed in `build.sbt`; and `/nakka/version` was 404 because the route provider key belongs under `pekko.management.http.routes` — fixed and pinned by a `ClusterConfigSuite` case (the deployed image predates the fix; the route is proven by the suite and the next deploy).
- [X] T035 [US3] The version negative by hand: `service.json` with `"runtime": "9.0.0"`, `apply`, `services get` → `Unavailable` with both versions; restore, `apply`, `Ready` again. Record.
  - `runtime: 9.0.0` → generation 4, `Unavailable`, detail `runtime 9.0.0 is outside the platform's supported range: runtimes 0.0.x (platform 0.0.0)`; the cluster's resource stayed at generation 3 (nothing written); restored → `Ready`, no detail.
- [X] T036 [US3] `README.md`: a "Your first service" section that is T034 written for a reader (every command executed), placed before "Control plane and CLI"; the dependency block from the build contract; the compatibility rule in one paragraph; "Not implemented" gains: no registry, no verification of the declared runtime against the running pod, no compatibility matrix, no `nakka.g8` mirror (use the git URL), CLI distributed by `sbt cli/stage` only.
- [X] T037 [P] [US3] `CLAUDE.md`: the template lives in `nakka.g8/` and is tested by `TemplateSuite`; `nakka init` shells out and carries no template; `Compatibility` in `controlplane-api` and why; the DDL constraint (FR-011: additive within a supported range); traps from Phase 2.

---

## Phase 7: Polish

- [X] T038 `sbt scalafmtAll scalafmtSbt`; `sbt clean compile Test/compile` warning-free.
- [X] T039 Seams: `cli` → `controlPlaneApi` only (no Giter8 library; `Init` uses `ProcessBuilder`); `crd` depends on nothing; `core`'s only addition is the generated `BuildInfo`; the samples still `dependsOn` project references.
  - `cli` → `controlPlaneApi` only (`Init` is `ProcessBuilder`; no Giter8 library anywhere); `crd` has no `dependsOn`; the samples still `dependsOn(sdk, runtime, http, testkit % Test)`; `core`'s source tree is unchanged — `BuildInfo` is generated into `target/`.
- [X] T040 Full `sbt test` (including `TemplateSuite`), then the reviewer's checklist in [quickstart.md](./quickstart.md).
  - Full `sbt test` 2026-09-18 19:48 → 2026-09-19 05:38: **69 suites, 67 green** including `TemplateSuite`
    (27.8s) and `OperatorClusterSuite` 36/36; the two failures were `ExposureClusterSuite` case 7 and
    `MultiNodeClusterSuite` case 7, and the run took 9h50m because the laptop slept — the log's
    timestamps jump 22:54 → 04:18 and `pmset -g log` shows the sleep cycles. Both suites rerun awake
    under `caffeinate`: 9/9 in 6.9m and 8/8 in 18m. The crash case's retry now tolerates a victim pod
    that no longer exists (it NPE'd on the churned cluster after the wake). Recorded in CLAUDE.md.
  - Reviewer's checklist: the expansion references nothing of this repository and no stub name;
    `ThisBuild / version` is gone; every template README command was executed in T034; README's
    "Not implemented" names the registry, the trusted declaration, no matrix, the staged CLI and the
    unpublished `nakka.g8` mirror; the samples still build from project references.

---

## Dependencies

- Phase 2 before anything in `build.sbt` (T012/T013 depend on T004–T006's findings; the template on T007–T009).
- US1 (T012–T017) before US4's T023 (`BuildInfo` exists) and before US2's `TemplateSuite` (artifacts to resolve).
- US4 (T018–T024) before US2's `service.json`/`build.sbt` content is final (`runtime` field, `nakka_version`).
- US2 before US3; US3's docs (T036–T037) can start with US2.

## Parallel opportunities

- T002/T003; T018/T020/T022 (tests) before T019/T021/T023; T025–T029 (template files) together; T031 with T030; T036/T037 together.

## Implementation strategy

MVP = US1 + US4 + US2 through T033: artifacts published locally, the compatibility rule live, and
`TemplateSuite` green — a build outside this one resolves nakka and its tests pass. US3 is the
same thing done by hand on the cluster and written up, which is the deliverable.
