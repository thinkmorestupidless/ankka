# Implementation Plan: A Service as an MCP Server

**Branch**: `054-service-as-mcp-server` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/054-service-as-mcp-server/spec.md`

## Summary

A service declares an **MCP endpoint**: a path on its HTTP port, an ACL, and **tools**, each a
handler with typed arguments or a one-line reference to a command, query or route. The platform
answers the Model Context Protocol over Streamable HTTP at that path, statelessly, from any
instance: `initialize`, `tools/list` filtered to what the caller may call, `tools/call` with the
arguments checked against the tool's types, tool errors and protocol errors kept apart as the
protocol keeps them. The ACL knows three kinds of caller: a workload by certificate, a person by a
token from the service's issuers, and the holder of a **client key**, a credential the service
issues through its client for one caller, held as a digest in the service's own database, scoped
to tools, carrying attributes a handler reads as claims, revoked on every instance within a second.
An endpoint that admits tokens publishes the protocol's resource metadata naming the service's
issuers; one that opts into **brokered sign-in** has the platform run a **sign-in broker** at the
service's own hostnames that registers the client, sends the person to the issuer and mints a token
for that endpoint alone. Python, TypeScript and Rust services declare the same under a minor bump
of the sidecar protocol. Every tool is a handler in the topology and a span in the traces.

Technically: a new module `ankka-mcp` above `http`, `agent` and `auth-oidc` (R1, R12). The MCP
endpoint is an `HttpEndpoint` whose three routes are built, not declared, so the HTTP server's
matching, ACL, caller, virtual thread, span and reporting serve it unchanged (R2); `http` gains two
generic seams, root routes and `problems(mounting)`, and one combinator, `Acl.anyOf` (R2). The
kernel is a pure function over the agent module's `Json` (R3). Tools reuse the agent's `SchemaType`
and `JsonSchema.derived`; a reference encodes with the handle's own serializer and is resolved at
the server's start (R4, R5). Client keys are the control plane's deploy-token design moved into a
service: an `OpaqueCredentials` primitive in `http`, a platform entity in the service's database,
a per-node index fed by the journal at a 500ms poll of its own (R8, R9). The sign-in broker is a
per-endpoint OAuth 2.1 authorization server at `/_ankka/sign-in<path>` that is itself an OIDC
client of the service's issuer, with public keys in a platform entity and private keys in the
secret store (R11). Behind the sidecar, protocol 1.20 adds a discovery entry, an `Mcp` service and
three `Client` rpcs, and the SDKs resolve references themselves (R13). One `ServedRoute` per tool
carries the tools to every reader (R14), and a span gains its subject (R15).

Planning found seven things the spec did not have, each amended into the spec and listed in
Complexity Tracking.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (`http`, `auth-oidc`, the new `mcp`, `sidecar`,
`runtime`, `controlplane-api`, `controlplane`, `cli`, `samples/shopping-cart`); Python ≥ 3.12 (`sdks/python`);
TypeScript on Node ≥ 22 (`sdks/typescript`, `console/package`); Rust stable (`sdks/rust`);
protobuf 3 (`protocol/`); JavaScript (the local console's `app.js`); Gherkin.

**Primary Dependencies**: none added to any classpath. `ankka-mcp` takes nimbus through
`ankka-auth-oidc`'s POM (R12). The protocol is MCP revision 2025-06-18 (2025-03-26 also answered),
OAuth 2.1 with RFC 7591, 8414, 8707, 9728 and PKCE S256. Tests: the agent module's `McpClient`,
`TestIssuer`, and, for the spikes, `@modelcontextprotocol/inspector` from npm.

**Storage**: three platform entity kinds in the service's database (`ankka-client-key` event
sourced; `ankka-sign-in-record` key value; `ankka-sign-in-keys` event sourced) and one platform
timed action (`ankka-sign-in-expiry`); the broker's private keys in the service's secret store. No
DDL: the journal and durable state are keyed by entity type. No change to the control plane's
journal or to the `AnkkaService` resource.

**Testing**: munit in `mcp`, `http`, `auth-oidc`, `sidecar`, `controlplane-api`, `controlplane`,
`cli`; `GherkinSuite` in `mcp` for `features/mcp-endpoints/` (one subclass per file) with
`startPeer` for the second node; `McpEndpointsClusterFeatures` on k3s for the three-instance,
hostname and paused scenarios; `ConformanceSuite` `mcp-endpoint.*` against all four references;
`DeployedTopologyFeatures` and `ConsoleTopologyJsSuite` extended; `McpEndpointsDocumentationSuite`;
the SDKs' own unit tests; the docs build; the features check; two spikes first (S1, S2).

**Target Platform**: a service anywhere ankka runs it: a developer's machine, the test kit, an
installation's cluster behind the gateway; a process or module behind the sidecar.

**Project Type**: a platform library module, two HTTP server seams, the sidecar and three SDKs,
the sample, the local console, the control plane's topology, documentation.

**Performance Goals**: a tool call that reads one entity within 1.5× a route's median
(SC-002; `McpLatencySuite` under `-Dankka.spikes=on`, R17): the protocol adds one JSON parse, one
decode of the arguments and one render. Client key verification is a map read and one SHA-256 on
the dispatcher, as a deploy token's is. Revocation seen on every node within one second (SC-003; R8).

**Constraints**: `runtime` gains no dependency; `http` names nothing of MCP; nimbus stays in
`auth-oidc`; no new `ANKKA_` variable family (the broker's client credentials join `ANKKA_AUTH_`);
no secret in any journal (digests and public keys only), none in any log, span or listing; the ACL
does no I/O; every new test switch is forwarded (none is added: `ankka.spikes` is); warning-free;
tests serialised; no fixed port; the twelfth artifact published and `templateArtifacts` still eight;
the sidecar protocol change is a minor, gated from both ends; stored forms stay readable.

**Scale/Scope**: about 22 new Scala source files and 20 changed across `http`, `auth-oidc`, `mcp`,
`runtime`, `sidecar`, `controlplane-api`, `cli` and the sample; about 18 new suites and 14 changed;
2 proto files changed and 1 new, with 3 copies regenerated; about 8 source files per SDK and each
SDK's example and conformance runner; 1 new docs page and about 9 changed; 1 new feature directory
(6 files, 52 scenarios), one new documentation feature (4 scenarios) and 3 feature files changed
(4 scenarios), 60 in all; the glossary.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` and the rule files state as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets them | pass | a tool's run function is ordinary endpoint code on a virtual thread, as a route's is; the client key entity and the sign-in records are entities with effects; the kernel is a pure function (R3) |
| Module dependency direction | pass | `http + agent + auth-oidc → mcp → {sidecar, samples}`; `testkit` is a Test dependency of `mcp` as of `grpc`; `runtime` and `http` gain no dependency; `http` names nothing of MCP (R1, R2) |
| `runtime` never sees the generated protocol | pass | `Conversation.invokeMcpTool` takes plain values in `runtime/remote`; `sidecar` translates (R13) |
| Explicit registration, never scanning | pass | an MCP endpoint is passed to `HttpServer.of`; `McpRuntime.descriptors` are registered by the service, as `AgentRuntime`'s are (R8) |
| The wire name is declared separately and never reused | pass | `issue`, `record-use`, `revoke`, `get` on the key entity; a reference names a handle, and its wire name is the handle's (R4) |
| `Acl.Authenticate` does no I/O on the dispatcher | pass | client keys read a per-node index; broker tokens a per-node key cache; `Oidc` as today (R8, R11) |
| No secret value in a journal | pass | the key's digest and the broker's public keys are journaled; private keys live in the secret store (R8, R11) |
| A fieldless enum gets an explicit string codec in its companion | pass | `Expiry`'s `Never` and the sign-in record's state are sum types with payloads, and `Expiry`'s codec is in its companion |
| jsoniter reads `null` on an `Option` as absent | pass | "never expires" is `Expiry.Never` on the command and `expiresAt = None` only in the stored event; "not given" is the command's absence, refused (data-model) |
| Platform entities are the component's own declaration (`platform = true`) | pass | the three entity kinds and the timed action set it as `ankka-agent-instance` does |
| A platform timed action must be registered on every instance or `TimerSweeper` deletes its rows | pass | `ankka-sign-in-expiry` is in `McpRuntime.descriptors`; a missing registration is warned at start as `AgentRuntime` warns, and the documentation shows `registerAll` |
| Every port a workload has is mutual TLS; the caller is read from the certificate | pass | the MCP endpoint is on the HTTP port; `allowCallers` admits a sibling's agent by its certificate with no credential (FR-005, FR-006) |
| Work handed to another thread cannot see a thread-local | pass | the kernel runs inside `RequestScope.withContext` and `Tracing.request` on the handler's virtual thread; the reference's `route.run` runs there too (R4, R15) |
| Never intern anything unbounded into the recorder's names | pass | tool names are declared and interned; a subject and a key id are span attributes, not names (R15) |
| A name a call carries is believed only when declared | pass | one `ServedRoute` per tool puts `MCP <path> <tool>` in `DeclaredNames` (R14) |
| A behaviour an older runtime must refuse is a new call, gated from both ends | pass | `mcp_endpoints` is a new discovery entry, `Mcp.InvokeTool` and the three `Client` rpcs are new; `McpSince = 20` in the sidecar and each SDK (R13) |
| Every reference declares the same components to the conformance suite | pass | an MCP endpoint is not a component; the route list gains the `MCP` records in all four (sdk-apis) |
| The module loader keeps its own list of the imports and exports it admits | pass | one export and three imports added to every list; `WasmImportsSuite` fails until they are (protocol) |
| A new `ANKKA_` variable is declared once, in `PlatformVariables` | pass | none added; `ANKKA_AUTH_<NAME>_CLIENT_ID` and `_CLIENT_SECRET` are read by `Issuer.read` under the existing `ANKKA_AUTH_` prefix (R11) |
| Every test switch is forwarded to the forked JVM | pass | none added; `ankka.spikes` is forwarded |
| No fixed port in a suite | pass | `HttpServer.at("127.0.0.1", 0)` everywhere; the broker's URLs are built from the request's `Host` |
| Could this check pass while the thing it checks is false? | pass | R19 names seven and how each is sharpened |
| Each acceptance scenario ends as a test that fails without the feature | pass | 60 scenarios mapped in R16, each to one suite |
| Docs: pages stand alone, generated reference, new page in nav and a skill, every sample from tested code | pass | R18; the sample's `CartMcpEndpoint` and the three examples carry the include regions |
| Every tracked file claimed by a CI path filter | pass | `modules/**`, `features/**`, `sdks/**`, `protocol/**` and `docs/**` claim every touched path; `build (rest)` runs the new module unlisted |
| Only a tag publishes; `publishLocal` yields the stated count | pass | `ankka-mcp` is the twelfth; the rule file's count and the template's commented line are updated (R1) |
| Traps are written down the day they cost time | pass | the rule file for the new area, `.claude/rules/mcp.md`, is created with what the spikes teach, and `CLAUDE.md`'s table gains the row |

**Violations to justify**: none. The departures from the spec's wording are in Complexity Tracking.

**Post-design re-check**: unchanged. The contracts add no dependency beyond the module edges named,
no variable, no DDL, no resource field, and no change to any pod template or to the gateway.

## Project Structure

### Documentation (this feature)

```text
specs/054-service-as-mcp-server/
├── plan.md              # this file
├── research.md          # R1–R19: decisions with file-level evidence; S1, S2 spikes first
├── data-model.md        # the endpoint, the tool, the client key, the sign-in records, the span, the config
├── quickstart.md        # the validation runs: spikes → pure → kit → report → sidecar and SDKs → k3s → docs → by hand
├── contracts/
│   ├── scala-api.md       # McpEndpoint, the builder, ClientKeys, McpRuntime, what fails the start, the http seams
│   ├── http-wire.md       # the protocol on the wire, admission answers, resource metadata, the sign-in broker, the token
│   ├── protocol.md        # the sidecar protocol at 1.20: discovery, mcp.proto, client.proto, the WASM ABI
│   └── sdk-apis.md        # Python, TypeScript, Rust; the conformance cases
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/mcp-endpoints/{tools,client-keys,access,brokered-sign-in,languages,observing}.feature`
(new), `features/documentation/mcp-endpoints.feature` (new), `features/agents/mcp-servers.feature`,
`features/polyglot/conformance.feature` and `features/local-console/services.feature`, in the words
of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/mcp/                                                   # new module, artifact ankka-mcp
  src/main/scala/com/thinkmorestupidless/ankka/mcp/
    McpEndpoint.scala                 # the endpoint: three routes, root routes, problems(mounting), tools
    McpTool.scala, ToolBuilder.scala, ToolInput.scala          # the builder, references, derived input
    Protocol.scala                    # the kernel: JSON-RPC, versions, tools/list, tools/call, errors
    Admission.scala                   # a tool's ACL and a key's scope, over http's decision
    ResourceMetadata.scala            # the well-known document and the 401 challenge
    ClientKeys.scala                  # the trait's implementation, the authenticator, issue/list/revoke
    ClientKeyEntity.scala, ClientKeyIndex.scala                # the platform entity and the per-node index
    signin/SignInBroker.scala         # the broker's root routes
    signin/SignInRecordEntity.scala, SignInKeysEntity.scala, SignInExpiry.scala, BrokerTokens.scala, IssuerClient.scala
    McpRuntime.scala                  # the extension: index, key cache, sweep, rotation, descriptors
  src/main/resources/reference.conf   # ankka.mcp.*
  src/test/scala/com/thinkmorestupidless/ankka/mcp/
    ProtocolSuite, ToolBuilderSuite, ToolInputSuite, McpEndpointProblemsSuite, ClientKeyEntitySuite, SignInRecordSuite, BrokerTokenSuite
    features/McpEndpointSteps.scala + ToolsFeatures, ClientKeysFeatures, AccessFeatures, BrokeredSignInFeatures, ObservingFeatures
    SiblingAgentSuite, McpLatencySuite (spikes), ClientKeyIndexSpike, OutsideClientSpike

modules/http/…/http/HttpEndpoint.scala                         # rootRoutes, problems(mounting), Mounting
modules/http/…/http/HttpServer.scala                           # root routes ahead of prefixes; validate; bind NeedsService; Acl.AnyOf in admit; clientKeys on EndpointClients
modules/http/…/http/HttpEndpoint.scala (Acl)                   # Acl.AnyOf, Acl.anyOf; TokenAuthenticator; NeedsService; ClientKeys, Expiry, summaries
modules/http/…/http/OpaqueCredentials.scala                    # new: the shape, the digest, the compare
modules/http/…/http/Tracing (in HttpServer.scala)              # subject on the request span
modules/auth-oidc/…/oidc/Oidc.scala, Issuer.scala              # TokenAuthenticator; CLIENT_ID / CLIENT_SECRET
modules/grpc/…/grpc/Admission.scala                            # Acl.AnyOf
modules/runtime/…/runtime/Recorder.scala, ObservabilityEndpoint.scala   # subject on a span; shown in the detail
modules/runtime/…/runtime/remote/Conversation.scala            # invokeMcpTool and its plain values; 1.20
modules/agent/…/agent/SchemaType.scala                         # ToolOutput[Done]
modules/telemetry-otlp/…                                       # enduser.id from the subject
controlplane/…/auth/DeployTokens.scala                         # delegates to OpaqueCredentials
controlplane/…/api/ControlPlaneAcl.scala                       # Acl.AnyOf
controlplane-api/…/api/Compatibility.scala                     # 1.20 and its changelog line
controlplane/src/test/…/McpEndpointsClusterFeatures.scala      # new, k3s
controlplane/src/test/…/DeployedTopologyFeatures.scala         # the report scenario
controlplane-api/src/test/…/McpEndpointsDocumentationSuite.scala

protocol/src/main/protobuf/ankka/protocol/v1/{discovery,client}.proto, mcp.proto (new); protocol/README.md; protocol/WASM-ABI.md
sidecar/…/sidecar/RemoteMcpEndpoint.scala (new), Discovery.scala (McpSince), GrpcConversation.scala, Translate.scala, Main.scala
sidecar/…/sidecar/ClientLogic.scala, ClientService.scala      # the three rpcs
sidecar/…/sidecar/wasm/{WasmConversation,WasmDiscovery,ModuleLoader,CallSite,HostImports}.scala
sidecar/src/test/…/{ProtocolSuite,DiscoverySuite,RemoteMcpEndpointSuite,WasmImportsSuite}.scala
sidecar/src/test/…/conformance/{ConformanceSuite,ConformanceTarget,ConformanceReference}.scala

sdks/python/src/ankka/{mcp_endpoint.py (new), client_keys.py (new), endpoint.py, server.py, service.py, agent.py}
sdks/python/examples/shopping_cart/conformance.py; sdks/python/tests/…
sdks/typescript/src/{mcp-endpoint.ts (new), client-keys.ts (new), routes.ts, spec.ts, service.ts, server/mcp.ts (new), server/discovery.ts}
sdks/typescript/examples/shopping-cart/conformance.ts; sdks/typescript/test/…
sdks/rust/ankka/src/{components/mcp_endpoint.rs (new), client_keys.rs (new), service.rs, abi/exports.rs, abi/imports.rs}
sdks/rust/examples/shopping-cart/src/conformance.rs; sdks/rust/ankka/tests/…
sdks/{python/scripts/proto.py, typescript/scripts/proto.ts, rust/scripts/proto.sh}   # run to refresh the copies

samples/shopping-cart/src/main/scala/shoppingcart/api/CartMcpEndpoint.scala (new), ShoppingCartEndpoint.scala (an issuing route)
samples/shopping-cart/src/main/scala/Main.scala; samples/shopping-cart/features/, GLOSSARY.md

cli/…/cli/mcp/AnkkaTools.scala                                 # describe_local_service's description
cli/…/cli/console/ConsoleServer.scala; cli/src/main/resources/console/app.js   # MCP rows, span subject
cli/src/test/…/ConsoleTopologyJsSuite.scala
controlplane-api/…/api/descriptors.scala                       # TopologyHandler.type "tool" documented; no shape change

build.sbt                                                      # lazy val mcp; sidecar and shoppingCart depend on it; aggregate
ankka.g8/src/main/g8/build.sbt                                 # the commented ankka-mcp line
.claude/rules/mcp.md (new), CLAUDE.md (the table row), .claude/rules/build-and-release.md (twelve)

docs/build/mcp-endpoints.md (new); docs/build/mcp-servers.md; docs/reference/{limitations,akka-divergences,configuration,runtime-endpoints}.md
docs/operate/local-console.md; mkdocs.yml (nav, facet, generated files); tools/docs/skill/ankka-endpoints/SKILL.md; the rendered skills
```

**Structure Decision**: one module is added, because the endpoint needs `http` and `agent` and
neither may see the other; it is the direction `grpc` took for the same reason. Everything else
goes where its kind already lives: the credential primitive beside the token verifier in `http`,
the seams in the server that owns mounting, the sidecar's endpoint beside `RemoteEndpoint`, the
SDKs' endpoint beside their `Endpoint`, the page beside `mcp-servers.md`. The one new kind of thing
is an authorization server inside a service, and it is a set of root routes and three platform
entities behind one flag.

## Order of work

Cut by user story, tests before the code they hold. Slices 0 to 6 need no cluster; slice 7 is the
k3s run. The sign-in broker (slice 5) is the largest single piece and the only one with no
consumer waiting on it: it can land in a second pull request on the same spec if the first grows
too large, with `brokered` refused at start until then.

0. **Spikes** — S1 and S2 of the research under `-Dankka.spikes=on`: the second query plugin at
   500ms across two kit nodes; an outside client against a loopback endpoint. R3 and R8 rest on them.
1. **The seams and the primitive in `http`** — `Acl.AnyOf` through every match, `TokenAuthenticator`
   on `Oidc.authenticate`, `NeedsService`, `rootRoutes` and `problems(mounting)` in the router and
   `validate`, `OpaqueCredentials` with `DeployTokens` delegating, `subject` on a span. All pure
   suites.
2. **User Story 1 and 6: the endpoint and its tools** (FR-001 to FR-005, FR-002a). The module, the
   kernel against the protocol's examples, the builder, `ToolInput`, references, `problems`, the
   `ServedRoute`s, the span. `ToolsFeatures` green but for the three k3s scenarios; `SiblingAgentSuite`.
3. **User Story 2: client keys** (FR-009 to FR-013). The entity, the index with its query plugin,
   `McpRuntime`, `ClientKeys` on `EndpointClients`, the authenticator and its binding, the scope
   filter, the sweep. `ClientKeysFeatures` on two kit nodes.
4. **User Story 3, first half: tokens, certificates, metadata** (FR-006 to FR-008). The ACL tree
   read, the well-known route, the challenge. `AccessFeatures`.
5. **User Story 3, second half: brokered sign-in** (FR-008a). The records, the keys, the broker's
   six routes, the issuer client, the token verifier, rotation and expiry. `BrokeredSignInFeatures`
   with a small OAuth client in the suite.
6. **User Story 4: the sidecar and the SDKs** (FR-014, FR-015). Protocol 1.20, `RemoteMcpEndpoint`,
   the `Client` rpcs, the module's export and imports; then Python, TypeScript and Rust in turn,
   each with its example and the `mcp-endpoint.*` conformance cases.
7. **User Story 5: the report, the consoles, the traces** (FR-016, FR-017) and **the sample on
   k3s**. `CartMcpEndpoint`, the issuing route, `McpEndpointsClusterFeatures`,
   `DeployedTopologyFeatures`, the local console's rows and span subject, `describe_local_service`.
8. **Documentation** (FR-018), the rule file, the glossary's sample copy, then the whole build.

Slice 6 depends on 2 and 3; 5 depends on 4; 7 depends on 2 and 3; 8 depends on everything.

## Complexity Tracking

No principle is departed from. The rows are places the plan departs from the spec's wording, each
with the simpler thing that was rejected and the spec amended.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The tools appear in the service's topology, not in `services get` (FR-016, amended) | `services get` lists no routes today; routes reach the control plane only as topology handlers | a new routes field on `ServiceStatus` is a second path for one fact the topology already carries (R14) |
| A service with client keys or brokered sign-in registers `McpRuntime` and its descriptors (SC-001, amended) | the index, the sweep and the platform entities need an extension and explicit registration | registering them implicitly from the endpoint is classpath-scanning by another name (R8) |
| A client key is issued for one MCP endpoint (FR-009, amended) | a scope is a list of tool names, which are unique per endpoint | a service-wide key would need tool names unique per service, or a scope that names endpoints (R9) |
| Brokered sign-in is per MCP endpoint with issuer `/_ankka/sign-in<path>`, and the service's issuer must hold a client for the broker (FR-008a, amended) | two brokered endpoints must not claim one root route, and `aud` names the endpoint; the broker is an OIDC client and the issuer must know it | one broker per service cannot name the endpoint in `aud`; a broker the issuer has never seen cannot sign anyone in (R11) |
| Arguments are checked by decoding into the tool's types (FR-004, amended) | the schema is derived from the types | a JSON Schema validator is a dependency for a second statement of the types (R5) |
| A route reference's body is one JSON argument, `body` (FR-002a, amended) | a `Route` knows only that it needs a body | recording a body schema on every route touches every body codec for one reference kind (R6) |
| `ankka-mcp` depends on `ankka-auth-oidc` (Assumptions, added) | the broker verifies the issuer's token; `Oidc.authenticate()` is in scope for every MCP endpoint | a JDK-only JWT verifier beside `MachineTokens`, or the broker in `auth-oidc` behind a seam; worth a second look before tasks (R12) |

**Operational consequences** to announce in the release:

- A twelfth library, `ankka-mcp`; a service adds it to declare an MCP endpoint, and `ankka init`'s
  template carries it as a commented line.
- The sidecar protocol is 1.20; an SDK at 1.20 declaring an MCP endpoint refuses a runtime below
  it, and nothing else changes for older SDKs.
- `/_ankka/sign-in` joins `/_ankka/health` as a reserved path on every service; a service with a
  route there fails its start.
- A service that opts into brokered sign-in sets `ANKKA_AUTH_<NAME>_CLIENT_ID` (and
  `_CLIENT_SECRET` for a confidential client) for the issuer the broker sends people to, after
  registering the broker's callback URL at that issuer.
- `Acl.anyOf` is new on every endpoint, HTTP and gRPC alike.
- A span now carries the principal's subject, in the local console and as `enduser.id` in OTLP.
