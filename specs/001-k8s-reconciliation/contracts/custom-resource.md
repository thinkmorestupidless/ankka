# Contract: The `NakkaService` Custom Resource

**Satisfies**: FR-001 to FR-004

The entire interface between the control plane and the operator. Neither side may depend on
anything else about the other. Owned by the `crd` module, which depends on nothing from nakka.

```
group:    nakka.thinkmorestupidless.com
version:  v1alpha1
kind:     NakkaService
plural:   nakkaservices
short:    nsvc
scope:    Namespaced
subresources: status
```

Namespaced, not cluster-scoped, and that is load-bearing: owner references only work within a
namespace, and the resource living beside the objects it owns is what makes cascade deletion
structural (R6, R10).

## Example

```yaml
apiVersion: nakka.thinkmorestupidless.com/v1alpha1
kind: NakkaService
metadata:
  namespace: nakka-checkout
  name: cart
spec:
  projectId: checkout
  serviceName: cart
  generation: 4
  paused: false
  image: registry.example.com/acme/cart:1.4.2
  env:
    - name: LOG_LEVEL
      value: info
    - name: NAKKA_DB_PASSWORD
      secretName: cart-db
      secretKey: password
  labels: {}
  annotations: {}
  instanceType: small
  autoscaling: { minInstances: 1, maxInstances: 10, targetCpuPercent: 80 }
  progressDeadlineSeconds: 600
status:
  generation: 4
  observedGeneration: 7
  lifecycle: Ready
  readyInstances: 1
  desiredInstances: 1
  detail: null
  lastTransitionTime: "2026-09-14T10:31:02Z"
```

## Who writes what

| Part | Written by | Never written by |
|---|---|---|
| `metadata`, `spec` | control plane | operator |
| `status` (subresource) | operator | control plane |

The status subresource is what makes this enforceable rather than a convention: the operator's
RBAC grants `nakkaservices/status: update` and **not** `nakkaservices: update`, so an operator bug
cannot rewrite desired state.

## The two generations

| Field | Owner | Meaning |
|---|---|---|
| `spec.generation` | control plane | **nakka's** generation. Bumped by every apply and restart. What `Service.onObserved` compares. |
| `metadata.generation` | API server | Bumped on every spec change. Only meaningful against `status.observedGeneration`. |

`status.generation` echoes `spec.generation`; `status.observedGeneration` echoes
`metadata.generation`. Both are needed — the first ties a report to an operator's intent, the
second tells the operator whether it has caught up with the API server — and conflating them
breaks the staleness guard silently.

## Printer columns

So `kubectl get nsvc` is useful without the CLI, which is a large part of why a resource was chosen
over a private protocol:

```
NAME   STATUS   READY   GEN   IMAGE                                      AGE
cart   Ready    1/1     4     registry.example.com/acme/cart:1.4.2       3m
```

## Versioning

`v1alpha1`, with `spec.autoscaling` present but **not honoured** (R5) — it is carried so that
enabling multi-replica support later is not a schema break.

An operator encountering a resource whose `apiVersion` it does not understand MUST record that in
`status.detail` and take no action (FR-004). Acting on a partial reading is the failure mode this
prevents: a field it cannot see is a field it would silently drop.

## Validation

The CRD manifest carries an OpenAPI schema with `required` on `projectId`, `serviceName`,
`generation` and `image`. This makes the API server reject a malformed resource, which means the
control plane's own validation and the operator's are backed by a third check neither side can
bypass — including a `kubectl apply` by hand.

Schema validation does **not** replace `ServiceDescriptor.problems`. That runs in
`controlplane-api`, shared by the CLI and server, so an operator sees a useful error before the
round trip rather than a schema rejection afterwards.
