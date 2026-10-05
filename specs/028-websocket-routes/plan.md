# Implementation Plan: Socket Routes — A Long-Lived Connection on an Endpoint

**Branch**: `028-websocket-routes` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/028-websocket-routes/spec.md`

## Summary

An HTTP endpoint can declare a **socket route**. A request to it opens a **socket**: the handler
runs on a virtual thread for as long as the socket is open, reads **frames** with a blocking
`receive()` and writes them with `send(text)`, and reads the caller, the principal and the
opening request as any route's handler does. The endpoint's ACL is decided on the opening
request, so a socket nobody may open is never opened. A Python or TypeScript service declares the
same route and the sidecar relays it; a module that declares one is refused. A socket ends
**closed**, with a **close reason** the client is told, and not **cut off** — when its handler
returns, when it fails, when a limit is passed, and when its instance stops.

Technically: a third route kind in `modules/http`, matched and admitted by the router that is
already there and upgraded through pekko-http's own WebSocket support (R1, R2), bridged to the
blocking handler by two bounded queues (R3). The sidecar builds the same route kind for a
process and relays frames over a new bidirectional call, `Http.HandleSocket`, as protocol 1.9
(R11, R13), so every rule below is the Scala server's and is written once. The operator renders
nothing new (R17).

Planning found six things the spec did not have:

- **pekko-http cannot send a chosen close code** through its public API: 1000 or 1011, nothing
  else. The codes go through one file over its internal frame-level upgrade, chosen by the user
  over narrowing the close reasons to two, and proved by a spike before anything rests on it (R4).
- **pekko-http ends a connection idle for sixty seconds**, so the keep-alive the clarification
  added for the gateway is needed on a laptop too (R6).
- **A span held open is lost.** The recorder overwrites a slot once enough newer spans exist, so
  a socket's span is recorded when it closes, with its start in the past (R9).
- **`ankka services get` lists no routes.** The route is listed where a deployed service's routes
  are: the topology (R10). FR-014 was amended.
- **A process has no file to put the limits in**, so they are also variables, and those are the
  platform's program's (R5). FR-018 was added.
- **A socket route cannot be reached under a mount** of a web-hosted service; the proxy cannot
  pass an upgrade. It is a stated limitation, not a change to the proxy (R17).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `runtime`, `http`, `testkit`, `sidecar`,
`controlplane-api`, `cli`, the shopping cart sample, and one line compiled into `operator`);
Python ≥ 3.12 (`sdks/python`); TypeScript on Node ≥ 22 (`sdks/typescript`); protobuf
(`protocol`); JavaScript (the local console's page). Rust: the protocol copy only.

**Primary Dependencies**: none added, in any language. The server side is pekko-http 1.4.0's
WebSocket support, already on the classpath; the test client is the JDK's
`java.net.http.WebSocket`; the SDKs use the gRPC libraries they have (grpc.aio, Connect).

**Storage**: none. No table, event, state, resource field or control plane wire type.

**Testing**: munit in `http` (the route's rules, mutual TLS on loopback) and in `testkit` (the
upgrade, the close codes, the limits); `GherkinSuite` in `testkit` for two feature files and in
`sidecar` for the one that needs the real token verifier; `ShutdownOrderSuite`; `sidecar`'s
`ProtocolSuite`, `RemoteEndpointSuite`, `WasmHostSuite` and nine new `ConformanceSuite` cases run
against the Scala reference and each SDK; pytest with mypy; Node's test runner with `tsc`; the
operator's and control plane's variable suites; one console fixture case; one k3s suite extended
(`ExposureClusterSuite`) behind one spike; the docs build.

**Target Platform**: wherever an embedded or process-hosted ankka service runs — a developer's
machine and a cluster, behind the gateway. Not a module, and not under a web-hosted service's
mount.

**Project Type**: platform libraries and a protocol (the runtime, the HTTP module, the sidecar,
two SDKs), documentation

**Performance Goals**: an open socket whose handler waits holds no platform thread (SC-003, a
thousand measured). A frame costs one queue hand-off each way and, behind a sidecar, one gRPC
message. Nothing is added to a request route's path.

**Constraints**: the ACL decided before any upgrade and before the 426; no token in a URL, a
response header, a log or a span; memory per socket bounded by `max-frame-size` ×
`unread-frames`; the internal pekko-http names confined to one file and held by a suite; `runtime`
still never sees the generated protocol; a 1.6 process runs unchanged; the three SDK copies of
`protocol/` identical to the canonical one; nothing rendered by the operator changes unless the
gateway spike says it must; `Test / parallelExecution := false` stays; warning-free; no suite
binds a fixed port

**Scale/Scope**: about 6 new Scala source files and 14 changed across seven modules, with 7 new
suites and about 10 changed; 2 protocol files; per SDK about 1 new source file, 6 changed, 2 new
test files and the two example services changed; 1 JavaScript file; about 10 docs pages changed,
none new; the skills rendered again

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | no effect is added. A socket's handler is blocking sequential code on a virtual thread, where endpoints already are; no component handler may hold a socket |
| Virtual threads make blocking free | pass | `receive()` and `send` park the handler's thread; the request context stays a thread-local on that one thread for the socket's life (R3) |
| Where two interpreters reduce the same thing, they share one function | pass | the sidecar builds a `SocketRoute` and serves it through the same `HttpServer`, so the ACL, the token, the limits, the close codes and the shutdown are one implementation (R13) |
| Module dependency direction | pass | the route in `http`; `Conversation.openSocket` in `runtime/remote` in plain values; grpc-java only in `sidecar`; `Recorder` in `runtime`; `TestSocket` in `testkit` |
| `runtime` never sees the generated protocol | pass | `SocketLink` is the seam, as `HttpForward` is (R13) |
| Wire names are a versioning boundary | pass | one rpc, four messages, one field, as a minor; an older counterpart is refused from both ends, naming the version (R11, R12) |
| An unknown case is never read as nothing | pass | an unset oneof closes the socket "failed" (R11) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the SDKs' copy scripts |
| A module cannot stream | pass | refused at discovery naming the route, with a backstop in `WasmConversation` (R14) |
| One declaration of the platform's variables | pass | `ANKKA_SOCKET_` is added to `PlatformVariables` and nowhere else (R5) |
| Never touch `ActorContext` from a `Future` callback | pass | no actor is involved; the stream's callbacks only offer to queues |
| Never intern anything unbounded into the recorder's names | pass | the handler name is the template, declared; a room or a cart id is never interned (R9) |
| Never re-parent an orphan | pass | while a socket is open its calls are in a trace with no root yet, shown as that (R9) |
| Coordinated shutdown's first phase must not wait | pass | the sockets are closed inside the extension's own stop, which that phase starts and does not wait for (R8) |
| The operator renders nothing it need not; a field needs the schema | pass | no field, no object, no rule changes; the one conditional change is behind a spike (R17) |
| Tests are serialised; a test never binds a fixed port; a test names no image by a literal tag | pass | `HttpServer.at("127.0.0.1", 0)` throughout; the cluster cases use the suite's own image tag |
| A `waitFor` must not swallow; an `eventually` waits for what it asserts | pass | `TestSocket.closed` fails on a connection cut off rather than timing out as "not closed" |
| Could this check pass while the thing it checks is false? | pass | R18 lists each guard: codes asserted by number, the keep-alive's negative twin, the thread count proved to move, the conformance wildcard |
| Each acceptance scenario ends as a test that fails without the feature | pass | 45 scenarios in seven feature files, each mapped to a suite in R18 |
| Docs: pages stand alone, samples from tested code, facts generated | pass | three included regions from the examples, two generated tables, three variables in prose; no new page (R19) |
| Every tracked file claimed by a CI path filter | pass | `features/**` and every directory touched are already claimed |

**Violations to justify**: one, against no written principle but against the grain of "depend on
as little as possible": five names from pekko-http's internal package. It is under *Complexity
Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no stored form, no field on
the resource and no grant.

## Project Structure

### Documentation (this feature)

```text
specs/028-websocket-routes/
├── plan.md              # this file
├── research.md          # R1–R20: decisions with file-level evidence; six things to verify first
├── data-model.md        # the route, the socket, close reasons and codes, settings
├── quickstart.md        # the validation runs: spikes → server → service → sidecar → SDKs → offline → k3s → by hand
├── contracts/
│   ├── scala-api.md         # socket(...), Socket, the opening request's answers, TestSocket
│   ├── protocol.md          # the wire at 1.9, the conversation, the conformance cases
│   ├── sdk-apis.md          # Python and TypeScript
│   └── platform.md          # the gateway, stopping, variables, listings, traces
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/sockets/` (five files),
`features/local-console/sockets.feature` and `features/documentation/sockets.feature`, in the
words of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/http/…/http/HttpEndpoint.scala                         # SocketRoute, three socket(...) overloads
modules/http/…/http/Socket.scala                               # new: Socket, SocketClosed, CloseReason
modules/http/…/http/Sockets.scala                              # new: the bridge, the limits, the open-socket registry
modules/http/…/http/HttpServer.scala                           # match, admit, 426, upgrade, list, stop
modules/http/…/org/apache/pekko/http/impl/engine/ws/AnkkaSocketUpgrade.scala   # new: the one file over internals
modules/http/src/main/resources/reference.conf                 # ankka.http.socket.*
modules/runtime/…/runtime/Recorder.scala                       # reserve, record
modules/runtime/…/runtime/remote/Conversation.scala            # openSocket, SocketLink; WireProtocol 1.9
modules/core/…/core/PlatformVariables.scala                    # ANKKA_SOCKET_ (compiled into operator too)
modules/testkit/…/testkit/TestSocket.scala                     # new
modules/testkit/…/testkit/AnkkaTestKit.scala                   # socket(path)

protocol/src/main/protobuf/ankka/protocol/v1/endpoint.proto, discovery.proto
protocol/README.md

sidecar/…/sidecar/RemoteEndpoint.scala                         # a SocketRoute that relays
sidecar/…/sidecar/GrpcConversation.scala                       # the link over the bidirectional call
sidecar/…/sidecar/Discovery.scala, Translate.scala             # the route's rules, the first minor gate
sidecar/…/sidecar/wasm/WasmDiscovery.scala, WasmConversation.scala
sidecar/src/test/…/ProcessDouble.scala, conformance/ConformanceSuite.scala, ConformanceReference.scala

controlplane-api/…/api/Compatibility.scala                     # protocol 1.9

cli/src/main/resources/console/app.js                          # list a socket route, offer no form
cli/…/cli/console/ConsoleServer.scala                          # refuse to call one

samples/shopping-cart/…/api/                                   # /carts/{cartId}/watch

sdks/python/src/ankka/endpoint.py, server.py, service.py, testkit/unit.py
sdks/typescript/src/routes.ts, socket.ts (new), server/http.ts, spec.ts, testkit/unit.ts
sdks/*/examples/…                                              # the watch route; the conformance routes
sdks/rust/ankka/protocol/                                      # the copy, nothing else

docs/build/http-endpoints.md, docs/reference/{limitations,configuration,sidecar-protocol,
  scala-sdk,python-sdk,typescript-sdk,wasm-abi,web-hosting,glossary}.md
CLAUDE.md
```

**Structure Decision**: no module, image or published artifact is added. Each piece goes where
its kind already lives: the route kind beside `StreamRoute`, the relay beside the route the
sidecar already builds for a process, the call on the protocol's existing `Http` service, the
variables in the one declaration. The one new placement is a single ankka source file in a
pekko-http package, which is what reaching `private[http]` requires (R4).

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own.

0. **Spikes** (R4, R6): the close codes and the keep-alive against a bare pekko-http server.
   Nothing else starts until the codes are proved or the spec is narrowed.
1. **A Scala endpoint holds a socket** (User Story 1). The route kind and its rules; the
   upgrade's answers; the bridge and the limits; the subprotocol token; close reasons; the
   recorder and the span; the listing; the stop; `TestSocket`; `SocketFeatures`; the sample's
   route.
2. **The protocol and the sidecar** (User Story 2, first half). 1.9, the link, the relay, the
   rules at discovery, the module's refusal, the process double, the Scala conformance reference
   and cases.
3. **The two SDKs** (User Story 2, second half), independent of each other: the declaration, the
   servicer, the version check at discovery, the unit double, the examples.
4. **The platform around it** (User Story 3). The variables' routing; the local console; then
   the gateway spike and the cluster cases.
5. **Documentation** (User Story 4), then the whole build.

Slice 3 depends on 2; slice 4's cluster cases depend on 1 only.

## Complexity Tracking

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| One source file uses five names from pekko-http's internal `impl.engine.ws` package | the public API sends close code 1000 or 1011 and nothing else; "going away", "too large", "unread" and "not text" cannot be told to a client without it | the public API alone was offered and declined in planning: a reconnecting client acts on "going away", and the glossary's close reasons were settled the same day. The file is one, the family is pinned, and a suite asserts every code, so an upgrade that breaks it is red, with the two-code behaviour as the stated fallback (R4) |
| A socket's span is recorded at the close, not begun at the open (FR-015 as amended) | a slot begun and not completed is skipped by readers and reused, so a long socket would never be recorded | beginning it at the open, as every other span is, records nothing for exactly the sockets worth looking at (R9) |
| The limits are variables as well as configuration keys (FR-018, added) | a process-hosted service has no configuration file of the runtime's | keys alone make the limits fixed for every Python and TypeScript service (R5) |
| The sidecar gates on a minor version for the first time (FR-006) | the spec asks for a refusal at discovery, and discovery is the only place both versions are known before a route is served | leaving it to the SDK alone, as earlier minors did, depends on every SDK remembering; the SDK's check is kept too, for the runtime that predates the gate (R12) |
| The route is listed in the topology, not by `services get` (FR-014 as amended) | `services get` lists no routes and its wire type has no field for them | adding a routes listing to `services get` is a feature of its own, for every kind of route (R10) |
| The method is `socket`, where the spec's first story writes `websocket(...)` | the glossary settled "socket" and refuses "WebSocket" | two words for one thing in the API and the features |

**One consequence to announce in the release**: the protocol's minor version rises, so an SDK
released with this feature states 1.9; a descriptor naming it on a platform not yet upgraded is
refused as outside the supported range, as for every earlier minor.
