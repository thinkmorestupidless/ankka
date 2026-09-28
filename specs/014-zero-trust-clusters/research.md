# Research: Zero Trust in the Service Clusters

**Feature**: `014-zero-trust-clusters` | **Date**: 2026-09-28

Every decision below was checked against the jars this build resolves (`~/Library/Caches/Coursier`,
Pekko 1.7.0, Pekko HTTP 1.4.0, Pekko Management 1.2.1, pekko-persistence-r2dbc 1.2.0,
r2dbc-postgresql 1.1.2) with `javap` and the jars' own `reference.conf`, or against the current
upstream documentation of the component named. Where a fact could only be established by running
it, the research item says so and the plan's first task in that area is a spike that proves it.

## R1. Cluster remoting: Pekko's rotating-keys TLS engine, as shipped

**Decision**: The Kubernetes overlay sets `pekko.remote.artery.transport = tls-tcp` and
`pekko.remote.artery.ssl.ssl-engine-provider =
org.apache.pekko.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider`, with
`rotating-keys-engine.secret-mount-point = /var/run/secrets/ankka/cluster`. Nothing is written in
ankka for the remoting transport itself.

**Rationale**: `pekko-remote_3-1.7.0.jar` carries `RotatingKeysSSLEngineProvider`,
`PemManagersProvider`, `PeerSubjectVerifier` and `X509Readers` — the Akka 2.6 design the guide
links to. Its `reference.conf` states the contract exactly:

- it reads `tls.key`, `tls.crt` and `ca.crt` from the mount point (cert-manager's Secret keys);
- mutual authentication is on and, after the handshake, each side verifies the peer's certificate
  shares at least one subject name (CN or SAN) with its own — `X509Readers.getAllSubjectNames`
  reads `getSubjectX500Principal` and `getSubjectAlternativeNames`. Every pod of a service holds
  the same certificate, so the names trivially match, and a certificate for another service
  never does;
- the SSL context is cached `ssl-context-cache-ttl = 5m`, so a renewed certificate is used for new
  connections within five minutes and the previous one must stay valid at least that long;
- constraints: the private key must be RSA in PKCS#1 or unencrypted PKCS#8 PEM; the certificate
  must be issued by a **root** CA, not an intermediate; all files PEM.

Those constraints decide R5's certificate shape.

**Alternatives considered**: `ConfigSSLEngineProvider` with JKS stores — needs a password and a
restart to rotate, which is exactly what the guide says not to do. A mesh sidecar — rejected in
the spec.

## R2. Management port: HTTPS with a required client certificate, readiness on a separate port

**Decision**: In the Kubernetes overlay `ClusterFormation` starts Pekko Management with
`ManagementRouteProviderSettings.withHttpsConnectionContext` built from the cluster certificate
(R12's rotating context) with `TLSClientAuth.Need`, so every management route — bootstrap's
`/bootstrap/seed-nodes`, `/ankka/version`, `/ankka/metrics`, `/cluster/*` — needs the service's own
certificate. Readiness moves to a third container port, **7627, named `probe`**, a plain HTTP
listener that serves only `GET /ready` by calling pekko-management's `HealthChecks(system).ready()`
— the same checks (cluster membership plus every `RuntimeExtension` opinion) that answer on the
management port today. The Deployment's readiness probe targets the port named `probe`.

**Rationale**: The kubelet cannot present a client certificate, and TLS client authentication is
per listener, not per route — so a management port that requires a certificate (FR-010) cannot
also serve the probe. pekko-management's route-level `withAuth` is HTTP basic credentials, not
certificate identity. A separate port is the honest shape: it discloses only ready/not ready, and
it keeps every other management route behind mutual TLS with no ankka code in the TLS path.
`ManagementRouteProviderSettings.httpsConnectionContext` and `withHttpsConnectionContext` exist in
`pekko-management_3-1.2.1` (verified with `javap`).

**Bootstrap probing over mutual TLS (FR-009)**: `HttpContactPointBootstrap.clientSslContext` uses
`contact-point.http-client.ca-path` when set and otherwise falls back to
`Http(system).defaultClientHttpsContext` (verified in bytecode). ankka leaves `ca-path` empty and
calls `Http().setDefaultClientHttpsContext` before `ClusterBootstrap.start()` with a context that
presents the cluster certificate and trusts the cluster CA — so each probe is mutual TLS and a
contact point that is not this service's peer fails the handshake. `PekkoManagement.start` calls
`ClusterBootstrap.setSelfContactPoint(uri)` with the scheme it bound, and the coordinator builds
contact-point URIs from that self contact point; **the spike in T1 proves the probe scheme is
`https` when management is HTTPS**, since it is inferred from bytecode rather than documented.

**Answered at implementation (T002, `TlsClusterFormationSuite`)**:

- The probe scheme *is* derived from the self contact point: bootstrap logs `scheme [https]` and
  probes `https://…/bootstrap/seed-nodes`. With `ca-path` empty it calls `Http().singleRequest` with
  the default client context, so `setDefaultClientHttpsContext` before `ClusterBootstrap.start()` is
  the whole mechanism. No override was needed.
- Pekko's TLS stage re-enables endpoint identification on a client engine after the engine is built,
  so a cluster peer reached by IP failed `No subject alternative names matching IP address`. Cluster
  contexts therefore use `RotatingTls.Peers.SameIdentity`, whose trust manager validates the chain
  with the two-argument check (no hostname) and then requires the peer to carry this process's own
  `ankka://` URI — the rule Pekko's remoting applies to itself. A node of another service, same
  authority, is refused in the handshake.
- Pekko's `reference.conf` arrives already resolved, so overriding only
  `rotating-keys-engine.secret-mount-point` leaves `key-file`, `cert-file` and `ca-cert-file` at
  `/var/run/secrets/pekko-tls/…`. The overlay names all three files.
- Bootstrap counts contact points per host; on one machine two nodes are told apart by host name
  (`localhost` and `127.0.0.1`). In a cluster every pod has its own IP and this does not arise.

**Alternatives considered**: `TLSClientAuth.Want` on the management port with route-level checks
only for ankka's own routes — leaves `/bootstrap/seed-nodes` and `/cluster/members` reachable
without identity; rejected because FR-010 says every route but readiness. A readiness `exec` probe
— the image owes the platform no shell.

## R3. HTTP server: Pekko HTTP's engine-per-connection context, peer certificate via the session header

**Decision**: `HttpServer` binds with `ConnectionContext.httpsServer(() => SSLEngine)` where the
engine comes from R12's rotating context with `setNeedClientAuth(true)`; the overlay sets
`pekko.http.server.parsing.tls-session-info-header = on`, and the router reads the peer certificate
from the synthetic `Tls-Session-Info` header (`peerCertificates`, verified in
`pekko-http-core_3-1.4.0`) and maps its `ankka://` URI SAN to a `Caller` (R6). No TLS in local
mode: `ankka.http.tls.enabled` is `off` in `reference.conf` and `on` only in the Kubernetes
overlay, with the file paths beside it.

**Rationale**: Pekko HTTP 1.4 has no `requireClientCertificateIdentity` and no JWT directive
(`javap` over `pekko-http_3` finds no TLS or JWT directive classes; those arrived in Akka HTTP
10.6+). The peer certificate is available, which is all ankka needs — the identity mapping is
ankka's anyway, because the certificate's naming is ankka's. The `() => SSLEngine` overload is
what makes rotation possible without rebinding: every new connection asks for an engine, and the
provider hands out one from the current context.

**Alternatives considered**: Terminating TLS in a sidecar proxy — the spec rejects a mesh. A
`Tls-Session-Info` lookup only in `Acl.Authenticate` — would make identity opt-in per endpoint;
the caller is a property of every request.

## R4. Database: r2dbc-postgresql takes a client certificate; the plugin's config does not, so ankka supplies the factory

**Decision**: The runtime builds the r2dbc `ConnectionFactory` itself when `ANKKA_DB_SSL_MODE`
is set, through pekko-persistence-r2dbc's `connection-factory-options-customizer` hook, and
supplies the pool to the plugin through the same `ConnectionFactoryProvider` path
(`connectionFactoryFor(location, config)`), rather than forking the plugin. The customizer sets
r2dbc-postgresql's `SSL_MODE = VERIFY_FULL`, `SSL_ROOT_CERT`, `SSL_CERT`, `SSL_KEY` options, and a
`SSL_CONTEXT_BUILDER_CUSTOMIZER` that reads the key and certificate files at the moment a
connection is created, so a connection opened after a renewal presents the renewed certificate
without a restart (FR-024a). Password absent → no `PASSWORD` option is set.

**Rationale**: `pekko-persistence-r2dbc_3-1.2.0`'s `ConnectionFactorySettings` exposes
`sslEnabled`, `sslMode`, `sslRootCert` and `connectionFactoryOptionsCustomizer` (a class name, read
from `connection-factory.connection-factory-options-customizer`) and nothing for a client
certificate. `io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider` exposes `SSL_CERT`,
`SSL_KEY`, `SSL_PASSWORD`, `SSL_ROOT_CERT`, `SSL_MODE`, `SSL_HOSTNAME_VERIFIER` and
`SSL_CONTEXT_BUILDER_CUSTOMIZER` (verified with `javap`). The customizer hook is the plugin's
own extension point, so ankka's factory is configuration, not a fork.

**Rotation caveat, decided**: r2dbc-postgresql builds its Netty `SslContext` when the
configuration is built, once. The `SSL_CONTEXT_BUILDER_CUSTOMIZER` runs at that point too. To
honour a renewal, ankka's factory is a thin delegating `ConnectionFactory` whose `create()`
rebuilds the underlying Postgres factory when the mounted files' modification time changes
(checked at most once a minute), keeping the pool's contract intact. The spec's edge case names
this exactly.

**Alternatives considered**: Password over `verify-full` only — was the spec's first draft and the
user moved client-certificate authentication into scope. Patching the plugin — a fork to
maintain for three config keys.

## R5. Certificates and authorities: cert-manager, three CAs, RSA, 24h validity renewed every 8h

**Decision**:

| Authority | Kind | Where | Issues |
|---|---|---|---|
| `ankka-cluster` | `ClusterIssuer` over a self-signed root `Certificate` in `cert-manager`'s namespace | installation (`components/pki`) | one certificate per service for remoting and management |
| `ankka-service` | `ClusterIssuer`, same shape | installation | one certificate per service for HTTP, the gateway's client certificate, the identity provider's and the control plane's server certificates |
| `ankka-database` | namespaced `Issuer` over a self-signed root in the project namespace | per project, rendered by the operator beside the CNPG `Cluster` | one client certificate per provisioned service, CN = the role name; the `streaming_replica` certificate |

Certificates: `privateKey: {algorithm: RSA, size: 2048, encoding: PKCS8, rotationPolicy: Always}`,
`duration: 24h`, `renewBefore: 16h` (a new certificate every 8 hours, each valid for 24, so the
one being replaced stays valid for 16 hours — far beyond the 5-minute remoting cache and the
kubelet's Secret propagation), URI SAN `ankka://<project>/<service>` as the identity, DNS SANs the
in-cluster names, and `usages: [server auth, client auth]`.

**Rationale**: cert-manager v1.21.2 is already a component; a CA `ClusterIssuer` looks its secret up
in cert-manager's namespace (the trap CLAUDE.md records), which is *correct* for an
installation-wide authority whose root lives there — the trap bit when the secret was elsewhere.
Roots are self-signed because Pekko's remoting engine refuses intermediates (R1). RSA because
`pekko-pki` reads RSA keys only (R1); PKCS8 because both the JDK path and Netty accept it.
cert-manager's minimums are 1h duration and 5m renewBefore, both far below these values, and a
CA-issued Secret carries `ca.crt` beside `tls.crt`/`tls.key`, which is what every consumer mounts
as its trust root. `rotationPolicy: Always` is the cert-manager ≥ 1.18 default and is stated
anyway. Separate authorities per kind of traffic are the guide's explicit recommendation, and they
make a remoting certificate useless against an HTTP port and vice versa (FR-002).

**Database authority is per project and owned by cert-manager, not CNPG**: CNPG's generated
`<cluster>-ca` Secret holds `ca.crt` *and* `ca.key` under CNPG's key names, which cert-manager's CA
issuer does not read (it wants `tls.crt`/`tls.key`). So the operator renders a self-signed root
`Certificate` + `Issuer ankka-database` per project and points the `Cluster` at it with
`spec.certificates.clientCASecret` and a `replicationTLSSecret` (a `Certificate` with
`commonName: streaming_replica` from the same issuer) — the shape CNPG's own cert-manager example
uses. CNPG keeps generating the *server* CA and certificate (`<cluster>-ca`, `<cluster>-server`),
which the service verifies; the pod mounts only the `ca.crt` item of `<cluster>-ca`, never its
`ca.key`.

**Alternatives considered**: One CA for everything — rejected by FR-002 and the guide. EC keys —
`pekko-pki` cannot read them (and LibreSSL's EC output is a known trap). Longer validity —
weakens the guide's temporal argument; shorter — the k3s suites already run for minutes and a
renewal every hour would add flake for no security gain.

## R6. Caller identity: a URI SAN, mapped by the router, with a local-mode stand-in

**Decision**: The identity in every service certificate is the URI SAN
`ankka://<project>/<service>`; the gateway's is `ankka://gateway`. `modules/http` gains
`Caller` — `Gateway`, `Service(project, name)`, `Local` — on `RequestContext`, set by the router
from the peer certificate under TLS and to `Local` otherwise, on the handler's thread through
`RequestScope` exactly as `principal` is. `Acl.AllowCallers(matchers*)` joins the enum, with
matchers `internet`, `service(name)`, `service(project, name)`, `anyInProject`, `self`; the
server's own project and name come from its own certificate's SAN, so no environment variable
names them. A peer certificate with no `ankka://` SAN is refused 403 as an unrecognised caller
before any route runs.

**Rationale**: A URI SAN carries structure a DNS name cannot, is what SPIFFE does, and is
readable from `X509Certificate.getSubjectAlternativeNames` (type 6). Keeping `Caller` distinct
from `Principal` is what lets a route have both (spec story 2, scenario 5). `Local` rather than
`Option` keeps every predicate total and makes "not enforced here" an explicit value the startup
log names once (FR-019).

**Testkit impersonation**: In local mode `HttpServer` generates a random per-process token at
start; a request carrying `X-Ankka-Local-Caller: <token> <caller>` is admitted as that caller, and
anything else is `Local`. The token never leaves the process except to the testkit, which reads it
from the server object (`AnkkaTestKit.httpAs(caller)`); the sidecar accepts it from
`ANKKA_LOCAL_CALLER_TOKEN` so the Python and TypeScript integration testkits, which run the sidecar
in a container, can set it. This is not the impersonation header feature 012 declined — that was
a header trusted by value; this is a secret the process minted.

## R7. Gateway → service: Gateway API `BackendTLSPolicy`, a gateway client certificate, trust-manager for the CA ConfigMap

**Decision**: For every exposed service the operator renders a `BackendTLSPolicy`
(`gateway.networking.k8s.io/v1alpha3`, in the project namespace, owned by the `AnkkaService`
beside its `HTTPRoute`) targeting the Service's `http` section with
`validation.hostname = <service>.<namespace>.svc.cluster.local` and
`caCertificateRefs: [{kind: ConfigMap, name: ankka-service-ca}]`. The gateway component adds a
`Certificate ankka-gateway-client` (URI `ankka://gateway`, from `ankka-service`) and
`EnvoyProxy.spec.backendTLS.clientCertificateRef` naming its Secret. **trust-manager** is added as
an installation component with one `Bundle` that writes the `ankka-service` root's `ca.crt` into a
ConfigMap `ankka-service-ca` in every namespace labelled `app.kubernetes.io/managed-by: ankka`
plus the platform's own.

**Rationale**: Envoy Gateway v1.9.1 documents backend mutual TLS with exactly these two pieces:
`BackendTLSPolicy` for validating the backend (CA from a ConfigMap or ClusterTrustBundle in the
policy's namespace) and `EnvoyProxy.backendTLS.clientCertificateRef` for the certificate Envoy
presents (installation-wide, which is right: the gateway is one principal). The CA must be a
ConfigMap in the *service's* namespace, and nothing in the platform can write one without reading
the CA's Secret — the operator may not (FR-006), the control plane cannot. trust-manager is
cert-manager's own tool for this one job and is deliberately the only new controller.

**To verify in T1's spike**: whether `clientCertificateRef` may name a Secret in the EnvoyProxy's
own namespace (`ankka-gateway`) without a `ReferenceGrant` — the upstream example keeps both in
`envoy-gateway-system`.

**Alternatives considered**: The operator reading `ca.crt` from the service's own issued Secret —
it would hold the service's private key in memory to do it. A `ClusterTrustBundle` — still needs a
writer, and the API is beta. Terminating the gateway's TLS re-origination at a per-route client
certificate (`Backend` resource) — per-installation is enough.

## R8. Network policy: enforced on k3s and on kind, kubelet probes exempted by the CNIs in scope

**Decision**: The operator renders three `NetworkPolicy` objects: per service, one for the cluster
ports (17355, 7626 from the service's own pods; 7627 from anywhere) and one for the HTTP port
(from pods labelled `app.kubernetes.io/managed-by: ankka` in any namespace so labelled, and from
the `ankka-gateway` namespace); per project, one for the database pods (`cnpg.io/cluster`
selector: 5432 from the project's ankka workloads; 8000 and 5432 from the CNPG operator's
namespace and from the cluster's own pods). Selecting a pod with any policy makes every other
ingress refused, which is the default-deny FR-011, FR-018 and FR-026 ask for.

**Rationale**: k3s ships kube-router's network policy controller enabled by default (the
`--disable-network-policy` flag exists to turn it off), so the k3s test image enforces policy as
is. kind's v0.24.0 release notes: "Out-of-the-box support for network policy via
sigs.k8s.io/kube-network-policies" — so a kind cluster created by the documented command enforces
policy with no CNI change, and `deploy-local.sh` refuses an older kind (FR-028) by probing rather
than by version: it applies a deny-all policy to a throwaway pod and requires a connection to it
to fail. CNPG's networking guidance requires the operator namespace to reach cluster pods on
8000 and 5432 and instances to reach each other, which the database policy allows. Kubelet probes
originate from the node, and kube-router, kube-network-policies, Calico and Cilium all exempt
host-originated traffic — but a policy cannot *name* the node, so the 7627 rule allows every source
as belt and braces, and the k3s suite asserts readiness stays true under the policy (SC-003)
rather than trusting this paragraph.

**Alternatives considered**: Egress policy — out of scope in the spec. A per-project HTTP boundary
— declined in clarification.

## R9. The one non-rolling transition

**Decision**: The pod template gains the label `ankka.thinkmorestupidless.com/transport: tls`
(`Labels.TransportKey`), added to the contact-point selector beside the formation label and — like
it — never to the Deployment's immutable `spec.selector`. When the operator finds an existing
ankka-managed Deployment whose template lacks the label, it executes `Action.Transition`: delete
the Deployment, wait until no pod with the service's identity labels remains (bounded by the
grace period plus the 5-second `preStop`), then apply the new one. The reported status during the
wait is `Deploying` with detail `moving to mutual TLS: instances restart together, once`, which the
control plane's history keeps.

**Rationale**: A TLS node and a plain node cannot join each other, so the rolling update's surge
pod would never become ready — the formation-label deadlock again. Letting the new pod form its own
cluster while old pods still hold shards is two clusters on one journal, the split `Recreate` once
prevented. Deleting and re-applying costs one bounded interruption and no data; the journal is the
service. The transport label on the selector means a new pod never probes an old one even during
the overlap, so the transition cannot deadlock if the wait is cut short. `Recreate` as a strategy
was considered and rejected: switching strategies on a live Deployment is the wedge CLAUDE.md
records, and it would stay on the object afterwards.

## R10. Compatibility floor and the old-image failure detail

**Decision**: `Compatibility` gains `MinimumRuntime = Version(0, 8, 0)`; a declared runtime below
it is refused at projection through the existing `ClusterView.Refused → Unavailable` path with
`runtime <declared> predates mutual TLS; the platform requires <minimum> or later`. For an
undeclared runtime that cannot speak TLS, the pod is never ready because its readiness probe names
a `probe` port the container does not declare; the operator's `podProblems` gains the newest
readiness-related Event on a not-ready pod, so the `Failed` detail reads what the kubelet said
rather than only `ProgressDeadlineExceeded`. That needs `events: get, list` on the operator's
ClusterRole, added with its reason.

**Rationale**: The rule "minor equal or one below" would accept a 0.7 descriptor under a 0.8
platform, and a 0.7 image cannot form a cluster or be reached by the gateway. Reading events is
the only cheap source of the kubelet's own words, and it improves every other never-ready report
too.

## R11. Service-to-service client: SRV resolution in-cluster, the console's registry locally

**Decision**: `sdk` gains a `ServiceClient` trait (blocking, virtual-thread friendly like
`ComponentClient`: `get[R](path)`, `post[B, R](path, body)`, and a raw `request`) obtained from
`EndpointClients.serviceClient(name)` / `serviceClient(project, name)` and from `AnkkaService` for
components. `modules/http` implements it over Pekko HTTP's client with R12's rotating client
context (presenting the service certificate, trusting the service CA, verifying the callee's DNS
SAN and its `ankka://` URI). The callee's address is `<name>.<prefix>-<project>.svc.cluster.local`
and its port is resolved from the Service's SRV record `_http._tcp.<name>.<ns>.svc.cluster.local`
through pekko-discovery's DNS method — the Service port is already named `http`, and the client
must not guess a port the descriptor can change. The operator sets `ANKKA_NAMESPACE_PREFIX` on
every workload (and the control plane refuses it in a descriptor, like the cluster variables) so
the client can name a namespace. Locally, `ServiceRegistration`'s directory gives each running
service's observability address, which already answers with the HTTP bound address the console
uses; the client reads it and calls plain HTTP.

**Rationale**: A URL in code is a port in code. SRV is what Kubernetes DNS publishes for a named
port and pekko-discovery already resolves it; the dependency is present. Local resolution reuses
what the console does, so a two-service project runs on a laptop unchanged (FR-022).

**Alternatives considered**: Reading the callee's Service through the API — no RBAC and no
reason to give any. Fixing every Service port to 9000 — contradicts the rendering's own comment
that a Service does not translate ports.

## R12. One rotating TLS provider in `runtime`, reused four times

**Decision**: `runtime` gains `RotatingTls`: given a directory with `tls.key`, `tls.crt`, `ca.crt`,
it builds an `SSLContext` (key manager from the PEM pair via `pekko-pki`'s `PEMDecoder`, trust
manager from `ca.crt`), re-reads when a file's modification time changes (polled at most once a
minute, on demand), and hands out `SSLEngine`s for server (client auth `Need`) or client use. The
HTTP server (R3), the management server and bootstrap client (R2), the service client (R11) and
the control plane's JWKS fetch (R14) all use it; the database factory (R4) reads the same
directory layout. Remoting uses Pekko's own engine (R1) which does the same job with the same
files.

**Rationale**: Four TLS paths that each parsed PEM and cached contexts would disagree eventually —
the reason `EventSourcedEffect.materialise` is shared. `pekko-pki` is already on the classpath.
Modification-time polling is what Pekko's engine does; a `WatchService` on a projected Secret
volume is unreliable because the kubelet swaps a symlinked directory rather than rewriting files.

## R13. Test certificates: minted in-process, never checked in

**Decision**: A test-scope dependency on `org.bouncycastle:bcpkix-jdk18on` and a `TestPki` helper
in `runtime`'s test sources (shared through `runtime % "test->test"`) mint a root CA and leaf
certificates with any SANs and lifetime a test wants — including a certificate that expires in
seconds, for rotation cases.

**Rationale**: Checked-in fixtures expire and cannot express rotation; `openssl` on the build
machine is LibreSSL with the EC trap; the JDK cannot sign certificates without an internal API.
BouncyCastle is the standard answer and is test-only, so `ankka-runtime`'s published POM is
unchanged.

## R14. The platform's own workloads

**Decision**: The control plane component gets a cluster `Certificate` and a service
`Certificate` from the two `ClusterIssuer`s, the same three mounts, the same env, the same
transport label, a `NetworkPolicy` pair, and a `BackendTLSPolicy` for its `HTTPRoute`. Its
`ANKKA_AUTH_JWKS_URL` becomes `https://ankka-keycloak-service.ankka-auth.svc:8443/...` with
`ANKKA_AUTH_JWKS_CA` naming the mounted service CA; the Keycloak resource sets `http.tlsSecret`
from a `Certificate` issued by `ankka-service` for its in-cluster names and `httpEnabled: false`,
and the identity provider's `HTTPRoute` gets a `BackendTLSPolicy` too. The control plane's own
CNPG database keeps password authentication over `verify-full` (its `bootstrap.initdb` shape is
the one CLAUDE.md singles out); certificate authentication for it is a follow-up named in the plan.

**Rationale**: FR-027 names remoting, the gateway and the key fetch. The Keycloak operator supports
`spec.http.tlsSecret`; Keycloak then serves 8443. The JWKS fetch is the control plane's only
outbound in-cluster call.

## R15. Documentation surface

**Decision**: Rewrite `docs/platform/networking.md` (the whole "what is not isolated" section
inverts), update `limitations.md` and `akka-divergences.md` (FR-032, FR-033),
`concepts/clustering.md` (TLS, the probe port), `platform/install-cloud.md` (network policy
requirement and check, trust-manager, the three issuers, swapping them), `platform/install-local.md`
(the enforcement probe), `platform/databases.md` (certificate authentication, the credential
Secret's new shape), `build/http-endpoints.md` (caller ACLs, `ServiceClient`, local behaviour, the
testkit's `httpAs`), the three SDK references, `reference/configuration.md` and
`reference/sidecar-protocol.md` (generated tables plus prose), `reference/runtime-endpoints.md`
(the probe port, management behind mutual TLS), `deploy/upgrading.md` (the one transition),
`operate/troubleshooting.md` (never-ready old image, route rejected for a missing CA ConfigMap).
Every code sample comes from a `docs:start` region in a test.

## R16. Version

**Decision**: This feature ships as **0.8.0** (the tree is at `v0.7.1`); the protocol version's
minor increments for the new `caller` and `allow_callers` fields; `MinimumRuntime` is 0.8.0.
