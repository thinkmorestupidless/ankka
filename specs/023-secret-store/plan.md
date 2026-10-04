# Implementation Plan: Secret Store — Values a Service Holds Without Ever Journaling Them

**Branch**: `023-secret-store` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/023-secret-store/spec.md`

## Summary

A service gets two kinds of secret it cannot have today. A **service secret** is a named text
value the service keeps and reads while it runs, through a `SecretStore` offered to endpoints,
workflow steps, consumers, timed actions and agents and to no entity and no view. It lives in one
new table of the service's own database, encrypted with a **secret key** the operator makes once
per service and gives only to the platform's own program. A **project secret** is a Kubernetes
Secret a member sets through the control plane, entry by entry, which the control plane can write
and never read; a descriptor references it with the `secretKeyRef` that already works. And the
three hand-kept lists of the platform's variables become one declaration.

Technically: the store is a trait in `sdk` implemented in `runtime` over the pool the journal
already uses (R1), on the contexts of the components that may have it (R2). Values are AES-256-GCM
under the name as associated data, behind a version byte (R3); the key is configuration (R4) and
the table is a fourth DDL file (R5). A process and a module reach the store through three calls
added to the protocol's `Client` service and three imports, as protocol 1.6, all behind the one
`ClientLogic` (R8, R9). The operator describes the key's Secret with an action that holds no key
and makes it with a create that tolerates "already exists", so it never reads one (R11). Project
secrets are a merge patch followed by a create, under the grant the registry credential has, and
the `Project` entity records names and never values (R13, R14).

Planning found three things the spec did not have:

- **The one declaration cannot live in `controlplane-api`**, where the spec first put it. Neither
  the operator nor the sidecar can see that module. It lives in `core`, and the operator compiles
  the same source file, keeping its dependency on `crd` alone (R12). FR-013 was amended to say so.
- **A project secret's name could overwrite a Secret the platform keeps** in the same namespace —
  a service's secret key among them. Names in the platform's forms are refused (R15).
- **The sidecar cannot tell which component called it**, so "no store in an entity or a view" is
  enforced by each SDK's types and contexts, not by the runtime (R2, R9).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `http`, `agent`, `testkit`,
`sidecar`, `controlplane-api`, `controlplane`, `operator`, `cli`); Python ≥ 3.12 (`sdks/python`);
TypeScript on Node ≥ 22 (`sdks/typescript`, `console/package`); Rust, edition 2024, target
`wasm32-unknown-unknown` (`sdks/rust`); protobuf (`protocol`); SQL (one DDL file)

**Primary Dependencies**: none added, in any language. The cipher is the JDK's `javax.crypto`;
the database is reached through `runtime`'s existing `Database` over the r2dbc pool; the cluster
through fabric8, already in `operator` and `controlplane`.

**Storage**: Postgres, one new table `ankka_secrets` in each service's own database, from a new
file in the canonical DDL directory, additive. Kubernetes Secrets for the secret key and for
project secrets. The control plane's journal gains two project events and `Project` one defaulted
field. No change to `AnkkaServiceSpec` or its schema.

**Testing**: munit in `core`, `sdk`, `runtime`, `controlplane-api`, `cli`; `testkit` with Postgres
(`SecretStoreSuite`); `sidecar` (`WasmHostSuite`, `ProtocolSuite`, `ConformanceSuite`'s new
`secret.*` cases against the Scala reference and each SDK); operator rendering suites; the control
plane's fast HTTP suite with the fake cluster; three k3s suites extended (`OperatorClusterSuite`,
`ControlPlaneClusterSuite`, `EndToEndClusterSuite`); pytest with mypy; Node's test runner with
`tsc`; `cargo test`; the console's fixtures test; the docs build.

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, as a module —
on a developer's machine and in a cluster; the control plane and the operator in a cluster.

**Project Type**: platform libraries and a protocol (four SDKs, the runtime, the sidecar), an
operator, a control plane and its CLI, documentation

**Performance Goals**: a `get` is one indexed row read and one decryption on the journal's pool,
with no cache; nothing is added to any path that does not call the store. The operator asks the
API server about a service's key once per process, not once per reconcile.

**Constraints**: no service secret in a journal, snapshot, durable state, timer or view row, a
log, an action's description or a protocol message other than the three calls; the secret key
never in the developer's program; no value in the control plane's journal; the operator's and the
control plane's grants unchanged; the operator's `dependsOn` unchanged; a service with no key
starts; a 1.3 process or module runs unchanged; the three SDK copies of `protocol/` identical to
the canonical one; `Test / parallelExecution := false` stays; warning-free; no suite binds a fixed
port

**Scale/Scope**: about 14 new Scala source files and 30 changed across ten modules, with 9 new
suites and about 16 changed; 1 DDL file named in seven lists; 1 protocol file and 1 fixture file;
per SDK about 2 new source files, 6 changed, 2 new test files and the conformance reference
changed; 2 console files; 1 new docs page and about 12 changed; the skills rendered again

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added. The store is a client like `ComponentClient`, offered only where handlers already run blocking sequential code, and withheld from entities, whose handlers return effects (R1, R2). `EnsureSecretKey` is an inert description with no key in it (R11) |
| Where two interpreters reduce the same thing, they share one function | pass | `SecretRules` is used by the runtime's store and the testkit's (R7); `ClientLogic` serves the process and the module (R8); `ProjectSecrets.problems` runs in the CLI and the control plane (R15) |
| Module dependency direction | pass | trait in `sdk`, implementation in `runtime`, double in `testkit`; `controlplane-api` still on `core` alone; the operator still on `crd` alone, compiling one shared source file (R12) |
| `runtime` never sees the generated protocol | pass | `ClientLogic` in `sidecar` translates the three messages to the `sdk` trait's plain values |
| No classpath scanning; explicit registration | pass | no component is added; nothing is discovered |
| Wire names are a versioning boundary | pass | three calls, three imports and two event names are declared strings; the protocol change is a minor by its own rule (R8) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| `protocol/fixtures/` at its top level belongs to `EncodingFixturesSuite` | pass | the rules fixture is in `protocol/fixtures/secrets/` (R7) |
| Stored forms stay readable both ways; the schema is additive | pass | one new table; `Project.secrets` defaults to empty and an old snapshot is pinned (R5, R14) |
| No secret value in the control plane's journal | pass | the events have no field for one, asserted by absence (R14) |
| Write the cluster first, then the journal | pass | R14 |
| A field on the resource needs the schema | pass | no field is added; the operator reads `spec.env` (R11) |
| The operator cannot reach into the control plane | pass | `dependsOn(crd)` is unchanged; the shared file has no import (R12) |
| Never touch `ActorContext` from a `Future` callback; anything read from the environment is overridable | pass | the store is called on virtual threads and uses no actor; the key is configuration, set by the test kit (R4, R10) |
| Tests are serialised; a test never binds a fixed port; a test names no image by a literal tag | pass | nothing changes in `build.sbt`'s test settings; no new image |
| An `eventually` waits for the thing it asserts | pass | the listing is an entity query, so no test waits on a projection (R14) |
| Could this check pass while the thing it checks is false? | pass | the dump reads every table, not a list (scala-api); the fake cluster merges as the cluster does (control-plane); the declaration is tested by iteration and by a source walk shown failing once (R12); the conformance filter's wildcard is named (quickstart) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 48 scenario references in the spec, each mapped to a level in R17 |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R18 |
| Every tracked file claimed by a CI path filter | pass | new files fall under directories the filters already claim; `features/` and `GLOSSARY.md` are new at the root and need a filter or an `unchecked` entry, which is a task |

**Violations to justify**: none against these principles. Where the plan departs from the spec's
wording is under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no field on the resource
and no grant.

## Project Structure

### Documentation (this feature)

```text
specs/023-secret-store/
├── plan.md              # this file
├── research.md          # R1–R18: decisions with file-level evidence; six things to verify first
├── data-model.md        # the secrets table and its stored form, the key, the project's record
├── quickstart.md        # the validation runs: pure → database → SDKs → offline → k3s → docs → by hand
├── contracts/
│   ├── scala-api.md         # the trait, the rules, who has it, configuration, test kits
│   ├── protocol.md          # the wire at 1.6, the imports, the conformance cases
│   ├── sdk-apis.md          # Python, TypeScript and Rust
│   ├── control-plane.md     # routes, wire types, what is recorded, the commands
│   └── operator.md          # the key's Secret, where the variable goes, the one declaration
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/secrets/` (five files), in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/core/…/core/PlatformVariables.scala                    # new: the one declaration
modules/sdk/…/sdk/SecretStore.scala                            # new: the trait and SecretRules
modules/sdk/…/sdk/contexts.scala, TimedAction.scala            # secrets on workflow, consumer, timed action
modules/runtime/…/runtime/DatabaseSecretStore.scala            # new: the table, over Database
modules/runtime/…/runtime/SecretCipher.scala                   # new: the stored form
modules/runtime/…/runtime/Ankka.scala                          # AnkkaService.secrets
modules/runtime/…/runtime/WorkflowEngine.scala, WorkflowHost.scala   # the step mark
modules/runtime/…/runtime/ProjectionRuntime.scala, TopicHandlers.scala, TimerSweeper.scala
modules/runtime/src/main/resources/reference.conf              # ankka.secrets.key
modules/http/…/http/EndpointClients.scala, HttpServer.scala
modules/agent/…/agent/Agent.scala, AgentRuntime.scala, autonomous/…
modules/testkit/…/testkit/AnkkaTestKit.scala                   # the key; the fourth DDL file
modules/testkit/…/testkit/InMemorySecretStore.scala            # new
modules/testkit/…/testkit/ConsumerTestKit.scala

kustomization/components/postgres/ddl/40-secrets-postgres.sql  # new
kustomization/components/postgres/kustomization.yaml, cluster.yaml

protocol/src/main/protobuf/ankka/protocol/v1/client.proto
protocol/README.md, protocol/WASM-ABI.md
protocol/fixtures/secrets/rules.json                           # new

sidecar/…/sidecar/ClientLogic.scala, ClientService.scala, Discovery.scala
sidecar/…/sidecar/wasm/HostImports.scala                       # three imports; lists deleted
sidecar/src/test/…/conformance/ConformanceSuite.scala, ConformanceReference.scala

operator/…/operator/Action.scala, Executor.scala, Rendering.scala, Names.scala, CnpgRendering.scala
build.sbt                                                      # operator compiles PlatformVariables.scala

controlplane-api/…/api/descriptors.scala                       # wire types, ProjectSecrets, lists deleted
controlplane-api/…/api/Compatibility.scala                     # protocol 1.6
controlplane/…/controlplane/domain/events.scala, model.scala
controlplane/…/controlplane/application/ProjectEntity.scala, ProjectRows.scala
controlplane/…/controlplane/api/ProjectEndpoint.scala
controlplane/…/controlplane/deploy/AnkkaServiceClient.scala, Fabric8AnkkaServiceClient.scala,
                                    ServiceProjector.scala
controlplane/…/controlplane/ControlPlane.scala

cli/…/cli/Main.scala, ControlPlaneClient.scala, Output.scala
cli/src/main/templates/common/docker-compose.yml               # ANKKA_SECRET_KEY passed through

sdks/python/src/ankka/secrets.py (new), server.py, service.py, testkit/…
sdks/typescript/src/secrets.ts (new), server/…, spec.ts, testkit/…
sdks/rust/ankka/src/secrets.rs (new), context.rs, abi/imports.rs, service.rs, testkit/…
sdks/*/examples/…/conformance.*                                # three routes each

console/package/src/client/schemas.ts                          # two mirrors
docs/build/secrets.md (new), docs/platform/, docs/reference/, mkdocs.yml, tools/docs/skill/
.github/workflows/ci.yml                                       # a filter for features/ and GLOSSARY.md
```

**Structure Decision**: no module, image or published artifact is added. Each piece goes where
its kind already lives: the trait beside `ServiceClients` in `sdk`, the implementation beside
`Database` in `runtime`, the calls on the protocol's existing `Client` service, the key's Secret
beside the database's in the operator's rendering, project secrets beside the registry credential
in the `Project` entity and its endpoint. The one new placement is `PlatformVariables` in `core`,
compiled a second time into the operator (R12).

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own.

1. **The declaration** (User Story 4). Characterise today's behaviour with the suites that exist,
   add `PlatformVariables`, delete the three lists, add the iteration and source-walk tests. It
   comes first because every later slice names `ANKKA_SECRET_KEY` through it.
2. **The store in Scala** (User Story 1). Rules and fixture, cipher, DDL and its seven lists, the
   database store, the contexts, the step mark, the test kits, `SecretStoreSuite`.
3. **The protocol and the sidecar** (User Story 2, first half). 1.6, `ClientLogic`, the imports,
   the Scala conformance reference and cases.
4. **The three SDKs** (User Story 2, second half), independent of each other: client, contexts,
   doubles, integration kit, conformance reference.
5. **The operator's key** (User Story 2's placement, FR-006 to FR-008). The action, the executor,
   the rendering, the offline suites, then k3s.
6. **Project secrets** (User Story 3). Rules and wire types, events and state, the cluster client,
   the endpoint, the CLI, the console's mirrors, the fast suite, then k3s.
7. **Documentation**, then the whole build.

Slices 3 to 6 depend on 1 and 2 only where named; 5 and 6 do not depend on each other or on 3 and
4.

## Complexity Tracking

No principle is violated. These are the places the plan departs from the spec's wording or widens
the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| One source file is compiled into two modules, `core` and the operator (FR-013, as amended) | no module can be seen from `controlplane-api`, the sidecar and the operator at once; the operator's only dependency is `crd`, by design | putting it in `controlplane-api`, as the spec first said, leaves the operator and the sidecar with copies, which is what the feature removes; a dependency from the operator on `core` ends a stated build-level fact (R12) |
| Project secret names in the platform's forms are refused (FR-016, added in planning) | the control plane patches a Secret by a member's chosen name in the namespace that holds the platform's own, without being able to look first; `payments-secret-key` would destroy a service's key | no rule means a typo can make every secret a service kept unreadable for good (R15) |
| The writer has two methods — the spec says one (FR-009) | clarification added `unset` | one method taking "entries to set or remove" hides two operations with different refusals |
| A workflow's store refuses outside a step, at run time | a workflow's commands and steps share one context, so the type cannot separate them | offering it to commands puts a blocking read on the workflow's single-writer path, which is what the clarification refused for entities (R2) |
| `<key>=-` reads a value from standard input | a value on a command line is in shell history | the spec's `<key>=<value>` alone forces that on every use; `registry set` already has `--password-stdin` (R16) |
| The console gets schemas and no page | a new wire type with no mirror fails two suites | a page is the spec's open question; answering it "later" keeps this feature to the CLI (R16) |
| Every service is given a key, used or not | a descriptor has nothing to ask for one with | a store that works on a laptop and fails deployed until something is declared (R11) |

**One operational consequence** to announce in the release: the first reconcile after the operator
is upgraded adds a variable to every service's pod template, so every service rolls once.
