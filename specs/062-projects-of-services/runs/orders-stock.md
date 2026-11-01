# Design run: orders and stock

2026-11-01. An agent holding only the rendered `ankka-design` skill (its `SKILL.md` and
`references/`), with no other file and no code, was given this description:

> A shop with two teams. The orders team owns placing orders. The warehouse team owns stock levels.
> The business rule is strict: an order may only be placed if every item on it is held in stock at
> that same moment, and placing it must reserve those items so no other order can take them.
> Overselling is never acceptable.

**What the scenario expects** (`features/projects/designing.feature`, "two services that must be
released together are designed as one service"): one service, and the design says it is one because
orders and stock must be consistent together.

**Outcome: met.** The design is one service, `shop`, with no context map. It says the ownership
difference alone would justify a boundary, but the rule ties orders and stock together at the same
moment, so it writes the boundary decision as **consistency** and keeps the teams' split as ownership
of components inside the one service. It quotes the concept page's own example.

## The design, as the agent returned it (abridged to its decisions)

- One service: "Two services by team: stock would reach orders only through a topic or route, both
  eventually consistent, so the order would decide on stale stock and could oversell."
- `stock` event sourced entity per product (reserve, release, commit, restock; a repeated reserve for
  an order id is recognised); `place-order` workflow per order, reserving each line and releasing on a
  refusal; `order` entity recording the outcome; a paused step releasing an abandoned placement;
  `stock-levels` and `orders-by-customer` views, never used to decide; two endpoints with their own
  access rules.
- Ownership inside one service: the warehouse team owns `stock`, `stock-levels` and the warehouse
  endpoint; the orders team owns `order`, `place-order`, `orders-by-customer` and the orders endpoint.
