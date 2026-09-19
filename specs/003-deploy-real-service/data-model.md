# Data Model: Deploy a Real ankka Service

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

One new field, carried across three existing layers, plus one new rendered object. Nothing else in
the model changes.

---

## The port, across the three layers it crosses

The port follows the path every descriptor field already takes: declared by a user, validated and
*resolved* in `controlplane-api`, projected into the custom resource by the control plane, rendered
into Kubernetes objects by the operator. The operator never sees the user's shorthand, only the
resolved answer — the same split `instanceType` → `cpuMillis`/`memoryMiB` uses.

```
descriptor (user JSON)        custom resource              rendered objects
  (neither field)      ──►      port: 9000          ──►   containerPort 9000
  port: 8080           ──►      port: 8080          ──►   ANKKA_HTTP_PORT=8080
  http: false          ──►      (field absent)      ──►   (nothing rendered)
       ▲                             ▲                          ▲
  controlplane-api              crd module                  operator
  validates + defaults          carries resolved            renders three
                                 value or nothing            things from one
```

### 1. `ServiceSpec` — `controlplane-api/src/main/scala/ankka/controlplane/api/descriptors.scala`

| Field | Type | Default | Notes |
|---|---|---|---|
| `http` | `Boolean` | `true` | whether the service serves HTTP at all |
| `port` | `Int` | `9000` | ignored when `http` is `false` |
| `resolvedPort` (derived) | `Option[Int]` | — | `Option.when(http)(port)` — the only form anything downstream sees |

**Not `port: Option[Int]` with `null` for "no HTTP"** — verified unrepresentable: jsoniter, under
ankka's shared codec config, reads `null` as absent and applies the default, so `{"port": null}`
parses as 9000 (research R4). "Serves no HTTP" has to be a positive statement.

The default lives here, not in the operator, because this module is where the CLI and the control
plane both read the same rules — the reason it holds validation at all.

**Validation** (added to `ServiceSpec.problems`, reported alongside every other problem in one
response):

| Rule | Message shape |
|---|---|
| `port` must be within 1–65535 (checked even when `http` is `false`) | `service port <n> is outside the range 1-65535` |
| `env` must not declare `ANKKA_HTTP_PORT` | `env var 'ANKKA_HTTP_PORT' conflicts with the service port; declare the port instead` |

Both rules are unconditional. The `port` field is the only way to set the port (research R5).
It matches by exact variable name, checking names and never values, exactly as feature 002's
`ANKKA_DB_*` escape hatch does.

### 2. `AnkkaServiceSpec` — `crd/src/main/scala/ankka/crd/AnkkaService.scala`

| Field | Type | Default | Notes |
|---|---|---|---|
| `port` | `Option[Int]` | `None` | present → serves HTTP on it; absent → serves none. Jackson, not jsoniter: absent decodes as `None` here, as `database: Option[DatabaseStatus]` already relies on |

`None` is the default so that an `AnkkaService` written before this feature decodes as serving no HTTP and
keeps behaving exactly as it does today. The CRD types are `@JsonInclude(NON_ABSENT)`, so an absent
port genuinely does not appear in the object.

**The OpenAPI schema in `kustomization/components/crd/ankkaservice.yaml` must gain the field in the
same change.** Feature 002 shipped a Scala model whose CRD schema had not been updated, and a real
cluster rejected every write with "field not declared in schema" — a failure no fake can produce.

### 3. `Service` (the control plane's domain entity) — unchanged

The port is part of the descriptor, and `ServiceProjection` already projects descriptor fields into
the custom resource. No new entity state, no new event, no journal change: a port change arrives as a
`ServiceApplied` like any other descriptor change, and bumps the generation like any other.

---

## The rendered Service

A new Kubernetes object, rendered by the operator only when the resolved port is present.

| Field | Value |
|---|---|
| `metadata.name` | the service name (`Names.service`, equal to the Deployment's and container's) |
| `metadata.namespace` | the project's namespace |
| `metadata.labels` | the merged platform + descriptor labels, as the Deployment carries |
| `metadata.annotations` | the descriptor's annotations plus the generation annotation |
| `metadata.ownerReferences` | the `AnkkaService` — deletion cascades, no sweep (FR-009) |
| `spec.type` | `ClusterIP` |
| `spec.selector` | `Labels.identity(projectId, serviceName)` — the Deployment's selector labels, not a second computation |
| `spec.ports[0].name` | `http` |
| `spec.ports[0].port` | the resolved port |
| `spec.ports[0].targetPort` | the resolved port |
| `spec.ports[0].protocol` | `TCP` |

**Address**: `<service-name>` within the project's namespace, or
`<service-name>.<namespace>.svc.cluster.local` from outside it.

---

## Changes to the rendered Deployment

| What | Before | After |
|---|---|---|
| `containerPort` | absent | the resolved port, named `http` — omitted when headless |
| `ANKKA_HTTP_PORT` | absent | injected from the resolved port — omitted when headless |
| `readinessProbe` | absent | `tcpSocket` on the resolved port, `initialDelaySeconds: 10`, `periodSeconds: 5` — omitted when headless |
| `imagePullPolicy` | absent (Kubernetes defaults to `Always` on a `:latest` tag) | **`IfNotPresent`, always** — research R2 |
| liveness probe | absent | still absent, deliberately (research R6) |

`imagePullPolicy` is the one change that applies to *every* service, headless or not, and the one
that is required for the feature to work at all rather than being part of its design.

---

## New `Action` cases

The operator's `Action` enum gains two cases, staying inert data interpreted by `Fabric8Executor`:

| Case | Behaviour |
|---|---|
| `EnsureService(service: io.fabric8.kubernetes.api.model.Service)` | server-side apply, same field manager and force-conflicts as every other ensure |
| `RemoveService(namespace, name, ownerUid)` | read first; delete **only** a Service whose `ownerReferences` contains `ownerUid`; otherwise do nothing |

`Rendering` emits exactly one of them per reconcile: `EnsureService` when the resolved port is
present, `RemoveService` when it is not. `RemoveService` is a no-op in steady state — it reads,
finds nothing owned, and writes nothing — so "an unchanged service writes nothing" still holds.

---

## Naming

`Names` gains one function, keeping every rendered name in the one place the existing comment says
they belong:

| Function | Value |
|---|---|
| `Names.service(serviceName)` | `serviceName` — the Deployment, container, resource and Service all share it |

---

## What does not change

- `LifecycleRules` — `Ready` already derives from the Deployment's `readyReplicas`, and a readiness
  probe changes what that *counts* without changing how it is read. The honest-status machinery gets
  more honest untouched.
- `ServiceLifecycle`, the status wire type, and the CLI's rendering of it.
- Database provisioning, in any respect.
- The control plane's own Deployment and Service, which are static kustomize manifests that already
  declare a port and a readiness probe.

---

## Migration: two layers, opposite defaults

| Layer | An object written before this feature | Behaves as |
|---|---|---|
| `AnkkaService` custom resource | no `port` field | serves no HTTP — **unchanged** from today |
| Journaled `ServiceApplied` descriptor | no `http`, no `port` | **serves HTTP on 9000** — gains a probe and a Service on the next projection pass |

The descriptor default is right for an ankka service and wrong for `registry.k8s.io/pause`. No real
ankka service has ever been deployed, so nothing of value changes — but every `pause`-based
descriptor in the repository, and any still running in a developer's kind cluster, needs
`"http": false` or it will stop being `Ready` (research R12).
