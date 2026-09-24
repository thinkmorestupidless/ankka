---
title: Timers
description: Schedule a call for later with a timed action, cancel or replace it by name, and handle retries — timers are stored in the database and outlive the process that set them.
kind: guide
languages: [scala, python]
components: [timed-action]
related: [build/workflows.md, concepts/consistency.md, build/testing.md]
---

# Timers

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

## A timed action in Scala

A timed action is a class extending `TimedAction` whose handlers return an `Effect`, and a companion that
registers them under wire names:

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/OrderTimers.scala#action -->
```scala
final class OrderTimers(context: TimedActionContext) extends TimedAction:

  private val client = context.componentClient

  def expireOrder(orderId: String): Effect =
    val outcome = client.forKeyValueEntity(EntityId(orderId)).call(OrderEntity.cancel).invoke()
    OrderTimers.observed.add(s"$orderId:$outcome"): Unit
    effects.done()
```

The handler reports success whatever the order's state was: an order that had already been confirmed
has nothing to cancel, and that is not a failure. The companion:

```scala
object OrderTimers extends TimedAction.Companion[OrderTimers](ComponentId("order-timers")):
  def create(context: TimedActionContext) = new OrderTimers(context)

  val expireOrder = handler("expire-order")(_.expireOrder)
```

`handler("expire-order")(_.expireOrder)` registers a handler that takes one argument; a handler with no
argument is registered the same way from a method with no parameters. The argument needs a `Serializer`
in scope, exactly as a command's does, because it is stored with the timer.

`effects.done()` completes the timer. `effects.error(message)` fails it, so it is retried. The
`TimedActionContext` passed to `create` carries the component client, `timerName` — which schedule fired
— and `previousAttempts`, how many times this timer has already failed.

## Scheduling and cancelling in Scala

Timers are scheduled through a `TimerScheduler`, which the `TimerRuntime` extension provides once the
service has started:

```scala
val timers = TimerRuntime()

val service = Ankka.service
  .register(OrderEntity.descriptor)
  .register(OrderTimers.descriptor)
  .withExtension(timers)
  .start()

val scheduler = timers.timerScheduler
```

Hand `timers.timerScheduler` to the code that schedules timers once the service is running, for example
by passing it to an endpoint from the factory given to `HttpServer.of`. Extensions start in the order
they are added, so add the `TimerRuntime` before the `HttpServer` for the scheduler to exist when the
endpoints are built. Asking for it before the timer runtime has started throws. A call is captured with
`deferred`, which encodes the argument there and then:

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/TimerSuite.scala#schedule -->
```scala
scheduler.createSingleTimer("expire-o-1", 300.millis, OrderTimers.expireOrder.deferred("o-1"))
assert(scheduler.exists("expire-o-1"))
```

| Method | Meaning |
|---|---|
| `createSingleTimer(name, delay, call)` | Run `call` once, after `delay`. Scheduling again under the same name replaces the earlier schedule. |
| `delete(name)` | Cancel. Cancelling a timer that does not exist is not an error. |
| `exists(name)` | Whether a timer with this name is still scheduled. |

**The name is the timer's identity.** Scheduling twice under one name replaces the first schedule, which
makes "extend the deadline" one call rather than a cancel followed by a create that could race. Build
names from the thing the timer is about — `expire-<orderId>` — so the code that confirms the order can
cancel the timer by name without having stored anything.

A timer's argument is limited to 1024 bytes. Pass an id and let the handler read what it needs, rather
than passing the data itself.

## A timed action in Python

A Python timed action is a class with a `component_id` and handlers declared with `@action`. It reaches
other components through `self.client`, and reads the timer's name and attempt count from its metadata:

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

`self.effects.done()` completes the timer and `self.effects.fail(message)` fails it, so it is retried. An
exception raised by the handler, or a process that cannot be reached, is retried in the same way. The
metadata keys are `ankka.timer`, the timer's name, and `ankka.attempts`, the number of earlier failed
attempts.

Register it with the service like any other component:

```python
Ankka.service().register(Reminder)
```

## Scheduling and cancelling in Python

A Python process schedules through `client.timers`, which forwards to the sidecar:

```python
from datetime import timedelta

await client.timers.schedule("nudge-c1", timedelta(hours=1), "reminder", "nudge", "c1")
await client.timers.cancel("nudge-c1")
```

`schedule(timer_id, delay, component_id, name, input)` names the timed action and handler by their wire
names. As in Scala, scheduling twice under one id replaces the earlier schedule, and cancelling a timer
that does not exist is not an error. The timer lives in the sidecar's database, so it fires even if the
process that set it has restarted in the meantime.

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

<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/TimerSuite.scala#register -->
```scala
testKit = AnkkaTestKit.start(
  Seq(OrderEntity.descriptor, OrderTimers.descriptor),
  Seq(timers)
)
```

See [Testing](testing.md) for the integration test kit.
