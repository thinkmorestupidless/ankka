# Research: Custom Hostnames for an Exposed Service

Decisions for [plan.md](plan.md), each with what in the repository, in the pinned dependencies or
in their documentation it rests on. File references are to this branch's base (`d256121b`).
"Verify first" marks a claim read from code or documentation and not yet run; the task that
touches it starts with a test or a spike that would show it false. The words are the glossary's:
a **custom hostname** is held by one service; a **proof record** proves a project controls its
name; the **gateway** serves it.

Eight things planning found that the spec did not have are amended into the spec and listed in
`plan.md`'s Complexity Tracking: the proof record's name (R3), how the proxy learns the address
(R12), the issuer's kind (R6), what the k3s suite issues with (R15), what a local platform still
needs (R16), the gateway's HTTP listener (R5), a project secret's name (R8), and the cap's home (R2).

## R1. The hostname is a listener in a `ListenerSet` the operator renders in the project's namespace

**Decision**: for each exposed service with custom hostnames the operator renders one
`ListenerSet` (`gateway.networking.k8s.io/v1`), named `<service>-hostnames`, in the project's
namespace, owned by the `AnkkaService`, with `parentRef` the installation's Gateway
(`ankka`/`ankka-gateway`) and one HTTPS listener per custom hostname: `name: <hostname>`,
`port: 443`, `hostname: <hostname>`, `tls.mode: Terminate`, `certificateRefs: [Secret <hostname>]`
(R8). The Gateway gains `spec.allowedListeners.namespaces.from: Selector` on the label
`app.kubernetes.io/managed-by: ankka`, as its `https` listener already admits routes. The
operator's ClusterRole gains `listenersets` with the route's verbs and nothing on `gateways`.

**Rationale**: the Gateway's `https` listener matches `*.<base domain>` only
(`kustomization/components/gateway/gateway.yaml` L25-39), so a route hostname under another domain
intersects no listener and is `NoMatchingListenerHostname`. The hostname needs a listener with its
own certificate, and the two places one can live are the Gateway's own `spec.listeners` or a
`ListenerSet`. The Gateway is installer-owned: `features/exposure/tenancy.feature` holds that the
operator cannot change how the gateway is reached, `operator.yaml` L114-115 grants nothing on
gateways on purpose, and `OperatorClusterSuite` proves withheld verbs are refused. A `ListenerSet`
is exactly the delegation Gateway API made for this (GEP-1713): it lives in the tenant's
namespace, its `certificateRefs` resolve in that namespace with no `ReferenceGrant`, the Gateway
opts in by selector, and a listener that conflicts with the Gateway's own is the one marked
`Conflicted` — the Gateway's listeners win (GEP-1713; Envoy Gateway v1.9.0 notes, "Gateway-owned
listeners win over ListenerSet listeners"). So a `ListenerSet` naming `api.<base>` or `*.<base>`
changes nothing about how the base domain is reached, which is the tenancy scenario this feature
adds, and the control plane refuses such a name before it is ever rendered (R2).

The versions line up: Envoy Gateway v1.9.1 (`components/envoy-gateway/kustomization.yaml` L9)
bundles Gateway API v1.6.1's CRDs including `listenersets.gateway.networking.k8s.io` at `v1`,
served and stored (read from the pinned `install.yaml`); Envoy Gateway added the kind in v1.7.0,
bumped to standard-channel CRDs in v1.8.0 and fixed its hostname-conflict and TLS Secret watch in
v1.9.0; fabric8 7.9.0 (`project/Dependencies.scala` L33) has
`io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerSet`, so the action carries a typed object
as `EnsureHttpRoute` does. The v1.9.0 `envoy-gateway-config` ConfigMap in the bundle sets no
runtime flag for it; v1.7.0's `XListenerSet` flag was for the experimental kind.

One `ListenerSet` per service, not per hostname: the set is the service's and is applied whole by
server-side apply, so a removed hostname's listener is gone with the next apply and there is no
per-hostname removal to sequence; `RemoveListenerSet` is rendered for a service with none, as
`RemoveHttpRoute` is, and a 404 on the *type* reads as absent (the route's rule,
`Fabric8Executor.routeIfAny`), so a cluster without the CRD still reconciles everything else.

**Alternatives considered**: the operator patching listeners into the Gateway — rejected: it is
the one thing the operator must not be able to do, and a certificate Secret in a project
namespace referenced from `ankka-gateway` would need a `ReferenceGrant` the operator writes into
its own namespace. One `ListenerSet` per hostname — rejected: N objects to own and remove where one
is applied whole. A second Gateway per project — rejected: a load balancer and an address per
project. A `TLSRoute` with passthrough — rejected: the service holds no certificate for the name
and the caller must still be read as the gateway.

**Verify first** (spike S1, on k3s under `-Dankka.spikes=on`): Envoy Gateway 1.9.1 programs a
`ListenerSet` listener whose `certificateRefs` Secret is in the set's namespace, with the Gateway's
`allowedListeners` set by selector and no change to `envoy-gateway-config`; an `HTTPRoute` with
`parentRefs` to both the Gateway (`sectionName: https`) and the set is `Accepted` on both parents
and serves the derived hostname on one and the custom one on the other; a listener named
`*.<base>` in a set is `Conflicted` and the base domain still answers. Also: whether the
`standard-install.yaml` 1.6.1 that `OperatorClusterSuite` applies (L91-99) carries
`listensersets`, or that suite moves to the Envoy Gateway bundle.

## R2. The rules are one pure object in `controlplane-api`, and the cap is 5

**Decision**: `CustomHostnames` in `controlplane-api` (beside `Hostnames` in spirit; `crd` cannot
hold it because the control plane's rules need the platform's own hostnames, which `crd`'s
`Hostnames` knows, and the cap, which is the control plane's): `normalise` (lowercase, a trailing
dot dropped), `problems(hostname, baseDomain)` returning the refusal in the feature's words for: a
scheme, a path or a port in it ("a custom hostname is a name alone"); a wildcard; not a DNS name
(a label empty, over 63, with a character outside `[a-z0-9-]` or starting or ending with `-`;
over 253 in all); fewer than two labels; the base domain or any name under it; one of the
platform's own names (which are under the base domain, so the same rule). `MaxPerService = 5`.
The endpoint applies them in this order, then the holder (R4), then the proof (R3), so a member
is told the first thing wrong (FR-002a).

**Rationale**: `ExposureRules` (`controlplane/.../api/ExposureRules.scala`) is the precedent — pure,
ordered, tested in words — and `controlplane-api` is where the descriptor's rules live so both
ends apply the same checks. The cap is checked in the `ServiceEntity` handler (`addHostname`),
since the count is the entity's own state and a handler check cannot lag; the endpoint's checks are
the cross-entity ones. Two labels at least: a single label (`localhost`, `cart`) is no name on the
internet, and it keeps every Certificate and Secret name (R8) distinct from the platform's, which
have no dot.

**Alternatives considered**: a quota per organization — rejected by clarification (2026-10-09).
The rules in `crd` — rejected: `crd` depends on nothing and the refusals are the control plane's
words.

## R3. The proof record is `_ankka.<hostname>` TXT `ankka-project=<project id>`, read once over JNDI

**Decision**: the control plane proves control by one DNS lookup when a hostname is added: the
`TXT` records of `_ankka.<hostname>` must include `ankka-project=<project id>`. The value is the
project's id, nothing derived and nothing secret. The lookup uses the JDK's JNDI DNS provider
(`com.sun.jndi.dns.DnsContextFactory`, module `jdk.naming.dns`), as `HttpServiceClients.srvPort`
already does for `SRV` (`modules/runtime/.../HttpServiceClients.scala` L296-313), with the
provider URL `dns://<host>:<port>` from `ANKKA_DNS_RESOLVER` when set and the system resolver
otherwise, `com.sun.jndi.dns.timeout.initial=2000`, `retries=2`. The spec said "a TXT record at the
name"; it is amended to `_ankka.<hostname>`.

**Rationale**:

- **A `CNAME` can have no sibling.** RFC 1034 §3.6.2: a name with a `CNAME` has no other data. The
  record to create for `app.example.com` is a `CNAME` to the derived hostname (FR-004), so a `TXT`
  *at* `app.example.com` cannot exist beside it. `_ankka.` is the usual answer (`_acme-challenge.`,
  `_dmarc.`); an apex (R17) takes an address record and could carry the `TXT`, but one rule is
  better than two.
- **The value needs no secret.** The record says "the owner of this zone authorises this project".
  Only someone who controls the zone can write it, which is the whole proof; a token nobody could
  guess would add nothing, since guessing it still needs the zone. A per-project random token would
  be an event in the journal and a thing to show; an HMAC would be a key to keep. The project id is
  already what `services get` shows, and `whenever the service is read` (FR-004) the record is a
  pure function of what the control plane has.
- **JNDI, not a dependency.** The lookup is one `getAttributes(name, Array("TXT"))`; the module is
  in the JDK; the `runtime` already uses it, so it is nothing new on a classpath. The endpoint
  runs on a virtual thread (`AnkkaExecutors.virtual`), so a blocking lookup is free and
  `Acl.Authenticate`'s "no I/O on the dispatcher" rule is untouched.
- **Once.** Clarification 1: the claim stands after it is recorded. A re-read on every `get` would
  turn a listing into DNS traffic and a resolver outage into an outage of `services get`.
- **A failed lookup is its own refusal.** FR-002a: `NameNotFoundException` is "no proof record";
  `CommunicationException` or a timeout is "could not look up <name>" — the member is not told to
  create a record they have. Both are 409s with the message; the lookup never throws into a 500.

**Alternatives considered**: `dnsjava` — rejected: a dependency for one record type. A `TXT` at
the hostname — rejected: the `CNAME` rule above. A per-project random token — rejected: an event
and a listing for a secret that is not one. Verifying through the authority's challenge only
(first claim holds) — rejected by clarification (2026-10-09).

**Verify first**: JNDI's DNS provider reads a `TXT` with several strings as one attribute value
per record (spike S2, offline, against a resolver the test starts — `pebble-challtestsrv`'s image
in a container, which serves DNS on 8053 and takes `POST /set-txt` on 8055, R15); the `dns://`
URL takes a port.

## R4. One hostname, one holder: the view scan, as the derived label's today

**Decision**: `ServiceRows` gains `customHostnames` (the row *is* `ServiceStatus`, so the wire
field is the column), and `ServiceEndpoint.hostnameHolder` for a custom hostname scans the rows
whose `customHostnames` is non-empty for one holding it, as it scans exposed rows for a derived
label (`ServiceEndpoint.scala` L284-289). The refusal names the holder. The race the scan admits
(two adds in one projection interval) is the one the spec's edge case names: both are recorded, the
gateway marks the later `ListenerSet`'s listener `Conflicted`, the status says `rejected:
HostnameConflict`, and a member removes one.

**Rationale**: the precedent is the derived label's (research 005 R2, the comment at L279-283), and
a cross-entity uniqueness check "lives in the endpoint, never a handler" (`control-plane.md`). The
installation-wide scan is over services with custom hostnames, which are few; a `jsonb` containment
query is not needed for correctness and is a later optimisation. The backstop is real: GEP-1713
orders `ListenerSet`s oldest first, so the earlier claim serves and the later is `Conflicted`
(R1), which is better than the derived label's backstop (a rejected route).

**Alternatives considered**: a `Hostname` entity keyed by the name, reserved first as a quota is —
rejected: a second entity and a two-step reservation for a race the gateway already resolves
visibly; worth doing when a listing lag is observed to matter.

## R5. The certificate: one cert-manager `Certificate` per hostname, from a `ClusterIssuer` the installation names

**Decision**: for each custom hostname the operator renders a `Certificate` named `<hostname>` in
the project's namespace, owned by the `AnkkaService`, `secretName: <hostname>` (R8), `dnsNames:
[<hostname>]`, `issuerRef: {name: <ANKKA_HOSTNAME_ISSUER>, kind: ClusterIssuer, group:
cert-manager.io}`, private key RSA 2048 `rotationPolicy: Always`, as `ZeroTrust.certificate`
renders every workload's (`operator/.../ZeroTrust.scala` L484-521), through the existing
`EnsureCertificate` action and the existing `certificates` grant. A new `RemoveCertificate` action
deletes one the service no longer names: the operator lists the namespace's Certificates carrying
the label `ankka.thinkmorestupidless.com/hostname` and owned by the resource (`list` is granted) and
removes those not in the spec. On a cloud installation the issuer answers ACME HTTP-01 through the
gateway (`solvers[].http01.gatewayHTTPRoute.parentRefs: [{name: ankka, namespace: ankka-gateway,
kind: Gateway}]`); the Gateway's `http` listener admits routes from the managed namespaces
(`allowedRoutes.namespaces.from: Selector`), since cert-manager writes the solver route in the
Certificate's namespace; and cert-manager's controller runs with `--enable-gateway-api`.

**Rationale**:

- **The operator already asks cert-manager for certificates and never reads one.** Every workload's
  certificates are `GenericKubernetesResource`s applied by `Fabric8Executor` (L474-481); the grant is
  `certificates: get list watch create patch delete` (`operator.yaml` L126-128). Nothing new is
  granted; the key lands in a Secret the gateway reads and the operator cannot (`secrets: create,
  patch`).
- **HTTP-01, not DNS-01.** The owner's zone is not the installation's; no credential of the
  installation can write it. HTTP-01 needs only that the name resolves to the gateway and port 80
  reaches it, which the record to create gives. The gateway solver (cert-manager 1.15+ beta,
  "Gateway API support is no longer behind a feature flag, but it must still be enabled") creates
  `cm-acme-http-solver-*` in the Certificate's namespace with `parentRefs` from the issuer,
  `hostnames` from `dnsNames`, an `Exact` match on `/.well-known/acme-challenge/<token>` to a
  temporary Service, and deletes it after issuance. It attaches to the Gateway's *own* port-80
  listener (a route with a Gateway `parentRef` attaches to the Gateway's listeners, not a set's,
  GEP-1713), whose `allowedRoutes` is `from: Same` today (`gateway.yaml` L16-21). The listener must
  therefore admit the managed namespaces, and the redirect route in `ankka-gateway` has no
  `hostnames`, so a custom hostname in the clear is redirected by it with no operator-rendered
  route; the solver's exact path is the more specific match and wins (Gateway API's precedence:
  exact over prefix across routes).
- **A `ClusterIssuer`, because a Certificate in a project namespace cannot name an `Issuer` in
  `ankka-gateway`.** The local root (`overlays/local/local-ca.yaml`) is a namespaced `Issuer` for
  the reason `kubernetes.md` records (a `ClusterIssuer`'s `ca.secretName` is read in cert-manager's
  namespace). Both directions of that trap are in play, so the local overlay moves the root CA
  Certificate and its selfsigned Issuer to the `cert-manager` namespace and makes `ankka-ca` a
  `ClusterIssuer`; the wildcard Certificate in `ankka-gateway` and every custom hostname Certificate
  name it; `deploy-local.sh` and `GatewayStack.exportCa` read `ankka-root-ca` from `cert-manager`.
  The exported root still verifies both. The cloud overlay gains a second `ClusterIssuer`,
  `letsencrypt-hostnames`, HTTP-01 only, beside `letsencrypt-production` (DNS-01 for the wildcard);
  one issuer with two solvers and a `dnsZones` selector is the alternative, rejected because the
  setting names an issuer and "the issuer for custom hostnames" should be one object to read.
- **The setting is `ANKKA_HOSTNAME_ISSUER`**, a `ClusterIssuer` name, on the operator *and* the
  control plane (so the add is refused when none is named, FR-003), from the platform ConfigMap by
  the same `replacements` that carry `ANKKA_BASE_DOMAIN`. Absent on the operator: every custom
  hostname is reported `rejected: the operator names no authority for custom hostnames
  (ANKKA_HOSTNAME_ISSUER)` — the route's wording for no base domain (`LifecycleRules` L31-48).
- **`renewBefore`/`duration` are left to the issuer**: Let's Encrypt fixes them; the local CA takes
  cert-manager's defaults (90 days, renewed at two thirds), which is what the local wildcard does.

**Alternatives considered**: the owner supplies a certificate — out of scope by the spec. DNS-01
with a delegated `_acme-challenge` `CNAME` into the installation's zone — rejected: a second
record for the owner and a zone the installation writes to. A `Certificate` the control plane
writes — rejected: the control plane holds no cluster credential for anything but the resource and
secrets.

**Verify first** (S1): cert-manager 1.21.2 with `--enable-gateway-api` creates the solver route
with the Gateway `parentRef` and Envoy Gateway serves it on port 80 for a hostname no listener
names (the `http` listener has no `hostname`, so every host matches); the challenge passes against
Pebble (R15) with the name resolving to the gateway's Service.

## R6. The status of a hostname: pending, serving or rejected, from three objects the operator reads

**Decision**: `AnkkaServiceStatus` gains `hostnames: List[HostnameStatus]` with `hostname`,
`state` (`pending` | `serving` | `rejected`) and `reason: Option[String]`, one per custom hostname
on the spec, in the spec's order. `HostnameRules.status` (pure, beside `LifecycleRules.routeStatus`)
decides from:

| Read | Where | Says |
|---|---|---|
| the Certificate's `Ready` condition | `certificates.cert-manager.io` (granted) | issued, or not |
| the newest `Challenge` whose `spec.dnsName` is the hostname | `challenges.acme.cert-manager.io`, a new `get list watch` | the authority's reason while issuing: `status.reason`, e.g. `Waiting for HTTP-01 challenge propagation: … lookup app.example.com … no such host` |
| the `ListenerSet`'s listener conditions for the hostname | `listenersets` (R1) | `Programmed`, `ResolvedRefs`, `Conflicted` with reasons |
| the route's parent status for the set | the `HTTPRoute` already read | `Accepted` on the set |

Rules, in order: no issuer named → `rejected` with that reason; a listener `Conflicted` or not
`Accepted`, or the route not `Accepted` on the set → `rejected: <reason>`; the Certificate not
`Ready` → `pending` with the Challenge's reason when there is one, else `the certificate is being
issued`; the listener not `Programmed` (the Secret not yet there) → `pending: the gateway is
attaching the hostname`; otherwise `serving`. A Certificate `Ready` with `Issuing: False` and a
failure message (a refused renewal) → `serving` with that reason beside it (FR-008's last
sentence). The operator *reads* the Challenge and never writes one; the object is cert-manager's.

**Rationale**: clarification 2 made the authority's word the only word about resolution. The
Challenge is where cert-manager keeps it (`status.reason`, `status.state`), by the name
(`spec.dnsName`), with no owner chain to walk. The Certificate's own `Ready` condition says only
`Requested`/`Pending` while a challenge runs, which is not a reason a member can act on. Reading a
CRD's status through fabric8's generic resource is where `kubernetes.md` records a parsing trap
(the k3s suites moved to `kubectl -o jsonpath`); the operator's reads use the typed
`GenericKubernetesResource` map access that `observeRoute` does for conditions, with a test on a
Challenge's JSON as cert-manager 1.21 writes it.

**Alternatives considered**: the operator resolving the name itself — rejected by clarification.
Reading the `Order` — rejected: its `status.reason` is a sentence about the order, the Challenge's
names the check that failed.

**Verify first**: a Challenge's `status.reason` text under cert-manager 1.21.2 when the self-check
fails to resolve, and when Pebble returns an error (S1 records both).

## R7. The control plane records the hostnames on the service entity, and a report per hostname

**Decision**: `ServiceEvent` gains `CustomHostnameAdded(hostname, actor, at)` and
`CustomHostnameRemoved(hostname, actor, at, byAdministrator: Boolean = false)`; `Service` gains
`customHostnames: Vector[String] = Vector.empty` and, from observation, `hostnameReports:
Vector[HostnameReport] = Vector.empty`; `ServiceObserved` gains `hostnames: Vector[HostnameReport]
= Vector.empty`. The history kinds are `hostname added`, `hostname removed` and `hostname taken
away` (`Service.remember`). `onDeleted` clears `customHostnames` as it clears `exposed`
("a re-applied name starts private again", `model.scala` L697-710); `onUnexposed` leaves them. The
handler wire names are `add-hostname`, `remove-hostname` and `take-hostname-away`, separate names
for a rolling deploy's sake, with the cap (R2) and "not exposed" checked in `add-hostname` (the
entity knows both) and the cross-entity holder and the proof in the endpoint (R3, R4).

`StatusIngest` folds the resource's `hostnames` into the observation beside `route`; the one
`ServiceObserved` guard (`Service.onObserved`, generation and identity) applies unchanged, and
`sameReport` on the resource compares the list so an unchanged report is not written.

**Rationale**: `ServiceExposed`/`ServiceUnexposed` (`events.scala` L287-288) are the shape: events
beside exposure, defaulted attribution, no generation bump, pinned by `EventCompatibilitySuite`.
The hostnames are desired state the entity owns; the reports are what the operator said, folded
like the route's. Every new field defaults so a pre-feature journal decodes.

**Alternatives considered**: hostnames in the descriptor — rejected by the spec (a command, never a
descriptor). A separate entity per hostname — R4.

## R8. The Certificate and its Secret are named by the hostname, and a project secret may not contain a dot

**Decision**: both are named `<hostname>` (lowercase, every label `[a-z0-9-]`, dots between: a valid
DNS subdomain name for a Kubernetes object, at most 253 characters, which the rules already hold).
`ProjectSecrets.problems` refuses a name containing a dot, `ServiceSpec.isPlatformSecret` treats a
dotted name as the platform's, and `ReservedSecretNamesSuite` holds the form.

**Rationale**: one name, unique across the installation by R4, readable in `kubectl get
certificates`, no hash and no truncation. Every platform Secret in a project namespace is
`<service>-<suffix>` with no dot (`Names.scala`), and a service name has no dot, so "contains a
dot" separates the two forms completely; `secrets.md` says a new platform Secret form needs the rule
and the suite. A project secret named with a dot is possible today (Kubernetes allows it) and
becomes refused at its next `set`; existing ones are not touched (an operational consequence in
`plan.md`).

**Alternatives considered**: `<service>-host-<n>` — rejected: a number that changes as hostnames
are removed. `hostname-<sha>` — rejected: unreadable, and still needs a reserved form.

## R9. The route carries every hostname and two parents; nothing else about routing changes

**Decision**: `Rendering.httpRoute` renders `hostnames: [derived, custom…]` and `parentRefs:
[Gateway ankka/ankka-gateway sectionName https, ListenerSet <service>-hostnames]` — the second only
when the spec has custom hostnames. The rules (gRPC first with its content-type match and `0s`
timeout, then HTTP), the backend and the `BackendTLSPolicy` (`ZeroTrust.backendTlsPolicy`, keyed
on the in-cluster name, L357-392) are untouched. `Fabric8Executor.observeRoute` reads the Gateway
parent for `route` as today and, new, the set's parent for R6.

**Rationale**: Gateway API attaches a route to each parent for the hostnames that intersect that
parent's listeners and ignores the rest, so one route serves both; the gRPC rule, the redirect and
readiness (the backend is the Service, so only a ready instance) are the route's and so are the
hostname's. A service with no custom hostnames renders the same route object as before, which is
what `RenderingUnchangedSuite` requires (R13).

**Verify first** (S1): Envoy Gateway accepts the two-parent route and reports both parents in
`status.parents` (it reports per `parentRef`).

## R10. The operator's settings and grants

**Decision**: `Settings` gains `hostnameIssuer: Option[String]` from `ANKKA_HOSTNAME_ISSUER`. The
ClusterRole gains `listenersets` (`gateway.networking.k8s.io`: get list watch create patch delete)
and `challenges` (`acme.cert-manager.io`: get list watch). Nothing on gateways, secrets or
clusterissuers changes. `OperatorClusterSuite` test 20 gains: a token for the operator's
ServiceAccount is refused a `patch` on the Gateway and a `create` of a `ClusterIssuer`, and is
granted a `ListenerSet` in a managed namespace.

**Rationale**: the operator's grant is the tenancy proof; each new verb is named and the withheld
ones proved withheld on a real API server, since offline tests cannot see RBAC.

## R11. The control plane's settings, routes and what a member reads

**Decision**: config `ankka.controlplane.hostnames.issuer` (`ANKKA_HOSTNAME_ISSUER`),
`.resolver` (`ANKKA_DNS_RESOLVER`, `host:port`, optional) and `.gateway-address`
(`ANKKA_GATEWAY_ADDRESS`, optional, the address an apex record points at, R17), read into
`DeployConfig`. Routes: `PUT /services/{projectId}/{name}/hostnames/{hostname}` (add, 204 or a
409 with the refusal), `DELETE /services/{projectId}/{name}/hostnames/{hostname}` (remove; by a
platform administrator who is not a member it records the take-away). `ServiceStatus` gains
`customHostnames: Vector[CustomHostname(hostname, state, reason, record)]` and `proofRecord:
Option[DnsRecord]` with `DnsRecord(name, kind, value)`; the record to create per hostname is a
`CNAME` to the derived hostname, or an `A` to the gateway's address for an apex (R17). The CLI gains
`ankka services hostnames add|remove <service> <hostname>` and shows hostnames in `services get`
(a block per hostname: state, reason, the record) and in the listing's `HOSTNAME` column after the
derived one; the MCP tools gain `add_hostname` and `remove_hostname`. The console's service page
gains a hostnames section (list with state and reason, the records, an add form, a remove control),
and `fake-control-plane.ts` the two routes and the refusals.

**Rationale**: expose/unexpose are the precedent for the route shape and the console parity rule
(`console/e2e/parity.ts`) requires every route through the console. One `DELETE` for both the
member's remove and the administrator's take-away: `authz.project` already admits an administrator,
and the event's `byAdministrator` is "the actor is not a member of the organization", which the
endpoint knows; a second route would be the same code behind a second name.

## R12. The proxy takes the address from the gateway's routing, and is given no list

**Decision**: for a request whose sender is the gateway (`Sender.Internet(None)` in
`TlsTransport`, the certificate's `ankka://gateway` URI), `Headers.inbound` states as the address
the request's `Host`/`:authority` — host and port parsed, the port dropped when it is the public
port — and the public authority only when the request carries none. `X-Forwarded-*` and `Forwarded`
from the internet are dropped as today. The operator gives the proxy nothing new;
`ANKKA_PROXY_PUBLIC_AUTHORITY` stays as the fallback. FR-010 is amended.

**Rationale**: the spec's own sentence decides it — "the gateway routes a hostname only to the
service that holds it, so the name a request arrived at is the gateway's word". Envoy selects the
filter chain by SNI and the virtual host by `Host`, and each listener's route configuration holds
only the routes attached to it, so a request reaches this service's proxy only with a `Host` the
operator rendered on this service's route: the derived hostname or one of its custom hostnames.
A `Host` of another service's name is a 404 at the gateway, never a request here. The list the
spec asked the operator to give the proxy would say the same thing a second time, and giving it
costs what the spec forbids: an environment variable rolls the pod on every add and remove (US4:
"no instance restarts"), and a mounted ConfigMap is a new volume on the web pod template, which
rolls every web-hosted service once on upgrade — a repin that changes an object, which
`kubernetes.md` says is never accepted (the proxy container mounts no project ConfigMap today,
`Rendering.scala` L1473-1476). The forgery the scenarios refuse is in the forwarded headers, which
are dropped regardless; `HeadersSuite` L54-69 changes in one assertion: a `Host` from the gateway
is the address, a forged `X-Forwarded-Host` still is not. A request from a mounting proxy is
unchanged (its stated authority wins).

**Alternatives considered**: the list in the project ConfigMap, written by `ProjectReconciler` from
every service's hostnames — rejected: the web pod does not mount it, and adding the mount rolls
pods on upgrade. A header the gateway sets per hostname on a second route — rejected: the same
information from the same source.

## R13. Nothing a service without custom hostnames renders changes

**Decision**: for a service with no custom hostnames the rendered objects are byte-for-byte
yesterday's; the actions gain `RemoveListenerSet` (read-first, 404 on the type is absent) and
`PruneHostnameCertificates` (list by label, delete the unnamed) lines. `RenderingUnchangedSuite`'s
fixtures are repinned for those two lines and no object, as feature 034 repinned for
`RemoveHttpRoute`.

**Rationale**: the pod template is untouched (R12), the route is the same object (R9), the policy is
untouched. The removal actions must be rendered for every exposed service, or a hostname removed in
the same apply that unexposed the service would leave its listener and certificate behind.

## R14. The installation: the gateway, the issuers, the settings

**Decision**: `components/gateway/gateway.yaml` gains `allowedListeners` (selector, managed-by) and
the `http` listener's `allowedRoutes` becomes the same selector; `components/certmanager` gains a
patch adding `--enable-gateway-api` to the controller's args; `platform-configmap.yaml` in both
overlays gains `hostnameIssuer` (local: `ankka-ca`; cloud: `letsencrypt-hostnames`) and the cloud's
`gatewayAddress # SET`; the `replacements` carry them into both Deployments. The local overlay's
`local-ca.yaml` moves the root to `cert-manager` (R5); the cloud overlay's `acme-issuer.yaml`
gains the HTTP-01 `ClusterIssuer`. `deploy-local.sh` reads the root from its new namespace;
`GatewayStack` applies the cert-manager patch and the moved root; `RemoteOverlaySuite` asserts both
overlays render the setting, the Gateway's `allowedListeners`, and that the cloud's hostnames
issuer has an HTTP-01 solver naming the Gateway and the local's names a `ClusterIssuer` whose
secret is in `cert-manager`.

**Rationale**: `kubectl apply -k` stays the whole deploy; every value is in the ConfigMap once; the
k3s suites install what the overlay installs. `gateway/kustomization.yaml`'s comment ("exposing a
service touches no certificate at all") is rewritten: exposing still does not; a custom hostname
does, in the project's namespace.

## R15. The k3s suite issues from Pebble, and the local authority is proved in `OperatorClusterSuite`

**Decision**: `CustomHostnamesClusterFeatures` (a `GherkinSuite` over
`features/exposure/custom-hostnames.feature`, modelled on `WebHostingClusterFeatures`) installs
`AcmeStack`: in namespace `acme-test`, `pebble-challtestsrv` (image `ghcr.io/letsencrypt/pebble-challtestsrv:v2.10.1`,
DNS on 8053, management on 8055, default A answer the gateway's Envoy Service's cluster IP) and
Pebble (`ghcr.io/letsencrypt/pebble:v2.10.1`, config `httpPort: 80`, `-dnsserver
<challtestsrv>:8053`, `PEBBLE_VA_NOSLEEP=1`), a `ClusterIssuer pebble` (`server:
https://pebble.acme-test.svc:14000/dir`, `skipTLSVerify: true`, HTTP-01 through the Gateway), and a
patch on cert-manager's controller: `--enable-gateway-api
--acme-http01-solver-nameservers=<challtestsrv ip>:8053`. The operator runs with
`ANKKA_HOSTNAME_ISSUER=pebble`; the control plane with the same and
`ANKKA_DNS_RESOLVER=<challtestsrv ip>:8053`. The steps: "the name X carries the proof record of the
project P" is `POST /set-txt {"host":"_ankka.X.","value":"ankka-project=P"}`; "the name X resolves
to the installation" is `POST /add-a` with the Envoy Service's cluster IP; "resolves to nothing" is
`/clear-a` and the default IPv4 cleared. Requests from the host are `curl --resolve
X:<mapped port>:127.0.0.1 --cacert <Pebble's root>`, the root fetched once from Pebble's
management `GET /roots/0` through a pod. The whole feature file runs against Pebble; the local
authority's path — a `Certificate` for a custom hostname issued by the moved `ankka-ca`
`ClusterIssuer` and served — is one case in `OperatorClusterSuite`, which already installs the
local root. SC-005 is amended to say so.

**Rationale**: SC-005 said the local authority for all but one scenario, but a suite with two
issuers switches the operator's setting mid-run and restarts it, and the local authority proves
nothing about the challenge, the solver route, the port-80 listener or the reason a member reads —
the things most likely to be wrong. Pebble is the ACME reference test server, built for exactly
this, with a resolver of its own so a name the test controls resolves inside the cluster.
cert-manager's self-check uses its own resolver, hence the nameservers flag; the control plane's
proof lookup uses the same server. `-Dankka.cluster.tests` gates it, `.github/cluster-suites.py`
lists it unasked, and under CI it runs on its own runner.

**Alternatives considered**: `PEBBLE_VA_ALWAYS_VALID=1` — rejected: it is the check the suite
exists to run. CoreDNS's `coredns-custom` ConfigMap for the records — rejected: a zone file
rewritten per scenario versus one `POST`. Two issuers in one suite — above.

**Verify first** (S1): Pebble's image honours `-config` and `-dnsserver` as command args in
v2.10.1 (an issue reports an image that ignored them); `GET /roots/0` on 15000 returns the PEM;
cert-manager's `skipTLSVerify` is honoured for the ACME server; the challenge round trip inside
k3s completes under a minute.

## R16. A local platform needs the proof record in real DNS, and a hosts entry to reach it

**Decision**: nothing in the local overlay waives the proof. A developer who wants `app.example.com`
on their kind cluster creates `_ankka.app.example.com TXT "ankka-project=<project>"` at their
provider — they own the domain or they do not — and a hosts entry `127.0.0.1 app.example.com`;
the control plane's lookup uses the machine's resolver, which reaches the real record. The local
authority then issues without a challenge and the hostname serves at `https://app.example.com:8443`.
FR-014's "with a hosts file" is amended to say the record too. `docs/deploy/custom-hostnames.md`
walks it.

**Rationale**: one rule everywhere; a switch that turns the proof off is a switch an installation
could set. A developer without a domain has the derived hostname, which is what a local platform is
for.

## R17. An apex takes an address record, where the installation knows its gateway's address

**Decision**: `ANKKA_GATEWAY_ADDRESS` (R11), optional. For a hostname with exactly two labels the
record to create is `A <address>` when the installation knows one, else the control plane says the
apex cannot be a `CNAME` and the installation has published no address, and leaves the hostname
pending until it resolves (the authority's word, R6). Non-apex hostnames always get the `CNAME`.

**Rationale**: a `CNAME` cannot sit at an apex (RFC 1034 §3.6.2 again); a cloud installation's
load balancer has an address the operator cannot learn from inside the cluster reliably (a
hostname, for some providers) and a local one has none. A setting, set once by whoever owns the
load balancer, is the honest shape.

## R18. Documentation

**Decision**: a new page `docs/deploy/custom-hostnames.md` (adding, the two records, where a
hostname stands, removing, the cap, the administrator's take-away, what the platform does not
do); `docs/deploy/expose.md` links it; `docs/reference/limitations.md` replaces "No custom
hostnames" with what remains out of scope (no DNS management, no supplied certificate, no
hostname for the platform's own services, no shared hostname by path); `docs/platform/install-cloud.md`
gains the issuer, the gateway's `allowedListeners`, cert-manager's flag and `ANKKA_GATEWAY_ADDRESS`;
`docs/reference/control-plane-api.md` gains the two routes' hand-written sections, the status
fields and the history kinds; `docs/reference/cli.md` by `just docs-reference`;
`docs/reference/web-hosting.md` says the address is the one the gateway routed; `mkdocs.yml` nav and
the `ankka-deploy` and `ankka-platform` skills list the page.

## R19. Where each check could pass while false, and what sharpens it

| Check | Could pass while false when | Sharpened by |
|---|---|---|
| a hostname answers | the derived hostname's certificate is served under SNI of the custom one | asserting the certificate's `subject`/SAN is the custom hostname (`curl -w '%{certs}'` or openssl `s_client`), from Pebble's root, never `-k` |
| the proof is required | the resolver answers nothing and the code treats "no answer" as "present" | the refused-until-proved scenario runs with the record absent *and* a case where the resolver is unreachable asserts the "could not look up" refusal |
| removal stops answering | Envoy keeps the listener a while | `within 30 seconds` on the gateway's 404/handshake failure, not on the resource's status |
| no instance restarts | pods were replaced and the names look alike | the pod UIDs before and after |
| the tenancy scenario | a set naming `*.<base>` is accepted but never programmed | asserting `Conflicted: True` on the listener *and* that the base domain still answers from the wildcard certificate |
| the operator cannot change the gateway | the suite uses admin credentials | test 20's minted token, a `patch` on the Gateway refused |
| the status is the authority's reason | the suite asserts `pending` only | asserting the reason's text contains the name it could not reach |

## Spikes

- **S1** (`-Dankka.spikes=on`, k3s): R1, R5, R6, R9 and R15's verify-first items, one throwaway
  suite: install the stack, apply by hand a `ListenerSet`, a Certificate from Pebble, a two-parent
  route; record the Challenge's reason texts and the listener's conditions.
- **S2** (offline): R3's JNDI `TXT` read against `pebble-challtestsrv` in a container.

## Verified during implementation

- **S2 / R3 (2026-10-09)**: JNDI's DNS provider reads `TXT` of `_ankka.app.example.com` from
  `pebble-challtestsrv` 2.10.1 through `dns://127.0.0.1:<port>` as the record's one string; a name
  with no record is `NameNotFoundException` (`NoRecord`); a resolver nothing answers at is a
  `NamingException` (`Unreachable`) within the timeouts. JNDI asks over UDP, so a containerised
  resolver needs its UDP port bound explicitly. `jdk.naming.dns` needed no `--add-modules`. Held by
  `ProofLookupSuite`, which replaces the spike. The images' tags have no `v`:
  `ghcr.io/letsencrypt/pebble:2.10.1`, `ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1`; both pull
  on this machine.
- **S1 / R1, R5, R9, R15 (2026-10-09, `ListenerSetSpike`, k3s v1.35.1, Envoy Gateway v1.9.1,
  cert-manager v1.21.2)**:
  - The shipped `gateway.yaml` with `allowedListeners` by selector admits a `ListenerSet` from a
    managed namespace (`attachedListenerSets: 1`); its listener is `Accepted`, `ResolvedRefs` and
    `Programmed` with a `certificateRefs` Secret in the set's namespace and no `ReferenceGrant`;
    `envoy-gateway-config` needed no change.
  - One `HTTPRoute` with two `parentRefs` (the Gateway's `https` section and the set) is `Accepted`
    on both and serves the derived hostname with the wildcard and the custom one with its own
    certificate. The set's parent in `status.parents` is told apart by `kind: ListenerSet`.
  - The local root moved to `cert-manager` with `ankka-ca` as a `ClusterIssuer` issues for a
    hostname in a project namespace; the exported root verifies it.
  - **R1 corrected.** A set's listener for `*.<base>` is `Conflicted: HostnameConflict` and not
    `Accepted`, as assumed. A set's listener for `api.<base>` is **accepted and programmed**: it is
    more specific than the Gateway's `*.<base>`, so it does not conflict, and Envoy would select it
    for that name. The Gateway's listeners win only an equal hostname. So the operator refuses to
    render any custom hostname under the base domain whatever the resource says
    (`HostnameRendering.refusal`), and reports it rejected; the control plane's refusal stays the
    first line.
  - cert-manager with `--enable-gateway-api` creates `cm-acme-http-solver-*` in the Certificate's
    namespace with the issuer's `parentRefs` (`sectionName: http`) and `hostnames` from `dnsNames`;
    the Gateway's `http` listener admitting managed namespaces is what lets it attach. Pebble 2.10.1
    honours `-config` and `-dnsserver` as container args, and issued within a minute;
    `GET /roots/0` on 15000 returned the root that verified the served certificate.
  - A name that does not resolve leaves the `Certificate` `Ready: False / DoesNotExist` and
    `Issuing: True`, and the `Challenge` `pending` with `status.reason` "Waiting for HTTP-01
    challenge propagation: failed to perform self check GET request 'http://<name>/…': … dial tcp:
    lookup <name> on …: lame referral" (`lame referral` is challtestsrv answering with no record; a
    public resolver says `no such host`). The reason names the host, which is what the scenario
    asserts. Go's resolver names the cluster DNS server in that message even when cert-manager's
    `--acme-http01-solver-nameservers` sent the query elsewhere.
  - A name resolving to an address nothing answers on 80 did not produce a `Challenge` within 75
    seconds in the spike; its reason text is not recorded. The scenario "a certificate the authority
    refuses" binds to it with a longer wait.
- **Implementation (2026-10-09)**:
  - `isApex` lives in `controlplane-api`'s `CustomHostnames`, not `crd`'s `Hostnames`: only the
    control plane needs it, and `controlplane-api` cannot see `crd`.
  - The certificates are marked `ankka.thinkmorestupidless.com/hostname-certificate: "true"` rather
    than labelled with the hostname, which can be longer than a label value's 63 characters.
  - cert-manager leaves a Certificate's Secret behind when the Certificate is deleted, unless
    `--enable-certificate-owner-ref`: without it a removed hostname's key outlives it (FR-012) and a
    re-added name is served at once from the stale Secret. The component's patch sets it.
  - `HostnameRules` checks the certificate before the listener's acceptance: a listener whose
    certificate does not exist yet may report it, and that is the expected early state.
  - The HTTP module had no route with three path parameters; `put` and `delete` gained one.
  - The console's operations live in the inspector (`shell.spec.ts` holds every page to that), so
    adding and removing a hostname is there, removal as a `<select>`; the body lists the hostnames.
  - The k3s suite's control plane, in the test JVM, cannot reach a resolver inside the node over
    UDP, so it reads proof records through a lookup the steps set; `ProofLookupSuite` is the real
    DNS read against the same image. The no-issuer scenario runs in `CliEndToEndSuite`, whose
    control plane names none.
  - An ACME authority reuses a valid authorization for the same account and name (Let's Encrypt for
    30 days; Pebble for half its orders unless `PEBBLE_AUTHZREUSE=0`), so a name validated once is
    issued again without being checked. `AcmeStack` turns reuse off so each scenario's challenge is
    real; the user page says a re-added hostname can be served before its record is restored.

