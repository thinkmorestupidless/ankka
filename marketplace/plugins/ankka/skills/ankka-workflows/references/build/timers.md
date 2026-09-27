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
`TimedActionContext` carries `timerName` and `previousAttempts`, while Python and TypeScript read the
metadata keys `ankka.timer` and `ankka.attempts`.

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

Every SDK names the timer by an id of your choosing, and the handler that will run. Python names the
timed action and handler by their wire names, `schedule(timer_id, delay, component_id, name, input)`;
TypeScript passes the component and handler themselves, so the names come from their declarations.

Scheduling twice under one id replaces the earlier schedule, and cancelling a timer that does not exist
is not an error. The timer lives in the database, so it fires even if the process that set it has
restarted in the meantime.

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
val timers = TimerRuntime(pollInterval = 200.millis)
```

```scala
testKit = AnkkaTestKit.start(
  Seq(OrderEntity.descriptor, OrderTimers.descriptor),
  Seq(timers)
)
```

See [Testing](testing.md) for the integration test kit.
