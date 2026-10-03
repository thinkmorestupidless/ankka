# Feature Specification: Recurring Timers — A Timer With a Period, Kept on Cadence

**Feature Branch**: `032-recurring-timers`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "A timer should be able to recur. Today the only way to run something
every hour or every day is for the handler to schedule itself again at the end, which drifts by
however long the handler took and stops silently the first time a run forgets. Add a period to a
timer: schedule it once by name with an interval, and the runtime fires it on that cadence,
computing each next due from the previous due rather than from when the handler finished, until
it is cancelled by name. Before any of that, confirm and fix a defect: the sweeper deletes a
timer by name after its handler succeeds, whatever the row holds by then, which reads as deleting
a schedule the handler itself just wrote under the same name. That is the documented way to
recur, so if it is broken today, fixing it comes first. Intervals only; cron expressions and
calendar alignment are a later feature. Scala, Python, TypeScript and Rust all get the API, since
the timer lives in the runtime."

## Context

A timer in ankka is a call the runtime makes later. A component schedules it by name through
`TimerScheduler` in `modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/TimedAction.scala`,
which has three methods: `createSingleTimer(name, delay, call)`, `delete(name)` and
`exists(name)`. The call is a `DeferredCall` to a timed action handler. The Python SDK's
`client.timers.schedule(timer_id, delay, component_id, name, input)` and the TypeScript SDK's
`timers.schedule(id, Duration, {component, handler}, input)` are the same operation over the
sidecar protocol's `ScheduleRequest`, and the wasm `schedule` import is the same message across
linear memory. The runtime stores the timer as a row in `ankka_timers`, whose DDL is
`kustomization/components/postgres/ddl/30-timers-postgres.sql`: `timer_name` is the primary key,
and `component_id`, `method`, `payload`, `due_at`, `attempts` and `created_at` are the rest.
`DatabaseTimerScheduler` in `modules/runtime/.../runtime/TimerRuntime.scala` writes it with
`ON CONFLICT (timer_name) DO UPDATE`, which is what makes scheduling the same name again a
replacement.

`TimerSweeper.scala` in the same package is a cluster singleton that polls once a second for rows
whose `due_at` has passed, runs each handler, and on success runs `TimerStore.delete(timer.name)`.
On failure, or when the process cannot be reached, it reschedules the same row with backoff from
three seconds doubling to thirty. Delivery is therefore at least once, and a handler that returns
an error for work that no longer applies is retried forever, which the timers page calls the
sharpest edge in the API.

Recurrence has no support. The timers page says to schedule the next timer from inside the
handler. Two things are wrong with that, one a defect and one a design gap.

The defect: the sweeper's success path deletes by name, unconditionally. A handler that schedules
its next run under its own name writes a new `due_at` onto the same primary key before it
returns. The sweeper then deletes that row. Read from the code, the documented way to recur
removes its own next occurrence. Nothing in the test suites exercises a handler that reschedules
its own name, so this has not been observed; the first task of this feature is a test that
schedules a timer whose handler reschedules itself and asserts the second fire happens. If it
fails, the fix is that the sweeper deletes only the row it ran, matched on name and the due time
it read, so a row the handler rewrote survives.

The design gap: even with that fixed, a self-rescheduling handler drifts. It schedules the next
run relative to when it finished, so a handler that takes four seconds every hour runs four
seconds later each hour, and a run that throws before reaching its reschedule ends the series
with no record that it was meant to continue. A period is a property of the schedule, not of the
handler, and the runtime should own it as it owns the delay.

The decisions this feature makes:

- **A period is stored on the row.** A nullable `period_millis` column is added to `ankka_timers`.
  Adding a nullable column is additive, so the compatibility rule for DDL holds: a running
  application on the previous version loses nothing it reads.
- **The next due is computed from the previous due.** When a recurring timer's handler succeeds,
  the sweeper sets `due_at` to the previous `due_at` plus the period, not to now plus the period.
  A handler's own duration does not move the cadence.
- **Failure keeps the existing backoff, then the cadence resumes.** A failed run is retried with
  the same backoff a one-shot timer gets. When a run succeeds, the next due is the first cadence
  point after the one that was being retried. The period never shrinks the backoff.
- **Cancel is by name, as today.** `delete(name)` removes a recurring timer. Scheduling a one-shot
  timer under a recurring timer's name replaces it, and the reverse, because the name is the
  primary key and replacement is already the rule.
- **Intervals only.** A period is a duration. Calendar alignment, time zones and cron expressions
  are not in this feature; a service that needs them computes the first delay itself and uses a
  period from there.

What this feature is not: a scheduler with a calendar, a way to run a handler on every instance,
or a change to how a one-shot timer behaves. A one-shot timer is a recurring timer with no period,
and every existing test keeps passing.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A handler that reschedules its own name is not deleted by the sweeper (Priority: P1)

A developer follows the timers page: at the end of a timed action's handler, they schedule the
same timer name again with a delay, intending it to run again. The timer fires once, the handler
schedules it again, and it fires a second time.

**Why this priority**: This is the documented way to recur today, and reading the sweeper says it
cannot work. A defect in a documented path is fixed before the feature that replaces it, and the
test that proves it is the first thing written.

**Independent Test**: In the testkit, schedule a timer whose handler calls `createSingleTimer`
under its own name with a short delay and records each run. Assert that a second run is observed
within the delay plus the sweeper's poll interval. Run it before the fix: it must fail. Run it
after: it must pass.

**Acceptance Scenarios**:

1. **Given** a timed action whose handler schedules its own timer name again with a one-second
   delay, **When** the timer fires once, **Then** it fires a second time within three seconds,
   and the recorded run count reaches two.
2. **Given** a timer whose handler does not reschedule, **When** it fires and succeeds, **Then**
   its row is gone and it does not fire again.
3. **Given** a timer whose handler rescheduled its own name and then the sweeper's delete ran,
   **When** the row is inspected, **Then** it holds the handler's new due time, not nothing.
4. **Given** a timer whose handler fails, **When** the sweeper reschedules it with backoff,
   **Then** the row holds the backoff due time and an incremented attempt count, as today.

---

### User Story 2 - A timer with a period fires on cadence (Priority: P1)

A developer schedules a cleanup under the name `sweep-stale` with a period of one hour. The
runtime fires it every hour. The handler takes a few seconds; the next fire is still on the hour
from the first due time, not a few seconds later each time.

**Why this priority**: This is the feature. The domain plan's daily jobs, catalog refreshes and
expiry sweeps are all periods, and every one of them today is either a CronJob outside the
platform or a self-rescheduling handler.

**Independent Test**: In the testkit with a short period, schedule a recurring timer whose handler
records each run's time, and assert the recorded times are spaced by the period from the first
due time, within the sweeper's poll interval, over five runs.

**Acceptance Scenarios**:

1. **Given** a timer scheduled with a period of two seconds, **When** five fires are observed,
   **Then** each fire's due time is the first due time plus a whole number of periods, and the
   handler's own duration does not appear in the spacing.
2. **Given** a recurring timer whose handler takes longer than the sweeper's poll interval but
   less than the period, **When** it fires, **Then** the next due is the previous due plus the
   period, not the handler's completion time plus the period.
3. **Given** a recurring timer, **When** `exists(name)` is asked between fires, **Then** it
   answers true.
4. **Given** a recurring timer, **When** `delete(name)` is called, **Then** it does not fire
   again and its row is gone.
5. **Given** a recurring timer, **When** a one-shot timer is scheduled under the same name,
   **Then** the recurring one is replaced: it fires once more at the one-shot's delay and then
   its row is gone.
6. **Given** a Python, TypeScript and Rust service, **When** each schedules a recurring timer
   through its SDK, **Then** each fires on cadence through the sidecar or the wasm host, with the
   same spacing rule.

---

### User Story 3 - A recurring timer survives restarts and failures without losing its cadence (Priority: P2)

The service restarts between two fires. When it comes back, the timer fires at the next due that
was already recorded, and the cadence continues from the original schedule. A run that throws is
retried with backoff; when a retry succeeds, the next due is the next cadence point, not the
retry's time plus the period.

**Why this priority**: A timer that keeps its cadence only while nothing goes wrong is the
self-rescheduling handler with extra steps. The point of the runtime owning the period is that
restarts and failures do not move it.

**Independent Test**: With `AnkkaTestKit`, schedule a recurring timer, call `restartService()`
between fires, and assert the next fire's due is the one recorded before the restart. Separately,
script a handler to fail twice then succeed, and assert the fire after the success is at the next
cadence point from the original due, not from the successful retry.

**Acceptance Scenarios**:

1. **Given** a recurring timer with a recorded next due, **When** the service restarts before
   that due, **Then** the timer fires at that due and continues on the original cadence.
2. **Given** a recurring timer whose handler fails twice and then succeeds, **When** the retries
   run, **Then** the row's attempts count rises on each failure, the backoff delays are the
   existing ones, and the fire after the success is at the first cadence point after the due
   that was being retried.
3. **Given** a recurring timer whose period is shorter than the backoff ceiling and whose handler
   keeps failing, **When** several periods pass, **Then** the sweeper retries with backoff and
   does not fire the timer once per missed period in addition.
4. **Given** a recurring timer whose next due is in the past by several periods because the
   service was down, **When** the service comes back, **Then** [NEEDS CLARIFICATION: it fires
   once and then continues from the next future cadence point, or once per missed period; the
   core document leaves this open and the spec records it as a question below].

---

### Edge Cases

- A period of zero or less is refused at scheduling with an error naming the timer, not stored.
- A payload at the size cap, scheduled with a period, is stored and fires; the period adds no
  bytes to the payload.
- Two instances of a service both hold the sweeper briefly during a singleton handoff; the delete
  matched on name and due time means at most one of them removes the row that ran, and the other
  finds nothing to delete.
- A recurring timer whose handler calls `delete` on its own name mid-run: the row is gone when the
  sweeper's success path runs, and the matched delete affects nothing. The timer does not fire
  again.
- A handler that schedules a different name with a period from inside a recurring timer: both
  timers run on their own cadences.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The sweeper MUST delete only the row it ran, matched on the timer's name and the
  due time it read, so a row rewritten by the handler under the same name is kept.
- **FR-002**: `TimerScheduler` MUST offer a way to schedule a timer with a period, in Scala and
  through the sidecar protocol's schedule message for Python, TypeScript and Rust.
- **FR-003**: A recurring timer's next due MUST be the previous due plus the period, computed
  when the handler succeeds, never from the handler's completion time.
- **FR-004**: A recurring timer whose handler fails MUST be retried with the existing backoff,
  and after a success the next due MUST be the first cadence point after the due that was being
  retried.
- **FR-005**: `delete(name)` MUST remove a recurring timer so that it does not fire again.
- **FR-006**: Scheduling any timer under an existing name MUST replace it, whichever of the two
  has a period.
- **FR-007**: The `ankka_timers` table MUST gain the period as a nullable column, so a journal and
  a timer table written before this feature read unchanged and a one-shot timer stores no period.
- **FR-008**: A period of zero or less MUST be refused at scheduling time with an error naming
  the timer.
- **FR-009**: The testkit MUST be able to assert a recurring timer's recorded due times.
- **FR-010**: The timers page MUST document periods and MUST stop recommending that a handler
  reschedule its own name as the way to recur.

### Key Entities

- **Timer row**: name, component id, method, payload, due time, attempts, created time, and now
  an optional period. The name is the identity.
- **Period**: a duration carried on the row. Absent for a one-shot timer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A test that schedules a self-rescheduling timer fails on the current code and passes
  after the sweeper fix.
- **SC-002**: Over twenty fires of a recurring timer with a two-second period and a handler that
  sleeps half a second, the drift of the last fire from the first due plus nineteen periods is
  under the sweeper's poll interval.
- **SC-003**: A recurring timer scheduled from each of the four SDKs fires on cadence in that
  SDK's integration suite.
- **SC-004**: Every existing timer test passes unchanged.
- **SC-005**: A service built against the previous runtime starts against a database whose
  timers table carries the new column.

## Assumptions

- The sweeper's poll interval bounds how late a fire can be, as it does today, and a period is not
  a promise of sub-second precision.
- Only one sweeper runs at a time in steady state, as the cluster singleton guarantees; the
  matched delete is a guard for the handoff moment, not a design for several sweepers.
- The sidecar protocol's schedule message gains an optional period field as a minor version
  change, and an older SDK that omits it schedules a one-shot timer.
- The defect in User Story 1 is real as read from `TimerSweeper.scala`; the first task is the test
  that proves it either way.

## Dependencies

- None on other specs. This feature touches `modules/runtime` timers, the `sdk` scheduling API,
  the sidecar protocol and the three SDKs, and `docs/build/timers.md`.
- Gates the domain plan's stage 3 and later, where the daily jobs, catalog refresh and expiry
  sweeps are periods.

## Open Questions

- When a recurring timer's next due is several periods in the past after an outage, does it fire
  once and continue from the next future cadence point, or once per missed period? Firing once
  is the safer default for a cleanup; a billing tick may want every period. One rule for all
  timers, or a flag on the schedule.
- Whether the local console should show a timer's period beside its next due.
