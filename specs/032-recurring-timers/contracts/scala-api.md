# Contract: recurring timers in Scala

What a service written in Scala sees, and what a test of one does. Decisions are in
[research.md](../research.md) (R3, R4, R5, R6, R9). What is stored is in
[data-model.md](../data-model.md).

## The scheduler (`sdk`)

```scala
trait TimerScheduler:
  /** Unchanged. */
  def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit

  /**
   * Schedules `call` to run first after `delay` and then once every `period`, until it is deleted
   * or replaced. Each next due is the previous due plus the period, never the time the handler
   * finished.
   *
   * Scheduling again under a name that already holds a recurring timer for the same handler and
   * the same period keeps its next due and takes the new payload, so this is safe to call every
   * time a service starts. Anything else under an existing name replaces it.
   */
  def createRecurringTimer(
      name: String,
      delay: FiniteDuration,
      period: FiniteDuration,
      call: DeferredCall
  ): Unit

  /** Unchanged: cancels either kind. */
  def delete(name: String): Unit

  /** Unchanged: true for either kind. */
  def exists(name: String): Boolean
```

Both `create…` methods block until the database has answered and throw `IllegalArgumentException`
before storing anything:

| When | Message names |
|---|---|
| the name is empty | that a timer needs a name |
| the payload is over 1,024 bytes | the timer, the size and the limit |
| the period is under one millisecond, zero, negative or over 36,500 days (`createRecurringTimer`) | the timer and the period |

The period's rule is `TimerRules.period(name, period)` in `sdk`, so the sidecar and the module
host answer with the same words.

A `delay` of zero or less is due at once, for either kind: the sweeper runs it on its next poll.

## What the handler is told (`sdk`)

```scala
trait TimedActionContext extends ComponentContext:
  def timerName: String
  def previousAttempts: Int
  /** The due time this run is for. A retry is told the due time of the attempt that failed. */
  def dueTime: java.time.Instant
  def secrets: SecretStore
```

For a recurring timer, successive values of `dueTime` are the first due plus a whole number of
periods. They are not when the handler was called: the sweeper polls, so a call arrives up to a
poll interval after its due.

## The rules a service can rely on

- **Cadence.** The next due is the previous due plus the period.
- **Missed periods are skipped.** When that is not in the future (the service was down, the
  handler kept failing, or one run outlasted a period) the next due is the first cadence point
  that is. A recurring timer fires once for any number of missed periods.
- **Failure.** A handler that fails or throws is retried after 3 seconds, doubling to 30, as a
  one-shot is; the period never shortens that. `previousAttempts` counts failures since the last
  run that succeeded.
- **At least once.** A handler can run more than once for one due time. `dueTime` is the same
  each time, which makes it a key to be idempotent with.
- **A handler may change its own timer.** `delete` on its own name stops it. A one-shot under its
  own name replaces it. `createSingleTimer` under a one-shot's own name is the next timer and is
  kept.
- **A recurring timer waits for its handler.** When the instance running timers does not have
  the handler a recurring timer names, as during a deploy that adds it, the timer is kept and
  looked at again every 30 seconds; it fires once an instance that has the handler runs
  timers. A one-shot in that position is removed.
- **An older runtime never runs a recurring timer.** While instances from before recurring timers
  are still running, a recurring timer waits; when an instance with them runs timers it fires
  once and continues on its cadence.

## The runtime extension (`runtime`)

```scala
object TimerRuntime:
  def apply(
      pollInterval: FiniteDuration = 1.second,
      observer: TimerObserver = TimerObserver.none
  ): TimerRuntime

trait TimerObserver:
  /** Called after each run, on the sweeper's thread pool. Must not block for long. */
  def fired(timer: FiredTimer): Unit

final case class FiredTimer(
    name: String,
    dueTime: Instant,
    attempts: Int,                 // failures before this run
    outcome: FiredTimer.Outcome,   // Done | Failed | Dropped | Deferred (a recurring timer whose handler is not here)
    next: Option[Instant]          // when the sweeper will next run it; None when it was removed
)
```

`TimerRuntime()` with no arguments is what it is today.

## The test kit (`testkit`)

```scala
final class TimerProbe extends TimerObserver:
  /** Every run of the timer named `name`, oldest first. */
  def fired(name: String): Vector[FiredTimer]
  /** The due times `name` has been run for and finished, one for each run that succeeded. */
  def dueTimes(name: String): Vector[Instant]
  /** The timer as the database holds it, or None when there is none. */
  def scheduled(name: String): Option[ScheduledTimer]

final case class ScheduledTimer(
    name: String,
    target: DeferredCall,
    dueTime: Instant,                  // the due the next run is for
    period: Option[FiniteDuration],    // None for a one-shot
    attempts: Int
)
```

```scala
val probe  = TimerProbe()
val timers = TimerRuntime(pollInterval = 100.millis, observer = probe)
val kit    = AnkkaTestKit.start(Seq(Cleanup.descriptor), Seq(timers))

timers.timerScheduler.createRecurringTimer("sweep-carts", 0.seconds, 2.seconds, Cleanup.sweep.deferred)
// … wait for three runs …
val due = probe.dueTimes("sweep-carts")
assertEquals(due(1), due(0).plusSeconds(2))
```

`scheduled` reads the service the kit started; `restartService()` keeps the same probe, so a test
can compare a due time from before a restart with one after.

## Errors a developer may meet

| When | What is said |
|---|---|
| the database's `ankka_timers` has no period columns (a local database from before recurring timers) | `createRecurringTimer` is refused: the table lacks them; apply `30-timers-postgres.sql` or recreate the local database. Timers that fire once go on working, and the runtime logs the same remedy once |
| a handler named by a one-shot timer does not exist | logged; the timer is removed, as today |
| a handler named by a recurring timer does not exist | logged at warn every 30 seconds, naming the timer and saying to cancel it if the handler is gone for good; the timer is kept |
