# Tasks: Recurring Timers — A Timer With a Period, Kept on Cadence

**Input**: Design documents from `/specs/032-recurring-timers/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, the spec makes a failing test the first task, and the plan's
"verify first" list turns each fact read from code into a test before the code that relies on it.
The scenarios are in `features/timers/` and `features/documentation/timers.feature`; where a task
says "case", it means a `test(...)` in the named suite (or its equivalent in the SDK's test
runner).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a handler that sets its own timer again is not deleted by the sweeper), US2
  (a timer with a period fires on cadence), US3 (a recurring timer survives restarts, failures and
  upgrades without losing its cadence)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `SDK`/`SDKT` =
`modules/sdk/src/{main,test}/scala/…/sdk`; `RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`;
`TK`/`TKT` = `modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `DDL` =
`kustomization/components/postgres/ddl`; `PY` = `sdks/python`; `TS` = `sdks/typescript`; `RS` =
`sdks/rust`; `DOCS` = `docs`; `SKILL` = `tools/docs/skill`. "R*n*" is a section of `research.md`;
"V*n*" an item of its *Verify first* list; a contract is named by its file under `contracts/`.

The branch `032-recurring-timers` exists, in the worktree `.claude/worktrees/032-recurring-timers`.
Every `sbt` command below takes `-Dankka.cluster.tests=off`; no task needs a k3s suite. A feature
suite's file argument is relative to its module, so `features/timers/x.feature` is
`"../../features/timers/x.feature"` from `testkit`.

---

## Phase 1: Setup — the fixtures and steps every timer scenario shares

**Purpose**: a timed action whose handlers do what a scenario says, and the step definitions for
what timers already do, so the defect can be shown before anything is changed.

- [X] T001 Create `TKT/timers/ScriptedTimers.scala`: a timed action whose behaviour is a per-scenario script. `final class ScriptedTimers(context, script)` with a factory `ScriptedTimers.companion(componentId: String, script: TimerScript)` returning a `TimedAction.Companion` that declares the handlers the features name (`nudge` for `reminders`; `sweep`, `sweep-once`, `sweep-all`, `sweep-deep` for `cleanup`), each taking a `String` input. `TimerScript` holds, per handler name, a behaviour — `Done`, `Fails` (always), `FailsThenDone(n)`, `Takes(duration)`, `SetsAgain(name, delay)` (calls `createSingleTimer` under `name` for the same handler), `Cancels(name)`, `SetsRecurringOnce(name, target, period)` (the first run only) — and a thread-safe record of every run: handler, input, `previousAttempts`, the wall-clock instant it ran. The scheduler reaches the action through its companion's `create` closure, as `ConformanceReference`'s `Reminder` does (`CONF/ConformanceReference.scala:344-356`). Do not record a due time yet: `TimedActionContext.dueTime` does not exist until T013.
- [X] T002 Create `TKT/timers/TimerSteps.scala` (`abstract class TimerSteps(feature: String) extends GherkinSuite(feature) with LogCapturing`) and `TKT/timers/TimerFeatures.scala` (`final class TimerFeatures extends TimerSteps("../../features/timers/timers.feature")`). Each scenario starts its own `AnkkaTestKit` with a fresh `TimerRuntime(pollInterval = 100.millis)` and stops it afterwards. Define the steps `timers.feature` uses that need nothing new: the service and its timed action; `a handler {string} of {string}` and its `that fails` / `that sets the timer {string} for {string} again, due {string} later` forms; `the timer {string} set for {string}` (a 300 ms delay, name and handler as given); `the timer {string} fires` / `fires and its handler finishes` (wait on the script's record for that timer, not on `exists`); `{string} has no timer {string}` / `has the timer {string}` (`scheduler.exists`); `does not fire again` (no new record within three poll intervals after two seconds); `fires a second time within {string}`; `fires again after a backoff of {string}` (the gap between two runs is at least the backoff and under it plus two seconds); `the handler is then told that the timer has failed {int} time(s)` (`previousAttempts`). Parse quoted durations (`"1 second"`, `"1500 milliseconds"`, `"1 minute"`) with one helper. In `TimerFeatures`, name the two due-time scenarios ("a handler is told the due time of the timer that ran it", "a timer that fires again after a failure is told the same due time") in `ranElsewhere` with the value `"this suite, once a handler is told its due time (T017)"`: they are reported ignored, never passed, and `GherkinSuite` fails the suite when an entry goes stale, so T017 must remove them. Say in the suite's header comment that `ranElsewhere` is used here to mean "not yet", and why that is safe.

**Checkpoint**: `sbt 'testkit/testOnly *TimerFeatures'` runs four scenarios and reports two ignored. Nothing in `main` sources has changed.

---

## Phase 2: User Story 1 — a handler that sets its own timer again is not deleted by the sweeper (Priority: P1) 🎯 MVP

**Goal**: a timer whose handler sets it again under its own name fires again, and the sweeper removes only the row it ran.

**Independent Test**: `sbt 'testkit/testOnly *TimerFeatures *TimerStoreSuite *TimerSuite'`: four scenarios of `timers.feature` green, two ignored, `TimerSuite`'s seven cases unchanged.

### The test, shown failing

- [X] T003 [US1] Run `sbt 'testkit/testOnly *TimerFeatures'` on the unchanged runtime and read the result (V1, SC-001). Expect "a timer is removed once its handler has run" and "a timer whose handler fails is kept and fires again after its backoff" to pass, and "a timer whose handler sets it again fires again" and "a timer its handler set again is kept with the due time the handler gave it" to **fail**, the first because no second run is recorded and the second because the row is gone. Keep the failing output for the commit message. If the two pass, stop: the defect is not real, the spec's assumption is wrong, and T004–T005 are not to be done; report it instead.

### Implementation

- [X] T004 [US1] Cases first, in a new `TKT/timers/TimerStoreSuite.scala` on `AnkkaTestKit` with no `TimerRuntime` registered (the suite drives `TimerStore` and `Database` directly, which `testkit` reaches as `private[ankka]`): an `Instant` read from `due_at` through `Database.query` and bound back with `sql"$instant"` matches its row (V2); a delete matched on the `due_at` read removes the row, and leaves it when `due_at` was rewritten in between; a backoff matched on the `due_at` read does the same; two matched deletes of one row have one effect. Then in `RT/TimerRuntime.scala`: `TimerStore.due` also selects `due_at`; add `TimerStore.deleteRan(name, dueAt)` and give `TimerStore.reschedule` the `due_at` it read, both `WHERE timer_name = … AND due_at = …`; `DueTimer` (`RT/TimerSweeper.scala:294-300`) gains `dueAt: Instant`. `TimerStore.delete(name)` stays as it is: it is the scheduler's cancel.
- [X] T005 [US1] In `RT/TimerSweeper.scala`, use the matched statements in both success paths (`:210`, `:270`), in `drop` (`:288-289`) and in `reschedule` (`:291-292`), and read `due_at` into `DueTimer` in `runBatch`. Then grep the file for `TimerStore.delete(`: no call may remain in the sweeper.
- [X] T006 [US1] Run `sbt 'testkit/testOnly *TimerFeatures *TimerStoreSuite *TimerSuite' 'sidecar/testOnly *RemoteProjectionSuite'`. Expect the two scenarios of T003 green, `TimerSuite`'s seven cases and `RemoteProjectionSuite`'s P5–P7 unchanged (SC-004).

**Checkpoint**: the defect is fixed and nothing else has changed: no schema, no API, no wire. This is mergeable alone.

---

## Phase 3: Foundational — the schema, the cadence and the statements

**Purpose**: what a period is stored as and how a next due is worked out. It blocks US2 and US3; US1 does not need it.

**⚠️ CRITICAL**: a recurring row with a finite `due_at` passes every test that uses only the new runtime and is fired and deleted by the previous one. T011 is the fixture that can see that; T012's cases use it.

- [X] T007 Edit `DDL/30-timers-postgres.sql` per `data-model.md` "The table": keep the `CREATE TABLE IF NOT EXISTS` and its index as they are, and add after them the three `ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS` statements (`period_millis BIGINT`, `fire_at TIMESTAMPTZ`, `due_for TIMESTAMPTZ`) and `CREATE INDEX IF NOT EXISTS ankka_timers_fire_idx ON ankka_timers (fire_at) WHERE fire_at IS NOT NULL`. Extend the header comment: a row whose `due_at` is `'infinity'` is a recurring timer, kept there so that a sweeper from before periods never selects it, and `due_at` must not be "tidied" into a nullable column. No list of DDL files changes (R10); confirm with `sbt 'operator/testOnly *SchemaResourceSuite *CnpgRenderingSuite'`.
- [X] T008 [P] Create `SDK/TimerRules.scala` and `SDKT/TimerRulesSuite.scala`, cases first: `TimerRules.period(name: String, period: FiniteDuration): Either[String, Long]` answers the period in milliseconds, or a message naming the timer and the period given when it is zero, negative, under one millisecond or over 36,500 days (R6). Cases: one millisecond, one hour and exactly 36,500 days accepted; zero, minus one second, 999 microseconds and 36,500 days plus one millisecond refused, each message containing the timer's name.
- [X] T009 [P] Create `RT/Cadence.scala` and `RTT/CadenceSuite.scala`, cases first (R3): `Cadence.next(dueFor: Instant, periodMillis: Long, now: Instant): Instant` and `Cadence.skipped(dueFor, periodMillis, now): Long`. Cases: `now` before `dueFor + period` gives `dueFor + period` and zero skipped; `now` exactly on a cadence point gives the *next* point; one millisecond before a point gives that point; one millisecond after gives the one after; several periods past gives the first point after `now` and the count of periods passed over; a period of one millisecond; a period of 36,500 days, the most `TimerRules` admits, from a due time in this century, with no overflow.
- [X] T010 [P] In `TKT/timers/TimerStoreSuite.scala`, add the driver cases V2 and V3 before any code relies on them, against rows inserted with literal SQL: a row with `due_at = 'infinity'` is selected by a query that names `fire_at` and `due_for` and never `due_at`, and mapped through `Database.query` without the driver being asked to decode `'infinity'`; a `Long` binds to `period_millis` and reads back; a `NULL` `period_millis`, `fire_at` and `due_for` read as absent. If the driver refuses any of these, stop and bring it back to the plan: R1's sentinel depends on it.
- [X] T011 Create `TKT/timers/LegacyTimers.scala` (R11): the five statements of `TimerStore` as they are at tag `v0.10.0` (`git show v0.10.0:modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/TimerRuntime.scala`, lines 107–140), copied verbatim into `LegacyTimers.Store`, with a header naming the tag and the lines; `LegacyTimers.createTable`, the `CREATE TABLE` and index of `v0.10.0`'s `30-timers-postgres.sql`; and `LegacyTimers.sweep(database)(run: DueTimer => Outcome)`, which does what `v0.10.0`'s `Sweep` did with those statements: select what is due, call `run`, delete by name on success, back off by name on failure. Nothing here may import the current `TimerStore`.
- [X] T012 The statements of `data-model.md` "What each statement does", cases first in `TKT/timers/TimerStoreSuite.scala`, then `TimerStore` in `RT/TimerRuntime.scala`. Cases: (a) the one-shot upsert writes `due_for = due_at` and `NULL` in `period_millis` and `fire_at`, over a row of either kind; (b) the recurring upsert writes `due_at = 'infinity'`, and set again with the same component, handler and period keeps `fire_at`, `due_for` and `attempts` and takes the payload, while another period, another handler or a one-shot row underneath replaces all three (R5), and the same upsert run from two connections at once, in either order, leaves one row with the first due of whichever landed first; (c) the due query returns an overdue one-shot and an overdue recurring row, oldest first, with what each needs to be matched on, and not a recurring row whose `fire_at` is in the future; (d) `LegacyTimers.Store.due` returns the one-shot and **not** the recurring row, and returns it once its `due_at` is made finite (V6 — this is the case that shows the fixture can see what it must not); (e) `LegacyTimers.Store.upsert` over a recurring row leaves a row the new due query treats as a one-shot, and `LegacyTimers.Store.delete` and `byName` see a recurring row; (f) the advance and the recurring backoff are matched on `due_at = 'infinity'` and the `due_for` read, and each is a no-op when the row was cancelled, replaced by a one-shot, or given another period in between; two advances of one row have one effect (the singleton handoff); (g) the one-shot backoff writes `due_for` as the due told; (h) V5: on a table made by `LegacyTimers.createTable`, apply the changed `30-timers-postgres.sql` twice while a loop runs `LegacyTimers.Store.due`, and neither stalls nor fails; (i) on a table made by `LegacyTimers.createTable` and *not* altered, a recurring upsert fails with SQLSTATE `42703`; (j) the deferral of R15 writes `fire_at = now + 30 seconds`, leaves `attempts` and `due_for` as they were, and is matched like the advance. `DueTimer` gains `dueFor: Instant` and `period: Option[Long]`.

**Checkpoint**: `sbt 'sdk/testOnly *TimerRulesSuite' 'runtime/testOnly *CadenceSuite' 'testkit/testOnly *TimerStoreSuite *TimerFeatures *TimerSuite'` is green. The sweeper still runs only one-shots, through statements that now write `due_for`.

---

## Phase 4: User Story 2 — a timer with a period fires on cadence (Priority: P1)

**Goal**: a timer set with a delay and a period fires for one due time after another, in Scala, Python, TypeScript and Rust; set again unchanged it keeps its next due; every handler is told the due time it is run for.

**Independent Test**: `sbt 'testkit/testOnly *RecurringTimerFeatures *TimerTestingFeatures *TimerFeatures'` for Scala; `sbt 'sidecar/testOnly *ConformanceSuite -- *timer.*'` and each SDK's `conformance` for the languages.

### Scala: the API, the sweeper and the probe

- [X] T013 [US2] In `SDK/TimedAction.scala`, per `contracts/scala-api.md`: add `createRecurringTimer(name, delay, period, call)` to `TimerScheduler` with the scaladoc of the contract; add `dueTime: java.time.Instant` to `TimedActionContext` and to `SimpleTimedActionContext`. Then make everything compile: grep for `extends TimerScheduler` and `SimpleTimedActionContext(` across the repository and account for each hit.
- [X] T014 [US2] Create `RT/TimerObserver.scala` (`TimerObserver`, `TimerObserver.none`, `FiredTimer` and its `Outcome`, per `contracts/scala-api.md`). In `RT/TimerRuntime.scala`: `TimerRuntime.apply(pollInterval, observer = TimerObserver.none)`, passed to the sweeper; `DatabaseTimerScheduler.createRecurringTimer`, which applies `TimerRules.period` (an `IllegalArgumentException` with its message), the existing name and payload checks, and the recurring upsert with the first due `Instant.now().truncatedTo(MILLIS).plusMillis(delay)`, a delay of zero or less being due at once; `createSingleTimer` truncates its due the same way (R4); and SQLSTATE `42703` from any timer statement becomes an error saying the table lacks the period columns and naming `30-timers-postgres.sql` and the local remedy, as `DatabaseSecretStore` does for `42P01` (`RT/DatabaseSecretStore.scala:54-60`). Add a case to `TimerStoreSuite` for the message, on the unaltered legacy table of T012(i).
- [X] T015 [US2] In `RT/TimerSweeper.scala` (R2, R3, R4): the due a run is told, per `data-model.md` "The due time a handler is told", goes into `SimpleTimedActionContext.dueTime` and, for a remote action, into metadata under a new `TimerSweeper.DueKey = "ankka.due"` as epoch milliseconds in decimal, set where `TimerNameKey` and `AttemptsKey` are (`:189-197`). After a recurring timer's handler succeeds, compute `Cadence.next` with the `now` read after the handler returned, run the advance, and when `Cadence.skipped` is above zero log at info on `ankka.timers` the timer's name and the count. After it fails, run the recurring backoff. A recurring timer whose component or handler this sweeper does not have is **not** removed (R15, FR-015): run the deferral of T012(j), log at warn on `ankka.timers` the timer, its target and that `delete` stops it, and report `Deferred`; a one-shot in that position is dropped with a matched delete, as today. Call the observer after each run with the `FiredTimer`; the call is made from `Sweep`, never from the actor, and an observer that throws is logged and does not fail the batch.
- [X] T016 [US2] Create `TK/TimerProbe.scala` per `contracts/scala-api.md`: `TimerProbe` (a `TimerObserver` that records; `fired(name)`, `dueTimes(name)`, `scheduled(name)`) and `ScheduledTimer`. `scheduled` reads the row through `Database` of the service the kit is running, so it needs the kit: `TimerProbe.scheduled(name)(using AnkkaTestKit)` or a `bind(kit)` call, whichever reads better beside `TimerSuite`'s setup; it must go on working after `restartService()`. For a recurring row it reads `due_for` and `period_millis`; for a one-shot, `due_at` when `attempts = 0`, else `due_for`.
- [X] T017 [US2] Finish `timers.feature`: record `dueTime` in `TKT/timers/ScriptedTimers.scala`'s run record; give `TimerSteps` a `TimerProbe` on its `TimerRuntime`; define the steps of the two due-time scenarios (`the handler is told the due time the timer {string} was set with`: the recorded `dueTime` equals `probe.fired(name).head.dueTime` and is within the 300 ms delay plus 50 ms of when the step set it; `the handler was told the same due time each time`); and remove both entries from `TimerFeatures.ranElsewhere`. `sbt 'testkit/testOnly *TimerFeatures'`: six scenarios, none ignored.
- [X] T018 [US2] Create `TKT/timers/RecurringTimerFeatures.scala` over `recurring-timers.feature` and add its steps to `TimerSteps`: the recurring timer set with a period (delay zero unless the step says `due {string} later`), with `and the value {string}` / `and a value as large as the limit for a timer` (1,024 bytes); `has fired {int} time(s)` (wait on `probe.dueTimes(name).size`); the due-time assertions, all against the probe and exact (`each due time it fired for is one period after the due time before it`, `the next due time of {string} is {string} after the due time it fired for`, `is the one it had before it was set again`, `is the one the handler that set it again gave it`); `a handler of {string} asks whether the timer {string} exists`, `cancels the timer`, `sets the timer … with no period`, `sets the recurring timer … again`; the refusal outline (`the handler is refused`, `the refusal names the timer`); `the handler {string} was given that value each time`; `runs {string} once for each period of {string}`. Sixteen scenarios. Then add one plain `test` in the same file for SC-002: twenty fires at a two-second period with a handler that sleeps 500 ms; the twentieth due is exactly the first plus nineteen periods, **and** the twentieth run's start, from the script's wall-clock record, is no earlier than that due and within the 100 ms poll interval plus 250 ms of it. The second assertion is the one that fails when the sweeper falls behind; the first only when the arithmetic is wrong. It takes forty seconds; say so in its name.
- [X] T019 [P] [US2] Create `TKT/timers/TimerTestingFeatures.scala` over `testing.feature`: its one scenario reads `probe.dueTimes` from a test that started the service with the test kit, which is what the steps already do; the file exists so the scenario is run and named.
- [X] T020 [US2] Show the suite can fail (quickstart 3), one break at a time, each reverted and the revert confirmed with `git diff`: make `Cadence.next` return `now + period` (the cadence scenarios and "however long a handler takes…" go red); make the recurring upsert always write the first due ("set again … keeps its next due time" goes red).

### Protocol 1.12 and the sidecar

- [X] T021 [US2] Edit `protocol/src/main/protobuf/ankka/protocol/v1/client.proto` per `contracts/protocol.md`: the `rpc` and its two messages, with comments saying that both a delay and a period are given, that a refusal is in the reply and never a gRPC status, and that there is no `kind` because a timer's target is a timed action. Update `protocol/README.md` (version `1.12` and what it added; `ankka.due` beside `ankka.timer` and `ankka.attempts` at `:74-77`) and `protocol/WASM-ABI.md` (one row in the imports table; `ankka.due` beside `ankka.now` at `:65-71`). Run the three SDK copy scripts (`cd sdks/python && uv run python scripts/proto.py`; `cd sdks/typescript && npm run proto`; `sdks/rust/scripts/proto.sh`).
- [X] T022 [US2] Bump the version everywhere the previous minor was written (1.6 on the first base, 1.10 after the rebase onto main): `WireProtocol.Version = "1.12"` in `RT/remote/Conversation.scala`; the per-minor comment in `SC/Discovery.scala` (1.12: recurring timers, one call on `Client` and one module import, and the due time in a timed action's metadata); `Protocol.version = ProtocolVersion(1, 12)` and its comment in `API/Compatibility.scala`; the assertions in `APIT/CompatibilitySuite.scala`, `APIT/HostingSuite.scala`, `SCT/ProtocolSuite.scala` (an SDK on 1.6 is admitted) and `SCT/RemoteProjectionSuite.scala` (the metadata string). Then `grep -rn '1\.6' --include='*.scala' --include='*.json' cli console/package/src docs operator` and account for each hit that is the protocol's version. `PROTOCOL_VERSION` in the three SDKs moves in each SDK's own tasks.
- [X] T023 [US2] Cases first, in `SCT/ProtocolSuite.scala` (or a new `SCT/ClientTimersSuite.scala` on `AnkkaTestKit` with a `TimerRuntime` and a `TimerProbe`): through `ClientLogic.scheduleRecurring`, a request is scheduled and `probe.scheduled` shows its period and first due; the same request again keeps the next due; a period of zero answers `Error(BAD_REQUEST)` naming the timer and stores nothing; an empty timer id and a 1,025-byte payload answer `BAD_REQUEST`; with timers not running the answer is `Error(UNAVAILABLE)`. Then add `scheduleRecurring` to `SC/ClientLogic.scala` over `createRecurringTimer`, turning `IllegalArgumentException` into `Error(BAD_REQUEST)` with its message, and the delegating method in `SC/ClientService.scala`, which never fails the gRPC call.
- [X] T024 [US2] Add the import to `SC/wasm/HostImports.scala` with `bytes("schedule_recurring")` in `values` (it stays a `lazy val`), answering `Error(UNAVAILABLE)` before `bind`. First show V4: with the row in `protocol/WASM-ABI.md` from T021 and no import, `SCT/WasmHostSuite.scala`'s import-list case (`:334`) fails. Cases in `WasmHostSuite`: the import answers through a bound `ClientLogic`; unbound it answers `UNAVAILABLE`; a refused period comes back as a reply and the instance is not discarded.
- [X] T025 [P] [US2] Add a case to `SCT/RemoteProjectionSuite.scala` beside P5–P7: a timed action request carries `ankka.due` equal to the timer's due in epoch milliseconds; after a failed attempt the retry carries the same value while `ankka.attempts` has risen.
- [X] T026 [US2] The conformance reference and cases, per `contracts/protocol.md` "Conformance cases". In `CONF/ConformanceReference.scala`: `Reminder` gains `tick(id)`, which records `due:<dueTime.toEpochMilli>` on the `conformance` entity `id`; the endpoint gains `POST /recur/{id}`, `POST /recur/{id}/cancel` and `POST /recur-refused/{id}` (400 with the refusal's message). In `CONF/ConformanceTarget.scala`: each target builds its `TimerRuntime(200.millis, probe)` and exposes the probe. In `CONF/ConformanceSuite.scala`: the five `timer.recurring.*` cases, each with a comment naming the scenario of `features/timers/languages.feature` it is. Update `specs/009-polyglot-runtimes/contracts/conformance.md`'s component, route and case tables. Run `sbt 'sidecar/testOnly *ConformanceSuite -- *timer.*'` against the Scala reference; read the first line of output for what it ran, and expect six cases run and one skipped (`timer.fires-after-process-restart`, as today).

### Python

- [X] T027 [P] [US2] Tests first: in `PY/tests/test_other_kinds.py` (or a new `PY/tests/test_timers.py`), against a fake `Client` service as `test_secrets.py` uses: `schedule_recurring` sends `ScheduleRecurringRequest` with the id, `delay_millis`, `period_millis`, component, name and payload; a period of zero raises `CommandError` with `BAD_REQUEST` naming the timer and sends nothing; an `Error` in the reply raises with its code; gRPC `UNIMPLEMENTED` raises an error naming protocol 1.12 and the runtime's being older; `TimedAction.due_time` is a UTC `datetime` for `ankka.due` and `None` without it; `TimedActionTestKit.of(cls).call(name, input, metadata={…})` runs the handler with that metadata. Then implement per `contracts/sdk-apis.md`: `Timers.schedule_recurring` in `PY/src/ankka/client.py`; `due_time` in `PY/src/ankka/timed_action.py`; the `metadata` argument in `PY/src/ankka/testkit/unit.py`; `PROTOCOL_VERSION = "1.12"` in `PY/src/ankka/service.py`. `uv run pytest -q && uv run mypy`.
- [X] T028 [US2] In `PY/examples/shopping_cart/conformance.py`: `Reminder.tick` recording `due:<ankka.due>`, and the three routes of T026. `ANKKA_CONFORMANCE_ONLY='*timer.*' uv run conformance`; read the first line and the count.

### TypeScript

- [X] T029 [P] [US2] Tests first: in `TS/test/client.test.ts`, beside "timers schedule by class and handler, or by name, and cancel": `scheduleRecurring` by class and handler and by name sends the request of the contract; a period of zero rejects with `BAD_REQUEST` and sends nothing; an `Error` in the reply rejects with its code; `UNIMPLEMENTED` rejects naming protocol 1.12, through the same helper the secret store uses (`TS/src/client.ts:255-262`). In `TS/test/other-kinds.test.ts`: `dueTime` is a `Date` for `ankka.due` passed to `TimedActionTestKit…invoke(action, input, { "ankka.due": "…" })` and `undefined` without it. Then implement per `contracts/sdk-apis.md`: `TimedActionTarget` and `Timers.scheduleRecurring` in `TS/src/client.ts`, exported from `TS/src/index.ts`; `get dueTime()` in `TS/src/timedAction.ts`; `PROTOCOL_VERSION = "1.12"` in `TS/src/spec.ts`. Do not change the test kit's default `ankka.attempts`. `npm run typecheck && npm test`.
- [X] T030 [US2] In `TS/examples/shopping-cart/conformance.ts`: `Reminder.actions.tick` and the three routes. `ANKKA_CONFORMANCE_ONLY='*timer.*' npm run conformance`.

### Rust

- [X] T031 [P] [US2] Tests first, in `RS/ankka/tests/kinds.rs` and `RS/ankka/tests/unit_testkit.rs`, through an installed `NativeHost`: `schedule_recurring` and `schedule_recurring_by_name` hand the host a `ScheduleRecurringRequest` with the fields of the contract; a period of zero is `Err(BadRequest)` naming the timer and reaches no host; an `Error` in the reply is `Err` with its code; `Context::due()` is `Some(Instant)` for `ankka.due` and `None` without; `TimedActionTestKit::<C>::new().with_metadata("ankka.due", "…").fire(name, input)` runs the action with it. Then implement per `contracts/sdk-apis.md`: `Import::ScheduleRecurring`, its `extern` declaration and dispatch in `RS/ankka/src/abi/imports.rs` (the native path goes to the `NativeHost` and panics as `schedule` does without one); the two methods in `RS/ankka/src/client.rs`, decoding `ScheduleRecurringReply` and answering its `Error` as `Err`; `due()` in `RS/ankka/src/context.rs` beside `now()`; `with_metadata` in `RS/ankka/src/testkit/kinds.rs`; `PROTOCOL_VERSION = "1.12"` in `RS/ankka/src/service.rs`. `cargo test --workspace`, then `cargo build -p shopping-cart --release --target wasm32-unknown-unknown` and `wasm-objdump -x` on the module: the example imports `schedule_recurring` only once T032 calls it.
- [X] T032 [US2] In `RS/examples/shopping-cart/src/conformance.rs`: the `tick` action and the three routes. `ANKKA_CONFORMANCE_ONLY='*timer.*' ./conformance.sh`; read both runs' first lines: one says stateless and one says stateful.

**Checkpoint**: a recurring timer fires on cadence in four languages; `languages.feature`'s sixteen rows are the conformance cases of T026 against five targets.

---

## Phase 5: User Story 3 — a recurring timer survives restarts, failures and upgrades (Priority: P2)

**Goal**: a restart, a failure, an outage and a runtime from before periods each leave a recurring timer on its cadence, firing once for whatever it missed.

**Independent Test**: `sbt 'testkit/testOnly *TimerRecoveryFeatures *TimerUpgradeFeatures'`.

- [X] T033 [US3] Add `downFor: FiniteDuration = Duration.Zero` to `AnkkaTestKit.restartService` in `TK/AnkkaTestKit.scala`: after the service has terminated and before it is hosted again, the kit waits that long. Existing callers are unchanged. It is what makes "stops, and starts again later" a real outage rather than a row edited by hand.
- [X] T034 [US3] Create `TKT/timers/TimerRecoveryFeatures.scala` over `restarts-and-failures.feature` and add its steps to `TimerSteps`: `{string} restarts before the next due time of {string}` (`restartService()` straight after a run); `fires for the due time it had before the restart` (the probe's next `dueTime` equals `scheduled(name).dueTime` read before the restart); `{string} stops, and starts again {string} later` (`restartService(downFor = …)`); `its next due time is the first still to come that is a whole number of periods after …` (`scheduled(name).dueTime` is after now, at most one period after now, and differs from the earlier due by a whole number of periods); `does not fire for the due times that passed while …` (no `dueTimes` entry strictly between the two); `the timer fired again after a backoff of {string}, and then of {string}` (gaps between `fired(name)` entries' wall-clock times, from the script's record); `the handler was told that the timer had failed {int} time, and then {int} times` (and, in the same step, that all three runs were told one due time: a recurring timer's retry is for the due that failed); `{string} have passed since the timer {string} first fired` and `has fired no more than {int} times`. Six scenarios. The behaviour is T015's; this task is its proof, and a scenario that fails here is a defect in T015 to fix there.
- [X] T035 [US3] Create `TKT/timers/TimerUpgradeFeatures.scala` over `upgrading.feature` and add its steps: a service "made with a runtime version that has no recurring timers" is `LegacyTimers` over the kit's database with no `TimerRuntime` registered (its sweep run on a 100 ms loop, its handler the scripted one); "is upgraded" stops that loop and registers a real `TimerRuntime`; "a service whose database holds the recurring timer" writes it with the current `DatabaseTimerScheduler`; "the instance with no recurring timers is the one that fires the timers" is the legacy loop running while the current scheduler sets the timer; "an instance with recurring timers becomes the one that fires the timers" stops the loop and starts the `TimerRuntime`. The two scenarios about a handler the firing instance does not have use no legacy code: "the instance without the handler" is a second `Sweep` (`RT/TimerSweeper.scala:97-105`) built over the kit's database with a `cleanup` descriptor that declares no `sweep`, run on a 100 ms loop with no `TimerRuntime` registered, and "an instance with the handler becomes the one that fires the timers" stops that loop and registers the real one; assert from the probe that the first real run was told `previousAttempts == 0`. Six scenarios. Add one plain `test` beside them for the first best-effort case of FR-014: a one-shot written and backed off once by `LegacyTimers` (so `due_for` is `NULL` and `attempts` is 1) is run by the real sweeper, told the `due_at` it read, and after one more failure is told that same value again. "`orders` is ready" is asserted as the legacy sweep completing a batch without error against the new schema; that the previous *binary* starts is T045's by-hand run, and the suite's header says so.
- [X] T036 [US3] Show the upgrade suite can fail (quickstart 3, V6): make the recurring upsert write a finite `due_at` (the first due) in place of `'infinity'`; "an instance from before recurring timers does not fire or remove a recurring timer" goes red because the legacy sweep fires it and deletes it. Revert and confirm with `git diff`.

**Checkpoint**: all thirty-five scenarios of `features/timers/` that `testkit` runs are green, and the remaining four outlines are the conformance cases.

---

## Phase 6: Polish — documentation, the whole build, the runs by hand

- [X] T037 Create `TKT/TimersDocumentationSuite.scala`, four tests named after the scenarios of `features/documentation/timers.feature`, in the shape of `APIT/WebHostingDocumentationSuite.scala` (find `docs/` upward, flatten whitespace, `says(path, statements*)`). Statements to hold `DOCS/build/timers.md` and `DOCS/reference/limitations.md` to: a recurring timer and its period; each next due is the previous due plus the period; it fires until cancelled or replaced; a service may set its recurring timers each time it starts; a handler is told the due time; several missed due times are one fire; a call to be made again and again is a recurring timer, with the page **not** containing advice to schedule the same name again from the handler in order to recur; a failing recurring timer is retried with backoff and continues from the due it failed for; a period is a length of time, not a time of day or a day of the week. Beyond the four scenarios, add a fifth test, named for what it holds, for three statements the analysis added: a recurring timer whose handler the running instance lacks is kept and logged, not removed; a period is at most 36,500 days; and the two retries that are told a best-effort due time during an upgrade. Run it: all five fail, since the pages say none of it yet.
- [X] T038 Add the documentation's Scala sample as one new case in `TKT/TimerSuite.scala`, between `// docs:start recurring` and `// docs:end recurring`: `createRecurringTimer` with a delay and a period, and an assertion on `probe.dueTimes` or `exists`. The seven existing cases are not edited. Add `// docs:start due-time` regions to a handler in `TKT/OrderTimers.scala` only if it can be done without changing what the existing cases observe; otherwise put the handler in the new case's own fixture.
- [X] T039 Write `DOCS/build/timers.md`: a "Recurring timers" section with Scala (included from T038), Python, TypeScript and Rust tabs; a Rust tab beside the other three in "Scheduling and cancelling"; `rust` in the page's `languages`; what a handler is told (`dueTime`, `due_time`, `dueTime`, `due()`, and the `ankka.due` key) beside the existing paragraph on `ankka.timer` and `ankka.attempts`; setting at start and what "unchanged" means; missed periods; failure and backoff; that a runtime from before recurring timers leaves one waiting; that a recurring timer whose handler the running instance does not have is kept, logged every 30 seconds and fires once an instance with the handler runs timers, so a handler removed for good needs its timer cancelled; the period's bounds; the two retries told a best-effort due time during an upgrade from a runtime without recurring timers; "Testing timers" gains `TimerProbe`. State each as a property of the system, with no feature number and no history. Then `just docs-sync`.
- [X] T040 [P] Update the reference pages: `DOCS/reference/scala-sdk.md` (`:182-194`: `createRecurringTimer`, `dueTime`; `:297`: `TimerProbe`), `python-sdk.md` (`:165-176`, `:289`), `typescript-sdk.md` (`:205-216`, `:327`), `rust-sdk.md` (`:200-212`, `:408`, `:480`), each per `contracts/sdk-apis.md`.
- [X] T041 [P] Update `DOCS/reference/sidecar-protocol.md` (`:42` version `1.12`; `:236-238` the `ankka.due` rule; the generated table gains `ScheduleRecurring` through `just docs-sync`, and the coverage check then needs the prose beside it to mention the call) and `DOCS/reference/wasm-abi.md` (`:63`, `:96` the import; `:80` `ankka.due`; `:164` the diagram's alt text).
- [X] T042 [P] Update `DOCS/reference/limitations.md` (a period is a length of time: no calendar, no time zone, no cron expression; a recurring timer fires once for missed periods and never catches up), `DOCS/reference/glossary.md` (recurring timer, period, due time, in the words of `GLOSSARY.md`) and `DOCS/build/testing.md` (`:154`, `:207`, `:537-541`: the probe, and metadata in the unit kits).
- [X] T043 Update the three language skills, `SKILL/ankka-python/SKILL.md` (`:47-48`), `SKILL/ankka-typescript/SKILL.md` (`:53-54`) and `SKILL/ankka-rust/SKILL.md`, to name the recurring call beside `timers.schedule`; then `just docs-sync && just docs`. Check `git status` for the rendered copies under `marketplace/` and `ankka.g8/`: they must change, and only by that command.
- [X] T044 Run `sbt 'testkit/testOnly *TimersDocumentationSuite'` (five green), `just features` (nothing reported for this spec), and add to `CLAUDE.md`: under *Component hosting* or beside it, one paragraph on what a recurring timer is stored as and why; under *Traps*, that a recurring row's `due_at` is `'infinity'` on purpose and a finite one is fired and deleted by a runtime from before periods, and that a new field on an existing protocol message is read by an older runtime as absent, so a behaviour an older runtime must refuse is a new call. Add the timer suites to the *Commands* block only if a line there would be used.
- [X] T045 The whole offline build, and the three runs by hand of quickstart 7: `sbt scalafmtCheckAll scalafmtSbtCheck`; `sbt compile` warning-free; `caffeinate -i sbt -Dankka.cluster.tests=off test`; each SDK's full line from `CLAUDE.md`'s *Commands*; `python3 .github/ci-coverage.py`. Then by hand: `v0.10.0`'s own `TimerSuite` against the new `30-timers-postgres.sql`; a local compose database from before the change, which must answer with the message of T014 and not an SQL error; and a ten-second period on a laptop stopped for a minute, one fire and the skip logged on restart. Record what each printed in the pull request.
  Done: the offline build is PR #86's CI run (green; its first attempt's two failures, a workflow step's stream cut off mid-step and a Kafka leader election racing a new topic, passed on rerun — the first is issue #87). The three runs by hand are recorded in PR #86's description; the second found timers that fire once stopping on a local database from before recurring timers, fixed in this feature.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: nothing before it.
- **US1 (Phase 2)**: needs Phase 1 only. It is the spec's first step and the MVP.
- **Foundational (Phase 3)**: needs Phase 2, because T012 changes the statements T004 introduced.
- **US2 (Phase 4)**: needs Phase 3. T017 finishes `timers.feature`'s two due-time scenarios here; they are User Story 2's, for they need the due time. Inside it: T013 → T014 → T015 → T016 → T017, T018; T021 → T022 → T023 → T024, T026; then the three SDK lines.
- **US3 (Phase 5)**: needs T015 and T016 (the sweeper and the probe) and T011 (the legacy fixture). It does not need the protocol or any SDK.
- **Polish (Phase 6)**: T037–T039 need T016; T040–T043 need Phase 4 whole; T045 needs everything.

### Story dependencies

- US1 stands alone.
- US2 needs US1's matched statements, which it extends.
- US3 needs US2's Scala half (T013–T016), and nothing of its wire half.

### Parallel opportunities

- T008, T009 and T010 together, beside T011.
- T019 beside T018; T025 beside T023–T024.
- After T026: Python (T027–T028), TypeScript (T029–T030) and Rust (T031–T032) are three independent lines.
- US3 (T033–T036) beside US2's wire half (T021–T032), by a second person, once T016 is in.
- T040, T041 and T042 together.

### Parallel example: after the Scala half of User Story 2

```text
Line A: T021 → T022 → T023 → T024 → T026      (protocol 1.12 and the sidecar)
Line B: T033 → T034 → T035 → T036              (User Story 3, on the same Scala half)
then, after Line A:
Line C: T027 → T028      (Python)
Line D: T029 → T030      (TypeScript)
Line E: T031 → T032      (Rust)
```

---

## Implementation Strategy

**MVP**: Phases 1 and 2 — User Story 1. The sweeper removes only the row it ran, so a handler that sets its own timer again is no longer deleted. No schema, no API and no wire change; it is mergeable alone, and the spec asks for it first.

**Then, each a mergeable step**:

1. Phase 3 and the Scala half of US2 (T007–T020): recurring timers for a service written in Scala, with the due time in the handler's context. This is the step that changes the schema.
2. US3 (Phase 5): the proofs for restarts, failures and upgrades. Best merged with step 1 or straight after: step 1 without it is a claim about upgrades with no test.
3. Protocol 1.12 and the sidecar (T021–T026), then each SDK as it is ready.
4. Documentation and the whole build (Phase 6).

## Notes

- A task that says "show it failing" or "run it: … fail" is not done until it has been seen red, and after a deliberate break the revert is confirmed (`git diff`, or a grep for what was pasted).
- Read what a run says it ran. A munit filter needs its leading `*`; a conformance run prints its target, its shape and its count; `GherkinSuite` reports a scenario in `ranElsewhere` as ignored, never passed.
- An `eventually` waits for the thing it asserts: wait on the probe's count of runs for the timer by name, then assert due times, which do not change once recorded. Never wait on `exists` for a recurring timer; it is true throughout.
- Assert due times exactly. They are whole milliseconds and the cadence is arithmetic; a tolerance on a due time hides a drift. Tolerances belong only on wall-clock gaps (a backoff, "within three seconds").
- `LegacyTimers` is `v0.10.0`'s code, not this branch's. Never "fix" it or point it at the current `TimerStore`: its whole value is that it does not know about periods.
