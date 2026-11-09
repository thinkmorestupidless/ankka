# Views

> Build a queryable projection of entities' or a topic's changes, one row per source id or rows named by key from several sources, with declared and recursive queries, rebuilt by raising its version.

Source: https://docs.ankka.cloud/build/views/
A view is a queryable table built from changes. An entity can only be found by its id, so any other
question — which carts contain this product, which orders are unpaid, everything under this node of a tree
— needs a view.

A view comes in two shapes:

| | Plain view | Keyed view |
|---|---|---|
| Reads | one source: an entity's events, a key value entity's state, a workflow's states, or a topic | one or more entities' events or states, or workflows' states |
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

A plain view reads exactly one source; a [keyed view](#keyed-views) reads one or more entities and
workflows:

| Source | Scala | Python | TypeScript | Delivery |
|---|---|---|---|---|
| An event sourced entity's events | `ChangeSource.eventsOf(ShoppingCartEntity)` | `source = ShoppingCartEntity` | `static readonly source = ShoppingCartEntity` | Every event, in order, exactly once. |
| A key value entity's state | `ChangeSource.stateOf(CheckoutLog)` | `source = CheckoutLog` | `static readonly source = CheckoutLog` | The latest value; intermediate values can be skipped. |
| A workflow's states | `ChangeSource.stateOf(CheckoutWorkflow)` | `source = CheckoutWorkflow` | `static readonly source = CheckoutWorkflow` | Each state the workflow records, in order, exactly once, with its standing; see [Reading a workflow](#reading-a-workflow). |
| A broker topic | `ChangeSource.fromTopic("stock-events", serializer)` | `topic = "stock-events"` | `static readonly topic = "stock-events"` | At least once; see [Broker topics](topics.md). |

In Java and Kotlin a view's `source()` answers `ChangeSource.of(ShoppingCart.class)` for an entity of either
kind, `ChangeSource.ofWorkflow(Checkout.class)` for a workflow, and `ChangeSource.ofTopic("stock-events",
StockEvent.class)` for a topic.

A view over a topic must also say where it starts, and is refused when the service starts if it does not;
see [Where a source starts](topics.md#where-a-source-starts). When the project declares the topic with a
[contract](topics.md#contracts), the view states the same contract, in Scala with
`TopicOptions(contract = Some(orders))` on `ChangeSource.fromTopic`, or the service is refused at start
naming the topic and the contract the project declares. A topic source may also name
[a broker the project declares](topics.md#a-topic-on-another-broker) and ask to read its partitions
[in parallel](topics.md#reading-partitions-in-parallel).

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
    checkedOut: Boolean,
    // Each product's name, so the row says all a watcher of the cart needs. Absent from rows
    // written before it was added, which read as no names.
    names: Map[String, String] = Map.empty
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
          current.copy(
            quantities = current.quantities.updated(item.productId, existing + item.quantity),
            names = current.names.updated(item.productId, item.name)
          )
        )
      case ItemRemoved(productId) =>
        effects.updateRow(
          current.copy(
            quantities = current.quantities - productId,
            names = current.names - productId
          )
        )
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
  /** The carts not checked out. Watchable: it selects each row's key beside the row. */
  val openCarts = query("open-carts")(
    s"SELECT row_key, payload FROM $table WHERE (payload::jsonb->>'checkedOut')::boolean = false"
  ).watched

  def create(ctx: ViewComponentContext) = new CartRowsView
```

**Java**

```java
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.CartRow> {

  @Manifest("cart-row")
  public record CartRow(
      String cartId,
      Map<String, Integer> quantities,
      boolean checkedOut,
      Map<String, String> names) {}

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected ViewEffect<CartRow> onChange(CartEvent event) {
    var current =
        rowState().orElse(new CartRow(updateContext().subject(), Map.of(), false, Map.of()));
    var quantities = new HashMap<>(current.quantities());
    var names = new HashMap<>(current.names());
    return switch (event) {
      case CartEvent.ItemAdded added -> {
        quantities.merge(added.item().productId(), added.item().quantity(), Integer::sum);
        names.put(added.item().productId(), added.item().name());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.ItemRemoved removed -> {
        quantities.remove(removed.productId());
        names.remove(removed.productId());
        yield effects()
            .updateRow(new CartRow(current.cartId(), quantities, current.checkedOut(), names));
      }
      case CartEvent.CheckedOut ignored ->
          effects()
              .updateRow(
                  new CartRow(current.cartId(), current.quantities(), true, current.names()));
      // The deletion that follows removes the row: a discarded cart leaves the listing.
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
```

**Kotlin**

```kotlin
/**
 * Every cart as a row, to answer what the entity cannot: which carts hold a product, which are
 * checked out. The row's JSON is the Scala cart's `cart-row`.
 */
@ComponentId("cart-rows")
class CartRows : View<CartEvent, CartRows.CartRow>() {
    @Manifest("cart-row")
    data class CartRow(
        val cartId: String,
        val quantities: Map<String, Int>,
        val checkedOut: Boolean,
        val names: Map<String, String>,
    )

    override fun source(): ChangeSource<CartEvent> = ChangeSource.of(ShoppingCart::class.java)

    override fun onChange(event: CartEvent): ViewEffect<CartRow> = effects {
        val row = rowState().orNull() ?: CartRow(updateContext().subject(), emptyMap(), false, emptyMap())
        when (event) {
            is CartEvent.ItemAdded ->
                updateRow(
                    row.copy(
                        quantities = row.quantities + (event.item.productId to (row.quantities[event.item.productId] ?: 0) + event.item.quantity),
                        names = row.names + (event.item.productId to event.item.name),
                    )
                )
            is CartEvent.ItemRemoved ->
                updateRow(row.copy(quantities = row.quantities - event.productId, names = row.names - event.productId))
            CartEvent.CheckedOut -> updateRow(row.copy(checkedOut = true))
            // The deletion that follows removes the row: a discarded cart leaves the listing.
            CartEvent.Discarded -> ignore()
        }
    }
}
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

**Rust**

```rust
pub struct CartRows;

impl View for CartRows {
    type Row = CartRow;
    type Event = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("cart-row");

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_event(
        row: Option<CartRow>,
        event: ShoppingCartEvent,
        ctx: &Context,
    ) -> ViewEffect<CartRow> {
        let cart_id = ctx.metadata().subject().unwrap_or_default();
        let mut row = row.unwrap_or_else(|| CartRow::new(cart_id));
        match event {
            ShoppingCartEvent::ItemAdded { item } => {
                *row.quantities.entry(item.product_id).or_default() += item.quantity;
            }
            ShoppingCartEvent::ItemRemoved { product_id } => {
                row.quantities.remove(&product_id);
            }
            ShoppingCartEvent::CheckedOut => row.checked_out = true,
            // The deletion that follows removes the row: a discarded cart leaves the listing, which
            // is the view's default when its source is deleted.
            ShoppingCartEvent::Discarded => return view::ignore(),
        }
        view::update_row(row)
    }

    fn queries() -> Vec<&'static str> {
        vec!["by-id", "all"]
    }
}
```

**Go**

```go
// Row is one cart as the view keeps it: what it holds by product, and whether it was checked out.
type Row struct {
	CartID     string         `json:"cartId"`
	Quantities map[string]int `json:"quantities"`
	CheckedOut bool           `json:"checkedOut"`
}

// RowsID is the view's id.
const RowsID = "cart-rows"

// Rows is a queryable projection of every cart: the entity answers by cart id, this answers the
// rest.
var Rows = ankka.View[Row](RowsID,
	ankka.OnChange(ankka.Changes(Entity), func(ctx context.Context, e Event, row *Row) (ankka.ViewEffect[Row], error) {
		current := Row{Quantities: map[string]int{}}
		if row != nil {
			current = *row
			current.Quantities = maps.Clone(row.Quantities)
			if current.Quantities == nil {
				current.Quantities = map[string]int{}
			}
		}
		if c, ok := ankka.ChangeFrom(ctx); ok && current.CartID == "" {
			current.CartID = c.Subject
		}
		switch e := e.(type) {
		case ItemAdded:
			current.Quantities[e.Item.ProductID] += e.Item.Quantity
		case ItemRemoved:
			delete(current.Quantities, e.ProductID)
		case CheckedOut:
			current.CheckedOut = true
		default:
			// Discarded: the deletion that follows removes the row.
			return ankka.IgnoreChange[Row](), nil
		}
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[Row](ankka.Manifest("cart-row"))),
)
```

| | Scala | Python | TypeScript |
|---|---|---|---|
| The current row, or none | `rowState: Option[Row]` | `self.row`, `None` when there is none | `this.row`, `undefined` when there is none |
| The source's id | `updateContext.subject` | `self.metadata.subject` | `this.subject` |
| Handle a change | `def onChange(change: Src): Effect` | `def on_change(self, event) -> ViewEffect` | `onChange(event)` |
| Handle a deletion | `override def onDelete: Effect` | `def on_delete(self) -> ViewEffect` | `onDelete()` |

In Java and Kotlin the row is `rowState()`, an `Optional` (`rowState().orNull()` in Kotlin), the source's id
is `updateContext().subject()`, and a view overrides `onChange(change)` and `onDelete()`.

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

## Reading a workflow

A view can read a workflow as it reads a key value entity: in Scala with `ChangeSource.stateOf` of the
workflow's companion, in Java and Kotlin with `ChangeSource.ofWorkflow` of the workflow's class, in Go
with `ankka.WorkflowSource(workflow)`, and in Python, TypeScript and Rust by naming the workflow where an entity would be
named. The view is handed each state the workflow records, decoded with the workflow's own codec, in the
order the workflow recorded them. A row and the record of how far the view has read are written in one
transaction, so each state is applied exactly once.

With each state comes the workflow's **standing**: where the workflow stood once the whole effect that
recorded the state was applied. A step that records a state and ends is one change whose standing is
completed, not a running state followed by an end. The handler reads it from its context: `updateContext.standing`
in Scala, `updateContext().standing()` in Java and Kotlin, `self.standing` in Python, `this.standing` in TypeScript, `ctx.standing()` in Rust and
`ankka.ChangeFrom(ctx)`'s `Standing` in Go. A change
from an entity or a topic has no standing.

| Standing | When a change carries it |
|---|---|
| `NotStarted` | The workflow recorded a state and has moved to no step yet. |
| `Running` | The workflow moved to a step with the state, or is on one. The step is named. |
| `Paused` | The workflow paused with the state. The step named is the one its timeout runs, or else the one it paused after. |
| `Completed` | The workflow ended with the state. |
| `Failed` | The workflow failed with the state. The reason is given. |
| `Unknown` | The state was recorded by a release that did not stamp standings. |

A standing carries the step the workflow is on or waits after, the retries of each step so far, and, for a
failed workflow, why it failed.

**What records no state is no change.** A transition, a pause, an end or a failure that records no state
delivers nothing, and a workflow that times out records none. A view that must show how a workflow
ended therefore needs the step that ends it to record a state: a failover step that records the failure
in the state and then fails the workflow is one change whose standing is failed. A caller that must
know a workflow ended, whatever its last step recorded, asks the workflow for its lifecycle instead.

This view keeps a row for each checkout: its own status, where the workflow stood, and why it failed.
A declared query lists the rows of one standing.

**Scala**

```scala
/** A checkout's row: its state's status, and where the workflow stood when it recorded it. */
final case class CheckoutRow(
    id: String,
    status: String,
    standing: String,
    step: Option[String],
    failure: Option[String]
)

final class CheckoutRowsView extends View[CheckoutState, CheckoutRow]:
  def onChange(state: CheckoutState): Effect =
    // A change from a workflow carries its standing: where it stood once the effect that
    // recorded `state` was applied.
    val standing = updateContext.standing.getOrElse(WorkflowLifecycle.unknown)
    effects.updateRow(
      CheckoutRow(
        updateContext.subject,
        state.status,
        standing.status,
        standing.pendingStep,
        standing.failure
      )
    )

object CheckoutRows
    extends View.Companion[CheckoutRowsView, CheckoutState, CheckoutRow](
      componentId = ComponentId("checkout-rows"),
      source = ChangeSource.stateOf(Checkout),
      rowSerializer = Codecs.serializer[CheckoutRow]("checkout-row")
    ):
  def create(ctx: ViewComponentContext) = new CheckoutRowsView
  val byStanding                        = query("by-standing")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'standing' = :standing"
  )
```

**Java**

```java
@ComponentId("checkout-rows")
public class CheckoutRows extends View<Checkout.CheckoutState, CheckoutRows.CheckoutRow> {

  @Manifest("checkout-row")
  public record CheckoutRow(
      String id,
      String status,
      String standing,
      Optional<String> step,
      Optional<String> failure) {}

  public static final ViewQuery BY_STANDING =
      ViewQuery.declare(
          "by-standing",
          "SELECT payload FROM "
              + ViewQuery.tableOf(CheckoutRows.class)
              + " WHERE payload::jsonb->>'standing' = :standing");

  private static final WorkflowLifecycle UNKNOWN =
      new WorkflowLifecycle("Unknown", Optional.empty(), Map.of(), Optional.empty());

  @Override
  protected ChangeSource<Checkout.CheckoutState> source() {
    return ChangeSource.ofWorkflow(Checkout.class);
  }

  @Override
  protected ViewEffect<CheckoutRow> onChange(Checkout.CheckoutState state) {
    // A change from a workflow carries its standing: where it stood once the effect that
    // recorded `state` was applied.
    var standing = updateContext().standing().orElse(UNKNOWN);
    return effects()
        .updateRow(
            new CheckoutRow(
                updateContext().subject(),
                state.status(),
                standing.status(),
                standing.pendingStep(),
                standing.failure()));
  }
}
```

**Kotlin**

```kotlin
@ComponentId("checkout-rows")
class CheckoutRows : View<Checkout.CheckoutState, CheckoutRows.CheckoutRow>() {

    @Manifest("checkout-row")
    data class CheckoutRow(val id: String, val status: String, val standing: String, val step: String?, val failure: String?)

    override fun source(): ChangeSource<Checkout.CheckoutState> = ChangeSource.ofWorkflow(Checkout::class.java)

    override fun onChange(state: Checkout.CheckoutState): ViewEffect<CheckoutRow> {
        // A change from a workflow carries its standing: where it stood once the effect that
        // recorded `state` was applied.
        val standing = updateContext().standingOrNull ?: UNKNOWN
        return effects {
            updateRow(
                CheckoutRow(
                    updateContext().subject(),
                    state.status,
                    standing.status,
                    standing.pendingStep.orNull(),
                    standing.failure.orNull(),
                )
            )
        }
    }

    companion object {
        val BY_STANDING: ViewQuery =
            ViewQuery.declare(
                "by-standing",
                "SELECT payload FROM ${ViewQuery.tableOf(CheckoutRows::class.java)} WHERE payload::jsonb->>'standing' = :standing",
            )

        private val UNKNOWN = WorkflowLifecycle("Unknown", Optional.empty(), emptyMap(), Optional.empty())
    }
}
```

**Python**

```python
@dataclass(frozen=True)
class CheckoutRow:
    id: str
    status: str
    standing: str
    step: str | None = None
    failure: str | None = None


class CheckoutRows(View[Checkout, CheckoutRow]):
    """A checkout's row: its state's status, and where the workflow stood when it recorded it."""

    component_id = "checkout-rows"
    source = CheckoutWorkflow
    event_codec = CheckoutWorkflow.state_codec
    row_codec = json_codec(CheckoutRow, "checkout-row")
    by_standing = declare(
        "by-standing",
        f"SELECT payload FROM {table_of('checkout-rows')} WHERE payload::jsonb->>'standing' = :standing",
    )

    def on_change(self, state: Checkout) -> ViewEffect:
        # A change from a workflow carries its standing: where it stood once the effect that
        # recorded `state` was applied.
        standing = self.standing or Standing("Unknown")
        return self.effects.update_row(
            CheckoutRow(self.metadata.subject or "", state.status, standing.status, standing.step, standing.failure)
        )
```

**TypeScript**

```typescript
export const CheckoutRow = s.record("CheckoutRow", {
  id: s.string,
  status: s.string,
  standing: s.string,
  step: s.option(s.string),
  failure: s.option(s.string),
})
export type CheckoutRow = Infer<typeof CheckoutRow>

/** A checkout's row: its state's status, and where the workflow stood when it recorded it. */
export class CheckoutRows extends View<Checkout, CheckoutRow> {
  static readonly componentId = "checkout-rows"
  static readonly source = CheckoutWorkflow
  static readonly events = CheckoutWorkflow.state
  static readonly row = jsonCodec(CheckoutRow, "checkout-row")
  static readonly declared = [
    declaredQuery("by-standing", `SELECT payload FROM ${tableOf("checkout-rows")} WHERE payload::jsonb->>'standing' = :standing`),
  ]

  onChange(state: Checkout) {
    // A change from a workflow carries its standing: where it stood once the effect that recorded
    // `state` was applied.
    const standing = this.standing
    return this.effects.updateRow({
      id: this.subject,
      status: state.status,
      standing: standing?.status ?? "Unknown",
      step: standing?.step ?? null,
      failure: standing?.failure ?? null,
    })
  }
}
```

**Rust**

```rust
/// A checkout's row: its state's status, and where the workflow stood when it recorded it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CheckoutRow {
    pub id: String,
    pub status: String,
    pub standing: String,
    pub step: Option<String>,
    pub failure: Option<String>,
}

pub struct CheckoutRows;

impl View for CheckoutRows {
    type Row = CheckoutRow;
    type Event = Checkout;
    const COMPONENT_ID: &'static str = "checkout-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("checkout-row");

    fn source() -> Source {
        Source::of(CheckoutWorkflow)
    }

    fn on_event(_: Option<CheckoutRow>, state: Checkout, ctx: &Context) -> ViewEffect<CheckoutRow> {
        // A change from a workflow carries its standing: where it stood once the effect that
        // recorded `state` was applied.
        let unknown = Standing::of("Unknown");
        let standing = ctx.standing().unwrap_or(&unknown);
        ViewEffect::UpdateRow(CheckoutRow {
            id: ctx.metadata().subject().unwrap_or_default().to_string(),
            status: state.status,
            standing: standing.status.clone(),
            step: standing.step.clone(),
            failure: standing.failure.clone(),
        })
    }

    fn declared() -> Vec<DeclaredQuery> {
        let table = table_of(Self::COMPONENT_ID);
        vec![query(
            "by-standing",
            format!("SELECT payload FROM {table} WHERE payload::jsonb->>'standing' = :standing"),
        )]
    }
}
```

**Go**

```go
// CheckoutRow is a checkout's row: its state's status, and where the workflow stood when it
// recorded it.
type CheckoutRow struct {
	ID       string  `json:"id"`
	Status   string  `json:"status"`
	Standing string  `json:"standing"`
	Step     *string `json:"step"`
	Failure  *string `json:"failure"`
}

func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// CheckoutRows is a checkout's row, by its id.
var CheckoutRows = ankka.View[CheckoutRow]("checkout-rows",
	ankka.OnChange(ankka.WorkflowSource(CheckoutFlow), func(ctx context.Context, s Checkout, _ *CheckoutRow) (ankka.ViewEffect[CheckoutRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		row := CheckoutRow{ID: c.Subject, Status: s.Status, Standing: "Unknown"}
		// A change from a workflow carries its standing: where it stood once the effect that
		// recorded s was applied.
		if st := c.Standing; st != nil {
			row.Standing, row.Step, row.Failure = st.Status, optional(st.Step), optional(st.Failure)
		}
		return ankka.UpdateRow(row), nil
	}),
	ankka.RowCodec(ankka.CodecFor[CheckoutRow](ankka.Manifest("checkout-row"))),
	ankka.Queries(ankka.Declared("by-standing", "SELECT payload FROM "+ankka.TableOf("checkout-rows")+" WHERE payload::jsonb->>'standing' = :standing")),
)
```

A view over a workflow declares a version and is rebuilt by raising it, as a view over an entity is: the
rebuild reads every state every workflow recorded, from the first. A state recorded before the platform
stamped standings is delivered with the standing `Unknown`. When a workflow is deleted, the view's deletion
handler runs, and by default the row goes with it. A keyed view may read workflows beside entities, one
change at a time; a topic and a workflow may not be sources of one view.

## Registering a view

A view runs only in a service that has the projection runtime. In Scala, register the view's descriptor and
add `ProjectionRuntime()` as an extension; without it, every command still succeeds and every view stays
empty. In Java and Kotlin, register the view's class; the runtime in the same JVM runs the projection. In
Python and TypeScript, register the class, and in Go pass the declaration to `ankka.NewService`; the sidecar
runs the projection.

**Scala**

```scala
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}

val service = Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .register(CartRows.descriptor)
  .withExtension(ProjectionRuntime())
  .start()
```

**Java**

```java
public static ServiceBuilder service() {
  return Ankka.service("shopping-cart")
      .register(ShoppingCart.class)
      .register(CartRows.class)
      .register(CheckoutNotifier.class)
      .register(CheckoutsSeen.class)
      .withEndpoint(CartEndpoint.class)
      .withEndpoint(CheckoutsSeenEndpoint.class)
      .withEndpoint(CartKeysEndpoint.class)
      .withMcpEndpoint(CartMcpEndpoint.class);
}
```

**Kotlin**

```kotlin
fun service(): ServiceBuilder =
    Ankka.service("shopping-cart")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .register(CheckoutNotifier::class.java)
        .register(CheckoutsSeen::class.java)
        .register(CheckoutWorkflow::class.java)
        .withEndpoint(CartEndpoint::class.java)
        .withEndpoint(CheckoutsSeenEndpoint::class.java)
        .withEndpoint(CartKeysEndpoint::class.java)
        .withMcpEndpoint(CartMcpEndpoint::class.java)

fun main() {
    service().start().whenTerminated().toCompletableFuture().join()
}
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

**Rust**

```rust
/// Everything the module hosts, registered by value; `service!` emits the exports once.
pub fn build() -> Service {
    let service = Service::new("ankka-rust")
        .register(entity::ShoppingCart)
        .register(cart_rows::CartRows)
        .register(checkout_workflow::CheckoutWorkflow)
        .register(checkout_notifier::CheckoutNotifier)
        .register(checkout_log::CheckoutLog)
        .register(assistant::CartAssistant)
        .endpoint(endpoint::CartApi);
    // The carts' graph is published to a topic, and a component that publishes needs a broker:
    // it is registered when the service's descriptor names one.
    match ankka::config("ANKKA_KAFKA_BOOTSTRAP_SERVERS") {
        Some(_) => service
            .register(cart_graph::CartGraph)
            .register(cart_contents_graph::CartContentsGraph),
        None => service,
    }
}

#[cfg(not(feature = "conformance"))]
ankka::service!(build);
```

**Go**

```go
// Service is the reference: the cart and every conformance component and endpoint, registered
// explicitly.
func Service() *ankka.Service {
	s := ankka.NewService(
		cart.Entity, cart.Rows, cart.API,
		Entity, PersonalContact, Profile, CheckoutFlow, ReplicatedVehicle, TreeNode, JoinedLeft, JoinedRight,
		CheckoutRows, TopicRows, TreeRows, JoinedRows,
		CheckoutEnds, CheckoutRecorder, CheckoutFanout, TopicRelay, ContractRelay, PartnerRelay, VehicleFeed,
		CartGraph, ProfileGraph,
		Reminder, Assistant, Approver, Answerer,
		Tree, Vehicles, Joined, API, Private, Callers, AutonomousAPI, McpKeys, CartMcp,
	)
	s.ErasureHandler(eraseNothing)
	reference.Store(s)
	return s
}
```

A view over a topic also needs a broker. See [Broker topics](topics.md).

## Querying a view in Scala

A view's rows live in a Postgres table, one row per source id, with the row stored as JSON. Queries are
real SQL over that JSON, not a query language of ankka's own. Get a handle on a view's rows from the
view client:

**Scala**

```scala
private def rows = testKit.service.viewClient.forView(CartRows)
```

**Java**

```java
@Get("/checkouts")
public List<CheckoutRows.Row> checkouts() {
  var rows = viewClient().forView(CheckoutRows.class);
  return rows.all();
}
```

**Kotlin**

```kotlin
val rows = viewClient().forView(CartRows::class.java)
```

**Python**

```python
async def every_cart(client: ComponentClient) -> list[CartRow]:
    rows: list[CartRow] = await client.views.all("cart-rows", CartRow)
    return rows
```

**TypeScript**

```ts
rows: get("/rows", s.list(CartRow), (ep: ShoppingCartEndpoint) => ep.client.views.all(CartRows.componentId, CartRow)),
```

**Go**

```go
// One row by its key, or 404; every row, up to 1000.
ankka.Get("/carts/{cartId}", func(ctx context.Context, req *ankka.Request) (CartSummary, error) {
	row, found, err := ankka.ViewRow[CartSummary](ctx, SummariesID, req.Param("cartId"))
	if err == nil && !found {
		err = &ankka.HttpProblem{Status: 404, Message: "no cart '" + req.Param("cartId") + "'"}
	}
	return row, err
}),
ankka.Get("/carts", func(ctx context.Context, _ *ankka.Request) ([]CartSummary, error) {
	return ankka.QueryView[CartSummary](ctx, SummariesID, ankka.All())
}),
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

**Scala**

```scala
// Query by an attribute rather than by key — the reason views exist.
val found = rows.where(jsonText("cartId") ++ sql" = ${"view-q-1"}")
```

**Kotlin**

```kotlin
val rows = kit.viewClient().forView(CartRows::class.java)
kit.eventually(Duration.ofSeconds(10)) {
    assertTrue(rows.count() >= 1L)
    assertTrue(rows.all().isNotEmpty())
    assertEquals(mapOf("p1" to 2), rows.getOrNull("view-q-1")?.quantities)
}
```

**Go**

```go
// A query is SQL over the row's JSON, declared with the view and checked when the service
// starts; a value the caller gives is bound as a parameter, never spliced into the text.
ankka.Queries(
	// By an attribute rather than by key: the reason views exist.
	ankka.Declared("by-cart-id",
		"SELECT payload FROM "+ankka.TableOf(SummariesID)+" WHERE payload::jsonb->>'cartId' = :cart_id"),
	// The first twenty open carts, by id, descending. Every field reads as text, so a boolean
	// compares against 'true' or 'false'.
	ankka.Declared("open-carts",
		"SELECT payload FROM "+ankka.TableOf(SummariesID)+
			" WHERE payload::jsonb->>'checkedOut' = 'false' ORDER BY payload::jsonb->>'cartId' DESC LIMIT 20"),
),
```

**Scala**

```scala
assert(rows.count() >= 1L)
assert(rows.all().nonEmpty)
assert(rows.count(jsonText("cartId") ++ sql" = ${"nope-does-not-exist"}") == 0L)
```

**Kotlin**

```kotlin
val rows = kit.viewClient().forView(CartRows::class.java)
kit.eventually(Duration.ofSeconds(10)) {
    assertTrue(rows.count() >= 1L)
    assertTrue(rows.all().isNotEmpty())
    assertEquals(mapOf("p1" to 2), rows.getOrNull("view-q-1")?.quantities)
}
```

**Go**

```go
// One row by its key, or 404; every row, up to 1000.
ankka.Get("/carts/{cartId}", func(ctx context.Context, req *ankka.Request) (CartSummary, error) {
	row, found, err := ankka.ViewRow[CartSummary](ctx, SummariesID, req.Param("cartId"))
	if err == nil && !found {
		err = &ankka.HttpProblem{Status: 404, Message: "no cart '" + req.Param("cartId") + "'"}
	}
	return row, err
}),
ankka.Get("/carts", func(ctx context.Context, _ *ankka.Request) ([]CartSummary, error) {
	return ankka.QueryView[CartSummary](ctx, SummariesID, ankka.All())
}),
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

**Scala**

```scala
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

val openCarts = rows.ordered(
  condition = jsonText("checkedOut") ++ sql" = ${"false"}",
  order = jsonText("cartId") ++ SqlFragment.raw(" DESC"),
  limit = 20
)
```

**Go**

```go
// A query is SQL over the row's JSON, declared with the view and checked when the service
// starts; a value the caller gives is bound as a parameter, never spliced into the text.
ankka.Queries(
	// By an attribute rather than by key: the reason views exist.
	ankka.Declared("by-cart-id",
		"SELECT payload FROM "+ankka.TableOf(SummariesID)+" WHERE payload::jsonb->>'cartId' = :cart_id"),
	// The first twenty open carts, by id, descending. Every field reads as text, so a boolean
	// compares against 'true' or 'false'.
	ankka.Declared("open-carts",
		"SELECT payload FROM "+ankka.TableOf(SummariesID)+
			" WHERE payload::jsonb->>'checkedOut' = 'false' ORDER BY payload::jsonb->>'cartId' DESC LIMIT 20"),
),
```

`jsonText` reads every field as text, so a boolean compares against `"true"` or `"false"`. Use
`jsonNumber` to compare or order a numeric field as a number rather than as text.

The table for a view is named `ankka_view_` followed by the component id with every character that is not
a letter or digit replaced by `_`: `cart-rows` is stored in `ankka_view_cart_rows`. A query that must be fast
at scale needs a Postgres expression index on the same term the query uses, such as
`((payload::jsonb->>'cartId'))`.

## Querying a view in Python

A Python service reads a view through the component client, by source id or all at once:

**Scala**

```scala
val row      = rows.get(id) // one row, or None
val everyRow = rows.all()   // every row, up to 1000
```

**Java**

```java
public static Optional<CartRows.Row> row(ViewClient views, String cartId) {
  return views.forView(CartRows.class).get(cartId); // one row, or empty
}

public static List<CartRows.Row> rows(ViewClient views) {
  return views.forView(CartRows.class).all(); // every row, up to 1000
}
```

**Kotlin**

```kotlin
val rows = kit.viewClient().forView(CartRows::class.java)
kit.eventually(Duration.ofSeconds(10)) {
    assertTrue(rows.count() >= 1L)
    assertTrue(rows.all().isNotEmpty())
    assertEquals(mapOf("p1" to 2), rows.getOrNull("view-q-1")?.quantities)
}
```

**Python**

```python
row = await self.client.views.get("cart-rows", cart_id, CartRow)     # one row, or None
rows = await self.client.views.all("cart-rows", CartRow)             # every row, up to 1000
```

**TypeScript**

```ts
rows: get("/rows", s.list(CartRow), (ep: ShoppingCartEndpoint) => ep.client.views.all(CartRows.componentId, CartRow)),
```

**Rust**

```rust
fn row(request: &Request) -> Result<CartRow, HttpProblem> {
    let cart_id = request.path("cartId");
    let rows: Vec<CartRow> = request
        .client()
        .query(CartRows, "by-id", cart_id.to_string())?;
    rows.into_iter()
        .next()
        .ok_or_else(|| HttpProblem::new(404, format!("no row for cart '{cart_id}'")))
}
```

**Go**

```go
// One row by its key, or 404; every row, up to 1000.
ankka.Get("/carts/{cartId}", func(ctx context.Context, req *ankka.Request) (CartSummary, error) {
	row, found, err := ankka.ViewRow[CartSummary](ctx, SummariesID, req.Param("cartId"))
	if err == nil && !found {
		err = &ankka.HttpProblem{Status: 404, Message: "no cart '" + req.Param("cartId") + "'"}
	}
	return row, err
}),
ankka.Get("/carts", func(ctx context.Context, _ *ankka.Request) ([]CartSummary, error) {
	return ankka.QueryView[CartSummary](ctx, SummariesID, ankka.All())
}),
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

**Java**

Declare a query as a `public static final ViewQuery` field of the view, naming the view's table with `ViewQuery.tableOf`:

```java
/** Every row under the row {@code row}, to any depth, by key: the same in every language. */
public static final ViewQuery UNDER =
    ViewQuery.declare(
        "under",
        "WITH RECURSIVE below AS (\n"
            + "  SELECT row_key, payload FROM "
            + TABLE
            + " WHERE payload::jsonb->>'under' = :row\n"
            + "  UNION\n"
            + "  SELECT n.row_key, n.payload FROM "
            + TABLE
            + " n JOIN below b ON n.payload::jsonb->>'under' = b.row_key\n"
            + ")\n"
            + "SELECT payload FROM below ORDER BY row_key");
```

Ask it through the view client, with each value it takes by name:

```java
@Get("/tree/{nodeId}/below")
public List<String> below(String nodeId) {
  return viewClient().forView(TreeRows.class).ask(TreeRows.UNDER, Map.of("row", nodeId)).stream()
      .map(TreeRows.TreeRow::key)
      .toList();
}
```

**Kotlin**

Declare a query as a `ViewQuery` in the view's companion object, naming the view's table with `ViewQuery.tableOf`:

```kotlin
/** Every row under the row `row`, to any depth, by key: the same in every language. */
val UNDER: ViewQuery =
    ViewQuery.declare(
        "under",
        """
        WITH RECURSIVE below AS (
          SELECT row_key, payload FROM $TABLE WHERE payload::jsonb->>'under' = :row
          UNION
          SELECT n.row_key, n.payload FROM $TABLE n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
        )
        SELECT payload FROM below ORDER BY row_key
        """.trimIndent(),
    )
```

Ask it through the view client, with each value it takes by name:

```kotlin
@Get("/tree/{nodeId}/below")
fun below(nodeId: String): List<String> =
    viewClient().forView(TreeRows::class.java).ask(TreeRows.UNDER, mapOf("row" to nodeId)).map { it.key }
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

**Rust**

```rust
/// Every row under the row `row`, to any depth, in key order.
fn declared() -> Vec<DeclaredQuery> {
    let table = table_of(Self::COMPONENT_ID);
    vec![query(
        "under",
        format!(
            "WITH RECURSIVE below AS (\n  \
             SELECT row_key, payload FROM {table} WHERE payload::jsonb->>'under' = :row\n  \
             UNION\n  \
             SELECT n.row_key, n.payload FROM {table} n JOIN below b ON n.payload::jsonb->>'under' = b.row_key\n\
             )\n\
             SELECT payload FROM below ORDER BY row_key"
        ),
    )]
}
```

**Go**

```go
// TreeRows is one row per node, and every row under a row, to any depth, by key: the same
// statement in every language.
var TreeRows = ankka.View[TreeRow]("tree-rows",
	ankka.OnChange(ankka.Changes(TreeNode), func(ctx context.Context, e TreePlaced, _ *TreeRow) (ankka.ViewEffect[TreeRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		return ankka.UpdateRow(TreeRow{Key: c.Subject, Under: e.Under}), nil
	}),
	ankka.RowCodec(ankka.CodecFor[TreeRow](ankka.Manifest("tree-row"))),
	ankka.Queries(
		ankka.Declared("under", `WITH RECURSIVE below AS (
  SELECT row_key, payload FROM `+ankka.TableOf("tree-rows")+` WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM `+ankka.TableOf("tree-rows")+` n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key`),
		ankka.WatchedQuery("all-rows", "SELECT row_key, payload FROM "+ankka.TableOf("tree-rows")+" ORDER BY row_key"),
	),
)
```

The statement must select the rows' `payload` column: the answer is rows of the view's own row type. A
value is text; a statement that needs a number casts it, as `(:depth)::int`. A query answers with at most
1000 rows unless the caller gives another limit, in the order the statement gives them; asked as a
stream, it has no limit (see [Streaming a query](#streaming-a-query)).

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

## Streaming a query

A whole answer is collected before the caller sees its first row, and stops at its limit. Asked as a
stream, the same query gives each row as the database yields it, in the statement's order, with no limit
unless the caller gives one — the way to read every row of a large view without holding them all:

**Scala**

```scala
// Every row, as the database yields it: nothing is collected, and there is no limit.
val everyCart = rows.allStream().runWith(Sink.seq)
```

**Java**

`askStream(query, values)` answers a `Flow.Publisher` of the rows, which an `@Sse` route serves as it is:

```java
// Every row's key, as the database yields the rows: one event each, then the end.
@Sse("/tree/streamed")
public Flow.Publisher<SseEvent> streamed() {
  var rows = viewClient().forView(TreeRows.class).askStream(TreeRows.ALL_ROWS, Map.of());
  return Wire.map(rows, row -> SseEvent.text(row.key()));
}
```

**Kotlin**

`askFlow(query, values)` answers a cold `Flow` of the rows; `asPublisher()` hands it to an `@Sse` route:

```kotlin
// Every row's key, as the database yields the rows: one event each, then the end.
@Sse("/tree/streamed")
fun streamed(): Flow.Publisher<SseEvent> =
    viewClient().forView(TreeRows::class.java).askFlow(TreeRows.ALL_ROWS).map { SseEvent.text(it.key) }.asPublisher()
```

**Python**

`views.stream(view_id, name, row, values, limit=None)` asks `all` or a declared query and yields the rows:

```python
@sse("/streamed")
async def streamed(self) -> AsyncIterator[str]:
    views = self.client.with_metadata(self.request.metadata).views
    async for row in views.stream("tree-rows", "all-rows", TreeRow):
        yield row.key
```

**TypeScript**

`views.stream(viewId, name, values, row, { limit })` asks `all` or a declared query and yields the rows:

```ts
streamed: sse("/streamed", async function* (ep: TreeEndpoint) {
  for await (const row of ep.client.views.stream(TreeRows.componentId, "all-rows", {}, TreeRow)) yield row.key
}),
```

**Go**

```go
// Every row's key, as the database yields the rows: one event each, then the end.
ankka.SSE("/streamed", func(ctx context.Context, _ *ankka.Request) iter.Seq2[string, error] {
	return func(yield func(string, error) bool) {
		for row, err := range ankka.QueryStream[TreeRow](ctx, "tree-rows", ankka.Named("all-rows", nil)) {
			if !yield(row.Key, err) || err != nil {
				return
			}
		}
	}
}),
```

| Method | Streams |
|---|---|
| `allStream(limit = None)` | Every row. |
| `whereStream(condition, limit = None)` | Every row matching a condition. |
| `orderedStream(condition, order, limit = None)` | Matching rows in an order. |
| `askStream(query, values*)` | A declared query's rows; `askStream(query, limit, values*)` reads at most `limit`. |

Each answers a Pekko Streams `Source`, which an `sse` route, a gRPC server stream or a socket route serves
as it is. A stream is produced no faster than its reader reads, and stops being produced when the reader
goes away. It runs in one read-only transaction on a connection of its own for as long as it lasts, and
gives the connection back however it ends.

A stream may run longer than a whole answer is allowed to: the database's statement timeout bounds each
fetch of rows, not the stream. A statement the database ends fails the stream with a timeout, and so does
a reader that takes nothing for that long, which would otherwise hold the database's cursor open. A stream
that fails is never a shorter answer: the reader is told.

## Watching a query

A watch is a query kept open. It gives every row the query matches now, then a marker saying it is
caught up, then, for as long as the watcher reads, each row as the view writes it and matches, and a
removal for each row it gave that is written so it no longer matches, or is deleted. A page listing open
carts watches the open carts, shows the listing once it is caught up, and keeps it current with no
polling.

A query is watched only when its view declares it watchable. Its statement selects `row_key` beside
`payload`, has no `LIMIT`, `OFFSET` or `FETCH`, and does not aggregate (no `GROUP BY`, `DISTINCT` or
aggregate function) at its outermost select; an `ORDER BY` orders the rows given now, and a `WITH`, a join
or a subquery may do what it likes inside. A service declaring a watchable query that breaks one of these
does not start, naming the rule. The same query may still be asked whole or as a stream.

**Scala**

```scala
/** The carts not checked out. Watchable: it selects each row's key beside the row. */
val openCarts = query("open-carts")(
  s"SELECT row_key, payload FROM $table WHERE (payload::jsonb->>'checkedOut')::boolean = false"
).watched
```

**Java**

```java
/** Every row, by key, watchable: the same statement in every language. */
public static final ViewQuery ALL_ROWS =
    ViewQuery.declare("all-rows", "SELECT row_key, payload FROM " + TABLE + " ORDER BY row_key")
        .watched();
```

**Kotlin**

```kotlin
/** Every row, by key, watchable: the same statement in every language. */
val ALL_ROWS: ViewQuery = ViewQuery.declare("all-rows", "SELECT row_key, payload FROM $TABLE ORDER BY row_key").watched()
```

**Python**

```python
all_rows = declare("all-rows", f"SELECT row_key, payload FROM {table_of('tree-rows')} ORDER BY row_key", watched=True)
```

**TypeScript**

```ts
declaredQuery("all-rows", `SELECT row_key, payload FROM ${tableOf("tree-rows")} ORDER BY row_key`, { watched: true }),
```

**Go**

```go
ankka.WatchedQuery("all-rows", "SELECT row_key, payload FROM "+ankka.TableOf("tree-rows")+" ORDER BY row_key"),
```

`watch(query, values*)` watches a declared query; `watchRow(key)` watches one row by its key and needs no
declaration. Each answers a `Source[WatchEvent[Row], NotUsed]`:

| Element | Means |
|---|---|
| `WatchEvent.Row(key, row)` | The row under `key`, as the query sees it now. |
| `WatchEvent.CaughtUp` | Given once, after the rows matched when the watch began and before any change; at once when nothing matched. |
| `WatchEvent.Removed(key)` | The row under `key`, given earlier, no longer matches or was deleted. |

An HTTP endpoint serves a watch as server-sent events with `asSse` (from
`com.thinkmorestupidless.ankka.http`), which names each event `row`, `caught-up` or `removed`, its data the
key and the row as JSON:

**Scala**

```scala
sseEvents("/")(() => views.forView(CartRows).watch(CartRows.openCarts).asSse)
```

**Java**

`watch(query, values)` and `watchRow(key)` answer a `ViewWatch`, whose `asSse()` an `@Sse` route returns:

```java
// The rows now, `caught-up`, then each row as it is written, as named events.
@Sse("/tree/watched")
public Flow.Publisher<SseEvent> watched() {
  return viewClient().forView(TreeRows.class).watch(TreeRows.ALL_ROWS, Map.of()).asSse();
}
```

**Kotlin**

The Java SDK's `ViewWatch`, served by an `@Sse` route through `asSse()`:

```kotlin
// The rows now, `caught-up`, then each row as it is written, as named events.
@Sse("/tree/watched")
fun watched(): Flow.Publisher<SseEvent> = viewClient().forView(TreeRows::class.java).watch(TreeRows.ALL_ROWS, emptyMap()).asSse()
```

**Python**

`views.watch(view_id, name, row, values, unread=…, overflow=…)` and `views.watch_row(view_id, key, row)`
yield `Row`, `CaughtUp` and `Removed`, and raise `WatchEnded`; `sse_events` serves a watch from an `@sse`
route:

```python
@sse("/watched")
async def watched(self) -> AsyncIterator[str | SseEvent]:
    views = self.client.with_metadata(self.request.metadata).views
    async for event in sse_events(views.watch("tree-rows", "all-rows", TreeRow)):
        yield event
```

**TypeScript**

`views.watch(viewId, name, values, row, { unread, overflow })` and `views.watchRow(viewId, key, row)`
yield `{ kind: "row" | "removed" | "caughtUp" }` and throw `WatchEnded`; `sseEvents` serves a watch from
an `sse` route:

```ts
watched: sse("/watched", (ep: TreeEndpoint) => sseEvents(ep.client.views.watch(TreeRows.componentId, "all-rows", {}, TreeRow))),
```

**Go**

```go
// The rows now, caught-up, then each row as it is written, as named events.
ankka.SSEEvents("/watched", func(ctx context.Context, _ *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
	return ankka.WatchEvents(ankka.Watch[TreeRow](ctx, "tree-rows", ankka.WatchQuery("all-rows", nil)))
}),
```

A route handler reads anything it needs from the request — the caller, a path value — before it returns
the stream: the stream is read later, on another thread, where the request is no longer at hand. The
route's ACL admits the watch when the request opens it, and nothing is checked again while it is open.

### A watch is live, not a record

- **Evaluated on writes.** Whether a row matches is decided when the view writes it, by the watched
  statement for that row alone. A statement whose match depends on the time gives a row that comes to
  match by the clock alone on its next write, not before. A row's match does not change when another row
  is written: a tree page watching everything under a node sees a moved subtree's rows only as each is
  written. A cheap statement makes a cheap watch, since it runs once for every written row.
- **Coalesced.** A watcher is never given an older version of a row after a newer one, and a row written
  faster than the watcher reads reaches it fewer times than it was written. The same version may be given
  twice.
- **Any instance.** A row written on any instance of the service reaches a watcher on any instance, once
  the write has committed.
- **Not replayed.** What was written while nobody watched is not given to a later watcher, beyond the
  rows the query matches when it begins. A reader that must see every change — to count them, to act on
  each — reads the source itself with a [consumer](consumers.md).

### Unread rows

A watch holds the rows its watcher has not read yet, one per row key: 256 unless the watcher gives
another bound, as `watch(query, Watching(unread = Some(1000)), values*)`. When it holds that many and a row
for another key arrives, the watcher's overflow strategy decides:

| `Overflow` | Does |
|---|---|
| `DropHead` | Drops the row changed longest ago. The default. |
| `DropTail` | Drops the row changed most recently. |
| `DropNew` | Drops the row that arrived. |
| `DropAll` | Drops every unread row. |
| `Fail` | Ends the watch, telling the watcher it ended unread. |

No strategy slows the view: a watcher never holds back the view's writes. Under a dropping strategy a
watcher that falls behind may be left holding a row that is gone, when the removal is what was dropped,
until that row is written again or it watches again; a row it never had appears on its next write. A
watcher that would rather know chooses `Fail`, and watches again.

### How a watch ends

A watch ends when its watcher stops reading. It also ends, with a reason, when:

| Reason | When |
|---|---|
| rebuilt | the view is emptied to be rebuilt at a higher version |
| instance stopping | the instance serving the watch stops |
| listener lost | the instance loses its connection for hearing of the view's writes |
| unread | the watcher's unread bound was reached under `Fail` |

The stream fails with `WatchEnded(reason)`; served with `asSse`, the last event is `ended`, its data the
reason. A watcher that still wants the rows watches again, and is given the rows the query matches now —
so a page that reconnects shows the current listing again.

An instance holds at most 1000 open watches, every view together; one more is refused, naming that bound.
A watch is counted as one call in the service's topology, marked as a stream, when it ends. A server
learns that a reader of server-sent events has gone only when it next writes to the connection, which a
quiet watch does once a heartbeat, so a watch served that way is held for up to about two heartbeats
after its page closes. Through the platform's gateway, a watch served as server-sent events is bounded by
the gateway's route timeout, as any long response is.

## Keyed views

A keyed view reads one or more entities, each through a handler of its own, and every handler names the
rows it writes and deletes by key. The view below reads two entities. An event of the left entity names a
row and the right entity it holds, and the left's handler writes that row from what it held before. The
right's handler finds every row holding it by asking the view's own declared query, and writes each.

**Scala**

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

**Java**

```java
@ComponentId("joined-rows")
public class JoinedRows extends KeyedView<JoinedRows.JoinedRow> {

  /** A row the left writes under the key it names, holding a right entity's id. */
  @Manifest("joined-row")
  public record JoinedRow(String key, String holding, List<String> notes) {
    JoinedRow noting(String who) {
      var more = new ArrayList<>(notes);
      more.add(who);
      return new JoinedRow(key, holding, List.copyOf(more));
    }
  }

  /** The rows holding one right entity, by key: the same statement in every language. */
  public static final ViewQuery OF_RIGHT =
      ViewQuery.declare(
          "of-right",
          "SELECT payload FROM "
              + ViewQuery.tableOf(JoinedRows.class)
              + " WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key");

  /** The left names a row {@code key|holding}, and writes it from what it held, noting itself. */
  @Source(JoinedLeft.class)
  public KeyedViewEffect<JoinedRow> onLeft(Noted event, KeyedChange<JoinedRow> change) {
    var parts = event.text().split("\\|", 2);
    var key = parts[0];
    var holding = parts[1];
    var held = change.rows().get(key).map(JoinedRow::notes).orElse(List.of());
    return effects().updateRow(key, new JoinedRow(key, holding, held).noting("left"));
  }

  /** The right finds every row holding it by asking the view's own query, and notes itself. */
  @Source(JoinedRight.class)
  public KeyedViewEffect<JoinedRow> onRight(Noted event, KeyedChange<JoinedRow> change) {
    var theirs = change.rows().ask(OF_RIGHT, Map.of("holding", change.subject()));
    var rows = new LinkedHashMap<String, JoinedRow>();
    for (var row : theirs) rows.put(row.key(), row.noting("right"));
    return effects().updateRows(rows);
  }
}
```

**Kotlin**

```kotlin
@ComponentId("joined-rows")
class JoinedRows : KeyedView<JoinedRows.JoinedRow>() {

    /** A row the left writes under the key it names, holding a right entity's id. */
    @Manifest("joined-row")
    data class JoinedRow(val key: String, val holding: String, val notes: List<String>) {
        fun noting(who: String): JoinedRow = copy(notes = notes + who)
    }

    /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
    @Source(JoinedLeft::class)
    fun onLeft(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val (key, holding) = event.text.split("|", limit = 2)
        val held = change.rows().getOrNull(key)?.notes ?: emptyList()
        return effects { updateRow(key, JoinedRow(key, holding, held).noting("left")) }
    }

    /** The right finds every row holding it by asking the view's own query, and notes itself. */
    @Source(JoinedRight::class)
    fun onRight(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val theirs = change.rows().ask(OF_RIGHT, mapOf("holding" to change.subject()))
        return effects { updateRows(theirs.associate { it.key to it.noting("right") }) }
    }

    companion object {
        /** The rows holding one right entity, by key: the same statement in every language. */
        val OF_RIGHT: ViewQuery =
            ViewQuery.declare(
                "of-right",
                "SELECT payload FROM ${ViewQuery.tableOf(JoinedRows::class.java)} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
            )
    }
}
```

**Python**

```python
class JoinedRows(KeyedView[JoinedRow]):
    """A keyed view of two sources: the left names a row ``key|holding`` and notes itself on it; the
    right finds every row holding it by asking the view's own query, and notes itself on each."""

    component_id = "joined-rows"
    row_codec = json_codec(JoinedRow, "joined-row")
    of_right = declare(
        "of-right",
        f"SELECT payload FROM {table_of('joined-rows')} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
    )

    @on(JoinedLeft, JoinedLeft.event_codec)
    async def on_left(self, event: JoinedNote) -> KeyedViewEffect:
        key, holding = event.text.split("|")
        held = await self.rows.get(key)
        notes = held.notes if held is not None else []
        return self.effects.update_row(key, JoinedRow(key, holding, [*notes, "left"]))

    @on(JoinedRight, JoinedRight.event_codec)
    async def on_right(self, event: JoinedNote) -> KeyedViewEffect:
        theirs = await self.rows.ask("of-right", holding=self.subject)
        return self.effects.update_rows({row.key: JoinedRow(row.key, row.holding, [*row.notes, "right"]) for row in theirs})
```

**TypeScript**

```ts
static readonly sources = [on(JoinedLeft, Noted, (v: JoinedRows, e) => v.onLeft(e)), on(JoinedRight, Noted, (v: JoinedRows) => v.onRight())]
static readonly declared = [
  declaredQuery("of-right", `SELECT payload FROM ${tableOf("joined-rows")} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key`),
]

async onLeft(event: Noted) {
  const [key, holding] = event.text.split("|")
  const held = (await this.rows.get(key))?.notes ?? []
  return this.effects.updateRow(key, { key, holding, notes: [...held, "left"] })
}

async onRight() {
  const theirs = await this.rows.ask("of-right", { holding: this.subject })
  return this.effects.updateRows(theirs.map((row) => [row.key, { ...row, notes: [...row.notes, "right"] }] as const))
}
```

**Rust**

```rust
pub struct JoinedRows;

impl JoinedRows {
    /// The left names a row `key|holding`, and writes it from what it held, noting itself.
    fn on_left(event: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let (key, holding) = event
            .text
            .split_once('|')
            .unwrap_or((event.text.as_str(), ""));
        let mut notes = ctx
            .rows()
            .get::<JoinedRow>(key)
            .map(|row| row.notes)
            .unwrap_or_default();
        notes.push("left".to_string());
        KeyedViewEffect::update_row(
            key,
            JoinedRow {
                key: key.to_string(),
                holding: holding.to_string(),
                notes,
            },
        )
    }

    /// The right finds every row holding it by asking the view's own query, and notes itself.
    fn on_right(_: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let subject = ctx.metadata().subject().unwrap_or_default();
        let theirs: Vec<JoinedRow> = ctx.rows().ask("of-right", &[("holding", subject)]);
        KeyedViewEffect::update_rows(theirs.into_iter().map(|mut row| {
            row.notes.push("right".to_string());
            (row.key.clone(), row)
        }))
    }
}

impl KeyedView for JoinedRows {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "joined-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("joined-row");

    fn sources() -> Sources<JoinedRows> {
        Sources::new()
            .on::<Noted>(Source::of(JoinedLeft), JoinedRows::on_left)
            .on::<Noted>(Source::of(JoinedRight), JoinedRows::on_right)
    }

    /// The rows holding one right entity, by key: the same statement in every language.
    fn declared() -> Vec<DeclaredQuery> {
        vec![query(
            "of-right",
            format!(
                "SELECT payload FROM {} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
                table_of(Self::COMPONENT_ID)
            ),
        )]
    }
}
```

**Go**

```go
// JoinedRows: the left names a row key|holding and writes it from what it held, noting itself;
// the right finds every row holding it by asking the view's own query, and notes itself on each.
var JoinedRows = ankka.KeyedView[JoinedRow]("joined-rows",
	ankka.On(ankka.Changes(JoinedLeft), func(_ context.Context, e Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		key, holding, _ := strings.Cut(e.Text, "|")
		held, _, err := rows.Get(key)
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		notes := append(slices.Clone(held.Notes), "left")
		return ankka.UpdateRowAt(key, JoinedRow{Key: key, Holding: holding, Notes: notes}), nil
	}),
	ankka.On(ankka.Changes(JoinedRight), func(ctx context.Context, _ Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		theirs, err := rows.Ask("of-right", map[string]string{"holding": c.Subject})
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		var each []ankka.KeyedViewEffect[JoinedRow]
		for _, r := range theirs {
			r.Notes = append(slices.Clone(r.Notes), "right")
			each = append(each, ankka.UpdateRowAt(r.Key, r))
		}
		return ankka.AllRows(each...), nil
	}),
	ankka.RowCodec(ankka.CodecFor[JoinedRow](ankka.Manifest("joined-row"))),
	ankka.Queries(ankka.Declared("of-right", "SELECT payload FROM "+ankka.TableOf("joined-rows")+" WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key")),
)
```

A handler is handed the change and a handle on the view's own rows: `change.rows.get(key)` and
`change.rows.ask(query, values*)`, with `change.subject`, the id of the entity the change came from. It
reads no other view. It returns row changes: `effects.updateRow(key, row)`, `effects.deleteRow(key)`, their
plural forms, and `++` to say several things at once. In Python a keyed view's handlers are methods marked
`@on(Entity, codec)`, in TypeScript `static sources = [on(Entity, Events, handler)]`, in Rust
`Sources::new().on(...)`, and in Go one `ankka.On(source, handler)` each given to `ankka.KeyedView`; each reads its rows through `rows` and answers the same row changes.

The same view in each language; in Go its handlers are given the rows as an `ankka.ViewRows` and
answer `ankka.UpdateRowAt`, `ankka.DeleteRowAt` and `ankka.AllRows`:

**Scala**

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

**Java**

```java
@ComponentId("joined-rows")
public class JoinedRows extends KeyedView<JoinedRows.JoinedRow> {

  /** A row the left writes under the key it names, holding a right entity's id. */
  @Manifest("joined-row")
  public record JoinedRow(String key, String holding, List<String> notes) {
    JoinedRow noting(String who) {
      var more = new ArrayList<>(notes);
      more.add(who);
      return new JoinedRow(key, holding, List.copyOf(more));
    }
  }

  /** The rows holding one right entity, by key: the same statement in every language. */
  public static final ViewQuery OF_RIGHT =
      ViewQuery.declare(
          "of-right",
          "SELECT payload FROM "
              + ViewQuery.tableOf(JoinedRows.class)
              + " WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key");

  /** The left names a row {@code key|holding}, and writes it from what it held, noting itself. */
  @Source(JoinedLeft.class)
  public KeyedViewEffect<JoinedRow> onLeft(Noted event, KeyedChange<JoinedRow> change) {
    var parts = event.text().split("\\|", 2);
    var key = parts[0];
    var holding = parts[1];
    var held = change.rows().get(key).map(JoinedRow::notes).orElse(List.of());
    return effects().updateRow(key, new JoinedRow(key, holding, held).noting("left"));
  }

  /** The right finds every row holding it by asking the view's own query, and notes itself. */
  @Source(JoinedRight.class)
  public KeyedViewEffect<JoinedRow> onRight(Noted event, KeyedChange<JoinedRow> change) {
    var theirs = change.rows().ask(OF_RIGHT, Map.of("holding", change.subject()));
    var rows = new LinkedHashMap<String, JoinedRow>();
    for (var row : theirs) rows.put(row.key(), row.noting("right"));
    return effects().updateRows(rows);
  }
}
```

**Kotlin**

```kotlin
@ComponentId("joined-rows")
class JoinedRows : KeyedView<JoinedRows.JoinedRow>() {

    /** A row the left writes under the key it names, holding a right entity's id. */
    @Manifest("joined-row")
    data class JoinedRow(val key: String, val holding: String, val notes: List<String>) {
        fun noting(who: String): JoinedRow = copy(notes = notes + who)
    }

    /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
    @Source(JoinedLeft::class)
    fun onLeft(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val (key, holding) = event.text.split("|", limit = 2)
        val held = change.rows().getOrNull(key)?.notes ?: emptyList()
        return effects { updateRow(key, JoinedRow(key, holding, held).noting("left")) }
    }

    /** The right finds every row holding it by asking the view's own query, and notes itself. */
    @Source(JoinedRight::class)
    fun onRight(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val theirs = change.rows().ask(OF_RIGHT, mapOf("holding" to change.subject()))
        return effects { updateRows(theirs.associate { it.key to it.noting("right") }) }
    }

    companion object {
        /** The rows holding one right entity, by key: the same statement in every language. */
        val OF_RIGHT: ViewQuery =
            ViewQuery.declare(
                "of-right",
                "SELECT payload FROM ${ViewQuery.tableOf(JoinedRows::class.java)} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
            )
    }
}
```

**Python**

```python
class JoinedRows(KeyedView[JoinedRow]):
    """A keyed view of two sources: the left names a row ``key|holding`` and notes itself on it; the
    right finds every row holding it by asking the view's own query, and notes itself on each."""

    component_id = "joined-rows"
    row_codec = json_codec(JoinedRow, "joined-row")
    of_right = declare(
        "of-right",
        f"SELECT payload FROM {table_of('joined-rows')} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
    )

    @on(JoinedLeft, JoinedLeft.event_codec)
    async def on_left(self, event: JoinedNote) -> KeyedViewEffect:
        key, holding = event.text.split("|")
        held = await self.rows.get(key)
        notes = held.notes if held is not None else []
        return self.effects.update_row(key, JoinedRow(key, holding, [*notes, "left"]))

    @on(JoinedRight, JoinedRight.event_codec)
    async def on_right(self, event: JoinedNote) -> KeyedViewEffect:
        theirs = await self.rows.ask("of-right", holding=self.subject)
        return self.effects.update_rows({row.key: JoinedRow(row.key, row.holding, [*row.notes, "right"]) for row in theirs})
```

**TypeScript**

```ts
static readonly sources = [on(JoinedLeft, Noted, (v: JoinedRows, e) => v.onLeft(e)), on(JoinedRight, Noted, (v: JoinedRows) => v.onRight())]
static readonly declared = [
  declaredQuery("of-right", `SELECT payload FROM ${tableOf("joined-rows")} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key`),
]

async onLeft(event: Noted) {
  const [key, holding] = event.text.split("|")
  const held = (await this.rows.get(key))?.notes ?? []
  return this.effects.updateRow(key, { key, holding, notes: [...held, "left"] })
}

async onRight() {
  const theirs = await this.rows.ask("of-right", { holding: this.subject })
  return this.effects.updateRows(theirs.map((row) => [row.key, { ...row, notes: [...row.notes, "right"] }] as const))
}
```

**Rust**

```rust
pub struct JoinedRows;

impl JoinedRows {
    /// The left names a row `key|holding`, and writes it from what it held, noting itself.
    fn on_left(event: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let (key, holding) = event
            .text
            .split_once('|')
            .unwrap_or((event.text.as_str(), ""));
        let mut notes = ctx
            .rows()
            .get::<JoinedRow>(key)
            .map(|row| row.notes)
            .unwrap_or_default();
        notes.push("left".to_string());
        KeyedViewEffect::update_row(
            key,
            JoinedRow {
                key: key.to_string(),
                holding: holding.to_string(),
                notes,
            },
        )
    }

    /// The right finds every row holding it by asking the view's own query, and notes itself.
    fn on_right(_: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let subject = ctx.metadata().subject().unwrap_or_default();
        let theirs: Vec<JoinedRow> = ctx.rows().ask("of-right", &[("holding", subject)]);
        KeyedViewEffect::update_rows(theirs.into_iter().map(|mut row| {
            row.notes.push("right".to_string());
            (row.key.clone(), row)
        }))
    }
}

impl KeyedView for JoinedRows {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "joined-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("joined-row");

    fn sources() -> Sources<JoinedRows> {
        Sources::new()
            .on::<Noted>(Source::of(JoinedLeft), JoinedRows::on_left)
            .on::<Noted>(Source::of(JoinedRight), JoinedRows::on_right)
    }

    /// The rows holding one right entity, by key: the same statement in every language.
    fn declared() -> Vec<DeclaredQuery> {
        vec![query(
            "of-right",
            format!(
                "SELECT payload FROM {} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
                table_of(Self::COMPONENT_ID)
            ),
        )]
    }
}
```

**Go**

```go
// JoinedRows: the left names a row key|holding and writes it from what it held, noting itself;
// the right finds every row holding it by asking the view's own query, and notes itself on each.
var JoinedRows = ankka.KeyedView[JoinedRow]("joined-rows",
	ankka.On(ankka.Changes(JoinedLeft), func(_ context.Context, e Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		key, holding, _ := strings.Cut(e.Text, "|")
		held, _, err := rows.Get(key)
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		notes := append(slices.Clone(held.Notes), "left")
		return ankka.UpdateRowAt(key, JoinedRow{Key: key, Holding: holding, Notes: notes}), nil
	}),
	ankka.On(ankka.Changes(JoinedRight), func(ctx context.Context, _ Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		theirs, err := rows.Ask("of-right", map[string]string{"holding": c.Subject})
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		var each []ankka.KeyedViewEffect[JoinedRow]
		for _, r := range theirs {
			r.Notes = append(slices.Clone(r.Notes), "right")
			each = append(each, ankka.UpdateRowAt(r.Key, r))
		}
		return ankka.AllRows(each...), nil
	}),
	ankka.RowCodec(ankka.CodecFor[JoinedRow](ankka.Manifest("joined-row"))),
	ankka.Queries(ankka.Declared("of-right", "SELECT payload FROM "+ankka.TableOf("joined-rows")+" WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key")),
)
```

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
- **A keyed view reads entities and workflows, never a topic.** A topic and an entity, or a topic and a
  workflow, may not be sources of one view, and a service that declares one does not start.

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

**Scala**

```scala
object Shipments extends KeyedView.Companion[ShipmentsView, ShipmentRow](...):
  override def version = Some(2)
```

**Java**

```java
package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.cart.CartEvent;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.View;
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect;
import java.util.OptionalInt;

/** One row per cart from the cart's events, at version 2. */
@ComponentId("cart-rows")
public class CartRows extends View<CartEvent, CartRows.Row> {

  public record Row(String cartId, int lines, boolean checkedOut) {}

  /** The carts not checked out. */
  public static final ViewQuery OPEN =
      ViewQuery.declare(
          "open",
          "SELECT payload FROM "
              + ViewQuery.tableOf(CartRows.class)
              + " WHERE (payload::jsonb->>'checkedOut')::boolean = false");

  @Override
  protected ChangeSource<CartEvent> source() {
    return ChangeSource.of(ShoppingCart.class);
  }

  @Override
  protected OptionalInt version() {
    return OptionalInt.of(2);
  }

  @Override
  protected ViewEffect<Row> onChange(CartEvent event) {
    Row row = rowState().orElse(new Row(updateContext().subject(), 0, false));
    return switch (event) {
      case CartEvent.ItemAdded added ->
          effects().updateRow(new Row(row.cartId(), row.lines() + 1, row.checkedOut()));
      case CartEvent.ItemRemoved removed ->
          effects().updateRow(new Row(row.cartId(), row.lines() - 1, row.checkedOut()));
      case CartEvent.CheckedOut ignored ->
          effects().updateRow(new Row(row.cartId(), row.lines(), true));
      case CartEvent.Discarded ignored -> effects().ignore();
    };
  }
}
```

**Kotlin**

```kotlin
// Raised from 1 once: every reference declares 2, which names the group it reads under.
override fun version(): OptionalInt = OptionalInt.of(2)
```

**Python**

```python
component_id = "shipments"
row_codec = json_codec(ShipmentRow, "shipment-row")
version = 2
```

**TypeScript**

```ts
export class Shipments extends KeyedView<ShipmentRow> {
  static readonly componentId = "shipments"
  static readonly row = jsonCodec(ShipmentRow, "shipment-row")
  // Raised from 1: the table is emptied once and every vehicle is read again from its beginning.
  static readonly version = 2
```

**Rust**

```rust
/// Raising the version rebuilds the view, as far back as the broker retains.
fn version() -> Option<u32> {
    Some(2)
}
```

**Go**

```go
// Raising the version rebuilds the view from every cart's first event.
ankka.SourceVersion(2),
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

A view of a [replicated entity](../concepts/replicated-entities.md) runs at every installation that holds
the entity, over the local journal, which is the same at each of them record for record. A follower's view
therefore holds the rows the home's view holds, built from the changes in the home's order, and is behind
it by what the follower's copy is behind. A view handler reads where a change was written as a consumer
does, and is given local and mirrored changes alike.

## Personal fields in a row

A row may hold [personal fields](personal-data.md), and the view's table holds each as an envelope,
encrypted under its data subject's key. A read of a row, and a declared query that returns rows, give
back the value. In Scala the view finds where its row's personal fields are from the row's type, with
nothing declared.

A declared query that compares a personal field would compare ciphertext, so it is refused when the
service starts, naming the view, the query and the field. A field the companion marks for lookup may be
compared by equality with a value the caller gives, which the runtime answers from a token of the value:

**Scala**

```scala
lookup(_.email)
val byEmail = query("by-email")(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email")
```

The token leaks which rows share a value; mark only the fields a query must find by. When a data subject
is erased, every row holding its fields has them reduced to the data subject alone and loses its lookup
tokens, and its other columns are kept. See [Views](personal-data.md#views) on the personal data page.

## Limits

- A view writes one table, its own, and a query reads that table alone: there is no query across two
  views' tables.
- A topic and an entity, or a topic and a workflow, may not be sources of one view, and a keyed view
  reads no topic.
- A view over a workflow is handed only the states the workflow records; a transition, a pause, an end or a
  failure that records no state is no change.
- A keyed view handles one change at a time; its throughput does not grow with instances.
- A view over a topic starts at the earliest message the broker holds unless it says otherwise, and is
  rebuilt by raising its version, as far back as the broker retains. See
  [Broker topics](topics.md#rebuilding-by-version).
- A watch is live, not a record: it may give a watcher fewer versions of a row than were written, and
  gives nothing written while it was not open. A watched query is decided for each written row alone.
- A WebAssembly module reads a view whole: it cannot stream a query or watch one.
- A declared query compares a personal field only by equality with a given value, on a field marked for
  lookup, and lookup fields are declared in Scala only.

See [Limitations](../reference/limitations.md) for the full list.

## Testing

In Python, `ViewTestKit` feeds changes to a view and keeps one row per key, as the sidecar would, with no
sidecar:

**Scala**

```scala
test("an added item writes the cart's row, and a deletion removes it") {
  val kit = ViewTestKit(CartRows)
  kit.change("c1", ItemAdded(LineItem("p1", "Pen", 2))): Unit
  assertEquals(
    kit.row("c1"),
    Some(CartRow("c1", Map("p1" -> 2), checkedOut = false, names = Map("p1" -> "Pen")))
  )
  assertEquals(kit.deleted("c1"), ViewEffect.DeleteRow)
  assertEquals(kit.row("c1"), None)
}
```

**Java**

```java
/** One change to a view's handler, and the row it wrote, with no runtime. */
public static Optional<CartRows.Row> cartRow() {
  var kit = ViewTestKit.of(CartRows.class);
  kit.change("c1", new CartEvent.ItemAdded(new LineItem("p1", "Pen", 2)));
  return kit.row("c1"); // Row("c1", 1, false)
}
```

**Kotlin**

```kotlin
val kit = ViewTestKit.of(CartRows::class.java)
kit.change("c1", CartEvent.ItemAdded(LineItem("p1", "Pen", 2)))
assertEquals(CartRows.CartRow("c1", mapOf("p1" to 2), false, mapOf("p1" to "Pen")), kit.row("c1").orNull())
kit.deleted("c1")
assertNull(kit.row("c1").orNull())
```

**Python**

```python
kit = ViewTestKit.of(CartRows)
kit.on_change("c1", ItemAdded(LineItem("p1", "Pen", 2)))
assert kit.get("c1") == CartRow("c1", {"p1": 2}, False)
assert isinstance(kit.on_delete("c1"), UpdateRow)
```

**TypeScript**

```ts
test("the rows follow the cart's events, one row per cart", async () => {
  const kit = ViewTestKit.of(CartRows)
  await kit.onChange("c1", { type: "ItemAdded", item: { productId: "p1", name: "Pen", quantity: 2 } })
  assert.deepEqual(kit.get("c1"), { cartId: "c1", quantities: { p1: 2 }, checkedOut: false })
  await kit.onChange("c1", { type: "CheckedOut" })
  assert.equal(kit.get("c1")?.checkedOut, true)
  assert.equal((await kit.onDelete("c1")).kind, "delete-row")
  assert.equal(kit.get("c1"), null)
})
```

**Rust**

```rust
#[test]
fn the_row_counts_each_product_and_leaves_with_a_discarded_cart() {
    let mut kit = ViewTestKit::<CartRows>::new();
    kit.on_event("cart-1", ShoppingCartEvent::ItemAdded { item: pen(2) });
    kit.on_event("cart-1", ShoppingCartEvent::ItemAdded { item: pen(3) });
    assert_eq!(kit.row("cart-1").unwrap().quantities["p1"], 5);
    kit.on_event("cart-1", ShoppingCartEvent::CheckedOut);
    assert!(kit.row("cart-1").unwrap().checked_out);
    // A discarded cart's row goes with it: the event changes nothing, the deletion removes it.
    kit.on_event("cart-2", ShoppingCartEvent::ItemAdded { item: pen(1) });
    kit.on_event("cart-2", ShoppingCartEvent::Discarded);
    assert!(kit.row("cart-2").is_some());
    kit.on_deleted("cart-2");
    assert!(kit.row("cart-2").is_none());
}
```

**Go**

```go
func TestTheRowsFollowTheCart(t *testing.T) {
	ctx := context.Background()
	kit := testkit.View(cart.Rows)
	defer kit.Close()
	change, err := kit.OnChange(ctx, "c1", cart.ItemAdded{Item: cart.LineItem{ProductID: "p1", Name: "Pen", Quantity: 2}})
	if err != nil || change.Updated == nil {
		t.Fatalf("an item added wrote %+v, %v", change, err)
	}
	row, _ := kit.Row("c1")
	if row.CartID != "c1" || row.Quantities["p1"] != 2 || row.CheckedOut {
		t.Fatalf("the row is %+v", row)
	}
	if change, _ := kit.OnDelete(ctx, "c1"); !change.Deleted {
		t.Fatalf("a deletion was %+v", change)
	}
}
```

A keyed view's handlers are tested with `KeyedViewTestKit`, with no actor system and no database. A change
of a source goes to that source's handler, the rows it names are written to a map the test reads, and every
row round-trips through the view's serializer. A declared query is SQL and there is no database to run it,
so the test says what each query answers; a handler that asks a query the test has not answered fails the
test, naming the query. Building the kit checks the view's declared statements as a service's start would.

**Scala**

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

**Java**

```java
/** The right's change notes every row holding it, found by the view's own query. */
public static KeyedViewTestKit<JoinedRows.JoinedRow> rightNotesEveryRow() {
  var kit = KeyedViewTestKit.of(JoinedRows.class);
  kit.change(JoinedLeft.class, "a", new Noted("r1|b"));
  kit.change(JoinedLeft.class, "a", new Noted("r2|b"));
  kit.change(JoinedLeft.class, "a", new Noted("r3|c"));
  // The query is the database's to run; the test says what it answers.
  kit.answering(
      JoinedRows.OF_RIGHT,
      values ->
          kit.rows().values().stream()
              .filter(row -> row.holding().equals(values.get("holding")))
              .toList());
  kit.change(JoinedRight.class, "b", new Noted("anything"));
  // kit.row("r1") and kit.row("r2") carry the notes [left, right]; kit.row("r3") only [left]
  return kit;
}
```

**Kotlin**

```kotlin
val kit = KeyedViewTestKit.of(JoinedRows::class.java)
kit.change(JoinedLeft::class.java, "a", Noted("r1|b"))
kit.change(JoinedLeft::class.java, "a", Noted("r2|b"))
kit.change(JoinedLeft::class.java, "a", Noted("r3|c"))
// The query is the database's to run; the test says what it answers.
kit.answering(JoinedRows.OF_RIGHT) { values -> kit.rows().values.filter { it.holding == values["holding"] } }
kit.change(JoinedRight::class.java, "b", Noted("anything"))
assertEquals(listOf("left", "right"), kit.row("r1").orNull()?.notes)
assertEquals(listOf("left", "right"), kit.row("r2").orNull()?.notes)
assertEquals(listOf("left"), kit.row("r3").orNull()?.notes)
```

**Python**

```python
kit = KeyedViewTestKit.of(Shipments)
kit.change(CounterEntity, "s1", Incremented(1))
assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment",))
with pytest.raises(LookupError, match="of-customer"):
    kit.change(Customer, "c1", Note("renamed"))
kit.answering("of-customer", lambda values: [r for r in kit.rows.values() if r.customer == values["customer"]])
kit.change(Customer, "c1", Note("renamed"))
assert kit.get("s1") == ShipmentRow("s1", "c1", ("shipment", "renamed"))
```

**TypeScript**

```ts
test("the right's change notes every row holding it, found by the view's own query", async () => {
  const kit = KeyedViewTestKit.of(JoinedRows)
  await kit.change(JoinedLeft, "a", { text: "r1|b" })
  await kit.change(JoinedLeft, "a", { text: "r2|b" })
  await kit.change(JoinedLeft, "a", { text: "r3|c" })
  // The query is the database's to run; the test says what it answers.
  kit.answering("of-right", (values) => [...kit.rows.values()].filter((row) => row.holding === values.holding))
  await kit.change(JoinedRight, "b", { text: "anything" })
  assert.deepEqual(kit.get("r1")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("r2")?.notes, ["left", "right"])
  assert.deepEqual(kit.get("r3")?.notes, ["left"])
})
```

**Rust**

```rust
#[test]
fn the_rights_change_notes_every_row_holding_it_found_by_the_views_own_query() {
    // The query is the database's to run; the test says what it answers.
    let mut kit = KeyedViewTestKit::<JoinedRows>::new().answering("of-right", |values, rows| {
        rows.iter()
            .filter(|row| row.holding == values["holding"])
            .cloned()
            .collect()
    });
    kit.change(Source::of(JoinedLeft), "a", noted("r1|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r2|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r3|c"));
    kit.change(Source::of(JoinedRight), "b", noted("anything"));
    assert_eq!(
        kit.row("r1").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r2").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r3").map(|r| r.notes),
        Some(vec!["left".to_string()])
    );
}
```

**Go**

```go
func TestTheRightsChangeNotesEveryRowHoldingIt(t *testing.T) {
	ctx := context.Background()
	kit := testkit.KeyedView(conformance.JoinedRows)
	defer kit.Close()
	for _, text := range []string{"r1|b", "r2|b", "r3|c"} {
		if _, _, err := kit.Change(ctx, conformance.JoinedLeft.ID(), "a", conformance.Noted{Text: text}); err != nil {
			t.Fatal(err)
		}
	}
	// The query is the database's to run; the test says what it answers.
	kit.Answering("of-right", func(values map[string]string) []conformance.JoinedRow {
		var rows []conformance.JoinedRow
		for _, r := range kit.Rows() {
			if r.Holding == values["holding"] {
				rows = append(rows, r)
			}
		}
		return rows
	})
	written, _, err := kit.Change(ctx, conformance.JoinedRight.ID(), "b", conformance.Noted{Text: "anything"})
	if err != nil || len(written) != 2 {
		t.Fatalf("the right wrote %v, %v", written, err)
	}
	for key, want := range map[string][]string{"r1": {"left", "right"}, "r2": {"left", "right"}, "r3": {"left"}} {
		if row, _ := kit.Row(key); !slices.Equal(row.Notes, want) {
			t.Fatalf("%s is noted %v", key, row.Notes)
		}
	}
}
```

Java, Kotlin, Python, TypeScript and Rust have a `KeyedViewTestKit` of the same shape, and Go has `testkit.KeyedView`.

A plain view is tested with the integration test kit as well, which runs the real projection against a
real database:

**Scala**

```scala
testKit = AnkkaTestKit.start(
  Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutNotifier.descriptor),
  Seq(ProjectionRuntime.withPublisher(publisher))
)
```

**Java**

```java
/** The whole service, with its view projected into a real database. */
public static AnkkaTestKit cartService() {
  return AnkkaTestKit.start(
      Ankka.service("carts").register(ShoppingCart.class).register(CartRows.class));
}
```

**Kotlin**

```kotlin
// Every port is a free loopback one, never 9000: `http()` is where the routes answer.
val kit = AnkkaTestKit.start(service())
val baseUrl = kit.http()
```

**TypeScript**

```ts
test("items survive the sidecar restarting", async () => {
  const kit = await AnkkaTestKit.start(service())
  try {
    const added = await kit.http.post("/carts/c1/items", { productId: "p1", name: "Pen", quantity: 2 })
    assert.equal(added.status, 204)
    await kit.restart()                                  // a new sidecar, the same database
    const cart = (await kit.http.get("/carts/c1")).json() as { items: unknown[] }
    assert.equal(cart.items.length, 1)
  } finally {
    await kit.stop()
  }
})
```

**Go**

```go
// A view's rows are written by the sidecar's projection, so reading one is proved through the
// integration kit: the whole service, a real sidecar and a throwaway database.
func TestTheSummariesAreReadThroughTheSidecar(t *testing.T) {
	kit := testkit.StartT(t, SummariesService())
	ctx := t.Context()
	h := kit.HTTP()

	for _, item := range []cart.LineItem{pen, ink} {
		if r, err := h.Post(ctx, "/carts/v1/items", item); err != nil || r.Status != 204 {
			t.Fatalf("adding answered %v, %v", r, err)
		}
	}
	if r, err := h.Post(ctx, "/carts/v2/items", pen); err != nil || r.Status != 204 {
		t.Fatalf("adding answered %v, %v", r, err)
	}
	if r, err := h.Post(ctx, "/carts/v2/checkout", nil); err != nil || r.Status != 200 {
		t.Fatalf("checking out answered %v, %v", r, err)
	}

	// A projection runs behind the write: read until the row holds what was written, never once.
	deadline := time.Now().Add(20 * time.Second)
	for {
		row, err := testkit.GetJSON[CartSummary](ctx, h, "/summaries/carts/v1")
		if err == nil && row.Items == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("v1 reads %+v, %v\n%s", row, err, kit.SidecarLogs(ctx))
		}
		time.Sleep(200 * time.Millisecond)
	}
	open, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/open")
	if err != nil || len(open) != 1 || open[0].CartID != "v1" {
		t.Fatalf("the open carts are %+v, %v", open, err)
	}
	byID, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/by-cart-id/v2")
	if err != nil || len(byID) != 1 || !byID[0].CheckedOut {
		t.Fatalf("v2 by id is %+v, %v", byID, err)
	}
	all, err := testkit.GetJSON[[]CartSummary](ctx, h, "/summaries/carts")
	if err != nil || len(all) != 2 {
		t.Fatalf("every row is %+v, %v", all, err)
	}
}
```

See [Testing](testing.md).
