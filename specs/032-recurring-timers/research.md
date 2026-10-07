# Research: Recurring Timers

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`64102d79`). "Verify first" marks a claim read from code or a
dependency and not yet run; the task that touches it starts with a test that would show it false.
"Run" marks a claim that was run while planning, against a throwaway `postgres:17-alpine`, the
image compose and the test kit use.

The words here are the implementation's: one-shot, cadence, sweeper, row. The living features say
the same things in the glossary's words.

## R1. A recurring timer is a row of `ankka_timers` that an older sweeper cannot see

**Decision**: a recurring timer is stored in `ankka_timers` with `due_at = 'infinity'`. Three
nullable columns are added: `period_millis`, `fire_at` (when the sweeper next runs it) and
`due_for` (the due time the next run is for). A row is recurring exactly when
`due_at = 'infinity'`. [data-model.md](data-model.md) has the columns and every statement.

**Rationale**: the clarified spec requires that a runtime from before this feature neither fires
nor removes a recurring timer (FR-012). What the previous version does to the table is five
statements (`TimerRuntime.scala:107-140`):

- the due query, `… WHERE due_at <= $now ORDER BY due_at LIMIT 100`, which never selects `due_at`
  itself;
- `DELETE … WHERE timer_name = $name`, `SELECT timer_name … WHERE timer_name = $name`;
- the upsert, which sets `component_id`, `method`, `payload`, `due_at` and `attempts = 0`;
- the backoff, which sets `attempts` and `due_at` by name.

With `due_at = 'infinity'` the due query cannot return the row, so an old sweeper never fires it,
never deletes it and never backs it off. The other statements keep meaning what they should: an
old instance's `exists` says a recurring timer exists, its `delete` cancels one, and its upsert
replaces one with the one-shot it asked for, because the upsert writes a finite `due_at` and a
finite `due_at` is what makes a row a one-shot again. The name therefore stays one identity in a
mixed-version cluster, which is the edge case the spec handed to this plan: the name never holds
two timers, because there is only ever one row.

**Run**: on Postgres 17, with the table as it is on `main` and then the three `ADD COLUMN IF NOT
EXISTS` and the index applied twice:

1. the previous version's due query, verbatim, returned an overdue one-shot and not an overdue
   recurring row;
2. `… WHERE timer_name = 'recurring'` found it, and so did the new recurring arm
   `… WHERE due_at = 'infinity' AND fire_at <= now()`;
3. the previous version's upsert, verbatim, over the recurring row left `due_at` finite, and the
   recurring arm no longer returned it;
4. the set-again upsert (R5) kept `due_for` and `attempts` when nothing had changed and replaced
   both when the period had;
5. a delete matched on the `due_at` read left a row whose `due_at` had been rewritten.

**Why the old sweeper is the one that matters**: the sweeper is a Pekko cluster singleton
(`TimerRuntime.scala:48-59`), which lives on the oldest member. In a rolling update the oldest
members are the old pods, so the old code sweeps until the last old pod leaves, and a recurring
timer set by a new pod in that window would be fired once and deleted by name
(`TimerSweeper.scala:210, 270`). A rollback is the same exposure without a time limit. Where the
singleton sits during a roll is Pekko's documented behaviour and was not run here; it is the
motivation, not a premise: the design holds whichever instance sweeps.

**Alternatives considered**:

- *A nullable `period_millis` column and nothing else*, as the spec first said. Rejected by the
  clarification: the old sweeper fires the row and deletes it.
- *A second table, `ankka_recurring_timers`.* An old sweeper cannot see it, but neither can an
  old `delete`, `exists` or upsert, so a name can hold a one-shot in one table and a recurring
  timer in the other, and every operation of the new runtime becomes two statements in a
  transaction with a rule for which row wins. One row has no such rule to get wrong.
- *A far-future finite sentinel* (`9999-12-31`) in place of `'infinity'`. Works the same way;
  `'infinity'` is Postgres's own value for it, compares correctly, sorts last in the existing
  index, and cannot be mistaken for a due time someone chose.
- *Refuse to schedule a recurring timer while an older instance is in the cluster.* Offered in
  clarification and not chosen: it does nothing for a rollback and needs the runtime to know its
  peers' versions.

**Verify first**: the r2dbc driver is never asked to decode `'infinity'`. Every statement names it
as a literal in the SQL text and selects `fire_at` and `due_for`, never `due_at`, for a recurring
row. The first runtime task reads a recurring row through `Database.query` to show it.

## R2. The sweeper changes only the row it read

**Decision**: every statement the sweeper runs after a handler returns is matched on what the
sweeper read, not on the name alone. A one-shot is deleted, and backed off, `WHERE timer_name =
$name AND due_at = $dueAtRead`. A recurring timer is advanced, and backed off, `WHERE timer_name =
$name AND due_at = 'infinity' AND due_for = $dueForRead`. The due query selects the values to
match on.

**Rationale**: this is User Story 1's defect and FR-001. Today the success path is
`TimerStore.delete(timer.name)` (`TimerSweeper.scala:210, 270`) and the upsert a handler runs to
schedule its own name again writes the same primary key (`TimerRuntime.scala:113-115`), so the
handler's new schedule is deleted when the handler returns. No suite has a handler that schedules
its own name: `TimerSuite`'s seven cases and `RemoteProjectionSuite`'s three do not. The first
task is that test, and it must be seen to fail on this base before anything else changes.

The same match is what the singleton handoff needs (two sweepers briefly: the second one's
statement affects nothing) and what a recurring timer needs when its handler cancels it, replaces
it or sets it again: cancelled, the row is gone; replaced by a one-shot, `due_at` is finite;
replaced by another period, `due_for` is new; set again unchanged, `due_for` is the same and the
advance applies, which is right.

The value matched is the one read back from the database, at the database's microsecond
precision, never the one the JVM wrote, so the comparison is exact.

**Alternatives considered**: matching on `xmin` or adding a version column — rejected: `due_at`
is what the spec names, is already selected by the index, and needs no column. Running the handler
inside a transaction that holds the row — rejected: a handler may run for minutes and calls other
components.

**Verify first**: an `Instant` read through `Database.query` and bound back with `sql"$instant"`
matches the row it came from.

## R3. The next due is one pure function, on the sweeper's clock

**Decision**: `Cadence.next(dueFor: Instant, periodMillis: Long, now: Instant): Instant` in
`runtime` returns `dueFor + k × period` for the smallest whole `k ≥ 1` that lands after `now`.
The sweeper calls it when a recurring timer's handler has succeeded, with the JVM's clock, and
writes the result to `due_for` and `fire_at` with `attempts = 0`. When `k > 1` it logs, at info on
`ankka.timers`, the timer's name and how many periods were skipped.

**Rationale**: FR-003, FR-004 and FR-011 are one rule, and the clarification asked for one rule
for an outage, a run of failures and a slow handler. Computed when the handler succeeds, `now` is
after the handler's own duration, so a handler that outlasts a period skips ahead by the same
arithmetic. The sweeper already reads the JVM's clock for the due query and the backoff
(`TimerSweeper.scala:115`, `TimerRuntime.scala:137`); using the database's clock for one of three
would make the three disagree. A pure function is tested without a database, at the boundaries:
`now` exactly on a cadence point, one millisecond either side, and a period of one millisecond.

A failure leaves `due_for` alone and writes `fire_at = now + backoff`, so a period shorter than
the backoff never shortens it (the third scenario of `restarts-and-failures.feature`), and the
retry is for the same due.

**Alternatives considered**: computing `next` in SQL — rejected: the rule would be untested
arithmetic inside a string, and the skipped count could not be logged.

## R4. Due times are whole milliseconds, and the one a run is for is kept apart from the retry time

**Decision**: a due time is truncated to milliseconds where it is created
(`Instant.now().truncatedTo(MILLIS).plusMillis(delay)`), for both kinds. `due_for` holds the due a
run is for: written at scheduling for both kinds, moved only by `Cadence.next` for a recurring
timer, and written by the sweeper's backoff for a one-shot. `TimedActionContext` gains
`dueTime: Instant`; a process and a module read it as metadata `ankka.due`, milliseconds since the
epoch in decimal.

**Rationale**: FR-014 says a handler is told the due it is run for and a retry is told the due of
the attempt that failed. Today the backoff overwrites `due_at` (`TimerRuntime.scala:135-140`), so
after one failure the original due is gone; it needs a column. Milliseconds because the period is
milliseconds, `delay_millis` is milliseconds, and `ankka.now` already reaches a module as epoch
milliseconds (`WasmConversation.scala:84-85`, `sdks/rust/ankka/src/context.rs:172-179`): one
format for both clock values, and the value a handler is told equals the value stored, so "each
due time is one period after the one before" is exact in every SDK.

What a handler is told is in [data-model.md](data-model.md). Two cases are best effort, and the
docs say so:

- a one-shot scheduled and already failed under a runtime from before this feature has no
  `due_for`; its retries are told the `due_at` read, which is a retry time, and the first backoff
  under the new sweeper records it so later retries agree;
- a one-shot that an old instance *replaces* and an old sweeper then backs off keeps the
  `due_for` of the schedule it replaced. Row contents cannot tell that from a legitimate retry.
  It needs an old replace, an old failure and an upgrade finishing inside one backoff.

**Alternatives considered**: a trigger that keeps `due_for` right whatever version writes the row
— it closes the second case and is rejected: the schema has no triggers, it would be logic no
Scala test reads, and the case is a wrong informational value during an upgrade, not a lost or
duplicated timer. RFC 3339 text for `ankka.due` — rejected for the reason above: `ankka.now` is
already epoch milliseconds and the Rust crate parses it with no dependency.

## R5. Setting a recurring timer again is one statement that decides for itself

**Decision**: the recurring upsert keeps `fire_at`, `due_for` and `attempts` when the existing
row is recurring for the same `component_id`, `method` and `period_millis`, and otherwise writes
the first due and zero. `payload` is always taken. It is a single `INSERT … ON CONFLICT … DO
UPDATE` with the condition in `CASE` expressions.

**Rationale**: FR-013. One statement is atomic, so two instances that start together and both set
the timer cannot reset each other in either order, and no instance reads before it writes. The
delay given with an unchanged set is not used, which is what lets a service set its recurring
timers every time it starts. A timer waiting out a backoff keeps its backoff, so a restart does
not hurry a failing handler.

**Run**: step 4 of R1's run.

**Alternatives considered**: read, compare, then write — rejected: a race between two instances.
Adding `exists` to the three SDKs so a service can guard — offered in clarification and not
chosen.

## R6. The Scala API

**Decision**: `TimerScheduler` gains `createRecurringTimer(name, delay, period, call)`.
`TimedActionContext` gains `dueTime`. A period under one millisecond, zero or negative is an
`IllegalArgumentException` naming the timer, thrown before anything is stored, as an empty name
and an oversized payload already are (`TimerRuntime.scala:80-88`). So is a period of more than
36,500 days: a `TIMESTAMPTZ` ends near the year 294,000, and a bound a century wide keeps every
due time, and every sum `Cadence.next` makes, far inside it. A delay of zero or less is due at
once, which is what a one-shot's has always been. Contract:
[contracts/scala-api.md](contracts/scala-api.md).

**Rationale**: the method's name sits beside `createSingleTimer`, whose shape it shares; the
clarification fixed that a delay and a period are both given. `TimerScheduler` is a trait
(`TimedAction.scala:92-110`) with one implementation in the tree; adding an abstract method breaks
an implementation outside it, which before 1.0 is a compile error with an obvious fix, and a
default that threw would hide it until a timer was set.

The validation lives in `sdk` (`TimerRules.period`), so the message is one string whoever raises
it: the runtime's scheduler, and through it the sidecar and the module host.

**Alternatives considered**: an optional `period` parameter on `createSingleTimer` — rejected: a
single timer with a period is not one. An effect — rejected for the reason `createSingleTimer` is
not one: scheduling happens where handlers run blocking sequential code.

## R7. Protocol 1.12: a new call, not a new field

**Decision**: `Client` gains `rpc ScheduleRecurring (ScheduleRecurringRequest) returns
(ScheduleRecurringReply)` and the `ankka1` imports gain `schedule_recurring`. `ScheduleRequest` is
unchanged. The reply carries an `Error` for a refusal. The sweeper sets `ankka.due` on every timed
action request. Contract: [contracts/protocol.md](contracts/protocol.md).

**Rationale**: the spec assumed "the schedule message gains an optional period field". A field is
the one shape that fails silently: a 1.11 runtime reads a request with an unknown field as a
request without it and schedules a one-shot, so a timer meant to recur fires once and nothing
says so. The repository has paid for exactly this before, with `produce_all`, and guards it with
a version in metadata (`RemoteProjection.scala:61`); a scheduling call has no request from the
runtime to carry one. A new call needs no guard: a 1.11 runtime answers gRPC `UNIMPLEMENTED`, which
each SDK already turns into "the runtime is too old for this, it needs protocol X"
(`sdks/typescript/src/client.ts:255-262`, for the secret store), and a module that calls the new
import does not instantiate on a 1.11 runtime while one that does not is unaffected
(`protocol/WASM-ABI.md:144-146`). Adding an rpc is a minor by the protocol's own rule
(`protocol/README.md:21-27`).

The request has no `kind` and no `entity_id`. `ScheduleRequest` has both and the sidecar ignores
them (`ClientLogic.scala:247-263`): only a timed action can be a timer's target, and the sweeper
drops a timer aimed at anything else (`TimerSweeper.scala:153-160`). The new message does not
repeat fields that mean nothing.

The reply carries the refusal because a module's import cannot carry a gRPC status: `schedule`'s
failure is a trap that discards the instance (`HostImports.scala:117-122`), and a period of zero
is a refusal a handler should be able to read. That is the secret store's reply shape, for its
reason.

**Alternatives considered**: the optional field with a version check — rejected: there is no
runtime-to-process message at scheduling time to check against. Echoing the period in the reply so
an SDK can detect an old runtime — rejected: the one-shot is already stored by then.

**Verify first**: `WasmHostSuite` holds the import list to what the runtime provides (`:334`); it
must fail when `schedule_recurring` is in `WASM-ABI.md` and not in `HostImports`.

## R8. The three SDKs

**Decision**: each SDK's timers client gains a recurring schedule that sends `ScheduleRecurring`,
and each timed action can read the due time through one typed accessor over `ankka.due`. Each
unit test kit can set the metadata a timed action is run with, which the Python and Rust kits
cannot today. Contract: [contracts/sdk-apis.md](contracts/sdk-apis.md).

**Rationale**: scheduling is `Timers.schedule` in Python (`client.py:156-185`) and TypeScript
(`client.ts:199-231`) and `Client::schedule` in Rust (`client.rs:193-255`); the recurring form
sits beside each. The timer's name and attempt count reach a handler as raw metadata in all three
(`timed_action.py:25-42`, `timedAction.ts:17-20`, `context.rs:22`), and the due time follows them
there, so the key is the contract. It gets an accessor where the other two have none because it
is the one value that needs parsing into the language's time type; the Rust crate already has the
parse for `ankka.now`.

`TimedActionTestKit.call` in Python runs with empty metadata (`testkit/unit.py:616-630`) and
Rust's `fire` with none (`testkit/kinds.rs:705-710`), so a handler that reads its due time cannot
be unit-tested in either until the kit takes metadata. TypeScript's already does
(`testkit/kinds.ts:373-396`).

Each SDK refuses a period of zero or less before sending, with the runtime's message, and the
runtime refuses it again: an SDK is not the authority, and its check is what makes the refusal
testable without a sidecar.

**Not changed here**, though planning saw them: TypeScript's test kit defaults `ankka.attempts`
to `"1"` where the runtime starts at `0`; no SDK has an accessor for the name or the attempts;
`ScheduleRequest.kind` and `entity_id` are accepted and ignored. SC-004 says every existing timer
test passes unchanged, and each of these is its own change.

## R9. The test kit reads what the sweeper did

**Decision**: `runtime` gains `trait TimerObserver { def fired(timer: FiredTimer): Unit }`, which
`TimerRuntime(pollInterval, observer)` takes and the sweeper calls after each run with the timer's
name, the due it was run for, its attempt count, its outcome and the next due it was given.
`testkit` gains `TimerProbe`, an observer that records, and reads a timer's row.

**Rationale**: FR-009 and `testing.feature`. A test of a cadence has to assert due times, and the
only other source is a handler that records what it was told, which tests the handler's context
and makes every such test write a recording handler. The failure scenarios need more than the
handler sees: the backoff between two runs and the next due after a success. A test already
constructs the `TimerRuntime` it registers (`TimerSuite.scala:15-24`), so handing it an observer
is one argument. The observer is called from the sweeper's `Future`, never the actor, and holds no
`ActorContext`.

`TimerProbe.scheduled(name)` reads the row through `Database`, which `testkit` can reach as it
reaches the rest of `private[ankka]`.

**Alternatives considered**: an always-on in-memory log inside `TimerRuntime` — rejected: state a
production service pays for and never reads. A Scala `TimedActionTestKit` — not needed by any
scenario and not added.

## R10. The schema change reaches every database the way the last one did

**Decision**: the `ALTER TABLE … ADD COLUMN IF NOT EXISTS` statements and the index go in
`30-timers-postgres.sql`, after the `CREATE TABLE`. No file is added, so no list of files changes.
A runtime that meets a table without the columns says so: SQLSTATE `42703` from a timer statement
becomes an error naming the file to apply and the local remedy.

**Rationale**: a deployed service's schema is applied by `schema-init`, which runs every file on
every pod start under an advisory lock (`SchemaInit.scala:86-111`), so the columns exist before
the first new runtime starts and while old pods still run. Adding a nullable column with no
default is a catalogue change, not a rewrite, and the old pods' statements name their columns.
The test kit, the SDK test kits and a generated project's compose copy the same directory.

A local compose database is different: `docker-entrypoint-initdb.d` runs only when the volume is
empty, so a developer's existing database does not gain the columns. The secret store met the
same thing with a missing table and answered with a message (`DatabaseSecretStore.scala:54-60`);
this does the same for a missing column, from the scheduler and from the sweeper's log.

Amended after the runs by hand: as first built, the due query named the new columns, so on such a
table the sweeper failed every poll and timers that fire once stopped too — a feature that worked
before the upgrade, broken by it. Such a table can hold only timers that fire once, so on SQLSTATE
`42703` the sweeper reads and backs them off with the previous release's statements
(`TimerStore.legacyDue`, `legacyReschedule`), the scheduler sets them with `legacyUpsert`, and the
remedy is logged once per process. Only a recurring timer is refused. `TimerStoreSuite` has the three
cases on the previous release's table, the two about timers that fire once seen failing first.

The control plane's own database is the other one that never runs the DDL again: its schema is
applied once, when its CNPG cluster is created. An installation upgraded in place keeps a timers
table without the new columns there. That is harmless only because the control plane registers
no `TimerRuntime`; a control plane that one day sets a timer needs its schema brought forward
first.

**Alternatives considered**: a new file `31-…` — rejected: seven lists name the files. The runtime
applying the `ALTER` itself at start — rejected, as it was for the secrets table: the schema would
have two sources.

## R11. Standing in for the previous runtime

**Decision**: `upgrading.feature` runs against `LegacyTimers`, a test fixture in `testkit` holding
the previous version's five statements verbatim from tag `v0.10.0`, with a sweep that runs them as
the old `Sweep` did: select what is due, call the handler, delete by name or back off by name.

**Rationale**: everything the old runtime does to a timer goes through those statements, so they
are what FR-012 is about, and a fixture runs in the same suite, in seconds. It proves the old
*statements* leave a recurring row alone and still run one-shots on the new schema. It does not
prove the old *binary* starts, which SC-005 also says; that is a run by hand of the released
sample image against a database with the new schema, in [quickstart.md](quickstart.md).

**Alternatives considered**: resolving the released `ankka-runtime` in a test and starting it in
its own class loader — rejected: two Pekko versions' worth of configuration in one JVM to prove
what five statements prove. A k3s rolling-upgrade suite from a released image — rejected for the
cost; the mixed-version window is a property of the table, not of Kubernetes.

**Could this pass while the thing is false?** Yes, if the fixture's statements drift from what
`v0.10.0` ran. The fixture's header names the tag and the lines, and the suite's first case runs
the legacy sweep over a one-shot and a recurring row written by the *new* scheduler: with
`due_at` made finite for the recurring row the case goes red, which is the check that the
statements can see what they must not.

## R12. Where each scenario is tested

Forty-three scenarios; each is a test that fails without the feature.

| Feature file | Scenarios | Suite |
|---|---|---|
| `features/timers/timers.feature` | 6 | `TimerFeatures` (`testkit`, `GherkinSuite` over the one file, `TimerSteps`) |
| `features/timers/recurring-timers.feature` | 16 | `RecurringTimerFeatures` (same steps) |
| `features/timers/restarts-and-failures.feature` | 6 | `TimerRecoveryFeatures` (same steps; restarts through `restartService`) |
| `features/timers/testing.feature` | 1 | `RecurringTimerFeatures`' sibling `TimerTestingFeatures` (same steps) |
| `features/timers/upgrading.feature` | 6 | `TimerUpgradeFeatures` (same steps; `LegacyTimers` for the four about an older runtime, R11, and a second `Sweep` without the handler for the two about an older service, R15) |
| `features/timers/languages.feature` | 4 outlines, 16 rows | `ConformanceSuite` cases `timer.recurring.*`, run against the Scala reference and each SDK; each case is named in the suite beside the scenario it is |
| `features/documentation/timers.feature` | 4 | `TimersDocumentationSuite` (`testkit`), tests named after the scenarios, holding statements to the pages as `WebHostingDocumentationSuite` does |

The first scenario of `timers.feature` to be written is "a timer whose handler sets it again
fires again", against the unchanged runtime, and it is shown red (SC-001).

Below the features: `CadenceSuite` (pure, R3), `TimerRulesSuite` (pure, R6), `TimerStoreSuite`
(`testkit`, the statements of [data-model.md](data-model.md) one by one, including the handoff —
two sweeps over one row, one effect — and two instances setting one recurring timer at once), a new case in `RemoteProjectionSuite` (`ankka.due` reaches a
process and a retry carries the same value), `WasmHostSuite` (the import), each SDK's own tests,
and SC-002's twenty fires as one slow case in `RecurringTimerFeatures`' suite, asserting the
twentieth due exactly and the twentieth run's start against the clock.

The feature files use short real waits (a period of two seconds, a backoff of three and six).
Tests are serialised, so the cost is wall-clock: the five suites together are about three minutes.

## R13. Documentation

**Decision**: `docs/build/timers.md` gains a "Recurring timers" section and a Rust tab beside the
other three in "Scheduling and cancelling"; it says what a handler is told, that a service may set
its recurring timers at start, what happens to missed periods, and that a period is a length of
time. `docs/reference/{scala,python,typescript,rust}-sdk.md`, `sidecar-protocol.md` (1.12,
`ankka.due`; its generated table gains the call through `just docs-sync`), `wasm-abi.md` (the
import), `limitations.md` (no calendar, no cron), `glossary.md` (the three terms) and
`build/testing.md` (`TimerProbe`, metadata in the unit kits) change. The three language skills'
`SKILL.md` name the new call beside `timers.schedule`, and the skills are rendered again.

**Rationale**: FR-010 and `features/documentation/timers.feature`. The Scala sample is included
from `TimerSuite` by `// docs:start`, so it is tested code; the page states behaviour as a
property of the system and carries no feature number.

## R14. What planning corrected in the spec

- **The timers page does not recommend that a handler reschedule its own name.** The spec's
  context said it does; `docs/build/timers.md` says nothing about recurring at all. The defect is
  real whoever recommended the pattern. The spec's sentence is corrected; FR-010's "stop
  recommending" is satisfied by a page that says what to do instead, which the documentation
  scenario asserts.
- **The protocol gains a call, not a field** (R7). The spec's assumption is amended.
- **Storage is three columns and a sentinel, not one column** (R1), which the clarification
  already required and the spec left to this plan.
- **A due time is whole milliseconds** (R4), which the spec did not say and the handler-facing
  value needs.

## R15. A recurring timer waits for a handler the sweeper does not have

**Decision**: when the sweeper finds no registered timed action, or no handler of that name, for
a *recurring* timer, it does not delete the row. It writes `fire_at = now + 30 seconds`, leaves
`attempts` and `due_for` alone, logs at warn on `ankka.timers` naming the timer, the target and
`delete` as the way to stop it, and reports `Deferred` to the observer. A one-shot in that
position is dropped, as today.

**Rationale**: the sweeper drops such a timer because retrying "can never succeed"
(`TimerSweeper.scala:128-136, 163-170`). For a recurring timer set at start that is false in the
commonest deploy there is: version two of a service adds a handler and sets its timer when it
starts, while the sweeper is still on a pod running version one, whose registry has no such
handler. With a delay of zero the timer is due at once and would be deleted seconds after it was
set, with nothing to set it again until the next deploy. R1 keeps a recurring timer from an older
*runtime*; this keeps it from an older *service*, and the analysis found the gap between them.

Thirty seconds is the backoff's ceiling, so a handler that is really gone costs what a
permanently failing timer costs, two looks a minute. The attempt count is not raised because
nothing failed: the first real run must see zero. When an instance with the handler sweeps, the
timer is overdue and `Cadence.next` skips ahead as for any outage.

A one-shot is left as it is: the spec changes nothing about one-shots beyond the matched delete
and the due time, and a one-shot for a handler that no longer exists is far more often a leftover
than a timer ahead of its deploy.

**Alternatives considered**: counting it as a failure — rejected: `previousAttempts` would be
above zero on a run that never failed. Dropping it and relying on "set at start" — rejected: the
last new pod starts before the last old pod stops, so the drop can come after the last set.

**How it is tested**: `Sweep` takes the map of registered actions as a constructor argument
(`TimerSweeper.scala:97-105`), so the upgrade suite runs a second `Sweep` over the kit's database
with a descriptor that lacks the handler, which is exactly what an older service's sweeper is.

## Verify first

1. The self-rescheduling test is red on this base (R2).
2. The driver never decodes `'infinity'`, and an `Instant` read and bound back matches its row
   (R1, R2).
3. `Long` binds to `BIGINT` and a `NULL` period round-trips through `SqlParam` (`sql.scala`).
4. `WasmHostSuite` fails when the ABI document and the host's imports disagree (R7).
5. `schema-init` applying an `ALTER TABLE` while an old pod's sweeper polls does not stall: it
   takes the table's exclusive lock for a catalogue change. No suite that starts from an empty
   database can see this, so `TimerStoreSuite` has a case that applies the changed file to a
   table created from the previous file while a loop runs the legacy due query against it, and
   the by-hand upgrade in the quickstart is the run against a real leftover database.
6. The legacy fixture can see a recurring row once `due_at` is finite (R11).
