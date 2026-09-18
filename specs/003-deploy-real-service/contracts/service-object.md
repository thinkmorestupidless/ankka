# Contract: The Rendered Service and Readiness

**Satisfies**: FR-007 to FR-013

> **Superseded in part by [feature 004](../../004-multi-node-clusters/spec.md).** The `tcpSocket`
> readiness probe below became `httpGet /ready` on the runtime's management port (cluster
> membership plus the HTTP server having bound), and the `strategy: Recreate` that feature 003's
> implementation added — with "a deploy is a brief outage" — was reversed to `RollingUpdate`
> `maxSurge: 1, maxUnavailable: 0` once nodes could find each other. The port, `NAKKA_HTTP_PORT`
> and the Service object are unchanged.

## The Service object

Rendered only when the resolved port is present ([port-resolution.md](./port-resolution.md)).

```yaml
apiVersion: v1
kind: Service
metadata:
  name: cart                      # Names.service — same as the Deployment and container
  namespace: nakka-checkout       # the project's namespace
  labels:                         # merged platform + descriptor labels
    app.kubernetes.io/managed-by: nakka
    app.kubernetes.io/name: cart
    nakka.thinkmorestupidless.com/project: checkout
    nakka.thinkmorestupidless.com/service: cart
  annotations:
    nakka.thinkmorestupidless.com/generation: "1"
  ownerReferences:
    - apiVersion: nakka.thinkmorestupidless.com/v1
      kind: NakkaService
      name: cart
      uid: <the resource's uid>
      controller: true
      blockOwnerDeletion: true
spec:
  type: ClusterIP
  selector:                       # Labels.identity — the Deployment's selector, not a second one
    app.kubernetes.io/managed-by: nakka
    app.kubernetes.io/name: cart
    nakka.thinkmorestupidless.com/project: checkout
    nakka.thinkmorestupidless.com/service: cart
  ports:
    - name: http
      port: 9000
      targetPort: 9000
      protocol: TCP
```

### The three fields that matter most

- **`spec.selector` is `Labels.identity`, the same value the Deployment's `spec.selector` uses.**
  Computing a second selector — even one that looks equivalent — is how a Service ends up with no
  endpoints and a service that is `Ready` but unreachable. It is the "a name computed slightly
  differently in two places" failure applied to labels.
- **`ownerReferences` points at the `NakkaService`.** Deleting the service deletes the Service with
  no sweep and no delete verb (FR-009), exactly as the Deployment is removed today. This is the
  *opposite* of feature 002's rule for CNPG objects and credential secrets, which carry no owner
  reference precisely so they outlive the resource — a Service holds no data, and an orphaned address
  routing nowhere is strictly worse than none.
- **`port` and `targetPort` are the same resolved value.** The Service is not a place to translate
  between an external and an internal port; nothing in this feature has an external port.

## Readiness

Rendered on the workload container, only when the resolved port is present.

```yaml
readinessProbe:
  tcpSocket:
    port: 9000                    # the resolved port
  initialDelaySeconds: 10
  periodSeconds: 5
```

| Decision | Why |
|---|---|
| `tcpSocket`, not `httpGet` | The operator knows neither the workload's routes nor its ACL — an endpoint may require a bearer token, and a probe is not a place to hold one. The control plane's own Deployment settled this identically. |
| readiness only, **no liveness probe** | A liveness probe firing during a long GC or a slow start restarts a healthy pod; on a single-replica service whose entities rehydrate from the journal, that turns a hiccup into an outage. |
| `initialDelaySeconds: 10` | A JVM plus a Pekko cluster does not bind instantly; probing immediately only produces noise in the pod's event log. |
| no probe at all when serving no HTTP | Otherwise a service that serves no HTTP could never become ready (FR-012) — the failure mode that makes "always probe" wrong. |

### What this changes about `Ready`

Nothing in `LifecycleRules`. It already derives `Ready` from the Deployment's `readyReplicas`, and
`readyReplicas` counts only pods passing their readiness probe. Adding the probe therefore upgrades
`Ready` from *"the container process started"* to *"the port is open"* **without touching the status
machinery at all** — feature 001's honest-status promise gets more honest by having something real to
report on.

A service whose port never opens is already covered: `readyReplicas` stays 0, the Deployment exceeds
`progressDeadlineSeconds`, and `LifecycleRules` reports `Failed` with the rollout's own reason
(FR-013). That is the same path an unpullable image takes today, reusing the Deployment's clock
rather than introducing one — the constraint feature 001 set so that two clocks can never disagree
about whether a rollout has given up.

## Lifecycle

| Event | Service |
|---|---|
| service applied, port resolved | created by server-side apply |
| re-applied unchanged | server-side apply writes nothing new — steady state stays silent (feature 001's FR-008) |
| port changed | the same Service updated in place; `spec.ports` and the pods both follow the new value |
| service deleted | removed by owner reference, no operator action |
| no HTTP → serves HTTP | Service created on the next reconcile |
| serves HTTP → no HTTP | **the Service is removed** — guarded, see below |

### Turning HTTP off removes the address — carefully

The spec's edge case is explicit: when a service stops serving HTTP, "the address is created or
removed to match". So when the resolved port is `None` the operator removes the Service, through a
new inert `Action.RemoveService(namespace, name, ownerUid)`.

The first draft of this plan left the Service behind, reasoning that the operator should hold no
`delete` verb "for consistency with feature 002". That misapplied the rule: feature 002 withholds
`delete` on things that hold *data*. A Service holds none, and the operator already has `delete` on
`deployments` and a `DeleteDeployment` action.

Two guards, both of which matter:

- **Delete only what this resource owns.** `RemoveService` carries the `NakkaService`'s uid, and the
  executor deletes only a Service whose `ownerReferences` contains it. A user may legitimately
  hand-create a Service named after a no-HTTP workload — for a protocol this platform does not model
  — and the operator must never remove an object it did not create.
- **Steady state stays silent.** For a service that never served HTTP there is nothing to remove, and
  issuing a `DELETE` on every reconcile pass would break feature 001's "an unchanged service writes
  nothing" (its FR-008). The executor reads first and deletes only if an owned Service is present.
