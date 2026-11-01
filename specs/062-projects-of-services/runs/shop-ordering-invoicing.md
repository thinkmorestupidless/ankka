# Design run: a shop whose ordering and invoicing are owned by different teams

2026-11-01. An agent holding only the rendered `ankka-design` skill (its `SKILL.md` and
`references/`), with no other file and no code, was given this description:

> A shop. Customers place orders for items; an order holds its lines and a total. The ordering team
> owns placing and changing orders and releases weekly. The finance team owns invoicing: when an
> order is placed, finance raises an invoice for it, and finance releases on its own monthly cadence
> under its own audit rules. The order page shows whether the order has been invoiced, and the
> invoice number when it has.

**What the scenarios expect** (`features/projects/designing.feature`): the design starts with a
context map naming each service, what it owns and the criterion for its boundary; every crossing is a
declared topic with its contract or a route with its caller, and nothing else crosses.

**Outcome: met.** Two services, decided first and by **ownership**, with the reason that no rule holds
across orders and invoices at the same moment. The context map came before any component table. Both
crossings are declared topics naming a contract and a schema path; there is no route and no cycle.
The design names what must not cross ("billing never reads the order's events or the orders
database"), restates the contract rules (a contract changes before either side depends on it; an
incompatible change is a new name read beside the old), and leaves two questions for the person
(gapless invoice numbering, changing an invoiced order) rather than deciding them.

The agent chose a topic for the order page's invoice number where a route would also have been a
crossing: it gave the reason that a route would make the order page depend on billing being up.

## The context map it wrote

```json
{
  "project": "shop",
  "services": [
    {"name": "orders", "owns": "orders, their lines and totals, and the invoice status shown on the order page",
     "boundary": {"criterion": "ownership", "why": "the ordering team owns placing and changing orders and releases weekly"}},
    {"name": "billing", "owns": "invoices and their numbers",
     "boundary": {"criterion": "ownership", "why": "finance owns invoicing and releases monthly under its own audit rules"}}
  ],
  "crossings": [
    {"from": "orders", "to": "billing", "topic": "orders-placed",
     "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"from": "billing", "to": "orders", "topic": "invoices-raised",
     "contract": "invoice.v1", "schema": "contracts/invoice.v1.json"}
  ]
}
```

## Its components, in short

- `orders`: `order` event sourced entity; `order-placed-publisher` consumer publishing `order.v1` to
  `orders-placed`; `invoice-recorder` consumer reading `invoices-raised` and calling the order's
  idempotent `record-invoice`; customer and staff endpoints with their own access rules.
- `billing`: `order-invoicer` consumer reading `orders-placed` and raising the invoice whose id is the
  order id, so a redelivery reaches the same invoice; `invoice` event sourced entity for the audit
  trail; `invoice-publisher` consumer publishing `invoice.v1`; an `invoices` view; a finance endpoint.
