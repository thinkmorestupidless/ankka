# Research: A Platform a Hosted Product Can Provision

Every item below was settled by reading the code on branch `011-hosted-ankka`; none was a
guess. Line references are to the tree as of the plan's date.

## R1. Where the creation policy lives and how it reaches the endpoint

**Decision**: a new `OrganizationPolicy(creation: OrganizationCreation, signupUrl: Option[String])`
in `controlplane/tenancy/`, with `OrganizationCreation` an enum `Open | PlatformAdmin`, loaded by
`OrganizationPolicy.from(config)` from a new `ankka.controlplane.organizations` section:

```hocon
organizations {
  creation = "open"
  creation = ${?ANKKA_ORGANIZATION_CREATION}
  signup-url = ""
  signup-url = ${?ANKKA_SIGNUP_URL}
}
```

`from` refuses any value but `open` and `platform-admin` with an `IllegalArgumentException` naming
the key and both values (FR-005), the same shape as `AuthConfig.from`'s refusal of an empty issuer.
`ControlPlane.endpoints` gains `policy: OrganizationPolicy = OrganizationPolicy.default` and passes
it to `OrganizationEndpoint`; `ControlPlane.builder` loads it from `config`.

**Rationale**: `DeployConfig` is "everything the control plane needs to know about its deployment
target" and this is not about the target. `AuthConfig` is about tokens. A third small value keeps
each one about one thing, and the loader pattern (read once at startup, refuse bad input before
serving) is the one both already follow.

**Alternatives**: a field on `DeployConfig` — wrong home, and `ServiceEndpoint` would carry a
policy it never reads. A realm role — rejected in the treatment (D4): subscriptions are per
organization, roles per user.

## R2. Enforcement is in the endpoint, before the entity

**Decision**: `OrganizationEndpoint`'s `postBody("/{organizationId}")` becomes:

1. if `request.owner.isDefined` and the caller is not a platform administrator → `CommandError(
   "platform administrator role required to name an owner", Forbidden)` (FR-010);
2. if `policy.creation == PlatformAdmin` and the caller is not an administrator →
   `CommandError(policy.refusal, Forbidden)` (FR-002, FR-003);
3. if `request.owner` is defined → `OrganizationEntity.createForOwner` with administrative
   attribution;
4. otherwise today's `createOrganization` with `authz.anyone(principal)`.

`policy.refusal` is `"organizations in this installation are created by the platform
administrator"` plus `"; sign up at <url>"` when `signupUrl` is set.

**Rationale**: the entity records who asked; the endpoint decides who may ask
(`OrganizationEntity`'s own doc comment). The CLI already prints a 403's message verbatim
(`ControlPlaneClient.explain`: `not permitted: <detail>`), so FR-003's "the CLI prints it" needs no
CLI change. `Forbidden` maps to 403 today (`requireAdmin` proves it).

**Alternatives**: checking in the entity — it cannot see the caller's roles, only the attribution,
and the attribution's `administrative` flag means "membership did not let them through", not
"holds the role".

## R3. A new entity command, not an edited payload

**Decision**: `OrganizationEntity` keeps `command("create")(_.create)` taking a `String` and gains
`command("create-for-owner")(_.createForOwner)` taking `CreateForOwner(name, owner: Owner)`, a
domain command in `domain/events.scala` beside `AddMember`. The handler applies the same rules as
`create` (deleted → conflict, known → conflict, empty name → error) and persists
`OrganizationCreated(name, actor, at, owner = Some(owner))`.

**Rationale**: `CLAUDE.md`, *Registration and handler identity*: the wire name is a versioning
boundary because in-flight requests must survive a rolling deploy. The control plane rolls with
`maxSurge: 1`, so for a while old and new instances share the journal and the shard for an
organization may live on either; a `create` whose payload changed from `String` to a record would
fail to decode on the old instance. A new name is decoded by nobody old, which is a `ModuleCommand`
refusal rather than a corrupt request.

**Alternatives**: changing `create` to take `CreateOrganization` — the rolling-update hazard
above, for no gain.

## R4. The event carries the owner; both folds seat them

**Decision**: `OrganizationCreated(name, actor = None, at = None, owner: Option[Owner] = None)`,
with `Owner(subject, email = None, display = None)` the wire type from `controlplane-api` (as
`Role` already is in events). `Organization.onCreated` seats `owner` when present — `Member(Owner,
email as key, display, at, addedBy = actor.subject)` — and otherwise derives the first owner from
the actor exactly as today. `OrganizationRows.onCreated` does the same for the listing row's
`members`/`owners` vectors (line 57), or the owner's `GET /organizations` will not list it
(`Authorization.visible` filters on `jsonContains("members", subject)`).

**Rationale**: FR-009 wants actor and first owner both recorded; the actor field already means
"who asked" everywhere, so the owner is a separate field. `EventCompatibilitySuite` pins the
pre-feature JSON (`OrganizationCreated(name, actor, at)` at line 32) and passes untouched because
the new field has a default — the suite's pattern match needs the fourth field added.

**Alternatives**: putting the owner in `actor` — makes the administrator disappear from the
audit trail, which is the opposite of the point.

## R5. The wire type and its codec

**Decision**: in `controlplane-api/descriptors.scala`, `CreateOrganization(name: String, owner:
Option[Owner] = None)` and `final case class Owner(subject: String, email: Option[String] = None,
display: Option[String] = None)`. `Codecs.make` handles the option with a default, so a body of
`{"name": "Acme"}` decodes as today; an old CLI is unaffected.

**Trap to respect**: jsoniter reads a JSON `null` on an `Option` field as absent and applies the
default — fine here, because the default is `None`; do not give `owner` any other default.

## R6. Administrative attribution for the owner path

**Decision**: `Authorization` gains `def administrator(principal: Principal): Metadata`, which is
`attribution(principal, administrative = true)` after checking the role (throws the same
`Forbidden` as `requireAdmin`), so the `MemberAdded`-style `addedBy` on the seated owner is the
administrator's subject and the event's actor has `administrative = true`.

## R7. Publishing `ankka-controlplane-api`

**Decision**: delete `publish / skip := true` from `controlPlaneApi` in `build.sbt` (line 263).
`ThisBuild / organization`, `licenses`, `developers` and `publishTo` already apply to every
project, so nothing else is needed for the artifact to reach the same `sonaRelease`.
`templateArtifacts` stays at six — the template is a service and does not depend on it — and its
comment says so. Documentation: the Scala SDK stays "six libraries a service depends on"
(`scala-sdk.md`, `install.md`); a short section names the seventh as what a client of the control
plane depends on, with its package; `CLAUDE.md`'s *Publishing* section says seven and why.

**Rationale**: FR-013 to FR-015. The module was written to be a client's library ("the CLI needs
to know what a service descriptor looks like, and should not drag Pekko … to find out").

**Alternatives**: extracting the CLI's `ControlPlaneClient` into the module too — deferred; it
would pull the CLI's `Settings` and session handling along, and the treatment does not need it.

## R8. The spoke proof

**Decision**: in `EndToEndClusterSuite`, after the hub control plane is up, start a second
in-process control plane — its own `AnkkaTestKit` (its own Postgres), `DeployConfig.default.copy(
namespacePrefix = "spoke", baseDomain = Some(s"spoke.$BaseDomain"))`, and an `AuthConfig` whose
`issuer` is the *hub's* derived issuer and whose `jwksUrl` is the same forwarded Keycloak service
address. Assert: `GET /auth` advertises the hub's issuer, not `derivedIssuer("spoke.…")`; `GET
/auth/whoami` with the hub-minted `Token` is 200; and a token minted by a `TestIdentity` for
another issuer is 401. The spoke's endpoints need no projector — no service is deployed through
it — so it is `ControlPlane.endpoints(aclFor(spokeAuth), spokeDeploy, Some(spokeAuth))` over
`ControlPlane.components` with `ProjectionRuntime()` alone.

**Rationale**: the suite already has a real Keycloak, a real token, and the forwarded JWKS
address; a second control plane in the same JVM is what `MultiNodeClusterSuite` already does with
`AnkkaTestKit`, and the cluster overlay gives each a random remoting port. The shipped manifest's
wiring of `ANKKA_AUTH_ISSUER` is one `${?ENV}` line already exercised by compose.

**Alternatives**: a second Deployment of the shipped manifest in `ControlPlaneClusterSuite` — a
second CNPG cluster (two services never share a database) and a node already running three control
plane instances; the k3s node's latency trap says no.

## R9. Documentation

**Decision**: the control plane's settings are documented in prose on `docs/platform/identity.md`
and `install-cloud.md`, not in the generated configuration table (which covers a *service's*
`reference.conf` files, `tools/docs/generate.py` line 41); so FR-006 is satisfied by a new "Who
may create organizations" section on the identity page naming both variables, and FR-019 by a
"A spoke installation" section on the cloud install page. `control-plane-api.md`'s prose for
`POST /organizations/{organizationId}` describes the body, the owner option and both refusals;
the generated route table is unchanged (no new route). `cli.md` is regenerated by
`CliReferenceSuite` after the flags land.

## R10. Test placement

**Decision**: the restricted policy needs its own server, so a new `OrganizationCreationPolicySuite`
(one `AnkkaTestKit`, `TestIdentity`, policy `PlatformAdmin`, `signupUrl = Some(…)`) covers US1
scenarios 2–5. The owner option under the default policy joins `AuthorizationMatrixSuite`, which
already has alice, bob and carol (admin). `OrganizationPolicySuite` is a plain unit suite for the
loader. `TenancyEntitySuite` gets the `create-for-owner` fold and replay (US2 scenario 7 is a fold,
so it is proven by folding, not restarting).
