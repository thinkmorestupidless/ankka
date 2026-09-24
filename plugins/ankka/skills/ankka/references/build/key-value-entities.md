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
`updateState` and then chooses a reply. A query only replies. The sample below keeps a traveller's
preferences:

**Scala**

```scala
package planner.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import planner.domain.Preferences

/** A user's stated preferences, so an agent can be given context it did not ask for. */
final class PreferencesEntity(context: KeyValueEntityContext) extends KeyValueEntity[Preferences]:

  private val userId: String = context.entityId

  def emptyState: Preferences = Preferences.empty(userId)

  def set(preferences: Preferences): Effect[Done] =
    if preferences.maxBudget < 0 then effects.error("budget cannot be negative")
    else effects.updateState(preferences.copy(userId = userId)).thenReply(_ => Done)

  def addLike(activity: String): Effect[Preferences] =
    if activity.isBlank then effects.error("an activity needs a name")
    else
      effects
        .updateState(currentState.copy(likes = (currentState.likes :+ activity).distinct))
        .thenReplyState

  def get: ReadOnlyEffect[Preferences] = effects.reply(currentState)

object PreferencesEntity
    extends KeyValueEntity.Companion[PreferencesEntity, Preferences](
      componentId = ComponentId("preferences"),
      stateSerializer = Codecs.serializer[Preferences]("preferences")
    ):
  def create(context: KeyValueEntityContext) = new PreferencesEntity(context)

  val set     = command("set")(_.set)
  val addLike = command("add-like")(_.addLike)
  val get     = query("get")(_.get)
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

The shape matches an event sourced entity's, and so do the rules. Handlers are declared with
`command` or `query` under a wire name that is separate from the method name. A query must return a
read-only effect, so it cannot change the state. In Scala the current value is `currentState`; in Python
it is `self.state`. The id is `context.entityId` in Scala and `self.entity_id` in Python.

## Effects

| Scala | Python | Meaning |
|---|---|---|
| `effects.updateState(s)` | `self.effects.update_state(s)` | Replace the stored value with `s`, then choose a reply. |
| `.thenReply(s => value)` | `.then_reply(lambda s: value)` | Reply with a value computed from the new state. |
| `.thenReplyState` | `.then_reply_state()` | Reply with the new state. |
| `.thenNoReply` | `.then_no_reply()` | Update and reply with nothing. |
| `.expireAfter(duration)` | `.expire_after(timedelta)` | Delete the entity once `duration` passes with no further update. |
| `effects.deleteEntity()` | `self.effects.delete_entity()` | Delete the stored value; then choose a reply. |
| `effects.reply(value)` | `self.effects.reply(value)` | Reply without changing anything. |
| `effects.error(message, code)` | `self.effects.error(message, code)` | Refuse the command; nothing changes. The code defaults to `BadRequest`. |

A handler that replies without calling `updateState` leaves the value exactly as it was, and nothing is
written.

After a deletion the id starts again from the empty state on its next command.

## Registering and calling

Register the entity like any other component, and call it through the component client:

**Scala**

```scala
val service = Ankka.service.register(PreferencesEntity.descriptor).start()

val preferences =
  componentClient.forKeyValueEntity(EntityId("user-1")).call(PreferencesEntity.get).invoke()
```

**Python**

```python
service = Ankka.service().register(CheckoutLog)

record = await client.for_key_value_entity("checkout-log", "c1").call("get").invoke(reply=CheckoutRecord)
```

## Projecting a key value entity

A view or consumer can read a key value entity's changes. In Scala the source is
`ChangeSource.stateOf(PreferencesEntity)`; in Python it is `source = CheckoutLog`. Each change delivered
is the entity's whole new value, not a difference. See [Views](views.md) and [Consumers](consumers.md).

## Testing

`KeyValueEntityTestKit` in Scala and `KeyValueTestKit` in Python run a handler with no runtime, apply the
new state, and show the reply:

**Scala**

```scala
val kit    = KeyValueEntityTestKit.of(PreferencesEntity, "user-1")
val result = kit.call(PreferencesEntity.addLike)("hiking")

assertEquals(result.replyValue.likes, List("hiking"))
assert(result.changed)
```

**Python**

```python
kit = KeyValueTestKit.of(CheckoutLog, "c1")
kit.call("record", 1_700_000_000_000)
assert kit.call("get").reply.notified
```

See [Testing](testing.md) for running the whole service against a real database.
