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
timer can be, not how precisely it fires. A service with a workflow runs the sweeper even when it
registers no `TimerRuntime`, because a paused workflow's deadline is a timer too
([Workflows](workflows.md)).

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

**Java**

```java
/** A timed action that records on the conformance entity it is given. */
@ComponentId("reminder")
public class Reminder extends TimedAction {

  @Action("remind")
  public TimedActionEffect remind(String id) {
    record(id, "reminded");
    return effects().done();
  }

  /** Records the due time it was run for, as the runtime told it. */
  @Action("tick")
  public TimedActionEffect tick(String id) {
    record(id, "due:" + context().dueTime().toEpochMilli());
    return effects().done();
  }

  private void record(String id, String what) {
    context()
        .componentClient()
        .forEventSourcedEntity(id)
        .call(ConformanceEntity::record)
        .invoke(what);
  }
}
```

**Kotlin**

```kotlin
@ComponentId("reminder")
class Reminder : TimedAction() {

    @Action("remind")
    fun remind(id: String): TimedActionEffect {
        record(id, "reminded")
        return effects { done() }
    }

    /** Records the due time it was run for, as the runtime told it. */
    @Action("tick")
    fun tick(id: String): TimedActionEffect {
        record(id, "due:${context().dueTime().toEpochMilli()}")
        return effects { done() }
    }

    private fun record(id: String, what: String) {
        context().componentClient().forEventSourcedEntity(id).call(ConformanceEntity::record).invoke(what)
    }
}
```

**Python**

```python
class Reminder(TimedAction):
    component_id = "reminder"

    @action("remind")
    async def remind(self, id: str) -> TimedActionEffect:
        assert self.client is not None
        await self.client.for_event_sourced_entity("conformance", id).call("record").invoke("reminded", reply=str)
        return self.effects.done()

    @action("tick")
    async def tick(self, id: str) -> TimedActionEffect:
        """Records the due time it was run for, as the runtime told it."""
        assert self.client is not None
        due = self.metadata.get("ankka.due")
        assert due is not None, "a timed action is run with ankka.due"
        await self.client.for_event_sourced_entity("conformance", id).call("record").invoke(f"due:{int(due)}", reply=str)
        return self.effects.done()
```

**TypeScript**

```ts
export class Reminder extends TimedAction {
  static readonly componentId = "reminder"
  static readonly actions = {
    remind: action("remind", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke("reminded")
      return r.effects.done()
    }),
    // Records the due time it was run for, as the runtime told it.
    tick: action("tick", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke(`due:${r.dueTime?.getTime()}`)
      return r.effects.done()
    }),
  }
}
```

**Rust**

```rust
fn expire_order(order_id: String, ctx: &Context) -> Result<(), CommandError> {
    // The entity answers "cancelled" or "already confirmed"; either way the timer is done.
    let _outcome: String = ctx.client().invoke(OrderEntity, &order_id, "cancel", ())?;
    Ok(())
}
```

**Go**

```go
// Reminder records on the conformance entity it is given the id of.
var Reminder = ankka.TimedAction("reminder",
	ankka.Action("remind", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "reminded"); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
	// Records the due time it was run for, as the runtime told it.
	ankka.Action("tick", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		due := "undefined"
		if t, ok := ankka.TimerFrom(ctx); ok && !t.Due.IsZero() {
			due = fmt.Sprint(t.Due.UnixMilli())
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "due:"+due); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
)
```

The Scala handler reports success whatever the order's state was: an order that had already been
confirmed has nothing to cancel, and that is not a failure. Its companion registers the handler:

**Scala**

```scala
object OrderTimers extends TimedAction.Companion[OrderTimers](ComponentId("order-timers")):
  def create(context: TimedActionContext) = new OrderTimers(context)

  val expireOrder = handler("expire-order")(_.expireOrder)
```

**Java**

```java
/** A timed action that records on the conformance entity it is given. */
@ComponentId("reminder")
public class Reminder extends TimedAction {

  @Action("remind")
  public TimedActionEffect remind(String id) {
    record(id, "reminded");
    return effects().done();
  }

  /** Records the due time it was run for, as the runtime told it. */
  @Action("tick")
  public TimedActionEffect tick(String id) {
    record(id, "due:" + context().dueTime().toEpochMilli());
    return effects().done();
  }

  private void record(String id, String what) {
    context()
        .componentClient()
        .forEventSourcedEntity(id)
        .call(ConformanceEntity::record)
        .invoke(what);
  }
}
```

**Kotlin**

```kotlin
/** The order's deadlines: what runs when a timer set on an order fires. */
@ComponentId("order-timers")
class OrderTimers : TimedAction() {
    @Action("expire-order")
    fun expireOrder(orderId: String): TimedActionEffect {
        val outcome = context().componentClient().forKeyValueEntity(orderId).call(OrderEntity::cancel).invoke()
        OBSERVED.add("$orderId:$outcome")
        return effects { done() }
    }

    companion object {
        /** Records what fired, so tests can assert on it. */
        val OBSERVED = ConcurrentLinkedQueue<String>()
    }
}
```

**Python**

```python
class OrderTimers(TimedAction):
    component_id = "order-timers"

    @action("expire-order")
    async def expire_order(self, order_id: str) -> TimedActionEffect:
        assert self.client is not None
        # Success whatever the order's state was: a confirmed order has nothing to cancel.
        outcome = await self.client.for_key_value_entity("order", order_id).call("cancel").invoke(reply=str)
        observed.append(f"{order_id}:{outcome}")
        return self.effects.done()
```

**TypeScript**

```ts
export class Reminder extends TimedAction {
  static readonly componentId = "reminder"
  static readonly actions = {
    remind: action("remind", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke("reminded")
      return r.effects.done()
    }),
    // Records the due time it was run for, as the runtime told it.
    tick: action("tick", s.string, async (r: Reminder, id) => {
      await r.client.of(Conformance, id).call(Conformance.handlers.record).invoke(`due:${r.dueTime?.getTime()}`)
      return r.effects.done()
    }),
  }
}
```

**Rust**

```rust
impl TimedAction for OrderTimers {
    const COMPONENT_ID: &'static str = "order-timers";

    fn actions() -> Actions<OrderTimers> {
        Actions::new().action("expire-order", OrderTimers::expire_order)
    }
}
```

**Go**

```go
// Reminder records on the conformance entity it is given the id of.
var Reminder = ankka.TimedAction("reminder",
	ankka.Action("remind", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "reminded"); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
	// Records the due time it was run for, as the runtime told it.
	ankka.Action("tick", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		due := "undefined"
		if t, ok := ankka.TimerFrom(ctx); ok && !t.Due.IsZero() {
			due = fmt.Sprint(t.Due.UnixMilli())
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "due:"+due); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
)
```

`handler("expire-order")(_.expireOrder)` registers a handler that takes one argument; a handler with no
argument is registered the same way from a method with no parameters. The argument needs a `Serializer`
in scope, exactly as a command's does, because it is stored with the timer. Java and Kotlin declare the
same with `@Action(name)` on a method of a `TimedAction`, Python with `@action(name)`, TypeScript with
`action(name, shape, run)` in `actions`, and Go with `ankka.Action(name, run)` given to `ankka.TimedAction`.

`done()` completes the timer; `error(message)` in Scala, Java and Kotlin and `fail(message)` in Python and TypeScript
(`ankka.ActionFail(code, message)` in Go) fail it, so it is retried. An exception raised by the handler, or a process that cannot be reached, is retried
in the same way.

What fired, and how many times it has already failed, reaches the handler differently: Scala's
`TimedActionContext` and the Java and Kotlin `context()` carry `timerName` and `previousAttempts`, while Python, TypeScript and Rust read the
metadata keys `ankka.timer` and `ankka.attempts`, and Go reads `Name` and `Attempts` from
`ankka.TimerFrom(ctx)`. In Scala, Java and Kotlin the context also carries `timers`, the
service's scheduler, so a handler can set what comes next under another name — a calendar schedule, which a
recurring timer's fixed period does not express, is one timer per occurrence — and `clock`, the clock the
sweeper fired it by, which a handler reads as now rather than `Instant.now()`, so that a test moving the
sweeper's clock moves the handler's too.

A handler is told the due time it is run for: `dueTime` on Scala's `TimedActionContext`, `dueTime()` on
the Java and Kotlin context, `due_time` in
Python, `dueTime` in TypeScript, `ctx.due()` in Rust and `ankka.TimerFrom(ctx)`'s `Due` in Go, all read from the metadata key `ankka.due`, which
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

**Java**

```java
@Post("/conformance/remind/{id}")
public Done remind(String id) {
  timers().schedule("remind-" + id, Duration.ofSeconds(1), Reminder::remind, id);
  return Done.done();
}
```

**Kotlin**

```kotlin
@Post("/conformance/remind/{id}")
fun remind(id: String): Done {
    timers().schedule("remind-$id", Duration.ofSeconds(1), Reminder::remind, id)
    return Done.done()
}
```

**Python**

```python
@post("/remind/{id}")
async def remind(self, id: str) -> Done:
    await self.client.timers.schedule(f"remind-{id}", timedelta(seconds=1), "reminder", "remind", id)
    return DONE
```

**TypeScript**

```ts
remind: post("/remind/{id}", Done, async (ep: ConformanceEndpoint, req) => {
  await ep.client.timers.schedule(`remind-${req.params.id}`, Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.remind }, req.params.id)
  return done
}),
```

**Rust**

```rust
fn remind(request: &Request, (): ()) -> Result<Done, HttpProblem> {
    let id = request.path("id");
    request.client().schedule(
        &format!("remind-{id}"),
        Duration::of_seconds(1),
        Reminder,
        None,
        "remind",
        id.to_string(),
    )?;
    Ok(Done)
}
```

**Go**

```go
ankka.Post("/remind/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.Schedule(ctx, "remind-"+id, time.Second, ankka.TimerTarget{ComponentID: "reminder", Handler: "remind"}, id)
}),
```

Every SDK names the timer by an id of your choosing, and the handler that will run. Python names the
timed action and handler by their wire names, `schedule(timer_id, delay, component_id, name, input)`;
TypeScript passes the component and handler themselves, so the names come from their declarations; Java
and Kotlin name the handler by a method reference (`Reminder::remind`), resolved to its wire name; Go
names them in an `ankka.TimerTarget{ComponentID, Handler}`.

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

**Java**

```java
// A recurring timer: due at once, then every second.
@Post("/conformance/recur/{id}")
public Done recur(String id) {
  timers()
      .scheduleRecurring("recur-" + id, Duration.ZERO, Duration.ofSeconds(1), Reminder::tick, id);
  return Done.done();
}
```

**Kotlin**

```kotlin
// A recurring timer: due at once, then every second.
@Post("/conformance/recur/{id}")
fun recur(id: String): Done {
    timers().scheduleRecurring("recur-$id", Duration.ZERO, Duration.ofSeconds(1), Reminder::tick, id)
    return Done.done()
}
```

**Python**

```python
# A recurring timer: due at once, then every second.
@post("/recur/{id}")
async def recur(self, id: str) -> Done:
    await self.client.timers.schedule_recurring(f"recur-{id}", timedelta(0), timedelta(seconds=1), "reminder", "tick", id)
    return DONE

# The same timer set again, with a delay a replacement would be first due after.
@post("/recur/{id}/again")
async def recur_again(self, id: str) -> Done:
    await self.client.timers.schedule_recurring(f"recur-{id}", timedelta(seconds=60), timedelta(seconds=1), "reminder", "tick", id)
    return DONE

@post("/recur/{id}/cancel")
async def recur_cancel(self, id: str) -> Done:
    await self.client.timers.cancel(f"recur-{id}")
    return DONE
```

**TypeScript**

```ts
// A recurring timer: due at once, then every second.
recur: post("/recur/{id}", Done, async (ep: ConformanceEndpoint, req) => {
  await ep.client.timers.scheduleRecurring(`recur-${req.params.id}`, Duration.ZERO, Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.tick }, req.params.id)
  return done
}),
// The same timer set again, with a delay a replacement would be first due after.
recurAgain: post("/recur/{id}/again", Done, async (ep: ConformanceEndpoint, req) => {
  await ep.client.timers.scheduleRecurring(`recur-${req.params.id}`, Duration.ofSeconds(60), Duration.ofSeconds(1), { component: Reminder, handler: Reminder.actions.tick }, req.params.id)
  return done
}),
recurCancel: post("/recur/{id}/cancel", Done, async (ep: ConformanceEndpoint, req) => {
  await ep.client.timers.cancel(`recur-${req.params.id}`)
  return done
}),
```

**Rust**

```rust
fn set_recurring(
    request: &Request,
    delay: Duration,
    period: Duration,
) -> Result<Done, HttpProblem> {
    let id = request.path("id");
    request.client().schedule_recurring(
        &format!("recur-{id}"),
        delay,
        period,
        Reminder,
        "tick",
        id.to_string(),
    )?;
    Ok(Done)
}

/// A recurring timer: due at once, then every second.
fn recur(request: &Request, (): ()) -> Result<Done, HttpProblem> {
    Self::set_recurring(request, Duration::ZERO, Duration::of_seconds(1))
}

/// The same timer set again, with a delay a replacement would be first due after.
fn recur_again(request: &Request, (): ()) -> Result<Done, HttpProblem> {
    Self::set_recurring(request, Duration::of_seconds(60), Duration::of_seconds(1))
}

fn recur_cancel(request: &Request, (): ()) -> Result<Done, HttpProblem> {
    let id = request.path("id");
    request.client().cancel(&format!("recur-{id}"))?;
    Ok(Done)
}
```

**Go**

```go
// A recurring timer: due at once, then every second.
ankka.Post("/recur/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 0, time.Second, tickTarget, id)
}),
// The same timer set again, with a delay a replacement would be first due after.
ankka.Post("/recur/{id}/again", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 60*time.Second, time.Second, tickTarget, id)
}),
ankka.Post("/recur/{id}/cancel", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	return ankka.Done{}, ankka.Cancel(ctx, "recur-"+req.Param("id"))
}),
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

**Scala**

```scala
private val probe  = TimerProbe()
private val timers = TimerRuntime(pollInterval = 200.millis, observer = probe)
```

**Kotlin**

```kotlin
// The kit's timer runtime polls every 200ms and tells `timerRuns()` what it did with each timer.
val service = Ankka.service("orders").register(OrderEntity::class.java).register(OrderTimers::class.java)
AnkkaTestKit.start(service).use { kit ->
```

**Scala**

```scala
testKit = AnkkaTestKit.start(
  Seq(OrderEntity.descriptor, OrderTimers.descriptor),
  Seq(timers)
)
```

**Kotlin**

```kotlin
// The kit's timer runtime polls every 200ms and tells `timerRuns()` what it did with each timer.
val service = Ankka.service("orders").register(OrderEntity::class.java).register(OrderTimers::class.java)
AnkkaTestKit.start(service).use { kit ->
```

A timer a week away need not take a week: give the runtime a clock the test moves, and the sweeper, the
timers it sets and every handler's `clock` move with it. So does every workflow's pause: its deadline is
set by that clock, and the pause ends when the test moves the clock past it, whether the workflow is
awake, passivated or restarted. The clock moves forward only.

**Scala**

```scala
private val clock  = MovableClock.at(Instant.parse("2026-10-11T18:00:00Z"))
private val timers = TimerRuntime(pollInterval = 200.millis, clock = clock)
```

**Java**

```java
var clock = MovableClock.at(Instant.parse("2026-11-10T09:00:00Z"));
var settings = Settings.defaults().withClock(clock);
try (var kit = AnkkaTestKit.start(Ankka.service("timers").register(CartTimers.class), settings)) {
  kit.timers().schedule("expire-c-9", Duration.ofHours(1), CartTimers::expire, "c-9");
```

```java
clock.moveBy(Duration.ofHours(1));
```

Another extension that needs the same clock finds the timer runtime by type:
`service.extension[TimerRuntime].map(_.clock)`.

A `TimerProbe` is told what the runtime did with every timer: the due time each run was for, its
outcome and the next due time it was given. `probe.bind(testKit)` lets it read a timer as the database
holds it with `scheduled(name)`, and it keeps its record across `restartService()`. A test of a cadence
reads due times from the probe rather than from when its handler was called, since a run starts up to a
poll interval after its due time:

**Scala**

```scala
val due = eventually("three runs")(Option(probe.dueTimes("expire-o-7")).filter(_.size >= 3))
assertEquals(due(1), due(0).plusSeconds(1))
assertEquals(due(2), due(1).plusSeconds(1))
```

**Java**

```java
/** The due times of a recurring timer's first three runs, a second apart. */
public static List<Instant> threeDueTimes(AnkkaTestKit kit) {
  kit.timers()
      .scheduleRecurring("tick-o-7", Duration.ZERO, Duration.ofSeconds(1), CartTimers::tick);
  kit.eventually(
      Duration.ofSeconds(20),
      () -> {
        if (kit.timerRuns().dueTimes("tick-o-7").size() < 3)
          throw new AssertionError("fewer than three runs so far");
      });
  kit.timers().cancel("tick-o-7");
  // A run starts up to a poll interval after its due time, so the cadence is read from the due
  // times the kit recorded, not from when the handler ran: due.get(1) is due.get(0) plus a
  // second, and due.get(2) a second after that.
  return kit.timerRuns().dueTimes("tick-o-7");
}
```

**Kotlin**

```kotlin
// A cadence is read from the due times the runtime ran for, not from when the handler
// was called: a run starts up to a poll interval after its due time.
kit.eventually(Duration.ofSeconds(10)) { assertTrue(kit.timerRuns().dueTimes("expire-o-7").size >= 3) }
val due = kit.timerRuns().dueTimes("expire-o-7")
assertEquals(due[0].plusSeconds(1), due[1])
assertEquals(due[1].plusSeconds(1), due[2])
```

A unit test of a handler that reads its due time sets the metadata itself: Python's
`TimedActionTestKit.of(Reminder).call("nudge", "c1", metadata={"ankka.due": "1767225600000"})`, TypeScript's
`invoke(action, input, { "ankka.due": "…" })` and Rust's `TimedActionTestKit::<C>::new().with_metadata("ankka.due", "…")`.

See [Testing](testing.md) for the integration test kit.
