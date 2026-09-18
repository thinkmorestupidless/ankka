# Phase 1 Data Model: Kubernetes Service Reconciliation

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Three layers of data: what already exists and changes slightly, the custom resource that is the new
contract, and the pure values each side computes.

---

## 1. Existing types that change

### `ServiceObservation` and `ServiceEvent.ServiceObserved` — `controlplane/…/domain/events.scala`

Both gain `confirmed: Boolean = true`. `false` means the control plane could not confirm this
against the cluster and is restating what it last knew, or that a resource exists with no status
yet (FR-030, FR-031). Defaulted so events already in a journal decode.

### `Service` — `controlplane/…/domain/model.scala`

Gains `confirmed: Boolean`, default `true` in `Service.empty`, folded by `onObserved` and passed
through `toStatus`.

**Invariant to preserve**: `onObserved` stays a pure fold with the generation guard inside it. The
equality dedupe in `ServiceEntity.observe` then covers `confirmed` automatically, which keeps an
outage to one event per service rather than one per cycle (FR-022).

### `ServiceStatus` — `controlplane-api/…/descriptors.scala`

Gains `confirmed: Boolean = true`. Wire-visible; defaulted so the JSON in `README.md` and existing
fixtures decode unchanged.

### `ServiceRows` view row

Folds `confirmed` as the entity does, and resets it to `true` on any operator action — an apply
supersedes whatever staleness was recorded.

### Project id validation — `controlplane-api/…/descriptors.scala`

New shared rule beside `ServiceDescriptor.ValidName`, applied by `ProjectEndpoint` on create. The
id becomes part of a namespace name, so it must be a DNS label, bounded to `63 - (prefix + 1)`
characters. In `controlplane-api` so the CLI rejects a bad id before the round trip.

---

## 2. The contract: `NakkaService` (`crd` module)

A namespaced custom resource. **This is the only thing both sides know about.** Full wire detail in
[contracts/custom-resource.md](./contracts/custom-resource.md).

```
apiVersion: nakka.thinkmorestupidless.com/v1alpha1
kind: NakkaService
metadata:
  namespace: nakka-{projectId}
  name: {serviceName}
spec:    ← written by the control plane, never by the operator
status:  ← written by the operator, never by the control plane
```

### `NakkaServiceSpec`

| Field | Type | Notes |
|---|---|---|
| `projectId` | `String` | redundant with the namespace, carried so the resource is self-describing |
| `serviceName` | `String` | |
| `generation` | `Long` | **nakka's** generation, not `metadata.generation` |
| `paused` | `Boolean` | desired state, not an observation |
| `image` | `String` | |
| `env` | `List[EnvEntry]` | literal or secret-sourced |
| `labels`, `annotations` | `Map[String, String]` | from the descriptor |
| `instanceType` | `String` | resolved to CPU and memory by the operator |
| `autoscaling` | `AutoscalingSpec` | carried and validated, **not honoured** — see R5 |
| `progressDeadlineSeconds` | `Int` | the bounded time to failure (FR-021) |

`EnvEntry(name, value: Option[String], secretName: Option[String], secretKey: Option[String])` —
exactly one of `value` or the secret pair, the same either-or `EnvVar.problems` already enforces.

### `NakkaServiceStatus`

| Field | Type | Notes |
|---|---|---|
| `generation` | `Long` | the **spec** generation this status describes |
| `observedGeneration` | `Long` | `metadata.generation` the operator acted on |
| `lifecycle` | `String` | one of the seven `ServiceLifecycle` names |
| `readyInstances` | `Int` | |
| `desiredInstances` | `Int` | |
| `detail` | `String` (nullable) | operator-readable; **never a secret value** |
| `lastTransitionTime` | `String` | RFC 3339 |

> **Naming trap.** `spec.generation` (nakka's) and `metadata.generation` (Kubernetes') are
> different numbers with the same word. nakka's is written by the control plane and is what the
> staleness guard in `Service.onObserved` compares. Kubernetes' is bumped by the API server on
> every spec change and is only meaningful against `status.observedGeneration`. Both are needed,
> and conflating them silently breaks the guard — so both appear in the status with distinct names.

---

## 3. Control plane pure values (`nakka.controlplane.deploy`)

### `ServiceProjection.project`

```
project(service: Service, config: DeployConfig): Either[Vector[String], NakkaServiceSpec]
```

Total and pure. Fails — reporting every problem at once, matching `ServiceDescriptor.problems` —
when the project id is not a DNS label or the namespace would exceed 63 characters.

### `StatusIngest.observe`

```
observe(desired: Service, status: Option[NakkaServiceStatus], reachable: Boolean): ServiceObservation
```

Total and pure, no clock. Three cases, and the separation is the point:

| Input | Result |
|---|---|
| `reachable = false` | last known lifecycle and counts, `confirmed = false`, detail names the failure |
| `status = None` (resource exists, nothing has reported) | `UpdateInProgress`, `confirmed = false`, detail says no operator has reported (FR-031) |
| `status = Some(s)` | `s` mapped through, `confirmed = true` |

A status whose `generation` is below the service's is passed through unchanged and dropped by the
existing guard in the fold — the ingest does not second-guess it, so there is exactly one place
staleness is decided.

### `NakkaServiceClient` — the seam

`put`, `delete`, `list`, `watch`, in terms of `NakkaServiceSpec`/`Status` only. Two
implementations: `Fabric8NakkaServiceClient` and `FakeNakkaServiceClient`. Contract in
[contracts/control-plane-seam.md](./contracts/control-plane-seam.md).

---

## 4. Operator pure values (`nakka.operator`)

### `Action` — inert cluster mutations

The operator's whole vocabulary. Producing one performs no I/O; `Fabric8Executor` is the only
interpreter. Same shape as nakka's component effects, and the same shape as `cloudflow`'s
`akka.kube.actions.Action`.

| Case | Meaning |
|---|---|
| `EnsureNamespace(name)` | idempotent create |
| `ApplyDeployment(rendered)` | server-side apply, operator's field manager |
| `DeleteDeployment(namespace, name)` | idempotent |
| `SetStatus(namespace, name, status)` | write the status subresource |
| `NoAction` | desired and observed agree — **no cluster write** (SC-003) |

### `Rendering.render`

```
render(resource: NakkaService, config: OperatorConfig): Either[Vector[String], Vector[Action]]
```

Total, pure, clock-free, deterministic (FR-013). Renders a namespace, a Deployment, and an owner
reference binding the Deployment to the resource. **No HorizontalPodAutoscaler and
`spec.replicas: 1`** — see R5.

### `ClusterSnapshot`

What the informer cache holds for one service; the only input to lifecycle classification.

| Field | Type |
|---|---|
| `exists` | `Boolean` |
| `nakkaGeneration` | `Option[Long]` — read back from the annotation |
| `specReplicas`, `readyReplicas`, `updatedReplicas` | `Int` |
| `k8sGeneration`, `observedGeneration` | `Option[Long]` |
| `progressing`, `available` | `Option[ConditionState]` |
| `podProblems` | `Vector[PodProblem]` — populated only when not fully ready |

`ConditionState(status: Boolean, reason: String, message: String)`;
`PodProblem(pod: String, reason: String, message: String)`.

### `LifecycleRules.observe`

```
observe(spec: NakkaServiceSpec, observed: Option[ClusterSnapshot]): NakkaServiceStatus
```

Total and pure. Full table in [contracts/observation-rules.md](./contracts/observation-rules.md).
Separate from `render` because "what should I do" and "what should I report" differ in the failure
case — there may be nothing to do and still something to say.

---

## 5. State transitions

Lifecycle is **derived** every pass from spec plus observed, never stored as a machine. The
reachable transitions are a consequence of the rules:

```
NotDeployed ──apply──▶ UpdateInProgress ──ready──▶ Ready
                            │                        │
                            │                        ├──instance lost──▶ Unavailable ──▶ Ready
                            │                        └──apply/restart──▶ UpdateInProgress
                            │
                            ├──progress deadline exceeded──▶ Failed ──apply──▶ UpdateInProgress
                            └──pause──▶ Paused ──resume──▶ UpdateInProgress

any ──delete──▶ (resource removed; children cascade) ──▶ NotDeployed
any ──control plane cannot reach cluster──▶ same lifecycle, confirmed = false
any ──resource exists, no status yet──────▶ UpdateInProgress, confirmed = false
```

`PartiallyReady` is reachable in principle but not at one replica — it becomes live with
multi-replica support, and the rule is implemented and tested now so it does not have to be
retrofitted.

`Paused` is the one state the cluster cannot contradict: it is desired state, reported whenever
`spec.paused` regardless of what is still winding down. `confirmed` is orthogonal to all of it.

---

## 6. Relationships

```
Organization 1─* Project 1─* Service                          (control plane, Postgres)
                              │
                              ├─ desired:  ServiceDescriptor @ generation   (journal)
                              └─ observed: ServiceObservation @ generation  (journal)
                                        ▲                 │
                                        │ StatusIngest    │ ServiceProjection
                                        │                 ▼
Project ──▶ Namespace nakka-{projectId}
Service ──▶ NakkaService resource  ──owns──▶ Deployment ──▶ Pod
                                   (ownerReference: cascade delete)
```

Ownership is structural, not conventional: the owner reference is what makes FR-029 true, and it is
why there is no orphan sweep anywhere in this design.
