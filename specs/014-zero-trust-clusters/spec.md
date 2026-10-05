# Feature Specification: Zero Trust in the Service Clusters

**Feature Branch**: `014-zero-trust-clusters`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "i want to spec out the implementation of 'zero-trust' in the service
clusters — see https://doc.akka.io/libraries/guide/security/zero-trust.html for all the details of how
(and why) to implement this"

## Context

Akka's zero-trust guide makes one argument and lists five things that follow from it. The argument:
a perimeter will be breached, so every connection *inside* it must be verified rather than trusted,
and a breached component must be limited to what that component was allowed to do. The five things:

1. **Mutual TLS on every network connection** — cluster remoting, HTTP and gRPC between services,
   and the database — with a different certificate authority for each kind of traffic.
2. **Identity on every connection.** A certificate names the service that presented it, and the
   receiver decides whether *that* service may call *this* API, so a breach is contained to what the
   breached service could already do.
3. **Credentials that rotate often** — Akka's example is every eight hours — picked up without a
   restart, so a leaked key is useful for hours rather than for the life of a deployment.
4. **Least privilege**, checked per call rather than granted to the network.
5. **Per-resource authorization** with tokens that tie a request back to a user, so a breached client
   cannot exceed what the user's session allowed.

ankka has the fifth and none of the first four. Feature 012 wrote the gap down, in
`docs/reference/limitations.md` under *Networking and security*, and it reads today:

- Cluster traffic — remoting on 17355 and management on 7626 — is plain TCP on the pod network,
  reachable from any namespace. A node picks its peers by label; nothing stops another workload
  connecting.
- Projects are not a network boundary. Any pod can reach any service's in-cluster address.
- The platform establishes no caller identity. There is no mutual TLS between services, no
  workload identity, and nothing distinguishes a request from the internet from one sent by the pod
  in the next namespace. `Acl.AllowIf` sees what the request carries and a header naming a caller is
  evidence of nothing.
- In-cluster traffic is plain HTTP; TLS ends at the gateway. The database connection is plain TCP
  with a password.

Feature 012 also recorded *why* ankka refused to invent Akka's caller-naming ACL vocabulary —
`INTERNET`, a named service, `SELF` — without the machinery: a principal decided from a header the
caller sets is not a control. That reasoning stands. This feature builds the machinery, and the
vocabulary follows from it.

Three things make this feasible now rather than a service mesh away:

- **The pieces exist in the platform already.** cert-manager is installed on every ankka
  installation and issues the gateway's wildcard certificate; a certificate per workload is more of
  the same. The runtime's clustering library ships a TLS transport that reads PEM files from a
  mounted Secret, re-reads them as they rotate, and refuses a peer whose certificate does not name
  the same subject as its own — Akka's own remoting design, which the guide links to. The HTTP
  server can be given a TLS context that requires a client certificate and exposes it to the route.
  The persistence driver can verify the database's certificate, and the driver beneath it can
  present one. The provisioned Postgres already serves TLS from a per-cluster CA that nothing
  verifies, and already accepts certificate authentication against a client CA it generates.
- **Everything a workload needs is rendered by one place.** The operator renders every Deployment,
  Service and route a service has; certificates, mounts, TLS settings and network policy are more
  rendering, and a service author changes nothing. A Python or TypeScript service gets all of it,
  because its sidecar is the process that speaks to the network.
- **It is an overlay property.** Cluster formation is already chosen by where the process runs.
  Zero trust is the same shape: on in the Kubernetes overlay, where the operator supplies
  certificates; absent locally, where there is no perimeter to distrust and a developer's laptop is
  the trust boundary.

What this feature is *not* is a service mesh. No sidecar proxy is injected, no control plane for
one is installed, and the identity is the service's own process presenting its own certificate.
That is deliberately Akka's shape rather than Istio's: a mesh would give every workload the mesh's
identity model and the mesh's failure modes, and ankka's workloads already have exactly one
process that owns the socket.

## Clarifications

### Session 2026-09-28

- Q: Should a project be a *network* boundary for service HTTP, or only an identity boundary? → A:
  Identity decides. The network admits HTTP from any platform-identified workload and the gateway;
  the callee's caller-naming ACL decides. Cluster ports and databases stay project-bounded.
- Q: Is zero trust always on for every Kubernetes installation, or can an installation or a
  service switch it off? → A: Always on. No installation switch and no descriptor opt-out.
- Q: Who triggers the one non-rolling transition from a pre-feature image to one with mutual TLS?
  → A: The platform, automatically. The operator detects running pods without the TLS marker,
  performs the stop-then-start once, and records it in the service's status and history.
- Q: Keep client-certificate authentication to the database out of scope? → A: No, in scope. A
  provisioned service authenticates to Postgres with a certificate from the project's database
  authority; no password is generated for it, and the credential rotates with the certificate.

### Session 2026-10-05 (glossary)

- Q: Which word for a connection where both ends showed a certificate the installation's authority issued? → A: **mutually authenticated** (refusing "mutual TLS", "mTLS" and "proven").

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Cluster traffic is private to the service (Priority: P1)

A service's instances form their cluster over mutual TLS with a certificate that names the service
and is issued by the installation's own authority. An instance refuses a peer that presents no
certificate, a certificate from another authority, or a certificate naming a different service.
Nothing outside the service's own pods can open a connection to its remoting or management ports at
all. The certificate rotates on a schedule measured in hours, and instances pick up the new one
without restarting or leaving the cluster.

**Why this priority**: Remoting is the most powerful port a service has — a peer that joins the
cluster can host any entity, read any journal record and answer any request — and today it is open
to every pod in the cluster. Closing it is the largest single reduction of the attack surface and
depends on nothing else in this feature.

**Independent Test**: Deploy a three-instance service into a real cluster, prove it forms; from a
pod in another namespace, try the remoting and management ports and observe the connection refused
at the network; from a pod carrying the service's own labels but a certificate for a different
service, observe the connection refused at the handshake; then advance the certificate past its
renewal point and observe membership unchanged and every request answered.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/zero-trust/service-cluster.feature`: the instances of a service join one service cluster over mutually authenticated connections
- added `features/zero-trust/service-cluster.feature`: a workload outside a service's project cannot connect to where its instances talk to each other
- added `features/zero-trust/service-cluster.feature`: a workload that cannot show the service's certificate cannot join its service cluster
- added `features/zero-trust/service-cluster.feature`: renewing a service's certificate restarts nothing and refuses nothing
- added `features/zero-trust/service-cluster.feature`: an instance of a new version joins the service cluster by showing the service's certificate
- `features/clusters/replacing.feature`: a service whose image changes refuses no request
- added `features/zero-trust/service-cluster.feature`: instances that cannot show a certificate are stopped before the new ones start, and the report says so

---

### User Story 2 - A request carries the caller's identity (Priority: P1)

Every HTTP request that reaches a service inside the cluster arrives over mutual TLS. A request
from another service carries that service's identity; a request from outside the cluster arrives
through the installation's gateway and carries the gateway's. A service author can therefore write
the ACLs Akka users write — allow the internet, allow a named service, allow any service in this
project, allow this service itself — and they mean something, because the caller was named by a
certificate the platform issued and not by a header the caller chose. A request that presents no
certificate never reaches an endpoint. A Python or TypeScript endpoint sees the same caller as a
Scala one.

**Why this priority**: It is the half of the gap feature 012 named and declined to fake, and the
half a service author actually experiences. Without it, every in-cluster call is anonymous and an
endpoint's ACL can only choose between open and closed.

**Independent Test**: Deploy two services into one project and a third into another; write an
endpoint on the first that allows the second and the internet; observe the second's calls served
with its identity on the request, the third's refused as forbidden naming nobody it should not, a
request through the gateway served as the internet, and a plain-HTTP or certificate-less request
refused at the connection.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/zero-trust/callers.feature`: an HTTP endpoint that admits a named service serves it and tells the handler who called
- added `features/zero-trust/callers.feature`: an HTTP endpoint refuses a service its ACL does not name, as a refusal and not a failure
- added `features/zero-trust/callers.feature`: an HTTP endpoint that admits the gateway serves a request from the internet
- added `features/zero-trust/callers.feature`: an HTTP endpoint that admits only the gateway refuses a service of the installation
- added `features/zero-trust/callers.feature`: a connection that does not prove its workload reaches no endpoint
- added `features/zero-trust/callers.feature`: a handler behind an authenticator is told both the principal and the calling workload
- added `features/zero-trust/callers.feature`: a service in any language is told the same calling workload
- added `features/zero-trust/callers.feature`: on a developer's machine every request comes from the local caller, and the service says once that callers are not checked

---

### User Story 3 - A service calls another service as itself (Priority: P2)

A Scala service asks the SDK for a client to another service, by name for one in its own project or
by project and name for one elsewhere, and the client presents the service's own certificate and
verifies the callee's. The author never handles a key, a file path or a TLS setting. On a
developer's machine the same code reaches the named service by its registered local address with no
TLS, so a service that calls another runs unchanged in both places.

**Why this priority**: Story 2 needs a caller. Without a client that presents identity, the only
way to be a named service is to read the mounted certificate by hand, and a feature whose safe path
is the hard path will be bypassed. It is P2 because story 2 can be proven with a test harness
before this exists, and because Python and TypeScript are deliberately left to a later feature.

**Independent Test**: In a two-service project, have the first call the second through the SDK
client under a k3s suite and observe the call served with the first's identity; run the same two
services locally and observe the call served.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/zero-trust/calling-services.feature`: a service sends a request to another of its project by name, as itself
- added `features/zero-trust/calling-services.feature`: a service sends a request to a service of another project by project and name, as itself
- added `features/zero-trust/calling-services.feature`: a request is not sent to a workload that is not the service asked for
- added `features/zero-trust/calling-services.feature`: on a developer's machine a service sends a request to another running there by name

---

### User Story 4 - The database connection is mutual TLS, and there is no password (Priority: P2)

A service's connection to its provisioned database is TLS in both directions. The service verifies
that the database it reached is the one the platform provisioned for it, against the authority that
issued the database's certificate, and the database authenticates the service by a certificate the
platform issued for it — no password is generated, stored or mounted, and the credential rotates
with the certificate. A database accepts connections only from its own project's workloads. A
service that supplies its own database can say how to verify it, and keeps whatever credential its
owner configured.

**Why this priority**: The journal is the service. Today it crosses the pod network in the clear
with a password that never rotates, and any pod in the cluster can open a connection to any
project's Postgres and try that password. Akka's guidance names the database as a connection that
takes a client certificate, and it is the one credential the platform generates today that a leak
would make permanent.

**Independent Test**: Deploy a service into a k3s cluster and read the database's own view of the
service's sessions: every one is TLS, authenticated by certificate, and the credential Secret
mounted into the pod holds no password. From a pod in another project, attempt a connection to the
database's port and observe it refused at the network. Then deploy a service provisioned *before*
this feature and observe it moved to certificate authentication on its next deployment.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/databases/connection.feature`: a service's connection to its provisioned database is mutually authenticated
- added `features/databases/connection.feature`: a provisioned database is reached with no password
- added `features/databases/connection.feature`: a service does not connect to a database whose certificate it was not told to trust
- added `features/databases/isolation.feature`: a service cannot connect to another service's database with its own credential
- added `features/databases/isolation.feature`: a workload outside a project cannot connect to the project's databases
- added `features/databases/connection.feature`: a service whose database was provisioned with a password is moved to its certificate when it is next deployed
- added `features/databases/connection.feature`: a service that declares a database of its own checks it as its descriptor says
- added `features/databases/connection.feature`: a service that declares a database of its own and nothing to check it with connects unchecked

---

### User Story 5 - The platform practices what it renders (Priority: P3)

The control plane is an ankka application deployed from a manifest rather than by the operator. Its
own cluster forms over mutual TLS with a certificate issued the same way; its management and
remoting ports are reachable only from its own pods; the gateway reaches it over TLS with the
gateway's identity; and its connection to the identity provider's keys is verified rather than
plain in-cluster HTTP.

**Why this priority**: A platform that holds every organisation's desired state and every deploy
token digest is the most valuable target in the cluster, and it would be the one workload left in
the clear. It is P3 only because it depends on stories 1 and 2 for the pieces and on none of the
service-facing behaviour.

**Independent Test**: In the end-to-end k3s suite, assert the control plane's remoting is refused
from another namespace, its cluster forms, `ankka` commands succeed through the gateway, and its
key fetch from the identity provider is over TLS.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/zero-trust/control-plane.feature`: the control plane's instances join their service cluster over mutually authenticated connections nothing else can open
- added `features/zero-trust/control-plane.feature`: a member reaches the control plane through the gateway, which it reads as the calling workload
- added `features/zero-trust/control-plane.feature`: the control plane checks the issuer's certificate when it fetches the issuer's keys

---

### User Story 6 - The guarantee is written down, and the absent one removed (Priority: P3)

Someone deciding how to protect a service reads the documentation and finds what the platform now
guarantees: which connections are mutual TLS, what a caller identity is and how an ACL names one,
how often credentials rotate, what the network refuses, and what an installation must provide for
any of it to be true. The limitations that no longer hold are gone, and the ones that still hold —
no realm for a service's own users, no identity-bearing client for Python and TypeScript — stay.

**Why this priority**: The platform's most common reader is a model that retrieved one page, and a
page that still says "no mTLS" beside a runtime that requires it is worse than either alone.

**Independent Test**: `just docs` passes; the limitations page no longer lists the four items quoted
in *Context*; the divergences page no longer lists caller principals as a divergence; the
networking page describes the new shape; `DocumentationDescriptorsSuite` and the generated
configuration tables cover every new variable.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/zero-trust/documentation.feature`: the limitations list what is still not protected and none of what now is
- added `features/zero-trust/documentation.feature`: the documentation of installing in a cluster says the network must enforce what the platform asks of it
- added `features/zero-trust/documentation.feature`: the ACLs that name a calling workload mean the same in every language

---

### Edge Cases

- **The readiness probe presents no certificate.** The kubelet cannot hold a client certificate and
  does not verify the server's. Readiness must keep working after the management port requires
  identity from peers; a pod that becomes permanently un-ready because its probe was locked out is
  an outage with no error anywhere.
- **Rotation overlap.** The runtime caches a TLS context for minutes and a new peer may present a
  certificate a stale context has not loaded. A renewal must leave the old certificate valid for
  longer than any cache lives, or a rolling renewal refuses its own peers.
- **Mounted secrets update late, and not at all through a `subPath`.** A certificate is rotated by
  rewriting a Secret; the kubelet propagates that to a volume within its sync period and never to a
  `subPath` mount. The rendering must not use one, and the renewal window must exceed the
  propagation delay.
- **Upgrading across the boundary.** A running pod that speaks plain TCP and a new pod that
  requires TLS cannot join each other. The rolling update that is otherwise zero-downtime would
  stall forever with the new pod never ready — the same shape as the formation-label deadlock —
  and the obvious fix, letting the new pod form its own cluster, is two clusters on one journal.
  This is the one transition that is not a rolling update.
- **An image that declares no runtime version.** Compatibility is unchecked for an undeclared
  runtime, so an old image can be deployed under a platform that renders TLS it does not speak. It
  must fail visibly — never ready, with a status detail naming the version route's answer or its
  absence — not silently serve plain HTTP that the gateway cannot reach.
- **A cluster that accepts a network policy and ignores it.** Every API server admits a
  `NetworkPolicy`; only a CNI that implements them enforces one. Every offline test passes, the
  policy exists, and nothing is refused. The installation must prove enforcement, not assume it.
- **The gateway is one principal.** Every request from the outside world carries the same identity,
  whichever hostname it arrived at. An ACL that allows the internet allows *every* exposed route's
  callers; per-user authorization on top of that is a bearer token, as it is today.
- **A caller-naming ACL on a service with `"http": false`.** Nothing to protect and nothing to
  render; the descriptor is valid and the rendering emits no server-side TLS for it.
- **`_ankka/health`.** Exempt from every ACL, unchanged — but it is now behind mutual TLS like every
  route, and anything that called it in the clear from inside the cluster stops working.
- **A database certificate rotates under an open connection pool.** A session authenticated
  before a renewal stays valid; a *new* connection after it must present the new certificate, so
  the driver must read the files when it connects rather than once at startup, or the pool is
  fine until the first reconnect after the first renewal.
- **Kafka.** A topic source's bootstrap address is the descriptor owner's, not the platform's; its
  TLS is theirs to configure and the runtime must not get in the way of that.
- **The process and its sidecar.** The gRPC conversation between a Python or TypeScript process and
  its sidecar is on the pod's loopback interface, shared by nothing but the pod's own containers. It
  stays plain, and the documentation says why that is not a gap.
- **Project deletion.** Certificates, policies and the authority's issued Secrets are owned by the
  service's resource or the project's namespace, so cascade deletion removes them; nothing here
  introduces a sweep.

## Requirements *(mandatory)*

### Functional Requirements

**Identity**

- **FR-001**: Every workload the operator deploys MUST be issued a certificate naming its project
  and service, by an authority the installation controls, and the certificate MUST be usable to
  verify the workload's in-cluster address.
- **FR-002**: The authority for cluster remoting, the authority for service-to-service HTTP and the
  authority for a project's database MUST be distinct, so a certificate for one kind of traffic
  cannot be presented for another.
- **FR-003**: A certificate MUST have a lifetime measured in hours and MUST be renewed
  automatically before it expires, with the previous certificate remaining valid for longer than
  any cache or propagation delay in the path.
- **FR-004**: A renewed certificate MUST be picked up by a running instance without a restart, a
  cluster leave, or a refused request.
- **FR-005**: The operator MUST create and own every certificate request, mount and policy a
  workload needs, and a service descriptor MUST NOT be able to set, override or disable any of it.
  The control plane MUST refuse a descriptor that tries, as it refuses `ANKKA_CLUSTER_MODE` today.
  There MUST be no installation-level setting that disables it either: in a cluster, zero trust is
  always on.
- **FR-006**: The operator MUST never read a private key. Keys reach a pod through the kubelet, from
  Secrets the operator did not write.
- **FR-007**: The operator's grant MUST grow by exactly the kinds this feature renders and no verb
  beyond what rendering them needs, and the documentation of its grant MUST say why each was added.

**Cluster traffic**

- **FR-008**: In the Kubernetes overlay, remoting between a service's instances MUST be mutual TLS,
  and an instance MUST refuse a peer that presents no certificate, a certificate from another
  authority, or a certificate naming a different service.
- **FR-009**: Cluster bootstrap's probing of contact points MUST run over the same mutual TLS, so
  that a node never learns of a cluster from a peer it could not verify.
- **FR-010**: The readiness probe MUST continue to answer on the management port without a client
  certificate, and no other management route MUST be reachable without one.
- **FR-011**: A network policy MUST permit connections to an instance's remoting and management
  ports only from pods of the same service and from the node's own probes, and MUST refuse every
  other source.
- **FR-012**: The local overlay MUST be unchanged: no TLS, no certificate files, loopback, random
  port. Zero trust is a property of where a process runs, selected the way cluster formation is.

**HTTP between services and from the gateway**

- **FR-013**: In the Kubernetes overlay, a service's HTTP port MUST accept only TLS connections with
  a client certificate issued by the installation's service authority; a connection without one
  MUST be refused before any route, ACL or handler runs.
- **FR-014**: The gateway MUST reach a service over TLS, presenting a certificate that identifies
  it as the gateway, and MUST verify the service's certificate against the service authority.
- **FR-015**: Every request that reaches an endpoint in the Kubernetes overlay MUST carry a caller
  identity derived from the client certificate: the gateway, or a project and service name. It MUST
  be available to ACL predicates and to handlers on the handler's own thread, as the request context
  is today.
- **FR-016**: The SDK's ACL vocabulary MUST gain forms that name a caller — the internet (the
  gateway), a named service in this project, a named service in a named project, any service in this
  project, and this service itself — with Akka's semantics: a list of allowed callers admits any of
  them, and a caller-naming ACL composes with `Acl.Authenticate` so a handler may have both a token
  principal and a caller.
- **FR-017**: A refusal by a caller-naming ACL MUST be a 403 recorded as a refusal, never a fault,
  and its body MUST NOT disclose which callers would have been allowed.
- **FR-018**: A network policy MUST permit connections to a service's HTTP port only from the
  gateway and from workloads carrying a platform identity — in any project — and MUST refuse a pod
  that carries none. A project is an identity boundary for HTTP, not a network one: whether a
  service in another project may call is the callee's ACL's decision, never the network's.
- **FR-019**: Outside a cluster — a local run, the testkit — every request MUST carry the caller
  identity "the local machine", a caller-naming ACL MUST admit it, and the service MUST log once at
  startup that caller identity is not enforced there. The testkit MUST let a test present a chosen
  caller, so an ACL that names one can be tested without a cluster.
- **FR-020**: The Python and TypeScript SDKs MUST expose the same caller identity on their request
  context and the same caller-naming ACL forms, with the same semantics; the sidecar MUST carry the
  caller across the protocol; and the conformance suite MUST cover it so the three cannot drift.

**Calling a service**

- **FR-021**: The Scala SDK MUST provide a client for another service, addressed by service name
  within the project or by project and service name across projects, that presents the calling
  service's certificate and verifies the callee's, with no key, path or TLS setting visible to the
  author.
- **FR-022**: The same client MUST, outside a cluster, reach a service registered with the local
  console by its address over plain HTTP, so calling code is identical in both places.
- **FR-023**: The client MUST fail a request whose callee's certificate does not name the service
  asked for, before sending a body, with an error that names the mismatch.

**Database**

- **FR-024**: A service's connection to its provisioned database MUST be TLS with the server's
  certificate and name verified against the project database's authority, and the platform MUST
  supply the trust root to the workload without the descriptor naming it.
- **FR-024a**: A provisioned service MUST authenticate to its database with a certificate issued
  for that service by the project database's authority. The platform MUST NOT generate a password
  for a provisioned role, the credential Secret MUST carry none, and the certificate MUST be
  presented from its mounted files at connection time so that a renewal is honoured by the next
  connection without a restart.
- **FR-024b**: A service provisioned before this feature MUST be moved to certificate
  authentication on its next deployment by the operator alone, with its password ceasing to log in
  and its database untouched; the previously generated credential Secret is left in place, as
  nothing this platform does deletes a Secret.
- **FR-025**: A workload supplying its own database MUST be able to supply a verification mode, a
  trust root, and a client certificate and key through the same `ANKKA_DB_*` family, and keeps a
  supplied password if that is what its owner configured; the reference configuration page MUST
  say what a connection without them does.
- **FR-026**: A network policy MUST permit connections to a project's database only from that
  project's workloads and from the database operator, and MUST refuse every other source.

**The platform's own workloads**

- **FR-027**: The control plane MUST form its cluster over the same mutual TLS with a certificate
  issued the same way, its remoting and management ports MUST be policed the same way, the gateway
  MUST reach it as it reaches a service, and its fetch of the identity provider's keys MUST verify
  the identity provider's certificate.

**Installation**

- **FR-028**: The local deploy script MUST prove that the cluster enforces network policy — by
  applying a policy and observing a connection refused, not by inspecting which CNI is installed —
  and MUST refuse to continue on a cluster that does not.
- **FR-029**: The cloud installation documentation MUST state that network policy enforcement is a
  requirement of the cluster, how to check it, and that an installation without it has the TLS
  guarantees and none of the network ones.
- **FR-030**: The transition of a service from an image without this feature to one with it MUST
  be performed by the operator on its own — detected from the running pods lacking the TLS marker,
  with no flag, command or confirmation from the deployer — MUST complete without human
  intervention so an unattended deploy never stalls, MUST NOT produce two clusters on one journal,
  MUST be visible in the service's status and history as the one non-rolling deployment it is, and
  MUST be documented as such.
- **FR-031**: This feature MUST raise the minimum runtime a descriptor may declare, refused at
  projection through the existing compatibility path with both versions in the detail; a service
  that declares none and cannot speak TLS MUST be reported as never ready with a detail that names
  the cause.

**Documentation**

- **FR-032**: The limitations page MUST no longer claim that cluster traffic is unencrypted or
  unisolated, that projects are not a network boundary for the ports this feature polices, that the
  platform establishes no caller identity, or that in-cluster traffic is plain HTTP; and MUST still
  state what remains: no realm for a service's own users, no identity-bearing client for Python and
  TypeScript, and a supplied database's credential being whatever its owner configured.
- **FR-033**: The Akka divergences page MUST drop the caller-principal row and say which of Akka's
  principals ankka now has, and the ACL page of each SDK reference MUST document the caller-naming
  forms from tested samples.
- **FR-034**: Every new environment variable the operator sets and every new configuration key MUST
  appear in the generated reference tables with prose beside it, and every `service.json` in the
  documentation MUST still validate.

### Key Entities

- **Workload identity**: a certificate naming a project and a service, issued by one of the
  installation's authorities for one purpose — remoting, service HTTP or database — with a lifetime
  in hours. Every pod of a service holds the same one. It is the only thing that makes a caller a
  principal.
- **Caller**: what a request's client certificate says. One of: the gateway (the internet), a
  service (project, name), or — outside a cluster — the local machine. Distinct from the token
  principal `Acl.Authenticate` establishes; a request may have both.
- **Caller-naming ACL**: an ACL that admits a list of callers, with Akka's semantics. It joins
  `DenyAll`, `AllowAll`, `AllowIf` and `Authenticate`, and a route may state one as it may state any
  ACL.
- **Authority**: a certificate authority the installation controls, one per kind of traffic. A
  local installation creates its own; a cloud installation may. The database authority is the one
  the provisioned Postgres already has per project, and it issues both the server's certificate
  and each service's client certificate for that database.
- **Traffic policy**: the network policy a workload gets — which sources may reach which of its
  ports — rendered from the same identity the Deployment and Service select on.
- **Service client**: the SDK's way for a service to call another as itself; addressed by name,
  never by URL, certificate or file.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the k3s suite, zero connections to a deployed service's remoting, management or
  database ports succeed from a pod outside the sources its policy names, and zero handshakes
  succeed on its remoting port from a pod the policy admits but whose certificate names another
  service.
- **SC-002**: In the k3s suite, a request from a permitted service is served with the caller's
  project and service readable by the handler; from an unpermitted service it is refused 403; from
  the gateway it reads as the internet; and a plain-HTTP or certificate-less connection to the
  service's port never produces a request in the recorder.
- **SC-003**: Across at least one certificate renewal observed by the suite, a three-instance
  service records zero membership changes, zero container restarts and zero failed requests under a
  continuous request load.
- **SC-004**: The rolling replacement of a three-instance service under the same load that today
  loses no requests still loses none with mutual TLS on.
- **SC-005**: Every session the provisioned database reports for a deployed service's role is
  encrypted and certificate-authenticated; there are zero unencrypted or password-authenticated
  ones, and the pod's credential Secret contains no password.
- **SC-006**: Measured on the same harness as feature 007's instrumentation benchmark — HTTP in
  through the gateway, entity, journal, reply — median request latency with mutual TLS on is within
  10% of the same path with it off. The number is reported with the run; a larger cost is a
  finding, not a failure, and is written into the networking page.
- **SC-007**: The transition of a running pre-feature service to a post-feature image completes in
  the suite in one deployment with a single, bounded interruption, and its history shows why.
- **SC-008**: The local deploy script refuses a kind cluster whose network ignores policy, proven
  by a deliberately unenforced cluster in the suite or by hand, and the message names what to
  change.
- **SC-009**: The three SDK conformance targets — Scala in-process, Python, TypeScript — pass the
  same caller-identity cases.
- **SC-010**: `just docs` passes; the four limitation statements quoted in *Context* are absent
  from the limitations page and the caller-principal row from the divergences page.

## Assumptions

- **cert-manager is the authority's issuer.** It is already installed on every installation and
  already rotates the gateway's certificate. An installation that wants its authorities elsewhere —
  a corporate PKI, a cloud CA — swaps the issuer, which is the one place the choice lives.
- **The provisioned database's own per-cluster authority is the database authority.** The Postgres
  operator issues a server certificate from it today and generates a client authority beside it,
  documented as the source for certificate-authenticated clients; the platform issues each
  service's database certificate from that authority and configures the database to accept
  certificate authentication for provisioned roles. That the persistence driver's configuration
  exposes only server verification is known: the driver beneath it takes a client certificate, so
  the plan supplies it through the connection factory rather than waiting on the plugin.
- **The gateway can present a client certificate to backends and verify theirs.** The installed
  gateway is a Gateway API implementation with backend TLS policy; whether the client certificate is
  per route or per installation is a research item, and per installation suffices because the
  gateway is one principal.
- **The test cluster enforces network policy and the local kind cluster can.** The k3s image the
  suites use ships a policy controller; kind's default network has enforced policy since kind
  0.23 — to be verified in research, and FR-028 makes the local deploy prove it either way.
- **Rotation is on the order of a day's validity renewed every few hours** — Akka's example is eight
  hours. The exact numbers are the plan's; the constraints (longer than the runtime's context cache,
  longer than kubelet propagation, short enough that a leaked key is bounded) are the spec's.
- **The identity in the certificate is verifiable against the in-cluster address.** A client
  verifying a callee checks both that the certificate is the platform's and that it names the
  service asked for.
- **Local mode has no perimeter to distrust.** A laptop's loopback is the boundary; the compose
  Postgres and the local console are unchanged.
- **The one non-rolling transition is acceptable.** Turning TLS on under a live cluster is a
  restart in Akka too; the platform performs it once rather than stalling, and the cost is a
  bounded interruption for that service on that deploy, and never again.
- **`ankka-cloud`, the hosted product, is an ankka service** and therefore a named caller of the
  control plane; the control plane's own ACLs may name it once this exists, which is a change in
  that repository and not this one.
- **A project is not a network boundary for HTTP, by decision.** The alternative — refusing
  cross-project HTTP at the network unless the callee's descriptor names the calling project — was
  considered and declined: it is defence in depth at the cost of a descriptor field, its validation
  and its rendering, and it would make a cross-project call two changes instead of one. Zero
  trust's own argument is that identity, not network position, decides; the limitations page
  states the choice so nobody reads the policy as a boundary it is not.
- **Zero trust is always on in a cluster, by decision.** There is no installation setting and no
  descriptor field that turns any of it off: every operator-deployed workload gets its
  certificates, mutual TLS and policy, so a service author can rely on the guarantee and there is
  one shape to test and document. A cluster whose network ignores policy still gets the TLS half;
  an image that cannot speak TLS is not deployable (FR-031), rather than a hole every caller must
  know about.

## Out of Scope

- **A service mesh.** No proxy injection, no mesh control plane. The identity is the process's own.
- **Rotating or removing a *supplied* database's credential.** A service that brings its own
  database through `ANKKA_DB_*` keeps the credential its owner configured; the platform can carry
  TLS settings for it but cannot rotate what it did not issue.
- **An identity-bearing service client for Python and TypeScript.** The certificate files are in
  the pod and the sidecar terminates inbound TLS, so a process can *be* called as itself today;
  calling *out* as itself needs the sidecar to proxy or the SDK to speak TLS, and is its own
  feature. Documented as a limitation.
- **Per-user authorization for a service's own callers.** `Acl.Authenticate` with a token the
  service verifies is the fifth zero-trust layer and exists; the platform still provisions no realm,
  client or token check for a service's users, and that limitation stays.
- **Egress policy.** Agents call model providers on the internet and a topic source is the owner's
  Kafka; restricting where a workload may connect *to* is a different question from who may
  connect to it, and is not asked here.
- **Kafka TLS.** The bootstrap address is the descriptor owner's, and so is its transport.
- **The local overlay, the compose Postgres and the local console.** Unchanged, on purpose.
- **The operator's own inbound ports.** It has none.
- **Authentication or authorization at the gateway.** Still routing only; the gateway's identity to
  services is the whole of its new role.
- **Named-caller principals for `ankka-cloud`'s ACLs on the control plane**, which live in that
  repository.
