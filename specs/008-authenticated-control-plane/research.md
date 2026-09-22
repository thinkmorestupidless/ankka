# Research: An Authenticated, Multi-User Control Plane

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified — against this repository, or against upstream
documentation fetched on 2026-09-22 — or is an assumption a named task settles at implementation.
The list at the end collects the latter.

## R1 — The Keycloak operator, pinned, in own-namespace mode

**Verified upstream.** Keycloak's current release is 26.7.4 (released 9 July 2026; 26.7 is the
only supported line). The operator is installed with one kustomize reference,
`github.com/keycloak/keycloak-k8s-resources/kubernetes?ref=26.7.4`, and by default watches only
the namespace it is installed in (`OwnNamespace`); a `cluster-wide` variant watches all namespaces
and "does not support reconciling multiple Keycloak versions in one cluster".

**Decision**: the operator and the platform's `Keycloak` resource both live in `ankka-auth`, in
own-namespace mode. The component pins the exact release the way `components/cnpg` and
`components/certmanager` do ("a CRD schema must not change under a running platform without a
deliberate bump here"). When tenant identity arrives, a second `Keycloak` resource goes in the
same namespace under the same operator — realms are the tenancy unit, instances are the blast-radius
unit (treatment D8).

**Alternatives considered**: the cluster-wide install — nothing needs a Keycloak outside
`ankka-auth`, and its one-version restriction is a constraint for no benefit; a plain
`Deployment` — where the treatment started, rejected for the reason recorded there.

## R2 — Realm import is one-shot, so the realm has no users and the CR is generated

**Verified upstream.** `KeycloakRealmImport` "only supports creation of new realms and does not
update or delete those"; an existing realm "will not be overwritten"; the CR does not delete the
realm when it is deleted; and the docs recommend deleting the CR after import to clean up its Job.
Its `placeholders` field maps environment variables to Secret keys (Secrets only, same namespace).

Three consequences, each a decision:

1. **`realm.json` is canonical and contains no users.** It defines the realm, the `ankka-cli`
   public client with the device grant, the `ankka-controlplane` client scope (R5), the
   `platform-admin` realm role, and registration off. It must be a JSON *file* because
   docker-compose's `--import-realm` reads files from a directory and the operator's CR embeds the
   realm inline: the deploy script renders the CR from the file (JSON is YAML, indented under
   `spec.realm:` with `sed`, the same tooling the script already uses to build the schema
   ConfigMap), and compose mounts the file. One copy, two consumers, as for the DDL.
2. **The development user is created after import, by the local paths only.** `deploy-local.sh`
   creates `dev` (password `dev`, email verified, `platform-admin`) through Keycloak's admin API
   with the bootstrap admin credential the script reads from the cluster; compose does the same
   with a one-shot init service running `kcadm.sh`. The remote overlay therefore ships no
   development user *by construction* — there is no file to delete, and nothing creates one.
3. **Realm changes after first import are a migration concern**, not a redeploy: re-running the
   deploy script re-applies the CR, the import finds the realm and skips it. A change to
   `realm.json` on an existing installation is applied by hand in the console, and `README.md`
   says so. This is the operator gap the treatment flagged, accepted for the platform's own realm.

**Alternatives considered**: users in `realm.json` with a placeholder password — the user would
still exist remotely, which FR-010 forbids; a separate local-only realm file — Keycloak imports one
realm per name and skips duplicates, so a second file cannot add users to the first; keeping the
realm inline in the CR and extracting it for compose — the extraction is the same `sed` in the
other direction with a worse failure mode (a YAML edit that is no longer valid JSON).

## R3 — TLS ends at the gateway; the issuer is the external URL and keys come from inside

**Verified upstream and in-repo.** The Keycloak CR has `spec.http.httpEnabled`,
`spec.hostname.{hostname,strict}`, `spec.db.*` with `usernameSecret`/`passwordSecret`,
`spec.ingress.enabled`, `spec.bootstrapAdmin` (a Secret with `username`/`password`; if
unspecified the operator generates `<name>-initial-admin` with `temp-admin`), and
`spec.additionalOptions` for anything else. In-repo, every route is an `HTTPRoute` on the single
Gateway under one wildcard certificate, and the kind cluster publishes HTTPS on host port 8443.

**Decision**:

- `httpEnabled: true`, `ingress.enabled: false`, `hostname.hostname: https://auth.<base>[:<port>]`
  (the port included when it is not 443 — the CR's hostname *is* the issuer, so it must be the
  URL a client sees), `hostname.strict: false` so the in-cluster JWKS fetch on the service address
  is answered, and proxy headers `xforwarded` (`spec.proxy.headers` where the CRD has it, else
  `additionalOptions: proxy-headers`).
- `bootstrapAdmin.user.secret` names `ankka-keycloak-admin` explicitly. With the secret deleted by
  the remote overlay the operator cannot create the instance — FR-010's "cannot start until an
  administrator secret is supplied" holds literally, rather than the operator quietly generating
  one.
- The control plane carries two values: `ankka.controlplane.auth.issuer` (what a token's `iss`
  must equal, `https://auth.<base>:<port>/realms/ankka`) and `ankka.controlplane.auth.jwks-url`
  (`http://<keycloak service>.ankka-auth.svc:8080/realms/ankka/protocol/openid-connect/certs`).
  They differ because inside the cluster there is no TLS to trust and no gateway to traverse. The
  issuer is derived in the overlay from the same `ankka-platform` ConfigMap values the control
  plane already receives as `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`, so `deploy-local.sh`'s
  `sed` substitutions cover it.
- The `HTTPRoute` at `auth.BASE_DOMAIN` follows `components/controlplane/httproute.yaml` exactly,
  including the overlay `replacements` entry. `auth` is one label, so it can never collide with
  `<service>-<project>`, the argument `api` already makes. **Found in implementation**: the route
  also sets `X-Forwarded-Port` to the HTTPS port. With a bare hostname Keycloak takes the issuer's
  port from the forwarded headers, and Envoy forwards none, so through kind's 8443 it advertised
  and signed a port-less issuer (`EndToEndClusterSuite` caught it; a probe of the image showed
  `X-Forwarded-Port` and `X-Forwarded-Host` fix it and `Host` does not).

**Alternatives considered**: TLS into Keycloak with the wildcard — a second place the certificate
must be mounted and rotated, for a hop inside the cluster; fetching keys over the external URL —
would require the control plane pod to trust the local CA and to route out through the gateway.

## R4 — nimbus-jose-jwt, in `controlplane` only

**Verified upstream.** `com.nimbusds:nimbus-jose-jwt` 10.9.1 (31 May 2026), minimal
dependencies, the library Keycloak itself and most JVM resource servers use. It provides remote
JWKS sources with caching and rate-limited refresh (`JWKSourceBuilder`) and a `DefaultJWTProcessor`
that checks signature, expiry and a claims verifier.

**Decision**: `TokenVerifier` wraps nimbus with a JWK source pointed at `jwks-url`, cached,
refreshed on a schedule and once on an unknown `kid`, and refuses when the source is unavailable
with a distinct outcome the endpoint maps to 503 (FR-005). Checks: signature (RS256/ES256 only —
`none` and HMAC rejected by construction, since the processor is configured with the asymmetric
algorithms alone), `iss` equals the configured issuer, `aud` contains the configured audience (R5),
`exp`/`nbf` with 60s skew, `typ` is `Bearer` (Keycloak's access-token type), and `sub` present.
Verification is CPU only after the first fetch, which is what SC-005 measures.

**Alternatives considered**: jwt-scala — a Scala wrapper that would still bring a JOSE
implementation and has thinner JWKS support; pac4j — a framework, most of which would be unused;
Keycloak's own adapter — deprecated upstream in favour of standard libraries; hand-rolling over
`java.security` — possible, and exactly the kind of code that is wrong in a way tests cannot see.

## R5 — Audience by client scope, not by client list

**Assumption, verified by the real-Keycloak suite.** By default a Keycloak access token's `aud`
does not name the requesting client; it is populated by mappers. The standard shape is an
*Audience* protocol mapper carried by a client scope.

**Decision**: `realm.json` defines a client scope `ankka-controlplane` holding an audience mapper
(`included.custom.audience: ankka-controlplane`), plus the `email` and `email_verified` mappers,
and makes it a default scope of `ankka-cli`. The verifier requires `aud` to contain
`ankka-controlplane`. A CI client is any confidential client an administrator creates with that
scope assigned — the control plane never learns client ids, so adding a client is a Keycloak
operation only.

**Alternatives considered**: checking `azp` against a configured client list — makes every new CI
client a control plane configuration change and a restart; accepting any `aud` — a token minted for
another resource server in the same realm would be accepted here.

## R6 — Device authorization grant with offline refresh, credentials beside the config

**Assumption, verified by the device-flow suite and the real-Keycloak suite.** Keycloak
implements RFC 8628; the device authorization endpoint is
`/realms/{realm}/protocol/openid-connect/auth/device`, the token endpoint takes
`grant_type=urn:ietf:params:oauth:grant-type:device_code`, and the discovery document at
`/realms/{realm}/.well-known/openid-configuration` names both. A client enables the grant with the
attribute `oauth2.device.authorization.grant.enabled: "true"` in the realm export. Requesting
`scope=openid offline_access` yields an offline refresh token whose idle lifetime is measured in
days rather than the realm's 30-minute SSO idle, which is what a CLI needs.

**Decision**:

- `ankka login` asks `GET /auth` on the configured control plane for the issuer and client id
  (contracts/http-api.md), fetches discovery from the issuer using the configured trust root,
  requests a device code, prints the verification URL and user code (and tries the platform's
  browser opener, ignoring failure), then polls the token endpoint honouring `interval`,
  `authorization_pending` and `slow_down`, until `expires_in` runs out.
- Credentials live in `credentials.json` beside `config.json`, mode 0600, keyed by control plane
  URL: `{issuer, clientId, refreshToken, accessToken, expiresAt}`. The path derives from
  `Settings.path`'s directory so `-Dankka.config` and `ANKKA_CONFIG` isolate it too (the trap in
  `CLAUDE.md` about `$HOME` applies unchanged).
- On every command: the token flag or `ANKKA_TOKEN` wins verbatim; else the saved login's access
  token if it has more than 30 seconds left; else a refresh; else "not logged in; run
  `ankka login`", exit 1. A 401 from the control plane after a successful refresh is reported the
  same way. `logout` revokes the refresh token at the issuer's revocation endpoint if discovery
  names one, then deletes the entry.

**Alternatives considered**: authorization code + PKCE with a loopback redirect — needs a browser
on the CLI's machine, false over SSH; storing tokens in the OS keychain — a native dependency in
the one module defined by having none; a single file for config and credentials — `config get`
prints the config, and the token must never be printed.

## R7 — `http` learns what a principal is, not what a token is

**Verified in-repo.** `Acl` is an enum with `DenyAll`, `AllowAll` and `AllowIf(predicate)`;
`HttpServer.permitted` folds it to a boolean and every refusal is a 403 with "not permitted by this
endpoint's acl". `RequestContext` is built once per request and shared by the ACL and the handler.
`HttpProblem.unauthorized` exists and is unused.

**Decision** (contracts/http-principal.md): add `Acl.Authenticate(RequestContext =>
AuthDecision)` where `AuthDecision` is `Allow(principal)`, `Unauthenticated(challenge)`,
`Forbidden(reason)` or `Unavailable(reason)`; add `Principal(subject, name, email, emailVerified,
roles, claims)` as plain data; add `principal: Option[Principal]` to `RequestContext`, populated
by the server before dispatch so `request.principal` is readable in a handler on its own thread
(the `ThreadLocal` rule applies unchanged). `Unauthenticated` answers 401 with
`WWW-Authenticate: Bearer realm="<realm>", error="invalid_token"`; `Forbidden` 403; `Unavailable`
503. The three existing cases are untouched, so every existing endpoint compiles and behaves as
before — the change is additive to a published module, within the compatibility rule.

**Alternatives considered**: putting the JWT verifier in `http` so applications could use it —
adds nimbus to a published library for a consumer that does not exist yet; that is the follow-on
feature's decision to make. Reusing `AllowIf` and throwing `HttpProblem.unauthorized` from the
predicate — works, and hides the 401/403 distinction in an exception the type does not mention.

## R8 — Authorization is resolved from entities; listings are scoped by a view row

**Verified in-repo.** `ProjectEndpoint` already reads `OrganizationEntity.exists` for a create
and `ServiceEndpoint` already resolves a project; a view row is one JSON `payload` per entity
(`ViewStore.createTable`: `row_key`, `payload TEXT`), queried with `SqlFragment`s such as
`jsonText("organizationId")`.

**Decision**:

- `Authorization.roleIn(principal, organizationId)` is a query on `OrganizationEntity`
  (`roleOf(subject)`), answering `Owner`, `Member`, `None`, and whether the organization is
  disabled. A project or service route resolves `ProjectEntity.get` → `organizationId` → role: two
  entity calls, both in-memory once the shard is warm, and never a view. Removal is therefore
  visible on the next request (FR-020, SC-003).
- Platform admins short-circuit to `Owner` on every organization and the call is marked
  administrative, which the endpoint passes down as the `Actor.administrative` flag on the
  command (FR-012, FR-023).
- Non-membership on a direct read is `NotFound` with the same message a missing id gives (FR-022).
- `OrganizationRows`' row gains `members: Vector[String]` (subjects), `invitations:
  Vector[String]` (emails) and `disabled: Boolean`. `GET /organizations` filters with a JSON
  containment predicate on the payload (`(payload::jsonb) @> '{"members":["<sub>"]}'`), a new
  `SqlSyntax.jsonContains` fragment; `GET /projects` first lists the caller's organizations then
  filters projects by `organizationId IN (...)`. Views lag by design and the spec allows it for
  listings (FR-021).

**Alternatives considered**: one row per member — the runtime keys view rows by the entity's id,
so a per-member row would need a second view keyed on a composite subject that the change source
does not produce; a membership cache in the endpoint — invalidation is the whole problem, and the
entity *is* the cache.

## R9 — Invitations are claimed on refusal and on listing, never on every request

**Decision.** FR-017 says a pending invitation becomes a membership "the first time a request
arrives" from a matching verified email. Checking a pending-invitations view on every request
would put a database query on the hot path SC-005 measures. Instead the claim runs exactly where it
changes the answer:

1. When a membership check is about to refuse, `InvitationClaim` asks the *target*
   organization's entity whether an invitation is pending for the principal's verified email; if
   so it issues `claimInvitation(subject, email, actor)` and re-evaluates. One extra entity call
   on a path that was going to fail anyway.
2. On `GET /organizations` and `GET /auth/whoami`, it queries the view for organizations whose
   `invitations` contain the email and claims each, so a user's first listing after being invited
   already shows the organization.

A member's ordinary request costs nothing extra. An unverified email never claims (FR-017). The
control plane needs no Keycloak credential for any of it (FR-011).

**Alternatives considered**: claiming at login — the control plane does not see logins; claiming
on every request — measured cost for no observable benefit.

## R10 — Suspension: a consumer for latency, the sweep for correctness

**Verified in-repo.** `ProjectionTrigger` is a `Consumer` on `ServiceEntity`'s events that
projects immediately, documented as "the latency half of the trigger pair; the sweep is the
correctness half". `ServiceProjector` runs a cluster-singleton sweep every 30s over every service.
`Service` already separates desired `paused` from observed `lifecycle`, with the comment that
desired and observed "do not share a field".

**Decision**:

- `Service` gains `suspended: Boolean` (desired state, set by the organization, distinct from
  `paused` which members own). `targetInstances` is 0 when either is true. `ServiceLifecycle`
  gains `Suspended`, set by `onSuspended` the way `onPaused` sets `Paused`; `onReinstated` restores
  `Paused` if `paused`, else `UpdateInProgress`.
- `SuspensionTrigger` is a `Consumer[OrganizationEvent, Nothing]` on `OrganizationEntity`: on
  `OrganizationDisabled`/`OrganizationEnabled` it enumerates the organization's projects and their
  services through the views and commands `suspend`/`reinstate` on each, idempotently. Any other
  event is ignored.
- The sweep gains one step: for every service row whose organization row is `disabled` and whose
  state is not suspended, command `suspend`; and the reverse for enabled organizations with
  suspended services. This closes the spec's edge case (an apply that passed the endpoint's
  disabled check before the disable landed, whose row the trigger's enumeration missed because the
  view lagged) within one sweep interval, with no lock anywhere.
- Every write route on projects and services checks `disabled` in the role resolution (R8) and
  refuses with `Conflict` naming the organization as disabled (FR-034).

**Alternatives considered**: the projector rendering `instances: 0` by reading the organization
at render time — stops the workload but leaves the service's own status saying `Ready` or
`Paused`, which FR-036 forbids; a saga in the endpoint that fans out synchronously — the same view
lag with a worse failure mode (a half-finished disable on a control plane restart).

## R11 — Actor on events, history in state, time from the endpoint

**Verified in-repo.** Events carry no timestamp and no actor; jsoniter applies defaults for absent
fields, which `CLAUDE.md` documents for `Option`s; the entity `get` query returns state, and
nothing reads the journal directly.

**Decision**: every command-produced event on all three entities gains `actor: Option[Actor] =
None` (`Actor(subject, display, administrative)`) and `at: Option[Instant] = None`, supplied by
the endpoint in the command (the handler is pure). `ServiceObserved` gets neither — it is the
operator's report. `Service` keeps `history: Vector[HistoryEntry]` of the last 50 command-produced
changes (kind, generation, actor, at), maintained in the fold so it replays; `GET
/services/{p}/{n}/history` returns it. Pre-feature events replay with `None` and appear as
unattributed (FR-024). `Organization` keeps who invited whom on the `Member` and `Invitation`
records themselves, which is what the members listing shows.

**Alternatives considered**: a journal read API in the runtime — a real feature for a real need,
neither of which exists yet; Pekko event metadata — invisible to the fold, so it could not shape
`history`.

## R12 — Testing: three levels, one of them real, and the cluster suites unchanged in cost

**Verified in-repo.** `ControlPlaneHttpSuite` builds the ACL with `ControlPlaneAcl.bearer` and
sends a header per request; the k3s suites run the control plane in the test JVM against the
node's kubeconfig and deploy only what a case needs to be `Ready`; `GatewayStack` installs
controllers with the node's `kubectl` from the same pinned URLs the components reference;
`RemoteOverlaySuite` renders both overlays with `kubectl kustomize`.

**Decision**:

| suite | what it proves | cost |
|---|---|---|
| `TokenVerifierSuite` (new, unit) | every rejection reason; unknown `kid` refetch once; rotation; skew; `none`/HMAC refused; unavailable JWKS → `Unavailable` | ms |
| `ControlPlaneHttpSuite` (extended) + `AuthorizationMatrixSuite` (new) | the ACL is `oidc` against an in-process JWKS on `jdk.httpserver`; two users, two organizations, platform admin, every role × every route, invitation claim on refusal and on listing, last-owner rule, 404-not-403, disable/enable including the sweep step, history and actors | seconds |
| `DeviceFlowSuite` (new, cli) | `login` against a scripted authorization server: discovery, `authorization_pending`, `slow_down`, expiry, offline scope requested, credentials file mode and per-URL keying, refresh, `logout` | seconds |
| `KeycloakRealmSuite` (new, testcontainers) | `quay.io/keycloak/keycloak:26.7.4 start-dev --import-realm` with the shipped `realm.json`; a test client and user created through the admin API; real tokens carry `aud`, `email_verified` and realm roles the verifier expects; a service-account token with a verified email claims an invitation | ~40s |
| `EndToEndClusterSuite` (extended, k3s) | the Keycloak component installed as `deploy-local.sh` installs it (operator, CNPG cluster, CR, route); a login through the gateway with a client created by the suite; every route 401 without a token through the real address (SC-001) | +60–90s |
| `RemoteOverlaySuite` (extended) | the remote overlay deletes the admin secret and contains no dev user; both overlays route `auth.<base>`; the issuer replacement lands in the control plane's env | seconds |
| `SuspensionSuite` (new) | the trigger and the sweep step on `AnkkaTestKit`, including the lagging-view case injected by disabling before the row exists | seconds |

The device flow's *interactive* half (Keycloak's login form) is not driven from a test; the
scripted server exercises the CLI's side and the real-Keycloak suite exercises Keycloak's. The
other k3s suites keep an in-process JWKS: they test reconciliation, and the deploy path is proven
twice — `EndToEndClusterSuite` (the control plane in the test JVM verifying against the deployed
Keycloak's keys over a port-forward, tokens minted through the gateway) and
`ControlPlaneClusterSuite` (the control plane *image* deployed beside Keycloak, deriving its issuer
from the base domain and the mapped HTTPS port) — and by the deploy script's smoke test.

**Settled in implementation**: `KeycloakRealmSuite` also runs a control plane against the real
realm's keys (US4's proof) — the one place real tokens meet the real verifier — so "the two ends
disagreeing" is caught in seconds, not only in the cluster suites.

**A benchmark for SC-005**, run the way feature 007 ran SC-003: one real request through HTTP,
entity, journal and reply, with and without verification, on the same harness.
`VerificationOverheadBenchmark` (T031, 2026-09-22): a single ordered pair measured verification
as *negative* (the second run inherits the first's warm JIT and pool), so the suite now alternates
three runs of each ACL and takes the best; the baseline is a constant-principal `Acl.Authenticate`
rather than `AllowAll`, because every endpoint now stamps commands with the caller and needs one.
One request measures about 0.8–1.0 ms on this laptop; the RSA signature check is on the order of
10 µs, inside the run-to-run noise and well under the 1% budget. The assertion allows 5% for that
noise.

## Verify at implementation

Assumptions above that a task must confirm before building on them, each with the suite that
pins it:

1. **Settled (T004/T033).** The 26.7.4 CRD has `spec.proxy.headers` (seen in the rendered CRD
   schema); the component uses it. Two things the upstream docs did not say and the k3s spike
   did: the operator crash-loops unless all *four* of its CRDs exist (the OIDC and SAML client
   kinds too), and a kustomize Component's `namespace:` transformer runs over the whole overlay,
   so the operator's manifests sit in a nested Kustomization of their own.
2. **Settled by `EndToEndClusterSuite`** (run recorded below): with `httpEnabled: true` and
   `hostname.strict: false` the instance reaches `Ready` with no `tlsSecret`, behind the gateway's
   TLS.
3. **Settled (T002, 2026-09-22).** `oauth2.device.authorization.grant.enabled: "true"` on the
   client enables the grant; the `ankka-controlplane` scope's audience mapper puts
   `ankka-controlplane` in `aud`. Two findings changed the realm: Keycloak writes a lone
   audience as a *string* and several as an array (the verifier and the tests read both), and a
   token carries no `sub` unless a scope maps it — the built-in `basic` scope was not attached to
   a client created through the admin API with an explicit scope list — so the control plane's
   own scope now carries a subject mapper and needs nothing else on the client.
4. **Settled (T002).** `scope=openid offline_access` yields `refresh_expires_in: 0`, an offline
   token. Idle-timeout behaviour is trusted from the realm setting, not measured.
5. **Settled (T003).** A service-account user accepts an email and `emailVerified` through the
   admin API and its client-credentials token then carries both, plus the audience. Users also
   need a first and last name or a password grant fails with "Account is not fully set up" —
   the deploy script and compose set them on `dev`.
6. jsonb containment on the view payload performs acceptably for listing (`AuthorizationMatrixSuite`
   for correctness; no scale test — the installation sizes in scope are tens of organizations).
7. Deleting `ankka-keycloak-admin` under a CR that references it leaves the instance uncreated
   rather than created with a generated secret (`RemoteOverlaySuite` can only render; this one is
   checked by hand against the remote overlay and recorded in `quickstart.md`).

## Implementation record (2026-09-22)

- Every fast suite green: `sbt -Dankka.cluster.tests=off -Dankka.template.tests=off test`.
- `EndToEndClusterSuite` 12/12 with the deployed operator, instance and realm, tokens minted
  through the gateway, and the advertised issuer asserted equal to the derived one.
- `ControlPlaneClusterSuite` 8/8 with the control plane *image* deployed beside Keycloak.
- `SampleDeploymentClusterSuite` 5/5; `ExposureClusterSuite` 9/9 on a clean run (two earlier
  runs failed on the sample's readiness and a rolling-restart timeout while the compose Keycloak
  shared the Docker VM); `MultiNodeClusterSuite` 7/8, the crash case failing in its own SIGKILL
  mechanics (the container had been replaced before it was inspected), not on anything here.
- `docker compose up -d keycloak keycloak-init` imports the realm, creates `dev` with
  `platform-admin`, and answers a device authorization request.
- Item 7 (the remote overlay's deleted admin secret) remains a by-hand check; tier 4 on kind was
  not run — the machine's context was a production cluster.

