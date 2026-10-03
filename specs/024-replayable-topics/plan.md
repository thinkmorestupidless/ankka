# Implementation Plan: Replayable Topic Sources — Groups of Their Own, a Chosen Start, and Rebuild on Version Change

**Branch**: `024-replayable-topics` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/024-replayable-topics/spec.md`

## Summary

A view or consumer that reads a Kafka topic gets three things. Its consumer group is named for its
service, so two services reading one topic each receive every message instead of half each. It
declares where it starts — the earliest retained message, the latest, or a time — and a consumer
must. And it declares a version: a view at a higher version than the one recorded for it is
emptied and read again from its start position, which is rebuild on deploy for as far back as the
broker retains. Scala, Python, TypeScript and Rust get both declarations.

Technically: one pure function names every group, with the version beside the kind where no
component id can spell it (R2). The start position is resolved to an offset and committed the
first time a partition is assigned, so it applies once and a restart always resumes (R3). A
rebuild and a row write exclude each other with a pair of Postgres advisory locks, which is what
makes "no row an earlier version wrote" true during a rolling update (R7); a view whose instance
is behind stops its own subscription (R8); and a view is never emptied until the broker has
answered (R9). The rules are one function applied to in-process and discovered components alike
(R10). The wire gains three fields as protocol 1.7, with each side refusing only what the other
would lose silently (R11).

Planning found three things the spec had assumed otherwise.

- **Nothing tells a workload its project.** The operator renders the service name and no project.
  The workload's certificate states both, and is the one statement of identity it cannot have
  written itself, so a deployed service reads its name from there and the operator renders
  nothing new (R1).
- **Rust modules can already read topics**, so Rust needs both declarations or a Rust consumer
  over a topic could never be registered (R12). The spec's FR-005 now says four languages.
- **There is no path from a running workload to `services get`.** The operator asks a pod nothing
  and holds nothing a pod would answer to. Building that path is a decision about who may ask a
  workload what, and it has been moved to a spec of its own; this feature shows a topic source in
  the service's log and its metrics (R17). FR-018 is withdrawn.

And one consequence of a decision the spec had already made, stated here because it is sharp: the
group rename re-reads on upgrade, and for a process-hosted consumer the upgrade that causes it is
the platform's, not the developer's. The upgrading page carries the cut-over recipe (R14), and the
rename is never released without the start position that recipe needs.

Analysis added one rule: `local` is a reserved project id, so that a deployed service and a named
local run can never produce one group name (R18).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`sdk`, `runtime`, `testkit`, `sidecar`,
`controlplane-api`, `cli`); Python ≥ 3.12 (`sdks/python`); TypeScript on Node ≥ 22
(`sdks/typescript`); Rust, edition 2024, target `wasm32-unknown-unknown` (`sdks/rust`); protobuf
(`protocol`)

**Primary Dependencies**: none added, in any language. `pekko-connectors-kafka` 1.2.0, already in
`runtime`, has the partition-assignment handler and the restricted consumer the start position
needs (R3). The Kafka client it brings is used directly for one read-only call
(`earliestRetained`).

**Storage**: the service's own Postgres gains one table, `ankka_view_versions`, created by the
runtime beside the view tables and only for a service with a topic-sourced view (R6). No file
under `kustomization/components/postgres/ddl/` changes. No stored form changes: view rows, the
journal and durable state are as they were. On the broker, new group ids and a committed offset
per assigned partition from the moment of assignment.

**Testing**: munit — pure suites in `runtime` (`ConsumerGroupsSuite`, `TopicSourceRulesSuite`,
`ServiceIdentitySuite`, `InMemoryBrokerSuite`, `MetricsSuite`); `testkit` with Postgres and the
in-memory broker (`TopicSourceSuite`, `ViewVersionSuite`); `testkit`'s `KafkaSuite` on a real
broker for everything a fake cannot show; `sidecar` (`ProtocolSuite`,
`RemoteProjectionSuite`, `WasmHostSuite`, `ConformanceSuite`); `controlplane-api`
(`DescriptorSuite`, `CompatibilitySuite`); `controlplane`'s `ReservedProjectIdsSuite`; `cli`
template suites. pytest with mypy strict; Node's
test runner with `tsc`; `cargo test` with clippy; each SDK's conformance run; the docs build; the
bdd checker. **No new k3s suite** (R16).

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, or as a
WebAssembly module — against Kafka reached through `ANKKA_KAFKA_BOOTSTRAP_SERVERS`.

**Project Type**: platform libraries and a protocol (the runtime, the sidecar, four SDKs), CLI
templates, documentation

**Performance Goals**: a topic-sourced view's row write becomes two statements in one transaction
where it was one (R7); nothing else on a message's path changes. A rebuild's truncation is one
statement whatever the table's size; its duration is the topic's. `sbt -Dankka.cluster.tests=off
test` grows by about a minute, most of it `KafkaSuite`.

**Constraints**: a view's default start stays `earliest`; version 1 and no version give the name
the spec states; entity-sourced views and consumers are untouched, their daemon and projection
names included; the operator renders nothing new and gains one reserved project id; the CRD and
the control plane's routes do not change; the three SDK
copies of `protocol/` stay identical to the canonical one; a 1.6 SDK keeps running on a 1.7
runtime; nothing unbounded is interned into the recorder's name table; `Test / parallelExecution
:= false` stays; warning-free in every language; no suite binds a fixed port

**Scale/Scope**: about 6 new Scala files and 14 changed across `sdk`, `runtime`, `testkit`,
`sidecar`, `controlplane-api` and `cli`, with 6 new suites and 8 changed; 1 protocol file and its
three copies; per SDK about 1 new source file, 4 changed, 1 new test file and the conformance
service; 2 template files; 7 documentation pages changed and none added; 5 feature files holding 40
scenarios and a glossary, already written

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | a start position and a version are values on a descriptor; declaring them reads nothing and subscribes to nothing |
| Where two interpreters reduce the same thing, they share one function | pass | `ConsumerGroups.name` for every subscription, `TopicSourceRules.problems` for both registries (R10), one guarded write for the in-process and the remote view handler (R7) |
| Module dependency direction | pass | `StartFrom` in `sdk` beside `ChangeSource`; identity, groups, rules and versions in `runtime`; `sidecar` translates; `controlplane-api` gains one reserved variable name, one reserved project id and a version number; `operator` gains the same reserved id and renders nothing new; `crd` does not change |
| `runtime` never sees the generated protocol | pass | `RemoteSource.Topic` and the remote descriptors carry plain values; `Discovery` maps the new fields |
| No classpath scanning; explicit registration | pass | no new component kind; the rules run in `ComponentRegistry.validate`, where an unregistered or mis-declared component already fails |
| Wire names are a versioning boundary | pass | group names are a contract with a test for distinctness and prefix (R2); the proto fields are numbered additions; the protocol change is a minor by its own rule (R11) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| Cluster formation and zero trust are overlay properties | pass | identity is read per mode exactly as formation is (R1); no port, policy or certificate is added |
| The Kubernetes overlay fails loudly on what it needs | pass | a deployed workload with a topic source and no identity in its certificate is refused at startup, naming it; it does not fall back to a local name |
| Schema is additive within a supported range | pass | one new table, created `IF NOT EXISTS`; nothing dropped or altered (R6) |
| Never touch `ActorContext` from a `Future` callback | pass | the rebuild and the guarded write run on the handler's futures with what they need captured at construction, as `ViewEventHandler` does |
| Never intern anything unbounded into the recorder's name table | pass | the metric series are written from a list of declared components, not through the recorder (contracts/topic-sources.md) |
| Tests are serialised; a test never binds a fixed port | pass | nothing changes in `build.sbt`'s test settings; brokers are in memory or the suite's own container |
| A test never names an image by a literal tag | pass | no suite here deploys an image |
| An `eventually` waits for the thing it asserts | pass | suites wait on row counts and on the rows' own version marker, never on "a row exists" |
| Could this check pass while the thing it checks is false? | pass | six claims are marked "verify first" with the test that would show each false; the fencing race is shown failing with the lock removed; row counts alone are not used where a re-read would give the same count |
| Each acceptance scenario ends as a test that fails without the feature | pass | 40 scenarios in `features/`, each named by the spec. 37 end as cases named for them, written before the code they hold. The 3 documentation scenarios are read off the pages: only the configuration table is checked by the build, and a test that looked for their sentences would be the check that finds a string somewhere |
| Docs: pages stand alone, samples from tested code, reference facts generated | pass | no page added; `ankka.service.name` enters the generated table and the coverage check forces its prose; samples are marked regions in `TopicSourceSuite` |
| Keep the Justfile thin; every tracked file claimed by a CI path filter | pass | no recipe added; `features/` and `GLOSSARY.md` are new at the root and need a filter or an `unchecked` entry, or `.github/ci-coverage.py` fails the build |

**Violations to justify**: none against these principles. What widens the platform's surface is
under *Complexity Tracking*.

**Re-check after Phase 1**: unchanged. The design added no module, no dependency and no port.

## Project Structure

### Documentation (this feature)

```text
specs/024-replayable-topics/
├── plan.md              # this file
├── research.md          # R1–R17: decisions with file-level evidence; six things to verify first
├── data-model.md        # StartFrom, the changed descriptors, ServiceIdentity, the status list, ankka_view_versions
├── quickstart.md        # the validation runs, by user story
├── contracts/
│   ├── group-names.md       # the three forms, the version's place, the properties a test holds, where identity comes from
│   ├── topic-sources.md     # rules T1–T5, start position, version, rebuild, the guarded write, log lines L1–L5, metrics
│   ├── subscriber.md        # MessageSubscriber, what an implementation owes, Kafka, InMemoryBroker
│   ├── protocol.md          # the wire at 1.7, what is refused at discovery, compatibility both ways
│   ├── scala-api.md
│   └── sdk-apis.md          # Python, TypeScript, Rust
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
features/topics/{groups,start-position,versions,status}.feature    # written; 37 scenarios
features/documentation/topic-sources.feature                        # written; 3 scenarios
GLOSSARY.md                                                         # written; every term still proposed

protocol/
├── src/main/protobuf/ankka/protocol/v1/discovery.proto    # Source.start_from, StartFrom, two version fields
├── README.md                                              # version 1.7
└── WASM-ABI.md                                            # one sentence

modules/sdk/src/main/scala/…/sdk/
├── ChangeSource.scala                   # StartFrom; Topic.startFrom; the three-argument fromTopic
├── View.scala                           # Companion.version; ViewDescriptor.version
└── Consumer.scala                       # Companion.version; ConsumerDescriptor.version

modules/runtime/src/main/
├── resources/reference.conf             # ankka.service.name
└── scala/…/runtime/
    ├── ServiceIdentity.scala            # new: the three shapes; from certificate, from configuration
    ├── ConsumerGroups.scala             # new: the one function
    ├── TopicSourceRules.scala           # new: T1–T5
    ├── ViewVersions.scala               # new: the table, the two locks, rebuild, the guarded write
    ├── TopicSources.scala               # new: the status list, as an extension
    ├── Ankka.scala                      # identity resolved and carried; rules in ComponentRegistry.validate
    ├── Database.scala                   # a transaction that returns what it wrote
    ├── MessageSubscriber.scala          # the new interface; InMemoryBroker's groups, positions, clock
    ├── Kafka.scala                      # the assignment handler; earliestRetained; Subscribed
    ├── ProjectionRuntime.scala          # groups from ConsumerGroups; the view's start sequence; the version table
    ├── TopicHandlers.scala              # the guarded write; stopping when behind
    ├── ObservabilityRoute.scala         # two series
    ├── ObservabilityEndpoint.scala      # topicSources in /observability/service
    └── remote/{RemoteDescriptors,RemoteProjection,Conversation}.scala
                                         # Topic.startFrom, version; the same guarded write; 1.7

modules/testkit/src/main/scala/…/testkit/AnkkaTestKit.scala       # identity
sidecar/src/main/scala/…/sidecar/Discovery.scala                   # the new fields; the rules; 1.7
controlplane-api/…/descriptors.scala                               # ANKKA_SERVICE_NAME refused; `local` reserved
operator/…/Names.scala                                             # `local` reserved, as the control plane's list
controlplane-api/…/Compatibility.scala                             # 1.7

cli/src/main/templates/common/docker-compose.yml                   # ANKKA_SERVICE_NAME on sidecar and runtime
ankka.g8/src/main/g8/src/main/resources/application.conf           # ankka.service.name

sdks/python/      src/ankka/{view,consumer,graph,service}.py, src/ankka/start_from.py (new),
                  proto/ (copied), examples/shopping_cart/conformance.py
sdks/typescript/  src/{view,consumer,graph,service,spec,index}.ts, src/startFrom.ts (new),
                  proto/ (copied), examples/shopping-cart/conformance.ts
sdks/rust/        ankka/src/components/{view,consumer}.rs, ankka/src/{graph,service,prelude}.rs,
                  ankka/src/start_from.rs (new), ankka/protocol/ (copied),
                  examples/shopping-cart/src/conformance.rs

sidecar/src/test/scala/…/conformance/     # the topic pre-loaded; two components; three cases

docs/build/{topics,views,consumers}.md, docs/reference/{limitations,configuration}.md,
docs/deploy/upgrading.md, docs/concepts/polyglot.md      # changed; none added
```

**Structure Decision**: the existing modules, with no new one. Everything a topic source is — its
name, its rules, its version, its status — is in `runtime`, in five small files beside the code
that already subscribes, so that `ProjectionRuntime` gains calls and not paragraphs. `crd`,
`controlplane` and `console` are not touched; `operator` gains one string in a set.

## Slices, in the order they are built, and what is released

Built in four slices, each ending green; released once.

1. **Groups of their own** (User Story 1). `ServiceIdentity`, `ConsumerGroups`, the reserved
   variable and project id, the templates, and `ProjectionRuntime` calling the function — with the
   subscriber's interface as it is today. A complete fix for the defect, and mergeable by itself.
2. **A start position** (User Story 2). `StartFrom`, the new subscriber interface, the Kafka
   assignment handler, `InMemoryBroker`'s groups, rules T1 to T3, protocol 1.7 with every new
   field, the four SDKs, the conformance cases.
3. **A version** (User Story 3). The table, the locks, rebuild, the guarded write, behind, rules
   T4 and T5, the SDKs' version declarations, the log lines and the metrics.
4. **The documentation** (User Story 4), written against what 1 to 3 built, with the upgrade
   recipe.

**No release carries slice 1 without slice 2.** The rename restarts every group from its start
position, and until slice 2 a consumer cannot say where that is: every topic consumer on the
platform would be redelivered everything its broker retains, with no way to decline.

**No release carries slice 2 without slice 3.** Protocol 1.7 is written once, in slice 2, with the
version fields in it. A runtime with slice 2 and not slice 3 would be handed a version by a 1.7
SDK and do nothing with it, which is the silent difference the protocol's own rule forbids.

So the feature is one release. The slices are for review and for bisecting, and `main` may hold
slice 1 alone for as long as no tag is cut from it.

## Complexity Tracking

Nothing here violates a principle. These are the places the platform's surface grows, and why
each is the smaller of the options.

| What grows | Why needed | Simpler alternative rejected because |
|-----------|------------|-------------------------------------|
| `MessageSubscriber` changes signature, breaking any third-party implementation | a start position has to reach the broker, and a subscription has to be stoppable | an overload with a default would let an old implementation ignore the start position and start where it always had (R4) |
| A topic-sourced view's write is a transaction of two statements | no row a lower version writes may survive a truncation, during a roll (FR-012) | a conditional write alone loses the race it exists to win (R7) |
| `InMemoryBroker` gains groups, positions and a clock | every fast suite and every conformance case reads a topic through it | left as it is, none of this feature could be tested without a container, and conformance could not test it at all (R5) |
| A table the DDL directory does not list | the recorded version must survive a restart and be shared by instances | the DDL directory reaches a local database once, when its volume is new (R6) |
| The 1.7 runtime accepts a 1.6 consumer with no start position | a running service must be able to restart after a platform upgrade | refusing it leaves no order in which platform and service can be upgraded (R11) |
| Two shapes for a versioned group name | the unqualified form must stay exactly as it is for a project that states no name | one shape would need a character outside the alphabet a broker's tools expect, or a refusal (R2) |
