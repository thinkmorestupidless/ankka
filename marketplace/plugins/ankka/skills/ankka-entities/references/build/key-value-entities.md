# Key value entities

> Store only the latest value of a piece of state, replace it with updateState, delete or expire it, and decide when that is a better fit than event sourcing.

Source: https://docs.ankka.cloud/build/key-value-entities/
A key value entity is a piece of state, addressed by an id, that stores only its latest value. A command
handler replaces the value with a new one, and the previous value is gone. There is no history and no event
handler: what is stored is what the entity is.

A key value entity has the same hosting guarantees as an event sourced entity. Each id lives in one place
in the service's cluster, receives one command at a time, and survives restarts because its value is
persisted before the reply is sent. The difference is only in what reaches storage.

## Choosing between key value and event sourced

Choose a key value entity when nothing ever needs to know how the value came to be: a user's preferences,
a configuration record, the latest reading from a device. Choose an
[event sourced entity](event-sourced-entities.md) when something does:

| Need | Key value | Event sourced |
|---|---|---|
| Read and replace the current value | yes | yes |
| An audit trail of every change | no | yes |
| A consumer that reacts to *every* change | no; intermediate values can be skipped | yes, each event exactly once in order |
| A view that counts or sums changes | no | yes |
| A view of the current value | yes | yes |
| Simplest code | yes | |

The consumer row is the one that decides most cases. A view or consumer reading a key value entity is
guaranteed to see the latest value, but not every value in between, because the store keeps no history to
replay. That is right for a projection of what is, and wrong for anything that counts or audits.

## Writing the entity

A key value entity declares its empty state and its handlers. A command replaces the state with
`updateState` and then chooses a reply. A query only replies. The sample below records when a cart was
checked out — the value is the record, and how it came to be recorded is of no interest to anything:

**Scala**

```scala
final class CheckoutLog(context: KeyValueEntityContext) extends KeyValueEntity[CheckoutRecord]:

  private val cartId: String = context.entityId

  def emptyState: CheckoutRecord = CheckoutRecord(cartId)

  def record(at: Long): Effect[Done] =
    effects.updateState(CheckoutRecord(cartId, at, notified = true)).thenReply(_ => Done)

  def get: ReadOnlyEffect[CheckoutRecord] = effects.reply(currentState)

object CheckoutLog
    extends KeyValueEntity.Companion[CheckoutLog, CheckoutRecord](
      componentId = ComponentId("checkout-log"),
      stateSerializer = Codecs.serializer[CheckoutRecord]("checkout-record")
    ):
  def create(context: KeyValueEntityContext) = new CheckoutLog(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
```

**Python**

```python
"""Where the notifier records checkouts: a key value entity per cart holding when it happened."""

from __future__ import annotations

from dataclasses import dataclass

from ankka import DONE, Done, json_codec
from ankka.effects.key_value import KeyValueEffect, KeyValueReadOnlyEffect
from ankka.event_sourced_entity import command, query
from ankka.key_value_entity import KeyValueEntity


@dataclass(frozen=True)
class CheckoutRecord:
    cartId: str
    at: int = 0
    notified: bool = False


class CheckoutLog(KeyValueEntity[CheckoutRecord]):
    component_id = "checkout-log"
    state_codec = json_codec(CheckoutRecord, "checkout-record")

    def empty_state(self) -> CheckoutRecord:
        return CheckoutRecord(self.entity_id)

    @command("record")
    def record(self, at: int) -> KeyValueEffect[CheckoutRecord, Done]:
        return self.effects.update_state(CheckoutRecord(self.entity_id, at, True)).then_reply(lambda _: DONE)

    @query("get")
    def get(self) -> KeyValueReadOnlyEffect[CheckoutRecord, CheckoutRecord]:
        return self.effects.reply(self.state)
```

**TypeScript**

```ts
export const CheckoutRecord = s.record("CheckoutRecord", { cartId: s.string, at: s.long, notified: s.boolean })
export type CheckoutRecord = Infer<typeof CheckoutRecord>

export class CheckoutLog extends KeyValueEntity<CheckoutRecord> {
  static readonly componentId = "checkout-log"
  static readonly state = jsonCodec(CheckoutRecord, "checkout-record")

  static readonly handlers = {
    record: command("record", s.long, Done, (log: CheckoutLog, at) => log.effects.updateState({ cartId: log.entityId, at, notified: true }).thenReply(() => done)),
    get: query("get", CheckoutRecord, (log: CheckoutLog) => log.effects.reply(log.state)),
  }

  emptyState(): CheckoutRecord {
    return { cartId: this.entityId, at: 0n, notified: false }
  }
}
```

The shape matches an event sourced entity's, and so do the rules. Handlers are declared with
`command` or `query` under a wire name that is separate from the method name. A query must return a
read-only effect, so it cannot change the state. The current value is `currentState` in Scala,
`self.state` in Python and `this.state` in TypeScript; the id is `context.entityId`, `self.entity_id`
and `this.entityId`.

## Effects

| Scala | Python | TypeScript | Meaning |
|---|---|---|---|
| `effects.updateState(s)` | `self.effects.update_state(s)` | `this.effects.updateState(s)` | Replace the stored value with `s`, then choose a reply. |
| `.thenReply(s => value)` | `.then_reply(lambda s: value)` | `.thenReply(s => value)` | Reply with a value computed from the new state. |
| `.thenReplyState` | `.then_reply_state()` | `.thenReplyState()` | Reply with the new state. |
| `.thenNoReply` | `.then_no_reply()` | `.thenNoReply()` | Update and reply with nothing. |
| `.expireAfter(duration)` | `.expire_after(timedelta)` | `.expireAfter(duration)` | Delete the entity once `duration` passes with no further update. |
| `effects.deleteEntity()` | `self.effects.delete_entity()` | `this.effects.deleteEntity()` | Delete the entity; then choose a reply. |
| `effects.reply(value)` | `self.effects.reply(value)` | `this.effects.reply(value)` | Reply without changing anything. |
| `effects.error(message, code)` | `self.effects.error(message, code)` | `this.effects.error(message, code)` | Refuse the command; nothing changes. The code defaults to `BadRequest`. |

A handler that replies without calling `updateState` leaves the value exactly as it was, and nothing is
written.

After a deletion the id starts again from the empty state on its next command.

A deletion is a recorded change, not a removed row. The entity's value is replaced by its empty state and
marked deleted, at the revision after its last update, and three things follow from that:

- **Its views and consumers are told.** A [view](views.md#when-the-source-is-deleted)'s deletion handler
  runs, which by default removes the entity's row, and a
  [consumer](consumers.md#when-the-source-is-deleted)'s deletion handler runs, at the deletion's revision.
- **Its revisions go on counting.** An entity created again under the same id continues from the
  deletion's revision, so everything that follows its changes sees them in order across the deletion.
- **Its id and revision stay stored.** What the entity held is gone; that an entity of that id existed,
  and how many times it changed, is not.

An entity whose value has expired is not deleted. Expiry is noticed when a command next arrives: the
handler is shown the empty state, nothing is written until it updates, and no view or consumer is told.

## Registering and calling

Register the entity like any other component, and call it through the component client:

**Scala**

```scala
val service = Ankka.service.register(CheckoutLog.descriptor).start()

val record =
  componentClient.forKeyValueEntity(EntityId("c1")).call(CheckoutLog.get).invoke()
```

**Python**

```python
service = Ankka.service().register(CheckoutLog)

record = await client.for_key_value_entity("checkout-log", "c1").call("get").invoke(reply=CheckoutRecord)
```

**TypeScript**

```ts
const service = Ankka.service().register(CheckoutLog)

const record = await client.of(CheckoutLog, "c1").call(CheckoutLog.handlers.get).invoke()
```

## Projecting a key value entity

A view or consumer can read a key value entity's changes. The source is
`ChangeSource.stateOf(CheckoutLog)` in Scala, `source = CheckoutLog` in Python and
`static readonly source = CheckoutLog` in TypeScript. Each change delivered is the entity's whole new
value, not a difference, with the entity's revision as its sequence number; the entity's deletion is
delivered as a deletion. See [Views](views.md) and [Consumers](consumers.md). A whole value at a revision
is also what a graph needs: see [Publish a graph](graph.md).

## Testing

`KeyValueEntityTestKit` in Scala and `KeyValueTestKit` in Python and TypeScript run a handler with no
runtime, apply the new state, and show the reply:

**Scala**

```scala
test("recording a checkout replaces the value and acknowledges") {
  val kit    = KeyValueEntityTestKit.of(CheckoutLog, "c1")
  val result = kit.call(CheckoutLog.record)(1_700_000_000_000L)

  assertEquals(result.replyValue, Done)
  assert(result.changed, "recording should write a new value")
  assertEquals(result.state.at, 1_700_000_000_000L)
  assertEquals(result.state.notified, true)
}
```

**Python**

```python
def test_recording_a_checkout_replaces_the_value() -> None:
    log = KeyValueTestKit.of(CheckoutLog, "c1")
    assert log.call("record", 1700000000000).reply is not None
    assert log.call("get").reply == CheckoutRecord("c1", 1700000000000, True)
```

**TypeScript**

```ts
test("recording a checkout replaces the value", async () => {
  const kit = KeyValueTestKit.of(CheckoutLog, "c1")
  const recorded = await kit.call(CheckoutLog.handlers.record, 1700000000000n)
  assert.equal(recorded.reply, done)
  assert.equal(kit.state.notified, true)
  assert.equal(kit.state.at, 1700000000000n)
})
```

See [Testing](testing.md) for running the whole service against a real database.
