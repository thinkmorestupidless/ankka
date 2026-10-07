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

Recurrence has no support, and the timers page says nothing about it. The only way to recur is
to schedule the next timer from inside the handler. Two things are wrong with that, one a defect
and one a design gap.

The defect: the sweeper's success path deletes by name, unconditionally. A handler that schedules
its next run under its own name writes a new `due_at` onto the same primary key before it
returns. The sweeper then deletes that row. Read from the code, the only way to recur removes
its own next occurrence. Nothing in the test suites exercises a handler that reschedules
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

- **A period is stored with the timer, where an older sweeper does not look.** The schema change
  is additive, so the compatibility rule for DDL holds: a running application on the previous
  version loses nothing it reads. A nullable `period_millis` column on `ankka_timers` alone is not
  enough, though. During a rolling upgrade the cluster singleton stays on the oldest node, so a
  sweeper from before this feature runs timers until the last old instance leaves; it selects
  every row whose `due_at` has passed and deletes it by name on success, which would fire a
  recurring timer once and remove it. A recurring timer is therefore stored so that the previous
  version's due query never returns it. That it holds is this spec's; how is the plan's, which
  keeps it in the same table with the row's `due_at` at `'infinity'` (research R1).
- **A recurring timer is never dropped for a handler the sweeping instance lacks.** The sweeper
  drops a timer whose timed action or handler it does not know, because for a one-shot retrying
  can never succeed. For a recurring timer it can: a deploy that adds a handler sets its timer
  from a new instance while an old instance, whose code has no such handler, still holds the
  sweeper, and a drop there would silently lose a timer set seconds earlier. So a recurring
  timer with an unknown target is left in place and looked at again after the backoff ceiling,
  logged each time, with its attempt count untouched; it runs when an instance that has the
  handler sweeps. The price is that a recurring timer whose handler was really removed is logged
  twice a minute until someone cancels it. A one-shot in that position is dropped, as today.
- **The next due is computed from the previous due.** When a recurring timer's handler succeeds,
  the sweeper sets the timer's next due to the previous due plus the period, not to now plus the
  period. A handler's own duration does not move the cadence. When the previous due plus the
  period has already passed, the missed-period rule below decides.
- **Failure keeps the existing backoff, then the cadence resumes.** A failed run is retried with
  the same backoff a one-shot timer gets. When a run succeeds, the next due is the first cadence
  point after the one that was being retried, or, when that has already passed, the first still
  in the future. The period never shrinks the backoff.
- **A missed period is skipped, never caught up.** When the previous due plus the period is
  already in the past, because the service was down, the handler kept failing, or one run took
  longer than a period, the timer has fired once for the overdue due, and its next due is the
  first cadence point still in the future: the previous due plus the smallest whole number of
  periods that lands after now. One rule for every recurring timer; there is no flag. A service
  that must account for every period, a billing tick say, works out what it missed from what it
  recorded last, which it has to do anyway for delivery that is at least once.
- **Cancel is by name, as today.** `delete(name)` removes a recurring timer. Scheduling a one-shot
  timer under a recurring timer's name replaces it, and the reverse, because the name is the
  primary key and replacement is already the rule.
- **Scheduling the same recurring timer again changes nothing about when it fires.** A service
  schedules its recurring timers where it starts, which is every instance and every deploy, and
  only the Scala SDK has `exists` to guard with. If each of those restarted the wait, a daily
  timer in a service deployed daily would never fire. So scheduling a recurring timer under a
  name that already holds a recurring timer for the same component and handler with the same
  period keeps the next due and the attempt count, and takes the new payload. A different
  period, a different handler, or a one-shot on either side is a replacement, and a replacement
  starts from the delay it was given. No SDK gains `exists` for this.
- **A handler is told the due time it is run for.** The context a timed action's handler
  already reads the timer's name and attempt count from carries the due time too, in Scala and
  through the sidecar protocol and the wasm ABI. It is the due the run is for, not the time the
  sweeper got to it, and a retry after backoff carries the due of the attempt that failed. That
  gives a handler a key to make at-least-once delivery safe to repeat with, shows a handler what
  the missed-period rule skipped, and lets a test assert a cadence from what the runtime recorded
  rather than from when a call happened to arrive. It also means the due a timer is run for is
  kept apart from the time its next retry is due, which the backoff overwrites today.
- **Intervals only.** A period is a duration. Calendar alignment, time zones and cron expressions
  are not in this feature; a service that needs them computes the first delay itself and uses a
  period from there.

What this feature is not: a scheduler with a calendar, a way to run a handler on every instance,
or a change to how a one-shot timer behaves, beyond the sweeper's fix and its handler being told
the due time. A one-shot timer is a recurring timer with no period, and every existing test keeps
passing.

## Clarifications

### Session 2026-10-04

- Q: When a recurring timer's next due is several periods in the past, because the service was
  down or its handler failed for longer than a period, does it fire once and continue from the
  next future cadence point, fire once per missed period, or take a flag on the schedule? → A:
  It fires once and continues from the first cadence point still in the future. One rule for
  every recurring timer, no flag; the same rule covers an outage, a long run of failures and a
  handler that runs for longer than a period (FR-011).
- Q: During a rolling upgrade to the first version with periods, or a rollback across it, an
  instance from before this feature can hold the sweeper, and it would fire a recurring timer
  once and delete its row. What must the platform guarantee? → A: A recurring timer is never
  lost. A runtime from before this feature neither fires nor removes one; it waits until an
  upgraded instance runs timers, and the missed-period rule applies then (FR-007, FR-012).
- Q: A service usually schedules its recurring timers when it starts, on every instance and
  after every deploy, and scheduling a name again replaces the timer and restarts its wait, so a
  daily timer in a service deployed daily would never fire. What does scheduling a recurring
  timer again do? → A: It keeps the cadence if nothing about the schedule changed. Scheduling a
  recurring timer under a name that already holds one with the same component, handler and
  period keeps its next due and takes the new payload; anything else replaces, as today. To
  restart a cadence, delete and schedule (FR-006, FR-013).
- Q: Is a timed action's handler told the due time it is being run for? → A: Yes, in every SDK,
  for one-shot and recurring timers alike, beside the timer's name and its attempt count. A
  retry is told the same due time as the attempt that failed (FR-014).
- Q: When is a recurring timer first due? → A: Scheduling one takes a delay, as a one-shot does,
  and a period, both given. The first due is the time of scheduling plus the delay, and a delay
  of zero is due at once. There is no form that leaves the delay out (FR-002).
- Q: Are due time, period, recurring timer, cancel and backoff the words the living features
  use, and does a timer with no period need a term of its own? → A: All five are accepted as
  written in `GLOSSARY.md`, and a timer with no period has no term: the features say "a timer
  with no period". This spec goes on saying one-shot, cadence and sweeper, which are the
  implementation's words.

Amended in planning, the same day (research R14): the timers page was found to say nothing about
recurring, so the context and FR-010 no longer say it recommends rescheduling from a handler; the
protocol gains a call and not a field (FR-002 and the assumptions); the storage decision names
its mechanism; a due time is a whole number of milliseconds (FR-014); and the edge case about an
old instance writing under a recurring timer's name is answered.

Amended after the consistency analysis, the same day: a recurring timer whose handler the
sweeping instance does not have is kept and not dropped (FR-015, and two scenarios of
`features/timers/upgrading.feature`); FR-014 names the two retries that are told a best-effort
due time during an upgrade; FR-003 and FR-004 defer to FR-011 when periods were missed; a period
has an upper bound and a negative delay a meaning (FR-008, FR-002); the two due-time scenarios of
`timers.feature` are listed under User Story 2, whose work they need; and SC-002 and SC-003 say
what is measured and where.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A handler that reschedules its own name is not deleted by the sweeper (Priority: P1)

A developer does the obvious thing: at the end of a timed action's handler, they schedule the
same timer name again with a delay, intending it to run again. The timer fires once, the handler
schedules it again, and it fires a second time.

**Why this priority**: This is the only way to recur today, and reading the sweeper says it
cannot work. A defect in the path people take is fixed before the feature that replaces it, and
the test that proves it is the first thing written.

**Independent Test**: In the testkit, schedule a timer whose handler calls `createSingleTimer`
under its own name with a short delay and records each run. Assert that a second run is observed
within the delay plus the sweeper's poll interval. Run it before the fix: it must fail. Run it
after: it must pass.

**Acceptance Scenarios**:

- added `features/timers/timers.feature`: a timer whose handler sets it again fires again
- added `features/timers/timers.feature`: a timer is removed once its handler has run
- added `features/timers/timers.feature`: a timer its handler set again is kept with the due time the handler gave it
- added `features/timers/timers.feature`: a timer whose handler fails is kept and fires again after its backoff

---

### User Story 2 - A timer with a period fires on cadence (Priority: P1)

A developer schedules a cleanup under the name `sweep-carts` with a delay and a period of one
hour. The runtime fires it first when the delay has passed, and every hour after that. The
handler takes a few seconds; the next fire is still on the hour from the first due time, not a
few seconds later each time.

**Why this priority**: This is the feature. The domain plan's daily jobs, catalog refreshes and
expiry sweeps are all periods, and every one of them today is either a CronJob outside the
platform or a self-rescheduling handler.

**Independent Test**: In the testkit with a short period, schedule a recurring timer whose handler
records each run's time, and assert the recorded times are spaced by the period from the first
due time, within the sweeper's poll interval, over five runs.

**Acceptance Scenarios**:

- added `features/timers/recurring-timers.feature`: a recurring timer is first due when it was set to be, and then once for each period
- added `features/timers/recurring-timers.feature`: a recurring timer set to be due at once fires at once, and then once for each period
- added `features/timers/recurring-timers.feature`: a recurring timer fires once for each period
- added `features/timers/recurring-timers.feature`: however long a handler takes, the next due time is one period after the due time before it
- added `features/timers/recurring-timers.feature`: a recurring timer's handler is told the due time of each time it fires
- added `features/timers/timers.feature`: a handler is told the due time of the timer that ran it
- added `features/timers/timers.feature`: a timer that fires again after a failure is told the same due time
- added `features/timers/recurring-timers.feature`: a recurring timer exists after it has fired
- added `features/timers/recurring-timers.feature`: a cancelled recurring timer does not fire again
- added `features/timers/recurring-timers.feature`: a recurring timer whose handler cancels it does not fire again
- added `features/timers/recurring-timers.feature`: a timer with no period set under a recurring timer's name replaces it
- added `features/timers/recurring-timers.feature`: a recurring timer set under the name of a timer with no period replaces it
- added `features/timers/recurring-timers.feature`: a recurring timer set again with the same handler and period keeps its next due time
- added `features/timers/recurring-timers.feature`: a recurring timer set again is given the new value the next time it fires
- added `features/timers/recurring-timers.feature`: a recurring timer set again with another handler or another period is replaced
- added `features/timers/recurring-timers.feature`: a period of zero or less is refused
- added `features/timers/recurring-timers.feature`: a recurring timer set by the handler of another has a period of its own
- added `features/timers/recurring-timers.feature`: a recurring timer gives its handler as large a value as any timer may, each time it fires
- added `features/timers/languages.feature`: a recurring timer fires once for each period in every language
- added `features/timers/languages.feature`: a cancelled recurring timer does not fire again in every language
- added `features/timers/languages.feature`: a handler is told the due time of the timer that ran it in every language
- added `features/timers/languages.feature`: a recurring timer set again keeps its next due time in every language
- added `features/timers/testing.feature`: a test reads the due times a recurring timer fired for
- added `features/documentation/timers.feature`: the documentation describes a recurring timer
- added `features/documentation/timers.feature`: the documentation does not tell a handler to set its own timer again
- added `features/documentation/timers.feature`: the documentation says what a period cannot say

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

- added `features/timers/restarts-and-failures.feature`: a recurring timer fires for the due time it had before a restart
- added `features/timers/restarts-and-failures.feature`: a recurring timer whose handler failed takes its next due time from the due time it failed for
- added `features/timers/restarts-and-failures.feature`: a recurring timer whose handler keeps failing fires only after each backoff
- added `features/timers/upgrading.feature`: a timer set before a service was upgraded fires once
- added `features/timers/upgrading.feature`: a service made with an earlier runtime version starts with a database that holds a recurring timer
- added `features/timers/upgrading.feature`: an instance from before recurring timers does not fire or remove a recurring timer
- added `features/timers/upgrading.feature`: a recurring timer kept through an upgrade fires once when an upgraded instance fires the timers
- added `features/timers/upgrading.feature`: a recurring timer whose handler the instance that fires the timers does not have is kept
- added `features/timers/upgrading.feature`: a recurring timer kept for its handler fires once when an instance that has the handler fires the timers
- added `features/documentation/timers.feature`: the documentation says what a recurring timer does after a failure
- added `features/timers/restarts-and-failures.feature`: a recurring timer that missed several periods while its service was not running fires once
- added `features/timers/restarts-and-failures.feature`: a recurring timer whose handler failed for longer than a period does not fire for the due times that passed
- added `features/timers/restarts-and-failures.feature`: a recurring timer whose handler takes longer than a period does not fire for the due times that passed

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
- A deploy adds a handler and sets a recurring timer for it at start. The new instance sets
  the timer; the old instance, which still holds the sweeper, has no such handler. The timer is
  kept and looked at again, and fires once the sweeper is on an instance that has the handler.
- Two instances starting together both schedule the same recurring timer: the first creates it,
  the second finds it unchanged and keeps its next due, in whichever order they land.
- A recurring timer scheduled again, unchanged, while it is waiting out a backoff: the backoff
  due and the attempt count are kept, so restarting a service does not hurry a failing timer.
- During a rolling upgrade, an instance from before this feature schedules or deletes a one-shot
  timer under a name that an upgraded instance has given a recurring timer. A timer is one row
  whichever version wrote it, so the name never holds two: the old instance's delete cancels the
  recurring timer, and its schedule replaces it with the one-shot it asked for (research R1).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The sweeper MUST delete only the row it ran, matched on the timer's name and the
  due time it read, so a row rewritten by the handler under the same name is kept.
- **FR-002**: `TimerScheduler` MUST offer a way to schedule a timer with a delay and a period,
  in Scala and through the sidecar protocol for Python, TypeScript and Rust. Both are given: the
  first due is the time of scheduling plus the delay, a delay of zero or less is due at once, as
  it is for a one-shot, and every later due follows from the period. A runtime too old to keep a
  period MUST refuse the request, never schedule it as a one-shot.
- **FR-003**: A recurring timer's next due MUST be the previous due plus the period, computed
  when the handler succeeds, never from the handler's completion time. When that is not still in
  the future, FR-011 decides.
- **FR-004**: A recurring timer whose handler fails MUST be retried with the existing backoff,
  and after a success the next due MUST be the first cadence point after the due that was being
  retried. When that is not still in the future, FR-011 decides.
- **FR-011**: When the previous due plus the period is not in the future at the moment the
  handler succeeds, the next due MUST be the previous due plus the smallest whole number of
  periods that is in the future, so a recurring timer fires once for any number of missed
  periods and stays on its original cadence. This holds whether the periods were missed because
  the service was not running, the handler failed, or the handler ran for longer than a period.
- **FR-005**: `delete(name)` MUST remove a recurring timer so that it does not fire again.
- **FR-006**: Scheduling a timer under an existing name MUST replace it, whichever of the two
  has a period, except as FR-013 says.
- **FR-013**: Scheduling a recurring timer under a name that already holds a recurring timer for
  the same component and handler with the same period MUST keep its next due and its attempt
  count and MUST take the new payload, in every SDK; the delay given with it is not used. A
  service can therefore schedule its recurring timers every time it starts.
- **FR-007**: The schema change that stores a period MUST be additive, so a timer table written
  before this feature reads unchanged, a one-shot timer stores no period, and a runtime from
  before this feature starts and runs its one-shot timers against the new schema.
- **FR-012**: A runtime from before this feature MUST neither fire nor remove a recurring timer.
  A recurring timer set while such a runtime runs the service's timers, or present when a service
  is rolled back to one, MUST still be there, with its period, when an upgraded instance next
  runs timers, and FR-011 then decides its next due.
- **FR-015**: A recurring timer whose timed action or handler the instance running timers does
  not have MUST NOT be removed. It MUST be looked at again after the backoff ceiling, logged each
  time, with its attempt count not raised, and it MUST run, under FR-011, when an instance that
  has the handler runs timers. A one-shot timer in that position is dropped, as today.
- **FR-008**: A period of zero or less, of under one millisecond, or of more than 36,500 days
  MUST be refused at scheduling time with an error naming the timer.
- **FR-009**: The testkit MUST be able to assert a recurring timer's recorded due times.
- **FR-014**: A timed action's handler MUST be told the due time it is run for, for a one-shot
  and a recurring timer alike, in Scala, Python, TypeScript and Rust. A retry MUST be told the
  due time of the attempt that failed, not the time its backoff ended. A due time is a whole
  number of milliseconds, so the value told equals the value stored in every language. During an
  upgrade from a runtime without this feature, two retries are told a best-effort value instead
  (research R4): a one-shot that had already failed before the upgrade is told the time its
  backoff ended, and a one-shot that an old instance replaced and an old sweeper then backed off
  is told the due of the schedule it replaced. Neither is lost or run twice for it.
- **FR-010**: The timers page MUST document periods and MUST say that a recurring timer, not a
  handler rescheduling its own name, is the way to run something again and again.

### Key Entities

- **Timer**: name, component id, method, payload, due time, attempts, created time, and now
  an optional period. The name is the identity. A timer with a period is stored where a sweeper
  from before this feature does not find it.
- **Period**: a duration carried on the row. Absent for a one-shot timer.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A test that schedules a self-rescheduling timer fails on the current code and passes
  after the sweeper fix.
- **SC-002**: Over twenty fires of a recurring timer with a two-second period and a handler that
  sleeps half a second, the twentieth due is exactly the first due plus nineteen periods, and the
  twentieth run starts within the sweeper's poll interval of that due.
- **SC-003**: A recurring timer scheduled from each of the four SDKs fires on cadence in the
  conformance run for that SDK.
- **SC-004**: Every existing timer test passes unchanged.
- **SC-005**: A service built against the previous runtime starts against a database with the new
  schema that holds a recurring timer, runs its own one-shot timers, and leaves the recurring
  timer as it found it.
- **SC-006**: A recurring timer set during a rolling upgrade, while an instance from the previous
  runtime holds the sweeper, fires for the first time only after an upgraded instance holds it,
  and is not lost.

## Assumptions

- The sweeper's poll interval bounds how late a fire can be, as it does today, and a period is not
  a promise of sub-second precision.
- Only one sweeper runs at a time in steady state, as the cluster singleton guarantees; the
  matched delete is a guard for the handoff moment, not a design for several sweepers.
- The sidecar protocol gains a call for scheduling a recurring timer as a minor version change;
  the existing schedule message is unchanged, and an older SDK goes on scheduling one-shot timers
  with it. A field on the existing message was the first idea and was dropped in planning: an
  older runtime would ignore it and schedule a one-shot. The due time reaches a process and a
  module the way the attempt count does, and an older SDK ignores it.
- The defect in User Story 1 is real as read from `TimerSweeper.scala`; the first task is the test
  that proves it either way.

## Dependencies

- None on other specs. This feature touches the timers DDL, `modules/runtime` timers, the `sdk`
  scheduling API, the test kit, the sidecar and its protocol, the three SDKs, and
  `docs/build/timers.md` with the reference pages beside it.
- Gates the domain plan's stage 3 and later, where the daily jobs, catalog refresh and expiry
  sweeps are periods.

## Open Questions

- Whether the local console should show a timer's period beside its next due. The local console
  lists no timers at all today, so this is a timers listing and not a column; it is left out of
  this feature unless the plan finds it cheap.
