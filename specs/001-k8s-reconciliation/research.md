# Phase 0 Research: Kubernetes Service Reconciliation

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-14

**Revision note**: R0 records the architecture change that caused this document to be rewritten.
Decisions from the superseded direct-to-Deployment design are kept where they survived, and marked
where they did not.

---

## R0 — Why a custom resource and an operator, not a direct reconciler

**Decision**: the control plane projects desired state into a `NakkaService` custom resource; an
in-cluster operator renders and owns the workload and writes status back.

**Rationale**: The first design had a cluster singleton inside the control plane writing
Deployments directly. Reviewing it against the sibling `cloudflow` project made the cost visible:
four separate research items in the superseded version existed only to reimplement things
Kubernetes already provides.

| Problem | Superseded design | With a custom resource |
|---|---|---|
| Cascade delete | sweep the cluster for orphans, join against desired state | `ownerReferences` — the API server deletes children |
| Change notification | poll every service on a timer | watch; reaction is sub-second |
| Desired vs observed staleness | hand-rolled generation guard on both sides | `metadata.generation` vs `status.observedGeneration`, plus nakka's own generation carried in the spec |
| Ownership | a `managed-by` label and a discipline of checking it | ownership is structural, and the operator's RBAC is namespace-scoped |
| Credentials | the control plane holds cluster write access to workloads | the operator uses its own in-cluster identity; the control plane's access is narrowed to one resource kind |

`cloudflow` also demonstrates the shape working in Scala:
`core/cloudflow-operator/src/main/scala/akka/kube/actions/Action.scala` defines inert values
describing cluster mutations and `Fabric8ActionExecutor` is the only code that performs them. That
is exactly nakka's organising idea — effects are inert data, the runtime interprets them — so the
pattern is idiomatic here rather than imported.

**What was *not* adopted from cloudflow**: its CLI is a `kubectl` plugin that writes the custom
resource itself, with no server in between — Kubernetes *is* its control plane database. nakka
cannot do that without deleting organizations, projects, tombstoned ids, the bearer-token HTTP API
and the audit journal, none of which are Kubernetes-shaped, and all of which exist because nakka
mirrors Akka's hosted platform. So the control plane stays, and the custom resource becomes the
boundary between it and the cluster rather than the system of record.

**Alternatives considered**: keeping the direct reconciler (rejected above); going fully
CRD-native like cloudflow and deleting the control plane's service layer (rejected — it discards
the tenancy model and the divergence from Akka would be severe); a control plane that talks to the
operator over HTTP instead of through a resource (rejected — it needs inbound reach into the
cluster, invents a second API to version, and loses `kubectl` as a debugging surface).

**Consequence**: the previously chosen "direct now, agent seam later" answer is superseded in the
better direction. The operator *is* the agent, the custom resource is its contract, and it is being
built now rather than deferred.

---

## R1 — Kubernetes client library

**Decision**: `io.fabric8:kubernetes-client:7.9.0`, in both the operator and the control plane.

**Rationale**: Already declared in `controlPlane`'s dependencies and used nowhere — the choice was
made when the build was written. It has first-class support for typed custom resources, informers,
and server-side apply. It is blocking, which suits both callers: the operator runs handlers on
virtual threads, and the control plane's projector does too.

Its custom-resource support requires **Jackson**, which is a new dependency for the `crd` module.
This does not conflict with nakka's use of jsoniter: jsoniter serialises nakka's own journal and
HTTP payloads, Jackson serialises the Kubernetes resource. They meet nowhere, and forcing jsoniter
into fabric8's serialisation path would mean reimplementing its resource handling.

**Alternatives considered**: official `io.kubernetes:client-java` (heavier, not in the build);
`skuber` (unmaintained for current Kubernetes versions); raw HTTP (the work is in the object models
and watch semantics, not the transport).

---

## R2 — What the operator is built on

**Decision**: fabric8 `SharedIndexInformer` for change notification, a de-duplicating work queue
keyed by resource, and one virtual thread per in-flight reconcile. **No Pekko in the operator at
all.**

**Rationale**: This is the standard controller-runtime shape — watch, enqueue by key, reconcile
idempotently — and it is what makes FR-024 (one failing service cannot block another) and FR-025
(rebuild from what is found on restart) fall out rather than be engineered. Keying the queue by
resource gives per-service serialization for free, which is the same property the superseded design
needed a singleton and an in-flight map to get.

Excluding Pekko is deliberate. The operator has no entities, no journal, no sharding and no views.
Adding an actor system would give it a cluster to form and a database to not use, and would make a
process whose entire job is to keep working while other things are broken depend on more things
being unbroken. `cloudflow` used Akka Streams here because it was an Akka product; that reason does
not transfer.

**Alternatives considered**: Pekko Streams over a watch source, mirroring `cloudflow`'s
`watchCr → toAction → executeActions` pipeline (elegant, and rejected only for the dependency cost
— the flow structure is worth copying even without the library); hosting the operator as a nakka
application for dogfooding symmetry (rejected in Complexity Tracking); a bare `watch()` without an
informer (no resync, no local cache, and a disconnect silently stops reconciliation).

---

## R3 — What triggers work, on each side

**Decision**:

- **Operator**: informer events on `NakkaService`, plus informer events on the objects it owns
  (so an out-of-band Deployment deletion is noticed immediately), plus a periodic resync.
- **Control plane**: a `Consumer` over `ServiceEntity` events for immediate projection, plus a
  periodic sweep that re-projects everything and reconciles against what is actually in the
  cluster.

**Rationale**: FR-018 and FR-006 require both event-driven and periodic paths on both sides, for
the same reason in each case: the event path gives latency, the periodic path is the only thing
that can notice what nobody announced. Watching owned objects is what delivers SC-004's 10-second
healing — the superseded design's 10-second *polling* cadence could only promise one cycle.

The control plane's consumer rides `ProjectionRuntime`, which it already registers, giving ordered
at-least-once delivery with a durable offset per entity.

**Alternatives considered**: polling on the operator side (rejected — an informer is strictly
better and is what the client library is for); no periodic resync (rejected — a missed watch event
during a disconnect would never be recovered).

---

## R4 — How each side writes

**Decision**: **server-side apply** with a distinct field manager on each side —
`nakka-controlplane` for the resource spec, `nakka-operator` for the objects it owns.

**Rationale**: Distinct field managers are what make FR-007 and FR-017 separable. The control plane
owns the resource's `spec` and reverts an out-of-band `kubectl edit` of it; the operator owns the
Deployment's fields and reverts an out-of-band edit of those; neither claims the other's. Because
enforce-on-every-cycle is the chosen drift policy, both use `forceConflicts` — a conflict means
someone else claimed a field this manager owns, and the answer is that the recorded state wins.

Status is written through the **status subresource**, which is why the operator can write status
without ever touching spec, and the control plane can write spec without clobbering status.

**Alternatives considered**: read-modify-write with resource versions (needs a hand-written
three-way merge on both sides); `replace` (clobbers the other manager's fields); one shared field
manager (the two sides would fight over every field).

---

## R5 — Replicas, autoscaling, and why there is neither *(revised — the superseded version was unsafe)*

**Decision**: the operator renders `spec.replicas: 1` and **no HorizontalPodAutoscaler**. The
descriptor's `autoscaling` block is validated and carried into the resource but not honoured.

**Rationale**: The superseded plan rendered an HPA with `minInstances`/`maxInstances` from the
descriptor. That was actively dangerous, and the evidence is in the runtime's own configuration:
`modules/runtime/src/main/resources/reference.conf` sets `pekko.cluster.seed-nodes = []`,
`nakka.join-self-if-no-seed-nodes = on` and `remote.artery.canonical.hostname = "127.0.0.1"`, and
`Nakka.joinSelfIfUnseeded` joins the node to itself when no seeds are configured. The build carries
no `pekko-management`, no `pekko-management-cluster-bootstrap` and no `pekko-discovery-kubernetes-api`.

Two replicas is therefore two independent single-node clusters sharing one journal, each running
its own sharding region and each able to host the same entity id. Two writers to one
`persistence_id` is the failure event sourcing is defined to prevent. An HPA would reach that state
automatically.

`README.md`'s divergence table currently attributes `minInstances = 1` to nakka targeting a
development cluster. That framing should be corrected: it is 1 because more than 1 does not
currently work.

**What multi-replica needs**, when it becomes its own feature: `pekko-management-cluster-bootstrap`
and `pekko-discovery-kubernetes-api` in `modules/runtime`; the management and remoting ports in the
container spec; a headless Service; a ServiceAccount with a Role granting `pods: get,list,watch`;
and management health routes wired to the readiness and liveness probes. `cloudflow`'s
`AkkaRunner.scala` is a complete worked reference for exactly this, including
`createAkkaClusterPolicyRule`. The operator is the right home for all of the rendering.

---

## R6 — Ownership, naming, and the immutable selector

**Decision**:

- Namespace per project: `{prefix}-{projectId}`, default prefix `nakka`.
- The `NakkaService` resource lives in that namespace, named for the service.
- Every object the operator creates carries an `ownerReference` to the resource.
- Identity labels: `app.kubernetes.io/managed-by=nakka`, `app.kubernetes.io/name={service}`,
  `nakka.thinkmorestupidless.com/project`, `nakka.thinkmorestupidless.com/service`.
- nakka's generation is an **annotation**, never a label.
- The Deployment's `spec.selector` is the identity labels and **never** the generation.

**Rationale**: Owner references make FR-015 and FR-029 structural — deleting the resource deletes
its children, with no orphan sweep to write or get wrong. This is the single largest simplification
R0 buys, and it only works because the resource and its children are in the same namespace, which
is why per-project namespaces and namespaced resources go together.

The selector rule is a hard trap: `spec.selector` is immutable after creation, so an apply that
changes it is rejected permanently. A generation in the selector bricks the service at generation
2. The generation still has to reach the pod template for FR-027 (restart) to work, so it goes in
the *pod template's* annotations — changing a template annotation triggers a rolling replacement,
which makes restart and apply one mechanism rather than two.

**Consequence — project ids are not validated.** `ServiceKey`'s comment asserts "project ids and
service names are both DNS labels", but only the service name is checked
(`ServiceDescriptor.ValidName`), and `ProjectEndpoint.postBody("/{projectId}")` accepts any path
segment. Rendering an unvalidated id into a namespace name is unsafe, so this feature adds the same
validation on project creation, bounded to 63 minus the prefix length.

**Alternatives considered**: one shared namespace with `{projectId}-{name}` object names (the
existing `nakka.controlplane.namespace` key implies this) — rejected because the combined name can
exceed 63 characters and because owner references would then be the only isolation; a label-only
ownership discipline (the superseded design — strictly worse now that owner references are
available).

---

## R7 — Reading observed state

**Decision**: the operator informs on the Deployments it owns and derives status from the cached
object, reading pods only when a Deployment is not fully ready.

**Rationale**: The informer cache means status derivation costs no API call at all in steady state,
which is what makes SC-003 (zero writes, zero reads when nothing changes) achievable rather than
aspirational. Reading pods conditionally keeps the expensive call on the path where it buys
something — explaining *why* a Deployment is not ready.

**Alternatives considered**: polling (the superseded decision; strictly worse here, and it was only
chosen because the superseded design had to enumerate everything anyway); reading pods always
(cost with no benefit on the healthy path).

---

## R8 — Classifying failure

**Decision**: derive lifecycle from the Deployment's status conditions first, then pod container
statuses for the human-readable reason. `Progressing=False` with reason `ProgressDeadlineExceeded`
is the authoritative "this rollout has given up" signal; detail comes from the first pod container
status in `ImagePullBackOff`, `ErrImagePull`, `CreateContainerConfigError` or `CrashLoopBackOff`.

**Rationale**: FR-021 needs a bounded time to failure, and Kubernetes implements exactly that as
`spec.progressDeadlineSeconds`. Reusing it means no second, competing timer that could disagree
with the cluster. `CreateContainerConfigError` is what a missing secret surfaces as, which is the
spec's named edge case. Full table in [contracts/observation-rules.md](./contracts/observation-rules.md).

**Alternatives considered**: the operator timing rollouts itself (two clocks, two answers, and it
would have to persist start times across restarts); reading Events (best-effort, rate-limited and
garbage-collected, so a detail built from them is not reproducible).

---

## R9 — Reporting that the cluster could not be read

**Decision**: extend the observation with `confirmed: Boolean`, carried through to `ServiceStatus`
and rendered distinctly by the CLI. The control plane reports `confirmed = false` when it cannot
reach the cluster; it also reports it when a resource exists but carries no status at all, which is
how FR-031 ("nothing is acting on it") is distinguished from a rollout in progress.

**Rationale**: FR-030. Encoding it in `detail` fails on evidence:
`cli/src/main/scala/nakka/cli/Output.scala:54` prints `NAME STATUS INSTANCES GEN IMAGE` and drops
`detail`, so the signal would be invisible in the one view an operator scans. An eighth
`ServiceLifecycle` case was rejected because staleness is orthogonal to lifecycle.

The existing dedupe guard does the right thing unchanged: the first unconfirmed observation differs
from what is recorded and persists; every identical one after is refused, so an outage costs one
event per service rather than one per tick.

---

## R10 — Deletion *(largely dissolved)*

**Decision**: the control plane deletes the custom resource; Kubernetes deletes everything else.

**Rationale**: This is what owner references are for, and it replaces the superseded design's
union-sweep entirely. The only remaining obligations are that the control plane's tombstone
survives so a pending delete completes when the cluster returns (FR-009), and that the periodic
sweep deletes any `NakkaService` resource with no desired state behind it — a much smaller problem
than sweeping arbitrary workloads, because the resource kind is nakka's own.

**Alternatives considered**: finalizers on the resource (they guard deletion of the object, which
is not the problem here — the control plane's record is already the authority, and a finalizer
would let a broken operator block deletion forever).

---

## R11 — Running real clusters in the test suite

**Decision**: `K3sContainer` from testcontainers 1.21.4 (already declared), one container per
suite, kubeconfig from `getKubeConfigYaml()`. Two cluster suites: the operator alone, and one
end-to-end running the control plane and the operator together. Both tagged so they can be excluded.

**Rationale**: FR-038. The operator suite proves rendering against a real API server — selector
immutability, owner-reference cascade, and admission — which a fake would model wrongly and then
agree with itself about. The end-to-end suite is the only thing that catches the two sides
disagreeing about the custom resource, which is the same argument the repository already makes for
taking `cli % Test` in `controlplane`.

**Risks to carry into tasks**: k3s needs a privileged container, which some CI sandboxes refuse;
startup takes tens of seconds, so both suites need a generous `munitTimeout` like
`ControlPlaneHttpSuite`'s four minutes; and the CRD must be installed into the test cluster before
either side starts, which makes FR-039's install path a test dependency rather than an afterthought
— a good forcing function.

---

## R12 — Configuration

**Decision**: a `nakka.controlplane.kubernetes { }` block for the control plane and a separate
`nakka.operator { }` block for the operator. The existing `nakka.controlplane.namespace` key is
**removed** and replaced by `kubernetes.namespace-prefix`.

**Rationale**: The existing key declares one namespace for every service, contradicting per-project
isolation. It has no readers in the codebase, so removing it is safe; leaving it would ship two
keys where one is silently ignored. Credentials follow fabric8's standard resolution on both sides
— in-cluster service account, then `KUBECONFIG`, then `~/.kube/config` — which keeps FR-036 a
deployment concern. Full listing and the RBAC split in
[contracts/configuration.md](./contracts/configuration.md).

---

## R13 — Databases *(new — the superseded spec was wrong about this)*

**Decision**: for this feature, a service's database is supplied by whoever writes the descriptor,
through the existing `env` and `secretKeyRef` fields. The platform provisions nothing. **One
database per service is mandatory** and the documentation must say so and say why.

**Rationale**: The superseded spec said "services are assumed stateless", which is false — a nakka
service is event-sourced and cannot start without Postgres carrying the journal, snapshot,
durable-state, projection and timer tables. The runtime already reads `NAKKA_DB_HOST`, `PORT`,
`NAME`, `USER` and `PASSWORD` from the environment, so the existing descriptor already expresses
this and no new field is needed.

Sharing one database between two nakka services is **destructive, not merely untidy**, and the
reason is specific: `nakka_timers` has no service or application column, `TimerStore.due` selects
from it with no filter, and `TimerSweeper.fire` **deletes** any row whose `component_id` is not in
its own registry — logging "not registered; dropping it". Two services on one database therefore
delete each other's timers. View row tables compound it: `ViewDescriptor.tableFor(componentId)`
derives the table name from the component id alone, so two services each with a `service-rows` view
write the same table, as do their projection offsets.

**Alternatives considered**: the operator provisioning a database per service on a configured
shared server, applying the canonical DDL and injecting generated credentials from a Secret — this
is the right long-term answer, is directly analogous to `cloudflow`'s `TopicActions` provisioning
Kafka topics per application, and now has a natural home; deferred as its own feature because
credential rotation and whether deleting a service drops its data are real questions this feature
should not answer in passing. A Postgres StatefulSet per service was rejected as heaviest by a wide
margin.
