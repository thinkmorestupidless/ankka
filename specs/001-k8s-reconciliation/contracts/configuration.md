# Contract: Configuration, RBAC and Installation

**Satisfies**: FR-035, FR-036, FR-039

## Control plane — `controlplane/src/main/resources/reference.conf`

```hocon
nakka.controlplane.kubernetes {
  # Namespace per project: "{namespace-prefix}-{projectId}".
  namespace-prefix = "nakka"
  namespace-prefix = ${?NAKKA_K8S_NAMESPACE_PREFIX}

  # How often every service is re-projected and swept. Desired-state changes do not
  # wait for this — a consumer on the service journal projects immediately.
  sweep-interval = 30s

  # Backoff after a failed projection, per service.
  retry-min-backoff = 5s
  retry-max-backoff = 2m

  # Carried into the resource as spec.progressDeadlineSeconds, so Kubernetes owns the
  # not-progressing clock and the control plane does not run a second one.
  progress-deadline = 10m

  # Refuse to start if the cluster is unreachable. Off by default: recording intent
  # durably is the control plane's job, and an apply must succeed when the cluster is down.
  fail-fast-on-unreachable-cluster = false
}
```

## Operator — `operator/src/main/resources/reference.conf`

```hocon
nakka.operator {
  # Namespaces to watch. Empty means all namespaces matching the prefix.
  namespace-prefix = "nakka"
  namespace-prefix = ${?NAKKA_K8S_NAMESPACE_PREFIX}

  # Informer resync. Watches deliver changes; this is the backstop that notices what
  # nobody announced.
  resync-interval = 5m

  # Backoff after a failed reconcile, per resource.
  retry-min-backoff = 2s
  retry-max-backoff = 5m

  # Ceiling on concurrent reconciles. Handlers run on virtual threads, so this bounds
  # load on the API server rather than threads.
  max-concurrent-reconciles = 16
}
```

`namespace-prefix` appears on both sides and **must agree**. A mismatch is the most likely
misconfiguration, and it presents exactly as FR-031's "no operator has reported on this service" —
which is precisely why that signal exists.

## Removed key

```hocon
nakka.controlplane.namespace = "nakka"     # DELETE
```

Replaced by `kubernetes.namespace-prefix`. It declared a single namespace for every service, which
contradicts per-project isolation. It has **no readers in the codebase**, so removal is safe;
leaving it would ship two keys where one is silently ignored.

## Credentials

fabric8's standard resolution on both sides: in-cluster service account → `KUBECONFIG` →
`~/.kube/config`. No nakka-specific path setting, so this stays a deployment concern.

## RBAC — the split is the security property

**Control plane** (`Role` per watched namespace, or one narrow `ClusterRole`):

| Resource | Verbs |
|---|---|
| `nakkaservices` | get, list, watch, create, patch, delete |
| `nakkaservices/status` | get, list, watch |
| `namespaces` | get, list |

It cannot create a Deployment, cannot read a Secret, and cannot write a status. Even fully
compromised it can only ask for workloads, not make them.

**Operator** (`ClusterRole`):

| Resource | Verbs |
|---|---|
| `nakkaservices` | get, list, watch |
| `nakkaservices/status` | update, patch |
| `namespaces` | get, list, create |
| `apps/deployments` | get, list, watch, create, patch, delete |
| `pods` | get, list, watch |

It cannot change desired state — no `update` on `nakkaservices` itself, only on the status
subresource. It has **no verb on `secrets`**, which makes FR-023 ("detail never contains a secret
value") structural: it cannot read one. Secrets reach a pod because the kubelet resolves
`valueFrom.secretKeyRef`, not because the operator passes them through.

## Installation (FR-039)

Two manifests, single copies, shipped on the operator's classpath:

```
operator/src/main/resources/nakka/crd/nakkaservice.yaml       # the CustomResourceDefinition
operator/src/main/resources/nakka/install/operator.yaml       # ServiceAccount, ClusterRole,
                                                              # ClusterRoleBinding, Deployment
```

Applying either twice must be a no-op. The same single-copy rule the DDL follows applies here: the
k3s test suites install from these exact files, so a manifest that does not work cannot pass CI.

## Test override

Both cluster suites supply the k3s container's kubeconfig and much shorter intervals — a 1s
resync and a short progress deadline — so a test asserts on the next pass rather than waiting
minutes. Overriding a duration in test config is the established pattern and is not a reason to
lower a production default.
