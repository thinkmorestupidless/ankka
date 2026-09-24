# Handlers and wire names

> How ankka names components, handlers and stored types independently of your code's names, why those names are a versioning boundary, and which renames are safe in a running system.

Source: https://docs.ankka.cloud/concepts/wire-names/
Everything the platform stores or routes by has a name you declare as a string, separate from the name of
the class or method in your code. A component has a **component id**, each handler has a **wire name**, and
each stored type has a **manifest**. Those strings are protocol: they are written into the database and
used to address calls. The code names are yours to change at any time.

## Declaring handlers

A handler is declared under its wire name, beside the method that implements it.

**Scala**

```scala
object ShoppingCartEntity
    extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
      componentId = ComponentId("shopping-cart"),
      stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
      eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
    ):
  def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)

  val addItem = command("add-item")(_.addItem)
  val getCart = query("get-cart")(_.getCart)
```

**Python**

```python
class ShoppingCartEntity(EventSourcedEntity[ShoppingCart, ShoppingCartEvent]):
    component_id = "shopping-cart"
    state_codec = json_codec(ShoppingCart, "shopping-cart")
    event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")

    @command("add-item")
    def add_item(self, item: LineItem) -> EventSourcedEffect[ShoppingCart, ShoppingCartEvent, Done]: ...

    @query("get-cart")
    def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]: ...
```

In Scala the declaration is a value on the companion object, and a call site uses that value:

```scala
componentClient.forEventSourcedEntity(cartId).call(ShoppingCartEntity.addItem).invoke(item)
```

The call is fully typed — the argument and the reply type come from the declaration — and involves no
reflection. There is no classpath scanning and no inspection of method names or bytecode. In Python a call
names the component id and the wire name as strings:

```python
await client.for_event_sourced_entity("shopping-cart", cart_id).call("add-item").invoke(item, reply=Done)
```

Because Scala and Python address a handler by the same component id and wire name, and store data under
the same manifests, a service can be ported from one language to the other without its callers or its
stored data noticing.

## Why the wire name is not the method name

A method name is a detail of the code. A wire name is a promise to everything outside it:

- **During a rolling update**, old and new instances run side by side and call each other. A call sent by
  an old instance names the handler as the old code knew it. If the name came from the method, renaming a
  method would make every such call fail until the update finished.
- **Timers are stored.** A timer holds the component id and the handler's wire name of the call it will
  make, possibly hours or days later. A handler renamed in the meantime would leave the timer calling a name
  that no longer exists, and it would be retried forever.
- **Other services and other languages** address handlers by name, and cannot see your code at all.

Declaring the name separately makes the boundary visible. Renaming a method is a refactoring; changing a
string in a `command(...)` or `@command(...)` is a protocol change, and it looks like one in review.

## What each name is used for

| Name | Declared as | Used for |
|---|---|---|
| Component id | `ComponentId("shopping-cart")`, `component_id = "shopping-cart"` | addressing calls; the journal's entity type; view table names; timers; traces |
| Wire name | `command("add-item")`, `@command("add-item")`, and likewise `query`, workflow `step`, timed action `handler` | addressing one handler; timers; traces |
| Manifest | `Codecs.serializer[T]("shopping-cart-event")`, `json_codec(T, "shopping-cart-event")` | stored with every event, snapshot, state and row, to say how to read it |
| Route path | `get("/{cartId}")`, `@get("/{cartId}")` | the service's public HTTP API |

## What is safe to change

| Change | Safe? | Why |
|---|---|---|
| Rename a Scala method, Python method, class or package | yes | nothing outside the code knows these names |
| Add a handler, a component or a route | yes | nothing addresses it yet |
| Remove a handler nothing calls any more | yes, once no caller or timer uses it | a stored timer that names it will fail on every attempt |
| Change a handler's wire name | no | in-flight calls, stored timers and other services use the old name |
| Change a component id | no | the journal, snapshots, view tables and timers are keyed by it; the component would start empty |
| Change a manifest | no | stored data carries the old manifest and could no longer be read |
| Change a route path | a breaking API change | callers outside the service use it |

To change a name that is not safe to change, add the new one beside the old, move callers across, and
remove the old one only when nothing can still use it: no instance running old code, no timer still
scheduled, no caller outside the service. For stored types, see
[Serialization and evolution](../build/serialization.md).

## Names are lowercase and hyphenated by convention

ankka does not require a style for wire names and component ids, but every sample uses lowercase words
joined by hyphens: `shopping-cart`, `add-item`, `get-cart`. Traces and the local console show a call as
`<component id>#<wire name>`, for example `shopping-cart#add-item`, so consistent names read well there.
A service's own name, in its descriptor, does have rules, because it becomes a Kubernetes name and part of a
hostname: lowercase letters, digits and hyphens, starting with a letter, at most 63 characters.
