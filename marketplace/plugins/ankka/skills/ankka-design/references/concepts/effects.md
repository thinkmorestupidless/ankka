# Effects are data

> Why every ankka handler returns a description of what should happen instead of doing it, what each component's effects look like, and why a refusal is a returned value rather than an exception.

Source: https://docs.ankka.cloud/concepts/effects/
Every ankka handler returns an **effect**: a value that describes what should happen. Building one
performs no I/O, reads no state, and calls no model. The runtime receives the value and carries it out —
writes the events, stores the state, sends the reply, starts the next step. This is the organising idea
of the whole platform.

**Scala**

```scala
def addItem(item: LineItem): Effect[Done] =
  if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
  else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

**Java**

```java
@Command("add-item")
public Effect<Done> addItem(LineItem item) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (item.quantity() <= 0)
    return effects().error("quantity must be greater than zero, was " + item.quantity());
  return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
}

@Command("remove-item")
public Effect<Done> removeItem(String productId) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (!currentState().contains(productId))
    return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
  return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
}
```

**Kotlin**

```kotlin
// A suspend fun, as any handler may be: it waits without holding a thread.
@Command("add-item")
suspend fun addItem(item: LineItem): Effect<Done> = effects {
    when {
        currentState().checkedOut -> error(checkedOut)
        item.quantity <= 0 -> error("quantity must be greater than zero, was ${item.quantity}")
        else -> persist(CartEvent.ItemAdded(item)).thenDone()
    }
}
```

**Python**

```python
@command("add-item")
def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]:
    if self.state.checkedOut:
        return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
    return self.effects.persist(ItemAdded(item)).then_reply(lambda _: DONE)
```

**TypeScript**

```ts
addItem(item: LineItem) {
  if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
  return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
}
```

**Rust**

```rust
fn add_item(cart: &Cart, item: LineItem, _: &Context) -> Effect<ShoppingCartEvent, Done> {
    if cart.checked_out {
        return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
    }
    if item.quantity <= 0 {
        let message = format!("quantity must be greater than zero, was {}", item.quantity);
        return effects::error(ErrorCode::BadRequest, message).into();
    }
    effects::persist(ShoppingCartEvent::ItemAdded { item }).then_reply_value(Done)
}
```

**Go**

```go
// addItem describes what should happen: a refusal is a returned error and nothing is persisted; an
// accepted item is an event the runtime persists before it replies.
func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}
```

Reading either handler, "persist `ItemAdded`, then reply `Done`" is a sentence, not a sequence of calls.
Nothing has been written when the handler returns.

## Why handlers return descriptions

**A component's decisions can be tested with nothing running.** A handler is a function from a command
and the current state to an effect. A unit test calls it and inspects the effect — which events, which
reply, which error — with no actor system, cluster or database. That takes milliseconds.
[Testing](../build/testing.md) shows the testkits.

**The runtime is free to decide how.** Because the handler only says what, the runtime chooses where the
entity lives, when events are written, how they are batched, and what happens on a retry. The same
component runs unchanged in a unit test, on a laptop, and in a cluster of five instances. It also runs
unchanged when the handler is in a Python process and the runtime is a sidecar: an effect is data, so it
crosses a process boundary as a message.

**Replay is safe.** An event sourced entity's state is rebuilt by applying its events again. If handlers
performed side effects, a replay would repeat them. Because a handler only returns a description, and
only the `applyEvent` function runs during replay, rebuilding state has no side effects at all.

**A handler cannot half-succeed.** The runtime applies an effect as a whole: the events are written and
then the reply is sent, or nothing is written and the caller gets a failure. There is no path where a
handler has written one thing and thrown before writing the next.

## What each component's effects say

Each component kind has its own vocabulary, because each has different things it can ask for.

| Component | The effect describes | Scala example |
|---|---|---|
| Event sourced entity | events to persist, then a reply; or deletion or expiry | `effects.persist(ItemAdded(item)).thenReply(_ => Done)` |
| Key value entity | a new state, then a reply; or deletion or expiry | `effects.updateState(profile).thenReplyState` |
| Workflow command | a state change and the step to start, then a reply | `effects.updateState(s).transitionTo(step).thenReply(Done)` |
| Workflow step | a state change and what happens next: another step, a pause, the end, a failure | `stepEffects.updateState(s).thenTransitionTo(deposit)` |
| View | what happens to this source's row: update it, delete it, or nothing | `effects.updateRow(row)` |
| Consumer | done, ignored, or a message to produce onward | `effects.produce(notice)` |
| Timed action | done, or failed and to be retried | `effects.done()` |
| Agent | the instructions, message, tools and guardrails for a model call, then the reply's shape | `effects.systemMessage("...").userMessage(q).tools(getWeather).thenReply()` |

Python uses the same vocabulary in its own spelling: `persist(...).then_reply(...)`,
`update_state(...)`, `then_transition_to(...)`, `update_row(...)`, `done()`, `ignore()`,
`system_message(...)`.

## Handlers that only read

A handler declared with `query` must return a read-only effect: a reply or an error, and nothing that
changes state. In Scala the type is `ReadOnlyEffect`, and a `query` declaration will not accept any
other, so "this handler cannot persist" is checked by the compiler. In Python, `@query` handlers must be
annotated as returning a `ReadOnlyEffect`, registration refuses anything else, and the sidecar refuses
events from a read-only handler regardless.

Because the platform knows which handlers are queries, tools can use that knowledge safely. The local
console runs a component's queries to show an entity's state, and refuses to run a command.

## A refusal is a value, not an exception

A command that breaks a business rule is refused with `effects.error(message, code)`. That is an effect
like any other: nothing is persisted, and the caller receives a typed failure carrying the message and an
**error code**.

**Scala**

```scala
if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
```

**Java**

```java
@Command("add-item")
public Effect<Done> addItem(LineItem item) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (item.quantity() <= 0)
    return effects().error("quantity must be greater than zero, was " + item.quantity());
  return effects().persist(new CartEvent.ItemAdded(item)).thenDone();
}

@Command("remove-item")
public Effect<Done> removeItem(String productId) {
  if (currentState().checkedOut()) return effects().error(checkedOut());
  if (!currentState().contains(productId))
    return effects().error(Problem.notFound("cart does not contain '" + productId + "'"));
  return effects().persist(new CartEvent.ItemRemoved(productId)).thenDone();
}
```

**Kotlin**

```kotlin
// A suspend fun, as any handler may be: it waits without holding a thread.
@Command("add-item")
suspend fun addItem(item: LineItem): Effect<Done> = effects {
    when {
        currentState().checkedOut -> error(checkedOut)
        item.quantity <= 0 -> error("quantity must be greater than zero, was ${item.quantity}")
        else -> persist(CartEvent.ItemAdded(item)).thenDone()
    }
}
```

**Python**

```python
if self.state.checkedOut:
    return self.effects.error("cart is already checked out", ErrorCode.CONFLICT)
```

**TypeScript**

```ts
if (this.state.checkedOut) return this.effects.error("cart is already checked out", ErrorCode.Conflict)
```

**Rust**

```rust
fn add_item(cart: &Cart, item: LineItem, _: &Context) -> Effect<ShoppingCartEvent, Done> {
    if cart.checked_out {
        return effects::error(ErrorCode::Conflict, "cart is already checked out").into();
    }
    if item.quantity <= 0 {
        let message = format!("quantity must be greater than zero, was {}", item.quantity);
        return effects::error(ErrorCode::BadRequest, message).into();
    }
    effects::persist(ShoppingCartEvent::ItemAdded { item }).then_reply_value(Done)
}
```

**Go**

```go
// addItem describes what should happen: a refusal is a returned error and nothing is persisted; an
// accepted item is an event the runtime persists before it replies.
func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}
```

The code travels with the refusal. A caller using the component client receives a `CommandError` with the
same code, and an HTTP endpoint that lets the refusal through answers with the matching status — `409` for
`Conflict`, `404` for `NotFound`, `400` for `BadRequest`, which is the default. The endpoint needs no error
mapping of its own, because the entity already said what kind of failure it was.
[Error codes](../reference/error-codes.md) lists every code and its status.

A refusal is different from an exception. A thrown exception is a fault: something went wrong that the
handler did not decide. The runtime reports faults as internal errors, and in a workflow step a thrown
exception is what triggers the step's declared retries. A refusal is a decision, so it is never retried and
never logged as a failure. Tracing records the two differently for the same reason: a refused command is
not a fault in the service.

## What a handler may do before returning

A handler may compute whatever it needs from its input and the current state. What it must not do is reach
outside: no database calls, no HTTP calls, no model calls. A value that is not repeatable, such as the
current time, belongs in the event the handler persists, so that replay applies the recorded value rather
than computing a new one.

Components that exist to coordinate — endpoints, workflow steps, consumers, timed actions and agent tools —
do call other components, through the component client, before returning their effect. That is their job.
An entity's command handler never does, because an entity cannot see another entity's state and a check
made by calling out would not hold by the time the effect is applied.
[Designing a service](designing-services.md) covers where such checks belong.
