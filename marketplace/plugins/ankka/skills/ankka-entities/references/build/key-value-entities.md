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

**Java**

```java
/** When a cart was checked out: the value is the record, and nothing cares how it got there. */
@ComponentId("checkout-log")
public class CheckoutLog extends KeyValueEntity<CheckoutLog.CheckoutRecord> {

  @Manifest("checkout-record")
  public record CheckoutRecord(String cartId, long at, boolean notified) {}

  @Override
  protected CheckoutRecord emptyState() {
    return new CheckoutRecord(context().entityId(), 0L, false);
  }

  @Command("record")
  public Effect<Done> record(Long at) {
    return effects().updateState(new CheckoutRecord(currentState().cartId(), at, true)).thenDone();
  }

  @Query("get")
  public ReadOnlyEffect<CheckoutRecord> get() {
    return effects().reply(currentState());
  }
}
```

**Kotlin**

```kotlin
/**
 * Where the notifier records checkouts: a key value entity per cart holding when it happened.
 *
 * Nothing needs to know how the record came to be, only what it is now, so this stores the latest
 * value rather than a journal of events. The cart itself is the opposite case, and is event sourced.
 */
@ComponentId("checkout-log")
class CheckoutLog : KeyValueEntity<CheckoutRecord>() {
    private val cartId: String
        get() = context().entityId()

    override fun emptyState() = CheckoutRecord(cartId)

    @Command("record")
    fun record(at: Long): Effect<Done> = effects { updateState(CheckoutRecord(cartId, at, notified = true)).thenDone() }

    @Query("get")
    fun get(): ReadOnlyEffect<CheckoutRecord> = effects { reply(currentState()) }
}
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

**Rust**

```rust
pub struct CheckoutLog;

impl CheckoutLog {
    fn record(_: &CheckoutRecord, at: i64, ctx: &Context) -> KeyValueEffect<CheckoutRecord, Done> {
        let record = CheckoutRecord {
            cart_id: ctx.entity_id().to_string(),
            at,
            notified: true,
        };
        effects::update_state(record).then_reply_value(Done)
    }

    fn get(record: &CheckoutRecord, _: (), _: &Context) -> ReadOnlyEffect<CheckoutRecord> {
        effects::reply(record.clone())
    }
}

impl KeyValueEntity for CheckoutLog {
    type State = CheckoutRecord;
    const COMPONENT_ID: &'static str = "checkout-log";
    const STATE_MANIFEST: Option<&'static str> = Some("checkout-record");

    fn empty_state(cart_id: &str) -> CheckoutRecord {
        CheckoutRecord {
            cart_id: cart_id.to_string(),
            at: 0,
            notified: false,
        }
    }

    fn handlers() -> KeyValueHandlers<CheckoutLog> {
        KeyValueHandlers::new()
            .command("record", CheckoutLog::record)
            .query("get", CheckoutLog::get)
    }
}
```

**Go**

The Go reference's key value entity keeps one name per profile:

```go
// ProfileState is a profile's state.
type ProfileState struct {
	Name string `json:"name"`
}

type kvEffect[R any] = ankka.KeyValueEffect[ProfileState, R]

// Profile is the key value entity: set, get and delete a name.
var Profile = ankka.KeyValue[ProfileState]("profile", func(string) ProfileState { return ProfileState{} },
	ankka.Update("set", func(_ context.Context, _ ProfileState, name string) (kvEffect[string], error) {
		if name == "" {
			return kvEffect[string]{}, ankka.Refuse(ankka.BadRequest, "a name is needed")
		}
		return ankka.UpdateState[ProfileState, string](ProfileState{Name: name}).ThenReplyValue("done"), nil
	}),
	ankka.Query("get", func(_ context.Context, s ProfileState, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		if s.Name == "" {
			return ankka.Reply("none"), nil
		}
		return ankka.Reply(s.Name), nil
	}),
	ankka.Update("delete", func(_ context.Context, _ ProfileState, _ ankka.Unit) (kvEffect[string], error) {
		return ankka.DeleteState[ProfileState, string]().ThenReplyValue("done"), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ProfileState](ankka.Manifest("profile"))),
)
```

The shape matches an event sourced entity's, and so do the rules. Handlers are declared with
`command` or `query` under a wire name that is separate from the method name. A query must return a
read-only effect, so it cannot change the state. The current value is `currentState` in Scala,
`self.state` in Python and `this.state` in TypeScript; the id is `context.entityId`, `self.entity_id`
and `this.entityId`. In Go a handler is a function given the state, declared with `ankka.Update` or
`ankka.Query`; the empty state is a function of the id, and `ankka.CommandFrom(ctx)` carries the id.

## Effects

| Scala | Python | TypeScript | Go | Meaning |
|---|---|---|---|---|
| `effects.updateState(s)` | `self.effects.update_state(s)` | `this.effects.updateState(s)` | `ankka.UpdateState[S, R](s)` | Replace the stored value with `s`, then choose a reply. |
| `.thenReply(s => value)` | `.then_reply(lambda s: value)` | `.ThenReply(func(S) R)`, `.ThenReplyValue(v)` | Reply with a value computed from the new state. |
| `.thenReplyState` | `.then_reply_state()` | `.ThenReplyState()` | Reply with the new state. |
| `.thenNoReply` | `.then_no_reply()` | `.ThenNoReply()` | Update and reply with nothing. |
| `.expireAfter(duration)` | `.expire_after(timedelta)` | `.ExpireAfter(d)` | Delete the entity once `duration` passes with no further update. |
| `effects.deleteEntity()` | `self.effects.delete_entity()` | `ankka.DeleteState[S, R]()` | Delete the entity; then choose a reply. |
| `effects.reply(value)` | `self.effects.reply(value)` | `ankka.KeepState[S, R]().ThenReplyValue(v)`; `ankka.Reply(v)` in a query | Reply without changing anything. |
| `effects.error(message, code)` | `self.effects.error(message, code)` | return `ankka.Refuse(code, message)` as the error | Refuse the command; nothing changes. The code defaults to `BadRequest`. |

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
val service = Ankka.service.register(CheckoutLog.descriptor)
```

```scala
val record =
  componentClient.forKeyValueEntity(EntityId("c1")).call(CheckoutLog.get).invoke()
```

**Java**

```java
/** Registered like any other component, and called through the component client. */
public static ServiceBuilder checkoutLogService() {
  return Ankka.service("carts").register(CheckoutLog.class);
}

public static CheckoutLog.CheckoutRecord checkoutRecord(ComponentClient client, String cartId) {
  return client.forKeyValueEntity(cartId).call(CheckoutLog::get).invoke();
}
```

**Kotlin**

```kotlin
val service = Ankka.service("shopping-cart").register(CheckoutLog::class.java)
ankkaTestKit(service) { // `service.start()` in a main; here the kit starts it
    val record = componentClient().forKeyValueEntity("c1").call(CheckoutLog::get).invoke()
```

**Python**

```python
def checkout_log_service() -> Any:
    """Registered like any other component, and called through the component client."""
    return Ankka.service().register(CheckoutLog)


async def checkout_record(client: ComponentClient, cart_id: str) -> CheckoutRecord:
    return await client.for_key_value_entity("checkout-log", cart_id).call("get").invoke(reply=CheckoutRecord)
```

**TypeScript**

```ts
/** Registered like any other component, and called through the component client. */
export function checkoutLogService() {
  return Ankka.service().register(CheckoutLog)
}

export function checkoutRecord(client: ComponentClient, cartId: string): Promise<CheckoutRecord> {
  return client.of(CheckoutLog, cartId).call(CheckoutLog.handlers.get).invoke()
}
```

**Rust**

```rust
fn checkout_log(request: &Request) -> Result<CheckoutRecord, HttpProblem> {
    let cart_id = request.path("cartId");
    Ok(request.client().invoke(CheckoutLog, cart_id, "get", ())?)
}
```

**Go**

```go
// CheckoutLogService registers the entity like any other component.
func CheckoutLogService() *ankka.Service { return ankka.NewService(CheckoutLog, CheckoutLogEndpoint) }

// CheckoutLogEndpoint reads a record through the client.
var CheckoutLogEndpoint = ankka.NewEndpoint("/checkout-log", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (CheckoutRecord, error) {
		return ankka.Invoke[CheckoutRecord](ctx, "checkout-log", req.Param("cartId"), "get", ankka.Unit{})
	}),
)
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

**Java**

```java
public static Result<Done> recordCheckout() {
  var kit = KeyValueEntityTestKit.of(CheckoutLog.class, "c1");
  var result = kit.call(CheckoutLog::record, 1_700_000_000_000L);

  // result.reply() is Done, and result.state() is CheckoutRecord("c1", 1700000000000, true)
  return result;
}
```

**Kotlin**

```kotlin
val kit = KeyValueEntityTestKit.of(CheckoutLog::class.java, "c1")
val result = kit.call(CheckoutLog::record, 1_700_000_000_000L)

assertEquals(Done.done(), result.reply())
assertEquals(1_700_000_000_000L, kit.state().at)
assertEquals(true, kit.state().notified)
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

**Rust**

```rust
#[test]
fn recording_a_checkout_replaces_the_value_and_acknowledges() {
    let mut kit = KeyValueEntityTestKit::<CheckoutLog>::new("c1");
    let result = kit.command("record", 1_700_000_000_000_i64);
    assert_eq!(result.answer.reply::<Done>(), Ok(Done));
    assert!(result.written, "recording should write a new value");
    assert_eq!(result.state.at, 1_700_000_000_000);
    assert!(result.state.notified);
    assert_eq!(kit.state().cart_id, "c1");
}
```

**Go**

```go
func TestRecordingACheckoutReplacesTheValue(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyValue(CheckoutLog, testkit.EntityID("c1"))
	defer kit.Close()
	r := kit.Command(ctx, "record", int64(1_700_000_000_000))
	if r.Refusal() != nil || r.Reply() != (ankka.Done{}) {
		t.Fatalf("record answered %v, %v", r.Reply(), r.Refusal())
	}
	if s := kit.State(); s.At != 1_700_000_000_000 || !s.Notified || s.CartID != "c1" {
		t.Fatalf("the record is %+v", s)
	}
	want := CheckoutRecord{CartID: "c1", At: 1_700_000_000_000, Notified: true}
	if q := kit.Query(ctx, "get", ankka.Unit{}); q.Reply() != want {
		t.Fatalf("get answered %+v", q.Reply())
	}
}
```

See [Testing](testing.md) for running the whole service against a real database.
