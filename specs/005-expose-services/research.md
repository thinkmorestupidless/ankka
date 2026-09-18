# Research: Expose Services Outside the Cluster

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified or is an assumption to be settled by a named task.
The standard is the one features 002–004 set: read the documentation, then check it against a
real cluster, because it was wrong or silent every time.

## R1 — The routing API: Gateway API, not Ingress

**Decision**: Kubernetes Gateway API v1 — a `Gateway` the installation owns, an `HTTPRoute` per
exposed service that the operator renders. Implementation: **Envoy Gateway v1.9.1**, installed
from its single `install.yaml`, which bundles the Gateway API CRDs.

**Rationale**: ingress-nginx — the controller behind roughly half of all clusters — was retired
and archived in March 2026 with no further releases; the Kubernetes steering committee's guidance
is to migrate to Gateway API, and the Ingress API itself is GA but feature-frozen. Building a new
platform's external routing on a frozen API in late 2026 is a choice a reviewer would rightly
question. Beyond longevity, the Gateway API's ownership model is nakka's tenancy model drawn out:
an installer-owned `Gateway` in a platform namespace, tenant-owned routes in their own namespaces
that *attach* to it, attachment admitted by namespace selector, and cross-namespace backends
forbidden unless a `ReferenceGrant` says otherwise. FR-015, FR-024 and FR-025 fall out of the API
rather than being enforced by nakka.

Envoy Gateway over the alternatives because it is the Gateway API's reference-quality
implementation, CNCF-hosted, ships as one manifest (`kubectl apply --server-side -f
https://github.com/envoyproxy/gateway/releases/download/v1.9.1/install.yaml`), and — unlike
Contour or Traefik — carries no Ingress heritage the platform would then be tempted to lean on.

**Alternatives considered**: Ingress API with Contour (maintained, supports Ingress and Gateway
API; would allow two-level hostnames and per-host certificates — see R2 — but on the frozen API);
Traefik (k3s's default; no plain-manifest install, Helm only); Ingress NGINX (retired).

**Verified**: ingress-nginx retirement and the Gateway API recommendation (kubernetes.io statements,
2025-11 and 2026-01); Envoy Gateway's install command, version and CRD bundling (its install docs);
fabric8 7.9.0 bundles `kubernetes-model-gatewayapi` with typed `HTTPRoute`/`Gateway` builders (jar
inspected) — no new dependency for the operator.

**Not verified, settled by T0xx**: Envoy Gateway starts and programs a Gateway inside the k3s test
container and on kind with no LoadBalancer available (R5 covers how).

## R2 — Hostname shape: one label, `<service>-<project>`, because wildcards are one label deep

**Decision**: `https://<service>-<project>.<base-domain>`; the control plane at
`https://api.<base-domain>`.

**Rationale**: the user chose TLS now and Gateway API (R1). Two facts then fix the shape:

1. An X.509 wildcard certificate `*.example.test` matches exactly one label (RFC 6125) — it covers
   `cart-checkout.example.test`, not `cart.checkout.example.test`.
2. Gateway API listener hostnames have the same rule: a listener `*.example.test` accepts
   `bar.example.test` and rejects `baz.bar.example.test` — the spec is explicit, and the two
   implementations that got it wrong (Cilium, Traefik) filed it as a bug.

TLS in the Gateway API is a *listener* property, and the listener belongs to the installer-owned
Gateway. So there is one certificate, it is a wildcard, and every hostname must sit one label
under the base domain. The two-level form (`cart.checkout.<base>`) would need a certificate per
project on a listener per project — which the operator cannot create without owning the Gateway,
which FR-024 forbids. The one-label form costs two things the spec must own:

- **Length**: a DNS label is at most 63 characters; a service name and a project id can each be
  up to 63 (project ids are already capped at 57 so `nakka-<project>` fits a namespace). Exposure
  is **refused** when `<service>-<project>` exceeds 63, naming the limit and the length. This
  narrows spec FR-007 ("valid for every accepted name") to "valid or refused with the reason" —
  recorded as a spec deviation, not hidden.
- **Ambiguity**: names may contain hyphens, so `a-b` in `c` and `a` in `b-c` both derive
  `a-b-c`. Exposure is **refused** when another exposed service already holds the hostname — a
  cross-entity check in the endpoint against the listing view, where cross-entity checks live.
  The check is racy across two simultaneous exposes; the Gateway then accepts one route and
  rejects the other with a visible `Accepted: False` status, which `services get` surfaces. Two
  services in different projects with the *same* name never collide (`cart-checkout`,
  `cart-returns`), which is the case FR-006 names.

The control plane's `api` label can never be derived: every service hostname contains a hyphen
followed by a project id, and `api` has no hyphen. Any hyphen-free reserved label is safe the
same way.

**Alternatives considered**: Akka's `<service>-<hash>` (unique and length-safe, but a user cannot
predict it and it reads as noise; kept in reserve if the refusals prove annoying in practice);
tightening name limits to 30 characters (breaks accepted names for a rare case);
two-level names on Ingress (R1).

**Verified**: Gateway API wildcard semantics (spec text via the hostnames concept page and the two
implementation bug reports); X.509 single-label wildcard is standard.

## R3 — Certificates: cert-manager issuing one wildcard from a local CA; the operator never touches TLS

**Decision**: cert-manager v1.21.2 from its single manifest. The local overlay creates a
self-signed `Issuer` → a CA `Certificate` → a `ClusterIssuer nakka-ca` → one `Certificate` for
`*.<base>` in the gateway's namespace, referenced by the Gateway's HTTPS listener. `deploy-local.sh`
exports the CA's `ca.crt` to `~/.nakka/local-ca.crt` and prints the `nakka config set ca` and
`curl --cacert` lines. An HTTP (`:80`) listener exists only to carry an installer-owned `HTTPRoute`
that redirects everything to HTTPS (301).

**Rationale**: with the certificate on the Gateway, exposing a service is rendering one HTTPRoute
and nothing else: the operator holds no secret access, no certificate access, no issuer access
(FR-024 in its strongest form), and "is the certificate issued" is a property of the installation
checked once, not of each service. For production the installer supplies the wildcard: a DNS-01
`ClusterIssuer` with a supported provider, or a bought certificate in the named secret. That is a
documented installation cost, and honest: HTTP-01 cannot issue wildcards, so this design makes a
DNS provider a production prerequisite where the Ingress design would not have. The user chose it
with that stated.

**Verified (T006, T010)**: cert-manager's webhook ready 18s after apply on k3s; the wildcard
issued 2s after its issuer existed; the https listener `Programmed` with the secret; `curl
--cacert` against the exported root verifies (`ssl_verify=0`) on k3s and on kind, and fails
without it. **Correction**: a `ClusterIssuer` resolves `ca.secretName` in cert-manager's own
namespace, not the Certificate's (`secrets "nakka-root-ca" not found`) — the local CA uses a
namespaced `Issuer` in `nakka-gateway`. **Correction**: the redirect must name the HTTPS port
(`requestRedirect.port`), or Envoy keeps the request's port in the `Location`; the local overlay
sets it to the kind host port.

## R4 — Local DNS: sslip.io, with the base domain overridable and a hosts-file fallback documented

**Decision**: the local base domain is `127.0.0.1.sslip.io`, so `cart-checkout.127.0.0.1.sslip.io`
and `api.127.0.0.1.sslip.io` resolve to the developer's own machine through public DNS with nothing
configured. `NAKKA_BASE_DOMAIN` overrides it for the whole deployment (one kustomize replacement
fans it out — R7). The README documents the fallback: a `/etc/hosts` line and a matching override,
for a machine whose resolver blocks the service.

**Rationale**: FR-021 forbids touching system files by default. sslip.io/nip.io embed the address in
the name, so the wildcard certificate `*.127.0.0.1.sslip.io` is a perfectly ordinary single-label
wildcard, and the same scheme works unchanged for a real domain.

**Verified (T009/T010)**: `curl https://api.127.0.0.1.sslip.io:8443/` with no resolver override
reached the kind gateway from this machine — sslip.io resolves the embedded address as documented.
Still worth stating: the sandbox could not run `dig` at all, and both sslip.io's and nip.io's
*websites* presented expired certificates that day. Their DNS is independent of their websites,
but the local walkthrough depends on a third party's DNS being up, so the deploy script checks
resolution and the README keeps the hosts-file fallback.

## R5 — Getting traffic into the node: NodePort with fixed ports, mapped by kind and by testcontainers

**Decision**: Envoy Gateway's `EnvoyProxy` resource configures the proxy Service as `NodePort`
with fixed `nodePort`s 30080/30443. The kind cluster is created from `kustomization/kind.yaml`
with `extraPortMappings` 30080→**8080** and 30443→**8443** on the host — not 80/443, because a
developer's machine routinely has those taken (this one did) and the port is part of the URL
only, never of the certificate or the hostname rule. Local URLs are therefore
`https://api.127.0.0.1.sslip.io:8443`. The k3s test container exposes 30443 through
testcontainers' port mapping, so the suites reach the gateway from the *host* — the same path a
developer's `curl` takes.

**Rationale**: neither kind nor the k3s container has a load balancer, and a `LoadBalancer` Service
would sit `Pending` forever on kind. k3s does ship ServiceLB (only Traefik is disabled by the
testcontainers image — verified from its source), but relying on it would make the two
environments differ; a fixed NodePort is the same manifest in both.

**FR-022**: the deploy script checks `docker port <cluster>-control-plane 30443` and refuses with
"recreate the cluster from kustomization/kind.yaml" when the mapping is absent. `extraPortMappings`
cannot be added to a running kind cluster.

**Verified**: testcontainers' K3sContainer command is `server --disable=traefik --tls-san=...` and
exposes only 6443/8443 (source read) — extra ports must be declared before start.

**Verified (T006, T010)**: Envoy Gateway needs Kubernetes ≥ 1.32 (its `xbackends` CRD's CEL rule) — the k3s suites moved to v1.35.1. `envoyService.type: NodePort` plus a `StrategicMerge` patch on
`spec.ports` by name (`http-80`, `https-443`) pins 30080/30443 on both k3s and kind; the Gateway
was `Programmed` 4s after apply.

## R6 — Reaching the route from the tests: `curl` on the host, with `--resolve` and `--cacert`

**Decision**: the cluster suites drive exposed hostnames with the host's `curl` via
`ProcessBuilder`: `curl --cacert <exported ca> --resolve <host>:<port>:127.0.0.1
https://<host>:<port>/...`. The CA is read from the cert-manager secret through the Kubernetes
client and written to a temp file.

**Rationale**: the strongest proof is a request from outside the cluster that verifies the
certificate, which is exactly what a developer does. `--resolve` makes it independent of DNS (this
sandbox has none) while still exercising SNI and hostname verification. The JDK's `HttpClient` can
be given a trust store but not a resolver override, and the node's `wget` is BusyBox (feature 004:
no PUT, and no `--cacert`). `curl` is present on every developer machine and CI image this project
targets; its absence skips the suite with a message, the way a missing image does.

## R7 — One base domain, fanned out by kustomize; the two processes share the derivation

**Decision**: `NAKKA_BASE_DOMAIN` reaches the operator and the control plane as environment (the
same "MUST match" contract as `NAKKA_K8S_NAMESPACE_PREFIX`). In the local overlay, a ConfigMap
`nakka-platform` carries it once and kustomize `replacements` copy it into: both Deployments' env,
the wildcard `Certificate`'s `dnsNames`, the Gateway listener's `hostname`, and the control plane's
own `HTTPRoute` hostname. The hostname derivation itself is one function in `crd`
(`Hostnames.of(service, projectId, baseDomain)`), which both `controlplane` and `operator` already
depend on and which depends on nothing.

**Rationale**: the resource carries `exposed: Boolean` and *no hostname*: the operator derives the
hostname it renders, so no writer of the resource — however privileged — can point a route at a
name it does not own (FR-025 by construction, not validation). The control plane derives the same
name for display and for the collision check. `crd` is the one module both can see.

## R8 — Exposure in the control plane: two events, two commands, one boolean

**Decision**: `Service.exposed: Boolean`; events `ServiceExposed`/`ServiceUnexposed`; commands
`expose`/`unexpose` (idempotent — unexposing the unexposed is a no-op reply, per spec edge case);
`POST /services/{project}/{name}/expose` and `/unexpose`; CLI `services expose|unexpose <name>`.
`ServiceStatus` gains `hostname: Option[String]`; the listing view row gains `exposed` and the
derived hostname. `apply` never reads or writes `exposed`. Restart, pause and resume leave it
alone; `paused` while exposed keeps the route (nothing behind it — the spec's scenario 7).

**Refusals in the endpoint** (cross-entity, per the control plane's own rule): `"http": false`
descriptor → "service serves no HTTP (http: false); nothing to expose"; label over 63; hostname
held by another exposed service (from the view).

**Route status**: the operator copies the HTTPRoute's `Accepted` condition into the resource's
status as `route: accepted | rejected(<reason>) | pending`; `StatusIngest` turns it into
`ServiceStatus.detail` when not accepted, so a hostname that will not answer says why.

## R9 — What the operator renders, and its RBAC

**Decision**: for an exposed service with a resolved port, an `HTTPRoute` in the service's
namespace: owner reference to the `NakkaService`; `parentRefs` → Gateway `nakka` in namespace
`nakka-gateway`, `sectionName: https`; `hostnames: [<derived>]`; one rule, `backendRefs` →
Service `<name>` port `<resolved>`. Unexposed, or `http: false`: the route is deleted if present
(`Action.DeleteHttpRoute`). Rendered before the Deployment like the identity objects, after the
Service.

The Gateway's `allowedRoutes.namespaces.from: Selector` matches the label the operator already puts
on every project namespace (`Labels.ManagedByKey`), so a route from a namespace nakka did not
create cannot attach.

**RBAC** (operator ClusterRole): `httproutes.gateway.networking.k8s.io` get/list/watch/create/
patch/delete. Nothing on `gateways`, `gatewayclasses`, `envoyproxies`, `certificates`, `secrets`.
The service's own Role (feature 004) is unchanged: pods get/list/watch only — SC-007's negative
test extends to `httproutes`.

## R10 — The CLI trusts a named root, and nothing else changes about its transport

**Decision**: `nakka config set ca <path>` saves the path; `ControlPlaneClient` builds its
`java.net.http.HttpClient` with an `SSLContext` whose trust store holds the platform default roots
*plus* that PEM. No `insecure`/`-k` option exists anywhere (SC-010). An `https://` URL with no `ca`
set uses the system roots, which is right for a real domain.

**Rationale**: the JDK can do this with `CertificateFactory` + `KeyStore` + `TrustManagerFactory`,
no dependency; the CLI stays free of Pekko and any HTTP library (its module rule).

## R11 — The suites, and what they cost

Both k3s suites that deploy real services (`SampleDeploymentClusterSuite`, `MultiNodeClusterSuite`)
gain nothing; a new `ExposureClusterSuite` in `controlplane` installs cert-manager and Envoy
Gateway into its k3s container, applies the gateway component, and proves US1/US2/US5 from the
host through `curl`. `ControlPlaneClusterSuite` gains the control plane's own route and the CLI
run against it with `config set ca`. Budget: cert-manager ~30s, Envoy Gateway ~30s, certificate
issuance ~5s — a suite in the 5–8 minute range, like the others. `OperatorClusterSuite` gains the
RBAC negatives (T0xx) since it already mints real tokens.

## Carried to tasks as "verify on a real cluster first"

1. Envoy Gateway on k3s and kind with NodePort + fixed ports (R5) — the first task, before any
   Scala.
2. `Programmed` Gateway with cert-manager's wildcard secret; `curl --cacert` verifies (R3).
3. sslip.io resolution from a developer machine (R4).
4. HTTPRoute `Accepted` status shape from Envoy Gateway, for the operator to copy (R8).
5. The cost of two more controllers in the k3s suites (R11).
