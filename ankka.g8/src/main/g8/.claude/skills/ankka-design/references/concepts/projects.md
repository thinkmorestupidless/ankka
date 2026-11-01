# A project of several services

> When an application is several services rather than one, how their boundaries are drawn and recorded in a context map, what may cross them, and why each service is still deployed on its own.

Source: https://docs.ankka.cloud/concepts/projects/
A project is a group of services that deliver what is required of them when they run together.
Each service in it is built, tested, released and deployed on its own. Most applications start as
one service, and that is the right place to start: a second service is worth its cost only when
something about the two parts really differs.

## Deployed together means one service

The platform deploys each service on its own and never several as one. This is deliberate. A
rollout that must change two services at the same moment, or not at all, cannot be made safe while
both are running and serving requests, so the platform offers no way to attempt it.

So services that must be deployed together are one service. Two parts that cannot be released
apart, because a change to one always needs a change to the other at the same moment, have no
boundary between them worth the name. Merge them, and keep the boundary for parts that can move
at their own pace.

## Where the boundaries go

Draw a boundary where one of these differs, and only there:

- **Ownership.** A team that releases on its own cadence owns a service of its own.
- **Scaling.** A part with very different load, such as a model-heavy assistant beside a
  transactional core, can be scaled on its own.
- **Consistency.** Anything that must be consistent with something else at the same moment belongs
  in the same service, usually the same entity. Across a boundary everything is eventually
  consistent.

An order that may only be placed while its items are held in stock is one rule over orders and
stock. They are one service, whatever the teams are called, because they must be consistent
together. [Designing a service](designing-services.md#service-boundaries) has the rest of the
reasoning.

## The context map

A design of several services starts with a context map, before any service's components are
designed. It is one file, `context-map.json`, at the root of the project's repository:

```json
{
  "project": "shop",
  "services": [
    {"name": "orders", "owns": "orders and their lines",
     "boundary": {"criterion": "ownership", "why": "the orders team releases on its own cadence"}},
    {"name": "billing", "owns": "invoices and payments",
     "boundary": {"criterion": "ownership", "why": "finance owns invoicing"}}
  ],
  "crossings": [
    {"from": "orders", "to": "billing", "topic": "orders-placed",
     "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"from": "orders", "to": "billing", "route": "POST /invoices"}
  ]
}
```

It names every service, what each owns, and which of ownership, scaling or consistency put each
boundary where it is, with the reason. It records every crossing, the only things that pass a
boundary. A crossing is one of two things:

- **A declared topic with its contract.** One service publishes to a
  [declared topic](../build/topics.md#declaring-a-topic), stating the [contract](../build/topics.md#contracts)
  the topic carries, and the other reads it. The schema of the contract is a file in the project.
- **A route of an endpoint.** One service calls a route of the other's endpoint, through the
  [service client](../build/calling-services.md). The crossing names who calls whom.

Nothing else crosses. A service reading another's database, or the events of another's entities,
or a topic with no contract, is a defect to remove, not a crossing to record: a service's database
and its events are its own, and change as its domain does.

Two services that call each other's routes form a cycle. Neither can be released, or answered,
without the other. One of the two calls becomes a declared topic: the side that only needs to know
something happened reads it, and the cycle is gone.

A design of one service has no boundary and no context map.

The map is checked, not just written. `ankka context-map check` holds it to the project's files:
every service it names has a directory and a descriptor, every directory is named, every schema
exists, and its rendering, `CONTEXT-MAP.md`, is up to date. The project's tests hold it to the code:
every topic a service publishes to or reads, and the contract it states, is a crossing in the map.
[A project of several services](../build/projects.md#the-context-map) shows both.

## One repository per project

Keep a project in one repository: every service, the context map, the contracts, and the specs of
features that change more than one service. A change to a contract and both its sides is then one
change, reviewed once, and the context map is checked against the code it describes.

One repository does not tie the services' deployments together. Each service is released by a
service tag, which names it and a version, such as `orders/v1.4.2`. A service tag builds that
service's image and deploys that service alone; the others are untouched, and each service's
version follows its own tags.

A service moves to a repository of its own when its owner or its toolchain differs from the rest
of the project: another organization owns it, say, or it is built by tools the project's checks do
not run. What stays in the project's repository is the context map, the contracts and the specs
that cross services. The moved service's repository holds its code, and a spec of its own for its
part of each feature, which names the project's spec it implements.

## A change that crosses services

A feature that changes several services is one spec, in the project's repository. Its plan names
each service's part and every contract the feature adds or changes. Its work list changes each
contract before either side depends on it, so neither side is ever built against a contract that
does not exist yet.

A compatible change to a contract, such as a field added that readers may ignore, keeps the
contract's name. An incompatible change is a new contract with a new name, `order.v2` beside
`order.v1`. The reading side reads both until no service publishes the old one, and only then is
the old one retired. Each service is released on its own service tag when its part is done, in
the order the work list gives.
