# Design treatment: an authenticated, multi-user control plane

**Status**: treatment, to be fed to `speckit-specify` as feature 008.
**Date**: 2026-09-22

## The problem

The control plane is reachable by anyone holding one shared bearer token. `ControlPlaneAcl`
says so itself: "a shared token is not identity — it cannot tell two operators apart, and it says
nothing about which projects a caller may touch. It is deliberately the floor rather than the
ceiling." `README.md` repeats it. This feature is the ceiling arriving.

Three things are missing, in order of dependency:

1. **Identity.** Nothing in the platform knows *who* made a request. The journal is described as
   the audit trail, and with one token it is an audit trail with no actor column.
2. **Registration.** Nothing decides who is a user of an installation at all. The remote overlay
   deletes the development token and leaves "create a real one out of band" — one secret, shared
   by every person who will ever operate the platform.
3. **Tenancy that means something.** Organizations exist as an entity but bound nothing: any
   token can create, rename, delete and deploy into any organization. A project's organization is
   a label, not a boundary.

The ask: the control plane accepts only authenticated, registered users; projects and services
belong to an organization; an organization has members; users are managed in a self-hosted
Keycloak so the platform gains no third-party dependency.

## What this feature is not

- Not authentication for the services ankka *hosts*. A deployed service's endpoints keep their own
  `acl`; nothing here changes `modules/http`'s posture for applications. (See "Follow-ons".)
- Not per-project roles, teams, or fine-grained permissions. Membership is on the organization.
- Not a web UI for the control plane. The CLI is the client; the local console stays local and
  credential-free, exactly as `README.md` states.
- Not a user directory of ankka's own. Users, passwords, MFA, federation and password resets are
  Keycloak's, entirely.

## The shape, in one paragraph

Keycloak is the *only* authenticator; the control plane is the *only* authorizer. Keycloak issues
OIDC tokens and decides who is a user of the installation. The control plane verifies those tokens
offline against the realm's published keys, takes the caller's stable subject id as their identity,
and answers every authorization question — which organizations this person belongs to and with
what role — from its own event-sourced state. Organization membership is a fact the Organization
entity records, so it replays, audits and enforces exactly like every other fact the control plane
holds. Keycloak never learns what an organization is, and the control plane never holds a Keycloak
credential.

## Decisions, with the alternatives they beat

### D1. Keycloak authenticates; the control plane authorizes

**Chosen**: identity from Keycloak (who you are, proven by a signed token); membership and role
from the control plane's `Organization` entity (what you may touch).

**Rejected**: modelling organizations as Keycloak groups and reading membership from token claims.
It puts tenancy in two places that must agree. The control plane's endpoints already refuse a
project in an organization that does not exist; they would then be refusing against a copy of
reality that Keycloak owns and the journal cannot replay. It also makes every membership change an
admin-API call the control plane must be credentialed for — see D6.

**Rejected**: Keycloak Authorization Services (UMA, resource server). Powerful, and it moves the
policy decision point out of the process that holds the facts the decision needs. Cross-entity
checks live in the endpoint in this codebase for exactly the reason they should live there here.

### D2. The caller's identity is the token's `sub`, never the email

`sub` is stable for the life of the Keycloak user; an email is changeable and reassignable. Every
membership record and every actor stamp on an event stores `sub`. The email and display name in the
token are carried for *display* only — listings, `whoami` — and are never a key.

### D3. Two organization roles: `owner` and `member`

- **owner**: everything a member can do, plus manage members, rename and delete the organization.
- **member**: create, rename and delete projects in the organization; every service operation in
  those projects (apply, pause, resume, restart, logs, expose, unexpose, delete).

The creator of an organization is its first owner. The last owner cannot be removed or demoted
(an organization with no owner is one nobody can administer). A read-only `viewer` role is the
obvious third; it is deferred, and the role set is an explicit clarification point below.

### D4. Any authenticated user may create an organization

The alternative — only a platform administrator creates organizations — means the person who
installs the platform is on the critical path of every new tenant. Registration is already gated
(D8): being a user of the installation at all is the admission decision, and once admitted,
creating a tenancy of your own is the GitHub/Google Cloud model. Organization ids stay globally
unique and tombstoned as today.

### D5. One installation-level role, `platform-admin`, carried by Keycloak

Someone has to be able to see every organization, remove a dead one, and add an owner to an
organization whose owners have all left. That is a fact about the *installation*, not about a
tenant, so it is the one authorization fact Keycloak carries: a realm role, `platform-admin`,
present in the token's realm-roles claim. It is not a way around D1 — the control plane still
makes the decision; it merely reads this one input from the token.

### D6. Members are added by invitation, and the control plane holds no Keycloak credential

`ankka organizations members add acme --email alice@example.com` must turn an email into a
`sub`. Asking Keycloak's admin API for it requires the control plane to hold an admin-capable
credential — the shape this codebase went to some length to avoid with the operator split.

**Chosen**: the owner's command records a *pending* membership keyed by email on the Organization
entity. The first time a token arrives whose `email` claim matches and whose `email_verified` is
true, the endpoint claims the pending membership for that `sub` and records the membership event.
Pending memberships are visible in the members listing and can be revoked before they are claimed.
A user who does not exist in Keycloak yet can be invited; the platform sends no email (Keycloak's
own registration and invitation are the user's path in).

This keeps the invariant that the control plane holds nothing able to act on another system, and
it works for users who do not exist yet.

### D7. The CLI logs in with the OAuth 2.0 Device Authorization Grant

`ankka login` asks the control plane where its issuer is (an unauthenticated discovery route —
the one route that must be open, and it reveals only an issuer URL and a public client id), starts
a device authorization against Keycloak, prints the verification URL and code (opening a browser
where one exists), polls, and stores the resulting refresh token in a credentials file beside
`config.json`, mode 0600, keyed by control plane URL. Access tokens are refreshed silently; a
refresh that fails says "run `ankka login`".

**Rejected**: authorization code with a loopback redirect. It needs a browser on the same machine
as the CLI, which is false over SSH and in a container. Device flow is what `gh`, `gcloud` and
`akka` use for the same reason.

`--token` / `ANKKA_TOKEN` survive, with changed meaning: "present this bearer as-is". For CI it is
an access token obtained from Keycloak with a confidential client's client-credentials grant — a
machine identity is just another OIDC principal, so there is no second authentication mechanism to
secure, document or test. The shared static token is removed outright; there is no compatibility
mode, because a mode that accepts an unverified secret is the thing being removed.

### D8. Keycloak is part of the installation, declaratively, through the Keycloak operator

A `kustomization/components/keycloak/` component, deployed the way CNPG is: the operator's
CRDs and controller first, then a `Keycloak` custom resource for the platform's own instance:

- the Keycloak operator is a fourth CRD-bearing controller beside CNPG, cert-manager and Envoy
  Gateway, installed by `deploy-local.sh` in the same ordered step and listed the same way in
  the remote overlay;
- the platform's instance is a `Keycloak` resource in its own namespace, with its own CNPG
  `Cluster` beside it (a `Database` referencing a `Cluster` in another namespace gets no status
  at all; and two services must never share a database);
- an `HTTPRoute` at `auth.<base domain>` on the platform's single Gateway, under the existing
  wildcard certificate, with TLS terminated at the gateway and Keycloak told so through its proxy
  headers setting. `auth` is one label, so it can never collide with `<service>-<project>`, the
  same argument `api` already makes;
- the realm imported from a `KeycloakRealmImport` resource: realm `ankka`, a public client
  `ankka-cli` with the device grant enabled, the `platform-admin` realm role, `email` and
  `email_verified` in the default scope, and **self-registration off** — an administrator adds
  users in Keycloak's own admin console, which is the registration gate;
- the local overlay adds a development user to the import and a bootstrap admin password from a
  Secret; the remote overlay *deletes* both, exactly as it deletes the development token today,
  so a remote installation cannot start its identity provider until a real admin secret exists
  out of band.

**Why the operator rather than a plain Deployment.** For one instance a Deployment is simpler,
and that is where this treatment started. The operator earns its place because of what comes
next: the platform will want to provision identity *for the services it hosts* — a realm per
project and a client credential per service, the same pattern the operator already follows for
databases through CNPG's `Database` and `DatabaseRole`. With the Keycloak operator, that feature
is the ankka operator writing one more inert `Action` kind (a `KeycloakRealmImport`) beside the
`Database` it already writes, rather than holding a Keycloak admin credential and calling an
admin API. Deploying the platform's own instance through the operator now means that feature
adds a resource instead of replacing a deployment mechanism.

**The platform's identity stays separate from tenant identity.** Tenant realms, when they come,
go on a *second* `Keycloak` resource, so that an upgrade or outage on the tenant side cannot lock
anyone out of operating the platform. With the operator a second instance is a few lines, which
is what makes the separation free to promise now.

**Two things the operator does not do, to be settled by research in the plan.** Its realm
import is one-shot: it creates a realm and does not reconcile later edits to the resource, and
deleting the resource does not delete the realm — so realm lifecycle after creation is not the
cascade CNPG gives a database, and day-two realm changes need the admin API or a config tool.
Neither affects the platform's own realm, which is created once and administered in Keycloak's
console; both matter for tenant realms and belong in that feature's treatment.

`docker-compose.yml` gains a Keycloak in `start-dev` mode with the same realm JSON imported from
a file, so `sbt controlPlane/run` still works with nothing but Docker. The realm JSON is one
copy, referenced by the `KeycloakRealmImport` and mounted by compose, for the same reason the DDL
is one copy.

### D9. Verification is offline, keys are cached, and the request path never calls Keycloak

The control plane verifies signatures against the realm's JWKS, fetched over the in-cluster
service address and cached; an unknown key id triggers one refetch. Checks: signature, issuer
(configured, must equal the token's `iss`), audience/authorized party (`ankka-cli` or a client
the installation lists), expiry, and `typ`. Configuration is two values — the issuer URL tokens
must carry and the URL keys are fetched from — because inside the cluster the two differ: tokens
say `https://auth.<base>/realms/ankka`, keys come from `http://<keycloak service>/realms/ankka/…`
with no TLS to trust.

Startup refuses to run without an issuer configured, for the reason `aclFrom` gives today. But
Keycloak being *down* is a 503 on requests, not a control plane that will not start: the process
whose job is to keep working while other things are broken should not need the identity provider
up to boot, only to admit anyone.

Revocation: membership removal is immediate (checked on every request against the entity); a
disabled Keycloak user is refused within one access-token lifetime. That lifetime is a realm
setting and a clarification point.

### D10. 401 and 403 are different answers, and the HTTP module must be able to give both

Today `Acl.AllowIf` yields a 403 for every refusal. A missing or invalid token is a 401 with a
`WWW-Authenticate: Bearer` challenge (so the CLI knows to say "log in"); a valid token without
membership is a 403 (so the CLI knows to say "you are not a member"). Getting this right needs
`modules/http` to let an ACL establish a *principal* that handlers can read from `request`, and
to refuse with either status. This is an additive change to a published library, kept small and
generic: the module learns what a principal is, not what a JWT is. JWT verification lives in the
control plane.

### D11. Events carry the actor

Every organization, project and service event that a command produced records the `sub` that
issued it. Existing journals have events without it, and replay must not break: the field is
optional in the codec, absent meaning "before this feature". This is what makes "the journal is
the audit trail" true once there is more than one user.

### D12. Listings are scoped; existence does not leak

`GET /organizations` returns the caller's organizations (all of them for a platform admin).
`GET /projects` returns projects in those organizations. A `GET` on an organization, project or
service the caller is not a member of answers 404 — the same answer as for one that does not
exist — never 403, so an outsider cannot probe which ids are taken. Listing goes through a
membership *view* (subject, organization, role) and tolerates projection lag; enforcement goes
through the *entity* and does not.

## User journeys, in priority order

1. **Lock the door.** An administrator deploys the platform; a request without a valid token is refused
   on every route; `ankka login` completes the device flow against the installation's Keycloak;
   the same commands as before then work. Independently testable and the whole security value.
2. **Tenancy holds.** Alice and Bob are users. Alice creates `acme` and is its owner. Bob cannot
   see `acme`, its projects or services, and cannot create a project in it. Alice invites Bob;
   Bob's next request claims the membership; Bob can now deploy into `acme`. Alice removes Bob;
   Bob's next request is refused.
3. **Administer without a credential in the control plane.** Owners manage members from the CLI:
   list, add by email, remove, change role; the last owner cannot be removed; a platform admin
   can act on any organization.
4. **Machines log in the same way.** A CI job with a client-credentials token applies a
   descriptor with no interactive step, and the applied event records that client as its actor.
5. **Read the audit trail.** `ankka services get` (or a history subcommand) shows who applied
   the current generation and when, from the events.

## Functional requirements (for the spec to sharpen)

- Every control plane route except OIDC discovery requires a verified bearer token; a request
  without one is 401 with a `WWW-Authenticate` challenge.
- Verification checks signature (against cached JWKS), issuer, audience, expiry and type; a token
  signed with `none`, an unknown key after one refetch, or a wrong issuer is refused.
- A principal is `sub` plus display claims plus realm roles; only `sub` is ever stored as a key.
- Organizations record memberships (subject, role) and pending memberships (email, role) as
  events on the entity; the creator is the first owner; the last owner cannot be removed.
- Authorization for a project or service route resolves project → organization → caller's role
  through entity queries, never through a view.
- Listings are filtered to the caller's organizations; non-membership answers 404 on direct reads.
- `platform-admin` realm role bypasses membership checks, and every such use is recorded.
- Command-produced events carry the actor's `sub`; absent on pre-feature events.
- The CLI gains `login`, `logout`, `whoami`, and `organizations members list|add|remove|role`;
  refresh tokens live in a 0600 file; the token is never printed in any output format.
- The Keycloak component deploys with `deploy-local.sh` in the same single command, through the
  Keycloak operator, with the realm imported from a resource; the remote overlay ships no
  development user and no admin password.
- The control plane process holds no Keycloak admin credential, and `ControlPlaneAcl.bearer` and
  `ANKKA_CONTROLPLANE_TOKEN` are removed.

## Success criteria

- No route on a deployed control plane answers anything but 401 to a request without a valid
  token — proven by the end-to-end cluster suite against the real gateway, not a unit test.
- A member of one organization can perform no read or write on another's projects or services,
  and cannot learn their ids exist.
- Membership removal takes effect on the next request; a disabled user is refused within one
  access-token lifetime.
- `ankka login` from a fresh machine to a first `services list` takes under two minutes, with
  the local cluster's CA configured exactly as today.
- Token verification adds no network call to a request after the first, and its per-request cost
  is measured against a real request the way the tracing overhead was, not against an empty loop.
- `just up` and `deploy-local.sh` remain one command, and the deploy's smoke test now logs in.

## Testing shape

Three levels, matching the repository's existing pattern of "the two ends must be caught
disagreeing":

- **Verifier unit suite**, in-process signer, no containers: every rejection reason, key
  rotation, clock skew, `alg=none`.
- **HTTP authorization matrix**, `AnkkaTestKit` with the control plane pointed at an in-process
  JWKS: two users, two organizations, every role × every route, plus the pending-membership claim
  and the last-owner rule. Fast and deterministic; this is where the semantics live.
- **One real Keycloak suite**, a testcontainers Keycloak with the realm import from the component,
  minting tokens for real and proving the control plane accepts them — the only test that can
  catch the realm export and the verifier disagreeing. The device flow's client side is tested
  against a scripted authorization server in-process; Keycloak's login form is not driven from a
  test.
- The k3s end-to-end suite deploys the Keycloak component too: the operator, its instance and
  the realm import. Its startup is tens of seconds and a Postgres of its own; that cost is
  accepted because the deploy path — including the operator's CRD ordering — is the thing under
  test.

## Things `speckit-clarify` should be asked to settle

- ~~Role set for v1~~ — settled at specification: `owner`/`member` only; `viewer` deferred.
- ~~Organization creation~~ — settled at specification: self-service (D4), and a platform
  administrator can *disable* an organization, which suspends every service in its projects and
  freezes changes until it is re-enabled (spec FR-032 to FR-037). Suspension is a state of its
  own on the service, separate from a member's pause, so re-enabling restores what members chose.
- Access-token lifetime, which is the revocation latency for a disabled user (5 minutes proposed).
- Whether `platform-admin` is needed in v1 at all, or whether Keycloak's admin adding themselves
  as owner via a one-off is acceptable for the first installation.
- ~~Whether pending invitations expire~~ — they do not; owners see and revoke them (spec assumption).
- ~~Whether the k3s suites run a real Keycloak~~ — settled at implementation: the two suites that
  prove the deploy path (`EndToEndClusterSuite`, `ControlPlaneClusterSuite`) install the real
  operator, instance and realm; the others keep an in-process key set.
- Whether the platform's Keycloak instance should be sized for tenant realms from the start, or
  a second instance is always the answer (D8 says the latter).

## Follow-ons this deliberately leaves out

- **Identity for the services ankka hosts**: a realm per project on a second, operator-managed
  Keycloak instance, a client credential per service delivered like `ANKKA_DB_*`, and a JWT ACL in
  `modules/http` matching Akka's endpoint annotations — a feature of its own, modelled on database
  provisioning (feature 002). D8 chooses the operator so that this adds a resource rather than
  replacing a deployment. It is what makes per-user agent memory, budgets and guardrails possible,
  and Akka does not offer it.
- Per-project roles and read-only access.
- A control plane console in the browser, which would want authorization code + PKCE rather than
  the device grant.
- Federating Keycloak to GitHub/Google. Keycloak supports it with no change on ankka's side; it is
  an installation's realm configuration, not a platform feature.
