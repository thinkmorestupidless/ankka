# Contract: what a member sees

Held by `features/cross-project/listing.feature` and the status steps of the others.

## `ankka services get <name> -p <project>`

```text
Grants:                 mounted | rollout needed
Cross-project topics:   spinvibe/casino.players  consume  granted
                        spinvibe/payments.deposits  produce  not granted (pending)
```

`ServiceStatus.grants: Option[String]` from the `AnkkaService` status; `crossProjectTopics`
computed from the running instances' topology (`topic:<project>/<name>` edges) joined with the
granting project's grants by this service as grantee — `granted` when an accepted grant with that
right exists, else `not granted (no grant | pending | ended)`. Both are on the service page in the
console and in `get_service` for MCP.

## `ankka projects grants list -p spinvibe`

```text
ID                GRANTEE                       TARGET                                          STATE     EFFECT               BY    AT
3f9c…             service:payments/merchant     route wallet POST /v1/wallets/{player}/{currency}/deposits   accepted  in effect            ada   2026-10-08T…
a1b2…             machine:affiliates/network    topic affiliates.attribution consume            pending   pending              ada   …
c3d4…             service:payments/merchant     route wallet GET /v1/wallets/{player}/history   accepted  route not seen       ada   …
```

`EFFECT` for an accepted grant comes from, in order: `rollout needed` (the service's status has no
`grants`), `route not grantable` (web hosting, or the topology's handler for the route lacks
`grantable`), `route not seen` (no such handler in the merged topology), `broker not exposed` (a
machine's topic grant on an installation without `ANKKA_BROKER_EXTERNAL_BOOTSTRAP`), else `in
effect`. A service with no ready instance is `route not seen` for its route grants.

## `ankka projects grants received -p payments` and `ankka organizations grants list affiliates`

```text
ID      FROM                 TARGET                                      STATE     RETENTION         CHANGES
3f9c…   spinvibe (eitheror)  route wallet POST /v1/wallets/…/deposits   accepted  -                 made by ada 2026-10-08T…
a1b2…   spinvibe (eitheror)  topic affiliates.attribution consume        pending   7 days, compacted  offered by ada …
```

A topic grant shows the topic's `partitions`, `compacted` and, once feature 043 lands, retention
and copies (optional fields on `TopicSettings`). The organization listing covers its machines and
the services of every project it owns.

## The topology

A route handler in `GET /services/{p}/{n}/topology` carries `grantable: true` when the route's
effective ACL names granted callers. The console's shape view marks such a route.

## The console

- Project page: a "Grants" card (held: grantee, target, state, effect, by, at; actions withdraw/
  revoke behind `grant.make`, `grant.end` operations) and a "Received" card.
- Organization page: an "Offered grants" card with accept/decline/relinquish (operations
  `grant.answer`, `grant.relinquish`); a "Machines" page beside "Deploy tokens" (register shows the
  secret once through `tokenFlash`; delete; byte rates), operations `machine.register`,
  `machine.delete`, `machine.byte-rates`.
- The `areas` list gains `machines`; `fake-control-plane.ts` fakes every route above; the Playwright
  suite exercises each, or `parity.ts` fails.
