# Contract: the Scala API

What a developer writes. Shapes, not implementations; names are final unless a task finds one
already taken.

## A declared query, on any view

```scala
object Nodes
    extends View.Companion[Nodes, NodeEvent, NodeRow](
      ComponentId("nodes"),
      ChangeSource.eventsOf(NodeEntity),
      Codecs.serializer[NodeRow]("node-row")
    ):
  val under = query("under")(s"""
    WITH RECURSIVE under AS (
      SELECT row_key, payload FROM $table WHERE payload::jsonb->>'parent' = :row
      UNION ALL
      SELECT n.row_key, n.payload FROM $table n JOIN under u ON n.payload::jsonb->>'parent' = u.row_key
    )
    SELECT payload FROM under""")

  def create(ctx: ViewComponentContext) = new Nodes
```

| Member of `View.Companion` and `KeyedView.Companion` | |
|---|---|
| `protected final def query(name: String)(statement: String): DeclaredQuery` | declares and registers; a `val` keeps the handle, as a command's is kept |
| `final def table: String` | the view's table, for a statement to name |
| `def version: Option[Int]` | now also for a view that reads entities |

A query declared after `descriptor` has been taken is a programming error and throws, naming the
query: declarations are `val`s of the companion and exist before it is registered.

## Asking

```scala
val rows = clients.viewClient.forView(Nodes)
rows.ask(Nodes.under, "row" -> nodeId)                 // Vector[NodeRow]
rows.ask(Nodes.under, limit = 5000, "row" -> nodeId)
rows.askAsync(Nodes.under, "row" -> nodeId)            // Future[Vector[NodeRow]]
```

`forView` also takes a `KeyedView.Companion`. A `DeclaredQuery` of another view is refused at the
call with `NotFound`, naming both. Failures are `CommandError`s with the codes C1–C6 give.

## A keyed view

```scala
final class Shipments extends KeyedView[ShipmentRow]:

  def onShipment(event: ShipmentEvent, change: Change): Effect = event match
    case ShipmentCreated(customerId, _) =>
      effects.updateRow(change.subject, ShipmentRow(change.subject, customerId, name = None))
    case _ => effects.ignore

  def onCustomer(event: CustomerEvent, change: Change): Effect = event match
    case CustomerRenamed(name) =>
      val theirs = change.rows.ask(Shipments.ofCustomer, "customer" -> change.subject)
      effects.updateRows(theirs.map(row => row.shipmentId -> row.copy(name = Some(name))))
    case _ => effects.ignore

object Shipments
    extends KeyedView.Companion[Shipments, ShipmentRow](
      ComponentId("shipments"),
      Codecs.serializer[ShipmentRow]("shipment-row")
    ):
  val shipments = source(ChangeSource.eventsOf(ShipmentEntity))(_.onShipment)
  val customers = source(ChangeSource.eventsOf(CustomerEntity))(_.onCustomer)

  val ofCustomer = query("of-customer")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'customerId' = :customer"
  )

  override def version = Some(2)
  def create(ctx: ViewComponentContext) = new Shipments
```

| Member of `KeyedView[Row]` | |
|---|---|
| `final type Effect = KeyedViewEffect[Row]` | |
| `final type Change = KeyedChange[Row]` | `subject`, `sequenceNumber`, `rows.get(key)`, `rows.ask(query, values*)` |
| `protected final val effects` | `updateRow(key, row)`, `deleteRow(key)`, `updateRows(pairs)`, `deleteRows(keys)`, `ignore`; and `a ++ b` to say several things |

| Member of `KeyedView.Companion[V, Row]` | |
|---|---|
| `protected final def source[Src](source: ChangeSource[Src])(onChange: V => (Src, KeyedChange[Row]) => KeyedViewEffect[Row]): KeyedSource[V]` | one per source |
| `…source(source, onDelete = _.onCustomerDeleted)(onChange)` | what to do when the source entity is deleted; default nothing |
| `query`, `table`, `version`, `create`, `descriptor` | as a view's |

There is no `parallelism` to override: a keyed view has one writer.

The handler is handed the change as a value, and nothing is set on the instance between changes:
one instance of the view serves a source for as long as its projection runs, as a plain view's
does, and a handler that blocks on a read must not be able to see another change's state.

A source that is a topic is refused when the service is registered (K2, K3), with the message
the feature's scenario asserts: `ComponentRegistry.validate` runs `KeyedViewRules` for a Scala
keyed view and a discovered one alike, so the problem is collected with every other and the
service does not start. Building the descriptor itself refuses nothing.

## Registering

Unchanged: the companion is handed to the service builder like any component. `ProjectionRuntime`
must be registered, as for any view.

## Testing

```scala
val kit = KeyedViewTestKit(Shipments)
kit.answering(Shipments.ofCustomer)(values => kit.rows.values.filter(_.customerId == values("customer")).toVector)
kit.change(Shipments.shipments, "s1", ShipmentCreated("c1", …))
kit.change(Shipments.customers, "c1", CustomerRenamed("Ada"))
assertEquals(kit.row("s1").flatMap(_.name), Some("Ada"))
```

| `KeyedViewTestKit[V, Row]` | |
|---|---|
| `change(source, subject, value)`, `deleted(source, subject)` | runs the handler; applies its effect through `RowChanges.reduce`; rows round-trip through the view's serializer |
| `row(key)`, `rows` | what the view holds |
| `answering(query)(answer)` | the answer to a declared query; a handler asking one with no answer given **fails the test**, naming the query |

Building the kit runs `QueryCheck` on the view's declared statements and fails on the first
problem, so a statement that would stop the service fails here.

`AnkkaTestKit` runs a keyed view against Postgres with no change to its own API.
