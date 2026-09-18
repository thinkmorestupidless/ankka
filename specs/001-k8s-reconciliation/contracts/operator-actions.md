# Contract: `NakkaService` → Cluster Objects

**Satisfies**: FR-012 to FR-017, FR-024 to FR-029

Two pure functions and one interpreter.

```scala
Rendering.render(resource, config): Either[Vector[String], Vector[Action]]   // pure, total
LifecycleRules.observe(spec, observed): NakkaServiceStatus                   // pure, total
Fabric8Executor.execute(action): Unit                                        // the only I/O
```

`Action` is inert data — the same discipline as nakka's component effects, and the same shape as
`cloudflow`'s `akka.kube.actions.Action` / `Fabric8ActionExecutor` split. Rendering performs no
I/O, so every rule below is a unit test with no cluster.

`ApplyDeployment` carries a fabric8 `Deployment`, so the pure layer does reference fabric8 *model*
types. That is intentional: building one is constructing a POJO, and a parallel model would put an
untested conversion between what the tests assert on and what the API server sees. The invariant
to check is therefore **`KubernetesClient` appears in no pure file**, not that fabric8 does not.

## Naming

| Thing | Value | Bound |
|---|---|---|
| Namespace | `{namespace-prefix}-{projectId}` | DNS label, ≤ 63 chars |
| `NakkaService` | `{serviceName}` | already validated by `ServiceDescriptor.ValidName` |
| Deployment | `{serviceName}` | same |
| Container | `{serviceName}` | same |

## Ownership — an owner reference, not a discipline

Every object the operator creates carries:

```yaml
ownerReferences:
  - apiVersion: nakka.thinkmorestupidless.com/v1alpha1
    kind: NakkaService
    name: cart
    uid: <the resource's uid>
    controller: true
    blockOwnerDeletion: true
```

Deleting the resource deletes its children. There is **no orphan sweep anywhere in this design**,
and that is the single largest thing the custom resource buys.

`uid`, not just name: a resource deleted and recreated under the same name gets a new uid, and
children of the old one are collected rather than silently adopted.

Identity labels are still applied, for selectors and for `kubectl` ergonomics:

```
app.kubernetes.io/managed-by           = nakka
app.kubernetes.io/name                 = {serviceName}
nakka.thinkmorestupidless.com/project  = {projectId}
nakka.thinkmorestupidless.com/service  = {serviceName}
```

Descriptor labels merge **under** these — a descriptor cannot override an identity label, because
that would let it impersonate another service.

## The three traps

1. **`spec.selector` is immutable.** It is the identity labels and nothing else. A generation in
   the selector bricks the service at generation 2, permanently, because the API server rejects a
   selector change. This has a dedicated regression test.

2. **Restart works through the pod template annotation.** `nakka.thinkmorestupidless.com/generation`
   on the *pod template* changes on every apply and restart, triggering a rolling replacement. That
   is why restart needs no separate mechanism (FR-027).

3. **`spec.replicas` is `1` and no HPA is rendered.** Not a simplification — a correctness
   constraint. Each pod joins itself as a single-node cluster, so two replicas means two writers to
   one journal. See research R5 for the evidence and for what multi-replica would require.

## Deployment

| Field | Source |
|---|---|
| `metadata.ownerReferences` | the resource |
| `spec.selector.matchLabels` | identity labels (**immutable**) |
| `spec.replicas` | `1`, or `0` when `spec.paused` |
| `spec.template.metadata.labels` | identity labels ++ spec labels |
| `spec.template.metadata.annotations` | spec annotations ++ nakka generation |
| `…containers[0].image` | `spec.image` |
| `…containers[0].env` | literal `value`, or `valueFrom.secretKeyRef` |
| `…containers[0].resources.limits.cpu` | `InstanceType.cpuMillis` as `{n}m` |
| `…containers[0].resources.limits.memory` | `InstanceType.memoryMiB` as `{n}Mi` |
| `spec.progressDeadlineSeconds` | `spec.progressDeadlineSeconds` — Kubernetes owns the failure clock |

Requests equal limits: the descriptor models one size, and inventing a ratio would be a policy
nobody asked for.

**No HorizontalPodAutoscaler, no Service, no Ingress, no PersistentVolumeClaim.** A service reaches
its database through environment variables the descriptor supplies (see
[configuration.md](./configuration.md)); nothing else is rendered.

## Writing

Server-side apply, field manager `nakka-operator`, `forceConflicts`. Drift policy is enforce, so a
conflict means someone claimed a field the operator owns and the resource wins (FR-017).

The operator's field manager is distinct from the control plane's (`nakka-controlplane`), which is
what lets each revert edits to its own fields without touching the other's.

## Determinism (FR-013)

Same resource at the same generation → byte-identical objects. No timestamps, no random suffixes,
no `Instant.now()`, ordered map iteration. A test renders twice and asserts equality — cheap, and
it catches the accidental clock.
