# Tasks: A Service as an MCP Server

**Input**: Design documents from `/specs/054-service-as-mcp-server/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R19, S1–S2), data-model.md,
contracts/{scala-api,http-wire,protocol,sdk-apis}.md, quickstart.md

**Tests**: included. The spec's acceptance scenarios live in `features/mcp-endpoints/`,
`features/documentation/mcp-endpoints.feature`, `features/agents/mcp-servers.feature`,
`features/polyglot/conformance.feature` and `features/local-console/services.feature`, and
`CLAUDE.md` asks that each ends as a test that fails without the feature. Each story's suites are
written first, red, then the code that turns them green. R19 names the checks that could pass while
false and how each is sharpened; the suite tasks carry those sharpenings.

**Organization**: by user story, in the plan's order of work. US1 and US6 share one phase: a
reference is a tool, and the kernel is one.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 the endpoint, US2 client keys, US3 who may call (tokens, certificates, brokered sign-in), US4 languages, US5 visibility and traces, US6 references
- Paths are from the repository root. `…/mcp/` is `modules/mcp/src/main/scala/com/thinkmorestupidless/ankka/mcp/`; `…/mcp-test/` is `modules/mcp/src/test/scala/com/thinkmorestupidless/ankka/mcp/`; `…/http/` is `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/`.

---

## Phase 1: Setup (the module and the spikes)

**Purpose**: a place for the code, and the two facts the design rests on checked before anything
is built on them.

- [X] T001 Add `lazy val mcp` to `build.sbt`: `project.in(file("modules/mcp"))`, `.dependsOn(core, sdk, runtime, http, agent, authOidc, testkit % Test, authOidc % "test->test")`, `name := "ankka-mcp"`, `commonSettings`; add `mcp` to `root.aggregate`, to `sidecar`'s and `shoppingCart`'s `dependsOn`; forward nothing new in `Test / javaOptions` (`ankka.spikes` is forwarded already)
- [X] T002 [P] Create `modules/mcp/src/main/resources/reference.conf` with the `ankka.mcp.*` keys of data-model.md (client-keys `last-use-interval`, `query` as a copy of `pekko.persistence.r2dbc.query` at `refresh-interval = 500ms` sharing the connection factory, the five `sign-in.*` lifetimes) each with a comment saying what it is
- [X] T003 [P] Confirm logging needs no file of its own: testkit's `src/main/resources/logback-test.xml` reaches `modules/mcp`'s tests through `testkit % Test`; every suite below mixes in `LogCapturing`, as `.claude/rules/testing.md` asks
- [X] T004 [P] Add the commented `ankka-mcp` line beside `ankka-agent` in `ankka.g8/src/main/g8/build.sbt` ("MCP endpoints: uncomment"); update the artifact count in `.claude/rules/build-and-release.md` (eleven → twelve, naming `ankka-mcp` and why it is a module) and `CLAUDE.md`'s "exactly eleven artifacts" command comment
- [X] T005 Spike S1 in `…/mcp-test/ClientKeyIndexSpike.scala` (skipped unless `ankka.spikes=on`): two kit nodes (`AnkkaTestKit.start` + `startPeer`), a throwaway event sourced entity, a follower through `readJournalFor[R2dbcReadJournal]("ankka.mcp.client-keys.query")`; persist on one node, measure when the other's follower sees it; print the delay and the pool count. Record the result and the config shape in `.claude/rules/mcp.md` (new file, with `paths:` frontmatter for `modules/mcp/**`)
- [X] T006 Spike S2 in `…/mcp-test/OutsideClientSpike.scala` (skipped unless `ankka.spikes=on`): a loopback `HttpServer.at("127.0.0.1", 0)` serving the agent module's `TestMcpServer`-shaped answers from a stub `HttpEndpoint` at `/mcp` (hand-written JSON for `initialize`, `tools/list`, one `tools/call`), prints the URL and waits for `ankka.mcp.spike.seconds`; run `npx @modelcontextprotocol/inspector --cli <url> --method tools/list` against it and note in `.claude/rules/mcp.md` which headers the inspector sends and what it needs from `initialize`

**Checkpoint**: `sbt mcp/compile` green with an empty module; S1's delay and S2's header list written down.

---

## Phase 2: Foundational (the seams in `http`, the credential primitive, the span's subject)

**Purpose**: generic changes every story below mounts on. Nothing here names MCP.

- [X] T007 [P] Write `modules/http/src/test/scala/com/thinkmorestupidless/ankka/http/AclAnyOfSuite.scala`: `Acl.anyOf` admits when the first member admits, falls through to the second, challenges with the first `Unauthenticated` member's challenge, answers `Unavailable` when the admitting member is unavailable, and combines `allowCallers` with an `Authenticate` (a `Caller.Service` admitted with no credential; a token admitted from the internet)
- [X] T008 [P] Write `modules/http/src/test/.../RootRoutesSuite.scala`: an endpoint's root route is served ahead of another endpoint's prefix (`/.well-known/x` beside an endpoint at `/`), `/_ankka/health` still answers, two endpoints claiming one root route fail `validate` naming both, a root route and a prefixed route rendering to one path fail `validate`
- [X] T009 [P] Write `modules/http/src/test/.../OpaqueCredentialsSuite.scala`: `OpaqueCredentials("ankka_ck_")` mints `prefix + 16 hex + 64 hex`, `parse` splits it, `digest` is lower-case hex SHA-256 of the secret half, `matches` is true for the minted secret and false for a one-character change, `looksLikeOne` by prefix; and `controlplane/src/test/.../DeployTokensSuite.scala` pins the existing `ankka_` shape byte for byte against a recorded token and digest, and adds: a client key `ankka_ck_…` presented as a deploy token is refused (its prefix matches `ankka_`, its shape does not, FR-012), and a deploy token presented to `OpaqueCredentials("ankka_ck_")` is refused
- [X] T010 [P] Write `modules/http/src/test/.../NeedsServiceSuite.scala`: an `Acl.Authenticate` whose function implements `NeedsService` is bound by `HttpServer.serve` before binding; one whose `bind` answers `Left` fails the start with that text inside `invalid ankka http configuration`
- [X] T011 Add to `…/http/HttpEndpoint.scala`: `Acl.AnyOf(acls: Vector[Acl])` and `Acl.anyOf(first, rest*)`; `trait TokenAuthenticator extends (RequestContext => AuthDecision) { def authorizationServers: Vector[String] }`; `trait NeedsService { def bind(service: AnkkaService): Either[String, Unit] }`; `private[ankka] def rootRoutes: Vector[Route] = Vector.empty`; `private[ankka] def problems(mounting: Mounting): Vector[String] = Vector.empty`; `private[ankka] final case class Mounting(registry: ComponentRegistry, endpoints: Vector[HttpEndpoint], service: AnkkaService)`; `Acl.namesGranted` recursing into `AnyOf`
- [X] T012 In `…/http/HttpServer.scala`: `admit` handles `Acl.AnyOf` (first `Allow` wins; first `Unauthenticated` is the challenge; an `Unavailable` from any member is the answer when none allows); `Router.handle` serves root routes after `/_ankka/health` and before prefix matching, through the same `admitted`/`dispatch`; `validate` adds the two root-route refusals of T008; `serve` takes the `AnkkaService`, builds `Mounting`, calls every endpoint's `problems`, binds every `NeedsService` found in any ACL tree (endpoint, route, root route) and folds both into the one refusal; `start` passes the service through
- [X] T013 Handle `Acl.AnyOf` in `modules/grpc/src/main/scala/com/thinkmorestupidless/ankka/grpc/Admission.scala` (`decide` and `namesCallers`), `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/ControlPlaneAcl.scala` and `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/RemoteEndpoint.scala` (`aclOf`, as a pass-through); `sbt compile` warning-free is the check
- [X] T014 Create `…/http/OpaqueCredentials.scala` (`final class OpaqueCredentials(prefix: String)` with `mint: Minted(id, secret, presented, digest)`, `parse`, `digest`, `matches` by `MessageDigest.isEqual`, `looksLikeOne`); make `controlplane/src/main/scala/.../auth/DeployTokens.scala` delegate to `OpaqueCredentials("ankka_")` keeping its public names
- [X] T015 In `modules/auth-oidc/src/main/scala/com/thinkmorestupidless/ankka/auth/oidc/Oidc.scala`: the function `authenticate(verifier, realm)` wraps is a `TokenAuthenticator` whose `authorizationServers` are the config's issuers' URLs in order; add a case to `modules/auth-oidc/src/test/.../OidcAclSuite.scala` asserting it
- [X] T016 [P] In `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Recorder.scala` add `subject: Option[String]` to `RecordedSpan` (stored per span, never interned) and a `begin`/`beginRoot`/`record` overload taking it; in `ObservabilityEndpoint.scala`'s span detail emit `"subject"` when present; in `HttpServer.scala`'s `Tracing.request` pass the principal's subject from the `RequestScope` context after admission; extend `modules/testkit/src/test/.../topology/TracesFeatures`'s steps or `RecorderSuite` with a case that a route span carries its principal's subject and a span without a principal carries none
- [X] T017 [P] In `modules/telemetry-otlp` map `RecordedSpan.subject` to the `enduser.id` attribute; add a case to its exporter suite
- [X] T018 [P] Add `given ToolOutput[Done]` ("done") to `modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/SchemaType.scala` and widen `agent/autonomous/JsonSchema` to `private[ankka]` if it is narrower; a case in `FunctionToolSuite`
- [X] T019 In `cli/src/main/resources/console/app.js` `renderSpan`, show `subject` beside the handler when present; in `cli/src/test/.../ConsoleTopologyJsSuite.scala` (or the traces JS suite beside it) a case rendering a span with a subject

**Checkpoint**: T007–T010 green; every match over `Acl` compiles; `DeployTokensSuite` unchanged in what it asserts.

---

## Phase 3: User Story 1 + User Story 6 — the endpoint, its tools, references (Priority: P1, P2) 🎯 MVP

**Goal**: a Scala service declares an MCP endpoint with hand-written and referenced tools and any
MCP client lists and calls them, statelessly, with the protocol's errors.

**Independent Test**: `sbt 'mcp/testOnly *ProtocolSuite *ToolBuilderSuite *ToolInputSuite *McpEndpointProblemsSuite *ToolsFeatures *SiblingAgentSuite'`;
14 of 17 `tools.feature` scenarios green, 3 marked run elsewhere; Claude Code pointed at
`sbt shoppingCart/run`'s `/mcp` lists and calls `get_cart`.

### Tests for User Story 1 and 6

- [X] T020 [P] [US1] Write `…/mcp-test/ProtocolSuite.scala` against the pure kernel, one case per row of contracts/http-wire.md's two tables: `initialize` (both versions, an unknown one answered `2025-06-18`), `ping`, `notifications/*` → 202, `tools/list` shape with no `nextCursor`, `tools/call` text and structured content, `-32700` on bad JSON with `id: null`, `-32600` on a batch, `-32601`, `-32602` for an unknown tool and for a bad argument naming it, a thrown handler as `isError: true`, `GET` and `DELETE` → 405 with `Allow`, `MCP-Protocol-Version: 1999-01-01` → 400, `Mcp-Session-Id` ignored, `Origin` mismatch → 403; plus the protocol page's own `tools/list`, `tools/call`, error and `initialize` examples replayed as fixtures under `modules/mcp/src/test/resources/protocol-examples/` (R19.1)
- [X] T021 [P] [US1] Write `…/mcp-test/ToolBuilderSuite.scala`: `param` up to three builds the `FunctionTool`-shaped schema (`properties`, `required`, `additionalProperties: false`), `arguments[A]` derives a case class, `withAcl` is kept, `handle` decodes each parameter and reports `parameter 'x': …`, `ToolOutput` of a String, an Int, a case class (text and structured) and `Done`
- [X] T022 [P] [US6] Write `…/mcp-test/ToolInputSuite.scala`: `command(handle, id)` with a case class input yields the id property plus the fields; `command(handle, id, input)` with a `String` yields two string properties; a `NoArgHandle` yields the id alone; a scalar input without `input` does not compile (`compileErrors`); the encoded payload for a `String` input is raw UTF-8 and for a case class JSON (the serializer trap, R4); `query` likewise
- [X] T023 [P] [US1] Write `…/mcp-test/McpEndpointProblemsSuite.scala`: each refusal text of contracts/scala-api.md's "What fails the start" list from `problems(mounting)` with a stub `Mounting` (a registry with a `job` entity having `book` and `get`, an endpoint at `/jobs` with `GET /jobs/{day}`), and a well-formed endpoint yielding none; an endpoint that called `get(...)` refused; a path `/_ankka/x` and `/.well-known/x` refused; two MCP endpoints at one path refused naming both, and two at `/mcp` and `/office` accepted (FR-001)
- [X] T024 [P] [US1] Write `…/mcp-test/features/McpEndpointSteps.scala` (`abstract class McpEndpointSteps(feature: String) extends GherkinSuite(feature) with LogCapturing`): a kit started per scenario group with the fixture service of `…/mcp-test/features/Fixtures.scala` (a `cart` event sourced entity with `get-cart`, `add-item` refusing quantity 0, `checkout`; a `job` entity with `book` and `get`; a `JobsEndpoint` at `/jobs` with `GET /jobs/{day}`; a `CartMcp` at `/mcp` whose tools are `get_cart`, `add_item`, `checkout` and, when a scenario asks, the references `book_job`, `get_job`, `todays_jobs`; a `ShopMcp` builder for the refusal scenarios), the agent module's `McpClient` over `McpTransport.Url` as "an MCP client", raw `java.net.http` for the stream and header steps; an `afterEach` that scans `LogCapturing`'s buffer, every recorded span's attributes and every listing the scenario read for every secret minted in the scenario, so each feature file inherits SC-005's check; steps for every line of `features/mcp-endpoints/tools.feature`; `ranElsewhere` naming the three k3s scenarios (three instances, every hostname, paused)
- [X] T025 [P] [US1] Write `…/mcp-test/features/ToolsFeatures.scala` (`class ToolsFeatures extends McpEndpointSteps("../../features/mcp-endpoints/tools.feature")`) and run it red
- [X] T026 [P] [US1] Write `…/mcp-test/SiblingAgentSuite.scala`, named for the two scenarios of `features/agents/mcp-servers.feature` ("the platform connects to an MCP server that is a service as the agent's service"; "an agent is offered the tools of a sibling service's MCP endpoint by listing the service"): two kits, `support` with an agent listing `McpServer.service("cart", "cart", "/mcp")` under `localServices = Map("cart" -> cartKit.httpUrl)`, `cart` with `CartMcp` whose ACL is `Acl.allowCallers(Callers.service("support"))` and the local caller header; `TestModelProvider` calls `mcp__cart__get_cart`; asserts the tool offered with the endpoint's description and the call reaching the entity; `McpAgentSuite`'s shape

### Implementation for User Story 1 and 6

- [X] T027 [US1] Create `…/mcp/Protocol.scala`: `private[ankka] object Protocol` with `Request(method, headers, body)`, `Response(status, headers, body: Option[Json])`, `handle(request, server: ServerInfo, tools: ToolsFor): Response` implementing every row of T020 over the agent module's `Json`; `SupportedVersions = Vector("2025-06-18", "2025-03-26")`; error codes as constants; `ToolsFor` is a function from the caller's context to the visible tools so filtering (US2, US3) plugs in without the kernel knowing why
- [X] T028 [P] [US1] Create `…/mcp/McpTool.scala`: `McpTool`, `ToolCall`, `ToolAnswer`, `ToolKind` (`Handler`, `Command`, `Query`, `Route`), `ToolName.problems(name)` (`[a-z0-9_-]+`, ≤ 128)
- [X] T029 [P] [US1] Create `…/mcp/ToolBuilder.scala`: `ToolBuilder`, `Described`, `Params1..3`, `Arguments[A]` per contracts/scala-api.md, building on `SchemaType`, `JsonSchema.derived`, `ToolOutput`; the invoker decodes as `FunctionTool.build` does and turns `NonFatal`, `HttpProblem` and `Rejection(CommandError)` into a tool error carrying the message
- [X] T030 [P] [US6] Create `…/mcp/ToolInput.scala`: `trait ToolInput[I] { schema: Vector[(String, Json)]; required: Vector[String]; decode(Json.Obj): Either[String, I] }`, `inline given derived[A](using Mirror.ProductOf[A], JsonValueCodec[A])`, `ToolInput.named[A: SchemaType](name)`
- [X] T031 [US6] In `…/mcp/ToolBuilder.scala` add `command`/`query` (three overloads each) and `route`: a reference tool whose `run` is bound late by `McpEndpoint.problems` (an `AtomicReference` filled at mount, refusing with a clear message if called unbound); the command path encodes `I` with `handle.inputSerializer`, calls `componentClient.transportRef.askHandler(handle.readOnly, handle.componentId, id, handle.name, bytes, Trace.outbound(Metadata.empty))`, decodes with `handle.outputSerializer`, renders with `ToolOutput[O]`; the route path builds `Vector[String]` path args from the template's placeholders and the `body` argument as JSON bytes, calls `route.run(args, bytes)`, maps status ≥ 400 to a tool error
- [X] T032 [US1] Create `…/mcp/McpEndpoint.scala`: `abstract class McpEndpoint(val path: String = "/mcp") extends HttpEndpoint(path)` with `name`, `version` (from `BuildInfo` when the service is named, else `ankka`/`0.0.0`), `brokered = false`, `protected def tool(name)`, `final def tools`, `final override def routes` (the three built routes, each with the endpoint's `acl`, the `POST` one `needsBody = true` and its `run` calling `Protocol.handle` with `RequestScope.currentContext`), empty `streamRoutes`/`socketRoutes`; `problems(mounting)` implementing every T023 refusal, resolving references (component by `handle.componentId` in `mounting.registry`, handler by wire name through the registry's descriptor `handlers`, route by `(method, template.render)` among `mounting.endpoints`), and the inherited-verbs check against `super.routes`; `served: Vector[ServedRoute]` one per tool (`"MCP"`, `"<path> <tool>"`, `endpointId(path)`, `grantable` true iff the tool's effective ACL names granted callers, exactly as `HttpServer` sets it for a route, so grants and rollouts need no code of their own, FR-005)
- [X] T033 [US1] In `…/http/HttpServer.scala`: an endpoint that is also a `private[ankka] trait ServesRoutes { def served: Vector[ServedRoute] }` (new in `http`, implemented by `McpEndpoint`) contributes its records to `served` instead of its built routes; `originOf` for such an endpoint is `CallOrigin(endpointId(prefix), "MCP <path> <tool>")` set per tool inside the kernel's call (the kernel receives a `Trace.within` hook from the endpoint); `Tracing.request` for the MCP routes uses component `"mcp"` and handler `"<path> <tool>"` once the tool is known (a child span per tool call under the request's, or the request span renamed; pick the child span and record it in `.claude/rules/mcp.md`)
- [X] T034 [US1] Create `samples/shopping-cart/src/main/scala/shoppingcart/api/CartMcpEndpoint.scala` with `// docs:start/end` regions: `get_cart` as a `query` reference to `ShoppingCart.getCart` (id `cartId`), `add_item` with three `param`s calling the entity, `checkout` as a `command` reference, `acl = Acl.AllowAll` for now (US2 tightens it); register it in `samples/shopping-cart/src/main/scala/Main.scala`'s `HttpServer.of`; add the scenario set to `samples/shopping-cart/features/` and words to `samples/shopping-cart/GLOSSARY.md`
- [X] T035 [US1] Run T025 and T026 green; add to `…/mcp-test/features/McpEndpointSteps.scala` the "no handler is run" and "handler is not run" assertions through a counter on the fixture entity rather than a log line (R19)

**Checkpoint**: US1 and US6 proved in the kit and by hand (`quickstart.md` step 7, first two commands). Deployable as an MVP for a service whose ACL is `AllowAll` or `allowCallers`.

---

## Phase 4: User Story 2 — client keys (Priority: P1)

**Goal**: a service issues, lists and revokes client keys through its client; a request carrying
one runs as the key's subject with its attributes as claims, within its scope, and a revoked key
is refused on every instance within a second.

**Independent Test**: `sbt 'mcp/testOnly *ClientKeyEntitySuite *ClientKeysImplSuite *ClientKeyIndexSuite *ClientKeysFeatures'`;
14 of 15 `client-keys.feature` scenarios green on two kit nodes, the k3s one marked run elsewhere.

### Tests for User Story 2

- [X] T036 [P] [US2] Write `…/mcp-test/ClientKeyEntitySuite.scala` with `EventSourcedTestKit`: `issue` persists `ClientKeyIssued` with the digest and never the secret (assert the journal bytes do not contain it), refuses a blank subject, an absent expiry (the command's `Expiry` is required; a test decoding `{"expiry": null}` sees the refusal, the jsoniter trap), a past instant, 17 attributes, a 257-character name or value, a second `issue` as `Conflict`; `record-use` persists only when a minute newer; `revoke` twice is one event; `get` answers the summary without a digest. In the same task, `…/mcp-test/ClientKeysImplSuite.scala` with a stub index and two endpoints `/mcp` and `/office`: `issue` with no endpoint named is refused naming both paths, `issue` for `/office` naming a tool of `/mcp` is refused naming the tool, `issue` with one endpoint registered implies it (FR-009)
- [X] T037 [P] [US2] Write `…/mcp-test/ClientKeyIndexSuite.scala`: `admit`/`evict` write through; a replayed `ClientKeyIssued` for an evicted id is ignored (`revoked` set); `lookup` hides expired keys; `Unavailable` before `markCaughtUp`; `touch` then a sweep sends `record-use` once for a key used twice in a minute and not again within the interval; a key whose `persistedLastUsed` is within the interval is not re-recorded
- [X] T038 [P] [US2] Write `…/mcp-test/features/ClientKeysFeatures.scala` extending `McpEndpointSteps` for `features/mcp-endpoints/client-keys.feature`, with steps in `McpEndpointSteps`: "a handler of plumbers issues a client key …" through a fixture route `POST /keys` calling `clients.clientKeys.issue`; the `plumbers` fixture MCP endpoint with the four tools and `acl = ClientKeys.authenticate()`, its service registering `McpRuntime()` after `HttpServer.of` on purpose (extensions start in registration order); "within one second … at every instance" using `startPeer` and a clock read around the revoke; "two years later" through the kit's `TimerRuntime` clock; "appears in no log, trace or listing" by searching `LogCapturing`'s buffer, the recorder's spans and the listing JSON for the minted key read from the issue reply (R19.4); `ranElsewhere("a revoked client key is refused on its next request on every instance")` for the k3s copy only if the step cannot be met on two kit nodes, otherwise run it here too; run red
- [X] T039 [P] [US2] Add to `…/mcp-test/ProtocolSuite.scala` the scope cases: `tools/list` under a key scoped to two of four tools lists two and omits `close_books` (an absence asserted, R19.2); `tools/call close_books` is `-32602 tool not available: close_books` and the counter shows no run; a key scoped to none lists four

### Implementation for User Story 2

- [X] T040 [P] [US2] Add to `…/http/HttpEndpoint.scala` (or a new `…/http/ClientKeys.scala`): `trait ClientKeys { issue; list; revoke }`, `enum Expiry { After, At, Never }` with a jsoniter codec in its companion, `IssuedClientKey`, `ClientKeySummary`, `private[ankka] trait ClientKeysProvider { def clientKeys: ClientKeys }`; `EndpointClients` gains `clientKeys: ClientKeys` (default an `Unavailable` implementation refusing with "add McpRuntime() to the service"); `HttpServer.start` fills it from `service.extensions.collectFirst { case p: ClientKeysProvider => p.clientKeys }`
- [X] T041 [P] [US2] Create `…/mcp/ClientKeyEntity.scala`: `ankka-client-key` event sourced entity with `platform = true`, commands `issue` (`IssueClientKey(subject, attributes, label, endpoint, scope, expiry: Expiry)`), `record-use`, `revoke`, query `get`; events and state per data-model.md; refusals in the handler as `CommandError(BadRequest, …)` with the contract's texts; `EventCompatibility`-style pin of the three event shapes in `…/mcp-test/ClientKeyEventsSuite.scala`
- [X] T042 [US2] Create `…/mcp/ClientKeyIndex.scala` from `controlplane/.../auth/DeployTokenIndex.scala`'s shape: `RuntimeExtension` named `client-keys`, replay then follow through `readJournalFor[R2dbcReadJournal]("ankka.mcp.client-keys.query")` (S1's config), `Live` records, `revoked`, `caughtUp`, readiness, `touch`, `admit`, `evict`, the sweep calling `record-use` through `service.componentClient`; the follower also folds `ankka-sign-in-keys` events into a `BrokerKeyCache` (US3b fills it; a no-op fold now)
- [X] T043 [US2] Create `…/mcp/ClientKeys.scala`: `object ClientKeys { def authenticate(): Acl }` returning `Acl.Authenticate(ClientKeyAuthenticator)` where the authenticator implements `NeedsService` (binds to `service.extension[McpRuntime]`'s index or answers `Left("… add McpRuntime() to the service")`), the bind keeps the `McpRuntime` reference and reads its index lazily, so a runtime registered after `HttpServer.of` still serves (extensions start in registration order, `runtime/.../Ankka.scala`), and readiness comes from the index, never from the bind; reads the bearer, `looksLikeOne` by prefix else `Unauthenticated(realm="ankka")`, looks the id up, `OpaqueCredentials.matches` in constant time, answers `client key not recognised` for unknown/revoked/wrong and `client key expired` for expired, `Unavailable` before caught up, `touch`es and builds the `Principal` of data-model.md (claims + `kind`, `client_key`; `issuer = Some("client-key")`); and `final class ClientKeysImpl(index, componentClient, endpoints: () => Vector[McpEndpoint])` implementing the `http` trait: `issue` resolves the endpoint (one → implied; several and none named → refused naming the paths), checks the scope against that endpoint's tool names, mints, sends `issue`, `admit`s locally, answers `IssuedClientKey`; `list` from the index; `revoke` sends `revoke` then `evict`s
- [X] T044 [US2] Create `…/mcp/McpRuntime.scala`: `RuntimeExtension with ClientKeysProvider` named `mcp`, `McpRuntime()` and `McpRuntime.descriptors` (the key entity now; the sign-in kinds join in US3b), `start` starting the index and the sweep, `stop` in reverse, readiness the index's, a warning at start when its descriptors are not registered (as `AgentRuntime` warns); the endpoints it knows are those `HttpServer` mounted, found through the server's `ServesRoutes` endpoints after start (or passed via `Mounting` in `problems`; pick the latter and keep a registry in `McpRuntime` filled by `McpEndpoint.problems`)
- [X] T045 [US2] In `…/mcp/McpEndpoint.scala` and `Protocol.scala`: the `ToolsFor` function filters by the principal's `client_key` scope (from the index's `Live.scope`, via the authenticator's bound index) for both `tools/list` and `tools/call`; `problems(mounting)` refuses a `ClientKeys.authenticate()` in the ACL tree with no `McpRuntime` and registers the endpoint with the runtime; the tool span carries `client_key = <id>` as an attribute and the subject (T016)
- [X] T046 [US2] Tighten `samples/shopping-cart/.../CartMcpEndpoint.scala`'s ACL to `ClientKeys.authenticate()`, add a `POST /mcp-keys` route to `ShoppingCartEndpoint.scala` (body `{"subject","scope","label"}`, one year, answers the issued key once) and a `DELETE /mcp-keys/{id}` route; register `McpRuntime.descriptors` and `McpRuntime()` in `samples/shopping-cart/src/main/scala/Main.scala`; docs regions on both
- [X] T047 [US2] Run T036–T039 green; measure the revoke-to-refusal delay printed by `ClientKeysFeatures` on the peer node and record it in `.claude/rules/mcp.md`

**Checkpoint**: US2 proved: a key issued from a route, used by the agent module's client, scoped, listed, revoked across two nodes, never in a log.

---

## Phase 5: User Story 3, first half — tokens, certificates and the resource metadata (Priority: P2)

**Goal**: one endpoint admits any mix of client keys, tokens from the service's issuers and named
workloads; one that admits tokens tells a client where to sign in.

**Independent Test**: `sbt 'mcp/testOnly *ResourceMetadataSuite *AccessFeatures'`; all 8
`access.feature` scenarios green with `TestIssuer`.

### Tests for User Story 3 (first half)

- [X] T048 [P] [US3] Write `…/mcp-test/ResourceMetadataSuite.scala`: an ACL of `Oidc.authenticate(verifier, realm)` yields `admitsTokens` and the issuer's URL; `Acl.anyOf(ClientKeys.authenticate(), Oidc…)` yields both; `ClientKeys.authenticate()` alone yields neither; the document's shape from a `Host` of `cart.shop.example` with and without TLS; the `401` challenge text with `resource_metadata`
- [X] T049 [P] [US3] Write `…/mcp-test/features/AccessFeatures.scala` extending `McpEndpointSteps` for `features/mcp-endpoints/access.feature`: the `customers` issuer is a `TestIssuer` (`authOidc % "test->test"`), the `orders` fixture lists it through `Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer("shop"))))`; steps for "the request is challenged" (status 401 and the `WWW-Authenticate` header), "names the resource metadata of orders", "names no issuer of the platform's" (the control plane's issuer URL absent), "admits only the service support" through the local caller header, the outline of refused credentials, and the tool-ACL scenario; "an MCP endpoint must state its ACL" is driven with a fixture whose `acl` is `null` (`acl` is abstract, so the existing null-acl refusal worded for the kind is the only runtime path), and `ranElsewhere` points its SDK half at `DiscoverySuite`'s empty `McpAcl` case; run red

### Implementation for User Story 3 (first half)

- [X] T050 [US3] Create `…/mcp/ResourceMetadata.scala`: `AclTree.tokenAuthenticators(acl): Vector[TokenAuthenticator]` walking `AnyOf`, `document(request, path, servers)`, `challenge(request, path)`, scheme from `request.caller == Gateway` or the server's TLS; in `McpEndpoint.rootRoutes` the `GET /.well-known/oauth-protected-resource<path>` route with `Acl.AllowAll` when `admitsTokens`, none otherwise; the `POST`/`GET`/`DELETE` routes' `Unauthenticated` challenge replaced by the metadata challenge when `admitsTokens` (a wrapping `Acl.Authenticate` around the endpoint's ACL that rewrites only the challenge)
- [X] T051 [US3] Create `…/mcp/Admission.scala`: `private[ankka] def decide(acl: Acl, context: RequestContext, mounting): Either[Refusal, RequestContext]` reusing `HttpServer`'s `admit` (lift `admit` to `private[ankka] object Admission` in `http` and call it); in `Protocol`'s `ToolsFor`, a tool with its own ACL is listed and callable only when `decide` admits the request's context; `-32602 tool not available` otherwise
- [X] T052 [US3] Run T048–T049 green; add to `…/mcp-test/features/McpEndpointSteps.scala` the assertion that the tool-ACL scenario's refused call ran no handler (counter) and the served one did

**Checkpoint**: the three kinds of caller admitted and refused as the feature says; the metadata names the service's issuers.

---

## Phase 6: User Story 3, second half — brokered sign-in (Priority: P2)

**Goal**: an endpoint opted into brokered sign-in has the platform register a client, send the
person to the service's issuer, and mint a token for that endpoint alone.

**Independent Test**: `sbt 'mcp/testOnly *SignInRecordSuite *BrokerTokenSuite *IssuerClientSuite *BrokeredSignInFeatures'`;
all 7 `brokered-sign-in.feature` scenarios green against `TestIssuer` extended with a code flow.
This phase may ship in a second pull request on the same spec; until then `brokered = true` is
refused at start with "brokered sign-in is not yet available".

### Tests for User Story 3 (second half)

- [X] T053 [P] [US3] Extend `modules/auth-oidc/src/test/.../TestIssuer.scala` with an OIDC code flow on its loopback server: `/.well-known/openid-configuration`, `/authorize` (auto-approves a configured person, or refuses a configured one with `error=access_denied`; records the `nonce` and `state`), `/token` (exchanges the code for an `id_token` it signs, with `nonce`, `aud` the client id; a switch to sign with a foreign key for R19.7), a counter of what it was asked, and `registeredClients` it accepts
- [X] T054 [P] [US3] Write `…/mcp-test/SignInRecordSuite.scala` with `KeyValueEntityTestKit`: the three states round-trip, a `Grant` moves from pending to coded once and refuses a second code, a `RefreshToken` is replaced on use, each write schedules `ankka-sign-in-expiry` at its expiry (through the kit's timer scheduler), the expiry deletes the record
- [X] T055 [P] [US3] Write `…/mcp-test/BrokerTokenSuite.scala`: `BrokerTokens.mint` produces the JWT of contracts/http-wire.md (header `kid`, claims `iss`, `sub`, `aud`, `exp`, `jti`, `typ`, `sign_in_issuer`, the kept claims); `BrokerTokens.verify` accepts it from the matching public key, refuses a foreign key, an expired token, an `aud` whose path is another endpoint's, and an `alg` of `none` or `HS256`; the key cache answers `Unauthenticated` for an unknown `kid`
- [X] T056 [P] [US3] Write `…/mcp-test/IssuerClientSuite.scala`: discovery read once from `TestIssuer`'s `openid-configuration`, the authorization URL built with the broker's client id, `redirect_uri`, `scope=openid`, `state`, `nonce`; the code exchange over `java.net.http` with `client_secret` when configured and PKCE always; the `id_token` verified through `OidcVerifier` with the nonce checked; a foreign-signed `id_token` refused
- [X] T057 [P] [US3] Write `…/mcp-test/features/BrokeredSignInFeatures.scala` extending `McpEndpointSteps` for `features/mcp-endpoints/brokered-sign-in.feature`, with an in-suite OAuth client (`…/mcp-test/features/BrokerClient.scala`: reads the metadata, registers, builds the authorize URL with S256 PKCE, follows the redirects through `TestIssuer`'s auto-approving `/authorize` and the broker's `/callback` without a browser by following `Location` headers, exchanges the code, calls a tool with the token); steps for "names the sign-in broker of orders, at a hostname of orders", "the person is sent to the issuer customers" (the first redirect's host), "the issuer customers was asked for nothing but to sign ada in" (`TestIssuer`'s counter: one authorize, one token exchange, no password endpoint), "orders holds no password of ada" (no record state carries one; the secret store holds only `ankka-sign-in-key-*`), the cross-service scenario with a `billing` fixture; the R19.7 case; run red

### Implementation for User Story 3 (second half)

- [X] T058 [P] [US3] Create `…/mcp/signin/SignInRecordEntity.scala` (`ankka-sign-in-record`, key value, `platform = true`, the sum-type state, commands `put-client`, `begin-grant`, `complete-grant`, `take-code`, `put-refresh`, `take-refresh`, `delete`, query `get`) and `…/mcp/signin/SignInExpiry.scala` (`ankka-sign-in-expiry` timed action deleting the named record, `platformDescriptor` as `ApprovalExpiry` has)
- [X] T059 [P] [US3] Create `…/mcp/signin/SignInKeysEntity.scala` (`ankka-sign-in-keys`, event sourced, id `keys`, events `SignInKeyAdded(kid, publicKeyJwk, createdAt)` / `SignInKeyRemoved(kid)`) and `…/mcp/signin/BrokerKeys.scala` (`mint` writing the PKCS#8 PEM to the secret store under `ankka-sign-in-key-<kid>` before persisting the event; `rotate`/`sweep` with `RotateEvery`/`KeepFor` from config; `sign(claims)` RS256 with the JDK as `MachineKeys.sign` does; `BrokerKeyCache` fed by `ClientKeyIndex`'s follower, `publicKey(kid)`)
- [X] T060 [P] [US3] Create `…/mcp/signin/IssuerClient.scala`: per issuer, lazy discovery, `authorizationUrl(...)`, `exchange(code, verifier)`, `verifyIdToken(...)` through `OidcVerifier` with the nonce; the client id and secret from `Issuer.clientId`/`clientSecret` (new optional fields read in `modules/auth-oidc/.../Issuer.scala` from `ANKKA_AUTH_<NAME>_CLIENT_ID`/`_CLIENT_SECRET`, with a case in `OidcConfigSuite`)
- [X] T061 [US3] Create `…/mcp/signin/BrokerTokens.scala` (`mint`, `verify`, the `TokenAuthenticator` for the endpoint with `authorizationServers = Vector(brokerIssuer)`) and `…/mcp/signin/SignInBroker.scala`: the six root routes of contracts/http-wire.md under `/_ankka/sign-in<path>` built as `Route`s with `Acl.AllowAll`, the per-node `TokenBucket` on `/token` (reuse `controlplane`'s shape by moving `TokenBucket` to `http` as `RateLimit`, or copy its 25 lines into `mcp` and say so in `.claude/rules/mcp.md`), redirect-URI validation (`https` or loopback `http`), error pages never redirecting to an unregistered URI, `Cache-Control: no-store`
- [X] T062 [US3] In `…/mcp/McpEndpoint.scala`: `brokered = true` adds the broker's root routes and the broker `TokenAuthenticator` to the effective ACL (`Acl.anyOf(declared, brokerTokens)`), makes the metadata name the broker's issuer alone, and `problems` refuses `brokered` without a `TokenAuthenticator` in the declared ACL or without a client id on every issuer, naming the variable; `/_ankka/sign-in` joins the reserved paths every endpoint's `problems` refuses; `McpRuntime.descriptors` gains the three sign-in kinds, `start` the rotation check (hourly, as `MachineKeyRotation`), the follower the keys entity
- [X] T063 [US3] Add a brokered fixture to `…/mcp-test/features/Fixtures.scala` and run T054–T057 green; record in `.claude/rules/mcp.md` what the inspector needed from the broker in S2 re-run against the real one (`sbt -Dankka.spikes=on -Dankka.mcp.spike.brokered=on 'mcp/testOnly *OutsideClientSpike'`)

**Checkpoint**: every `access.feature` and `brokered-sign-in.feature` scenario green; the inspector signs in through the broker against the test issuer by hand.

---

## Phase 7: User Story 4 — Python, TypeScript and Rust (Priority: P2)

**Goal**: a service in any SDK language declares an MCP endpoint, the sidecar answers the protocol
and asks the process to run the tool, and the conformance suite proves the three against Scala.

**Independent Test**: `sbt 'sidecar/testOnly *ProtocolSuite *DiscoverySuite *RemoteMcpEndpointSuite *WasmImportsSuite'`,
`sbt 'sidecar/testOnly *ConformanceSuite -- *mcp-endpoint.*'`, then `uv run conformance`,
`npm run conformance`, `./conformance.sh` (quickstart step 4).

### Tests for User Story 4

- [X] T064 [P] [US4] Add the eleven `mcp-endpoint.*` cases of contracts/sdk-apis.md to `sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance/ConformanceSuite.scala`, driven over HTTP with the raw protocol (`postJson` to `/mcp`), the key issued through the reference's `POST /mcp-keys` route, the token from `ConformanceTarget.issuer`; document each in `specs/009-polyglot-runtimes/contracts/conformance.md`; the outline row in `features/polyglot/conformance.feature` is owned by these cases, named in the suite's scaladoc as `McpAgentSuite` names its scenarios (no Gherkin suite runs that file); extend `discovery.lists-every-component`'s route list with the three `MCP /mcp <tool>` records
- [X] T065 [P] [US4] Add to `sidecar/src/test/.../ProtocolSuite.scala` the pin `1.20`; to `DiscoverySuite.scala` cases: a `Spec` with `mcp_endpoints` under `protocol_version = "1.19"` refused naming `McpSince`; a reserved path, a route-claimed path, no tool, a bad name, a blank description, a non-object schema, an empty `McpAcl`, `deny_all` beside `tokens`, `tokens` with no issuer, `brokered` without `tokens` each refused with the Scala wording
- [X] T066 [P] [US4] Write `sidecar/src/test/.../RemoteMcpEndpointSuite.scala`: a scripted `Conversation` receives `RemoteMcpCall` with the checked arguments JSON, the principal (a client key's with `kind = client-key` and no secret anywhere in the message; a token's as its claims with no raw token or signature anywhere in the message, FR-014, R19.4) and the caller; `ok` with `structured_json` becomes text and structured content; `error` becomes `isError: true`; `failure` becomes a tool error naming the tool and a report note; a timeout is a tool error
- [X] T067 [P] [US4] Add to `sidecar/src/test/.../wasm/WasmImportsSuite.scala` the export `ankka1_mcp_tool` and the three imports (it fails until they are listed), and to `WasmHostSuite` a module declaring an MCP endpoint without the export refused

### Implementation for User Story 4: the protocol and the sidecar

- [X] T068 [US4] Edit `protocol/src/main/protobuf/ankka/protocol/v1/discovery.proto` (`Spec.mcp_endpoints = 5`, `McpEndpoint`, `McpTool`, `McpAcl`), create `mcp.proto` (`Mcp.InvokeTool`, `McpToolRequest`, `McpToolReply`, `McpToolAnswer`), edit `client.proto` (the three rpcs and messages) per contracts/protocol.md; bump `protocol/README.md` to `1.20` with its paragraph; add the export and imports to `protocol/WASM-ABI.md`
- [X] T069 [US4] Bump `WireProtocol.Version` in `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/remote/Conversation.scala` to `1.20` and add `invokeMcpTool(call: RemoteMcpCall): Future[Either[ProcessFailure, Either[String, RemoteMcpAnswer]]]` with `RemoteMcpCall(endpointId, tool, argumentsJson, principal: Option[RemotePrincipal], caller: Option[RemoteCaller], metadata)` and `RemoteMcpAnswer(text, structuredJson: Option[String])`, default throwing as `openSocket` does; `Compatibility.version` to `(1, 20)` in `controlplane-api/src/main/scala/.../Compatibility.scala` with the changelog line; `Discovery.ProtocolVersion`'s scaladoc changelog in `sidecar/.../Discovery.scala`
- [X] T070 [US4] In `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/Discovery.scala`: `McpSince = 20`, parse `mcp_endpoints` into `McpEndpointSpec`s, every T065 refusal in `validate`; in `Translate.scala` `toMcpToolRequest`/`fromMcpToolReply`; in `GrpcConversation.scala` `invokeMcpTool` over the new `Mcp` stub; in `wasm/WasmConversation.scala` `fresh("mcp_tool", …)`; in `wasm/ModuleLoader.scala` `Exports` and `Imports`, `wasm/CallSite.scala` `Permitted`, `wasm/WasmDiscovery.scala` `needs()` and the validation, `wasm/HostImports.scala` the three imports gated like `get_secret`
- [X] T071 [US4] Create `sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/RemoteMcpEndpoint.scala`: `extends McpEndpoint(spec.path)`, ACL from `McpAcl` (`Acl.anyOf` of the set members; `tokens` → `authenticated`, `client_keys` → `ClientKeys.authenticate()`, `callers` → `matcherOf`), `brokered` from the spec, tools from `McpTool` with `FunctionTool.raw`-style specs and `run` calling `conversation.invokeMcpTool` with `Trace.outbound` metadata carrying the tool's origin, timeout → tool error; `Main.build` mounts it with the other endpoints and adds `McpRuntime` with its descriptors when any MCP endpoint admits client keys or is brokered, `SidecarExtension` still first; `ClientLogic.scala`/`ClientService.scala` serve the three `Client` rpcs through `service.extension[McpRuntime]`'s `ClientKeys`, `Error` in the reply, refusing when no `McpRuntime` is present
- [X] T072 [US4] In `sidecar/src/test/.../conformance/ConformanceTarget.scala` wire `RemoteMcpEndpoint` and `McpRuntime` into `Sidecar` and `ModuleTarget` and the Scala `InProcess`; in `ConformanceReference.scala` add `CartMcp` (the three tools, `checkout` a reference, `acl = Acl.anyOf(ClientKeys.authenticate(), ConformanceTarget.authenticated)`), `POST /mcp-keys` and `DELETE /mcp-keys/{id}` routes; run the Scala reference's eleven cases green

### Implementation for User Story 4: Python

- [X] T073 [P] [US4] Refresh the proto copy (`sdks/python/scripts/proto.py`), bump `PROTOCOL_VERSION` in `sdks/python/src/ankka/service.py` to `"1.20"`, add `MCP_SINCE = (1, 20)` and `mcp_refusal` to `sdks/python/src/ankka/server.py`'s `Discover` chain, last
- [X] T074 [P] [US4] Create `sdks/python/src/ankka/mcp_endpoint.py`: `McpEndpoint` class (`path`, `acl`, `brokered` ClassVars checked in `__init_subclass__`; `name`, `version` optional), `@tool(description, *, acl=None)` deriving the schema from type hints via `agent._schema_for` (parameters, or one dataclass parameter's fields), `tool.command/query/route` factories resolved in `service.Registry.spec()` against registered components and endpoints with `problems()` naming what is missing, `to_pb()` producing `McpEndpoint`/`McpTool`/`McpAcl`; `Acl.CLIENT_KEYS` and `Acl.any_of(*acls)` in `endpoint.py`; `_takes_client` reused for construction
- [X] T075 [P] [US4] Create `sdks/python/src/ankka/client_keys.py`: `ClientKeys` over the three rpcs (`issue(subject, expiry: Expiry, endpoint=None, scope=(), attributes={}, label=None)`, `list`, `revoke`), `Expiry.after/at/never`, `UNIMPLEMENTED` → "the runtime is too old for client keys (1.20)", `InMemoryClientKeys`, `HasClientKeys` mixin added to `Endpoint`, `McpEndpoint`, `TimedAction` and `Agent`
- [X] T076 [US4] Add `McpServicer.InvokeTool` to `sdks/python/src/ankka/server.py` (find endpoint and tool, decode arguments with the tool's codec, set the request `ContextVar` with principal and caller, run, answer `ok`/`error`/`failure`); register it in `Server.add_servicers`; `ServiceBuilder.register` accepts `McpEndpoint` subclasses and checks path clashes with endpoints
- [X] T077 [US4] Add `CartMcp` with the three tools, `checkout` a reference, and the key routes to `sdks/python/examples/shopping_cart/conformance.py`; unit tests in `sdks/python/tests/test_mcp_endpoint.py` (schema derivation, reference refusals, too-old runtime, no credential reaches the tool) ; `uv run pytest -q && uv run mypy && uv run conformance` green

### Implementation for User Story 4: TypeScript

- [X] T078 [P] [US4] Refresh the proto copy (`sdks/typescript/scripts/proto.ts`), bump `PROTOCOL_VERSION` in `sdks/typescript/src/spec.ts`, add `MCP_SINCE` and `mcpRefusal` to `sdks/typescript/src/server/discovery.ts`, last; pin in `sdks/typescript/test/contracts.test.ts`
- [X] T079 [P] [US4] Create `sdks/typescript/src/mcp-endpoint.ts` (`McpEndpoint`, `McpEndpointClass`, `tools()` builder with `tool`, `command`, `query`, `route`, resolved in `spec.ts`'s `renderSpec` with refusals) and `sdks/typescript/src/client-keys.ts` (`clientKeysFor(client)`, `Expiry`, `Code.Unimplemented` → too old); `Acl.clientKeys` and `Acl.anyOf` in `routes.ts`; `aclToProto` for `McpAcl`
- [X] T080 [US4] Create `sdks/typescript/src/server/mcp.ts` handling `invokeTool` as `server/http.ts` handles a route (request in `AsyncLocalStorage`, `ok`/`error`/`failure`); `service.ts` `register` overload for an `McpEndpointClass`
- [X] T081 [US4] Add `CartMcp` and the key routes to `sdks/typescript/examples/shopping-cart/conformance.ts`; tests in `sdks/typescript/test/mcp-endpoint.test.ts`; `npm run proto && npm run typecheck && npm test && npm run conformance` green

### Implementation for User Story 4: Rust

- [X] T082 [P] [US4] Refresh the proto copy (`sdks/rust/scripts/proto.sh`), bump `PROTOCOL_VERSION` in `sdks/rust/ankka/src/service.rs`, add `mcp_refusal` to `Service::discover`, last; pin in `sdks/rust/ankka/tests/contracts_discovery.rs`
- [X] T083 [P] [US4] Create `sdks/rust/ankka/src/components/mcp_endpoint.rs` (`trait McpEndpoint`, `Tools<Self>` with `tool`, `command`, `route`, `Acl::ClientKeys` and `Acl::AnyOf` in `components/endpoint.rs`, `to_proto`, `handle(McpToolRequest) -> McpToolReply` with the principal and caller on a `Context`) and `sdks/rust/ankka/src/client_keys.rs` (`ctx.client_keys()` over three imports, each in its own `#[link(wasm_import_module = "ankka1")]` block in `abi/imports.rs`, `Error` in the reply)
- [X] T084 [US4] Add `Export::McpTool` and `ankka1_mcp_tool` to `sdks/rust/ankka/src/abi/exports.rs` and the `service!` macro; `Service::register` for an `McpEndpoint` with reference resolution refusals at `discover`
- [X] T085 [US4] Add `CartMcp` and the key routes to `sdks/rust/examples/shopping-cart/src/conformance.rs` (gated `--features conformance` as the rest); tests in `sdks/rust/ankka/tests/mcp_endpoint.rs`; `cargo test --workspace && ./conformance.sh` green in both shapes
- [X] T086 [US4] Name the three scenarios of `features/mcp-endpoints/languages.feature` in `ConformanceSuite`'s scaladoc beside the `mcp-endpoint.*` cases that prove each, as `McpAgentSuite` names its scenarios (no Gherkin suite runs that file); update `docs/contributing/language-sdks.md`'s case table with the eleven cases

**Checkpoint**: the eleven conformance cases green against four references; 1.20 pinned in five places.

---

## Phase 8: User Story 5 — visible everywhere, traced, and on k3s (Priority: P3)

**Goal**: a member, the consoles and a coding agent see an MCP endpoint's tools beside the routes;
a tool call is a span with its subject; the sample proves hostnames, instances and pausing on k3s.

**Independent Test**: `sbt 'controlPlane/testOnly *DeployedTopologyFeatures' 'cli/testOnly *ConsoleTopologyJsSuite' 'mcp/testOnly *ObservingFeatures'`,
then `gh workflow run cluster --ref 054-service-as-mcp-server -f suite=McpEndpointsClusterFeatures`.

### Tests for User Story 5

- [X] T087 [P] [US5] Write `…/mcp-test/features/ObservingFeatures.scala` extending `McpEndpointSteps` for `features/mcp-endpoints/observing.feature`: the trace scenario through the kit's recorder (`span.component == "mcp"`, `handler == "/mcp get_cart"`, `subject == "acme"`, the entity's span a child, the minted key absent from every span's attributes); the report scenario in `ranElsewhere` naming `DeployedTopologyFeatures`
- [X] T088 [P] [US5] Extend `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/DeployedTopologyFeatures.scala` with the report scenario: a scripted instance topology whose `endpoint:/mcp` node has handlers of `type: "tool"` beside `endpoint:/carts`'s route; `services topology` through the real control plane and the CLI's table show both; the console fixture under `console/package/fixtures/control-plane/` regenerated with a tool handler and `ControlPlaneFixturesSuite` green
- [X] T089 [P] [US5] Extend `cli/src/test/.../ConsoleTopologyJsSuite.scala` for the new `features/local-console/services.feature` scenario: a service document with `MCP /mcp get_cart` and `MCP /mcp add_item` routes renders two rows under one `/mcp` heading with the tools and no invoke form; `ConsoleServer` refuses to invoke an `MCP` route as it refuses `GRPC`
- [X] T090 [P] [US5] Write `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/McpEndpointsClusterFeatures.scala` (`GherkinSuite("../features/mcp-endpoints/tools.feature")` and `client-keys.feature` through a shared steps base, every non-k3s scenario in `ranElsewhere`; reads `ankka.cluster.tests` so `.github/cluster-suites.py` lists it): deploy the sample by `BuildInfo.imageTag` with three instances, expose with a custom hostname through `AcmeStack` as `CustomHostnamesClusterFeatures` does, `tools/list` at both hostnames through the gateway, `tools/call` at each pod's address with no session header, a key issued through `POST /mcp-keys` revoked through `DELETE` and refused at all three pods within one second (a loop with a deadline, asserting each pod's first refusal time), pause and assert `/mcp` answers as `/carts` does

### Implementation for User Story 5

- [X] T091 [US5] In `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/TopologyJson.scala` `endpoint(...)` emit `{"name": "<tool>", "type": "tool"}` for a served route whose method is `MCP` (name without the path), and in `ObservabilityDocuments.service()` keep the raw record; in `CallOrigin.scala`'s `DeclaredNames.of` nothing changes (the record's `"MCP <path> <tool>"` is declared by construction) but add a case to `CallOriginSuite`; update `docs/reference/runtime-endpoints.md`'s `/observability/service` row
- [X] T092 [US5] In `cli/src/main/resources/console/app.js` `loadRoutes` render `MCP` rows grouped by endpoint with the tool names and no form; `cli/.../console/ConsoleServer.scala` refuses invoking `MCP`; `cli/.../mcp/AnkkaTools.scala` `describe_local_service`'s description names "MCP endpoints and their tools"; `docs/operate/local-console.md` Invoke and Topology sections
- [X] T093 [US5] In `controlplane-api/.../descriptors.scala` document `TopologyHandler.type = "tool"` in the comment and `docs/reference/control-plane-api.md`'s generated text (`just docs-reference`); `console/package/src/ui/topology/Table.tsx` shows a tool handler with a tool glyph (no schema change); `console/package/src/testing/fake-control-plane.ts` scenario with a tool handler
- [X] T094 [US5] Run T087–T089 green locally; run T090 through `gh workflow run cluster --ref 054-service-as-mcp-server -f suite=McpEndpointsClusterFeatures` and record the measured revoke delay in `.claude/rules/mcp.md`

**Checkpoint**: every scenario of the six feature files owned by a green suite or a `ranElsewhere` pointing at one; the cluster run green.

---

## Phase 9: Polish and cross-cutting

**Purpose**: documentation, the honest lists, the rule file, the whole build.

- [X] T095 [P] Write `docs/build/mcp-endpoints.md` (frontmatter `kind: guide`, `languages: [scala, python, typescript, rust]`, `components: [mcp-endpoint]`, `related: [build/http-endpoints.md, build/mcp-servers.md, concepts/tenancy-and-access.md]`): Declaring an endpoint and its tools (four tabs, includes from `samples/shopping-cart/.../CartMcpEndpoint.scala` and the three examples), A command, query or route as a tool, Who may call (three kinds, `Acl.anyOf`), Client keys (issuing from a route, what the handler reads, listing, revoking), Pointing a client at it (Claude Code, Claude Desktop with a header, an agent of a sibling service), What a client that signs in is told (the metadata, when to opt into brokered sign-in, the issuer's registration), What fails the start, Testing, What it does not do
- [X] T096 [P] Add `mcp-endpoint` to `mkdocs.yml`'s `components` facet, `- MCP endpoints: build/mcp-endpoints.md` to the nav after `MCP servers`, `modules/mcp/src/main/resources/reference.conf` to `generated.configuration.files`; add the page to `tools/docs/skill/ankka-endpoints/SKILL.md`'s `pages` and extend its `description` with "MCP endpoint, tool, client key"; `just docs-sync` to render the skills
- [X] T097 [P] Edit `docs/reference/limitations.md` under Components: tools only (no resources, prompts, sampling, elicitation); stateless (no session, no `list_changed`; a change is seen at the next listing); no one MCP endpoint for every service of a project (spec 055); no authorization server for a service that has not opted in; a route reference's body is JSON; the "Two protocols" entry gains "MCP rides on the HTTP port"; under Networking and security: the sign-in broker holds no account and no password and mints only for an opted-in endpoint
- [X] T098 [P] Edit `docs/reference/akka-divergences.md`: a Summary row and a section "An MCP endpoint offers tools, built or referenced" (Akka's `@McpEndpoint` with `@McpTool`, `@McpResource`, `@McpPrompt`; ankka's tools only, references, client keys, brokered sign-in; why); fix the "What Akka has that ankka does not" line about MCP
- [X] T099 [P] Edit `docs/reference/configuration.md`: a `### MCP endpoints` subsection under "What each variable means" covering each `ankka.mcp.*` key and the two `ANKKA_AUTH_<NAME>_CLIENT_*` variables; `docs/build/mcp-servers.md` gains "An agent lists a sibling's MCP endpoint" with `McpServer.service`; `docs/concepts/tenancy-and-access.md` names the client key as the fourth credential
- [X] T100 [P] Write `controlplane-api/src/test/scala/com/thinkmorestupidless/ankka/controlplane/api/McpEndpointsDocumentationSuite.scala` for the three documentation scenarios of `features/documentation/mcp-endpoints.feature` that read pages (as `GrpcDocumentationSuite`): the four language tabs present, the three ACL kinds named, issuing/listing/revoking described, the metadata and brokered sign-in described, each include region resolving to a file a test compiles, the limitations entries present, the divergences section present; the coding-agent scenario owned by T092's `describe_local_service` test in `cli` and named in `ranElsewhere`
- [X] T101 [P] Create `.claude/rules/mcp.md` (if T005 did not) with `paths: [modules/mcp/**, sidecar/**/RemoteMcpEndpoint.scala, protocol/**/mcp.proto]`: the kernel's rules in one table, the index's query plugin, the reserved paths, the broker's per-endpoint issuer, the serializer trap for references, the inspector's needs from S2, the measured revoke delay, and why grants and rollouts have no MCP test of their own (built routes, `grantable` set as a route's); add the row to `CLAUDE.md`'s rule table and a line to `.claude/rules/build-and-release.md` on the twelfth artifact
- [X] T102 Update `GLOSSARY.md` if any step wording changed in Phases 3–8, and `samples/shopping-cart/GLOSSARY.md` for the sample's new scenarios; `just features` at 0 findings
- [X] T103 Run `just docs-sync && just docs`, `sbt scalafmtAll scalafmtSbt`, `sbt -Dankka.cluster.tests=off buildAll`, `sbt publishLocal` (twelve artifacts), `.github/ci-coverage.py` and `.github/cluster-suites.py`; fix what they report
- [ ] T104 Run `quickstart.md` step 7 by hand (Claude Code against `sbt shoppingCart/run`, a key, the local console's span) and `McpLatencySuite` under `-Dankka.spikes=on` (`…/mcp-test/McpLatencySuite.scala`, written here: 200 reads through `GET /carts/{id}` and through `get_cart`, medians, 1.5×); record both results in `.claude/rules/mcp.md`
- [X] T105 Amend `specs/054-service-as-mcp-server/spec.md`'s Status to Implemented and its Clarifications with anything the implementation decided that the plan did not; add the feature's line to `docs/reference/limitations.md`'s dated list if the page keeps one

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)**: none. T005 and T006 can run as soon as T001 compiles.
- **Phase 2 (Foundational)**: after T001. Blocks every story: `Acl.AnyOf`, the root routes, `problems`, `NeedsService`, `OpaqueCredentials`, the span's subject.
- **Phase 3 (US1 + US6)**: after Phase 2. The MVP.
- **Phase 4 (US2)**: after Phase 3 (needs `McpEndpoint` and the kernel's `ToolsFor`).
- **Phase 5 (US3 first half)**: after Phase 3; independent of Phase 4 except for the mixed-ACL scenario rows, which need both.
- **Phase 6 (US3 second half)**: after Phase 5. May be a second pull request.
- **Phase 7 (US4)**: after Phases 3 and 4 (the conformance cases use client keys); the broker is not conformance-tested, so Phase 6 is not needed.
- **Phase 8 (US5)**: after Phases 3 and 4; T090 needs the sample of T034 and T046.
- **Phase 9 (Polish)**: after every phase the pull request carries.

### Within each story

- Suites first (red), then the code; the suite tasks are marked [P] against each other.
- Entity before index before authenticator before endpoint wiring (Phase 4).
- Protocol files before the sidecar before the SDKs (Phase 7); the three SDKs are independent of each other.

### Parallel opportunities

- Phase 2: T007–T010 together; T016, T017, T018, T019 together once T011 is in.
- Phase 3: T020–T026 together; T028, T029, T030 together after T027's types exist.
- Phase 4: T036–T039 together; T040 and T041 together.
- Phase 6: T053–T057 together; T058, T059, T060 together.
- Phase 7: T064–T067 together; after T072, the Python (T073–T077), TypeScript (T078–T081) and Rust (T082–T085) runs are three independent lines.
- Phase 8: T087–T090 together.
- Phase 9: T095–T101 together.

---

## Parallel Example: User Story 1 + 6

```bash
# the suites, red, together:
Task: "ProtocolSuite against the pure kernel (…/mcp-test/ProtocolSuite.scala)"
Task: "ToolBuilderSuite (…/mcp-test/ToolBuilderSuite.scala)"
Task: "ToolInputSuite with the compileErrors case (…/mcp-test/ToolInputSuite.scala)"
Task: "McpEndpointProblemsSuite (…/mcp-test/McpEndpointProblemsSuite.scala)"
Task: "McpEndpointSteps and ToolsFeatures (…/mcp-test/features/)"
Task: "SiblingAgentSuite (…/mcp-test/SiblingAgentSuite.scala)"

# then the pieces of the module, together once Protocol.scala's types exist:
Task: "McpTool.scala"   Task: "ToolBuilder.scala"   Task: "ToolInput.scala"
```

---

## Implementation Strategy

### MVP first (User Stories 1 and 6)

1. Phase 1 and the two spikes.
2. Phase 2: the seams in `http`.
3. Phase 3: the endpoint, the kernel, the builder, references, the sample.
4. **Stop and validate**: `ToolsFeatures` green, Claude Code listing and calling the sample's tools.
   A service whose ACL is `allowCallers` or `AllowAll` can use this today.

### Incremental delivery

1. US2 (client keys) makes the endpoint usable by a product's customers: the ankka-for-plumbers need.
2. US3 first half (tokens, certificates, metadata) costs little and widens the audience.
3. US4 (languages) rides on US1–US2 and is three independent lines once the sidecar is done.
4. US5 (visibility, k3s) closes the operability loop.
5. US3 second half (brokered sign-in) is the largest slice and has no consumer waiting; it can be
   the second pull request on this spec, with `brokered = true` refused at start until it lands.

### Suggested split into pull requests

- **PR 1**: Phases 1–5, 7, 8 and 9 (everything but the broker), with T062's refusal of `brokered`.
- **PR 2**: Phase 6, the broker, with its documentation section and the limitation line removed.

---

## Notes

- Every `-D` switch a suite reads must be in `Test / javaOptions`; none is added here, `ankka.spikes` is already forwarded.
- No suite binds a fixed port; the broker's URLs come from the request's `Host`.
- A client key appears in no log, span, report or listing: T038 and T066 search for the minted value, not a placeholder.
- Images shared by every session: `McpEndpointsClusterFeatures` names the sample by `BuildInfo.imageTag`, never `:latest`.
- Commit after each task or logical group; `git config core.hooksPath .githooks` once per clone.
