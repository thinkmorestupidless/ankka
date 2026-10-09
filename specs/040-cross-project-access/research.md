# Research: Cross-Project Access

Every decision below was taken against ankka at `main` (`00cdbfbd`), the clarified spec of
2026-10-08, Strimzi 1.2.0, Envoy Gateway v1.9.1 (Gateway API v1.6.1 bundled) and Kafka 4.3.1. Line
numbers are that commit's. `CP` is `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`,
`API` is `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api`, `H` is
`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`, `R` is
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`, `G` is
`modules/grpc/src/main/scala/com/thinkmorestupidless/ankka/grpc`, `O` is
`operator/src/main/scala/com/thinkmorestupidless/ankka/operator`, `SC` is
`sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar`.

## The two assumptions the spec deferred to planning

### R1. Strimzi renders the passthrough routes itself: listener type `tlsroute`

**Decision**: the external listener is Strimzi's own `type: tlsroute` (Strimzi ≥ 1.1; the
installation pins 1.2.0, `kustomization/components/broker/strimzi/kustomization.yaml:12`). Strimzi
creates the bootstrap `Service` and `TLSRoute` and one of each per broker node, with
`configuration.parentRefs` naming the installation's `Gateway` (`ankka` in `ankka-gateway`),
`bootstrap.host: broker.<base>`, `hostTemplate: broker-{nodeId}.<base>` and
`advertisedPortTemplate: "9094"`. The Gateway gains one listener, `broker`, `protocol: TLS`,
`tls.mode: Passthrough`, port 9094, `hostname: "*.<base>"`, `allowedRoutes.namespaces` by selector
`kubernetes.io/metadata.name: ankka-broker` and `kinds: [TLSRoute]`. The EnvoyProxy's `NodePort`
Service gains `tls-9094 → 30094`; `kustomization/kind.yaml` maps 30094 to the host's 9094 (a cluster
made before this needs recreating to reach the broker from the host; `deploy-local.sh` does not
require the mapping, since the component is opt-in — R14).

**Rationale**: the spec's shape (a `cluster-ip` listener, one hand-rendered `TLSRoute` per
hostname, "a new broker node needs its hostname and TLSRoute; the broker component renders one per
node") is exactly what Strimzi's listener type does, including the resize case, and it keeps the
operator out of Gateway API resources it has no RBAC on (`kustomization/components/operator/
operator.yaml:116-118` grants `httproutes` and `backendtlspolicies` only). Strimzi 1.2.0's cluster
operator role already holds `tlsroutes` (its install manifest, line 10669). `TLSRoute` is in the
Gateway API **standard** channel since v1.4 and `v1` since v1.5; Envoy Gateway v1.9.1's
`install.yaml` bundles Gateway API v1.6.1 with `tlsroutes` served at `v1`, and the standard
`v1.6.1` CRDs `OperatorClusterSuite` installs carry it too — so the spec's "experimental" worry is
moot, and fabric8 7.9.0 ships `gatewayapi.v1.TLSRoute` should a test read one. The TLS mode is the
Gateway listener's, not Strimzi's (Strimzi's post says so), which is why the component patches the
Gateway. A port of its own rather than 443: the HTTPS listener's `*.<base>` hostname already
matches `broker.<base>`, and coexistence of an HTTPS and a TLS listener on one port is an Envoy
filter-chain subtlety the spec said to avoid ("on a port of its own"); 9094 is also what every Kafka
client expects as a default convention.

**Alternatives**: `cluster-ip` plus operator-rendered `TLSRoute`s (duplicates Strimzi, needs RBAC,
needs a node-pool watcher); `loadbalancer` per node (declined in the spec); port 443 (above).

### R2. Strimzi's `oauth` listener authentication does what the spec needs

**Decision**: the external listener authenticates with `type: oauth`, `jwksEndpointUri` the control
plane's in-cluster keys address (R10), `validIssuerUri` the public issuer string (R12),
`userNameClaim: broker_user`, `checkAudience: true` with `clientId: ankka`, `checkAccessTokenType`
left at its default (the token carries `typ: Bearer`, R12), `maxSecondsWithoutReauthentication: 900`,
`tlsTrustedCertificates` the service authority's `ca.crt` (already present in `ankka-broker` as the
`ankka-broker-tls` secret's `ca.crt`, mounted today at `/mnt/ankka-service-ca`). Authorization stays
`simple`. A machine's `KafkaUser` has **no** `authentication` block: Strimzi documents that
authentication is optional, no credential is then generated, and ACLs and quotas are written for the
user name as a SASL principal — exactly `User:machine.<org>.<name>` as the OAuth listener names a
token whose `broker_user` claim is that string.

**Rationale**: confirmed against the `strimzi-kafka-oauth` README (fast local JWT validation needs
only `oauth.jwks.endpoint.uri`; `oauth.valid.issuer.uri` is independent of it; the principal is
`User:<username claim>`; `connections.max.reauth.ms` is what `maxSecondsWithoutReauthentication`
sets, and a Kafka client ≥ 2.2 re-authenticates on its own) and the Strimzi API reference for
`KafkaUserSpec` ("Authentication is optional. If authentication is not configured, no credentials are
generated"). Kafka's own OAUTHBEARER client support (KIP-768, `sasl.oauthbearer.token.endpoint.url`,
`sasl.oauthbearer.client.credentials.client.id` / `.client.secret`, Kafka 4.1's documented names; the
JAAS `clientId`/`clientSecret` options still work) is what the docs tell a partner to configure
(`contracts/broker-external.md`).

**Alternatives**: a `custom` listener with Kafka's built-in `OAuthBearerValidatorCallbackHandler`
(the fallback the spec names; needs extra volumes for a file JWKS and would not re-read on
rotation); client certificates (declined in the spec).

## Grants on the control plane

### R3. A grant is a value on the `Project` entity, with an id and a seven-state lifecycle

**Decision**: `Project.grants: Map[String, Grant]` (`CP/domain/model.scala:328-396` gains the
field). `Grant(id, grantee: Grantee, target: GrantTarget, state: GrantState, granted:
Attribution, answered: Option[Attribution], ended: Option[Attribution])`. The id is 16 hex
characters from `SecureRandom`, minted by the endpoint; the identity rule is "one live grant per
(grantee, target)": `make-grant` for a pair that is live (`pending` or `accepted`) replies with the
existing grant and persists nothing (the no-op the spec asks for), and a pair whose grant has ended
makes a new grant with a new id. Events (`CP/domain/events.scala`, each with `actor`/`at` defaulting
to `None` as every event does): `GrantMade(id, grantee, target, pending, actor, at)`,
`GrantAccepted(id, …)`, `GrantDeclined`, `GrantWithdrawn`, `GrantRevoked`, `GrantRelinquished`,
`GrantLapsed`. Commands: `make-grant`, `withdraw-grant`, `revoke-grant`, `accept-grant`,
`decline-grant`, `relinquish-grant`, `lapse-grant`; queries `grants` and `grant`. Transitions outside
the lifecycle (`accept` on an accepted grant, `revoke` on a pending one) are `Conflict`. A grant on an
undeclared topic is refused in the handler (the topic is on the same entity); FR-005's "no grant to
a service of this project" is a handler check too (the grantee's project id equals the entity's).
`EventCompatibilitySuite` pins every new event's wire form and asserts no event has a `secret` field.

**Rationale**: the spec identifies a live grant by grantee and target but wants an ended grant to be
history with "its own history" when granted again, which needs an id; commands addressing a grant by
id keep the CLI and the console honest about *which* grant was revoked. One entity, one fold, replay
reproduces the state (the control plane's rule for every invariant, `.claude/rules/control-plane.md`).

**Alternatives**: identity by (grantee, target) only (no history of a re-grant); a `GrantEntity`
per grant (two entities, no transaction, which the review already declined).

### R4. The grantee side's copy is a mirrored record, written by one consumer

**Decision**: `GrantMirror`, a `Consumer[ProjectEvent, Nothing]` beside `ProjectionTrigger` and
`SuspensionTrigger` (`CP/application`), commands `record-grant-change` on the grantee: the grantee
`Project` for a `service:` grantee, the grantee `Organization` for a `machine:` grantee. Both entities
gain `received: Map[String, ReceivedGrant]` and the event `GrantRecorded(grantId, grantingProject,
grantee, target, change: GrantChange, actor, at)` with `change` one of `offered`, `accepted`,
`declined`, `withdrawn`, `revoked`, `relinquished`, `lapsed`, `made` (a same-organization grant that
started accepted). The query `received-grants` answers the grantee-side listing, and
`history` on each entity shows the record; the k3s acceptance feature's "the history of
`affiliates`" reads it. The consumer is idempotent (a `GrantRecorded` for a (grantId, change) already
recorded is refused `Conflict` and the consumer ignores it), so a redelivery writes nothing twice.

**Rationale**: FR-030 and the review's one-writer rule: the granting project's events are the only
command-written record; a consumer deriving the copy is the control plane's existing shape for
cross-entity reactions (`ProjectionTrigger`, `SuspensionTrigger`, `ProjectTopicsTrigger`).

**Alternatives**: a view (`GrantRows`) alone — a view cannot give the grantee *entity* a history the
spec says both sides hold; the endpoint writing both (declined by the review).

### R5. Acceptance is an endpoint check on the grantee organization, applied to the granting project

**Decision**: `POST /organizations/{org}/grants/{grantId}/accept|decline|relinquish` on
`OrganizationEndpoint`: `authz.requireOwner(principal, org, write = true)`, then
`OrganizationEntity.received-grants` must hold the grant (for a machine grantee) or
`ProjectEntity.received-grants` of a project the organization owns must (the endpoint reads the
grantee project's `organizationId` first); then the command goes to the granting `Project`
(`accept-grant` etc.) with the owner's attribution. A deploy token is a `member`, so `requireOwner`
refuses it (the feature's "nor can a deploy token"). A member who is not an owner gets 403, a
non-member 404, as everywhere.

**Rationale**: cross-entity checks live in endpoints, never handlers; the granting project's handler
cannot see the grantee organization's membership. The mirrored record (R4) is what makes "the grant
is offered to this organization" checkable without a view.

### R6. Lapse: deletion of the grantee ends its grants with the deleter's attribution

**Decision**: two triggers. `MachineLifecycleTrigger` (`Consumer[MachineEvent, Nothing]`) on
`MachineDeleted(actor, at)` queries the organization's `received-grants` for grants naming
`machine:<org>/<name>` and sends `lapse-grant(id)` to each granting project with
`Attribution.from(actor, at)`. `ProjectTopicsTrigger`, renamed in code only to `ProjectTrigger`
(component id stays `project-topics-trigger`, so its consumer group and offsets survive the
upgrade), gains two reactions: on `ProjectDeleted` it reads the deleted project's own
`received-grants` (the tombstone keeps state readable) and lapses every grant naming a service of
it, with the deleter's attribution; and on every grant event it calls `projector.projectTopics`
(R7), so the cluster sees grants. A machine's `register` after a deletion is a new machine (R9), and
`lapsed` is terminal, so nothing reopens.

**Rationale**: clarification Q1. Attribution stays a person because the deleting command carried one.

### R7. Grants reach the cluster on `AnkkaProject`, and services read them from the file they already mount

**Decision**: `AnkkaProjectSpec` gains `grants: List[ProjectGrantEntry]` — only `accepted` grants
(the operator never sees a pending one, so "opens nothing" is structural) — with `id`, `grantee`,
`target` fields (`kind: route|method|topic|erasure`, `service`, `httpMethod`, `path`, `topic`,
`right`, `decrypt`), `grantedAt`. `ProjectProjection.spec` sorts them by id.
`ProjectReconciler` renders them into the existing `ankka-project` ConfigMap as a second key,
`grants.json` (`O/ProjectConfig.scala:52-87`), mounted on every platform container at
`/var/run/ankka/project` since feature 037 — `optional: true`, no `subPath`, so the kubelet updates it
in place. The runtime gains `GrantsFile` (`R/GrantsFile.scala`): reads
`ANKKA_PROJECT_GRANTS=/var/run/ankka/project/grants.json`, re-reads on access when the real path's
mtime changed, at most once per `ankka.grants.reload-interval` (10 s; the `RotatingTls.refreshed`
pattern, `R/RotatingTls.scala:132-147`), keeps the last good value on a parse failure, and filters
to this service's entries by `ServiceIdentity`. The operator reports `status.grants: "mounted"` on
every `AnkkaService` it renders with the mount; the control plane shows a grant on a service whose
status lacks it as not in effect, "rollout needed" — an installation whose operator predates this
feature, or has not reconciled the service yet, is the honest case left.

**Rationale**: the review's "a volume can only be added when the pod is made" is met by the mount
feature 037 already put on every pod (`RenderingUnchangedSuite`'s fixtures all carry `ankka-project`
since d256121b), so no rendering changes and no service rolls on upgrade — which the kubernetes rule
forbids ("a repin that changes an object is a service rolling on upgrade, and is never accepted").
One file per project keeps the operator's ConfigMap write where it is; a per-service file would be a
second ConfigMap per service for nothing. The kubelet's ConfigMap sync (its sync period plus cache
TTL, about a minute) and a 10 s re-read fit the two-minute bound (R25).

**Alternatives**: a grants volume of its own (a rendering change on every pod — rolls everything on
upgrade); a watch on the API server from the runtime (a credential every pod would hold).

## The HTTP and gRPC side

### R8. `Callers.granted` admits by route, so a matcher is evaluated against a target

**Decision**: `CallerMatcher.Granted` (`H/Caller.scala:85-104`); `admits` gains a third parameter,
`target: Option[GrantTarget]`, with `GrantTarget.Route(method, template)` and `.Method(service,
method)` (`service/method` in gRPC's full-name form); every existing matcher ignores it. `Granted`
admits `Caller.Local` (as every matcher does), and otherwise answers `grants.admits(caller, target)`
where `grants` is a `Grants` handle (`H/Grants.scala`, a trait with `admits` and `entries`) the server
holds: `GrantsFile` in a cluster, `Grants.none` by default, and whatever `AnkkaTestKit.start(grants =
…)` supplies in a test. `HttpServer.admit` (`H/HttpServer.scala:476-502`) passes the matched route's
method and template; `G/Admission.decide` passes `fullName`. A route whose ACL does not name
`Granted` never consults the grants (FR-008 by construction). The runtime's topology document marks
each route handler `grantable: true` when its effective ACL names `Granted`
(`TopologyHandler.grantable: Option[Boolean]`, `API/descriptors.scala:1206-1296`), which is what the
listing reads for "route not grantable" (R19).

**Rationale**: the matcher cannot know which route is being served without being told; threading
the target through `admits` is the smallest change and keeps `Acl` a value. `Grants` as a handle
keeps `http` free of file I/O in tests.

### R9. `Caller.Machine` from a bearer token, verified in `http` with the JDK alone

**Decision**: `Caller.Machine(organization, name)`, encoded `machine:<org>/<name>`
(`Caller.encode/decode`). `CallerSource.callerOf` (`H/HttpServer.scala:704-720`): when the
certificate says `Gateway` and the request carries `Authorization: Bearer <jwt>` whose `iss` is the
machine issuer, `MachineTokens.verify` decides — valid gives `Caller.Machine`, anything else leaves
`Caller.Gateway` exactly as today (FR-015; a token from another issuer is not even parsed beyond
`iss`). Over a service-to-service connection the certificate decides and the token is ignored (edge
case). `MachineTokens` (`H/MachineTokens.scala`, no new dependency): JWS compact parsing, `alg`
RS256 only, `kid` lookup in a JWKS cache, `java.security.Signature("SHA256withRSA")` over the JWK's
`n`/`e`, then `iss`, `aud` (contains `ankka`), `exp`/`nbf` with 60 s skew, `typ: Bearer`, `sub`
of the form `machine:<org>/<name>`. The JWKS is fetched lazily from `ANKKA_MACHINE_JWKS_URL` with
pekko-http's client over an SSL context trusting `ANKKA_MACHINE_JWKS_CA` (the service authority's
`ca.crt` the pod already mounts), cached, refreshed on an unknown `kid` at most once per 30 s, and
held through an outage. `auth-oidc`'s `Oidc.authenticate` learns the same issuer: a token the machine
verifier accepts yields `Principal(subject = "machine:<org>/<name>", claims = Map("kind" ->
"machine", "organization" -> org))`, so an `Acl.Authenticate` route sees both a principal and
`Caller.Machine` (FR-015's second half). The variables are platform-only (`PlatformVariables`):
`ANKKA_MACHINE_ISSUER`, `ANKKA_MACHINE_JWKS_URL`, `ANKKA_MACHINE_JWKS_CA`, rendered by the operator
on every platform container from its own settings; a descriptor that sets one is refused as
`ANKKA_HTTP_PORT` is.

**Rationale**: `http` cannot see nimbus (`authOidc` depends on `http`, `build.sbt:324`), and a
service without users must still turn a machine token into a caller with no descriptor change
(FR-016). RS256 verification is a hundred lines of JDK; `MachineTokensSuite` drives it with keys the
JDK generates and tokens `TestIssuer` signs. Verification is synchronous on the server's dispatcher
like every ACL, so the JWKS fetch happens off it (a request arriving before the first fetch completes
reads as `Gateway` and is refused by a granted route; the fetch is started at server start, so this
window is the first second of a pod's life).

**Alternatives**: making `http` depend on `auth-oidc` (reverses the module direction); the operator
mounting the JWKS as a file (a second delivery path beside the broker's URL, and the broker needs the
URL anyway).

### R10. The control plane issues tokens; the key lives in a Secret it creates and mounts

**Decision**: `POST /oauth/token` and `GET /.well-known/jwks.json` on a new `MachineTokenEndpoint`
(`Acl.AllowAll`; the token route is the only unauthenticated write on the control plane). The signing
key: on first need the control plane generates an RSA-2048 pair and **creates** the Secret
`ankka-controlplane-machine-keys` in its own namespace (`create` only; a 409 means another instance
won), holding `<kid>.pem`; its own manifest mounts that Secret `optional: true` at
`/var/run/ankka/machine-keys`, and `MachineKeys` (`CP/auth/MachineKeys.scala`) re-reads the directory
by mtime as `RotatingTls` does, signing with the newest `kid` and publishing every key in the JWKS.
Rotation is a `patch` adding a new `<kid>.pem` and, an hour later, a second patch removing keys older
than the previous one: a recurring timed action (`MachineKeyRotation`, every 30 days, feature 032's
`due_at = 'infinity'` row) and `POST /platform/machine-keys/rotate` for a platform administrator.
Locally and in tests (`ankka.controlplane.machine-keys.directory` unset) the key is generated in
memory per process, which `MachineTokenSuite` uses. The JWKS and `GET /.well-known/openid-configuration`
(issuer and `jwks_uri` only) are also served on a new container port 7629 `keys`, TLS with the
control plane's service certificate and **no client authentication**, so the broker — which holds no
client certificate — and every service read keys over an address the gateway does not front. The
control plane's network policy admits `ankka-broker` and every `managed-by: ankka` namespace to 7629.

**Rationale**: the control plane's RBAC is `secrets: create, patch` with no `get` (the registry
credential rule, `.claude/rules/control-plane.md`) and its manifest sets no `ANKKA_SECRET_KEY`
(`kustomization/components/controlplane/deployment.yaml`), so the one place a private key can live
unread by Postgres and reachable by every instance is a Secret the control plane writes and the
kubelet hands back as a file. Rotation without a token failing: a token names its `kid`, the JWKS
carries old and new for longer than fifteen minutes, verifiers refresh on an unknown `kid`. The
`keys` port is the second written departure from "every port is mutual TLS" (the first is Garage's
plain HTTP); what it serves is public. `keys` is plain server TLS, not plain text, because a forged
JWKS would mint machines.

**Alternatives**: the secret store (`ankka_secrets`) — needs a secret key the control plane's
manifest does not have and 038 is redesigning; a Keycloak client per machine (declined in the spec);
serving the JWKS only through the gateway (the broker in a kind cluster cannot reach `api.<base>`).

### R11. The token route's rate limit is per client id, per node, from installation settings

**Decision**: a token bucket per client id in `MachineTokenEndpoint`, `ankka.controlplane.machines.
token-rate` (`ANKKA_MACHINE_TOKEN_RATE`, default `12/minute`, burst 12), refusing `429` with
`Retry-After` for the rest of the minute; unknown client ids are counted too, so a guessing client
is slowed. Per node, stated in the docs: an installation with N control plane instances allows up to
N times the rate.

**Rationale**: a shared counter would be a view or an entity on the request path — an I/O call per
token request to defend against a flood is the wrong trade; a per-node bucket needs nothing. The
secret is 256 bits, so the limit is about load, not guessing.

### R12. What a machine token says

**Decision**: header `{"alg":"RS256","kid":…,"typ":"JWT"}`; claims `iss` = the public control
plane address (`https://api.<base>[:port]`, derived like the Keycloak issuer from `ANKKA_BASE_DOMAIN`
and `ANKKA_HTTPS_PORT`; locally `ankka.controlplane.machines.issuer` or `http://localhost:9000`),
`sub: machine:<org>/<name>`, `aud: ["ankka"]`, `exp = iat + 900`, `iat`, `nbf`, `jti` (random),
`typ: "Bearer"` (the claim Keycloak writes and both verifiers check), `organization`, `machine`,
`broker_user: machine.<org>.<name>`. Nothing else: no grant, no scope. The response is
`{"access_token", "token_type": "Bearer", "expires_in": 900}`; the request is form-encoded
`grant_type=client_credentials` with `client_id`/`client_secret` as form fields or HTTP Basic (both
of which Kafka's `DefaultJwtRetriever` and every OAuth library send). Errors follow RFC 6749:
`invalid_client` (401) for an unknown id, a wrong secret or a deleted machine, all alike;
`unsupported_grant_type`; `429` for the limit.

### R13. Machines are an entity, a view and a cluster resource

**Decision**: `MachineEntity` (`CP/application/MachineEntity.scala`), id `<org>/<name>`, state
`Machine(organizationId, name, digest, registeredBy, registeredAt, byteRates: Option[ByteRates],
deleted)`; events `MachineRegistered(digest, actor, at)`, `MachineByteRatesSet(produce, consume,
requestPercentage, actor, at)`, `MachineDeleted(actor, at)`; commands `register` (refused while one
exists; accepted again after a deletion, a new secret and a new `registeredAt`), `set-byte-rates`,
`delete`; queries `get`, `check-secret(digest)` (constant-time, answers the machine only when not
deleted). The client id is `machine:<org>/<name>`; the secret is minted as deploy tokens are
(`CP/auth/DeployTokens.mint`: 256 bits, SHA-256 digest kept, `MessageDigest.isEqual` to compare),
shown once in `MachineRegistered`'s reply. `MachineRows` lists by organization (name, registered by
and when, byte rates, no digest). `machine` becomes a reserved project id (`ProjectId.Reserved` and
`Names.ReservedProjectIds`, held together by `ReservedProjectIdsSuite`). The control plane writes a
cluster-scoped `AnkkaMachine` resource named `<org>.<name>` on register and on byte-rate changes
(`spec.organizationId`, `spec.name`, `spec.byteRates`) and deletes it on delete; the operator renders
the machine's `KafkaUser` from it and from every `AnkkaProject` grant naming it (R16).

**Rationale**: a deploy token got an entity of its own for the same reasons (its journal is what
the index replays); the token route reads the digest through a query on a virtual thread, which is
allowed because it is an endpoint, not an `Acl.Authenticate`. The organization is the tenant, so
the resource is cluster-scoped: no project namespace owns a machine, and the control plane keeps
writing nothing in the broker's namespace.

**Alternatives**: machines as members of the organization (a machine is not a member: it cannot
list, and the spec says so); the quotas carried on each grant entry (stale whenever an owner changes
them).

### R14. The broker is exposed by a component an installation opts into

**Decision**: `kustomization/components/broker-external/`: a JSON patch adding the `external`
listener to the `Kafka` (R1, R2), a `Certificate` `ankka-broker-external` in `ankka-broker` for the
wildcard `*.<base>` from the installation's public issuer (one label deep covers `broker.<base>` and
every `broker-<n>.<base>`), referenced by the listener's `brokerCertChainAndKey`; a patch adding the
Gateway listener and the EnvoyProxy port; the operator's and the control plane's settings
`ANKKA_BROKER_EXTERNAL_BOOTSTRAP=broker.<base>:9094` (the operator renders nothing from it but the
control plane reports it, R19, and the machine listing tells a partner where to connect). The
listener's `networkPolicyPeers` is the gateway's proxy pods (`envoy-gateway-system`, the
`owning-gateway-{name,namespace}` labels `O/ZeroTrust.scala:243-249` already names). The same patch
sets the `Kafka`'s `max.connections.per.ip` (per broker, Kafka applies it to every listener) and
`listener.name.external.max.connection.creation.rate` from two `ankka-platform` ConfigMap keys
with shipped defaults (64 and 20 a second), which is FR-025's connection cap. The cloud
overlay lists the component and sets `PUBLIC_ISSUER` to `letsencrypt-production`; the local overlay
does not list it, and `docs/platform/cross-project-access.md` says how to (and that kind needs the
30094 mapping). `RemoteOverlaySuite` asserts the cloud render has the listener, the certificate names
an issuer that exists, and the local render has none.

**Rationale**: FR-021 ("MUST NOT expose it unless it says so"); a component is how every optional
piece of the platform ships (`garage`, `telemetry-store`, `broker` itself).

### R15. The public issuer becomes a `ClusterIssuer` in both overlays

**Decision**: the local overlay's self-signed chain moves to the `cert-manager` namespace:
`Certificate ankka-root-ca` there, and `ankka-ca` becomes a `ClusterIssuer` (whose `ca.secretName`
is resolved in cert-manager's namespace — the trap, used the right way round). The wildcard
`Certificate` in `ankka-gateway` names it; `deploy-local.sh` exports the root from
`cert-manager/ankka-root-ca`. Both overlays then replace the placeholder `PUBLIC_ISSUER` in the
broker-external certificate: `ankka-ca` locally, `letsencrypt-production` in the cloud (already a
`ClusterIssuer`). `RemoteOverlaySuite`'s issuer cases are updated to assert the new shape in both
directions.

**Rationale**: a namespaced `Issuer` in `ankka-gateway` cannot issue a certificate in `ankka-broker`,
and the external listener's certificate must come from the issuer a partner already trusts. Moving a
local root reissues the local wildcard once (a kind cluster is recreated for the port anyway).

### R16. The operator renders the broker side of grants: ACL entries and machine users

**Decision**: `StrimziRendering.user(spec, broker, granted: Vector[GrantedTopic])`
(`O/StrimziRendering.scala:49-74`) appends one literal `topic` ACL per accepted topic grant naming
`service:<p>/<s>`: `Read`+`Describe` for `consume`, `Write`+`Describe` for `produce`, on
`<grantingProject>.<topic>`; the group prefix rule is unchanged. `ServiceReconciler` reads them with
`Executor.grantsNaming(ServiceRef)`: every `AnkkaProject` in the informer's cache (the operator
already holds one, `O/Operator.scala:82-135`), filtered by grantee. A changed `AnkkaProject` requeues
the services in its namespace (as today) **and** every service and machine its grants name (new).
`MachineReconciler` on a queue of its own renders `KafkaUser machine.<org>.<name>` in `ankka-broker`:
no `authentication`, `authorization.simple` with the entries its accepted grants give plus
`group` prefix `ankka.machine.<org>.<name>.` Read, and `quotas` from `AnkkaMachine.spec.byteRates` or
the operator's defaults (`ANKKA_MACHINE_PRODUCE_BYTES`, `ANKKA_MACHINE_CONSUME_BYTES`,
`ANKKA_MACHINE_REQUEST_PERCENTAGE`, defaults 1 MiB/s, 4 MiB/s, 50), clamped to
`ANKKA_MACHINE_BYTE_RATE_CEILING` (default 32 MiB/s; the control plane refuses above the ceiling at
set time, the operator clamps anyway). `KafkaUserSpec` gains `authentication: Option` and `quotas:
Option[KafkaUserQuotas(producerByteRate, consumerByteRate, requestPercentage)]`. On an `AnkkaMachine`
deletion the reconciler re-renders the user with no topic entries and keeps it (FR-024). A machine
named by no grant and no resource is never rendered.

**Rationale**: the spec's assumption ("a KafkaUser is rendered from its own service and every grant
naming it, so the operator holds the whole picture"); the rendering stays pure, inputs read by the
reconciler as `declaredBrokers` are today (`O/ServiceReconciler.scala:67-74`). `RenderingGoldenSuite`'s
`broker.txt` is unchanged for a service with no grants; `BrokerGrantsRenderingSuite` holds the new
entries and the machine user as goldens of their own.

### R17. A cross-project topic is named by project in the options, and the broker alone refuses it

**Decision**: `TopicOptions.project: Option[String]` and `Publication.project: Option[String]`
(`modules/sdk/.../ChangeSource.scala:97-110`); `ChangeSource.fromTopic(project, name, …)` and
`Consumer.produceTo(project, name)` as short forms. `KafkaConnection.qualified` (the one place a
topic is handed to Kafka) prefixes with the named project instead of the service's own; the group is
unchanged (`ConsumerGroups.name`, the service's own project); `DeclaredSource.Topic` carries the
project, so the topology shows `topic:<project>/<name>` and `undeclaredTopics` skips it (it already
skips ids with a `/`, `CP/api/ServiceEndpoint.scala:74-99`). `rejectUndeclared` does not read the
project's declarations for it (the declaration is another project's). The start-time check does not
consult grants: the broker refuses, the subscription's `failing` says `TopicAuthorizationException`,
and `ServiceStatus.crossProjectTopics: Option[Vector[CrossProjectTopic(project, topic, right,
state)]]` is computed by `ServiceEndpoint` from the topology's cross-project edges joined with the
granting project's `grants` query (`granted`, `not granted: no grant` / `pending` / `ended`), the
spec's Q3. Protocol 1.15 carries `project` on `Source.Topic` and `Publication` (R18).

**Rationale**: 037's R1 chose options over positional fields for exactly this kind of addition; the
spec decided deploy order does not matter for topics.

### R18. Protocol 1.15: a machine caller, a granted matcher, a topic's project

**Decision**: `discovery.proto`: `CallerMatcher.kind` gains `Empty granted = 5`; `Source.Topic` and
`Publication` gain `optional string project`. `endpoint.proto`: `Caller.kind` gains
`MachineCaller machine = 4 { organization, name }`. `README.md`, `Compatibility.Protocol.version`,
`WireProtocol.Version`, the three SDK constants and `ProtocolSuite` move to `1.15`; the SDK copies of
`protocol/` are refreshed by their scripts. Each SDK gains `Callers.granted()` (Python
`Callers.granted`, TypeScript `Callers.granted`, Rust `CallerMatcher::Granted`), a `MachineCaller`
caller type, and `project=` on topic sources and publications. An SDK refuses to declare a `granted`
matcher or a cross-project topic against a runtime stating a version below 1.15 (the socket
precedent, `.claude/rules/sidecar.md`); the sidecar refuses a `Spec` using either under an earlier
minor. `ConformanceReference.CallersEndpoint` gains `GET /callers/granted` under
`withAcl(Acl.allowCallers(Callers.granted))`, and the suite gains `http.caller-machine`,
`http.granted-admits-a-grant`, `http.granted-refuses-without`, `topics.cross-project-source`; every
SDK's reference declares the same.

### R19. The listing computes "in effect, or why not" in the endpoint, from four sources

**Decision**: `GET /projects/{id}/grants` returns each grant with `state` and `effect: in effect |
pending | declined | withdrawn | revoked | relinquished | lapsed | route not seen | route not
grantable | rollout needed | broker not exposed`. The endpoint reads: the grant's state; for a route
or method target, the service's status (`hosting == "web"` → not grantable; status `grants` absent →
rollout needed) and its merged topology (`handlers` with the route's name: absent → route not seen,
`grantable != Some(true)` → route not grantable); for a machine topic target, the control plane's
`ANKKA_BROKER_EXTERNAL_BOOTSTRAP` (unset → broker not exposed). The grantee-side listings
(`GET /projects/{id}/grants/received`, `GET /organizations/{id}/grants`) show the record and, for a
topic grant, the topic's retention settings read from the granting project's declaration (FR-031,
feature 043's fields when they exist; today `partitions` and `compacted`, with `retention`/`copies`
added when 043 lands — the wire type leaves the fields optional). `GET /organizations/{id}/machines`
shows each machine with the external bootstrap address and the token route, so a partner's
configuration is one listing away.

### R20. Routes, the CLI and the console

**Decision** (`contracts/grants.md`, `contracts/machines.md`): routes on `ProjectEndpoint`
(`POST /projects/{id}/grants`, `GET /projects/{id}/grants`, `GET /projects/{id}/grants/received`,
`DELETE /projects/{id}/grants/{grantId}` — withdraw while pending, revoke while accepted) and
`OrganizationEndpoint` (`GET /organizations/{id}/grants`, `POST …/grants/{grantId}/{accept|decline|
relinquish}`, `GET/POST /organizations/{id}/machines`, `DELETE …/machines/{name}`, `PUT
…/machines/{name}/byte-rates`), plus `MachineTokenEndpoint` (R10). CLI: `ankka projects grants
make|withdraw|revoke|list|received`, `ankka organizations grants list|accept|decline|relinquish`,
`ankka organizations machines register|delete|list|byte-rates`. Console: a "Grants" section on the
project page (held and received, with effect and the reason), a "Machines" page beside "Deploy
tokens" on the organization (register shows the secret once through the same `tokenFlash`), and
"Offered grants" on the organization page with accept/decline/relinquish; every new route is faked
in `fake-control-plane.ts` and exercised by the Playwright suite, since `parity.ts` fails on a route
the console never called. The console work is on the redesigned shell (feature 035 landed on
`main`); it is the last step of the order of work and the one the plan flags as separable if the
console is mid-redesign again.

### R21. The sample grows a wallet and an affiliates shape, behind one knob

**Decision**: `CART_WALLET=on` registers in the shopping cart sample a `WalletEndpoint`
(`/v1/wallets`: `POST /{player}/{currency}/deposits` with an idempotency key the entity enforces,
`GET /{player}` admitting only `Callers.service("lobby")`, `GET /{player}/{currency}`, the SSE
`GET /{player}/ledger`, the socket `/{player}/events`, all but the second under
`Callers.granted`), a `WalletService` gRPC service (`Deposit`, `GetBalance`) in
`samples/shopping-cart-api`, and an `AffiliatesEndpoint` (`/v1/affiliates`: `GET /attribution`
granted, `GET /feed` allow-all, `GET /report` authenticated). The checkouts topic knob
`CART_CHECKOUTS_TOPIC` accepts `project/name` for the cross-project consumer case, and
`CART_PUBLISH_TO` names a cross-project publication for the notifier.

**Rationale**: the k3s features name these routes, and the sample is already the one image every
cluster suite deploys under different names; a second image would double the build on every runner.

### R22. One suite per feature file; four on k3s, two offline

**Decision**: `controlplane/src/test/.../CrossProjectClusterFeatures.scala` (abstract, the
`BrokerClusterFeatures` shape) with `CrossProjectRouteGrantsFeatures` (gateway, operator, control
plane, the sample as `spinvibe/wallet`, `payments/merchant`, `payments/psp-gateway`),
`CrossProjectTopicGrantsFeatures` (the broker stack, `BrokerProbe` holding a service's certificate),
`CrossProjectMachinesFeatures` (gateway, `curl` from the host for the token route and the granted
route), `CrossProjectMachineTopicsFeatures` (gateway, broker, the `broker-external` component with
base domain `127.0.0.1.sslip.io` so `broker-0.<base>` resolves on the host, the k3s container's
30094 bound to the host's 9094 because the advertised port is fixed, and the Apache Kafka Java client
in the test JVM as the stock client — the console consumer's library, driven from the host outside
every policy); `CrossProjectAcceptanceFeatures` and `CrossProjectListingFeatures` offline against an
in-process control plane, a scripted `TopologyReader` and the operator's pure rendering for "the
credential may read". `ranElsewhere` carries the lapse and the 042-hooks scenarios to the entity
suites that hold them. Each class is a plain `class`, so `.github/cluster-suites.py` lists it.

### R23. Refusals are recorded, and revocation reaches open streams

**Decision**: `HttpServer.Router.handle` records a refused request as a span with
`SpanOutcome.Refused` (today a 403 returns before `Tracing.request`, `H/HttpServer.scala:308-339`;
gRPC already records `spans.refused`), which FR-009 requires and the first route-grants scenario
asserts. `OpenSocket` keeps the `RequestContext` it was opened with and the `GrantTarget`;
`dispatchStream` runs each SSE source through a `KillSwitch` registered in a new `OpenStreams`
(the `OpenSockets` shape, `H/Sockets.scala:72-83`). `GrantsFile` publishes a change to a listener the
server registers, which closes every socket (`CloseReason.Revoked`, code 1008) and stream whose
caller the current grants no longer admit on their target.

### R24. `decrypt` and `erasure` are data in every record, enforced by nothing here

**Decision**: a `consume` topic grant carries `decrypt: Boolean`; an `erasure` grant has the target
`{kind: "erasure"}`; both are stored, listed, mirrored and written into `grants.json` (the keyring
of feature 042 reads that file when it exists). No ACL, no KafkaUser entry and no service reads
them. `CrossProjectListingFeatures` holds the scenario.

### R25. The two-minute budget

| Step | Bound | Source |
|---|---|---|
| command → `AnkkaProject` applied | ~1 s | the consumer and a server-side apply |
| operator → ConfigMap / `KafkaUser` | ~1 s (2 s resync in tests) | informer |
| kubelet syncs the ConfigMap volume | ≤ ~90 s | sync period 60 s + cache TTL |
| `GrantsFile` re-read | ≤ 10 s | `ankka.grants.reload-interval` |
| Strimzi applies the ACL | seconds | user operator |

The k3s suites assert within 120 s; the plan does not tighten the bound. The operator's resync
interval in a suite is 2 s as today.

## Where the implementation departed from the plan (2026-10-09)

Recorded during implementation, before any k3s run; the k3s runs (T089) may add to it.

- **No new environment variables on a pod.** The plan gave services `ANKKA_PROJECT_GRANTS` and the
  `ANKKA_MACHINE_*` issuer settings as variables. Both would change the pod template, so a first grant
  or an operator upgrade would roll every service. Instead `GrantsFile` finds `grants.json` beside the
  project declarations in the `ankka-project` ConfigMap every platform container already mounts, and
  the machine issuer and key set URL ride beside it as `machines.json` (operator `Settings.machines`;
  issuer derived as `https://api.<base>[:port]`, keys from the control plane's `keys` port). The
  variables remain as overrides for a service run outside the platform.
- **`AnkkaProject` is written when a project is created**, not only at its first topic or grant
  (`ProjectTopicsTrigger` on `ProjectCreated`), so `machines.json` exists for every project made after
  this release. A project created before it gets the resource at its first topic or grant.
- **The deleted-project lapse is `ProjectTrigger`**, a consumer of its own, not an extension of
  `ProjectTopicsTrigger`; both triggers share `GrantLapse`.
- **The local CA was not moved to a ClusterIssuer (T070).** Moving it changes every local platform's
  root and cannot be verified without kind; the local overlay does not list `broker-external`, and
  `CrossProjectMachineTopicsFeatures` creates its own ClusterIssuer over the local root instead.
- **A project's history of grants is its grants listing**; there is no project history route. The
  listing keeps each grant's maker, answer and end with when, which is what the history scenarios read.
- **Retention is not declarable on a topic yet**, so the listing scenario that reports a topic's
  retention to its grantee is `@ignore`d.
