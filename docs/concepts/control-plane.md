---
title: Desired and observed state
description: How the control plane records what you asked for, how the operator makes the cluster match it, and how the status you read is kept honest with generations and confirmation.
kind: concept
related: [reference/lifecycle-states.md, reference/service-descriptor.md, operate/status-and-history.md, concepts/tenancy-and-access.md]
---

# Desired and observed state

Deploying on ankka is declarative. You tell the control plane what a service should be, as a
[descriptor](../reference/service-descriptor.md), and the platform works to make the cluster match. The
control plane keeps two things for every service side by side: the **desired state** you asked for, and
the **observed state** the cluster last reported. Everything `ankka services get` prints is one or the
other, and reconciliation is the work of closing the gap between them.

## The control plane is an ankka application

The control plane is built from the same components as the services it runs. Organizations, projects and
services are event sourced entities; the listings are views; the API is a set of HTTP endpoints. The
journal of a service's entity is its audit trail: every apply, pause, restart, expose and delete is an
event that records who asked and when, which is what `ankka services history` reads back.

## Desired state: the descriptor and its generation

`ankka services apply -f service.json` records a descriptor for a service and increments the service's
**generation**. A restart increments it too, because a restart is a request for new instances even when
the descriptor has not changed. Pause, resume, expose and unexpose change the desired state without a new
generation.

Recording is all an apply does. It validates the descriptor, persists it and returns; it does not wait for
anything to start. That is deliberate. Desired state and the act of reaching it fail independently: a
cluster that is unreachable is a reconciliation problem, not a lost request, and the apply is honoured
as soon as the cluster can be reached.

The service entity never talks to Kubernetes itself. A command handler that performed I/O could not be
replayed, and the journal would stop being a faithful record of what was decided.

## From the control plane to the cluster

Two processes share the work, and neither can reach into the other.

1. **The control plane projects** each service's desired state into an `AnkkaService` custom resource
   in the project's namespace. That resource is the only thing the two sides share.
2. **The operator**, running inside the cluster, watches those resources and owns everything below them:
   the Deployment, the service account and role, the in-cluster Service, the database, and, for an
   exposed service, the route. It writes what it sees back to the resource's status.
3. **The control plane folds that status back** into the service entity as an observation.

The split is what gives the platform cascade deletion through Kubernetes owner references, near-instant
change notification through watches rather than polling, and a control plane that holds no credential
able to create a workload. The operator is deliberately small and depends on nothing but Kubernetes, so
that it keeps working while other things are broken.

Before writing the resource, the control plane checks the versions a descriptor declares. A `runtime`
outside the supported range — same major version as the platform, minor version equal or one below — or
a `protocol` the platform cannot speak is refused: no resource is written, no pod starts, and the service
reports `Unavailable` with both versions in its detail. A descriptor that declares no runtime version is
not checked.

## Observed state and why it can be trusted

An observation records the lifecycle, the ready and desired instance counts, a detail message, and the
database's state, and it states **which generation it describes**. An observation about a superseded
generation — a slow report from the rollout you have just replaced — is dropped rather than allowed to
overwrite the state of the newer one. That check happens when events are applied, not when commands are
handled, so replaying the journal drops the same observations every time.

An observation identical to the one already recorded is also refused. The platform reconciles on a
timer, so without that rule a service that never changes would grow its journal forever.

Desired state wins over a report for the two decisions people make directly. A service its members have
paused reads `Paused`, and one whose organization is disabled reads `Suspended`, whatever the last report
from the cluster said.

## Confirmed and unconfirmed

Not every status is a fresh reading. When the control plane cannot reach the cluster, it restates what it
last knew and marks it **unconfirmed**. When a resource exists but no operator has ever reported on it,
the status says so, which is how a missing or broken operator shows itself. The CLI prints an unconfirmed
status as, for example, `Ready (unconfirmed)`, because a stale `Ready` that looks identical to a live one
is exactly the mistake the field exists to prevent. The `detail` line says why.

The lifecycle words themselves are listed in [Service lifecycle states](../reference/lifecycle-states.md).

## What reconciliation does not do

- **It does not autoscale.** `minInstances` is honoured as a fixed count. `maxInstances` and
  `targetCpuPercent` are validated and stored, but nothing acts on them.
- **It does not transact across entities.** Checks such as "a project with services cannot be deleted"
  are made at the API from a view, so a service created a moment earlier may not be counted yet. They
  catch mistakes; they are not constraints.
- **It does not respect changes made behind its back.** Scaling a service's Deployment with `kubectl` is
  reverted on the operator's next pass, because the resource still says otherwise. To take a service
  down, pause it.
