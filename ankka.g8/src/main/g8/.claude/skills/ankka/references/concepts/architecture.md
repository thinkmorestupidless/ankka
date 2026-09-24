# How ankka works

> The parts of ankka and how they fit together — components and the runtime inside a service, the control plane and operator that deploy it, the sidecar for other languages, and where every piece of state lives.

Source: https://docs.ankka.cloud/concepts/architecture/
ankka has two halves. Inside a service, **components** written by you are hosted by the **runtime**,
which supplies persistence, distribution and scheduling. Around services, the **platform** — a control
plane, an operator and the infrastructure they manage — deploys and operates them on Kubernetes. This
page describes both halves and where state lives.

## Inside a service: components and the runtime

A service is a set of components registered with the runtime. Each component is one of a fixed set of
kinds — entity, view, consumer, workflow, timed action, agent, HTTP endpoint — and
[Components](components.md) describes each one.

A component's handlers do not perform their own I/O. They return an **effect**, a value describing what
should happen, and the runtime carries it out: writes the events, updates the state, replies, schedules
the next step. [Effects are data](effects.md) explains why this is the organising idea.

The runtime is built on [Apache Pekko](https://pekko.apache.org/). What it does for each kind:

| Component | Hosted by the runtime as |
|---|---|
| Event sourced entity | a persistent actor in cluster sharding, one per entity id |
| Key value entity | a durable-state actor in cluster sharding, one per entity id |
| Workflow | a persistent actor whose events are the workflow's step transitions |
| View | a projection of a source's changes into a Postgres table, or a Kafka consumer group |
| Consumer | a projection of a source's changes, or a Kafka consumer group |
| Timed action | a Postgres table of due calls, swept by one instance in the cluster |
| Agent | an actor in cluster sharding, one per session id, handling one request at a time |
| HTTP endpoint | a route tree served by Pekko HTTP |

Components never call each other directly. They use the **component client**, which routes a call to
wherever the target instance lives in the cluster. Endpoints, workflow steps, consumers, timed actions
and agent loops all run on Java virtual threads, so waiting for a reply is ordinary sequential code and
costs no platform thread.

## A service is one cluster

A service runs as one or more **instances**, and those instances form one Pekko cluster. Entities are
spread across the instances by id, each id living on exactly one instance at a time, and the work that
must happen exactly once per service — sweeping timers, running projections — runs on one instance and
moves if that instance goes. Adding an instance adds capacity; losing one moves its entities to the
others, which rebuild their state from the database. [Clusters and instances](clustering.md) covers how
instances find each other and what happens during a rolling update or a failure.

## Where state lives

Every piece of durable state a service has is in one Postgres database that belongs to that service:

| State | Where |
|---|---|
| An event sourced entity's events and snapshots | the event journal and snapshot tables |
| A key value entity's current value | the durable state table |
| A workflow's step transitions | the event journal |
| A view's rows | one table per view, holding each row as JSON |
| How far each projection has read | projection offset tables |
| Scheduled timers | the timer table |
| An agent's conversation | the event journal, as an entity's events |

An instance holds entities in memory only as a cache. An idle entity is dropped from memory after two
minutes and rebuilt from the journal the next time it is called, so restarting or losing an instance
loses nothing.

**Each service has its own database, and two services must never share one.** Views and timers are
stored in tables named after component ids only, and a service's timer sweeper deletes timers for
components it does not know — so two services on one database delete each other's timers. The platform
provisions a separate database per service for this reason.
[Databases](../platform/databases.md) describes how.

## Around services: the platform

The platform deploys services to Kubernetes and reports how they are doing. It has three parts.

```text
  ankka CLI ──HTTPS──▶ control plane ──writes──▶ AnkkaService resource ◀──watches── operator
  (your machine)       (an ankka app)            (one per service)                    │
                            ▲                                                         │ creates
                            └───────── status folded back from the resource ◀─────────┤
                                                                                      ▼
                                                    namespace, Deployment, Service, database,
                                                    route, ServiceAccount — the running service
```

- **The control plane** holds the platform's desired state: organizations, projects, members, and each
  service's descriptor. It is itself an ankka application — event sourced entities for tenancy, views for
  listings, HTTP endpoints for its API — so every change is an event in its journal, which is also the
  audit trail. It authenticates callers with tokens from the platform's Keycloak and decides what each may
  do.
- **The `AnkkaService` custom resource** is the only thing the control plane and the operator share. The
  control plane writes one per service, describing what should run; the operator writes its status back.
- **The operator** runs inside the cluster, watches those resources, and creates everything a service
  needs: a namespace per project, a Deployment, an in-cluster Service, a database and its credentials, and
  an HTTPS route when the service is exposed. Deleting the resource deletes the workload, because every
  object it created is owned by it.

The split means the control plane holds no credential able to create a workload, and the operator keeps
working when the control plane is down. [Desired and observed state](control-plane.md) explains how the
two agree about what is running.

The **CLI** is a thin client of the control plane's HTTP API. It holds no state beyond its settings and a
saved login, and it validates a descriptor with the same code the control plane runs before sending it.

## Services in other languages

A Scala service is one JVM: your components and the runtime, compiled together. A service in another
language — Python today — runs as your process plus the runtime beside it in the same pod, as a
**sidecar**. The sidecar owns everything stateful and distributed; your process decides what each command
does. They talk a protobuf protocol over gRPC on loopback, and the SDK hides it.

To the platform the two are the same kind of service. The descriptor says `"hosting": "process"`, the
operator adds the sidecar container, and everything else — database, cluster, exposure, logs, the local
console — behaves identically. [Services in other languages](polyglot.md) describes the model.

## What the platform stands on

A platform installation needs a Kubernetes cluster with four controllers besides ankka's own:

| Controller | What ankka uses it for |
|---|---|
| [CloudNativePG](https://cloudnative-pg.io/) | a Postgres cluster per project, and a database and role per service |
| [cert-manager](https://cert-manager.io/) | the wildcard certificate every exposed service is served under |
| [Envoy Gateway](https://gateway.envoyproxy.io/) | the gateway that routes exposed hostnames to services |
| [Keycloak](https://www.keycloak.org/) and its operator | identity for everyone who operates the platform |

[Install a local platform](../platform/install-local.md) installs all of them into a kind cluster with one
script.
