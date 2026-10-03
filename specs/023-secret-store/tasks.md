# Tasks: Secret Store — Values a Service Holds Without Ever Journaling Them

**Input**: Design documents from `/specs/023-secret-store/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
code into a test before the code that relies on it. The scenarios are in `features/secrets/`;
where a task says "case", it means a `test(...)` in the named suite (or its equivalent in the
SDK's test runner), named for the scenario it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a service secret is stored and never journaled), US2 (a process-hosted or
  wasm service uses the store), US3 (a project secret is set without cluster credentials), US4
  (the platform's variables are declared once)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK`/`SDKT` = `modules/sdk/src/{main,test}/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `HTTP` =
`modules/http/src/main/scala/…/http`; `AGENT` = `modules/agent/src/main/scala/…/agent`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `CLI`/`CLIT` =
`cli/src/{main,test}/scala/…/cli`; `DDL` = `kustomization/components/postgres/ddl`; `PY` =
`sdks/python`; `TS` = `sdks/typescript`; `RS` = `sdks/rust`; `DOCS` = `docs`; `SKILL` =
`tools/docs/skill`. "R*n*" is a section of `research.md`; "V*n*" an item of its *Verify first*
list; a contract is named by its file under `contracts/`.

The branch `023-secret-store` exists, in the worktree `.claude/worktrees/023-secret-store`.
`main`'s own working tree carries changes that are not this feature's; work in the worktree.
Every `sbt` command below takes `-Dankka.cluster.tests=off` unless the task names a k3s suite.

---

## Phase 1: Setup — what every later phase reads

**Purpose**: the rules fixture all four SDKs test against, and a CI filter for the two new
top-level paths.

- [X] T001 Claim `features/**` and `GLOSSARY.md` in `.github/workflows/ci.yml`'s `changes` job: add them to the filter of the job that should run when they change (the `docs` job, which is the one that can check prose), or to `unchecked` with the reason. Confirm with `git add -N features GLOSSARY.md && python3 .github/ci-coverage.py` that every tracked file is claimed and every pattern matches; without the filter that command fails, which is the check.
- [X] T002 Create `protocol/fixtures/secrets/rules.json` per R7 and `contracts/scala-api.md` "The rules": an array `names` of `{"name", "accepted", "why"}` covering a plain name, `provider/acme`, each of `.`, `_`, `-`, a 253-character name (accepted), a 254-character name, the empty name, a space, a colon, a tab, a non-ASCII letter (refused); and an array `values` of `{"bytes", "accepted", "why"}` giving a UTF-8 byte length and a repeat unit so a reader can build the value: 1 byte, 65,536 bytes (accepted), 0 bytes, 65,537 bytes, and a 2-byte character repeated to 65,538 bytes (refused: the limit is bytes, not characters).
- [X] T003 Copy the fixture into the three SDK copies with their own scripts (`cd sdks/python && uv run python scripts/proto.py`; `cd sdks/typescript && npm run proto`; `sdks/rust/scripts/proto.sh`) and confirm: `sbt 'core/testOnly *EncodingFixturesSuite'` passes with the new subdirectory present; the three `diff -r protocol/fixtures …` lines of `.github/workflows/ci.yml` report nothing.

**Checkpoint**: the fixture is in `protocol/fixtures/secrets/` and in all three SDK copies; nothing else has changed and every existing suite is green.

---

## Phase 2: Foundational — the secrets table

**Purpose**: the one schema change. It blocks US1 and every conformance case of US2.

**⚠️ CRITICAL**: a DDL file that exists and is named in six of the seven lists passes every offline suite and fails on a cluster. T005 names all seven.

- [X] T004 Create `DDL/40-secrets-postgres.sql` per `data-model.md`: `CREATE TABLE IF NOT EXISTS ankka_secrets (name TEXT PRIMARY KEY, ciphertext BYTEA NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());`, with a header comment saying the table is the secret store's alone and that no projection reads it.
- [X] T005 Name the file in all seven lists of R5: `TK/AnkkaTestKit.scala` (`DdlResources`, as `/docker-entrypoint-initdb.d/40-secrets.sql`); `OP/CnpgRendering.scala` (`SchemaFiles`); `OPT/SchemaResourceSuite.scala`; `OPT/CnpgRenderingSuite.scala` (the ConfigMap now has four keys); `SCT/SidecarClusterSuite.scala`; `kustomization/components/postgres/kustomization.yaml` (`configMapGenerator` files); `kustomization/components/postgres/cluster.yaml` (`postInitApplicationSQLRefs`, after `30` and before `99-grants`).
- [X] T006 Run `sbt 'operator/testOnly *SchemaResourceSuite *CnpgRenderingSuite' 'controlPlane/testOnly *RemoteOverlaySuite'` and `kubectl kustomize kustomization/overlays/local | grep -c 40-secrets` (expect the file in the generated ConfigMap and in the cluster's refs). Then start a test kit (`sbt 'testkit/testOnly *TimerSuite'`) to confirm Postgres still initialises with four files.

**Checkpoint**: every database a test kit, compose, the operator or the control plane's own cluster creates has `ankka_secrets`. Nothing reads or writes it yet.

---

## Phase 3: User Story 4 — the platform's variables are declared once (Priority: P2, first by dependency)

**Goal**: one declaration in `core`, read by `controlplane-api` and the sidecar and compiled into the operator; the three hand-kept lists are gone.

**Why first**: US2 routes `ANKKA_SECRET_KEY` through this declaration in three places. Done first, it is added once.

**Independent Test**: `sbt 'core/testOnly *PlatformVariablesSuite' 'controlPlaneApi/testOnly *DescriptorSuite *HostingSuite' 'operator/testOnly *ProcessHostingRenderingSuite *WasmHostingRenderingSuite' 'sidecar/testOnly *WasmHostSuite' 'controlPlane/testOnly *PlatformDeclarationSuite'` — all green, with the last one shown red once by pasting a list back into the operator.

### Tests first

- [X] T007 [US4] Characterise today's behaviour before anything moves: run `DescriptorSuite`, `HostingSuite` (`APIT`), `ProcessHostingRenderingSuite`, `WasmHostingRenderingSuite` (`OPT`) and `WasmHostSuite` (`SCT`) and record that they pass. Add the two cases today's suites lack, so the move is held: in `SCT/WasmHostSuite.scala`, that `config` answers `ANKKA_KAFKA_BOOTSTRAP_SERVERS` (it is shared, not withheld); in `OPT/ProcessHostingRenderingSuite.scala`, that an `ANKKA_KAFKA_*` variable is on both containers. Both pass against the unchanged code.
- [X] T008 [P] [US4] Write `CORET/PlatformVariablesSuite.scala` per `contracts/operator.md`: each part holds exactly the members R12's table lists (fifteen exact platform-only names; three runtime-only prefixes and `ANKKA_SECRET_KEY`; the shared prefix; five runtime-read prefixes and two names); `platformOnly` is an exact match (`ANKKA_CLUSTER_MODE_X` is not platform-only); `runtimeOnly("ANKKA_DB_HOST")`, `runtimeOnly("ANKKA_SECRET_KEY")`, not `runtimeOnly("ANKKA_SECRET_KEYS")`; `withheldFromModule` is true for a member of each of the three parts it unions and false for `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and for `MY_SETTING`. It does not compile yet.

### Implementation

- [X] T009 [US4] Create `CORE/PlatformVariables.scala`: a `private[ankka] object` with **no import**, holding `SecretKey`, `HttpPort`, `PlatformOnly`, `RuntimeOnlyPrefixes`, `RuntimeOnlyNames`, `SharedPrefixes`, `RuntimeReadPrefixes`, `RuntimeReadNames` and the four functions of `contracts/operator.md`. A header comment says the operator compiles this same file and that an import of anything outside the standard library breaks its build, on purpose. T008 passes.
- [X] T010 [US4] In `build.sbt`, add that one file to the operator's sources: `Compile / unmanagedSources += (core / Compile / scalaSource).value / "com/thinkmorestupidless/ankka/core/PlatformVariables.scala"`, with a comment giving R12's reason; `dependsOn(crd, testPki % Test)` stays. Confirm V4: `sbt operator/compile controlPlane/Test/compile sidecar/Test/compile` is warning-free with the class in two jars on those test classpaths. If it is not, stop and record what was seen in `research.md` R12 before choosing another route.
- [X] T011 [US4] In `API/descriptors.scala`, delete `PortEnvVar`, `PlatformEnvVars`, `WasmEnvVars`, `SidecarEnvVars`, `SidecarEnvPrefixes` and `SharedEnvPrefixes` from `ServiceSpec`; `ServiceSpec.problems` asks `PlatformVariables` and keeps both messages word for word (the port's "conflicts with the service port; declare the port instead", and "is set by the platform and cannot be declared"). Point `APIT/DescriptorSuite.scala` and `APIT/HostingSuite.scala` at the declaration where they named the deleted constants. Add a `DescriptorSuite` case for `platform-settings.feature` "a descriptor may not give a variable the platform alone sets, however it gives it": `ANKKA_HTTP_PORT` as a value and as a `secretKeyRef` are both refused, naming the variable.
- [X] T012 [US4] In `OP/Rendering.scala`, delete `SidecarEnvPrefixes`, `SharedEnvPrefixes` and the private `PortEnvVar`; `containersFor` partitions with `PlatformVariables.runtimeOnly` and `PlatformVariables.shared`; fix the comment at the split that says the list is duplicated. Search `OP` for any other literal of a platform variable's name and replace it with the declaration's constant.
- [X] T013 [US4] In `SC/wasm/HostImports.scala`, delete `ReservedPrefixes`, `ReservedNames` and `reserved`; `lookup` uses `PlatformVariables.withheldFromModule`; fix the class comment that says the list is duplicated. Update `SCT/WasmHostSuite.scala` for the two changes R12 names: `ANKKA_NAMESPACE_PREFIX` and `ANKKA_SECRET_KEY` are answered absent.

### Tests that hold the declaration

- [X] T014 [P] [US4] In `OPT/ProcessHostingRenderingSuite.scala`, a case that **iterates** `PlatformVariables.RuntimeOnlyPrefixes` (each with a made-up suffix) and `RuntimeOnlyNames`: a process-hosted service rendered with that variable has it on the platform's container (index 0) and not on `<service>-app`; and iterates `SharedPrefixes`: on both. Holds `platform-settings.feature` "a platform setting a descriptor gives is kept from the process" and "a variable newly made a platform setting…".
- [X] T015 [P] [US4] In `SCT/WasmHostSuite.scala`, a case that iterates every member of the three withheld parts with the environment holding a value for each: `config` answers absent for every one. Holds "a module that asks for a platform setting is told that it is not set" and "every platform setting kept from a process is kept from a module".
- [X] T016 [US4] Write `CPT/PlatformDeclarationSuite.scala`: walk the repository's Scala sources from the working directory; fail if more than one file is named `PlatformVariables.scala`; fail if any main source under `operator/`, `sidecar/` or `controlplane-api/` declares a `Vector(` or `Set(` whose elements are **bare string literals** beginning `ANKKA_` or `ANTHROPIC_` (a collection of `name -> value` pairs is what the operator *sets*, not a list of which variables are the platform's, and is allowed: `ZeroTrust.Database.Environment` in `OP/ZeroTrust.scala` is one); and assert `build.sbt` names the file in the operator's `unmanagedSources`. Run it green on the tree as it is first, so a false positive is seen before the break. **Show it failing**: paste `val SidecarEnvPrefixes = Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_")` into `OP/Rendering.scala`, see red, remove it, and re-grep that it is gone.

**Checkpoint**: one declaration, three readers, no list anywhere else. The only behaviour that changed is that a module is no longer answered `ANKKA_NAMESPACE_PREFIX`, and `ANKKA_SECRET_KEY` is routed and withheld though nothing sets it yet.

---

## Phase 4: User Story 1 — a service secret is stored and never journaled (Priority: P1) 🎯 MVP

**Goal**: a service written in Scala keeps, reads and removes service secrets through a `SecretStore`; the database holds them only encrypted and only in `ankka_secrets`.

**Independent Test**: `sbt 'sdk/testOnly *SecretRulesSuite *SecretStoreAvailabilitySuite' 'runtime/testOnly *SecretCipherSuite' 'testkit/testOnly *SecretStoreSuite'` — one case per scenario of `features/secrets/secret-store.feature`.

### Tests first

- [X] T017 [P] [US1] Write `SDKT/SecretRulesSuite.scala`: read `protocol/fixtures/secrets/rules.json` (resolved from the repository root, as `EncodingFixturesSuite` finds its files) and assert `SecretRules.nameProblem` and `SecretRules.valueProblem` give every row's verdict, and that a refusal's message names the rule and, for a value, the limit `65536`.
- [X] T018 [P] [US1] Write `RTT/SecretCipherSuite.scala` per R3 and `data-model.md` "Stored form": a round trip under a name; the stored bytes begin `0x01` and are `1 + 12 + n + 16` long; two encryptions of one value differ (the nonce); decrypting under another key fails with the wrong-key error; decrypting under the right key and **another name** fails (the name is bound); flipping one byte fails; an unknown version byte fails naming the version; a key that is not base64, or is base64 of 16 bytes, is refused naming `ANKKA_SECRET_KEY` and "32 bytes".
- [X] T019 [P] [US1] Write `SDKT/SecretStoreAvailabilitySuite.scala` per `contracts/scala-api.md` "What a test must show": with `compileErrors`, `context.secrets` does not compile against an `EventSourcedEntityContext`, a `KeyValueEntityContext` or a `ViewComponentContext`, and the same expression **does** compile against a `ConsumerContext`, a `WorkflowContext` and a `TimedActionContext`. Holds "an entity or a view is given no secret store".
- [X] T020 [US1] Write `TKT/SecretStoreSuite.scala` with `LogCapturing`, on `AnkkaTestKit`, with a small service in the test sources (an endpoint on `HttpServer.at("127.0.0.1", 0)` with put/get/delete routes, a consumer and a workflow whose step reads a secret, one event sourced entity and one view so the dump has journal and view rows to search). One case per scenario of `secret-store.feature`, named for it: kept by one component and read by another; **the dump** (read every row of every table as text through `jdbcUrl`, enumerating tables from `information_schema`, and assert the plaintext occurs nowhere; then exactly one `ankka_secrets` row for the name, whose bytes differ from the value's); still read after `restartService()`; kept on one instance and read on a peer (`startPeer`); never kept is `None` with no failure; kept again replaces and there is one row; removed is `None` and the row is gone; a service with no key starts (`start(secretKey = None)` and `awaitReady`); with no key `put` and `get` fail naming `ANKKA_SECRET_KEY` (and `delete` works, R4); another key (`restartService(secretKey = other)`) fails saying the key is wrong and does **not** return `None`; a value over the limit is refused naming the limit and nothing is stored; an empty value is refused; `provider/acme` round-trips; a name with a space, with a colon, the empty name and a 254-character name are refused naming the rule; a malformed key stops the service starting. It does not compile yet.

### Implementation

- [X] T021 [US1] Create `SDK/SecretStore.scala`: the `SecretStore` trait of `contracts/scala-api.md` with its scaladoc, and `object SecretRules` (`MaxNameLength = 253`, `MaxValueBytes = 65536`, `nameProblem`, `valueProblem`, and `check(name)` / `check(name, value)` that throw `CommandError(…, ErrorCode.BadRequest)`). T017 passes.
- [X] T022 [US1] Create `RT/SecretCipher.scala`: `SecretKey.parse(text): Either[String, SecretKey]` (standard base64 of exactly 32 bytes; the `Left` names the variable and the form), and `SecretCipher.encrypt(key, name, value): Array[Byte]` / `decrypt(key, name, bytes): String` with AES-256-GCM, a 12-byte `SecureRandom` nonce, the name's UTF-8 bytes as associated data and the `0x01` version byte. A tag failure becomes `CommandError("…the secret key is not the one '<name>' was kept with…", ErrorCode.Internal)`. T018 passes.
- [X] T023 [US1] Add `ankka.secrets { key = "", key = ${?ANKKA_SECRET_KEY} }` to `modules/runtime/src/main/resources/reference.conf`, with a comment giving the form and the three states of R4.
- [X] T024 [US1] Create `RT/DatabaseSecretStore.scala`: `private[ankka] final class DatabaseSecretStore(database: Database, key: Option[SecretKey]) extends SecretStore`, blocking with `Await.result` inside `scala.concurrent.blocking` as `DatabaseTimerScheduler` does. `put` checks the rules, requires the key, upserts (`INSERT … ON CONFLICT (name) DO UPDATE SET ciphertext = …, updated_at = now()`); `get` checks the name, requires the key, reads with `Database.query` and decrypts; `delete` checks the name and deletes with no key needed. No key is `CommandError(…, Internal)` naming `ANKKA_SECRET_KEY`; a database failure is `Unavailable`; SQLSTATE `42P01` (no table) is `Internal` naming `40-secrets-postgres.sql`. Build SQL with `SqlFragment`/`sql"…"` as `TimerStore` does. Log names, never values.
- [X] T025 [US1] In `RT/Ankka.scala`: parse `ankka.secrets.key` during `host`, before components start — empty is `None`, a `Left` fails the start with its message — and give `AnkkaService` a `lazy val secrets: SecretStore` built on `Database()` (beside `services`).
- [X] T026 [US1] Put `secrets: SecretStore` on the contexts that may have it, and on no other: `WorkflowContext` and `ConsumerContext` and their `Simple*` classes in `SDK/contexts.scala`; `TimedActionContext` and `SimpleTimedActionContext` in `SDK/TimedAction.scala`; `AgentContext`/`SimpleAgentContext` in `AGENT/Agent.scala`; `AutonomousAgentContext`/`SimpleAutonomousAgentContext` in `AGENT/autonomous/AutonomousAgent.scala`; `EndpointClients` in `HTTP/EndpointClients.scala` (a third `val`, with a stand-in beside `noServices` that throws when used). Do **not** touch `ComponentContext`, `EntityContext` or `ViewComponentContext`. T019 passes.
- [X] T027 [US1] Thread `service.secrets` to every construction site the compiler now reports: `RT/WorkflowHost.scala` (through `initWorkflow` in `Ankka.scala`), `RT/ProjectionRuntime.scala` (the two consumer handlers), `RT/TopicHandlers.scala` (`ConsumerTopicHandler`), `RT/TimerSweeper.scala` and `RT/TimerRuntime.scala`, `AGENT/AgentRuntime.scala` and `AGENT/autonomous/AutonomousAgentHost.scala`, `HTTP/HttpServer.scala`, and any remote host in `RT/remote` or `SC` that builds one of these contexts. `sbt compile` is warning-free.
- [X] T028 [US1] The step mark (R2, V1). First a case in `TKT/SecretStoreSuite.scala`: a workflow whose **command handler** calls `context.secrets.get` is refused with a `BadRequest` naming "a step", and the same call in a step succeeds. Holds `secret-store.feature` "a workflow reads a service secret in a step and not in a command". Then add `StepScope` (a thread-local set around the step body in `RT/WorkflowEngine.scala`'s `startStep`, cleared in a `finally`) and have `WorkflowHost` give the workflow context a store that throws outside the scope. Confirm by reading `WorkflowEngine` that command handling never runs inside `startStep`'s thread.
- [X] T029 [P] [US1] Create `TK/InMemorySecretStore.scala` (a `SecretStore` over a concurrent map, applying `SecretRules`), and give `TK/ConsumerTestKit.scala` one for the context it builds, exposed as `kit.secrets`. A case in `TKT/ConsumerTestKitSuite.scala` (or the suite that tests the kit): a consumer handler that keeps a secret, read back through `kit.secrets`; a bad name is refused as the runtime refuses it.
- [X] T030 [US1] In `TK/AnkkaTestKit.scala`: generate a key per kit (32 `SecureRandom` bytes, base64) and put `ankka.secrets.key` in `configFor`'s map; `start` gains `secretKey: Option[String]` defaulting to the generated one (`None` omits the key); `restartService` gains `secretKey` defaulting to the kit's; `current.secrets` returns the service's store; `startPeer` uses the kit's key. T020 passes in full.
- [X] T031 [US1] Break it once to see the suite can fail: make `DatabaseSecretStore.put` store the value's bytes unencrypted, run `sbt 'testkit/testOnly *SecretStoreSuite'`, see the dump case and the row-differs case red, restore, and confirm with `git diff` that nothing of the break is left.

**Checkpoint**: US1 is whole and independently testable. A Scala service keeps secrets; a dump of its database shows them in one place, encrypted. Nothing is in the protocol, the operator or the control plane yet.

---

## Phase 5: User Story 2 — a process-hosted or wasm service uses the store (Priority: P1)

**Goal**: Python, TypeScript and Rust services keep and read service secrets through the runtime; the secret key is made by the operator and reaches only the platform's own program.

**Independent Test**: each SDK's conformance run with `ANKKA_CONFORMANCE_ONLY='*secret.*'` reports eight cases; `sbt 'operator/testOnly *RenderingSuite *ProcessHostingRenderingSuite *WasmHostingRenderingSuite'` holds the key's placement; `sbt 'operator/testOnly *OperatorClusterSuite'` holds the Secret's life.

### Protocol 1.6 and the sidecar

- [X] T032 [US2] Edit `protocol/src/main/protobuf/ankka/protocol/v1/client.proto` per `contracts/protocol.md`: three `rpc`s and six messages, with comments stating that a refusal is in band and that `GetSecretReply` with no case is a fault. Update `protocol/README.md` (version `1.6` and what it added) and `protocol/WASM-ABI.md` (three rows in the imports table; `config` withholds `ANKKA_SECRET_KEY`). Run the three SDK copy scripts of T003.
- [X] T033 [US2] Bump the version everywhere the 1.3 commit (`cca2af9f`) did: `WireProtocol.Version = "1.6"` in `RT/remote/Conversation.scala`; the per-minor comment in `SC/Discovery.scala`; `Protocol.version = ProtocolVersion(1, 4)` and its comment in `API/Compatibility.scala`; and the assertions in `APIT/CompatibilitySuite.scala` (1.0–1.5 supported), `APIT/HostingSuite.scala`, `SCT/ProtocolSuite.scala` (an SDK on 1.5 is admitted) and `SCT/RemoteProjectionSuite.scala` (the metadata string). `PROTOCOL_VERSION` in the three SDKs moves in each SDK's own tasks.
- [X] T034 [US2] Cases first, in `SCT/ProtocolSuite.scala` (or a new `SCT/ClientSecretsSuite.scala` on `AnkkaTestKit`): through `ClientLogic`, put then get answers `value`; a name never kept answers `absent`; a bad name answers `Error(BAD_REQUEST)` naming the rule; with the kit started with no key, put and get answer `Error(INTERNAL)` naming `ANKKA_SECRET_KEY`; delete answers no error. Then add `getSecret`, `putSecret` and `deleteSecret` to `SC/ClientLogic.scala` over `service.secrets`, mapping `CommandError` through `Translate.toCode` as `invoke` does and anything else to `INTERNAL`; and the three delegating methods in `SC/ClientService.scala`, which never fail the gRPC call.
- [X] T035 [US2] Add the three imports to `SC/wasm/HostImports.scala` with `bytes("get_secret")`, `bytes("put_secret")`, `bytes("delete_secret")` in `values` (it stays a `lazy val`), each answering `Error(UNAVAILABLE)` before `bind`, as `invoke` does. Cases in `SCT/WasmHostSuite.scala`: each import answers through a bound `ClientLogic`; unbound, each answers `UNAVAILABLE`; `config("ANKKA_SECRET_KEY")` is absent with the variable set.
- [X] T036 [US2] The Scala conformance reference and the cases. In `CONF/ConformanceReference.scala`, add to `ConformanceEndpoint` the three routes of `contracts/protocol.md` (`PUT /secrets?name=`, `GET /secrets?name=` answering 404 when absent, `DELETE /secrets?name=`) over `clients.secrets`. In `CONF/ConformanceSuite.scala`, under a `// ── Secrets ──` banner, the eight `secret.*` cases of the contract; `secret.stored-encrypted` reads `ankka_secrets` and searches every table through the kit's `jdbcUrl`. Add the routes and behaviours to `specs/009-polyglot-runtimes/contracts/conformance.md`. Run `sbt 'sidecar/testOnly *ConformanceSuite -- *secret.*'` and **read that it ran eight cases** (a filter without its leading `*` runs none and is green).

### Python (independent of TypeScript and Rust)

- [X] T037 [P] [US2] V3 for Python: read `PY/src/ankka/server.py` (around lines 230, 280, 313) and `PY/src/ankka/context.py` to see what a workflow step is handed, and record in `research.md` R9 how a step is given the store and a command is not. Then tests first, in `PY/tests/`: the unit double agrees with every row of `proto/fixtures/secrets/rules.json`; an entity's `CommandContext` and a `View` have no `secrets` attribute; a consumer, a timed action, an agent, an endpoint and a workflow step do; a call answered gRPC `UNIMPLEMENTED` raises an error naming protocol `1.6` and the runtime's version.
- [X] T038 [US2] Implement in `PY/src/ankka/secrets.py` the `Secrets` class of `contracts/sdk-apis.md` over `ClientStub.GetSecret/PutSecret/DeleteSecret` (an in-band `Error` raises `CommandError` with its code; an unset `GetSecretReply` raises); wire it in `PY/src/ankka/server.py` and the consumer, timed action, agent, autonomous agent, graph consumer and endpoint classes, and the workflow's step context only; export it from `PY/src/ankka/__init__.py`; `PROTOCOL_VERSION = "1.6"` in `PY/src/ankka/service.py`; an in-memory `Secrets` in `PY/src/ankka/testkit/`; and in `PY/src/ankka/testkit/integration.py` a generated `ANKKA_SECRET_KEY` on the sidecar unless `env` names one. `uv run pytest -q && uv run mypy` pass.
- [X] T039 [US2] Add the three routes to `PY/examples/shopping_cart/conformance.py`'s `ConformanceEndpoint`, run `ANKKA_CONFORMANCE_ONLY='*secret.*' uv run conformance`, and confirm eight cases ran. V6: stash the endpoint change with a WIP commit, see the cases fail, restore.

### TypeScript (independent of Python and Rust)

- [X] T040 [P] [US2] V3 for TypeScript: read `TS/src/server/workflow.ts` (around lines 80, 133) for how a step is bound, and record it in R9. Then tests first, in `TS/test/`: the double agrees with the rules fixture; `// @ts-expect-error` on `entity.secrets` for both entity classes and the view, beside a consumer where it compiles; a run-time check that the property is absent on those three; an `UNIMPLEMENTED` answer is reported as a version mismatch. Every server a test starts destroys its sessions before `close()`.
- [X] T041 [US2] Implement `TS/src/secrets.ts` (`class Secrets` holding the shared `Connection`, erasable syntax only: no parameter properties), `get secrets()` on the consumer, timed action, agent, autonomous agent, endpoint and graph consumer classes and on a workflow within a step, bound in `TS/src/server/*`; `noSecrets()` beside `noClient()` in `TS/src/client.ts`; an in-memory store in `TS/src/testkit/`; export from `TS/src/index.ts`; `PROTOCOL_VERSION = "1.6"` in `TS/src/spec.ts`; a generated `ANKKA_SECRET_KEY` in `TS/src/testkit/integration.ts`'s environment unless `env` names one. `npm run proto && npm run typecheck && npm test` pass on Node 22 and 24.
- [X] T042 [US2] Add the three routes to `TS/examples/shopping-cart/conformance.ts`, run `ANKKA_CONFORMANCE_ONLY='*secret.*' npm run conformance`, confirm eight cases ran, and show them failing once without the endpoint change (V6).

### Rust (independent of Python and TypeScript)

- [X] T043 [P] [US2] V2 first: build a module from the workspace that does not call the store (`cargo build -p shopping-cart --release --target wasm32-unknown-unknown` before the conformance routes exist) with the new `extern` declarations present, and list its imports (`wasm-objdump -x` or `wasm-tools print | grep import`): `get_secret` must be absent. Record the result in R8. If it is present, gate the declarations so an unused store imports nothing, before going on. Then tests first, in `RS/ankka/`: the native host's store agrees with the rules fixture; `ctx.secrets()` is `None` for an event sourced entity, a key value entity, a view and a workflow command, and `Some` for a consumer, a timed action, an agent, an endpoint and a workflow step.
- [X] T044 [US2] Implement `RS/ankka/src/secrets.rs` (`Secrets` with `put`, `get`, `delete` returning `Result`), `Context::secrets()` in `RS/ankka/src/context.rs` decided where each kind builds its context, `Import::GetSecret/PutSecret/DeleteSecret` in `RS/ankka/src/abi/imports.rs` (wasm32 block and native host, whose default answers come from an in-memory map applying the rules), `PROTOCOL_VERSION = "1.6"` and its test in `RS/ankka/src/service.rs`, and a generated `ANKKA_SECRET_KEY` in `RS/ankka/src/testkit/integration.rs` unless `start_with` names one. `cargo test --workspace`, clippy and rustdoc at `-D warnings` pass.
- [X] T045 [US2] Add the three routes to `RS/examples/shopping-cart/src/conformance.rs`, run `ANKKA_CONFORMANCE_ONLY='*secret.*' ./conformance.sh`, and confirm eight cases ran **in each of the two shapes the script prints**. Show them failing once without the endpoint change (V6).

### The operator's key (independent of the SDKs)

- [X] T046 [P] [US2] Tests first, offline. In `OPT/RenderingSuite.scala`: an embedded service's container has `ANKKA_SECRET_KEY` as a `secretKeyRef` to `<service>-secret-key`, key `key`; `EnsureSecretKey` precedes `ApplyDeployment` in the rendered actions; the action's `describe` contains the namespace and name and no key material; a descriptor that sets `ANKKA_SECRET_KEY` (as a value, and as a `secretKeyRef`) renders **no** `EnsureSecretKey` and no added variable. In `OPT/ProcessHostingRenderingSuite.scala`: the variable is on the platform's container and absent from `<service>-app`, for the rendered key and for a descriptor-set one. In `OPT/WasmHostingRenderingSuite.scala`: on the one container. Holds `secret-key.feature`'s first, second, fourth and fifth scenarios.
- [X] T047 [US2] Implement: `Names.secretKeySecret(service) = s"$service-secret-key"` in `OP/Names.scala`; `case EnsureSecretKey(namespace: String, name: String, labels: Map[String, String])` in `OP/Action.scala` with its `describe`; in `OP/Rendering.scala`, emit it before the Deployment and add the variable through `container`'s `extraEnv` when `!spec.env.exists(_.name == PlatformVariables.SecretKey)`; in `OP/Executor.scala`, perform it by generating 32 `SecureRandom` bytes, building an `Opaque` Secret with the labels, entry `key` and no owner reference, and calling `create` — a `KubernetesClientException` with code 409 is success — remembering `(namespace, name)` in a concurrent set so later reconciles skip the call. Never `get` it; never log the bytes.
- [X] T048 [US2] k3s, in `OPT/OperatorClusterSuite.scala` (run with `caffeinate -i sbt 'operator/testOnly *OperatorClusterSuite'`): after the first reconcile the Secret exists and its `key` decodes to 32 bytes; ten more reconciles leave its `resourceVersion` unchanged; a second service in the project has a different key; deleting the `AnkkaService` keeps the Secret, and re-applying leaves its `uid` and data unchanged and the new pod's template names it (`secret-key.feature` "a service deleted and deployed again…", with `SecretStoreSuite`'s restart case, per R17); and the shipped-grant case still passes with no rule added to `kustomization/components/operator/operator.yaml`.
- [X] T049 [P] [US2] Pass the key through for local runs: `ANKKA_SECRET_KEY: ${ANKKA_SECRET_KEY:-}` on the sidecar (or runtime) service of `cli/src/main/templates/common/docker-compose.yml` and on any compose file under `sdks/*/examples` that starts the runtime, with a comment saying how to make one (`openssl rand -base64 32`). Run the template suites for the languages touched (`sbt -Dankka.template.tests=python,typescript,rust 'cli/testOnly *TemplateSuite'`) if their tools are present; otherwise say in the pull request which were not run.

**Checkpoint**: US2 is whole. Four languages keep secrets through one implementation; a deployed service has a key it never chose, on the platform's container only.

---

## Phase 6: User Story 3 — a project secret is set without cluster credentials (Priority: P2)

**Goal**: a member or a deploy token sets, unsets and lists project secrets through the CLI; the control plane writes them and can never read them; a descriptor's `secretKeyRef` finds them.

**Independent Test**: `sbt 'controlPlaneApi/testOnly *ProjectSecretsSuite *ControlPlaneFixturesSuite' 'controlPlane/testOnly *ControlPlaneHttpSuite *TenancyEntitySuite *EventCompatibilitySuite *ReservedSecretNamesSuite' 'cli/testOnly *ProjectSecretsCommandSuite'`, then the two k3s suites of T061–T062.

### Verify first

- [X] T050 [US3] V5, on k3s, before any endpoint code: extend case 8 of `CPT/ControlPlaneClusterSuite.scala` (the shipped grant, with the control plane's own ServiceAccount token). With that restricted client: a JSON merge patch `{"stringData": {"A": "1"}}` on a Secret that does not exist answers **404**, not 403; after a `create` of an `Opaque` Secret with entry `A`, a merge patch `{"stringData": {"B": "2"}}` leaves `A` and adds `B` (read back with the suite's admin client); `{"data": {"A": null}}` removes `A` and leaves `B`; after `{"data": {"B": null}}` leaves the Secret with no entry, a merge patch `{"stringData": {"C": "3"}}` succeeds and the Secret holds `C` alone; and `get`, `list` and `delete` are still 403. If any of these is not so, stop and revise R13 before T055.

### Tests first

- [X] T051 [P] [US3] Write `APIT/ProjectSecretsSuite.scala` per `contracts/control-plane.md` "Rules": accepted names; each refused form (`ankka-x`, `x-db`, `x-cluster-tls`, `x-service-tls`, `x-database-tls`, `x-secret-key`) refused with a message saying the form is the platform's; an uppercase name, a leading `-`, a 254-character name refused; entry names (`STRIPE_KEY`, `a.b-c` accepted; a space, `=`, empty refused); an empty value and a 65,537-byte value refused; no entries refused; every problem of a request reported together.
- [X] T052 [P] [US3] In `CPT/EventCompatibilitySuite.scala`: `ProjectSecretEntriesSet` and `ProjectSecretEntryRemoved` round-trip; the encoded JSON's field names are exactly `type`, `name`, `entries` (or `entry`), `actor`, `at`; a value used in the test occurs nowhere in the JSON; a `Project` snapshot with no `secrets` field decodes to an empty map (add the old JSON as a literal).
- [X] T053 [P] [US3] In `CPT/TenancyEntitySuite.scala`, on the entity test kit: set records the event with attribution and the entries; a second set with another entry merges; setting the same entry again keeps one; remove takes the entry out; removing the last entry takes the secret out of the state; removing an entry that is not there is `NotFound` and records nothing; any of these on a project that does not exist is `NotFound`.
- [X] T054 [US3] In `CPT/ControlPlaneHttpSuite.scala`, one case per row of the three answer tables of `contracts/control-plane.md` and per scenario of `project-secrets.feature`: set then list shows name and entries and no value; the value reached the fake cluster and is in no response body; a second set keeps the first entry (asserted **in the fake's Secret**, so a replacing endpoint fails); unset removes one; the last unset stops it being listed and leaves the fake's Secret, empty; a set on that name afterwards lists it again with only the new entry, and the fake's Secret holds only that entry; unset of an unknown entry is 404 and the fake saw no call; a non-member gets 404 "no such project" for set, unset and list; a deploy token may do all three; each reserved name is 400 and the fake saw no call; `refuseSecrets()` makes set and unset 503 and the listing is unchanged afterwards; the listing is correct **immediately** after a set, with no `eventually`. Extend `CPT/FakeAnkkaServiceClient.scala` with per-namespace, per-name entry maps that merge on set and remove on unset.

### Implementation

- [X] T055 [US3] In `API/descriptors.scala`: `SetProjectSecret`, `ProjectSecretSummary`, `object ProjectSecrets` with `problems(name, entries)`, `nameProblems`, `ReservedPrefix`, `ReservedSuffixes`; codecs in `Wire`; fixtures in `APIT/ControlPlaneFixturesSuite.scala` and the fixture files it writes. T051 passes. Then mirror both types as zod schemas in `console/package/src/client/schemas.ts` and `schemasByType`, and run `just test-console` (its `fixtures.test.ts` fails without them).
- [X] T056 [US3] In `CP/domain/events.scala` and `CP/domain/model.scala`: the two events with `actor`/`at` defaulting to `None`; `ProjectSecretRef`; `Project.secrets` defaulting to empty; the two folds of `data-model.md`. In `CP/application/ProjectEntity.scala`: commands `set-secret-entries` and `remove-secret-entry` and query `secrets` on the companion with declared wire names and serializers, refusing as T053 says. In `CP/application/ProjectRows.scala`: the two events leave the row unchanged. T052 and T053 pass.
- [X] T057 [US3] In `CP/deploy/AnkkaServiceClient.scala`: `setSecretEntries(namespace, name, entries)` and `removeSecretEntry(namespace, name, key)` on the client trait, and `trait ProjectSecretWriter` with `setEntries(projectId, name, entries)` and `removeEntry(projectId, name, key)`. In `CP/deploy/Fabric8AnkkaServiceClient.scala`: implement per R13 with `PatchContext.of(PatchType.JSON_MERGE)` — ensure the namespace, patch, on 404 create (type `Opaque`, the two labels), on that create's 409 patch again; removal patches `{"data": {key: null}}`; a 4xx other than 404/409 from the API server (too large, invalid) becomes `CommandError(…, BadRequest)` with its message; never `get`; log names, never values. In `CP/deploy/ServiceProjector.scala`: implement `ProjectSecretWriter` beside `RegistryWriter`.
- [X] T058 [US3] In `CP/api/ProjectEndpoint.scala`: a `secretWriter: Option[ProjectSecretWriter] = None` parameter and the three routes, in the registry route's order — `authz.project(principal, projectId, write)`, `ProjectSecrets.problems` (400 naming every problem), the writer (no writer or a non-`CommandError` failure is `Unavailable`), then the entity command with `authz.metadata`. Unset asks the entity's `secrets` query first and answers 404 before touching the cluster. `GET` asks the entity and sorts by name. Wire `secretWriter = Some(projector)` in `CP/ControlPlane.scala` beside `registry`. T054 passes.
- [X] T059 [US3] Write `CPT/ReservedSecretNamesSuite.scala` beside `ReservedProjectIdsSuite`: for a sample service name, every Secret name the operator derives (`CnpgRendering.credentialSecretName`, the three `ZeroTrust` certificate secret names, `Names.secretKeySecret`, `Registries.SecretName`, `CnpgRendering`'s `ankka-db-*` names) is refused by `ProjectSecrets.nameProblems`. Show it failing once by removing `-secret-key` from `ReservedSuffixes`.
- [X] T060 [US3] The CLI. Tests first in `CLIT/ProjectSecretsCommandSuite.scala`, driving `Main.run` against a stub HTTP server on an ephemeral port with `-Dankka.config` pointing at a file the test owns: `set checkout A=1 B=2` sends one `PUT` with both entries and prints the names and no value; `set checkout A=-` reads the value through `Console.withIn` (one trailing newline removed); two `=-` pairs, a pair with no `=`, and a reserved name are refused locally with no request sent; `unset` sends the `DELETE`; `list` prints `NAME`, `ENTRIES`, `SET`, `BY` and, with `-o json`, the body. Then implement: `setProjectSecret`, `unsetProjectSecretEntry`, `listProjectSecrets` in `CLI/ControlPlaneClient.scala`; the `secrets` subcommand tree under `projectsCommand` in `CLI/Main.scala`; `Output.projectSecrets` in `CLI/Output.scala`.
- [X] T061 [US3] k3s end to end, in `CPT/EndToEndClusterSuite.scala`, through the real CLI: `projects secrets set` an entry; apply a descriptor whose variable takes it by `secretKeyRef`; read the variable **inside the pod** (`InPod`, `printenv`) and see the value; set the entry again; `services restart`; read the new value in the new pod. And one case for a descriptor that references a project secret that does not exist: the service does not become ready and `services get` shows the cluster's reason.
- [X] T062 [US3] Run `caffeinate -i sbt 'controlPlane/testOnly *ControlPlaneClusterSuite *EndToEndClusterSuite'` and confirm the control plane's ClusterRole in `kustomization/components/controlplane/controlplane-rbac.yaml` is byte-for-byte unchanged (`git diff --stat` shows nothing for it); extend its comment only to say the same rule now covers project secrets.

**Checkpoint**: US3 is whole. A deploy token in CI can give a service a credential with no cluster access, and nothing the control plane stores holds a value.

---

## Phase 7: Documentation, and the whole build

**Purpose**: FR-014, and everything green together.

- [X] T063 [P] Write `DOCS/build/secrets.md` per R18: what a service secret is, who has the store and who does not (and why), the rules and the limit, `put`/`get`/`delete` in Scala with a sample included from `TKT/SecretStoreSuite.scala`'s test service by `// docs:start` markers, the three failures and what each means, testing with `InMemorySecretStore` and `AnkkaTestKit`. The page stands alone: no feature numbers, no "see above". Add it to `mkdocs.yml`'s `nav` and to a skill's `pages:` under `SKILL/`.
- [X] T064 [P] In `DOCS/platform/` (on `databases.md` or a sibling page added to `nav` and a skill): the secret key — made by the platform per service, kept when a service is deleted, supplying your own with `ANKKA_SECRET_KEY`, what a local run needs; and project secrets — `set`, `unset`, `list`, referencing an entry from a descriptor (the `service.json` block must be a valid descriptor: `DocumentationDescriptorsSuite` decodes it), the reserved names and why, what the control plane can and cannot do with a value.
- [X] T065 Reference pages: run `just docs-reference` (the CLI page and the routes table regenerate); in `DOCS/reference/control-plane-api.md` write a section for each new route containing its literal `` `METHOD /path` `` (the suite's coverage check); in `DOCS/reference/configuration.md` mention `ankka.secrets.key` in the prose beside the generated table and add `ANKKA_SECRET_KEY` to "What each variable means"; in `DOCS/reference/sidecar-protocol.md` add 1.6 to the version history (the table regenerates with `just docs-sync`); in `DOCS/reference/wasm-abi.md` the three imports; `DOCS/concepts/polyglot.md`'s protocol version; `DOCS/reference/glossary.md` (service secret, secret store, secret key, project secret); `DOCS/reference/limitations.md` (no rotation, no sharing between services, no file mount, no console page).
- [X] T066 [P] Each SDK's docs page for the store (Python, TypeScript, Rust), with samples included from that SDK's tested example by markers, saying where the store is offered and that an entity and a view have none.
- [X] T067 Run `just docs-sync && just docs` until clean (the rendered skills under `marketplace/` and `ankka.g8/src/main/g8/.claude/skills/` change; in the template's copy every `$` stays escaped).
- [X] T068 Update `CLAUDE.md`: the DDL list's fourth file and its seven lists; one architecture paragraph on the secret store, the secret key and project secrets; `PlatformVariables` as the one declaration and why the operator compiles it; and a trap for each thing implementation found that a later change could walk into (at least: server-side apply cannot merge a Secret's entries; a project secret's name can collide with the platform's).
- [X] T069 Run the BDD checker (`.specify/extensions/bdd/bdd-config.yml`'s `checker`, with `--specs-from 019`) and confirm no finding names `specs/023-secret-store`, `features/secrets` or `GLOSSARY.md`; confirm each of the 48 references in `spec.md` names a scenario that a test in this branch holds, and list any that is held only by the composition R17 describes.
- [X] T070 `sbt scalafmtAll scalafmtSbt`, then `sbt -Dankka.cluster.tests=off compile test` warning-free and green, then `caffeinate -i sbt test` for the k3s suites; `cd sdks/python && uv run pytest -q && uv run mypy && uv run conformance`; `cd sdks/typescript && npm run typecheck && npm test && npm run test:slow && npm run conformance`; `cd sdks/rust && cargo test --workspace && ./conformance.sh`; `just test-console`. Read each conformance run's own count of cases.
- [ ] T071 `quickstart.md` step 7 on the local cluster (`just up`): set and list a project secret, see a service's key Secret keep its `uid` through delete and apply. Note in the pull request that the first reconcile after the operator's upgrade rolls every service once.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (1)** → nothing. **Foundational (2)** → nothing; blocks US1 and US2's conformance.
- **US4 (3)** → Setup. Blocks US2 (the key's routing) and touches `descriptors.scala`, which US3 also edits; do it first.
- **US1 (4)** → Foundational. Blocks US2's protocol group (`ClientLogic` calls `service.secrets`).
- **US2 (5)** → US1 and US4. Within it, the protocol group (T032–T036) blocks the three SDK groups; the operator group (T046–T048) depends only on US4.
- **US3 (6)** → US4 (same file) and, for T059 only, T047's `Names.secretKeySecret`. Otherwise independent of US1 and US2.
- **Documentation (7)** → everything it describes.

### Within a story

Tests before the code they hold; a "verify first" task before the design it could overturn (T010, T028, T037, T040, T043, T050).

### Parallel opportunities

- T008 beside T007. T014 and T015 once T012 and T013 are in.
- T017, T018 and T019 together; T029 beside T027–T028.
- After T036: the Python (T037–T039), TypeScript (T040–T042) and Rust (T043–T045) groups, and the operator group (T046–T048), are four independent lines.
- T051, T052 and T053 together.
- US3 as a whole beside US2, by a second person, once US4 is merged.
- T063, T064 and T066 together.

### Parallel example: User Story 2 after the protocol is in

```text
Line A: T037 → T038 → T039      (Python)
Line B: T040 → T041 → T042      (TypeScript)
Line C: T043 → T044 → T045      (Rust)
Line D: T046 → T047 → T048      (the operator's key)
```

---

## Implementation Strategy

**MVP**: Phases 1, 2 and 4 — User Story 1. A Scala service keeps secrets that never reach its journal, tested against a real database. It is useful on its own and touches no wire, no operator and no control plane.

**Then, each a mergeable step**:

1. US4 (Phase 3), a refactoring with two small behaviour changes, green on its own.
2. US2's protocol and sidecar (T032–T036), then each SDK as it is ready.
3. US2's operator key (T046–T048) — the step that rolls every deployed service once.
4. US3 (Phase 6).
5. Documentation and the whole build (Phase 7).

Phase 3 is written before Phase 4 because it is first by dependency for US2; for an MVP of US1 alone it can wait.

## Notes

- A task that says "show it failing" is not done until it has been seen red and the break is confirmed gone (`git diff`, or a grep for what was pasted).
- Read what a run says it ran. A munit filter needs its leading `*`; the Rust conformance script prints its shape; a k3s suite that takes hours has been run on a sleeping laptop.
- Never log, print, describe or journal a value or a key. The suites assert it for the journal, the events and the action's description; nothing asserts it for a log line, so it is the reviewer's to read for.
