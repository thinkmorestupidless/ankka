# Views

> Build a queryable projection of entities' or a topic's changes, one row per source id or rows named by key from several sources, with declared and recursive queries, rebuilt by raising its version.

Source: https://docs.ankka.cloud/build/views/
A view is a queryable table built from changes. An entity can only be found by its id, so any other
question — which carts contain this product, which orders are unpaid, everything under this node of a tree
— needs a view.

A view comes in two shapes:

| | Plain view | Keyed view |
|---|---|---|
| Reads | one source: an entity's events, a key value entity's state, or a topic | one or more entities' events or states |
| Row key | the id of the entity the change came from, always | whatever the handler names |
| The current row | handed to the handler | read by the handler, by key or by a declared query |
| Rows one change writes | at most one | any number |
| Changes handled at once | many, in slices of the source | one, across every source and instance |

Use a plain view wherever one will do. A plain view keeps one row per source id, and every other way of
finding rows is a query, not a second key: re-keying a row by an attribute would silently orphan the old row
the first time the attribute changed. A keyed view is for the questions a plain view cannot answer: a row
that two entities keep up to date, such as a shipment that shows its customer's current name, or one change
that updates many rows, such as a customer's rename reaching every one of their shipments. See
[Keyed views](#keyed-views).

## Sources

A plain view reads exactly one source; a [keyed view](#keyed-views) reads one or more entities:

| Source | Scala | Python | TypeScript | Delivery |
|---|---|---|---|---|
| An event sourced entity's events | `ChangeSource.eventsOf(ShoppingCartEntity)` | `source = ShoppingCartEntity` | `static readonly source = ShoppingCartEntity` | Every event, in order, exactly once. |
| A key value entity's state | `ChangeSource.stateOf(CheckoutLog)` | `source = CheckoutLog` | `static readonly source = CheckoutLog` | The latest value; intermediate values can be skipped. |
| A broker topic | `ChangeSource.fromTopic("stock-events", serializer)` | `topic = "stock-events"` | `static readonly topic = "stock-events"` | At least once; see [Broker topics](topics.md). |

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
      // The deletion that follows removes the row: a discarded cart leaves the listing, which is
      // the view's default when its source is deleted.
      case Discarded =>
        effects.ignore()

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

from examples.shopping_cart.domain import CheckedOut, Discarded, ItemAdded, ItemRemoved, ShoppingCartEvent
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
            case Discarded():
                # The deletion that follows removes the row: a discarded cart leaves the listing,
                # which is the view's default when its source is deleted.
                return self.effects.ignore()
        raise AssertionError(event)
```

**TypeScript**

```ts
export const CartRow = s.record("CartRow", { cartId: s.string, quantities: s.stringMap(s.int), checkedOut: s.boolean })
export type CartRow = Infer<typeof CartRow>

export class CartRows extends View<ShoppingCartEvent, CartRow> {
  static readonly componentId = "cart-rows"
  static readonly source = ShoppingCartEntity
  static readonly events = ShoppingCartEntity.events
  static readonly row = jsonCodec(CartRow, "cart-row")
  static readonly queries = ["by-id", "all"]

  onChange(event: ShoppingCartEvent) {
    const current = this.row ?? { cartId: this.subject, quantities: {}, checkedOut: false }
    switch (event.type) {
      case "ItemAdded": {
        const { productId, quantity } = event.item
        return this.effects.updateRow({ ...current, quantities: { ...current.quantities, [productId]: (current.quantities[productId] ?? 0) + quantity } })
      }
      case "ItemRemoved": {
        const { [event.productId]: _removed, ...rest } = current.quantities
        return this.effects.updateRow({ ...current, quantities: rest })
      }
      case "CheckedOut":
        return this.effects.updateRow({ ...current, checkedOut: true })
      case "Discarded":
        // The deletion that follows removes the row: a discarded cart leaves the listing, which is the
        // view's default when its source is deleted.
        return this.effects.ignore()
    }
  }
}
```

| | Scala | Python | TypeScript |
|---|---|---|---|
| The current row, or none | `rowState: Option[Row]` | `self.row`, `None` when there is none | `this.row`, `undefined` when there is none |
| The source's id | `updateContext.subject` | `self.metadata.subject` | `this.subject` |
| Handle a change | `def onChange(change: Src): Effect` | `def on_change(self, event) -> ViewEffect` | `onChange(event)` |
| Handle a deletion | `override def onDelete: Effect` | `def on_delete(self) -> ViewEffect` | `onDelete()` |

A handler returns one of three effects:

| Scala | Python | Meaning |
|---|---|---|
| `effects.updateRow(row)` | `self.effects.update_row(row)` | Store `row` as the row for this source id, replacing any previous one. |
| `effects.deleteRow()` | `self.effects.delete_row()` | Remove the row. |
| `effects.ignore()` | `self.effects.ignore()` | Leave the row as it is. |

Build the new row from the current one rather than from scratch, as the sample does. The handler sees one
change at a time, so the row is the only memory it has of the changes before.

## When the source is deleted

When a source entity is deleted, the view's deletion handler runs, for an event sourced entity and a key
value entity alike: a deletion is a recorded change of the entity, delivered after every change before it.
By default the handler removes the row, which is what the cart's view relies on: a discarded cart deletes
itself, and its row leaves the listing with it. A view that must outlive its source, an order history
reading entities that are deleted once an order is placed, say, overrides the handler to keep the row as a
tombstone and mark it, rather than lose what the entity held.

An entity whose state has expired is not deleted: no deletion is delivered, and its row stays as it was.

## Registering a view

A view runs only in a service that has the projection runtime. In Scala, register the view's descriptor and
add `ProjectionRuntime()` as an extension; without it, every command still succeeds and every view stays
empty. In Python and TypeScript, register the class; the sidecar runs the projection.

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
service = (
    Ankka.service()
    .register(ShoppingCartEntity)
    .register(CartRows)
)
```

**TypeScript**

```ts
const service = Ankka.service()
  .register(ShoppingCartEntity)
  .register(CartRows)
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

Beyond those two, a view in any language answers the queries it declares; see
[Declared queries](#declared-queries). Conditions built at the call with `sql"…"` are a Scala service's
alone.

## Declared queries

A view declares the questions it can be asked beyond one row and every row. Each declared query has a
name and one SQL statement over the view's own table, and the values the statement takes are the `:name`s
it holds. A caller asks the query by name and gives the values; each value is bound as a parameter and is
never part of the statement's text, so nothing a caller gives can change what the statement does.

**Scala**

Declare a query as a `val` of the view's companion, naming the view's table with `table`:

```scala
/** Every row under the row `row`, to any depth. */
val under = query("under")(s"""
  WITH RECURSIVE below AS (
    SELECT row_key, payload FROM $table WHERE payload::jsonb->>'under' = :row
    UNION
    SELECT n.row_key, n.payload FROM $table n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
  )
  SELECT payload FROM below""")
```

Ask it through the view client, with each value it takes by name:

```scala
val under = clients.viewClient.forView(Nodes).ask(Nodes.under, "row" -> nodeId)
val first = clients.viewClient.forView(Nodes).ask(Nodes.under, 100, "row" -> nodeId)   // at most 100 rows
```

**Python**

```python
from ankka.view import query, table_of

class Nodes(View[NodeEvent, NodeRow]):
    component_id = "nodes"
    source = NodeEntity
    event_codec = NODE_EVENTS
    row_codec = NODE_ROW
    under = query("under", f"""
        WITH RECURSIVE below AS (
          SELECT row_key, payload FROM {table_of("nodes")} WHERE payload::jsonb->>'under' = :row
          UNION
          SELECT n.row_key, n.payload FROM {table_of("nodes")} n
            JOIN below b ON n.payload::jsonb->>'under' = b.row_key)
        SELECT payload FROM below""")

rows = await self.client.views.ask("nodes", "under", NodeRow, {"row": node_id})
```

**TypeScript**

```ts
import { declaredQuery, tableOf } from "ankka"

export class Nodes extends View<NodeEvent, NodeRow> {
  static readonly componentId = "nodes"
  static readonly declared = [
    declaredQuery("under", `
      WITH RECURSIVE below AS (
        SELECT row_key, payload FROM ${tableOf("nodes")} WHERE payload::jsonb->>'under' = :row
        UNION
        SELECT n.row_key, n.payload FROM ${tableOf("nodes")} n
          JOIN below b ON n.payload::jsonb->>'under' = b.row_key)
      SELECT payload FROM below`),
  ]
  // ...
}

const rows = await client.views.ask("nodes", "under", { row: nodeId }, NodeRowShape)
```

The statement must select the rows' `payload` column: the answer is rows of the view's own row type. A
value is text; a statement that needs a number casts it, as `(:depth)::int`. A query answers with at most
1000 rows unless the caller gives another limit, in the order the statement gives them.

### What stops a service from starting

Every declared statement is checked when the service starts, by reading the parsed statement, never the
text, so a table named in a comment or a string is no table. A service whose view declares any of these
does not start, and the problem names the view, the query and what is wrong:

- a statement that cannot be read as SQL, or no statement;
- more than one statement;
- a statement that is not a `SELECT` (an `UPDATE`, a `DELETE`, an `INSERT`), or a `WITH` item that is not
  one;
- `SELECT … INTO`, or a locking clause such as `FOR UPDATE`;
- a table that is not the view's own, named in the `FROM`, a join, a subquery or a `WITH` item's body —
  the problem names that table;
- a table named with its schema, even the view's own;
- a function that reads a query or a relation given as text (`query_to_xml`, `table_to_xml` and their
  kin), reads a file (`pg_read_file`), reaches another database (`dblink`) or a large object (`lo_…`),
  takes an advisory lock, changes a setting (`set_config`), or reaches another connection;
- a value written as `$1` or `?` rather than `:name`, or a name that is not `[a-z][a-z0-9_]*`;
- a query named like a fixed way of asking (`get`, `all`, `where`, `ordered`, `count`, `by-id`,
  `by-key`), or two queries with one name.

The check holds a developer's statement to one read of the view's own table; it is a guard for the
developer who wrote it, not a wall against them, since a service's database is its own. What a statement
does when it runs is the database's to hold: a declared query runs in a read-only transaction, so it cannot
write whatever it calls, and the database ends it when the service's ask timeout runs out, answering the
caller with a timeout rather than leaving it running.

### Walking a tree

A recursive query follows rows to any depth in one statement. A view of a tree keeps one row per node
holding its parent's key, and the `under` query above answers every row under a node, however deep the
tree. Write it with `UNION` rather than `UNION ALL`: rows already found are not followed again, so a cycle
in the data ends the walk instead of running until the timeout.

## Keyed views

A keyed view reads one or more entities, each through a handler of its own, and every handler names the
rows it writes and deletes by key. The view below reads two entities. An event of the left entity names a
row and the right entity it holds, and the left's handler writes that row from what it held before. The
right's handler finds every row holding it by asking the view's own declared query, and writes each.

```scala
/** A row the left writes under the key it names, holding a right entity's id. */
final case class JoinedRow(key: String, holding: String, notes: Vector[String])

final class JoinedRowsView extends KeyedView[JoinedRow]:

  /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
  def onLeft(event: Noted, change: Change): Effect =
    val Array(key, holding) = event.text.split('|')
    val held                = change.rows.get(key).fold(Vector.empty[String])(_.notes)
    effects.updateRow(key, JoinedRow(key, holding, held :+ "left"))

  /** The right finds every row holding it by asking the view's own query, and notes itself. */
  def onRight(@scala.annotation.unused event: Noted, change: Change): Effect =
    val theirs = change.rows.ask(JoinedRows.ofRight, "holding" -> change.subject)
    effects.updateRows(theirs.map(row => row.key -> row.copy(notes = row.notes :+ "right")))

object JoinedRows
    extends KeyedView.Companion[JoinedRowsView, JoinedRow](
      ComponentId("joined-rows"),
      Codecs.serializer[JoinedRow]("joined-row")
    ):
  val lefts  = source(ChangeSource.eventsOf(JoinedLeft))(_.onLeft)
  val rights = source(ChangeSource.eventsOf(JoinedRight))(_.onRight)

  /** The rows holding one right entity, by key: the same statement in every language. */
  val ofRight = query("of-right")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new JoinedRowsView
```

A handler is handed the change and a handle on the view's own rows: `change.rows.get(key)` and
`change.rows.ask(query, values*)`, with `change.subject`, the id of the entity the change came from. It
reads no other view. It returns row changes: `effects.updateRow(key, row)`, `effects.deleteRow(key)`, their
plural forms, and `++` to say several things at once. In Python a keyed view's handlers are methods marked
`@on(Entity, codec)`, in TypeScript `static sources = [on(Entity, Events, handler)]`, and in Rust
`Sources::new().on(...)`; each reads its rows through `rows` and answers the same row changes.

The rules a keyed view keeps:

- **The platform deletes no row a view did not name.** Moving a row is the handler's to say: delete it
  under its old key and write it under its new one, in the same effect. A row moved any other way is
  left behind under its old key.
- **A row written by two sources is written whole by each**, from the row as it stands, which the handler
  reads before it writes. The view handles one change at a time, so what a handler reads is what it writes
  over: nothing another change writes falls in between, and no write is lost.
- **The rows one change names are written together or not at all.** A change one of whose rows cannot be
  written writes none of them, and is handled again.
- **A source is read in order, and once.** Over an event sourced entity a change's rows and the record of
  how far the source has been read are written in one transaction, so the change is applied exactly once.
  Over a key value entity the record is made afterwards, and a change may be handled again, as it may for
  any view of a key value entity: a key value entity keeps no history to make it otherwise.
- **A keyed view reads entities only.** A topic and an entity may not be sources of one view, and a service
  that declares one does not start.

Handling one change at a time is the price of these rules: a keyed view has one writer, whatever the number
of instances, and its throughput is its handlers'. A handler that waits on its own view's read longer than
the service's ask timeout fails its change, which is handled again. One change's rows may weigh at most
4 MiB together, and a row's key is at least one character.

## Rebuilding by version

A view declares a version, and raising it rebuilds the view: its table is emptied, once, however many
instances start, and every source is read again from its beginning, so the table holds only rows written by
the view as it is now. For a view that reads entities, that is every event and every state change they ever
recorded; for a view that reads a topic, as far back as the broker retains (see
[Broker topics](topics.md#rebuilding-by-version)). A view that declares no version is at version 1 and is
never rebuilt.

```scala
object Shipments extends KeyedView.Companion[ShipmentsView, ShipmentRow](...):
  override def version = Some(2)
```

While it is rebuilt, the view answers from the rows written so far. During a rolling update, an instance
declaring the lower version stops writing to the view as soon as the higher one has rebuilt it, and says so
in its log; a part of the view's work held by such an instance waits until it leaves, so the view may lag
until the update completes, and no row the lower version writes survives. A service rolled back to a lower
version leaves the rows as they are and writes nothing to them. A consumer that reads an entity declares no version.

Raise a view's version only in a deploy after the one that brought every instance of the service to a
release that knows versions of views that read entities: an instance from before then cannot be told to stop
writing. See [Upgrading](../deploy/upgrading.md).

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

- A view writes one table, its own, and a query reads that table alone: there is no query across two
  views' tables.
- A topic and an entity may not be sources of one view, and a keyed view reads no topic.
- A keyed view handles one change at a time; its throughput does not grow with instances.
- A view over a topic starts at the earliest message the broker holds unless it says otherwise, and is
  rebuilt by raising its version, as far back as the broker retains. See
  [Broker topics](topics.md#rebuilding-by-version).

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

A keyed view's handlers are tested with `KeyedViewTestKit`, with no actor system and no database. A change
of a source goes to that source's handler, the rows it names are written to a map the test reads, and every
row round-trips through the view's serializer. A declared query is SQL and there is no database to run it,
so the test says what each query answers; a handler that asks a query the test has not answered fails the
test, naming the query. Building the kit checks the view's declared statements as a service's start would.

```scala
test("the right's change notes every row holding it, found by the view's own query") {
  val kit = KeyedViewTestKit(JoinedRows)
  kit.change(JoinedRows.lefts, "a", Noted("r1|b"))
  kit.change(JoinedRows.lefts, "a", Noted("r2|b"))
  kit.change(JoinedRows.lefts, "a", Noted("r3|c"))
  // The query is the database's to run; the test says what it answers.
  kit.answering(JoinedRows.ofRight)(values =>
    kit.rows.values.filter(_.holding == values("holding")).toVector
  )
  kit.change(JoinedRows.rights, "b", Noted("anything"))
  assertEquals(kit.row("r1").map(_.notes), Some(Vector("left", "right")))
  assertEquals(kit.row("r2").map(_.notes), Some(Vector("left", "right")))
  assertEquals(kit.row("r3").map(_.notes), Some(Vector("left")))
}
```

Python, TypeScript and Rust have a `KeyedViewTestKit` of the same shape.

In Scala, a plain view is tested with the integration testkit, which runs the real projection against a
real database:

```scala
testKit = AnkkaTestKit.start(
  Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutNotifier.descriptor),
  Seq(ProjectionRuntime.withPublisher(publisher))
)
```

See [Testing](testing.md).
