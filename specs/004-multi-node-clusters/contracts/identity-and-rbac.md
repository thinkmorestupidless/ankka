# Contract: Identity and Permissions

**Satisfies**: FR-003, FR-028 to FR-031

## What each service gets

In its project's namespace, all carrying an owner reference to the `NakkaService`, so they are
removed with it and need no `delete` verb anywhere (FR-030):

```yaml
kind: ServiceAccount          # name: <service>
---
kind: Role                    # name: <service>-peers
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
---
kind: RoleBinding             # name: <service>-peers  →  the Role, the ServiceAccount
```

| Property | How |
|---|---|
| cannot see outside its project (FR-028) | a `Role`, never a `ClusterRole` |
| cannot change anything (FR-028) | three read verbs, one resource |
| granted to a service, not to a project's workloads (FR-029) | one ServiceAccount per service, not the namespace default |
| never joins another service's pods (FR-003) | peers are selected by `Labels.identity` — all four labels, the same value the Deployment and Service select on |

**The honest cost**: a service can list *every* pod in its project, including other services'. It
cannot join them — the selector prevents that — but it can see them. That is what Kubernetes API
discovery costs, it was chosen knowingly over DNS-based discovery, and it is bounded at the project.

## What the operator gains

Added to its `ClusterRole`:

| Resource | Verbs |
|---|---|
| `serviceaccounts` | get, list, watch, create, patch |
| `roles`, `rolebindings` (`rbac.authorization.k8s.io`) | get, list, watch, create, patch |

`patch` is not optional — every ensure is a server-side apply, which is a PATCH even for a new object.
No `delete`: owner references do it.

### Granting what you hold

Kubernetes will not let a principal create a Role granting permissions it lacks. The operator already
holds `pods: get, list, watch` — added in feature 001 to explain un-ready Deployments — which is
exactly the Role's content, so neither `escalate` nor `bind` is needed.

**That is reasoning, not a result.** This ClusterRole has been wrong in this way twice, and both times
the k3s suites could not see it because they run the operator on admin credentials. So:

- the real `Fabric8Executor`, under the operator's **real ServiceAccount token**, creates all three
  objects — the pattern feature 003's T025 established;
- a **service's** real token is then used to try listing pods in another namespace, and to try
  creating, patching and deleting a pod in its own: every one refused (SC-007).

## The control plane

The same three objects, as static kustomize manifests in `nakka-controlplane`. A `Role`, so
`controlplane-rbac.yaml`'s "still no workload access" stays true: the grant does not reach a project
namespace.
