# Data Model: Expose Services Outside the Cluster

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

## Control plane domain (`controlplane/domain`)

### `Service` — one new field

| Field | Type | Meaning |
|---|---|---|
| `exposed` | `Boolean = false` | Desired state: a route exists for this service. Independent of `descriptor`, `generation`, `paused`, `restarts`. |

**Events** (`ServiceEvent`): `ServiceExposed`, `ServiceUnexposed`. Folds: `onExposed = copy(exposed
= true)`, `onUnexposed = copy(exposed = false)`. Neither touches `generation`: exposure is not a
deployment and must not roll pods or invalidate observations.

**Commands** (`ServiceEntity`): `expose`, `unexpose`. Both refuse on a service that does not
`exist`; both are idempotent — already in the requested state persists nothing and replies the
current state (the "identical is refused" pattern the entity already uses for observations).

**Not in the entity**: the hostname (derived), the collision check and the `http: false` check
(cross-entity and descriptor-level, so in the endpoint).

### Derived: hostname

```
Hostnames.of(serviceName, projectId, baseDomain) = s"$serviceName-$projectId.$baseDomain"
Hostnames.label(serviceName, projectId)          = s"$serviceName-$projectId"      // must be <= 63
Hostnames.controlPlane(baseDomain)               = s"api.$baseDomain"
```

Lives in `crd` (`com.thinkmorestupidless.ankka.crd.Hostnames`) — pure, no dependencies — so `controlplane` (display,
collision check) and `operator` (rendering) share one copy. `Hostnames.problems(serviceName,
projectId): Vector[String]` returns the length refusal text.

## Listing view (`ServiceRows`)

| Column | Type | Added |
|---|---|---|
| `exposed` | `Boolean` | yes |
| `hostname` | `Option[String]` | yes — derived at projection time from the row's name, project and the control plane's base domain; `None` when not exposed |

The collision check in the endpoint queries this view: `exposed = true and hostname = <candidate>
and (projectId, name) != (this)`.

## Wire types (`controlplane-api`)

### `ServiceStatus` — one new field

| Field | Type | Meaning |
|---|---|---|
| `hostname` | `Option[String] = None` | `Some(https://…)` when exposed; the CLI prints `-` otherwise |

`detail` carries a route that is not accepted (`route rejected: <reason>`), the same slot the
database phrase uses.

### Endpoints

- `POST /services/{projectId}/{name}/expose` → `200 ServiceStatus` (with `hostname`), `404` unknown,
  `409` with a problem naming the cause: `http: false`, label over 63, hostname held by another
  service.
- `POST /services/{projectId}/{name}/unexpose` → `200 ServiceStatus`; idempotent.

### CLI settings (`~/.ankka/config.json`)

| Key | Type | Meaning |
|---|---|---|
| `ca` | path (string), optional | PEM file of a root to trust *in addition to* the platform's, for the control plane's URL |

`config set ca <path>`, `config unset ca`. No key, flag or environment variable ever disables
verification.

## Custom resource (`crd`)

### `AnkkaServiceSpec` — one new field

| Field | Type | Meaning |
|---|---|---|
| `exposed` | `Boolean = false` | Projected from `Service.exposed`. **No hostname**: the operator derives it (research R7). |

CRD schema gains `exposed: boolean`. `port`/`http` from feature 003 are what decide whether a
route *can* be rendered.

### `AnkkaServiceStatus` — one new field

| Field | Type | Meaning |
|---|---|---|
| `route` | `Option[RouteStatus]` | `Accepted`, `Rejected(reason)`, `Pending` — copied from the HTTPRoute's `Accepted` parent condition; `None` when not exposed |

## Rendered objects (`operator`)

### `HTTPRoute` (gateway.networking.k8s.io/v1), rendered iff `exposed && resolvedPort.isDefined`

| Path | Value |
|---|---|
| `metadata.name` | `<service>` |
| `metadata.namespace` | `<prefix>-<project>` |
| `metadata.ownerReferences` | the `AnkkaService` (cascade delete; FR-014) |
| `metadata.labels` | the service's identity labels |
| `spec.parentRefs[0]` | `{group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: https}` |
| `spec.hostnames` | `[Hostnames.of(service, project, baseDomain)]` |
| `spec.rules[0].backendRefs[0]` | `{name: <service>, port: <resolvedPort>}` — same namespace by API rule (FR-025) |

Unexposed, or `http: false`: `Action.DeleteHttpRoute(namespace, name)` if one exists.

### Actions (`Action`)

`EnsureHttpRoute(route: HTTPRoute)`, `DeleteHttpRoute(namespace: String, name: String)`. Rendered
after the Service, before the Deployment.

### `ClusterSnapshot` — one new field

`route: Option[HTTPRouteStatusView]` — the `Accepted` condition of the route's parent status for
the ankka Gateway, if the route exists. Feeds `LifecycleRules` only for the status copy; exposure
never changes `lifecycle`.

## Installation objects (`kustomization/`)

| Object | Namespace | Owner | Purpose |
|---|---|---|---|
| `GatewayClass ankka` | — | installer | `controllerName: gateway.envoyproxy.io/gatewayclass-controller` |
| `EnvoyProxy ankka` | `ankka-gateway` | installer | proxy Service `NodePort`, nodePorts 30080/30443 (R5) |
| `Gateway ankka` | `ankka-gateway` | installer | listeners `http:80` and `https:443` with `hostname: "*.<base>"`, `tls.certificateRefs: [ankka-wildcard-tls]`; `allowedRoutes.namespaces.from: Selector` on `Labels.ManagedByKey` |
| `HTTPRoute https-redirect` | `ankka-gateway` | installer | on the `http` listener: `RequestRedirect scheme https, 301` |
| `Issuer selfsigned`, `Certificate ankka-root-ca`, `ClusterIssuer ankka-ca`, `Certificate ankka-wildcard` | `ankka-gateway` / cluster | installer (local overlay) | the local CA and the one wildcard certificate (R3) |
| `HTTPRoute ankka-controlplane` | `ankka-controlplane` | installer | hostname `api.<base>` → `ankka-controlplane:9000` |
| `ConfigMap ankka-platform` | `ankka-gateway` | installer (local overlay) | `baseDomain`, fanned out by kustomize replacements (R7) |

`ankka-controlplane` and every project namespace carry `Labels.ManagedByKey` so their routes may
attach.

## Settings

| Process | Setting | Source | Default |
|---|---|---|---|
| control plane | `ankka.controlplane.deploy.base-domain` | `ANKKA_BASE_DOMAIN` | none — required when any service is exposed; `expose` refuses with "no base domain configured" if unset |
| operator | `ankka.operator.base-domain` | `ANKKA_BASE_DOMAIN` | none — a resource with `exposed: true` and no base domain is reported `route: Rejected("operator has no base domain")`, never silently unrouted |
| local overlay | `baseDomain` in `ankka-platform` | `kustomization/overlays/local` | `127.0.0.1.sslip.io` |
| local overlay | `httpsPort` in `ankka-platform` | `kustomization/overlays/local` | `8443` — the kind host port; names the redirect's target port |

## State transitions

```
apply ──────────────────────────► exposed unchanged
expose      (unexposed → exposed)   ServiceExposed;   projector → spec.exposed=true  → operator renders HTTPRoute
expose      (exposed)               no event; reply current
unexpose    (exposed → unexposed)   ServiceUnexposed; projector → spec.exposed=false → operator deletes HTTPRoute
unexpose    (unexposed)             no event; reply current
pause/resume/restart ──────────────► exposed unchanged; route stays; backends come and go with readiness
delete service / project ──────────► route removed by owner reference
```
