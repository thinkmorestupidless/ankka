# Implementation Plan: Recurring Timers — A Timer With a Period, Kept on Cadence

**Branch**: `032-recurring-timers` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/032-recurring-timers/spec.md`

## Summary

A timer can be given a period. It is set once by name with a delay and a period, and the runtime
fires it for one due time after another, each the previous due plus the period, until it is
cancelled or replaced. A due time that has passed is never caught up: after an outage, a run of
failures or a handler that outlasts a period, the timer fires once and continues from the first
cadence point still to come. Setting the same recurring timer again changes nothing about when it
fires, so a service sets its recurring timers every time it starts. Every timed action handler,
one-shot or recurring, is told the due time it is run for. Before any of that, the sweeper stops
deleting a timer its handler has just set again.

Technically: a recurring timer is a row of `ankka_timers` whose `due_at` is `'infinity'`, with its
period, its next run time and the due that run is for in three new nullable columns (R1). That
keeps it out of the previous version's due query, which is what lets it survive a rolling upgrade
and a rollback, and keeps one row per name whatever mix of versions writes it. The sweeper's
delete, backoff and advance are each matched on what it read (R2), which is the fix for the
defect and the guard for a singleton handoff. The next due is one pure function (R3); "set again,
unchanged" is one upsert (R5). Scala gains `createRecurringTimer` and `dueTime` (R6); a process
and a module gain one call and one import as protocol 1.12 and read the due time as metadata (R7,
R8). The test kit gains a probe fed by an observer on the sweeper (R9).

Planning found four things the spec did not have:

- **A period as an optional field on the schedule message would fail silently.** A 1.11 runtime
  would read it as a one-shot. It is a new call, which an older runtime refuses (R7).
- **The edge case clarification handed over closes itself.** With one row per name, an old
  instance that replaces a recurring timer simply makes it a one-shot again; the name never holds
  two timers (R1).
- **Two retried one-shots are told a best-effort due time during an upgrade** (R4). Neither is
  lost or duplicated; the value is informational, and closing the gap needs a database trigger.
- **The timers page never recommended rescheduling from a handler.** The spec's context said it
  did; the defect is real either way, and FR-010 is met by saying what to do instead (R14).

The consistency analysis found one more: **the sweeper drops a timer whose handler it does not
have, and during a deploy that adds a handler the sweeper is on the old code.** A recurring
timer in that position is kept and looked at again (R15, FR-015).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`sdk`, `runtime`, `testkit`, `sidecar`,
`controlplane-api`); Python ≥ 3.12 (`sdks/python`); TypeScript on Node ≥ 22
(`sdks/typescript`); Rust, edition 2024, target `wasm32-unknown-unknown` (`sdks/rust`); protobuf
(`protocol`); SQL (one DDL file)

**Primary Dependencies**: none added, in any language. The database is reached through
`runtime`'s `Database` over the r2dbc pool the journal uses.

**Storage**: Postgres, the existing `ankka_timers` table in each service's own database: three
nullable columns and one partial index, added by idempotent statements in the existing
`30-timers-postgres.sql`. No journal event, snapshot, state or view row changes. Nothing in the
control plane or the operator is stored differently.

**Testing**: munit in `sdk` and `runtime` (pure); `testkit` with Postgres (`TimerStoreSuite`, five
`GherkinSuite`s over `features/timers/`, `TimersDocumentationSuite`, `TimerSuite` unchanged);
`sidecar` (`ProtocolSuite`, `RemoteProjectionSuite`, `WasmHostSuite`, `ConformanceSuite`'s
`timer.recurring.*` against the Scala reference and each SDK); pytest with mypy; Node's test
runner with `tsc`; `cargo test`; the docs build; `just features`. No k3s suite changes.

**Target Platform**: wherever an ankka service runs — in process, behind a sidecar, as a module —
on a developer's machine and in a cluster

**Project Type**: platform libraries and a protocol (four SDKs, the runtime, the sidecar) and
documentation

**Performance Goals**: the sweeper's poll stays two indexed reads capped at the batch size; a
recurring fire costs one update where a one-shot costs one delete. A fire is never earlier than
its due and at most one poll interval later, as today. SC-002: over twenty fires at a two-second
period with a half-second handler, the twentieth due is exactly the first plus nineteen periods
and the twentieth run starts within a poll interval of it.

**Constraints**: a runtime from before this feature neither fires nor removes a recurring timer;
one row per name in any mix of versions; a one-shot behaves as it does today except that the
sweeper no longer deletes a row its handler rewrote; every existing timer test passes unchanged; a
1.11 process or module runs unchanged; the schema change is additive and safe to apply on every
start while old instances run; the three SDK copies of `protocol/` identical to the canonical one;
`Test / parallelExecution := false` stays; warning-free; no suite binds a fixed port

**Scale/Scope**: about 5 new Scala source files and 12 changed across five modules, with 9 new
suites or fixtures and 4 changed; 1 DDL file; 2 protocol files and the ABI document; per SDK about
4 changed source files, 2 changed test files and the conformance service; about 10 docs pages and
3 skills, rendered again

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added. Scheduling stays a blocking call on a client, offered where handlers already run sequential code, as `createSingleTimer` is (R6) |
| Where two interpreters reduce the same thing, they share one function | pass | `Cadence.next` is the only arithmetic of a next due (R3); `TimerRules.period` is the one refusal, raised for Scala, a process and a module (R6); `ClientLogic.scheduleRecurring` serves the gRPC service and the module's import (R7) |
| Module dependency direction | pass | trait and rule in `sdk`, store and sweeper in `runtime`, probe in `testkit`; `controlplane-api` changes one constant |
| `runtime` never sees the generated protocol | pass | the sweeper sets a metadata key on the plain `TimedActionRequest`; `sidecar` translates the new message to the `sdk` trait's values |
| No classpath scanning; explicit registration | pass | no component is added |
| Wire names are a versioning boundary | pass | one call, one import and one metadata key are declared strings; the change is a minor by the protocol's own rule, and the shape chosen is the one an older runtime refuses rather than misreads (R7) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| Stored forms stay readable both ways; the schema is additive | pass | nullable columns, `IF NOT EXISTS`; the previous version's five statements run unchanged on the new table and were run (R1, R10) |
| Never touch `ActorContext` from a `Future` callback | pass | the observer is called from `Sweep`, which holds no actor reference, as the recorder already is (R9) |
| Tests are serialised; a test never binds a fixed port; a test names no image by a literal tag | pass | nothing changes in `build.sbt`'s test settings; no new image; no HTTP in the timer suites |
| An `eventually` waits for the thing it asserts | pass | the steps wait on the probe's count of runs for the timer by name, then assert due times, which do not change once recorded |
| Could this check pass while the thing it checks is false? | pass | the defect's test is shown red first (quickstart 2); the legacy fixture is shown able to see a recurring row (R11); three deliberate breaks are named (quickstart 3); the conformance filter's wildcard and first line are named (quickstart 4) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 43 scenario references in the spec, each mapped to a suite in R12 |
| Empty-cluster tests cannot see a leftover object | pass | the schema change is applied to a table created from the previous file while the legacy query runs, and the previous version's own suite is run on the new schema by hand (R10, quickstart 7) |
| Docs: pages stand alone, samples from tested code, generated tables regenerated | pass | R13; no new page, so no `nav` or skill list changes |
| Every tracked file claimed by a CI path filter | pass | every new file falls under a directory a filter already claims; `features/**` and `GLOSSARY.md` are claimed since feature 023 |

**Violations to justify**: none against these principles. Where the plan departs from the spec's
wording is under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no component and no grant.

## Project Structure

### Documentation (this feature)

```text
specs/032-recurring-timers/
├── plan.md              # this file
├── research.md          # R1–R15: decisions with file-level evidence; six things to verify first
├── data-model.md        # the columns, the invariants, every statement and what it is matched on
├── quickstart.md        # the validation runs: pure → the defect → database → SDKs → offline → docs → by hand
├── contracts/
│   ├── scala-api.md         # the scheduler, the context, the rules a service relies on, the probe
│   ├── protocol.md          # the wire at 1.12, the import, the metadata key, the conformance cases
│   └── sdk-apis.md          # Python, TypeScript and Rust
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/timers/` (six files) and
`features/documentation/timers.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
kustomization/components/postgres/ddl/
└── 30-timers-postgres.sql            # + three columns and a partial index, idempotent

modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/
├── TimedAction.scala                 # TimerScheduler.createRecurringTimer; TimedActionContext.dueTime
└── TimerRules.scala                  # new: the period's rule and its message

modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── TimerRuntime.scala                # the scheduler's new method; TimerStore's statements; the observer argument
├── TimerSweeper.scala                # matched delete, backoff and advance; ankka.due; the observer
├── Cadence.scala                     # new: next(dueFor, period, now)
├── TimerObserver.scala               # new: TimerObserver, FiredTimer
└── remote/Conversation.scala         # WireProtocol.Version = "1.12"

modules/testkit/src/
├── main/scala/…/testkit/TimerProbe.scala          # new: the recording observer and the row reader
├── main/scala/…/testkit/AnkkaTestKit.scala        # restartService(downFor): a restart that stays down
└── test/scala/…/testkit/
    ├── timers/TimerSteps.scala                    # new: the steps of features/timers/
    ├── timers/{Timer,RecurringTimer,TimerRecovery,TimerTesting,TimerUpgrade}Features.scala   # new
    ├── timers/LegacyTimers.scala                  # new: v0.10.0's five statements and its sweep
    ├── timers/TimerStoreSuite.scala               # new: the data model's statements, one by one
    ├── TimersDocumentationSuite.scala             # new
    └── TimerSuite.scala                           # one docs sample region added; cases unchanged

protocol/
├── src/main/protobuf/ankka/protocol/v1/client.proto   # ScheduleRecurring and its two messages
├── README.md                                          # 1.12; ankka.due
└── WASM-ABI.md                                        # the schedule_recurring import

sidecar/src/
├── main/scala/…/sidecar/{ClientLogic,ClientService,Discovery}.scala
├── main/scala/…/sidecar/wasm/HostImports.scala
└── test/scala/…/sidecar/{ProtocolSuite,RemoteProjectionSuite,WasmHostSuite}.scala
    └── conformance/{ConformanceSuite,ConformanceReference,ConformanceTarget}.scala

controlplane-api/src/main/scala/…/controlplane/api/Compatibility.scala   # Protocol.version = 1.12

sdks/python/       # client.py, timed_action.py, service.py, testkit/unit.py, tests, the conformance service, the protocol copy
sdks/typescript/   # client.ts, timedAction.ts, spec.ts, tests, the conformance service, the protocol copy
sdks/rust/         # client.rs, abi/imports.rs, context.rs, service.rs, testkit/kinds.rs, tests, the conformance service, the protocol copy

docs/build/{timers,testing}.md
docs/reference/{scala-sdk,python-sdk,typescript-sdk,rust-sdk,sidecar-protocol,wasm-abi,limitations,glossary}.md
tools/docs/skill/{ankka-python,ankka-typescript,ankka-rust}/SKILL.md      # then `just docs-sync` renders marketplace/ and ankka.g8/
CLAUDE.md                                                                 # what a recurring timer is stored as, and two traps
```

**Structure Decision**: no module, project or file list is added. The change follows the path a
timer already takes: the trait in `sdk`, the store and the sweeper in `runtime`, the translation
in `sidecar`, one client method and one accessor in each SDK. The new test sources go in a
`timers` package under `testkit`'s tests, as the topology features have theirs.

## Order of work

The spec fixes the first step; the rest follows what each step needs.

1. **The defect** (User Story 1): the step definitions for `timers.feature`, the self-rescheduling
   scenario shown red, then the matched delete and backoff. Nothing else changes, so this is
   shippable alone.
2. **Storage and the sweeper** (User Story 2, 3): the DDL, `Cadence`, the store's statements under
   `TimerStoreSuite`, the recurring arm of the sweeper, `createRecurringTimer`, `dueTime`, the
   observer and the probe; then `recurring-timers.feature`, `restarts-and-failures.feature` and
   `testing.feature`.
3. **Upgrade**: `LegacyTimers` and `upgrading.feature`.
4. **Protocol 1.12 and the sidecar**: the call, the import, `ankka.due`, the version in its six
   places, the conformance reference and cases.
5. **The three SDKs**, independent of each other once step 4 is in.
6. **Documentation**, then the skills rendered and the by-hand runs.

## Complexity Tracking

No principle is violated. These are the places the plan departs from what the spec said, each
amended in the spec:

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| Three columns and a sentinel in `due_at`, where the spec first said one nullable column | FR-012: an older sweeper must not see a recurring timer, and FR-014 needs the due a run is for kept apart from its retry time | one column leaves the row visible to the previous version's due query, which fires it once and deletes it (R1) |
| A new call and a new import, where the spec assumed an optional field on the schedule message | an older runtime must refuse a recurring timer, not schedule it as a one-shot | an unknown field is ignored without a word, and there is no runtime-to-process message at scheduling time to carry a version check (R7) |
| An observer on the sweeper and a probe in the test kit, where FR-009 says only that the test kit can assert due times | the failure and restart scenarios assert what the sweeper did, which a handler does not see | recording in the handler tests the handler's context and cannot show a backoff or the next due (R9) |
| A due time is whole milliseconds | the value a handler is told must equal the value stored, in four languages | microseconds from the JVM's clock do not survive a millisecond wire value, and "one period after the one before" would be off by a fraction (R4) |
