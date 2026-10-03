# Research: Service-Level Identity

Decisions, each with what in the code establishes it. File paths are relative to the repository
root. "Today" means `origin/main` at `166cfc6a`, the branch point.

## R1. The verifier moves into a new published module, `ankka-auth-oidc`

**Decision**: a new sbt project `authOidc` at `modules/auth-oidc`, artifact `ankka-auth-oidc`,
`dependsOn(http)`, `libraryDependencies += nimbusJoseJwt`. It is the eighth published module.
`controlPlane` and `sidecar` gain `dependsOn(authOidc)`; `controlPlane` also
`authOidc % "test->test"` for the test issuer. It is not added to `templateArtifacts` or to the
template's `build.sbt` dependencies: the template is a service, and a service without users pays
nothing. The template's `build.sbt` gains a commented line beside `ankka-agent`'s.

**Rationale**: `project/Dependencies.scala:144` says nimbus is "deliberately not in any published
module" and `build.sbt:395` gives it to `controlPlane` alone. The reason was that only the
control plane had users. FR-005 keeps the spirit: nimbus reaches a service only when the service
asks for it. `http`'s `libraryDependencies` are `pekkoHttp` and the testkit (`build.sbt:248-256`)
and stay so.

**Alternatives**: a package in `ankka-http` (nimbus on every service: rejected by FR-005); a copy
in `sidecar` only (Scala services keep copying the control plane's: rejected by FR-004's one
copy); a module under `runtime` (the verifier is HTTP's concern and `runtime` must not depend on
`http`).

## R2. One verifier, several issuers, no fetch at start

**Decision**: `OidcVerifier(config: OidcConfig)` holds one `DefaultJWTProcessor` and one
`JWKSource` per issuer, keyed by the issuer string. `verify(token)` parses the token's header and
claims without verifying (`SignedJWT.parse`), reads `iss`, and selects the issuer; an `iss` that
matches no listed issuer is `Rejected("issuer not accepted")` with no key lookup; a token that is
not a signed JWT with three parts is `Rejected("not a well-formed token")`. The selected
processor verifies signature under `TokenVerifier.Asymmetric` (the same nine algorithms), issuer,
audience, `exp`, `nbf` with the issuer's skew, and a required `sub`. The `typ` check runs only
when the issuer configured one. The key source is nimbus's builder with the control plane's
settings: cache 5 minutes with 15 seconds refresh-ahead, rate limit 30 seconds, outage tolerance
1 hour, retrying. Nothing is fetched at construction; the first verify fetches.

**Rationale**: `controlplane/.../auth/TokenVerifier.scala` is the whole of this already, for one
issuer: the processor (lines 29-40), `verify` (42-58), `Asymmetric` (63-73), `keySource` (118-131)
and the CA-trusting retriever (80-108). Selecting by `iss` before verification is safe because
selection chooses which keys to check the signature with, and a token claiming issuer A signed by
B's key fails A's signature check (US3 scenario 2). Reading `iss` from an unverified token leaks
nothing: the claim is only used as a map key.

**Alternatives**: try every issuer's keys in turn (two fetches for a bad token, and a token for
issuer B could be accepted under A's audience: rejected); one processor with all key sets merged
(audience and skew are per issuer: rejected).

## R3. The named set, parsed once

**Decision**: `OidcConfig.fromEnv(env: Map[String, String]): Either[Vector[String], OidcConfig]`.
`ANKKA_AUTH_ISSUERS` is a comma-separated list of names; each name matches
`[A-Za-z][A-Za-z0-9-]*`, and its variable segment is the name upper-cased with `-` as `_`. For
each name: `ANKKA_AUTH_<NAME>_ISSUER` (required), `ANKKA_AUTH_<NAME>_JWKS_URL` (required),
`ANKKA_AUTH_<NAME>_AUDIENCE` (required), `ANKKA_AUTH_<NAME>_CA` (optional path),
`ANKKA_AUTH_<NAME>_TYP` (optional; `Bearer` is the only value this feature documents),
`ANKKA_AUTH_<NAME>_CLOCK_SKEW` (optional, default 60 seconds, the control plane's default).
`ANKKA_AUTH_REALM` (optional, default `ankka`) is the `realm=` of the challenge. An absent
`ANKKA_AUTH_ISSUERS` is `Right(OidcConfig.empty)`, which is "no issuers": a Scala service that
builds an ACL from it fails at construction naming the variable, and the sidecar refuses an
`AUTHENTICATED` route in discovery (R8). A name listed twice, an unknown variable under a listed
name's prefix, a required variable missing, or a name that is not a valid identifier is a
problem naming it; every problem is reported, not the first.

**Rationale**: the clarification chose the named set so every value is a plain string a descriptor
and the console show, and so a principal names its issuer by a word a handler can read (FR-008).
The control plane's own variables are singular and derived (`AuthConfig.from`, with
`reference.conf`'s `ankka.controlplane.auth`); they are not changed. `fromEnv` takes a map so
tests and the sidecar pass one; `sys.env` is the default.

**Alternatives**: one JSON variable; a numbered set. Both rejected in clarification.

## R4. The sidecar builds the ACL once from the same configuration

**Decision**: `Main.build(…, auth: Option[OidcConfig])`. `run` reads `OidcConfig.fromEnv(sys.env)`;
a `Left` is logged with every problem and exits 1 before dialling the process. `RemoteEndpoint.from`
takes the `Acl` to use for `AUTHENTICATED`: `Oidc.authenticate(config)` when issuers are
configured, else the existing `Unavailable` ACL, which can no longer be reached because discovery
refuses the route (R8) but stays as the backstop. `ConformanceTarget` builds the `OidcConfig` from
the test issuer the suite runs and passes it to `build`.

**Rationale**: `sidecar/.../RemoteEndpoint.scala:146-152` maps `AUTHENTICATED` to a constant
`Unavailable`; `Main.build` (`Main.scala:116-178`) is where every runtime seam is configured and
where tests reach in (`imports`, `models`). `Settings.load` reads `sys.env` directly, which a test
cannot set; the `OidcConfig` parameter follows `models: Models = Models.fromEnv()`'s shape.

## R5. The protocol gains claims and issuer; version 1.4

**Decision**: `endpoint.proto` `Principal` gains `map<string, string> claims = 6;` and
`optional string issuer = 7;`. `WireProtocol.Version` becomes `"1.4"`; the three SDKs declare
`"1.4"`; `protocol/README.md` and `docs/reference/sidecar-protocol.md` record what 1.4 added.
`Translate.toPrincipal` fills both. Python's `Principal` gains `claims: Mapping[str, str]` (a
`MappingProxyType` over a dict, frozen like the rest) and `issuer: str | None`; TypeScript's
`claims: Readonly<Record<string, string>>` and `issuer: string | null`; Rust's
`claims: BTreeMap<String, String>` and `issuer: Option<String>`.

**Rationale**: `endpoint.proto:44-50` has five fields; the Scala `Principal` has six
(`HttpEndpoint.scala:45-52`) and gains a seventh. `docs/reference/sidecar-protocol.md:184` says
adding an optional field is a minor change, and 1.1 to 1.3 each did exactly this. An SDK on 1.3
against a 1.4 sidecar is accepted (same major, lower minor) and simply never sees the two fields.

**Alternatives**: carry claims in `roles` or `name` (rejected: a lie); a major bump (rejected:
nothing is removed or changed).

## R6. The control plane keeps its variables and behaviour, and loses its verifier

**Decision**: `AuthConfig` stays, with `toOidc: OidcConfig` producing one issuer named `ankka`
with `issuer`, `jwksUrl`, `audience`, `jwksCa`, `clockSkew`, `typ = Some("Bearer")` and the
realm hint as the realm. `TokenVerifier.scala` and `TokenVerifierSuite.scala` are deleted;
`Verification` lives in the module. `ControlPlaneAcl.oidc(config: AuthConfig): Acl` is
`Oidc.authenticate(config.toOidc)`; `composite` is unchanged. `Principals.from(claims)` moves to
the module as the one mapping from claims to a principal (roles from `realm_access.roles` when
present, else from a top-level `roles` list claim, else empty; name from `name` else
`preferred_username`; email lowercased; every other non-null claim into `claims` as its string;
`issuer` the configured name); the control plane's `Principals` keeps `PlatformAdmin`,
`isPlatformAdmin` and `display`. The message "shared tokens are no longer accepted; run 'ankka
login'" for a token without two dots is the control plane's: `composite` keeps that pre-check
before delegating, so the message survives; the module says "not a well-formed token".

**Rationale**: FR-004 and US4. Every control plane suite that authenticates (`ControlPlaneHttpSuite`,
`AuthorizationMatrixSuite`, `KeycloakRealmSuite`, `ConsoleAclSuite`, `CliEndToEndSuite`,
`EndToEndClusterSuite`) runs unchanged, which is the acceptance. `TestIdentity`
(`controlplane/src/test/.../TestIdentity.scala`) becomes the module's `TestIssuer` plus the
control plane's `MutableClock`, which has nothing to do with tokens.

## R7. `ANKKA_AUTH_` is routed to the sidecar container and withheld from the app container

**Decision**: add `"ANKKA_AUTH_"` to `ServiceSpec.SidecarEnvPrefixes`
(`controlplane-api/.../descriptors.scala:282`) and `Rendering.SidecarEnvPrefixes`
(`operator/.../Rendering.scala:135`). `HostImports.ReservedPrefixes` (`sidecar/.../wasm/HostImports.scala:163-174`)
already lists it. `ProcessHostingRenderingSuite` gains the assertion that `ANKKA_AUTH_ISSUERS`
is on the node container and absent from the app container, beside its `ANTHROPIC_API_KEY` case.
`ServiceSpec.problems` refuses nothing new: the variables are the service's to set.

**Rationale**: the clarification chose withholding, the rule the model key and the database
follow (`Rendering.scala:713-740`). Collapsing the three lists into one declaration is
023-secret-store's R-item; this feature changes two lines in the lists that exist.

## R8. Discovery refuses an authenticated route with no issuer

**Decision**: `Discovery.validate` takes `authConfigured: Boolean`. For each endpoint whose ACL is
`AUTHENTICATED`, and each route whose own ACL is, when `authConfigured` is false the problem is
`endpoint '<id>': route '<METHOD> <template>' is AUTHENTICATED but no issuer is configured; set
ANKKA_AUTH_ISSUERS` (or `endpoint '<id>' is AUTHENTICATED …` for an endpoint-wide ACL). It joins
the existing problems and is reported through the same `reportError` to the process and the log.
Module mode uses the same validation (`WasmDiscovery` builds a `Discovered` the same code checks).

**Rationale**: FR-011 and US2 scenario 4. `Discovery.scala:124-247` collects every problem into
one vector and `:64-82` refuses with all of them. The 503 today is a documented feature that
cannot work (`docs/build/http-endpoints.md:581`).

## R9. Tests

**Decision**:

- `OidcConfigSuite` (module): every grammar rule of R3, each refusal named, all problems at once.
- `OidcVerifierSuite` (module): `TokenVerifierSuite`'s eight cases ported, plus: two issuers and a
  token from each; a token naming A signed by B refused; an unlisted issuer refused with the
  issuer's `fetches` at 0; `typ` required for one issuer and not the other; keys slow beyond the
  fetch timeout answer `Unavailable` within it (the test issuer gains a `delay`).
- `OidcAclSuite` (module): a `HttpServer.at("127.0.0.1", 0)` service with an endpoint using
  `Oidc.authenticate`: 200 with the principal's subject, roles, claims and issuer in the body; 401
  with the challenge for none, expired, unlisted; 503 with `Retry-After` for keys never fetched
  and for the tolerance passed; `principal` under `AllowAll` throws naming the ACL; a bearer
  token on an `allowCallers` route is ignored.
- `DiscoveryAuthSuite` (sidecar): a discovered spec with an `AUTHENTICATED` endpoint and a
  separate problem, `authConfigured = false`: the report names both; with `true`, neither.
- `ConformanceSuite`: `http.auth-admits-verified-token`, `http.auth-claims`, `http.auth-challenges-missing`,
  `http.auth-challenges-expired`, `http.auth-challenges-unlisted-issuer`, replacing
  `http.acl-deny-never-reaches-process`'s `401 || 503`. `ConformanceTarget` starts a `TestIssuer`
  per run and passes its `OidcConfig` to `Main.build`; each reference gains `GET /private/me`
  returning subject, roles, the claim `tier` and the issuer as JSON.
- `ProcessHostingRenderingSuite`: the prefix routing.
- The control plane: no new case; the existing suites compile against the module and pass.

**Rationale**: every case fails today; none needs a cluster. The Python, TypeScript and Rust
targets already run the conformance suite in CI (`sdk-python`, `sdk-typescript`, `sdk-rust`
jobs), so the new cases run there with no workflow change.

## R10. Documentation

**Decision**:

- `docs/build/http-endpoints.md`: under Access control, the one-line Scala form from a tested
  sample (a new `OidcEndpoint` in `OidcAclSuite` marked `docs:start`), and the Python, TypeScript
  and Rust paragraph replacing "answers 503 for now" with the declaration and the variables.
- `docs/platform/identity.md`: a section "A service's own users" stating the platform verifies and
  does not issue, the named set with an example, the per-issuer `typ` switch, and that the
  control plane is a user of the same verifier.
- `docs/reference/configuration.md`: a hand-written table of the `ANKKA_AUTH_*` variables after
  the generated block, since a named set cannot be stated as HOCON keys for the generator
  (`mkdocs.yml:105-113` lists reference.conf files); the generated block is unchanged.
- `docs/reference/limitations.md:45`: the entry becomes "The platform verifies a service's users'
  tokens and issues none": no realm, client or user is provisioned.
- `docs/reference/scala-sdk.md`: `ankka-auth-oidc` in the module table.
- `docs/reference/sidecar-protocol.md`, `protocol/README.md`: 1.4.
- `CLAUDE.md`: eight published modules; a line under Architecture for the module.
- `just docs-sync` refreshes the included sample and the skills that carry these pages
  (`ankka-endpoints`, `ankka-platform`); `just docs` must pass.

**Rationale**: `docs/contributing/documentation.md`'s rules; the pages are already in nav and in
skills, so nothing is added to `mkdocs.yml`.

## R11. What stays out

**Decision**: no realm, client or user provisioning; no change to callers; no session or cookie;
no change to `Acl.Authenticate`'s signature (a developer's own decide function still works); no
per-route issuer selection (a route is authenticated against the service's whole set); no unifying
of the three prefix lists (023); no token verification in the 021 web hosting proxy (its spec
decides).
