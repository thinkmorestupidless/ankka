# Timers

> Schedule a call for later with a timed action, cancel or replace it by name, and handle retries — timers are stored in the database and outlive the process that set them.

Source: https://docs.ankka.cloud/build/timers/
A timer is a call the platform makes later on your behalf. You schedule it under a name, with a delay
and a target: a handler on a **timed action**, which is a stateless component whose job is to coordinate
other components when the time comes. "Cancel this order if it is not confirmed within an hour" is a
timer that calls a timed action handler, which calls the order entity.

Timers are stored in the service's Postgres database, not in memory. A timer outlives the process that
set it, a restart, and a redeployment. One instance of the service runs a sweeper, as a cluster
singleton, that polls for due timers once a second and runs them. The poll interval bounds how late a
timer can be, not how precisely it fires.

## Delivery is at least once

A timer is removed only after its handler reports success. If the handler fails, throws, or its process
cannot be reached, the timer is rescheduled with backoff: 3 seconds, doubling to a ceiling of 30 seconds,
for as long as it keeps failing. Two consequences follow.

- **A handler can run more than once for one timer.** Make what it does safe to repeat. Cancelling an
  order that is already cancelled should be a no-op, not an error.
- **"Nothing to do" is success.** A timer whose work has become irrelevant — the order was confirmed
  after the timer was set — must return `done`. Returning an error reschedules it, forever. This is the
  sharpest edge in the timer API.

## Writing a timed action

A timed action is a class whose handlers are registered under wire names, because a scheduled timer names
its handler as a string that has to survive a deployment:

**Scala**

```scala
final class OrderTimers(context: TimedActionContext) extends TimedAction:

  private val client = context.componentClient

  def expireOrder(orderId: String): Effect =
    val outcome = client.forKeyValueEntity(EntityId(orderId)).call(OrderEntity.cancel).invoke()
    OrderTimers.observed.add(s"$orderId:$outcome"): Unit
    effects.done()
```

**Python**

```python
from ankka import Done
from ankka.effects.timed_action import TimedActionEffect
from ankka.timed_action import TimedAction, action


class Reminder(TimedAction):
    component_id = "reminder"

    @action("nudge")
    async def nudge(self, cart_id: str) -> TimedActionEffect:
        attempts = int(self.metadata.get("ankka.attempts") or "0")
        if attempts > 5:
            return self.effects.done()          # give up quietly rather than retry forever
        assert self.client is not None
        await self.client.for_key_value_entity("reminders", cart_id).call("record").invoke(reply=Done)
        return self.effects.done()
```

**TypeScript**

```ts
import { TimedAction, action, s } from "ankka"

export class Reminder extends TimedAction {
  static readonly componentId = "reminder"
  static readonly actions = {
    nudge: action("nudge", s.string, async (r: Reminder, cartId) => {
      await r.client.of(Reminders, cartId).call(Reminders.handlers.record).invoke()
      return r.effects.done()
    }),
  }
}
```

The Scala handler reports success whatever the order's state was: an order that had already been
confirmed has nothing to cancel, and that is not a failure. Its companion registers the handler:

```scala
object OrderTimers extends TimedAction.Companion[OrderTimers](ComponentId("order-timers")):
  def create(context: TimedActionContext) = new OrderTimers(context)

  val expireOrder = handler("expire-order")(_.expireOrder)
```

`handler("expire-order")(_.expireOrder)` registers a handler that takes one argument; a handler with no
argument is registered the same way from a method with no parameters. The argument needs a `Serializer`
in scope, exactly as a command's does, because it is stored with the timer. Python declares the same with
`@action(name)` and TypeScript with `action(name, shape, run)` in `actions`.

`done()` completes the timer; `error(message)` in Scala and `fail(message)` in Python and TypeScript fail
it, so it is retried. An exception raised by the handler, or a process that cannot be reached, is retried
in the same way.

What fired, and how many times it has already failed, reaches the handler differently: Scala's
`TimedActionContext` carries `timerName` and `previousAttempts`, while Python, TypeScript and Rust read the
metadata keys `ankka.timer` and `ankka.attempts`.

A handler is told the due time it is run for: `dueTime` on Scala's `TimedActionContext`, `due_time` in
Python, `dueTime` in TypeScript and `ctx.due()` in Rust, all read from the metadata key `ankka.due`, which
is milliseconds since the epoch. A retry is told the same due time as the attempt that failed, so the due
time is a key to make a repeated run safe with: record the last due time handled, and a run for it again
has nothing to do.

Register it with the service like any other component.

## Scheduling and cancelling

**Scala**

```scala
scheduler.createSingleTimer("expire-o-1", 300.millis, OrderTimers.expireOrder.deferred("o-1"))
assert(scheduler.exists("expire-o-1"))
```

**Python**

```python
from datetime import timedelta

await client.timers.schedule("nudge-c1", timedelta(hours=1), "reminder", "nudge", "c1")
await client.timers.cancel("nudge-c1")
```

**TypeScript**

```ts
import { Duration } from "ankka"

await client.timers.schedule("nudge-c1", Duration.ofHours(1), { component: Reminder, handler: Reminder.actions.nudge }, "c1")
await client.timers.cancel("nudge-c1")
```

**Rust**

```rust
ctx.client().schedule("nudge-c1", Duration::of_hours(1), Reminder, None, "nudge", "c1".to_string())?;
ctx.client().cancel("nudge-c1")?;
```

Every SDK names the timer by an id of your choosing, and the handler that will run. Python names the
timed action and handler by their wire names, `schedule(timer_id, delay, component_id, name, input)`;
TypeScript passes the component and handler themselves, so the names come from their declarations.

Scheduling twice under one id replaces the earlier schedule, and cancelling a timer that does not exist
is not an error. The timer lives in the database, so it fires even if the process that set it has
restarted in the meantime.

## Recurring timers

To run something again and again, use a recurring timer. A recurring timer is set once, with a delay and
a period: it is first due when the delay has passed, and then once every period. Each next due time is
the previous due time plus the period, never the time the handler finished plus the period, so a handler
that takes a few seconds does not move the cadence. A recurring timer fires until it is cancelled or
replaced.

**Scala**

```scala
scheduler.createRecurringTimer(
  "expire-o-7",
  Duration.Zero,
  1.second,
  OrderTimers.expireOrder.deferred("o-7")
)
```

**Python**

```python
from datetime import timedelta

await client.timers.schedule_recurring("sweep-carts", timedelta(0), timedelta(hours=1), "cleanup", "sweep")
```

**TypeScript**

```ts
import { Duration } from "ankka"

await client.timers.scheduleRecurring("sweep-carts", Duration.ZERO, Duration.ofHours(1), { component: Cleanup, handler: Cleanup.actions.sweep })
```

**Rust**

```rust
ctx.client().schedule_recurring("sweep-carts", Duration::of_seconds(0), Duration::of_hours(1), Cleanup, "sweep", ())?;
```

A delay of zero is due at once. A period is from one millisecond to 36,500 days; any other is refused when
the timer is set, with an error naming the timer, and nothing is stored. A period is a length of time:
to run something at midnight, work out the delay to the next midnight and give a period of a day.

**A service may set its recurring timers every time it starts.** Setting a recurring timer under a name
that already holds one for the same handler with the same period changes nothing about when it fires: it
keeps its next due time, and takes the new input. So setting it at start, on every instance and after
every deployment, never restarts the wait. Anything else under the name — another period, another
handler, or a timer that fires once — replaces it. To restart a cadence, cancel the timer and set it.

**Missed due times are skipped, not caught up.** When a due time passes while nothing can run the timer
— the service was down, its handler kept failing, or one run took longer than a period — it fires once,
not once for each due time that passed, and goes on from the first due time still to come on the
original cadence. A service that must account for every period, a daily billing run say, works out what
it missed from the due time it is told and the last one it recorded.

**Failure.** A recurring timer whose handler fails is retried after the same backoff as any timer, 3
seconds doubling to 30, for the same due time; the period never shortens the backoff. When a retry
succeeds, the next due time is taken from the due time it failed for, not from the time the retry ran.

**A recurring timer waits for its handler.** When the instance running timers does not have the handler
a recurring timer names — during a deployment that adds the handler, while an instance of the previous
version still runs the timers — the timer is kept, and looked at again every 30 seconds; it fires once an
instance that has the handler runs the timers. A recurring timer whose handler has been removed for good
is logged each time it is looked at, until it is cancelled. A timer that fires once in that position is
removed.

**Upgrading.** A runtime from before recurring timers neither runs nor removes one: while a service is
being upgraded, or after it is taken back to such a runtime, a recurring timer waits until an instance
with recurring timers runs the timers, and then fires once for whatever it missed. Two retries of a timer
that fires once are told a best-effort due time during that upgrade: one that had already failed before
the upgrade is told when its backoff ended, and one that an instance of the previous runtime replaced
and then retried is told the due time of the timer it replaced. Neither is lost or run twice for it.

**A local database from before recurring timers.** Postgres applies the schema only when a database's
volume is new, so a developer's existing local database keeps the timers table it was created with.
Timers that fire once go on working on it exactly as before. Setting a recurring timer there is refused,
with an error that says to apply `30-timers-postgres.sql` from the platform's schema, which is safe to
apply to a database in use, or to recreate the local database; the runtime says the same once in its log.

## Timers and workflows

A workflow can wait without a timer of its own: a step that ends with a pause and a timeout transitions
the workflow on its own when the time passes. Use that for deadlines that belong to one workflow instance.
Use a timer when the deadline belongs to something that is not a workflow, such as an entity, or when a
handler must run on a schedule set from an endpoint. See [Workflows](workflows.md).

## Testing timers

A timed action handler is ordinary code and can be called directly. In Python, `TimedActionTestKit.of(Reminder).call("nudge", "c1")`
runs one handler with no sidecar and returns its effect. Scheduling, firing, replacement and backoff are
the runtime's behaviour, and are tested through the integration test kit with the `TimerRuntime`
extension registered — a short poll interval keeps such a test fast:

```scala
private val probe  = TimerProbe()
private val timers = TimerRuntime(pollInterval = 200.millis, observer = probe)
```

```scala
testKit = AnkkaTestKit.start(
  Seq(OrderEntity.descriptor, OrderTimers.descriptor),
  Seq(timers)
)
```

A `TimerProbe` is told what the runtime did with every timer: the due time each run was for, its
outcome and the next due time it was given. `probe.bind(testKit)` lets it read a timer as the database
holds it with `scheduled(name)`, and it keeps its record across `restartService()`. A test of a cadence
reads due times from the probe rather than from when its handler was called, since a run starts up to a
poll interval after its due time:

```scala
val due = eventually("three runs")(Option(probe.dueTimes("expire-o-7")).filter(_.size >= 3))
assertEquals(due(1), due(0).plusSeconds(1))
assertEquals(due(2), due(1).plusSeconds(1))
```

A unit test of a handler that reads its due time sets the metadata itself: Python's
`TimedActionTestKit.of(Reminder).call("nudge", "c1", metadata={"ankka.due": "1767225600000"})`, TypeScript's
`invoke(action, input, { "ankka.due": "…" })` and Rust's `TimedActionTestKit::<C>::new().with_metadata("ankka.due", "…")`.

See [Testing](testing.md) for the integration test kit.
