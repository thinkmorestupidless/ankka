# Contract: Python, TypeScript and Rust

Each SDK gains the same six things, spelled its own way: a declared query on a view, `ask` on the
view client, a keyed view, a `rows` handle for a keyed handler, a version on a view that reads
entities, and a keyed view test kit. Each also refuses, at discovery, to run anything here on a
runtime below 1.13 (S1 in [protocol.md](protocol.md)).

No SDK parses a statement: Q1–Q8 are the runtime's, and reach the developer as the service not
starting.

## What changes in every SDK

| | Today | After |
|---|---|---|
| a view's version over an entity | refused at registration (`start_from.py:74`, `service.ts:375`, `start_from.rs:96-98`) | accepted; still refused for a consumer |
| `queries` on a view | bare names, sent and never read | unchanged and still sent; declared queries are a second, separate declaration |
| the view client | `get`, `all` (Rust: `query` by name) | also `ask(view, name, values, limit?)` |
| protocol constant | `1.7` | `1.13`, and a gate for the three new declarations beside the 1.7 one |
| the copy of `protocol/` | at 1.7 | copied again; CI diffs it |

## Python

```python
class Nodes(View[NodeEvent, NodeRow]):
    component_id = "nodes"
    source = NodeEntity
    event_codec = NODE_EVENTS
    row_codec = NODE_ROW
    under = query("under", f"""
        WITH RECURSIVE under AS (
          SELECT row_key, payload FROM {table_of("nodes")} WHERE payload::jsonb->>'parent' = :row
          UNION ALL
          SELECT n.row_key, n.payload FROM {table_of("nodes")} n
            JOIN under u ON n.payload::jsonb->>'parent' = u.row_key)
        SELECT payload FROM under""")

rows = await self.client.views.ask("nodes", "under", NodeRow, {"row": node_id})


class Shipments(KeyedView[ShipmentRow]):
    component_id = "shipments"
    row_codec = SHIPMENT_ROW
    version = 2
    of_customer = query("of-customer",
        f"SELECT payload FROM {table_of('shipments')} WHERE payload::jsonb->>'customerId' = :customer")

    @on(ShipmentEntity, SHIPMENT_EVENTS)
    async def on_shipment(self, event: ShipmentEvent) -> KeyedViewEffect:
        ...
        return self.effects.update_row(self.subject, ShipmentRow(...))

    @on(CustomerEntity, CUSTOMER_EVENTS)
    async def on_customer(self, event: CustomerEvent) -> KeyedViewEffect:
        theirs = await self.rows.ask("of-customer", customer=self.subject)
        return self.effects.update_rows({r.shipment_id: replace(r, name=event.name) for r in theirs})

    @on_deleted(CustomerEntity)
    async def on_customer_deleted(self) -> KeyedViewEffect: ...
```

- `query(name, statement)` and `table_of(component_id)` are functions of `ankka.view`; a class
  attribute holding a query is collected in `__init_subclass__`, as `queries` is read today. The
  declaration is not exported as `ankka.query`, which is the entity read-only handler decorator.
- `Views.ask(view_id, name, row, values=None, *, limit=None)` takes the values as a mapping, not as
  keyword arguments: a value may then have any name, `row` and `limit` included.
- `self.rows` is `get(key)` and `ask(name, **values)`, bound to the view's own id. It is given a
  client by the servicer, which a Python view has never had; a plain view still has none.
- `self.subject`, `self.metadata`, `self.effects` as a view's. There is no `self.row`.
- `KeyedViewTestKit.of(Shipments)`: `change(source, key, event)`, `deleted(source, key)`, `get(key)`,
  `rows`, `answering(name, fn)`; an unanswered `ask` raises, naming the query.

## TypeScript

```ts
export class Shipments extends KeyedView<ShipmentRow> {
  static componentId = "shipments"
  static row = ShipmentRowShape
  static version = 2
  static sources = [
    on(ShipmentEntity, ShipmentEvents, (v: Shipments, e) => v.onShipment(e)),
    on(CustomerEntity, CustomerEvents, (v: Shipments, e) => v.onCustomer(e), {
      deleted: (v) => v.onCustomerDeleted(),
    }),
  ]
  static declared = [
    declaredQuery("of-customer", `SELECT payload FROM ${tableOf("shipments")} WHERE payload::jsonb->>'customerId' = :customer`),
  ]

  async onCustomer(event: CustomerEvent): Promise<KeyedViewEffect<ShipmentRow>> {
    const theirs = await this.rows.ask("of-customer", { customer: this.subject })
    return this.effects.updateRows(theirs.map((r) => [r.shipmentId, { ...r, name: event.name }]))
  }
}

const rows = await client.views.ask("nodes", "under", { row: nodeId }, NodeRowShape)
```

- A plain view gains `static declared`, beside the `static queries` it has. The declaration is
  `declaredQuery(name, statement)`: `query` is already the package's export for an entity's
  read-only handler.
- Effects are erasable values, as today's are: `{kind: "rows", changes: [{key, upsert} | {key, delete: true}]}`.
- `this.rows` is `get(key)` and `ask(name, values)`; `this.subject`, `this.metadata`.
- `KeyedViewTestKit.of(Shipments)` as Python's.

## Rust

```rust
impl KeyedView for Shipments {
    type Row = ShipmentRow;
    const COMPONENT_ID: &'static str = "shipments";

    fn sources() -> Sources<Self> {
        Sources::new()
            .on::<ShipmentEvent>(Source::of::<ShipmentEntity>(), Self::on_shipment)
            .on::<CustomerEvent>(Source::of::<CustomerEntity>(), Self::on_customer)
    }
    fn declared() -> Vec<DeclaredQuery> {
        vec![query("of-customer", format!(
            "SELECT payload FROM {} WHERE payload::jsonb->>'customerId' = :customer",
            table_of(Self::COMPONENT_ID)))]
    }
    fn version() -> Option<u32> { Some(2) }
}

impl Shipments {
    fn on_customer(event: CustomerEvent, ctx: &Context) -> KeyedViewEffect<ShipmentRow> {
        let theirs: Vec<ShipmentRow> = ctx.rows().ask("of-customer", &[("customer", ctx.subject())]);
        KeyedViewEffect::update_rows(theirs.into_iter().map(|r| (r.shipment_id.clone(), r.named(&event.name))))
    }
}
```

- A handler is a function of the event and the context, as a view's is of the row, the event and
  the context: a module's fresh instance remembers nothing, so everything arrives as an argument.
- `ctx.rows()` is `get(key)` and `ask(name, values)` for the view being handled, over the `query`
  import that exists. It panics outside a keyed view's handler.
- `View` gains `fn declared() -> Vec<DeclaredQuery>` defaulting to empty, beside `queries()`.
- `Client::ask(view, name, values)` beside `query`, and `Client::ask_by_name(view_id, name,
  values, limit)` beside `query_by_name`, which is how a limit is given.
- `KeyedViewTestKit::<Shipments>::new()`: `change::<E>(source, key, event)`, `deleted(source, key)`,
  `row(key)`, `answering(name, fn)`; an unanswered `ask` panics, naming the query.

## Conformance

Each SDK's conformance service adds, and the Scala reference adds the same:

| Component | What it is for |
|---|---|
| `tree-node` | an event sourced entity: command `place` takes the parent's id as text (empty for none) and records `{"under": parent}` |
| `tree-rows` | a plain view over `tree-node`: one row `{"key": id, "under": parent}` per node, with the declared recursive query `under` |
| `joined-rows` | a keyed view over two entities, with a declared query its own handler asks |

`tree-rows`' `under` is the same statement in every language:

```sql
WITH RECURSIVE below AS (
  SELECT row_key, payload FROM ankka_view_tree_rows WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM ankka_view_tree_rows n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key
```

The conformance endpoint adds `POST /tree/{nodeId}` (a root), `POST /tree/{nodeId}/under/{parentId}`,
and `GET /tree/{nodeId}/below`, which answers a JSON array of the keys under the node, in the order
the query gives them. The cases are named for `features/views/languages.feature`'s five scenarios,
once per language.

"Does not start" is checked on the target's own discovered declaration: the statement each language
sent for `under` must pass the check, and the same declaration with that statement replaced by one
reading another view's table must be refused, naming the view, the query and the table. A process
target is started by its SDK's script before the suite runs, so the suite cannot start a second one
with a broken declaration; what each language must get right is that its declaration reaches the one
check intact.
