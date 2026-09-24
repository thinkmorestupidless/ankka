# Clusters and instances

> How a service's instances form one cluster, how entities are spread across it, how nodes find each other locally and in Kubernetes, and what that means for rollouts, failures and instance counts.

Source: https://docs.ankka.cloud/concepts/clustering/
Every ankka service runs as a cluster. Its instances are not independent copies behind a load balancer;
they are members of one Apache Pekko cluster that share one set of entities, one journal and one set of
background jobs. A service with one instance is a cluster of one, running the same code path as a
service with five.

## What the cluster shares

**Entities are sharded across instances.** Each entity, workflow and agent session lives on exactly one
instance at a time, chosen by its id. A request that arrives at any instance is routed to the one that
holds the entity, so the caller never needs to know where that is. Because there is only ever one live
copy, there is only ever one writer to an entity's journal, and commands to one entity are processed one
at a time.

**Background work runs once.** Work that must not run twice in parallel — the sweeper that fires due
timers, and the projections that feed views and consumers — runs on exactly one member at a time. When
that member leaves, another takes over.

**Idle entities are passivated.** An entity that has received nothing for two minutes is removed from
memory. The next command rebuilds it from its snapshot and journal. Rehydrating is cheap, and holding
every entity that was ever touched in memory is not.

## Blocking is free

Endpoints, workflow steps, consumers, timed actions and agent loops run on Java virtual threads. When a
handler waits for another component, a database or a model, the virtual thread parks and releases the
operating system thread it was running on. That is why the component client offers a blocking `invoke`
and why ordinary sequential code is the recommended style: waiting costs almost nothing, and fanning out
with `invokeAsync` is there for when calls do not depend on each other.

One consequence: the current HTTP request is available to a handler as a thread-local value. Work handed
to another thread cannot see it, so read what you need from the request before fanning out.

## How nodes find each other

How a node finds its peers depends on where it runs, and the platform chooses. The runtime's base
configuration says nothing about peers; a small overlay per environment does, selected by
`ANKKA_CLUSTER_MODE`.

**Locally**, the default, a node binds loopback on a random remoting port and joins itself. That is why
running a service needs no configuration at all, and why several services and test suites can share a
laptop. To see a real two-node cluster on one machine, fix the first node's port and name it as the
second node's seed:

```bash
ANKKA_CLUSTER_PORT=17355 sbt run
ANKKA_CLUSTER_SEED_NODES=pekko://ankka@127.0.0.1:17355 ANKKA_HTTP_PORT=9001 sbt run
```

A cart added through port 9000 then reads back through port 9001. `ANKKA_CLUSTER_SEED_NODES` takes a
comma-separated list of node addresses.

**In Kubernetes**, the platform sets `ANKKA_CLUSTER_MODE=kubernetes` and nodes discover each other
through the Kubernetes API, using Pekko Cluster Bootstrap. The operator gives every service a service
account that may list pods in its own namespace and nothing else, and labels its pods so that a node can
never mistake another service's pods for its own. A node refuses to start if any of the variables this
mode needs is missing, rather than binding loopback and quietly joining nothing. A descriptor cannot set
these variables; they belong to the platform.

When several instances start from nothing, Bootstrap waits until it can see two of them (or one, for a
single-instance service) before forming a cluster. Two pods starting together therefore find each other
and form one cluster rather than two, and one pod that cannot be scheduled does not hold the others
down.

## Readiness means membership

An instance reports ready only when it has joined its service's cluster and every part of the runtime
that has an opinion agrees; the HTTP server, for example, agrees once it has bound its port. The probe
is `/ready` on the management port. Traffic, including traffic from an exposed hostname, reaches an
instance only once it is ready, so a request never lands on a node that is still joining.

Readiness does not call any of your routes. It says the node is a working member of the cluster, not
that your application is healthy. There is no liveness probe: entities rebuild from their journals, so
restarting a pod for a slow garbage collection costs more than the pause did.

## Rolling updates without downtime

A service rolls out one new instance at a time, never removing an old one first. The new instance joins
the existing cluster, entities are handed over to it, and only then is an old instance stopped. This
holds at one instance too: the new pod joins the old pod's cluster, takes its entities, and the old pod
leaves. A caller reading an entity throughout a rollout sees no failed request.

Before an instance begins shutting down, it waits five seconds while still serving. Kubernetes takes a
moment to stop routing to a pod that is leaving, and the wait covers that moment so that no request is
sent to a port that has already closed.

Changing only the instance count does not roll anything: going from three instances to four adds one
pod and leaves the three running. Only `ankka services restart` replaces running instances without a
change of image or configuration. See [Scale and roll out](../deploy/scaling-and-rollouts.md).

## Failures and partitions

When an instance crashes, the others notice, remove it from the cluster, and take over its entities,
which rebuild from their journals. Kubernetes starts a replacement, which joins as a new member.

When the network splits the cluster into parts that cannot see each other, only one part may carry on,
or two parts would both believe they own the same entities. ankka resolves a split by majority: the side
with more than half of the members stays up and the rest shut down. An instance that is shut down this
way exits, so Kubernetes restarts it and it rejoins cleanly.

**Use an odd number of instances.** With two, a partition leaves one on each side. Neither is a
majority, so both are shut down and the service is unavailable until Kubernetes restarts them. Three
instances survive the loss of one. One instance has no partition to survive. Instance counts are set by
`minInstances` in the [service descriptor](../reference/service-descriptor.md); the default is one.

## What the cluster does not protect

Cluster traffic between instances is neither encrypted nor restricted to the service's own namespace.
Pod labels stop a node from choosing a stranger as a peer, but nothing stops a stranger connecting to
the remoting or management port. See [Limitations](../reference/limitations.md).
