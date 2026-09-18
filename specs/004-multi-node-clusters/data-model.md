# Data Model: Multi-Node Service Clusters

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

No new descriptor field, no new custom-resource field, no journal change. This feature is almost
entirely about *behaviour* — which is why the contracts are longer than this document.

---

## Configuration: one base, two overlays

All in `modules/runtime/src/main/resources/`. See [contracts/config-layering.md](./contracts/config-layering.md).

| File | Layer | Says how nodes find each other? |
|---|---|---|
| `reference.conf` | base | **no** — that is the rule that keeps it a base |
| `nakka-cluster-local.conf` | overlay | yes: join self, or named seed nodes |
| `nakka-cluster-kubernetes.conf` | overlay | yes: bootstrap through the Kubernetes API |

### Moves out of the base

| Setting | Today (base) | After |
|---|---|---|
| `pekko.remote.artery.canonical.hostname = "127.0.0.1"` | base | local overlay; Kubernetes overlay uses `${POD_IP}` |
| `pekko.remote.artery.canonical.port = 0` | base | local overlay (`${?NAKKA_CLUSTER_PORT}`); Kubernetes overlay fixes `17355` |
| `pekko.cluster.seed-nodes = []` | base | gone; the local overlay has `nakka.cluster.seed-nodes`, comma-separated, from `${?NAKKA_CLUSTER_SEED_NODES}` — an env var is a string, and Pekko's key is a list |
| `nakka.join-self-if-no-seed-nodes = on` | base | local overlay `on`; Kubernetes overlay **`off`** |

### Added to the base

| Setting | Value | Why |
|---|---|---|
| `pekko.coordinated-shutdown.exit-jvm` | `on` | a node downed by the split-brain resolver must exit so Kubernetes restarts it; otherwise it lingers `Running`, never ready, never restarted (research R9) |
| `pekko.cluster.split-brain-resolver.active-strategy` | `keep-majority` | stated rather than inherited, because the instance-count guidance depends on it |

### New

| Setting | Values | Set by |
|---|---|---|
| `nakka.cluster.formation` | `join-self-or-seeds` \| `bootstrap` | each overlay |

---

## Environment the operator injects

Only in Kubernetes, only by the platform (FR-010) — never by the person writing a descriptor.

| Variable | Value | Consumed by |
|---|---|---|
| `NAKKA_CLUSTER_MODE` | `kubernetes` | the loader — picks the overlay |
| `POD_IP` | downward API, `status.podIP` | remoting and management bind address |
| `NAKKA_CLUSTER_SERVICE` | the service name | bootstrap's `service-name` |
| `NAKKA_CLUSTER_POD_SELECTOR` | `Labels.identity`, as `k=v,k=v` | discovery's `pod-label-selector` |
| `NAKKA_CLUSTER_CONTACT_POINTS` | `min(instances, 2)` | bootstrap's `required-contact-point-nr` |

All five join `NAKKA_HTTP_PORT` on the list of names a descriptor's `env` may not set. One rule,
extended: the platform's variables are the platform's.

---

## Instance count — an existing field, honoured

| Layer | Field | Change |
|---|---|---|
| descriptor | `resources.autoscaling.minInstances` (default 1) | none |
| custom resource | `spec.autoscaling.minInstances` | none — already projected |
| operator | `Rendering.Replicas = 1` | **removed**; replicas = `spec.autoscaling.minInstances` |

`maxInstances` and `targetCpuPercent` stay validated, carried and unhonoured (FR-015).

**Validation added** to `ServiceResources.problems`: a `minInstances` of 2 is accepted with no error —
but see the quickstart; `keep-majority` means two instances cannot both survive a partition, and the
documentation says so rather than the validator refusing it.

---

## Rendered objects

### New, per service — all owned by the `NakkaService`

| Kind | Name | Content |
|---|---|---|
| `ServiceAccount` | `<service>` | — |
| `Role` | `<service>-peers` | `pods`: `get`, `list`, `watch` |
| `RoleBinding` | `<service>-peers` | Role → ServiceAccount |

Three new `Action` cases — `EnsureServiceAccount`, `EnsureRole`, `EnsureRoleBinding` — inert data,
server-side applied by `Fabric8Executor` like every other ensure. Rendered **before** the Deployment,
though research R8 shows the other order heals itself.

### Changed: the Deployment

| What | Feature 003 | Now |
|---|---|---|
| `replicas` | `1`, a constant | `spec.autoscaling.minInstances` (0 when paused) |
| `strategy` | `Recreate` | `RollingUpdate`, `maxSurge: 1`, `maxUnavailable: 0` — at **every** count, one included (research R6) |
| `serviceAccountName` | default | `<service>` |
| ports | `http` | `http`, **`management`** (7626), `remoting` (17355) — the *name* `management` is load-bearing |
| `readinessProbe` | `tcpSocket` on the HTTP port; none if no HTTP | `httpGet /ready` on `management`, **always** |
| env | `NAKKA_HTTP_PORT` | + the five above |
| liveness probe | none | still none |

### Unchanged

The Service from feature 003 — it routes to ready pods, and "ready" now means "a cluster member",
which is exactly what it should route to. Database provisioning. The schema ConfigMap.

---

## Status

`PartiallyReady` becomes reachable for the first time since feature 001 implemented it.

`ClusterSnapshot` gains `totalReplicas` (`status.replicas`), and `LifecycleRules` gains a rule:

| Rule | Reports |
|---|---|
| `totalReplicas > updatedReplicas` — pods of the old template still exist | `UpdateInProgress` |

Without it a rollout reports `Ready` while an **old** pod is the one being counted ready — seen live
in feature 003, which `Recreate` hid rather than fixed. Bringing `RollingUpdate` back brings it back.
