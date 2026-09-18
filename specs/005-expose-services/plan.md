# Implementation Plan: Expose Services Outside the Cluster

**Branch**: `005-expose-services` | **Date**: 2026-09-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-expose-services/spec.md`

## Summary

A service is private until `nakka services expose <name>`; then it answers at
`https://<service>-<project>.<base-domain>` with a certificate the platform issued, and
`unexpose` removes only that. The control plane records exposure as one boolean beside the
descriptor (two events, two commands, two routes, two CLI verbs) and projects it onto the
`NakkaService` resource; the operator renders a Gateway API `HTTPRoute` per exposed service into
the service's namespace, attached to one installer-owned `Gateway` whose HTTPS listener carries
one wildcard certificate from cert-manager. The control plane is exposed the same way at
`api.<base-domain>`, and the CLI learns `config set ca` so it can trust the local cluster's root
without an insecure switch. The kind cluster is created from a config file that publishes the
gateway's NodePorts on the host (8080/8443 — 80/443 are routinely taken on a developer machine), the deploy script installs cert-manager and Envoy Gateway,
exports the root, and refuses a cluster made the old way.

Three decisions were the user's: exposure is a command, hostnames are platform-derived only, TLS
is in scope now; and, once the routing fork was shown to change the hostname shape, Gateway API
with one wildcard certificate over Ingress with per-host certificates. [research.md](./research.md)
records why each one shapes the rest.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21 (unchanged)

**Primary Dependencies**: no new library dependencies. fabric8 7.9.0 already bundles the Gateway
API v1 model (`HTTPRouteBuilder`, verified in the jar). New *cluster* dependencies, both single
manifests: cert-manager v1.21.2, Envoy Gateway v1.9.1 (bundles the Gateway API CRDs).

**Storage**: the control plane's journal gains two event types; the listing view two columns. No
DDL change.

**Testing**: munit; `NakkaTestKit`; the k3s suites (testcontainers). One new k3s suite,
`ExposureClusterSuite`, which additionally needs `curl` on the host and maps a NodePort out of the
k3s container. `OperatorClusterSuite`, `ControlPlaneClusterSuite`, `RenderingSuite`,
`ServiceEntitySuite`, `ControlPlaneSuite` (CLI) and `DescriptorSuite` gain cases.

**Target Platform**: Kubernetes ≥ 1.30 (Gateway API v1, `preStop.sleep`); kind ≥ 0.23 locally.

**Project Type**: multi-module platform; touches `crd`, `controlplane-api`, `controlplane`, `cli`,
`operator`, `kustomization/`. Not `runtime`, not `sdk`, not `http`.

**Performance Goals**: expose-to-answering ≤ 60s (SC-002); zero failed requests through a rolling
restart by hostname (SC-003).

**Constraints**: the operator holds RBAC only for `httproutes`; the control plane holds no
workload credential; no option anywhere disables certificate verification; the resource carries no
hostname.

**Scale/Scope**: ~25 files; two new manifests components; one new suite.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template. The governing constraints are
`CLAUDE.md`'s, and each holds:

| Principle | How this plan keeps it |
|---|---|
| Effects/actions are inert data; one executor performs them | `Action.EnsureHttpRoute`/`DeleteHttpRoute` are values; `Fabric8Executor` alone touches the cluster |
| The resource is the only thing the two halves share | it gains one boolean, `exposed`; the operator derives everything else |
| Control plane holds no workload credential | it writes a resource field; the operator renders the route |
| Cross-entity checks live in the endpoint | the hostname collision and `http: false` refusals |
| Withhold verbs rather than promise restraint | ClusterRole gets `httproutes` only; negatives proven under real tokens |
| Single copy | hostname derivation in `crd`; base domain once in the overlay, fanned out by kustomize |
| Verify on a real cluster | Envoy Gateway + cert-manager on k3s and kind are the first tasks, before Scala |

No violations; the Complexity Tracking table is empty.

## Project Structure

### Documentation (this feature)

```text
specs/005-expose-services/
├── plan.md
├── research.md          # R1–R11: routing API, hostname shape, certificates, DNS, NodePorts, tests
├── data-model.md        # Service.exposed, Hostnames, HTTPRoute, status, settings, transitions
├── quickstart.md        # Tiers 1–6, the kind walkthrough with curl --cacert
├── contracts/
│   ├── expose-api.md    # POST expose/unexpose, refusals, CLI verbs, config set ca
│   └── route-object.md  # the HTTPRoute, the Gateway, status mapping, RBAC
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
crd/src/main/scala/nakka/crd/
├── Hostnames.scala                          # NEW: of / label / controlPlane / problems
└── NakkaService.scala                       # spec.exposed; status.route

controlplane-api/src/main/scala/nakka/controlplane/api/
└── descriptors.scala                        # ServiceStatus.hostname; RouteStatus wire enum

controlplane/src/main/scala/nakka/controlplane/
├── domain/{model,events}.scala              # Service.exposed; ServiceExposed/Unexposed
├── application/ServiceEntity.scala          # expose / unexpose commands
├── application/ServiceRows.scala            # exposed, hostname columns
├── api/ServiceEndpoint.scala                # POST …/expose, …/unexpose; the three refusals
├── deploy/DeployConfig.scala                # baseDomain: Option[String]
├── deploy/ServiceProjection.scala           # exposed → spec.exposed
└── deploy/StatusIngest.scala                # route status → detail

cli/src/main/scala/nakka/cli/
├── Main.scala                               # services expose|unexpose; config set|unset ca
├── Settings.scala                           # ca
├── ControlPlaneClient.scala                 # SSLContext from ca; hostname in output
└── Output.scala                             # HOSTNAME column/row

operator/src/main/scala/nakka/operator/
├── Action.scala                             # EnsureHttpRoute, DeleteHttpRoute
├── Rendering.scala                          # httpRoute(spec); GatewayName/GatewayNamespace
├── Executor.scala                           # the two cases; route status read
├── ClusterSnapshot.scala                    # route
├── ServiceReconciler.scala                  # plan the route beside the Service
├── LifecycleRules.scala                     # status.route copy (lifecycle unchanged)
└── Settings.scala                           # baseDomain

kustomization/
├── kind.yaml                                # NEW: extraPortMappings 30080→80, 30443→443
├── deploy-local.sh                          # port-mapping guard; cert-manager; envoy gateway; export CA; new epilogue
├── components/certmanager/                  # NEW: install manifest reference
├── components/envoy-gateway/                # NEW: install manifest reference
├── components/gateway/                      # NEW: GatewayClass, EnvoyProxy, Gateway, redirect route, ReferenceGrant if needed
├── components/controlplane/
│   ├── httproute.yaml                       # NEW: api.<base>
│   ├── deployment.yaml                      # NAKKA_BASE_DOMAIN
│   └── namespace.yaml                       # managed-by label so its route may attach
├── components/operator/operator.yaml        # NAKKA_BASE_DOMAIN; httproutes RBAC
└── overlays/local/
    ├── platform-configmap.yaml              # NEW: baseDomain = 127.0.0.1.sslip.io
    ├── local-ca.yaml                        # NEW: selfsigned Issuer, root CA, ClusterIssuer, wildcard Certificate
    └── kustomization.yaml                   # replacements fanning baseDomain out

controlplane/src/test/scala/nakka/controlplane/
├── ExposureClusterSuite.scala               # NEW: Tier 4
├── ControlPlaneClusterSuite.scala           # the control plane's own route + CLI with ca
└── ControlPlaneSuite.scala                  # Tier 2 cases
operator/src/test/scala/nakka/operator/
├── RenderingSuite.scala, OperatorClusterSuite.scala
crd/src/test/scala/nakka/crd/HostnamesSuite.scala   # NEW
```

**Structure Decision**: the existing module layout absorbs the feature without a new module.
`Hostnames` goes in `crd` because it is the one place both the control plane and the operator
can see and that depends on nothing — the same reasoning that put the resource there. The
Gateway, its class, the proxy configuration and the redirect route form one kustomize component
(`components/gateway`), installer-owned and never rendered; the local CA and the base domain are
overlay-only, because a real installation supplies both.

## Design notes that tasks must respect

1. **Order of rendering**: Service → HTTPRoute → identity → Deployment. A route before its Service
   is `ResolvedRefs: False` for a moment and then heals; rendering it after keeps the status clean.
2. **Deleting a route on unexpose is an explicit action**, not an owner-reference cascade — the
   owner (the resource) survives. `Action.DeleteHttpRoute` is planned when `!exposed` and a route
   exists in the snapshot.
3. **The projector writes `exposed`, the operator derives the hostname.** No task may add a
   hostname field to the resource; the collision check reads the view, not the cluster.
4. **`expose` on a paused service is allowed** and renders a route with no ready backends; Envoy
   returns 503 until resume. That is the spec's scenario 7 and is asserted, not avoided.
5. **The k3s suites must expose 30443 before the container starts** (`withExposedPorts`), and the
   gateway component's `EnvoyProxy` must pin the NodePort — the same manifest kind uses.
6. **Nothing skips verification.** The suites read the CA from the cert-manager secret and pass it
   to `curl --cacert`; the CLI test uses `config set ca`. A reviewer greps for `-k` (quickstart Tier
   5).
7. **`preStop.sleep` from the 004 close-out stays** — it is what makes SC-003 hold by hostname too.

## Phase 0 — research

Done: [research.md](./research.md). Five items are marked "verify on a real cluster first" and
become the first tasks: Envoy Gateway on k3s/kind with fixed NodePorts; cert-manager's wildcard on
the Gateway with `curl --cacert` end to end; sslip.io from a developer machine; the HTTPRoute
`Accepted` status shape; the added startup cost in the k3s suites.

## Phase 1 — design

Done: [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).

## Post-design Constitution Check

Unchanged from above. One spec amendment was made during planning and is recorded in both places:
FR-007 now reads "valid, or refused naming the rule", because the one-label hostname the chosen
routing model requires can exceed a DNS label for the longest accepted names (research R2).

## Complexity Tracking

None.
