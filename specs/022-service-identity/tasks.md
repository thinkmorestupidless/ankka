# Tasks: Service-Level Identity — Verifying Tokens from Issuers the Platform Does Not Own

**Input**: Design documents from `/specs/022-service-identity/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included. Every scenario in `features/service-identity/` and
`features/control-plane/signing-in.feature` fails against today's code (the sidecar answers 503,
the module does not exist, the protocol carries no claims), and this repository's rule is that a
guarantee nobody measured is a comment. Where a task says "case", it means a `test(...)` in the
named munit suite; where it names a feature scenario, the case is that scenario.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a Scala endpoint verifies its users' tokens in one line), US2 (a Python,
  TypeScript or Rust route declared authenticated works), US3 (one service serves two issuers),
  US4 (the control plane uses the published verifier)

Paths are repository-relative. Abbreviations: `OIDC` =
`modules/auth-oidc/src/main/scala/com/thinkmorestupidless/ankka/auth/oidc`, `OIDCT` =
`modules/auth-oidc/src/test/scala/com/thinkmorestupidless/ankka/auth/oidc`; `HTTP` =
`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`; `SC` =
`sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar`, `SCT` =
`sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar`, `CONF` = `SCT/conformance`; `CP` =
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane`, `CPT` =
`controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane`; `PY` =
`sdks/python/src/ankka`, `TS` = `sdks/typescript/src`, `RS` = `sdks/rust/ankka/src`; `DOCS` =
`docs`. "R*n*" is a section of `research.md`; "§*n*" a section of `data-model.md` or of the
named contract.

---

## Phase 1: Setup — the branch and the module's skeleton

**Purpose**: the feature's documents are committed and the new sbt project exists, so every later
task has a place to put code and a build that compiles.

- [ ] T001 Commit this feature's documents on `022-service-identity` by name, never `git add -A`: `specs/022-service-identity/`, `features/`, `GLOSSARY.md` and `.specify/feature.json`. The main checkout's untracked `console/` changes are not on this worktree; the twelve other spec directories under the main checkout's `specs/` are not either, and must not be added here.
- [ ] T002 Add the module to `build.sbt` (R1): `lazy val authOidc = project.in(file("modules/auth-oidc")).dependsOn(http).settings(commonSettings).settings(name := "ankka-auth-oidc", libraryDependencies += nimbusJoseJwt)`, placed after `http`; add `authOidc` to the root `aggregate(...)` after `http`; change `controlPlane`'s `dependsOn` to include `authOidc % "compile;test->test"` and remove `nimbusJoseJwt` from its `libraryDependencies`; add `authOidc` to `sidecar`'s `dependsOn`. In `project/Dependencies.scala:144` change the comment to "JOSE/JWT verification; a dependency of `ankka-auth-oidc` and of nothing else". Create `modules/auth-oidc/src/main/scala/com/thinkmorestupidless/ankka/auth/oidc/` and `modules/auth-oidc/src/test/scala/...` with a placeholder removed by T004. `sbt authOidc/compile sidecar/compile` must pass; `controlPlane/compile` fails until T026 and that is expected, so do not run `sbt compile` at the root until Phase 6.

**Checkpoint**: `sbt authOidc/compile` is green; `grep -n nimbus build.sbt` names `authOidc` only.

---

## Phase 2: Foundational — the verifier, its configuration and its test issuer

**Purpose**: everything every story uses: the configuration grammar, the verifier, the mapping to
a principal, the `Acl` builder, and the in-process issuer the suites mint tokens with.

- [ ] T003 [P] In `HTTP/HttpEndpoint.scala` add `issuer: Option[String] = None` as the last field of `Principal` with scaladoc "the configured name of the issuer that verified the token; `None` when another `Authenticate` built the principal" (§3). `sbt http/test` stays green with no other change.
- [ ] T004 Write `OIDC/Issuer.scala`: `final case class Issuer(name, issuer, jwksUrl, audience, ca: Option[java.nio.file.Path] = None, typ: Option[String] = None, clockSkew: FiniteDuration = 60.seconds)`, `final case class OidcConfig(issuers: Vector[Issuer], realm: String = "ankka")`, `OidcConfig.empty`, and `OidcConfig.fromEnv(env: Map[String, String] = sys.env): Either[Vector[String], OidcConfig]` implementing §1 exactly: the name pattern, the upper-cased `-` to `_` variable segment, every required and optional variable from `contracts/environment.md`, `ANKKA_AUTH_REALM`, every problem in §1's table collected into one vector (never the first alone), and variables of unlisted names ignored rather than refused. Strip a trailing `/` from `issuer`. `OidcConfig.problems(config)` reports a duplicate name or an empty required string for a config built in code.
- [ ] T005 Write `OIDC/OidcVerifier.scala` (R2, §2): `enum Verification { Verified(claims: JWTClaimsSet, issuer: Issuer); Rejected(reason); Unavailable(reason) }`; `final class OidcVerifier(config: OidcConfig, keys: Issuer => JWKSource[SecurityContext])` holding one `DefaultJWTProcessor` per issuer built as `CP/auth/TokenVerifier.scala:29-40` builds its one (audience, issuer, required `sub` and `exp`, skew from the issuer), and `verify(token)` in §2's order: three parts, else `Rejected("not a well-formed token")`; `SignedJWT.parse` and `getJWTClaimsSet.getIssuer` to select the issuer, none matching → `Rejected("issuer not accepted")` with no key lookup; the issuer's processor, mapping `KeySourceException` → `Unavailable`, `BadJOSEException`/`JOSEException` → `Rejected(reason)`, `ParseException` → `Rejected("not a well-formed token")`; then the `typ` check only when `issuer.typ` is set. Companion: `Asymmetric` (the nine algorithms from `TokenVerifier.Asymmetric`), `remote(config)` and `keySource(issuer, minTimeBetweenFetches)` and the CA-trusting `retriever` moved verbatim from `TokenVerifier.scala:80-131` with `trusting` taken from `issuer.ca`. Nothing fetches at construction.
- [ ] T006 Write `OIDC/Principals.scala`: `def from(claims: JWTClaimsSet, issuer: Issuer): Principal` per §3's table, moved from `CP/auth/Principals.scala:18-34` with `roles` falling back to a top-level `roles` list claim and `issuer = Some(issuer.name)`; `Structural` gains `"roles"`.
- [ ] T007 Write `OIDC/Oidc.scala` (contract `scala-api.md`): `Oidc.authenticate(): Acl` reading `OidcConfig.fromEnv()` and throwing `IllegalStateException` listing every problem, or "no issuer is configured; set ANKKA_AUTH_ISSUERS" when the set is empty; `Oidc.authenticate(config: OidcConfig): Acl` building `Acl.Authenticate { context => ... }` from `Oidc.verifier(config)` with the bearer extraction (`presented`) and challenge quoting (`quoted`) moved from `CP/api/ControlPlaneAcl.scala:96-116`: no token → `Unauthenticated(realm="<config.realm>")`; `Rejected` → `Unauthenticated(realm, error="invalid_token", error_description=...)`; `Unavailable` → `AuthDecision.Unavailable("tokens cannot be verified right now: <reason>")`; `Verified` → `Allow(Principals.from(claims, issuer))`. `Oidc.verifier(config) = OidcVerifier.remote(config)`.
- [ ] T008 Write `OIDCT/TestIssuer.scala` from `CPT/TestIdentity.scala` (R6): the RSA key, the JWKS on `127.0.0.1:0`, `fetches`, `rotate`, `goOffline`/`comeBack`, plus `name` (default `"test"`), `config(audience, typ = None, ca = None): Issuer`, `token(subject, audience, roles, claims: Map[String, Any], expiresIn, notBefore, typ = Some("Bearer"), kid)`, `tokenSignedWithSecret` (HMAC, to be refused), `tokenWithoutSubject`, and `delayResponses(by)` so the keys endpoint answers after a delay. Leave `MutableClock` where it is for T027.
- [ ] T009 Write `OIDCT/OidcConfigSuite.scala`: one case per row of §1's table, one asserting `ANKKA_AUTH_ISSUER` and `ANKKA_AUTH_JWKS_URL` beside a named set are ignored, one for a two-issuer set with `customers-eu` reading `ANKKA_AUTH_CUSTOMERS_EU_*`, one asserting a set with three problems reports all three, one for an absent `ANKKA_AUTH_ISSUERS` giving `OidcConfig.empty`, and one for `ANKKA_AUTH_REALM`.
- [ ] T010 Write `OIDCT/OidcVerifierSuite.scala` porting `CPT/TokenVerifierSuite.scala`'s eight cases onto `OidcVerifier` with one issuer configured `typ = Some("Bearer")` (good token; expired, not-yet-valid, wrong issuer, wrong audience, wrong type, missing type; skew; `alg=none` and HMAC refused with `fetches` at 0; not a JWT; unknown kid refetches once then accepts a rotation; unreachable with nothing cached is `Unavailable` and with a cache still verifies; keys over TLS trusted by the named root alone, using `testPki` as the control plane's case does), plus: the `typ` check is skipped for an issuer with `typ = None`; a slow keys endpoint answers `Unavailable` within nimbus's read timeout rather than hanging (`delayResponses`).

**Checkpoint**: `sbt authOidc/test` is green; the module compiles with `-Wunused` clean.

---

## Phase 3: User Story 1 — A Scala endpoint verifies its users' tokens in one line (Priority: P1) 🎯 MVP

**Goal**: `val acl: Acl = Oidc.authenticate()` on an endpoint, configured from the named set, admits
a verified token with its principal and refuses everything else with the right status.

**Independent Test**: `sbt 'authOidc/testOnly *OidcAclSuite'`, every case in
`features/service-identity/verifying.feature`.

- [ ] T011 [US1] Write `OIDCT/OidcAclSuite.scala` driving `Router` directly as `modules/http/src/test/.../AclSuite.scala` does, with a `TestIssuer` and an endpoint built from `Oidc.authenticate(config)`; mark the endpoint class with `// docs:start endpoint` / `// docs:end endpoint` for T012, declaring `get("/me")` answering `principal.subject`, `principal.roles`, `principal.claims.get("tier")` and `principal.issuer`. Cases, each named by its scenario in `verifying.feature`: a verified token reaches the handler with subject, roles and issuer; a custom claim `tier` arrives by name; no token is 401 with `WWW-Authenticate: Bearer realm="ankka"` and the handler does not run; expired, not yet valid, wrong audience, no subject are 401 with `error="invalid_token"` (an outline over four tokens); an unlisted issuer is 401 and its `fetches` stays 0; a shared-secret token is 401 with `fetches` 0; keys fetched then the issuer offline still admits; offline longer than the tolerance answers 503 with `Retry-After: 5` (build the verifier with a key source whose outage tolerance is 1 second through `OidcVerifier`'s `keys` parameter); constructing the ACL with the issuer offline fetches nothing (`fetches == 0` after construction) and the first request is 503; `principal` under an `AllowAll` endpoint throws naming the ACL; a bearer token attached to an `allowCallers(NamedService)` route is ignored and the handler reads the caller. Also one case that `Oidc.authenticate()` with no `ANKKA_AUTH_ISSUERS` in a supplied empty map throws naming the variable (use a package-private overload `authenticate(env: Map[String, String])`).
- [ ] T012 [US1] In `DOCS/build/http-endpoints.md` under "Access control", after the decisions table, replace the paragraph "ankka does not ship a check for a specific identity provider..." with a subsection "Verify your users' tokens" showing the include `<!-- include: modules/auth-oidc/src/test/scala/com/thinkmorestupidless/ankka/auth/oidc/OidcAclSuite.scala#endpoint -->`, the dependency `"com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion`, a sentence that configuration is the `ANKKA_AUTH_` named set described on the identity page, and that a service with the ACL and no issuer configured does not start. Run `just docs-sync` so the copy lands in the page.

**Checkpoint**: US1 is demonstrable: a Scala service with the module and the variables verifies tokens; `just docs` passes.

---

## Phase 4: User Story 2 — A Python, TypeScript or Rust route declared authenticated works (Priority: P1)

**Goal**: the sidecar verifies with the same module from the same variables, hands the route a
principal with claims and issuer, and refuses to start an `AUTHENTICATED` route with no issuer.

**Independent Test**: `sbt 'sidecar/testOnly *DiscoveryAuthSuite *ConformanceSuite'` and each
SDK's conformance run (quickstart §3 and §4); every case in
`features/service-identity/languages.feature`.

- [ ] T013 [P] [US2] In `protocol/src/main/protobuf/ankka/protocol/v1/endpoint.proto` add `map<string, string> claims = 6;` and `optional string issuer = 7;` to `Principal` with comments from `contracts/protocol.md`; set `WireProtocol.Version` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/remote/Conversation.scala:164` to `"1.4"`; in `protocol/README.md` ("## Version") and `DOCS/reference/sidecar-protocol.md:179-181` state `1.4` and that it "added the claims map and the issuer to a route's principal". `sbt protocol/compile`.
- [ ] T014 [US2] In `SC/Translate.scala:135-138` fill the two new fields from `Principal.claims` and `Principal.issuer`.
- [ ] T015 [US2] Wire the sidecar (R4, R8): `SC/Discovery.scala` `validate` gains `authConfigured: Boolean` and, for each endpoint with `Acl.AUTHENTICATED` and each route whose own `acl` is, adds §5's problem text when it is false; `SC/RemoteEndpoint.from(spec, conversation, settings, authenticated: Acl)` and `aclOf(acl, callers, authenticated)` return `authenticated` for `AUTHENTICATED`, keeping today's `Unavailable` ACL as the value passed when no issuer is configured; `SC/Main.build(..., auth: Option[OidcConfig] = None)` builds `Oidc.authenticate(config)` once and passes `authConfigured = auth.exists(_.issuers.nonEmpty)` into discovery; `Main.run` calls a new `Main.readAuth(env: Map[String, String]): Either[Vector[String], Option[OidcConfig]]` (`OidcConfig.fromEnv`, an empty set as `None`) before dialling, logging every problem and exiting 1 on a `Left`, and passes the `Some` through. The wasm path (`wasm/WasmDiscovery` → the same `validate`) needs no separate change; confirm with the grep.
- [ ] T016 [US2] Write `SCT/DiscoveryAuthSuite.scala`: a discovered spec with an `AUTHENTICATED` endpoint, a route-level `AUTHENTICATED` on an `ALLOW_ALL` endpoint, and one unrelated problem (two endpoints sharing a prefix): with `authConfigured = false` the problems name both authenticated declarations with `ANKKA_AUTH_ISSUERS` beside the prefix problem; with `true`, only the prefix problem. Also: `Main.readAuth` given a set with two problems returns `Left` naming both, and `Main.run` with such an environment (supplied through a package-private `run(args, env)` overload) exits 1 having dialled nothing, asserted on a `ProcessDouble` at the process address that received no discovery.
- [ ] T017 [US2] Conformance: in `CONF/ConformanceTarget.scala` start one `TestIssuer` (name `test`, audience `conformance`) per run, pass `Some(OidcConfig(Vector(issuer.config("conformance"))))` to `Main.build` for the process and wasm targets, and expose a `token(...)` helper to the suite; in `CONF/ConformanceReference.scala:626` add `get("/me")` to `PrivateEndpoint` answering the JSON of `contracts/protocol.md`; in `CONF/ConformanceSuite.scala` add `http.auth-admits-verified-token`, `http.auth-claims`, `http.auth-challenges-missing` (asserts the `WWW-Authenticate` header), `http.auth-challenges-expired`, `http.auth-challenges-unlisted-issuer` (a second `TestIssuer` whose `fetches` stays 0), and change `http.acl-deny-never-reaches-process` to assert 401. Green against the Scala reference before the SDKs change.
- [ ] T018 [P] [US2] Python SDK: `PY/context.py` `Principal` gains `claims: Mapping[str, str] = MappingProxyType({})` and `issuer: str | None = None`; `PY/server.py:620` fills both from the proto; `PY/service.py:27` `PROTOCOL_VERSION = "1.4"`; `sdks/python/examples/shopping_cart/conformance.py` `PrivateEndpoint` gains `@get("/me")` returning the JSON; regenerate stubs with `uv run python scripts/proto.py`; `uv run pytest -q && uv run mypy`; a unit test asserting `Principal` decodes the two fields.
- [ ] T019 [P] [US2] TypeScript SDK: `TS/context.ts:37` `Principal` gains `claims: Readonly<Record<string, string>>` and `issuer: string | null`; the server's decoding fills them; `TS/spec.ts:15` `PROTOCOL_VERSION = "1.4"`; `sdks/typescript/examples/shopping-cart/conformance.ts:317` endpoint gains `/me`; `npm run proto && npm run typecheck && npm test`; a unit test for the decoding.
- [ ] T020 [P] [US2] Rust SDK: `RS/components/endpoint.rs:131` `Principal` gains `claims: BTreeMap<String, String>` and `issuer: Option<String>`, filled at `:590`; `RS/service.rs:21` `PROTOCOL_VERSION = "1.4"`; refresh the crate's protocol copy with `scripts/proto.sh`; `sdks/rust/examples/shopping-cart/src/conformance.rs:794` `PrivateEndpoint` gains the `/me` route; `cargo test --workspace`.
- [ ] T021 [US2] Run all four targets per quickstart §3 and §4 (`uv run conformance`, `npm run conformance`, `./conformance.sh`, and the in-process reference); every `http.auth-*` case green on each.
- [ ] T022 [P] [US2] Routing (R7): add `"ANKKA_AUTH_"` to `ServiceSpec.SidecarEnvPrefixes` in `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/descriptors.scala:282` and to `Rendering.SidecarEnvPrefixes` in `operator/src/main/scala/com/thinkmorestupidless/ankka/operator/Rendering.scala:135`, updating both scaladocs to say why (the sidecar verifies, the process never holds a keys URL); in `operator/src/test/.../ProcessHostingRenderingSuite.scala` beside the `ANTHROPIC_API_KEY` assertions at lines 95-99 add `ANKKA_AUTH_ISSUERS` on the node container and absent from the app container; in `SCT/WasmHostSuite.scala:279` add `"ANKKA_AUTH_ISSUERS"` to the withheld names it asserts; add a pin in the descriptor suite under `controlplane-api/src/test` that the prefix list holds exactly the four prefixes.
- [ ] T023 [US2] Docs: in `DOCS/build/http-endpoints.md:574-590` replace "answers `503` for now, because the sidecar has no token verifier configured" with a statement that `Acl.AUTHENTICATED` (Python), `Acl.authenticated` (TypeScript) and `Acl::Authenticated` (Rust) are verified by the runtime from the `ANKKA_AUTH_` named set, the principal carries `claims` and `issuer`, and a service declaring one with no issuer configured does not start; in `DOCS/reference/python-sdk.md:218`, `typescript-sdk.md:256` and `rust-sdk.md:384` name the two new principal fields.

**Checkpoint**: every language passes the auth cases; a sidecar with the route and no issuer exits with the report.

---

## Phase 5: User Story 3 — One service serves two issuers (Priority: P2)

**Goal**: a token from either listed issuer is verified against that issuer's keys and audience,
the principal names it, and the two issuers fail independently.

**Independent Test**: the US3 cases in `OidcVerifierSuite` and `OidcAclSuite`, every case in
`features/service-identity/issuers.feature`.

- [ ] T024 [US3] Add to `OIDCT/OidcVerifierSuite.scala` and `OIDCT/OidcAclSuite.scala` the cases of `issuers.feature`: two `TestIssuer`s `staff` (audience `backoffice`, `typ = Some("Bearer")`) and `customers` (audience `shop`, no `typ`); a token from `customers` is verified and the principal's `issuer` is `customers`; a token naming `customers` signed with `staff`'s key is rejected; a token from `staff` for the audience `shop` is rejected; `staff`'s keys fetched and `customers` offline still admits `staff`; the `typ` outline (`staff` with `Bearer` admitted, `staff` with `Other` rejected, `customers` with `Other` and with none admitted); and in `OidcConfigSuite` an issuer listed twice and an issuer with no audience each refuse with the name.
- [ ] T025 [US3] Docs: in `DOCS/platform/identity.md` add a section "A service's own users" before "Running a control plane outside a cluster": the platform verifies and issues nothing; the named set with the two-issuer example from `contracts/environment.md`; the per-issuer `typ` switch and why it is off by default; that the control plane is one user of the same verifier with `typ` on. In `DOCS/reference/configuration.md` after the generated block add a hand-written table "Token verification" with every row of `contracts/environment.md` and a sentence per variable, and note that a named set is not a HOCON key so the generator does not own the table (R10).

**Checkpoint**: two realms through one service, documented.

---

## Phase 6: User Story 4 — The control plane uses the published verifier (Priority: P2)

**Goal**: the control plane behaves exactly as before through the module; the repository holds
one verifier.

**Independent Test**: every existing control plane authentication suite green with no case
changed; `grep -rn nimbus build.sbt` names `authOidc` alone; every case in
`features/control-plane/signing-in.feature`.

- [ ] T026 [US4] Switch the control plane (R6): in `CP/auth/AuthConfig.scala` add `def toOidc: OidcConfig = OidcConfig(Vector(Issuer("ankka", issuer, jwksUrl, audience, jwksCa.map(Path.of(_)), typ = Some("Bearer"), clockSkew)), realm = realmHint)`; in `CP/api/ControlPlaneAcl.scala` make `oidc(config: AuthConfig): Acl = Oidc.authenticate(config.toOidc)` and in `composite` keep the pre-check that a bearer with other than two dots answers `Unauthenticated` with "shared tokens are no longer accepted; run 'ankka login'" before delegating, so the message survives; in `CP/ControlPlane.scala:151` `aclFor(auth) = ControlPlaneAcl.oidc(auth)`; in `CP/auth/Principals.scala` keep `PlatformAdmin`, `isPlatformAdmin`, `display` and delete `from` and `Structural`; delete `CP/auth/TokenVerifier.scala`; fix the imports in `Authorization.scala`, `DeployTokens.scala`, `InvitationClaim.scala`, `OrganizationPolicy.scala`, `Attributing.scala`, `AuthEndpoint.scala`, `Main.scala` to the module's `Verification`, `Oidc` and `Principals` where they used the control plane's. `sbt controlPlane/compile`.
- [ ] T027 [US4] Tests: rewrite `CPT/TestIdentity.scala` as a thin wrapper holding a `TestIssuer` (from `authOidc`'s test sources, via the `test->test` dependency) and the `MutableClock`, exposing the same members the suites call (`issuer`, `token(...)`, `rotate`, `goOffline`, `fetches`, `config`, `acl`, `clock`), so no suite changes; delete `CPT/TokenVerifierSuite.scala` (ported in T010 and T024); run `sbt 'controlPlane/testOnly *ControlPlaneHttpSuite *AuthorizationMatrixSuite *ConsoleAclSuite *DeployTokensSuite *DeployTokenIndexSuite *VerificationOverheadBenchmark'` green, then `sbt controlPlane/test` with Docker for `KeycloakRealmSuite` and `CliEndToEndSuite`. Assert in a one-line case of `CPT/ReservedProjectIdsSuite.scala` or a new `OneVerifierSuite` that `classOf[Oidc]` is the control plane's verifier's package, or simply that no class named `TokenVerifier` exists under `controlplane` (`Class.forName` fails).

**Checkpoint**: the control plane's suites are green and unchanged; one verifier in the repository.

---

## Phase 7: Polish — the rest of the documentation, the build, the whole test run

- [ ] T028 [P] In `DOCS/reference/limitations.md:45` replace the entry with "**The platform verifies a service's users' tokens and issues none.** A service lists the issuers it accepts; the platform provisions no realm, client or user for a service's users and administers none." In `DOCS/reference/scala-sdk.md:22` add a row for `ankka-auth-oidc` (`com.thinkmorestupidless.ankka.auth.oidc`, "`Oidc.authenticate`, the issuer configuration, the verifier; depends on nimbus"). In `ankka.g8/src/main/g8/build.sbt:35` add a commented line `// "com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion,   // your users' tokens: uncomment`.
- [ ] T029 [P] `CLAUDE.md`: under "Publishing" change "Seven modules are published" to eight, naming `auth-oidc` as the one a service with users of its own adds; under "Architecture" add a short section "A service verifies its users' tokens with one module" stating the module, the named set, that the sidecar carries it, that the control plane is a user of it, and that nimbus lives there alone. In `build.sbt`'s `templateArtifacts` comment say the module is not published for the template because the template is a service without users.
- [ ] T030 `just docs-sync && just docs`; `sbt scalafmtAll scalafmtSbt`; `sbt compile` warning-free; `sbt -Dankka.cluster.tests=off test` green; the three SDK suites and `cd sdks/python && uv run conformance` etc. green; run the BDD checker (`uvx --from "git+https://github.com/thinkmorestupidless/speckit-bdd@v0.2.0#subdirectory=checker" speckit-bdd check --root . --glossary GLOSSARY.md --features features --specs specs --specs-from 019`) and confirm the only findings are in `specs/019-graph-delta-publisher`.
- [ ] T031 Optional, needs Docker and minutes: add a case to `SCT/SidecarClusterSuite.scala` deploying the Python sample with the named set in its descriptor and asserting, from the host through the gateway, a 401 carrying `WWW-Authenticate: Bearer` for a request with no token, which proves the sidecar container received the variables on a real pod (quickstart §8).
- [ ] T032 Open the pull request against `main` with the spec's user stories as its description; CI's `changes` job must select `build`, `docs`, `sdk-python`, `sdk-typescript` and `sdk-rust`; after merge, note in the core features document that C1 is Done.

---

## Dependencies

```text
Phase 1 (T001–T002)
  └─ Phase 2 (T003 ∥ T004 → T005 → T006 → T007; T008 → T009, T010)
       ├─ US1 (T011 → T012)                                   ← MVP
       ├─ US2 (T013 ∥ T022 ∥ ...; T014 → T015 → T016 → T017 → {T018 ∥ T019 ∥ T020} → T021 → T023)
       ├─ US3 (T024 → T025)                                   needs T010, T011
       └─ US4 (T026 → T027)                                   needs T007, T008
            └─ Phase 7 (T028 ∥ T029 → T030 → T031 → T032)
```

- US1, US2 and US4 are independent of each other once Phase 2 is done; US3 adds cases to US1's
  suites and so follows T011.
- T013 (the protocol change) is independent of everything but must land before T014.
- T022 (routing) is independent of every other US2 task.
- Phase 6 unblocks `sbt compile` at the root, so Phase 7's whole-build checks come last.

## Parallel execution examples

- After T002: T003, T004 and T008 in parallel (three files, no shared state).
- After T007 and T008: T009, T010 and T011 in parallel; T026 can start in parallel with them.
- After T017: T018, T019 and T020 in parallel (three SDKs), with T022 and T013's docs edits
  alongside.
- Phase 7: T028 and T029 in parallel, then T030.

## Implementation strategy

1. **MVP = Phase 1 + Phase 2 + US1** (T001–T012): a Scala service verifies its users' tokens in
   one line. Demonstrable and releasable on its own; the domain plan's money path needs exactly
   this.
2. **US2** makes the documented-but-broken `AUTHENTICATED` route work in every language and is the
   second release candidate; it is the largest phase because it touches the protocol and three SDKs.
3. **US4** removes the second verifier and can ship with either of the above.
4. **US3** is cases and docs on top of US1.
