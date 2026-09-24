# Views

> Build a queryable projection of an entity's or a topic's changes, keep one row per source id, and query the rows with SQL in Scala or by key in Python.

Source: https://docs.ankka.cloud/build/views/
A view is a queryable table built from one source's changes. An entity can only be found by its id, so
any other question — which carts contain this product, which orders are unpaid, which are the largest —
needs a view. The runtime reads the source's changes in order, hands each one to the view's handler with
the current row for that source id, and stores the row the handler returns.

A view keeps one row per source id. The row's key is always the id of the entity the change came from,
and every other way of finding rows is a query, not a second key. Re-keying a row by an attribute would
silently orphan the old row the first time the attribute changed.

## Sources

A view reads exactly one source:

| Source | Scala | Python | Delivery |
|---|---|---|---|
| An event sourced entity's events | `ChangeSource.eventsOf(ShoppingCartEntity)` | `source = ShoppingCartEntity` | Every event, in order, exactly once. |
| A key value entity's state | `ChangeSource.stateOf(PreferencesEntity)` | `source = CheckoutLog` | The latest value; intermediate values can be skipped. |
| A broker topic | `ChangeSource.fromTopic("stock-events", serializer)` | `topic = "stock-events"` | At least once; see [Broker topics](topics.md). |

The source is built from the source component's own declaration, so a view over the cart is typed
against the cart's event type and decodes with the cart's own serializer. It cannot drift when the entity's
serializer changes.

## Writing a view

A view declares its row type and a handler for changes. The handler receives one change and the current
row for its source id, and returns what should happen to the row:

**Scala**

```scala
package shoppingcart.application

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.ShoppingCartEvent
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * A queryable projection of every cart.
 *
 * The entity can only be found by cart id. This view exists to answer the questions it cannot:
 * which carts contain a product, which have been checked out, which are the largest.
 */
final case class CartRow(
    cartId: String,
    quantities: Map[String, Int],
    checkedOut: Boolean
):
  def productIds: List[String] = quantities.keys.toList.sorted
  def totalQuantity: Int       = quantities.values.sum

final class CartRowsView extends View[ShoppingCartEvent, CartRow]:

  def onChange(event: ShoppingCartEvent): Effect =
    val current = rowState.getOrElse(CartRow(updateContext.subject, Map.empty, checkedOut = false))
    event match
      case ItemAdded(item) =>
        val existing = current.quantities.getOrElse(item.productId, 0)
        effects.updateRow(
          current.copy(quantities =
            current.quantities.updated(item.productId, existing + item.quantity)
          )
        )
      case ItemRemoved(productId) =>
        effects.updateRow(current.copy(quantities = current.quantities - productId))
      case CheckedOut =>
        effects.updateRow(current.copy(checkedOut = true))

  /**
   * Keeps the row after the cart is deleted.
   *
   * Checkout deletes the entity, but a checked-out cart is exactly what an order history needs.
   * This is the tombstone case: the row outlives the entity that produced it.
   */
  override def onDelete: Effect =
    rowState match
      case Some(row) => effects.updateRow(row.copy(checkedOut = true))
      case None      => effects.ignore()

object CartRows
    extends View.Companion[CartRowsView, ShoppingCartEvent, CartRow](
      componentId = ComponentId("cart-rows"),
      source = ChangeSource.eventsOf(ShoppingCartEntity),
      rowSerializer = Codecs.serializer[CartRow]("cart-row")
    ):
  def create(ctx: ViewComponentContext) = new CartRowsView
```

**Python**

```python
"""A queryable projection of every cart: the entity answers by cart id, this answers the rest —
which carts contain a product, which have been checked out, which are the largest."""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from ankka import json_codec
from ankka.effects.view import ViewEffect
from ankka.view import View

from examples.shopping_cart.domain import CheckedOut, ItemAdded, ItemRemoved, ShoppingCartEvent
from examples.shopping_cart.entity import ShoppingCartEntity


@dataclass(frozen=True)
class CartRow:
    cartId: str
    quantities: dict[str, int] = field(default_factory=dict)
    checkedOut: bool = False

    @property
    def total_quantity(self) -> int:
        return sum(self.quantities.values())


class CartRows(View[ShoppingCartEvent, CartRow]):
    component_id = "cart-rows"
    source = ShoppingCartEntity
    event_codec = ShoppingCartEntity.event_codec
    row_codec = json_codec(CartRow, "cart-row")
    queries = ("by-id", "all")

    def on_change(self, event: ShoppingCartEvent) -> ViewEffect:
        current = self.row or CartRow(self.metadata.subject or "")
        match event:
            case ItemAdded(item):
                quantities = {**current.quantities, item.productId: current.quantities.get(item.productId, 0) + item.quantity}
                return self.effects.update_row(replace(current, quantities=quantities))
            case ItemRemoved(product_id):
                return self.effects.update_row(replace(current, quantities={k: v for k, v in current.quantities.items() if k != product_id}))
            case CheckedOut():
                return self.effects.update_row(replace(current, checkedOut=True))
        raise AssertionError(event)

    def on_delete(self) -> ViewEffect:
        """Checkout deletes the cart, but a checked-out cart is exactly what an order history
        needs: the row outlives the entity that produced it."""
        if self.row is None:
            return self.effects.ignore()
        return self.effects.update_row(replace(self.row, checkedOut=True))
```

| | Scala | Python |
|---|---|---|
| The current row, or none | `rowState: Option[Row]` | `self.row`, `None` when there is none |
| The source's id | `updateContext.subject` | `self.metadata.subject` |
| Handle a change | `def onChange(change: Src): Effect` | `def on_change(self, event) -> ViewEffect` |
| Handle a deletion | `override def onDelete: Effect` | `def on_delete(self) -> ViewEffect` |

A handler returns one of three effects:

| Scala | Python | Meaning |
|---|---|---|
| `effects.updateRow(row)` | `self.effects.update_row(row)` | Store `row` as the row for this source id, replacing any previous one. |
| `effects.deleteRow()` | `self.effects.delete_row()` | Remove the row. |
| `effects.ignore()` | `self.effects.ignore()` | Leave the row as it is. |

Build the new row from the current one rather than from scratch, as the sample does. The handler sees one
change at a time, so the row is the only memory it has of the changes before.

## When the source is deleted

When a source entity is deleted, the view's deletion handler runs. By default it removes the row. Override
it to keep a tombstone instead: the cart deletes itself on checkout, and its view keeps the row and marks it
checked out, because a checked-out cart is exactly what an order history needs. The row outlives the entity
that produced it.

## Registering a view

A view runs only in a service that has the projection runtime. In Scala, register the view's descriptor and
add `ProjectionRuntime()` as an extension; without it, every command still succeeds and every view stays
empty. In Python, register the class; the sidecar runs the projection.

**Scala**

```scala
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}

val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime())
  .start()
```

**Python**

```python
service = Ankka.service().register(ShoppingCartEntity).register(CartRows)
```

A view over a topic also needs a broker. See [Broker topics](topics.md).

## Querying a view in Scala

A view's rows live in a Postgres table, one row per source id, with the row stored as JSON. Queries are
real SQL over that JSON, not a query language of ankka's own. Get a handle on a view's rows from the
view client:

```scala
private def rows = testKit.service.viewClient.forView(CartRows)
```

The view client is `testKit.service.viewClient` in a test, `clients.viewClient` in an
[HTTP endpoint](http-endpoints.md), and `service.viewClient` on a started service. The handle offers:

| Method | Returns |
|---|---|
| `get(key)` | The row for one source id, or `None`. |
| `where(condition, limit = 1000)` | Every row matching a SQL condition. |
| `ordered(condition, order, limit = 1000)` | Matching rows in a SQL order. |
| `all(limit = 1000)` | Every row. |
| `count(condition)` | How many rows match; every row with no condition. |

Each has an `…Async` variant returning a `Future`, for fanning several queries out at once.

Conditions are built with the `sql"…"` interpolator and a few helpers that reach into the row's JSON, all
from `com.thinkmorestupidless.ankka.runtime.SqlSyntax`:

```scala
// Query by an attribute rather than by key — the reason views exist.
val found = rows.where(jsonText("cartId") ++ sql" = ${"view-q-1"}")
```

```scala
assert(rows.count() >= 1L)
assert(rows.all().nonEmpty)
assert(rows.count(jsonText("cartId") ++ sql" = ${"nope-does-not-exist"}") == 0L)
```

| Helper | Renders | Use |
|---|---|---|
| `jsonText("email")` | `payload::jsonb->>'email'` | Compare a text field. |
| `jsonText("address", "city")` | `payload::jsonb->'address'->>'city'` | A nested text field. |
| `jsonNumber("total")` | `(payload::jsonb->>'total')::numeric` | Compare or order numerically. |
| `jsonContains("members", "alice")` | `payload::jsonb->'members' @> '["alice"]'` | Whether a JSON array contains a value. |

Values interpolated into `sql"…"` are never spliced into the SQL text. `sql" = ${email}"` renders as
`= $1` with `email` bound as a parameter, so user input in a query cannot become SQL injection. Fragments
join with `++`, which renumbers their parameters. `SqlFragment.raw(text)` adds SQL with no parameters and
is only for text the developer writes, never for input.

An ordered query over the cart rows, returning the first twenty open carts by id, looks like this:

```scala
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

val openCarts = rows.ordered(
  condition = jsonText("checkedOut") ++ sql" = ${"false"}",
  order = jsonText("cartId") ++ SqlFragment.raw(" DESC"),
  limit = 20
)
```

`jsonText` reads every field as text, so a boolean compares against `"true"` or `"false"`. Use
`jsonNumber` to compare or order a numeric field as a number rather than as text.

The table for a view is named `ankka_view_` followed by the component id with every character that is not
a letter or digit replaced by `_`: `cart-rows` is stored in `ankka_view_cart_rows`. A query that must be fast
at scale needs a Postgres expression index on the same term the query uses, such as
`((payload::jsonb->>'cartId'))`.

## Querying a view in Python

A Python service reads a view through the component client, by source id or all at once:

```python
row = await self.client.views.get("cart-rows", cart_id, CartRow)     # one row, or None
rows = await self.client.views.all("cart-rows", CartRow)             # every row, up to 1000
```

These are the only two queries a view in a Python service answers. SQL conditions over the rows are
available only to a Scala service today.

## Consistency

A view is eventually consistent with its source. A command's reply is sent once its events are persisted,
and the view catches up shortly afterwards, usually within milliseconds and not within any bound. A read
of a view immediately after a write may not see the write yet, while a read of the entity always does.
Read the entity when the answer must include the caller's own last change; read the view to find things.

Over an event sourced entity, a view is exactly once: the row update and the record of how far the view
has read are committed in one transaction, so a crash between them cannot apply a change twice. Over a key
value entity or a topic, delivery is at least once, and the handler should give the same row when it sees a
change twice. See [Consistency and delivery](../concepts/consistency.md).

In a test, poll for the expected row until a deadline instead of reading once. That is not a workaround for
a race; it is the consistency model the view actually has.

## Limits

- A view reads one source and writes one table. Joining two sources into one view is not supported;
  project each into its own view and combine the results where they are read.
- A view does not rebuild its rows when its code changes. A changed handler applies to changes from then on.
- A view over a topic sees only messages published after it started.

See [Limitations](../reference/limitations.md) for the full list.

## Testing

In Python, `ViewTestKit` feeds changes to a view and keeps one row per key, as the sidecar would, with no
sidecar:

```python
kit = ViewTestKit.of(CartRows)
kit.on_change("c1", ItemAdded(LineItem("p1", "Pen", 2)))
assert kit.get("c1") == CartRow("c1", {"p1": 2}, False)
assert isinstance(kit.on_delete("c1"), UpdateRow)
```

In Scala, a view is tested with the integration testkit, which runs the real projection against a real
database:

```scala
testKit = AnkkaTestKit.start(
  Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutNotifier.descriptor),
  Seq(ProjectionRuntime.withPublisher(publisher))
)
```

See [Testing](testing.md).
