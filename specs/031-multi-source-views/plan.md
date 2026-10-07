# Implementation Plan: Multi-Source Views — Several Sources, an Explicit Row Key, and a Recursive Read

**Branch**: `031-multi-source-views` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/031-multi-source-views/spec.md`

## Summary

A view gets three things. It declares queries by name, each a SQL statement over its own table
that may be recursive, checked once when the service starts and asked by name with bound values.
It may be a keyed view: several entity sources with a handler each, effects that name every row
they write or delete, and reads of its own rows while it handles a change. And a view that reads
entities may declare a version, as a view that reads a topic already can: a higher one empties the
table and reads every source again from its first event. Scala, Python, TypeScript and Rust get
all three.

Technically: the statement check is a parser's (JSqlParser), run where every other registration
rule runs, for Scala and discovered views alike (R6, R7); the database, not the check, is what
holds a query to reading and ends one that does not end (R8). A keyed view is a second shape
beside the plain one, so the runtime knows how to run it before it starts (R1), and it handles
one change at a time by taking, exclusively, the advisory lock 024 already gave every view (R3).
A rebuild over entities is 024's rebuild with the projection's name carrying the version, which
is to a journal what a new group is to a topic (R10). The wire gains additive fields as protocol
1.13 (R13).

Planning found eight things the spec had assumed otherwise; `research.md` opens with them. The
ones that change what a reader should expect:

- **Explicit row keys are a second shape of view, not an extra effect.** Whether handlers name
  keys decides how the view is run, and that has to be known at startup (R1).
- **No query has a timeout in the database today.** The caller stops waiting and Postgres keeps
  running. Declared queries get a real one; the reads that exist keep the path they have (R8).
- **Every view that reads entities pays two statements per change for the version**, declared or
  not, because the instance that declares none is the one that must stop when another declares a
  higher one (R10).
- **A key value source stays at least once** in a keyed view, as it is in a plain one. FR-016 is
  corrected to say which half each kind of source gets (R4).
- **A version may be raised only once every instance runs this release**: an older instance has
  no guard and cannot be stopped (R12).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `testkit`, `sidecar`,
`controlplane-api`); Python ≥ 3.12 (`sdks/python`); TypeScript on Node ≥ 22 (`sdks/typescript`);
Rust, edition 2024, target `wasm32-unknown-unknown` (`sdks/rust`); protobuf (`protocol`)

**Primary Dependencies**: one added: `com.github.jsqlparser:jsqlparser` 5.x in `runtime`, as a
direct `libraryDependencies` entry so the published POM carries it (R7). Nothing added in any
SDK: no SDK parses a statement. Pekko Projection 1.1.0, already present, has the pause R11 needs.

**Storage**: the service's own Postgres. No table changes shape. `ankka_view_versions`, which 024
created for a service with a topic-sourced view, is now created by a service with any view, and a
row ensured per view (additive). Projection offsets gain names (`data-model.md`); none is moved
or deleted. No file under `kustomization/components/postgres/ddl/` changes.

**Testing**: munit — pure suites in `core` (`RowChangesSuite`) and `runtime` (`QueryCheckSuite`,
`TopicSourceRulesSuite`, `TopologyJsonSuite`, `KeyedViewRulesSuite`, `ViewProjectionsSuite`); `testkit` with Postgres
(`DeclaredQuerySuite`, `KeyedViewSuite`, `EntityViewVersionSuite`, `KeyedViewTestKitSuite`, and
three `GherkinSuite`s over `features/views/`); `sidecar` (`ProtocolSuite`,
`RemoteProjectionSuite`, `WasmHostSuite`, `ConformanceSuite`); `controlplane-api`
(`HostingSuite`). pytest with mypy strict; Node's test runner with `tsc`; `cargo test` with
clippy; each SDK's conformance run; the docs build; the bdd checker. **No new k3s suite**: nothing
here depends on where a service runs.

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, or as a
WebAssembly module.

**Project Type**: platform libraries and a protocol (the runtime, the sidecar, four SDKs), and
documentation

**Performance Goals**: SC-001, a thousand-row subtree in one statement within the ask timeout. A
plain view's change gains two statements in the transaction it has (R10), expected within noise
of `CartViewSuite`'s time on `main` and measured before merge. A keyed view handles one change at
a time by design; its throughput is its handler's, and the documentation says so. A declared
query costs two statements more than its own (`SET TRANSACTION`, `SET LOCAL`).

**Constraints**: a plain view that declares nothing new behaves as before, its projection and
daemon names included (FR-009); the view table's shape does not change (FR-004); a 1.7 SDK keeps
running on a 1.13 runtime; no refused statement reaches the database (SC-002); the three SDK copies
of `protocol/` stay identical to the canonical one; `runtime` names nothing generated; nothing
unbounded is interned into the recorder's name table; `Test / parallelExecution := false` stays;
warning-free in every language; no suite binds a fixed port

**Scale/Scope**: about 9 new Scala files and 16 changed across `core`, `sdk`, `runtime`,
`testkit`, `sidecar` and `controlplane-api`, with 9 new suites and 7 changed; 3 protocol files and
their three copies; per SDK about 2 new source files, 5 changed, 2 new test files and the
conformance service; 10 documentation pages changed and none added, 1 skill; 6 feature files
holding 40 scenarios and a glossary, already written

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | `KeyedViewEffect` is a list of row changes in `core`; a declared query is a name and a string on a descriptor. Building either reads nothing |
| Where two interpreters reduce the same thing, they share one function | pass | `RowChanges.reduce` for both in-process handlers, the remote host and four test kits (R2); `QueryCheck` for Scala and discovered views and for the unit kit (R6, R17); `ViewVersions`' guard for plain, keyed and topic views (R10) |
| Module dependency direction | pass | the effect in `core`; `KeyedView`, `DeclaredQuery` in `sdk`; the check, the lock and the hosts in `runtime`; `sidecar` translates. The parser is `runtime`'s dependency, so neither `core` nor `sdk` nor the CLI's native image gains it |
| `runtime` never sees the generated protocol | pass | `RemoteKeyedViewDescriptor`, `ViewRequest.sourceId` and `ViewOutcome.Rows` are plain values; `Discovery` and `Translate` map the new fields |
| No classpath scanning; explicit registration | pass | no new component kind: a keyed view is a `ComponentKind.View`. Its rules and the statement check run in `ComponentRegistry.validate` |
| Wire names are a versioning boundary | pass | a declared query has a declared name; a keyed view's handlers are named for their sources' component ids; proto fields are numbered additions |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| Virtual threads for anything that blocks | pass | a keyed handler runs on `AnkkaExecutors.virtual` and is handed its change as a value, so nothing thread-bound is read across the hop (R5) |
| Schema is additive within a supported range | pass | one existing table created more widely; nothing dropped or altered |
| Never touch `ActorContext` from a `Future` callback | pass | handlers capture what they need at construction, as `ViewEventHandler` does |
| Never intern anything unbounded into the recorder's name table | pass | query names and source ids are declared; a key, a value and a statement are never interned |
| No secret value in a journal; write the cluster first | n/a | nothing here is a credential or reaches the cluster |
| Tests are serialised; a test never binds a fixed port | pass | nothing changes in `build.sbt`'s test settings |
| An `eventually` waits for the thing it asserts | pass | suites wait on the row's own content (a counter, a version marker), never on "a row exists" |
| Could this check pass while the thing it checks is false? | pass | nine claims, in five decisions (R3, R7, R8, R10, R11), are marked "verify first" with the test that would show each false; `quickstart.md` names the one line to remove to watch each story's check go red; the "does not start" conformance case fails for a target that ignores it; the unit kit fails on an unscripted query |
| Each acceptance scenario ends as a test that fails without the feature | pass | 40 scenarios in `features/`, each named by the spec. 23 run through `GherkinSuite`, 7 as named cases, 5 as conformance cases per language. The 5 documentation scenarios are read off the pages (R18) |
| Docs: pages stand alone, samples from tested code, reference facts generated | pass | no page added; samples are marked regions in the new suites; the protocol table is regenerated |
| Keep the Justfile thin; every tracked file claimed by a CI path filter | pass | no recipe added; every new file is under a directory a filter already claims |

**Violations to justify**: none against these principles. What widens the platform's surface is
under *Complexity Tracking*.

**Re-check after Phase 1**: unchanged. The design added no module and no port, and one dependency,
which is justified below.

## Project Structure

### Documentation (this feature)

```text
specs/031-multi-source-views/
├── plan.md              # this file
├── research.md          # R1–R19: decisions with file-level evidence; nine things to verify first
├── data-model.md        # the declared values, the effect, what is stored, the three states of a view
├── quickstart.md        # the validation runs, by user story, and the line to break for each
├── contracts/
│   ├── declared-queries.md  # rules Q1–Q9, values, the call C1–C7
│   ├── keyed-views.md       # the two shapes, rules K1–K6, the effect E1–E6, one at a time O1–O4
│   ├── rebuild.md           # T4 changed, names no id can spell, startup, the guarded write, L1–L3
│   ├── protocol.md          # the wire at 1.13, what discovery and an SDK refuse, compatibility
│   ├── scala-api.md
│   └── sdk-apis.md          # Python, TypeScript, Rust, and the conformance components
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
features/views/{declared-queries,recursive-queries,several-sources,rebuilding,languages}.feature   # written; 35 scenarios
features/documentation/views.feature                                                               # written; 5 scenarios
features/topics/versions.feature, features/documentation/topic-sources.feature                     # one scenario changed in each
GLOSSARY.md                                                                                        # written; the Views terms settled

protocol/
├── src/main/protobuf/ankka/protocol/v1/{discovery,view,client}.proto
├── README.md                                              # version 1.13
└── WASM-ABI.md                                            # two sentences

modules/core/src/main/scala/…/core/effect/
└── KeyedViewEffect.scala                # new: RowChange, KeyedViewEffect, RowChanges.reduce, the builders

modules/sdk/src/main/scala/…/sdk/
├── DeclaredQuery.scala                  # new
├── KeyedView.scala                      # new: KeyedView, KeyedChange, KeyedSource, Companion, KeyedViewDescriptor
└── View.scala                           # Companion.query, .table; ViewDescriptor.queries; the version's comment

modules/runtime/src/main/scala/…/runtime/
├── QueryCheck.scala                     # new: Q1–Q9, the scanner, CheckedQuery, problems(descriptors)
├── KeyedViewRules.scala                 # new: K1–K5, for both registries
├── ViewProjections.scala                # new: the one function that names a projection
├── KeyedViewHandlers.scala              # new: the event and the state handler; the lock; the virtual hop
├── ViewVersions.scala                   # the guard through a session and a transaction, shared or exclusive
├── ViewClient.scala                     # ask/askAsync; the read-only transaction; forView for a keyed companion
├── Database.scala                       # a read-only transaction with a statement timeout
├── TopicSourceRules.scala               # T4: views accepted
├── DeclaredConnections.scala            # sourcesOf
├── TopologyJson.scala                   # one connection per source
├── ProjectionRuntime.scala              # keyed views started; versions for every view; names carrying the version
├── ProjectionSupport.scala              # the guard before a plain view's write
├── Ankka.scala                          # the two new rule sets in ComponentRegistry.validate
└── remote/{RemoteDescriptors,RemoteProjection,Conversation}.scala
                                         # RemoteKeyedViewDescriptor; the remote keyed handlers; sourceId, Rows; 1.13

modules/testkit/src/main/scala/…/testkit/KeyedViewTestKit.scala     # new
sidecar/src/main/scala/…/sidecar/{Discovery,Translate,ClientLogic}.scala
                                         # sources, declared queries, D1–D5; the new field and case; ask by name
controlplane-api/…/Compatibility.scala                               # 1.13
project/Dependencies.scala, build.sbt                                # jsqlparser, on runtime

sdks/python/      src/ankka/{view,service,server,client,start_from}.py, src/ankka/keyed_view.py (new),
                  src/ankka/effects/keyed_view.py (new), src/ankka/testkit/unit.py, proto/ (copied),
                  examples/shopping_cart/conformance.py
sdks/typescript/  src/{view,service,spec,client,startFrom,index}.ts, src/keyedView.ts (new),
                  src/effects/keyed.ts (new), src/server/stateless.ts, src/testkit/kinds.ts,
                  proto/ (copied), examples/shopping-cart/conformance.ts
sdks/rust/        ankka/src/components/{view,mod}.rs, ankka/src/components/keyed_view.rs (new),
                  ankka/src/effects/keyed_view.rs (new), ankka/src/{client,context,service,start_from,prelude}.rs,
                  ankka/src/testkit/kinds.rs, ankka/protocol/ (copied),
                  examples/shopping-cart/src/conformance.rs

sidecar/src/test/scala/…/conformance/    # two components, the routes, five cases, the broken-query start

docs/build/{views,topics,testing}.md, docs/deploy/upgrading.md,
docs/reference/{limitations,glossary,sidecar-protocol,python-sdk,typescript-sdk,rust-sdk}.md
                                         # changed; none added
tools/docs/skill/ankka-views-consumers/SKILL.md, and its rendered copies
```

**Structure Decision**: the existing modules, with no new one. A keyed view is one file in `sdk`
and one in `runtime`, beside the plain view's, so that neither `View.scala` nor
`ProjectionRuntime.scala` learns a second shape by growing branches; the three sets of rules are a
file each, as `TopicSourceRules` is. `crd`, `operator`, `controlplane`, `cli` and `console` are
not touched.

## Slices, in the order they are built, and what is released

Built in four slices, each ending green; released once.

1. **Declared queries** (User Story 1). The four *verify first* suites' first cases. `QueryCheck`
   and the parser; `DeclaredQuery` on a view; `ask` with the read-only transaction and the
   timeout; the rule set in `validate`; protocol 1.13 **with every field of this feature**; the
   sidecar's `ClientLogic`; the SDKs' declaration and `ask`; the tree conformance cases. A whole
   capability: a tree is walked.
2. **Keyed views** (User Story 2). The effect and its reducer; `KeyedView`; the rules; the two
   handlers and the lock; topology; the remote hosts; the SDKs' keyed view and its reads; the
   test kits; the keyed conformance cases.
3. **Versions for views that read entities** (User Story 3). T4; the versions table for every
   view; names carrying the version; the guard on plain and keyed writes; behind; the SDKs' rule;
   `EntityViewVersionSuite`.
4. **The documentation**, written against what 1 to 3 built, with the upgrade note (R12).

**No release carries slice 1 without slices 2 and 3.** Protocol 1.13 is written once, in slice 1,
with `sources`, `rows` and the widened `version` in it. A runtime with slice 1 alone would be
handed a keyed view or an entity view's version by a 1.13 SDK and refuse it or misreport it, which
is the difference between runtimes of one protocol version that the protocol's own rule forbids.
So the feature is one release; the slices are for review and for bisecting.

## Complexity Tracking

Nothing here violates a principle. These are the places the platform's surface grows, and why
each is the smaller of the options.

| What grows | Why needed | Simpler alternative rejected because |
|-----------|------------|-------------------------------------|
| A dependency, JSqlParser, in `runtime` and so in every service's classpath | the spec requires the check to parse, and a comment or a literal must not fool it | a parser of our own is a second SQL; asking the database to plan the statement sends it to the database, which SC-002 forbids (R7) |
| A second shape of view | how a view is run depends on whether its handlers name keys, and that must be known before it starts | a flag on the one shape can be misdeclared, and its type has one source (R1) |
| A keyed view has one writer | a handler reads rows and writes whole rows; two at once lose an update or miss a row | last write wins was the spec's first answer and was clarified away; revisions per row miss a row that appears after a query (R3) |
| Every entity view's write is guarded, two statements more | an instance at a lower version must stop writing during a roll (FR-012) | guarding only views that declare a version leaves unguarded exactly the instance that must stop (R10) |
| Projection names carry the version, in a second form | a rebuild must read every source from its beginning without touching offsets an older instance is using, and no other view's id may spell the new name | deleting offsets races the older instance, which would then re-read everything with the old handler; appending the version to the id lets `summary-v2` share `summary`'s offsets (R10) |
| A declared query runs in a transaction of its own with two extra statements | "never writes" and "is stopped" are facts about what runs, which only the database can hold | a check on the text cannot see what a function does, and a caller that stops waiting stops nothing (R8) |
| Python views gain a client, for keyed views | a keyed handler reads its own rows | carrying rows in the request would need the runtime to know which rows a handler will want (R15) |
