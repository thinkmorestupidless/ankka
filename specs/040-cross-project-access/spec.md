# Feature Specification: Cross-Project Access — A Project Grants Named Routes and Topics to Named Callers

**Feature Branch**: `040-cross-project-access`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Cross-project communication is necessary, and it should work just like
any machine would be given authentication and authorisation to access a project's APIs, broker topics,
and so on. One model: a principal — a service in another project, or a machine outside ankka — is
granted access to named parts of a project, its routes and the topics it may consume or produce, by
that project. The owning project grants, the default is closed, the broker enforces topic grants, a
revocation takes effect without redeploying either side, and every grant and revocation is recorded."
Clarified 2026-10-08: a machine outside the installation can be granted a topic in this feature, and a
grant to another organization's grantee takes effect only when that organization accepts it.
Reviewed 2026-10-08: owners act, `machine` is reserved, the grants volume is mounted from the first
deploy, gRPC methods are targets, delivery is at least once, one writer per record, and hooks for
features 042 and 043. Clarified again 2026-10-08, before planning: a deleted grantee's grants lapse,
a topic grant carries one right, a cross-project topic reference is checked by the broker alone,
042's hooks are data only here, and the glossary's terms are settled.

## Context

Three things decide today whether one project can reach another, and they disagree.

**HTTP between services is already possible, and the grant lives in code.** Feature 014 made a
project an identity boundary for HTTP, not a network one (FR-018; `docs/reference/limitations.md`,
"A project is not a network boundary for HTTP"). Any ankka workload may open a connection to any
service's HTTP port; the callee reads the caller from the client certificate's
`ankka://<project>/<service>` URI (`Caller.fromCertificate` in
`modules/http/.../Caller.scala`), and its endpoint's ACL decides. `Callers.service(project, name)`
builds a `CallerMatcher.NamedService(Some(project), name)`, and `ServiceClients(project, name)` in
`modules/sdk/.../ServiceClient.scala` addresses the callee by project and name, resolving
`<name>.ankka-<project>.svc.cluster.local` (`HttpServiceClients`). So **a cross-project call is
admitted by caller identity today** — when the callee's author wrote that project and service into
the endpoint's source. Granting, changing or revoking that access is a code change and a redeploy of
the callee. Nothing outside the source records that it exists, nobody but a reader of the code can
list who may call a route, and a revocation in an incident waits on a build. Locally,
`ServiceClients(project, name)` ignores the project and reaches the service of that name, so a
local run cannot tell a cross-project call from a same-project one.

**Topics are closed between projects, by the broker.** Feature 027 gave every service a `KafkaUser`
named `<project>.<service>` with `Read`/`Write`/`Describe` on the `<project>.` topic prefix and `Read`
on its own `ankka.<project>.<service>.` group prefix, rendered by `StrimziRendering`. Its clarification
says grants are a later feature and that a fact another project needs is republished "by a consumer in
the owning project, to a topic the reader's project owns". That workaround does not work: the owning
project's consumer holds no credential for the reader's prefix either. The only path a fact can take
between projects today is HTTP.

**A machine outside the installation has no identity of its own.** Every request from outside arrives
through the gateway and reads as `Caller.Gateway` — "the internet" — whoever sent it. Feature 022 lets
a service verify bearer tokens from issuers it lists, so a service *can* admit a partner's machine by a
token from the partner's own identity provider, but that is per-service configuration, invisible to the
platform, and a different notion of caller from the one every service-to-service ACL reads. The
installation's Keycloak realm has no client with service accounts enabled, and the control plane holds
no Keycloak administration credential by design (feature 007, research R9). A deploy token is a member
of an organization on the control plane and reaches no service. The broker's listener admits only
workloads in `managed-by: ankka` namespaces holding a certificate from the service authority; nothing
outside the cluster reaches it.

The driving case is eitheror's. A shared `payments` project hands each confirmed deposit to the wallet
of the brand project the player belongs to — a call carrying an idempotency key the wallet enforces,
since a granted call is delivered at least once and ankka has no idempotency of its own at an
endpoint, or a message the brand's wallet consumes — and an affiliate platform outside ankka reads each brand's attribution
events, as a stream from the brand's topic or through a feed route the brand serves. Today the first
needs every brand's wallet to name `payments/merchant` in its source, and the second has no path at
all. The affiliate platform may be run by another organization than the brand's, and eitheror's brands
and its shared projects may sit in different organizations, so a grant crosses organizations as often
as it crosses projects.

The decisions this feature makes:

- **The owning project grants, as data, and the default is closed.** A grant is held by the project
  whose route or topic it opens, beside that project's topic declarations on the `Project` entity. It
  names one grantee and exactly what it reaches: a route of one of the project's services by method
  and path template, a gRPC method of one of its services as `service/method`, one of the project's
  declared topics with one right, `consume` or `produce`, or the right to file erasure requests for the
  project's data subjects (feature 042). There are no wildcards: no "any service of project B", no
  "every route of this service", no topic prefix. A grantee with no grant reaches nothing a grant
  would open.
- **A route is grantable only if its author says so.** The endpoint's ACL names a new matcher,
  `Callers.granted`, beside whatever else it admits. A grant on a route that does not name it opens
  nothing, and the project's grant listing says so. This keeps the decision "may anyone outside this
  project ever call this route" in reviewed code and the decision "who, today" in data a member can
  change in an incident. `Callers.service(project, name)` keeps working unchanged, as a grant written
  in code.
- **One notion of caller, two ways to prove it.** A grantee is either a service,
  `service:<project>/<name>`, proven as today by its certificate, or a machine,
  `machine:<organization>/<name>`, proven by a short-lived token. The platform turns a verified
  machine token into a new `Caller.Machine(organization, name)` before the ACL runs, exactly as it
  turns a certificate into `Caller.Service`. An endpoint never verifies a machine's token itself, and
  `Callers.granted` reads both kinds through one rule.
- **The control plane issues machine credentials; Keycloak does not.** A machine is registered on its
  organization, as a deploy token is, and receives a client id and secret shown once and stored as a
  digest. It exchanges them at the control plane for a token by the OAuth client-credentials grant, so
  any standard client library works; the token is signed by a key the control plane holds, lives
  fifteen minutes and names the machine and nothing else — no grant is carried in it. Services verify
  it offline against the control plane's published keys, configured by the operator with no descriptor
  change. This makes the control plane a token issuer, and that has a price the choice carries
  knowingly: it holds a signing key and rotates it, publishes a JWKS every service and the broker
  read, rate-limits its token route per client id, and defends client secrets against guessing —
  256 bits from `SecureRandom`, only a SHA-256 digest stored, exactly as deploy tokens are minted.
  The alternative was a Keycloak client per machine, which needs not the realm's administration
  credential but a client of the control plane's own holding only `manage-clients`; it was declined
  because that is still a standing right to create any client in the realm, held by the one
  component that is designed to hold no Keycloak right at all, and because a machine would then be
  known in two places with two lifecycles.
- **The broker enforces topic grants.** A grant to consume or produce becomes an ACL entry on the
  grantee's own `KafkaUser` — `Read` and `Describe`, or `Write` and `Describe`, on the one qualified
  topic, a literal name and never a prefix. A consumer in project B reading project A's topic keeps its
  group under B's own `ankka.<B>.<service>.` prefix, which its user already holds, so its offsets are
  B's and a grant gives A no view of them. A grantee with no such entry is refused by the broker
  whatever its code says. A machine is the same: the operator renders it a `KafkaUser`,
  `machine.<organization>.<name>`, holding the entries its grants give it and Read on its own group
  prefix, `ankka.machine.<organization>.<name>.`, and nothing else.
- **A machine reaches the broker through the installation's gateway, by TLS passthrough.** An
  installation that turns it on gets a second broker listener, `external`, of Strimzi's `tlsroute`
  type: Strimzi itself makes a bootstrap service and one service per broker node, each advertised
  under its own one-label hostname of the installation's base domain (`broker.<base>`,
  `broker-<n>.<base>`), and one Gateway API `TLSRoute` per hostname attached to the installation's
  one `Gateway`, which gains a TLS listener in passthrough mode on a port of its own, so Envoy
  routes each connection by its SNI and never sees inside it; the broker terminates TLS with a
  certificate from the installation's public issuer, the one the gateway's own certificate comes from,
  so a partner trusts it as it trusts every exposed route. A dedicated load balancer per broker node —
  Strimzi's `loadbalancer` type — was the alternative and is declined: it is one public address and one
  cloud load balancer per node plus one for bootstrap, each added or removed as the broker is resized,
  and every one an entry point outside the gateway, where the installation's address policy, network
  policies and hostnames already live. The listener's `networkPolicyPeers` admit the gateway's proxy
  pods only (they run in `envoy-gateway-system`, `kubernetes.md`), so the listener is unreachable by
  any other path.
- **One credential per machine, on routes and on the broker.** The external listener authenticates by
  SASL `OAUTHBEARER` against the control plane's published keys (Strimzi's `oauth` listener
  authentication, with `simple` authorization unchanged). The partner's Kafka client fetches its token
  itself from the same token route, with the same client id and secret, by the client-credentials
  support standard Kafka clients have carried since Apache Kafka 3.1 (KIP-768), and the token's
  broker-user claim names `machine.<organization>.<name>`, the `KafkaUser` its grants are rendered on.
  Client certificates for machines were the alternative and are declined: they would be a second
  credential per machine with its own issue and rotation, held outside the cluster for months, and the
  broker's custom listener has no revocation list, so a stolen certificate would work until it expired.
  A token lives fifteen minutes, and the listener makes every connection re-authenticate within that
  lifetime, so a deleted machine is off the broker within fifteen minutes; a revoked grant is sooner,
  because the broker checks ACLs on every request.
- **An outside consumer cannot starve the broker.** Every machine's `KafkaUser` carries byte rates — produce
  and fetch bytes per second and a share of request time — from the installation's defaults, which an
  owner may lower or raise for one machine within the installation's ceiling. The external listener
  caps connections per address. Services inside the installation have no byte rate from this feature.
- **A grant to another organization's grantee waits for that organization to accept it.** A grant
  whose grantee belongs to the granting project's own organization takes effect at once. One whose
  grantee belongs to another organization — a service of a project it owns, or a machine registered on
  it — is *pending*, opens nothing, and is shown to that organization; an owner of that organization
  accepts or declines it. The
  grantor may withdraw a pending grant and revoke an accepted one without asking, and the grantee may
  relinquish an accepted one without asking. A project therefore cannot appear in another's audit trail
  as holding access it never agreed to, and either side can end access alone.
- **The route feed stays a choice.** A partner that cannot or will not run a Kafka client reads the same
  facts through a route the owning service serves — a cursor-paged feed over its own view, or a webhook
  its consumer sends — granted as any other route. Exposing the broker does not replace it.
- **Grants reach running services without a redeploy.** The operator renders the grants that name a
  service's routes into that service's namespace, and the runtime re-reads them on change, as
  `RotatingTls` re-reads certificates by modification time. That only works for a file that is
  mounted, and a volume can be added to a pod only when the pod is made; so every service pod and
  every sidecar carries a grants volume from its first deploy, empty until a grant names it. A grant
  takes effect, and a revocation stops a grantee, within two minutes on both HTTP and the broker,
  with neither side redeployed. A revoked machine's tokens stop working at the next refresh of the
  grants, not at their expiry. A revocation also closes the SSE streams and sockets the grant admitted.
  The grants are rendered to the granting service and to any platform component that admits by
  grant — feature 042's keyring is the first.
- **Every grant is attributed and kept, on both sides, with one writer.** Granting, withdrawing and
  revoking are commands on the granting `Project` carrying the owner's `Attribution`; accepting,
  declining and relinquishing are commands an owner of the grantee organization issues through an
  endpoint that checks that right and then applies them to the granting `Project`. The granting
  project's events are the one record written by a command. The grantee side's copy — on the grantee
  `Project` for a service grantee, on the `Organization` for a machine — is derived from those
  events by a consumer, never written by the endpoint beside them, so no change is half-recorded.
  Each side's history then answers "who could reach what, agreed by whom, between which times"
  without the other's. No secret value is in either.

## Clarifications

### Session 2026-10-08

- Q: Are cross-project service calls admitted by caller identity today? → A: Yes, over HTTP. A
  service calls another project's service with `ServiceClients(project, name)`, the network admits
  it, and the callee admits it if an endpoint's ACL names `Callers.service(project, name)`. The access
  lives only in the callee's source. Topics are closed between projects by the broker, and machines
  outside the installation have no identity but the gateway.
- Q: Can a machine outside the installation be granted a topic? → A: Yes, in this feature. An
  installation may expose its broker through the gateway by TLS passthrough; a machine authenticates
  to it with the same client id and secret it uses for routes (SASL `OAUTHBEARER`), the broker enforces
  its grants with the same ACL entries a service's grants give, its groups are under its own prefix,
  and a byte rate bounds it. A route feed or webhook remains an alternative for partners without a Kafka
  client.
- Q: May a project grant to a principal of another organization? → A: Yes. A principal is identified
  by its certificate or its token, not by membership, and the granting project chose to name it. The
  grantee's organization learns only what was granted to it.
- Q: Should a grant to another organization's principal require that organization to accept it before
  it takes effect? → A: Yes. It is pending until an owner of the grantee organization accepts it; it
  may be declined, the
  grantor may withdraw it while pending, the grantor revokes and the grantee relinquishes without the
  other's consent, and every change is recorded on both sides. A grant within one organization takes
  effect at once.
- Q: Should machines reach the broker through the gateway or a load balancer of their own? → A:
  Through the gateway, by TLS passthrough with one `TLSRoute` per broker hostname: one public entry
  point, the installation's hostnames and issuer, and no cloud load balancer per broker node.
- Q: Should a machine authenticate to the broker by client certificate or by token? → A: By token
  (SASL `OAUTHBEARER`), the same credential it uses for routes; no certificate is issued to anything
  outside the cluster.

### Session 2026-10-08 (review)

- Q: Who grants, accepts and registers machines? The first draft said "a member who manages the
  project", and no such right exists: the control plane's `Organization` holds members with
  `Role.Owner` or `Role.Member` and nothing per project. → A: An owner of the organization grants,
  withdraws, revokes, accepts, declines, relinquishes, registers and deletes machines and sets their
  byte rates; a member lists. A deploy token does none of it.
- Q: `ankka.machine.<org>.<name>.` is a machine's group prefix, and `ankka.<project>.<service>.` a
  service's. A project called `machine` with a service named after an organization would hold read
  on every machine's groups of that organization. → A: `machine` is a reserved project id, beside
  `platform` and `local`, for the same reason `local` is: it is part of a name on the broker.
- Q: How does a grant reach a running service with no redeploy, when a volume can only be mounted
  at pod creation? → A: Every service pod and sidecar gets a grants volume from its first deploy,
  empty until needed, re-read by modification time. Services deployed before this feature need one
  rollout to gain the mount, and the operator reports which still lack it.
- Q: Can a gRPC method be granted? → A: Yes. A target may be a gRPC method, `service/method`; the
  same `Callers.granted` matcher runs in the gRPC binding's admission, which evaluates the same
  `CallerMatcher`.
- Q: Does a granted call carry any delivery guarantee? → A: At least once, as every call does. ankka
  has no idempotency support at an endpoint; the callee makes a retried command safe, as
  `docs/concepts/consistency.md` already asks. eitheror's wallet enforces the deposit's idempotency
  key; the grant does not.
- Q: The first draft had the grantee's endpoint apply a change to the granting project and record it
  on the grantee entity too — two entities, no transaction. → A: The granting project's events are
  the only command-written record; the grantee side's copy is derived from them by a consumer.
- Q: What happens to an SSE stream or a socket a grant admitted when the grant is revoked? → A: It is
  closed within the same bound as any other revocation.
- Q: How does a machine's token reach a route whose ACL is `Acl.Authenticate`, through the gateway?
  → A: As a bearer token in `Authorization`; `ankka-auth-oidc` verifies it against the control plane's
  JWKS as it verifies any issuer's, and the caller becomes `Caller.Machine` before the ACL runs.
- Q: Is a per-listener connection cap real? → A: Kafka's per-address cap is per broker, not per
  listener, and byte rates are per client id; the requirement says what Kafka can do.
- Q: What does the control plane take on by issuing tokens, and what was the honest alternative? →
  A: Key custody and rotation, JWKS publication, a rate limit on the token route per client id, and
  secrets minted and digested as deploy tokens are. The alternative was a Keycloak client holding
  `manage-clients` only — not the realm administration credential — declined because it is still a
  standing right to create clients, held by the component designed to hold none.
- Q: What does feature 042 need from a grant? → A: Two things this feature defines without designing
  042: a topic grant may carry `decrypt`, meaning the grantee may decrypt personal fields in what it
  reads under 042's rules; and a project may grant a grantee the `erasure` right, to file erasure
  requests for its data subjects. Grants are rendered to platform components that admit by grant,
  the keyring first. A topic grant's listing on the grantee side shows the topic's retention
  settings (feature 043).
- Q: Which SDK files carry the new matcher? → A: `sdks/python/src/ankka/endpoint.py`,
  `sdks/typescript/src/routes.ts`, the Rust crate's ACL types, and the matcher's wire form in
  `discovery.proto`, with the conformance suite covering `Callers.granted` on every host.

### Session 2026-10-08 (clarify)

- Q: What happens to a grant when its grantee — the project or the registered machine — is deleted,
  when no owner of the granting project acted? → A: It ends in a terminal state of its own, `lapsed`,
  as an event on the granting project carrying the attribution of the owner who deleted the grantee.
  A machine's name may be registered again; the new machine holds nothing, since an ended grant is
  never reopened.
- Q: Is a topic grant one record per topic carrying a set of rights, or one record per right? → A:
  One per right. The target is the topic and one right, `consume` or `produce`; `decrypt` qualifies a
  `consume` grant. A grantee that may both consume and produce holds two grants, each accepted,
  revoked and recorded on its own; no command changes a grant's rights.
- Q: Is a descriptor that references another project's topic checked against grants at deploy time,
  or only by the broker at runtime? → A: Only by the broker. The deploy is accepted whatever the
  grants say, the service's status reports each cross-project topic reference as granted or not and
  why, and the consumer retries the broker's refusal until the grant is in effect.
- Q: How far does this feature take feature 042's hooks, the `decrypt` attribute and the `erasure`
  right? → A: As data only. They are accepted, listed, recorded and rendered into the grants file,
  with a scenario proving each is held and listed; nothing here enforces them, since no component
  admits by them until 042's keyring.
- Q: Do the twenty proposed glossary terms for cross-project access stand as written? → A: Yes,
  settled: grant, grantor, grantee, target, consume, produce, in effect, accept, decline, withdraw,
  relinquish, granted caller, grantable, registered machine, client id, client secret, machine
  token, token route, byte rate and throttled, with `lapsed` added and `target` and `in effect`
  amended by this session's answers.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service in one project calls a route another project granted it (Priority: P1)

An owner of the `spinvibe` project's organization grants `service:payments/merchant` the route
`POST /v1/wallets/{player}/{currency}/deposits` of its `wallet` service, whose ACL names
`Callers.granted`. Within two minutes `merchant` calls the route with an idempotency key, which the
wallet enforces, and is served. `payments/psp-gateway`, which holds no grant, is refused with 403. The owner revokes the grant
and within two minutes `merchant` is refused, with neither service redeployed.

**Why this priority**: This is the one cross-project path eitheror's money moves on, and today it
needs a code change in every brand to open or close.

**Independent Test**: In the k3s suite, deploy the sample as `payments/merchant`, `payments/other`
and `spinvibe/wallet` with a route admitting `Callers.granted`. Assert `merchant` is refused, grant,
assert served, assert `other` refused, revoke, assert refused, and assert no pod restarted.

**Acceptance Scenarios**:

- added `features/cross-project/route-grants.feature`: a service of another project is refused a route nobody granted it
- added `features/cross-project/route-grants.feature`: a service of another project is served a route its project granted it
- added `features/cross-project/route-grants.feature`: a grant to one service admits no other service of its project
- added `features/cross-project/route-grants.feature`: a revoked grant refuses its service without a redeploy
- added `features/cross-project/route-grants.feature`: a grant on a route whose ACL does not name granted callers opens nothing
- added `features/cross-project/route-grants.feature`: a grant names the route by method and path template and opens no other route of the service
- added `features/cross-project/route-grants.feature`: a grant on a gRPC method admits that method and no other
- added `features/cross-project/route-grants.feature`: a revoked grant closes the streams and sockets it admitted
- added `features/cross-project/route-grants.feature`: a service deployed before the feature gains its grants volume on its next rollout

---

### User Story 2 - A service consumes or produces another project's topic, and the broker enforces it (Priority: P1)

An owner of `spinvibe`'s organization grants `service:affiliates-hub/attribution` consume on `spinvibe`'s declared
topic `casino.players`, and `service:payments/notifier` produce on `spinvibe`'s
`payments.deposits`. The `attribution` consumer subscribes to `spinvibe`'s topic by project and
name, under a group in its own project, and reads every message; `notifier` publishes to
`spinvibe`'s topic. Without the grants, the broker refuses both. After a revocation the broker
refuses the next fetch or send.

**Why this priority**: Without it, a fact crosses projects only as a synchronous call, and every
reader of a stream needs the owner to write an HTTP feed for it.

**Independent Test**: In the k3s broker suite, with two projects on one Strimzi broker, assert a
consumer of project B is refused project A's topic, grant consume, assert it reads, assert its group
is under B's prefix, revoke, assert the broker refuses. Repeat for produce. A probe holding B's
certificate asserts the refusal is the broker's, not the SDK's.

**Acceptance Scenarios**:

- added `features/cross-project/topic-grants.feature`: a service is refused another project's topic nobody granted it
- added `features/cross-project/topic-grants.feature`: a service reads another project's topic its project granted it to consume
- added `features/cross-project/topic-grants.feature`: a service publishes to another project's topic its project granted it to produce
- added `features/cross-project/topic-grants.feature`: a grant to consume does not let a service publish
- added `features/cross-project/topic-grants.feature`: a consumer of another project's topic keeps its group in its own project
- added `features/cross-project/topic-grants.feature`: a revoked topic grant is refused by the broker without a redeploy
- added `features/cross-project/topic-grants.feature`: a topic the project has not declared cannot be granted

---

### User Story 3 - A machine outside the installation calls a granted route with a token (Priority: P1)

An owner of the `eitheror` organization registers a machine, `machine:eitheror/affiliate-network`,
and is shown its client id and secret once. An owner of `spinvibe`'s organization grants the machine
`GET /v1/affiliates/attribution` of its `affiliates` service. The machine asks the control plane for
a token with the client-credentials grant and calls the route through the gateway with it; the
handler reads `Caller.Machine("eitheror", "affiliate-network")`. Without the grant, or with no
token, the route refuses it. The owner deletes the machine, and the control plane issues it no
more tokens; an owner revokes the grant, and its current token is refused within two minutes.

**Why this priority**: The affiliate platform, a PSP's reconciliation job and any partner integration
are machines, and today each is "the internet" to every service. A route is also the path for a
partner with no Kafka client: the brand's service serves a cursor-paged feed over its own view of the
facts the topic carries (User Story 5), or its consumer sends them to the partner as webhooks.

**Independent Test**: In the k3s suite with the gateway, register a machine, take a token with
`curl` against the control plane's token route, call a granted route through the gateway and
assert 200 and the caller the handler reports; call an ungranted route and assert 403; revoke and
assert 403 within the bound while the token is unexpired.

**Acceptance Scenarios**:

- added `features/cross-project/machines.feature`: a registered machine is shown its secret once and it is never shown again
- added `features/cross-project/machines.feature`: a machine takes a token from the control plane with its client id and secret
- added `features/cross-project/machines.feature`: a machine is served a route its grant names, as itself
- added `features/cross-project/machines.feature`: a machine is refused a route nobody granted it
- added `features/cross-project/machines.feature`: a request from the internet with no token is not a machine
- added `features/cross-project/machines.feature`: a token from another issuer does not make its bearer a machine
- added `features/cross-project/machines.feature`: a revoked grant refuses a machine whose token has not expired
- added `features/cross-project/machines.feature`: a deleted machine is given no token
- added `features/cross-project/machines.feature`: a machine's token at a route that authenticates makes it a principal and a machine caller
- added `features/cross-project/machines.feature`: a client id asking for tokens faster than the limit is refused for a while

---

### User Story 4 - Members see who may reach their project and what their project may reach (Priority: P2)

A member of `spinvibe` lists the project's grants: each grantee, what it reaches, who granted it
and when, and whether it is in effect — or why not: pending the grantee's acceptance, declined, a
route not yet seen, a route whose ACL does not name granted callers, a topic not declared, the broker
not exposed to a machine. A member of `payments` lists the grants its services hold
from other projects. Every grant and revocation appears in the project's history with the owner who
made it.

**Why this priority**: A regulator's question "who could move money into this wallet on that date"
must be answerable from the platform's record, not from reading every repository.

**Independent Test**: In the control plane suite, grant, revoke and grant again, and assert the
listing, the grantee's listing and the history agree, with attribution on each event and no
credential anywhere in the journal.

**Acceptance Scenarios**:

- added `features/cross-project/listing.feature`: a project lists who it has granted what, and who granted it
- added `features/cross-project/listing.feature`: a project lists what its services and machines were granted by other projects
- added `features/cross-project/listing.feature`: a grant that opens nothing yet says why
- added `features/cross-project/listing.feature`: a topic grant tells its grantee the topic's retention
- added `features/cross-project/listing.feature`: a member of neither project sees no grant between them
- added `features/cross-project/listing.feature`: granting and revoking are recorded with the owner who did it
- added `features/cross-project/listing.feature`: a grant that allows decryption and an erasure grant are held and listed, and nothing enforces them yet

---

### User Story 5 - An affiliate platform outside ankka consumes a brand's attribution topic (Priority: P2)

The installation exposes its broker. An owner of the `affiliates` organization registers
`machine:affiliates/network`, which runs an ordinary Kafka client outside the installation. A member
of `spinvibe` grants it consume on `spinvibe`'s declared topic `affiliates.attribution`; the grant
crosses organizations, so it is pending until an owner of `affiliates` accepts it. The machine's
client is configured with the broker's bootstrap hostname, its client id and secret, and the control
plane's token route; it subscribes to `spinvibe.affiliates.attribution` under the group
`ankka.machine.affiliates.network.attribution`, reads every message from the earliest one the broker
retains, and finds each CloudEvents attribute in the headers. It is refused every other topic and
publishing to this one. An owner of `spinvibe`'s organization revokes the grant, and its next fetch is refused. A
second partner with no Kafka client is granted the brand's feed route instead and reads the same
facts over HTTP.

**Why this priority**: Attribution facts are a stream, and an affiliate network consuming several
brands should not need each brand to write and operate a feed for it. It is P2 because the route path
of User Story 3 already serves the case, more slowly.

**Independent Test**: In the k3s broker suite with the gateway and the external listener on, register
a machine, grant and accept consume on one topic, and from the host — outside every network policy —
run a stock Apache Kafka client configured only with the bootstrap hostname, the gateway's CA, the
client id and secret and the token route. Assert it reads what a service published, that its
group is under its prefix, that it is refused a second topic and a produce, that revocation refuses
its next fetch within two minutes, and that a connection with no token or another issuer's token is
refused at authentication.

**Acceptance Scenarios**:

- added `features/cross-project/machine-topics.feature`: a machine with an accepted consume grant reads a project's topic from outside the installation
- added `features/cross-project/machine-topics.feature`: a machine is refused a topic nobody granted it
- added `features/cross-project/machine-topics.feature`: a machine granted consume cannot publish to the topic
- added `features/cross-project/machine-topics.feature`: a machine with an accepted produce grant publishes to a project's topic
- added `features/cross-project/machine-topics.feature`: a machine's consumer group is under its own prefix and no other group is open to it
- added `features/cross-project/machine-topics.feature`: a revoked grant is refused by the broker on the machine's next request
- added `features/cross-project/machine-topics.feature`: a deleted machine is disconnected by the time its token would have expired
- added `features/cross-project/machine-topics.feature`: a connection to the external listener with no token, or a token from another issuer, is refused
- added `features/cross-project/machine-topics.feature`: a machine that exceeds its fetch quota is throttled, and services on the broker are not
- added `features/cross-project/machine-topics.feature`: an installation that has not exposed its broker reports a machine's topic grant as not in effect

---

### User Story 6 - A grant to another organization takes effect only when that organization accepts it (Priority: P2)

An owner of the `eitheror` organization, which holds `spinvibe`, grants `machine:affiliates/network` consume on
a topic. The grant is listed on `spinvibe` as pending and opens nothing; an owner of `affiliates` sees
it among the grants offered to the organization, naming `spinvibe` and the topic, and accepts it, and
it takes effect within two minutes. Another grant is declined and never
opens anything. The grantor withdraws a pending grant before it is answered. Later an owner of
`affiliates` relinquishes an accepted grant and it stops within two minutes, without anyone in
`spinvibe` acting. Each of these is in both organizations' history with the owner who did it. A grant
from `spinvibe` to `payments/merchant`, in the same organization, takes effect at once with no
acceptance.

**Why this priority**: eitheror's brands, its shared projects and its partners will not all share one
organization, and an organization must not be recorded as holding access — to player-linked data or
money routes — that it never agreed to hold.

**Independent Test**: In the control plane suite with two organizations, grant across them and assert
pending opens nothing (the operator renders no ACL entry and no route grant), accept and assert it
renders, decline, withdraw and relinquish each, and assert each change is an attributed event on both
the granting project and the grantee side, and that a member who is not an owner on the grantee side
— or a deploy token — cannot accept.

**Acceptance Scenarios**:

- added `features/cross-project/acceptance.feature`: a grant to another organization's principal is pending and opens nothing
- added `features/cross-project/acceptance.feature`: an owner of the grantee organization accepts a pending grant and it takes effect
- added `features/cross-project/acceptance.feature`: a declined grant never takes effect
- added `features/cross-project/acceptance.feature`: the grantor withdraws a pending grant
- added `features/cross-project/acceptance.feature`: the grantee relinquishes an accepted grant without the grantor
- added `features/cross-project/acceptance.feature`: the grantor revokes an accepted grant without the grantee
- added `features/cross-project/acceptance.feature`: a member who is not an owner cannot accept, nor can a deploy token
- added `features/cross-project/acceptance.feature`: a grant within one organization takes effect without acceptance
- added `features/cross-project/acceptance.feature`: every change to a grant is recorded on both sides with the owner who made it
- added `features/cross-project/acceptance.feature`: deleting a registered machine lapses every grant naming it, and a machine registered again under its name holds nothing

---

### Edge Cases

- **A grant names a route the service does not have yet.** It is accepted and reported as not in
  effect, "route not seen", until the service's topology lists the route; deploy order between
  projects then does not matter. Topics are different: the topic is on the same entity as the grant,
  so a grant on an undeclared topic is refused at once.
- **The grantee project or service does not exist.** A grant to a project id that was never created
  is refused (an endpoint check, since it reads another entity). A service name is a deployment
  target, not a tenancy boundary, so a grant to a service not yet deployed is accepted.
- **A project is deleted.** Its grants stop being rendered and the grantee's `KafkaUser` loses the
  entries; a project id is never reused, so a grant cannot silently pass to a new owner. Every grant
  held by a deleted project's services lapses with it: an event on each granting project, attributed
  to the owner who deleted the project, so both histories still name a person.
- **A registered machine is deleted.** Every grant naming it lapses, attributed to the owner who
  deleted it, and its `KafkaUser` keeps no topic entry. Its name may be registered again: the new
  machine is a new grantee and holds nothing, because an ended grant is never reopened.
- **A service is deployed as the name a grant expected, in the grantee project.** It holds the grant;
  that is what naming a service means, and the grantee's members decide what is deployed under a name.
- **The same route is granted twice to one grantee.** The second is a no-op; a grant is identified
  by grantee and target, so a listing never shows duplicates. `consume` and `produce` on one topic
  are two targets, so two grants, and a listing shows both.
- **A machine token arrives over a service-to-service connection.** The certificate decides: a
  request carrying a service certificate is that service, and a bearer token in it is a `Principal`
  for `Acl.Authenticate`, never a caller.
- **A machine token arrives through the gateway at a route whose ACL is `Acl.Authenticate`.** The
  token is verified as any listed issuer's is, by `ankka-auth-oidc` against the control plane's JWKS;
  the request carries a `Principal` whose subject is the machine, and the caller is `Caller.Machine`,
  so a route may admit by either.
- **A grant is revoked while a stream it admitted is open.** The SSE stream or socket is closed
  within the bound; the client's reconnect is refused.
- **A service was deployed before this feature and has no grants volume.** A grant naming its routes
  is accepted and reported as not in effect, "rollout needed", until the operator has applied a
  Deployment carrying the mount and said so in the service's status; `services get` shows which
  state a service is in.
- **A client id asks for tokens too often.** The token route refuses it for a period, as a login
  route does; a partner's client that fetches a token per request, instead of per lifetime, hits it.
- **A local run.** Every caller is `Caller.Local` and `Callers.granted` admits it, as every
  caller-naming ACL does; the testkit can present a chosen `Caller.Machine` or `Caller.Service` and a
  set of grants, so a route's grant behaviour is testable without a cluster. `ServiceClients(project,
  name)` reaching the local service of that name is unchanged and documented.
- **The control plane is down.** No new machine token is issued; tokens already issued verify
  offline until they expire, and grants already rendered keep applying. Changes wait.
- **A web-hosted service.** Its routes are served by its own program behind the proxy, which has no
  ankka ACL, so its routes cannot be granted and a grant naming one is reported as not grantable.
- **A service references another project's topic before its grant is in effect.** The deploy is
  accepted; deploy order between projects does not matter for topics any more than for routes. The
  service's status reports the reference as not granted and why, the broker refuses the subscription
  or the publish, and the consumer retries until the grant is in effect, then reads from its declared
  start.
- **A grant to consume a compacted or very old topic.** The consumer's start position is its own
  declaration (feature 024); a grant gives access, not a position. A machine's start position is its
  Kafka client's `auto.offset.reset` and its own committed offsets.
- **A pending grant is never answered.** It stays pending and opens nothing; the grantor sees it as
  pending and may withdraw it. It has no expiry in this feature.
- **A grant is declined, withdrawn, revoked or relinquished, and the grantor grants the same again.**
  It is a new grant with its own history; a cross-organization one is pending again. An ended grant is
  never reopened.
- **The grantee project, or the machine, moves to another organization, or the granting project
  does.** Projects and machines do not move between organizations today; if that is ever built, it
  must end or re-offer every cross-organization grant it affects.
- **A machine's token expires mid-connection.** The external listener makes the client re-authenticate
  within the token's lifetime; a client that cannot fetch a new token — because the machine was
  deleted or the control plane is down — is disconnected at that point. A client that can carries on
  without reconnecting.
- **The broker is resized.** A new broker node needs its hostname and `TLSRoute`; Strimzi's
  `tlsroute` listener renders one per node of the node pool from its host template, and a node that
  has none is unreachable from outside, not reachable by a wrong name.
- **A machine is granted a topic on an installation that does not expose its broker.** The grant is
  accepted, renders its `KafkaUser` entries, and is reported as not in effect, "broker not exposed";
  the same machine's route grants are unaffected.
- **A machine produces malformed messages to a topic it may produce to.** The broker does not inspect
  bodies; the topic's consumers handle a message they cannot decode as they handle any other (feature
  024). A produce grant to a machine is a decision about trust that the granting project makes.
- **An address opens many connections to the external listener.** Connections beyond the listener's
  per-address cap are refused before authentication.

## Requirements *(mandatory)*

### Functional Requirements

**Grants**

- **FR-001**: A project MUST hold grants, each naming one grantee — `service:<project>/<name>` or
  `machine:<organization>/<name>` — and one target: a route of one of its services by service name,
  method and path template; a gRPC method of one of its services as `service/method`; one of its
  declared topics with one right, `consume` or `produce`, a `consume` grant optionally carrying `decrypt` (feature 042); or
  the `erasure` right over the project's data subjects (feature 042).
- **FR-002**: There MUST be no form of grant that names more than one grantee or more than one
  target. A grantee with no grant MUST reach nothing a grant would have opened.
- **FR-003**: Only an owner of the granting project's organization MUST be able to grant, withdraw or
  revoke; a member MAY list. A deploy token MUST NOT be able to grant, as it cannot manage members
  or tokens.
- **FR-004**: A grant on an undeclared topic, or to a project id that was never created, MUST be
  refused with the reason. A grant on a route MUST be accepted whether or not the route has been seen.
- **FR-005**: A project's own services MUST NOT be grantable to its own routes or topics; inside a
  project the existing forms (`Callers.service(name)`, `Callers.anyInProject`) apply.
- **FR-006**: `machine` MUST be a reserved project id, beside `platform` and `local`, with
  `ReservedProjectIdsSuite` holding the list.

**Routes and gRPC methods**

- **FR-007**: The SDK's caller vocabulary MUST gain `Callers.granted`, which admits a caller that
  holds a grant naming this route or gRPC method of this service, in Scala, Python
  (`sdks/python/src/ankka/endpoint.py`), TypeScript (`sdks/typescript/src/routes.ts`) and Rust, with
  its wire form in `discovery.proto` and the conformance suite covering it on every host. The gRPC
  binding's admission MUST evaluate it as the HTTP server does.
- **FR-008**: A grant on a route whose ACL does not name `Callers.granted` MUST open nothing and MUST
  be reported as not grantable.
- **FR-009**: A refusal MUST be a 403 recorded as a refusal, and its body MUST NOT say what grants
  exist, as feature 014's refusals do not.
- **FR-010**: Every service pod and every sidecar MUST carry a grants volume from its first deploy,
  empty until a grant names the service, re-read on change by modification time. The grants that
  name a service's routes MUST reach its running instances without a redeploy, and a change MUST take
  effect within two minutes. A service whose running Deployment the operator has not yet rendered
  with the mount MUST have its grants reported as not in effect, "rollout needed", until the operator
  reports the mount on the service's status, which `services get` MUST show.
- **FR-011**: A revoked, withdrawn, declined or relinquished grant MUST close, within the bound of
  FR-010, every SSE stream and socket it admitted.
- **FR-012**: A granted call is delivered at least once; the platform MUST NOT claim idempotent
  delivery, and the documentation of grants MUST say that a retried command is the callee's to make
  safe.

**Machines**

- **FR-013**: An owner MUST be able to register a machine on an organization and be shown its client
  id and secret once; the secret MUST be 256 bits from `SecureRandom` and the control plane MUST keep
  only its SHA-256 digest, as it keeps deploy tokens; an owner MUST be able to delete a machine.
- **FR-014**: The control plane MUST issue a machine a token for its client id and secret by the OAuth
  2.0 client-credentials grant, signed by a key only the control plane holds, naming the machine, with
  a lifetime of fifteen minutes, and MUST publish the keys that verify it as a JWKS. It MUST be able to
  rotate the signing key without a token in flight failing, and MUST limit token requests per client
  id, refusing a client that exceeds the limit for a period.
- **FR-015**: A request through the gateway carrying a valid machine token MUST arrive at the handler
  as `Caller.Machine(organization, name)`; one with no token or any other token MUST arrive as
  `Caller.Gateway` exactly as today. At a route whose ACL is `Acl.Authenticate`, the same token MUST
  also yield a `Principal` whose subject is the machine, verified by `ankka-auth-oidc` against the
  control plane's JWKS as any listed issuer's token is.
- **FR-016**: A service MUST verify machine tokens offline, configured by the platform, with no
  variable in its descriptor.
- **FR-017**: A machine whose grant is revoked MUST be refused within the bound of FR-010, whether or
  not its token has expired.

**Topics**

- **FR-018**: A grant to consume MUST give the grantee's broker user — the service's `KafkaUser`, or
  the machine's — read and describe on the one qualified topic, and a grant to produce write and
  describe; no grant MUST ever add a prefix entry or an entry for a consumer group.
- **FR-019**: The SDK MUST let a consumer subscribe to, and a component publish to, a topic of another
  project by project and name, and the consumer's group MUST be named under its own project as
  feature 024 names it. A descriptor referencing another project's topic MUST be accepted whatever
  the grants say; the service's status MUST report each such reference as granted or not, and why
  (no grant, pending, ended), beside its undeclared topics; and the broker MUST be the only thing that
  refuses the subscription or the publish until the grant is in effect, which the consumer retries
  as it retries any refusal of the broker's.
- **FR-020**: A revoked topic grant MUST be refused by the broker within the bound of FR-010, without
  a redeploy of either service.

**Machines on the broker**

- **FR-021**: An installation MUST be able to expose its broker to machines, and MUST NOT expose it
  unless it says so. Exposed, the broker MUST have an `external` listener reachable only from the
  gateway's proxy pods, and the gateway MUST route a TLS passthrough connection by SNI to the
  bootstrap and to each broker node under one-label hostnames of the installation's base domain, one
  per node of the node pool.
- **FR-022**: The external listener MUST present a certificate from the installation's public issuer
  covering every hostname it advertises, and MUST authenticate by SASL `OAUTHBEARER` with the tokens
  the control plane issues machines, verified against its published keys; it MUST refuse any other
  token and any connection without one, and MUST make a connection re-authenticate within its token's
  lifetime.
- **FR-023**: A machine MUST use one credential, its client id and secret, for routes and for the
  broker. No certificate MUST be issued to a machine.
- **FR-024**: A machine named by any topic grant MUST have a `KafkaUser`, `machine.<organization>.<name>`,
  rendered by the operator with the entries its grants in effect give it (FR-018) and read on the
  group prefix `ankka.machine.<organization>.<name>.`, and with no credential of its own. A machine
  whose grants all end, or that is deleted, MUST keep its `KafkaUser` with no topic entry, as a
  service's is never removed.
- **FR-025**: Every machine's `KafkaUser` MUST carry produce and fetch byte rates and a request
  percentage from the installation's defaults, applied per machine; an owner of the machine's
  organization MAY set one machine's byte rates within the installation's ceiling. The broker MUST cap
  connections per client address and the rate at which the external listener accepts new
  connections, both installation settings with shipped defaults; Kafka applies the per-address cap
  per broker, not per listener.
- **FR-026**: A deleted machine MUST be unable to produce or fetch once its current token's lifetime
  has passed; a relinquished topic grant ends as a revoked one does (FR-020).

**Grants across organizations**

- **FR-027**: A grant whose grantee belongs to the granting project's organization MUST take effect
  when made. A grant whose grantee belongs to another organization MUST be pending and MUST open
  nothing — no route admission, no broker entry — until accepted.
- **FR-028**: A pending grant MUST be shown to the grantee organization, naming the granting project
  and organization and the target. Only an owner of the grantee organization MUST be able to accept,
  decline or relinquish it; a member MAY list it; a deploy token MUST NOT act on it.
- **FR-029**: The grantor MUST be able to withdraw a pending grant and revoke an accepted one, and the
  grantee MUST be able to relinquish an accepted one, each without the other side's consent, each
  taking effect within the bound of FR-010. An ended grant MUST NOT be reopened; granting again MUST
  make a new grant.

**Record**

- **FR-030**: Granting, withdrawing, revoking, accepting, declining and relinquishing MUST each be an
  event on the granting project carrying the owner's attribution, and that MUST be the only record a
  command writes. The grantee side's copy — on the grantee project for a service grantee, on the
  organization for a machine — MUST be derived from those events by a consumer, with the owner and
  the granting project. No event MUST hold a credential. The deletion of a grantee project or of a
  registered machine MUST lapse every grant naming it: a terminal state of its own, `lapsed`, written
  on the granting project with the attribution of the owner who deleted the grantee.
- **FR-031**: A project's members MUST be able to list its grants with their state, and the reason a
  grant is not in effect; a grantee project's members MUST be able to list the grants its services
  hold and are offered, and an organization's members the grants its machines hold and are offered.
  A topic grant in the grantee's listing MUST show the topic's retention, cleanup and copies
  (feature 043).
- **FR-032**: Grants MUST be rendered not only to the granting service but to every platform component
  that admits by grant, so that feature 042's keyring can admit a `decrypt` or `erasure` grant by the
  same record. This feature MUST accept, list, record and render `decrypt` and `erasure` grants as
  data, and MUST NOT enforce either: no component admits by them until feature 042 adds the keyring.
- **FR-033**: The CLI, the control plane's routes and the console MUST offer grant, withdraw, revoke,
  accept, decline, relinquish and list, and machine registration, deletion and byte rates; the reference
  pages MUST be regenerated; `docs/reference/limitations.md` MUST drop "no grant that lets a service of
  one project read or publish to another project's topic", and the topics guide MUST document reading
  a granted topic from outside the installation with a stock Kafka client.

### Key Entities

- **Grantee**: who a grant names. A service, `service:<project>/<name>`, proven by its certificate;
  or a machine, `machine:<organization>/<name>`, proven by a token the control plane issued.
- **Grant**: held by the granting project. One grantee, one target, who granted it and when, for
  a topic target whether it carries `decrypt`, and its lifecycle: `pending` (another organization's grantee, not yet answered) → `accepted` or
  `declined`; `pending` → `withdrawn`; `accepted` → `revoked` or `relinquished`; `pending` or
  `accepted` → `lapsed` when the grantee is deleted. A grant within one organization starts
  `accepted`. Only an `accepted` grant opens anything. Identified while live by grantee and target;
  an ended grant is history, and granting again makes a new one.
- **Target**: a route (service name, method, path template), a gRPC method (service name,
  `service/method`), a declared topic with one right, `consume` or `produce`, a `consume` grant optionally carrying `decrypt`, or
  the project's `erasure` right.
- **Machine**: registered on an organization by an owner. A name, a client id, a SHA-256 digest of
  its 256-bit secret, who registered it, and its byte rates when they differ from the installation's defaults. Holds no
  grant itself; grants name it. On the broker it is the `KafkaUser` `machine.<organization>.<name>`,
  which holds no credential. Deleting it lapses every grant naming it; its name may be registered
  again, as a new grantee.
- **Grant record on the grantee side**: the copy of each change to a grant kept on the grantee project
  or organization, derived by a consumer from the granting project's events — what changed, who
  changed it, the granting project, the target.
- **External broker listener**: the installation's choice to expose its broker; the bootstrap and
  per-node hostnames, the passthrough routes, the listener's certificate, the per-address connection
  cap and the default and ceiling machine byte rates.
- **Grant state**: in effect, or not and why — pending acceptance, route not seen, route not
  grantable, rollout needed, broker not exposed, or ended: declined, withdrawn, revoked,
  relinquished or lapsed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service of another project is refused, served after a grant, and refused after a
  revocation, with no pod of either project restarted, each change observed within two minutes.
- **SC-002**: A service holding a consume grant on one topic of another project is refused by the
  broker — checked with a probe holding its certificate, not through the SDK — for every other topic
  of that project, and for publishing to the granted one.
- **SC-003**: A machine reaches exactly the routes granted to it through the gateway, and a request
  with no token or a token from any other issuer reads as the internet.
- **SC-004**: After a revocation, a machine holding an unexpired token is refused within two minutes.
- **SC-005**: The control plane's journal holds no machine secret and no token;
  `EventCompatibilitySuite` asserts the absence.
- **SC-006**: For any route or topic, the platform answers "who could reach this, granted by whom,
  between which times" from the granting project's history alone.
- **SC-007**: eitheror's deposit hand-off, `payments/merchant` calling a brand's wallet, works with
  no brand-specific code in `merchant` beyond the brand's project id, and with no mention of
  `payments` in the wallet's source beyond `Callers.granted`; a retried hand-off is applied once,
  because the wallet enforces the idempotency key, not because the platform did.
- **SC-012**: A grant on a gRPC method admits that method through the gRPC binding and no other, on
  the same grant record the HTTP server reads.
- **SC-013**: After a revocation, a socket the grant admitted is closed within two minutes and its
  reconnect refused.
- **SC-008**: A stock Apache Kafka client outside the installation, configured with nothing
  but the bootstrap hostname, the issuer's CA, a client id and secret and the token route, reads a
  topic it was granted and is refused every other topic, every other group and publishing.
- **SC-009**: No connection reaches the external listener except through the gateway: a client in the
  cluster outside `envoy-gateway-system` is refused by network policy.
- **SC-010**: A machine fetching as fast as it can is held to its byte rate while a service consuming on
  the same broker keeps its throughput, measured in the k3s suite.
- **SC-011**: A cross-organization grant opens nothing until accepted, and for every grant both the
  granting project's and the grantee side's history name every change and the owner who made it.

## Assumptions

- Grants are rendered by the operator, which already watches every `AnkkaProject` and writes every
  `KafkaUser`. A `KafkaUser` is rendered from its own service and every grant naming it, so the
  operator holds the whole picture; the control plane still writes nothing in the broker's namespace.
- The two-minute bound is the time for a projected change to reach the cluster and for a mounted file
  to reach a pod, plus one re-read. The planning phase measures it on k3s and may tighten it.
- Machine tokens carry no grant, so a grant changes without reissuing tokens and a token never
  outlives a revocation; the price is that a service needs the grant set locally, which it has.
- Cross-installation access is not built here. It partly falls out: a service in another installation
  can be registered as a machine, call granted routes through the gateway and, where the installation
  exposes its broker, consume or produce granted topics with a Kafka client of its own — but it is a
  `Caller.Machine`, not a `Caller.Service`, and ankka's own consumers and publishers do not yet speak
  to another installation's broker. Trusting another installation's service authority is a feature of
  its own.
- **Resolved by planning**: `TLSRoute` has been in the Gateway API standard channel since v1.4 and
  `v1` since v1.5; Envoy Gateway v1.9.1 bundles Gateway API v1.6.1 with it served, and Strimzi 1.1
  added a listener type, `tlsroute`, that renders the routes itself and holds the RBAC for them in
  1.2.0. The passthrough listener sits on the installation's one `Gateway` on port 9094, beside the
  HTTPS listener on 443. Nothing in the repository referenced passthrough before this feature.
- **Resolved by planning**: Strimzi 1.2.0's `oauth` listener authentication validates tokens
  offline against a JWKS endpoint with `validIssuerUri` separate from `jwksEndpointUri`, so an
  in-cluster JWKS URL works; it takes the principal from a named claim, works with `simple`
  authorization, and a `KafkaUser` without `authentication` is legal — Strimzi then writes its ACLs
  and byte rates for the name as a SASL principal; `maxSecondsWithoutReauthentication` sets Kafka's
  `connections.max.reauth.ms`. The broker reads the JWKS over a port of the control plane's that
  serves public keys with server TLS only, since the broker holds no client certificate; that port is
  the second written exception to every port being mutual TLS. If any of this fails on k3s, the
  fallback is a listener of Strimzi's custom type with the same token verifier, not client
  certificates.
- An organization's owners are the people who grant, accept and register machines for it; the
  control plane has no finer right, and this feature adds none. Sheldon's affiliates organization,
  say, has one owner who answers every grant offered to it.
- `machine` becomes a reserved project id. No project of that name exists on any installation
  today; one created before this feature would be refused on upgrade and named in the release notes.
- The grants volume is a ConfigMap mount beside the schema mount every service already carries, so
  services deployed before this feature need one rollout; the operator reports which.
- The token route's rate limit and the per-broker connection cap are installation settings with
  shipped defaults; the planning phase names them in `PlatformVariables`.
- The broker is the installation's one Kafka from feature 027. A service that supplies its own broker
  through `ANKKA_KAFKA_*` is outside this feature, as it is outside 027.

## Dependencies

- **014-zero-trust-clusters**: caller identity from certificates and the caller-naming ACL forms this
  extends.
- **022-service-identity**: the offline token verifier in `ankka-auth-oidc`, reused to verify machine
  tokens.
- **024-replayable-topics** and **027-managed-broker**: qualified group ids and the per-service
  `KafkaUser` this adds entries to.
- **025-polyglot-service-client**: cross-project addressing for Python, TypeScript and Rust callers.
- **The installation's gateway** (Envoy Gateway on the Gateway API) and its public issuer: the
  passthrough listener, the `TLSRoute`s and the external listener's certificate.
- **Strimzi's OAuth support** (`strimzi-kafka-oauth`, shipped in Strimzi's Kafka images): token
  authentication on the external listener.
- **042-personal-data-erasure** depends on this feature's `decrypt` attribute, `erasure` right, and
  rendering of grants to the keyring; **043-topic-retention** on the grantee listing showing a topic's
  settings. Neither blocks this feature.
- Unblocks eitheror's casino plan: the shared payments project's hand-off to brand wallets, and the
  affiliate integration across brands, without the "C12 cross-project topic grants" work the plan had
  dropped.

## Open Questions

- Whether a grant should carry an expiry, so a partner's access lapses unless renewed. Not in this
  feature; a revocation is the only end.
- Whether the console should show a project's grants as a graph across the installation for a
  platform administrator. Not in this feature.
