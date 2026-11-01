# Running and inspecting a project of several services (quickstart §4)

2026-11-01, on a project rendered by `ankka init shop --project --services orders,billing` with a
Python service `stock` added, against this branch's ankka published locally.

## By hand

- `docker compose up -d`, `sbt schema`, then `scripts/run.sh billing` and `scripts/run.sh orders`
  in the background: billing on 9001 and orders on 9000, each in its own database, nothing else
  configured. `list_local_services` and `ankka local console` (`/api/services`) listed both.
- `POST /orders/inspect-widget-1` answered 204. Billing's `item` answered the item within a second,
  and `GET /orders/inspect-widget-1/billing` answered `{"name":"Widget","count":3}` through the route
  crossing; orders' trace held `http GET /{id}/billing` with the call `service:local/billing GET`
  under it, and billing held the same trace id.
- The topic crossing was in a trace of its own: the request's trace ended at `item#add-item`, the
  message was published by `orders-publisher#on-message` in a new trace, and billing's
  `orders-reader#on-message` continued that trace under the same id. The skill said otherwise and was
  corrected.
- **Billing stopped:** `list_local_services` listed orders alone, and the route answered 503
  "cannot reach local/billing: not in ankka.local-services and not announced in ~/.ankka/running":
  not met, naming billing.
- **Billing rebuilt stating `orders.v2`:** the topologies read through the console said
  `orders → topic-publication orders.v1` and `billing ← topic-subscription orders.v2`: not met, naming
  both contracts. Billing still read and recorded the next item, since off the platform no contract is
  compared per message; the skill now says to compare the contracts before running anything,
  whatever the requests then show.

## By an agent holding only the rendered `ankka-inspect` skill

With orders and billing (stating `orders.v1`) running, an agent given only the skill and the scenario
"an item added at orders is recorded by billing, and orders reads it back from billing" reported:

| Scenario | Observed | Evidence | Verdict |
|---|---|---|---|
| an item added at orders is recorded by billing, and orders reads it back. Crossings `orders → billing by orders-events` (`orders.v1`) and `orders → billing by GET /billing/{id}` | billing's `get-item` answered Widget, 3 on the first poll, about 2s after the 204; orders answered the same through the route | both topologies state `orders.v1` with one fingerprint; topic crossing trace `9dfdb22fedb964e` (orders-publisher → orders-reader), route crossing trace `c531d555891bfcdb` (`service:local/billing GET` → billing's `GET /{id}`) | met |

It ran `ankka context-map check` first, started and stopped the console itself, used fresh ids, and
read the request's trace ending at the write. Its six notes on the skill, each now in the skill: the
call span's project is `local` on a laptop; the second service's half of a crossing is `partial` with
`parentUnknown`, which is the crossing and not unattributed work; traces are matched by handler and
time, since spans carry no entity id; a service the scenario does not name need not run; how to start
the console and find its address; the reading side's contract also shows in `describe_local_service`.
