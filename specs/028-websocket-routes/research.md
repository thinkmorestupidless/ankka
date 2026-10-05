# Research: Socket Routes

Decisions for [plan.md](plan.md), each with what in the repository or in pekko-http it rests on.
File references are to this branch's base (`64102d79`); pekko-http references are to the sources
of `pekko-http-core_3` 1.4.0, the version `project/Dependencies.scala:10` pins. "Verify first"
marks a claim read from code or a dependency and not yet run; the task that touches it starts
with a test or a spike that would show it false. The words are the glossary's: a **socket** is
opened by a request to a **socket route**, carries **frames**, and ends **closed** with a **close
reason** or **cut off**.

## R1. A socket route is a third route kind on `HttpEndpoint`, declared with `socket`

**Decision**: `modules/http` gains `SocketRoute(template, run: (Vector[String], Socket) => Unit,
acl)` beside `Route` and `StreamRoute`, collected by three `socket` overloads on `HttpEndpoint`
(zero, one and two path parameters; no body). The handler is given a `Socket`:

```scala
trait Socket:
  def receive(): Option[String]   // the next frame; None once the socket is closed, by anyone
  def send(text: String): Unit    // throws SocketClosed once the socket is closed
```

**Rationale**: `StreamRoute` is already "a separate route kind rather than a `ToResponse`
instance because the server has to treat it differently all the way down"
(`HttpEndpoint.scala:134-149`), and a socket differs more. Path parameters arrive as typed
arguments through `FromPath` exactly as `sse` takes them (`HttpEndpoint.scala:268-290`).
`receive()` returning `Option` is the smallest shape that "returns the next text frame or reports
the close" (FR-003) and makes `while socket.receive() match …` end by itself. The method is
`socket`, not the `websocket` of the spec's first story, because the glossary settled the word
and refuses the other.

**Alternatives considered**: a `Flow[String, String, ?]` handler, as `sse` returns a `Source` —
rejected: the feature's second decision is that the handler is ordinary blocking code, and a flow
cannot call `componentClient` between frames without leaving the virtual thread. A `receive()`
that throws on close — rejected: a client closing is the ordinary end of a socket, not a fault.

## R2. The upgrade is answered from the router ankka already has

**Decision**: `Router.matched` consults socket routes for a `GET`; the effective ACL is decided
by the existing `admit` before anything else; then a request without pekko-http's upgrade
attribute is answered 426 with `Upgrade: websocket`, and one with it is upgraded. `validate`
counts a socket route as `("GET", template)`, so a socket route and a `GET` or SSE route on one
template are refused at startup by the rule that already refuses duplicates.

**Rationale**: ankka routes against `HttpRequest` directly, not through directives
(`HttpServer.scala:25-31`), and pekko-http hands a low-level handler the upgrade as a request
attribute, `AttributeKeys.webSocketUpgrade` (`scaladsl/model/AttributeKeys.scala:20`), whose
`handleMessages(flow, subprotocol)` builds the 101 response. Because `admit`
(`HttpServer.scala:416-442`) runs first and unchanged, a refused upgrade is the same 401 with
`WWW-Authenticate`, 403 or 503 a request route gives (FR-002), and no handler runs. Deciding the
ACL before the 426 keeps the property the router states at `:299-303`: a closed endpoint does not
disclose which of its paths exist.

## R3. Between the stream and the blocking handler: two bounded queues

**Decision**: the upgraded connection is a `Flow.fromSinkAndSource`. Inbound messages are reduced
to whole frames (R5) and offered to a bounded queue that `receive()` takes from; outbound is a
`Source.queue` that `send` offers to and waits on. The handler runs once, on
`AnkkaExecutors.virtual`, inside `RequestScope.withContext`, from the upgrade until it returns.
When it returns the outbound source completes; when it throws, the close reason is set to
"failed" first. A `SocketClosed` that escapes the handler is its ordinary end, not a failure.

**Rationale**: both waits park a virtual thread and release its carrier, which is the property
FR-004 and SC-003 ask for and the one `dispatch` already relies on (`HttpServer.scala:459-471`).
The request context is a `ThreadLocal` set for the handler's thread (`RequestContext.scala:112-133`),
so holding it for the handler's whole run is what gives the handler the caller, the principal,
the query and the headers "for the life of the connection" with no new accessor.

**Verify first**: a `send` on a socket whose client reads slowly blocks rather than buffering
without bound; a thousand handlers parked in `receive()` add no platform thread.

## R4. Close codes go through one file over pekko-http's internals

**Decision**: one source file in `modules/http`, compiled into the package
`org.apache.pekko.http.impl.engine.ws`, upgrades through `UpgradeToWebSocketLowLevel.handleFrames`
with pekko's own `WebSocket.stack` joined to ankka's message flow, followed by one stage that
replaces the outgoing close frame with `FrameEvent.closeFrame(code, reason)` when ankka has
chosen a close reason. Ankka always *completes* its outbound source and never fails it; the
reason is a value set before completing.

| Close reason | Code | When |
|---|---|---|
| finished | 1000 | the handler returned |
| going away | 1001 | the instance is stopping (R8) |
| not text | 1003 | a binary frame arrived |
| unread | 1008 | more frames were waiting than a socket holds (R5) |
| too large | 1009 | a frame was larger than a frame may be (R5) |
| failed | 1011 | the handler threw, or the process behind a sidecar failed |

**Rationale**: pekko-http's public API cannot choose a code. `FrameOutHandler` sends
`CloseCodes.Regular` when the user's stream completes and `UnexpectedCondition` with the fixed
text "internal error" when it fails (`FrameOutHandler.scala:73-79`); `ActivelyCloseWithCode` is
produced only by its own frame parser (`FrameHandler.scala:163-166`). The frame-level upgrade is
`private[http]` and marked internal "for now" (`UpgradeToWebSocketLowLevel.scala:43-45`), and the
server's upgrade header is that class (`Handshake.scala:126-145`). Rewriting one frame keeps all
of pekko's message assembly, UTF-8 decoding, ping answering and close handshake; completing
rather than failing also avoids the error-level log line pekko writes for a failed handler
(`FrameOutHandler.scala:76`), which would otherwise appear for every restart. The internal names
used are five: `UpgradeToWebSocketLowLevel.handleFrames`, `WebSocket.stack`,
`FrameEvent.closeFrame`, `FrameStart` and `Protocol.Opcode.Close`. This package has barely changed
since akka-http 10.0, the family is pinned (`Dependencies.scala:103-113`), and a suite asserts
every row of the table on the wire, so an upgrade of pekko-http that breaks the file is a compile
error or a red test. If the upgrade attribute is ever not that class, the file falls back to the
public API and the table collapses to 1000 and 1011; the suite fails then too, on purpose.

**Alternatives considered**: the public API alone — offered to and declined by the user in
planning: "going away" is the code a reconnecting client acts on. Rebuilding pekko's message
layer over frames — rejected: several hundred lines of someone else's internals instead of five
names. A second HTTP server library for sockets — rejected: a second listener, a second TLS
setup and a second place the caller is read from.

**Verify first**: that ankka code in a pekko package compiles under Scala 3 and reaches
`private[http]` members; that the attribute's value is an `UpgradeToWebSocketLowLevel`; that each
code reaches a client. This is the first task of the feature, as a spike.

## R5. Limits: a frame's size, and frames waiting unread

**Decision**: three settings in `modules/http`'s `reference.conf`, each with a variable:

| Key | Variable | Default |
|---|---|---|
| `ankka.http.socket.max-frame-size` | `ANKKA_SOCKET_MAX_FRAME_SIZE` | 64 KiB |
| `ankka.http.socket.unread-frames` | `ANKKA_SOCKET_UNREAD_FRAMES` | 64 |
| `ankka.http.socket.keep-alive` | `ANKKA_SOCKET_KEEP_ALIVE` | 20s |

A frame's size is its text as UTF-8 bytes, whole: a message a client sent in several fragments is
one frame to a handler. A frame over the bound closes the socket "too large" without being
buffered; a frame arriving when `unread-frames` are already waiting closes it "unread".
`ANKKA_SOCKET_` joins `PlatformVariables.RuntimeOnlyPrefixes`, so a descriptor may give the
variables and for a process-hosted service they go to the platform's container.

**Rationale**: pekko-http has no bound of its own on a message; a large one arrives as
`TextMessage.Streamed`, so the bound is a `limitWeighted` over its parts and memory never holds
more than the bound. 64 KiB is a generous chat or notification message and far under grpc-java's
4 MiB message bound, which a frame relayed to a process must fit in; the sidecar refuses a larger
configured value at startup, naming both. The variables exist because a Python or TypeScript
service has no `application.conf` of the runtime's, and they are the platform's program's in
exactly the sense `PlatformVariables.RuntimeOnlyPrefixes` describes
(`PlatformVariables.scala`, "For the platform's program and never the developer's"): the sidecar
holds the socket. Every variable must be mentioned in the configuration page's prose, which the
docs coverage check enforces (`tools/docs/.../generate.py:229-245`).

**Alternatives considered**: backpressure instead of closing a socket whose handler does not
read — rejected by the spec's edge case, and a handler that only sends would stall its client's
writes for ever. Counting the unread bound in bytes — rejected: with a frame bound, a count is a
byte bound, and a count is what a developer can reason about.

## R6. Keep-alive, and why it is not only for the gateway

**Decision**: the `WebSocketSettings` given to the upgrade (R4's shim builds pekko's stack with
them) carry `periodic-keep-alive-max-idle` from `ankka.http.socket.keep-alive`, in ping mode. A ping is a control frame: the
handler never sees it and it is not a frame in the glossary's sense.

**Rationale**: pekko-http's server `idle-timeout` is 60 seconds (its `reference.conf:46`) and
nothing in the repository overrides it (the only `pekko.http.server` key set anywhere is the TLS
session header, `ankka-cluster-kubernetes.conf:55`). An upgraded connection is still that
connection, so a socket nobody writes to is *cut off* after a minute on a developer's laptop,
before any gateway is involved. A ping every 20 seconds is activity for that timeout and for
Envoy's stream idle timeout (five minutes by default) alike. The setting is applied to
the settings the upgrade is built with, rather than by a substitution of pekko's own key in
`reference.conf`: a service's own configuration then changes one ankka key.
A keep-alive not shorter than pekko-http's idle timeout would do nothing, so startup refuses
that configuration naming both values.

**What this does not do**: pekko-http does not wait for a pong. A client that vanished is noticed
when the connection's own failure is, which can take minutes; the handler is then told the socket
is closed. That is stated in the limitations.

**Verify first**: a socket with no frames for 90 seconds is still open on loopback, and is cut
off with the keep-alive turned off — the second half is what proves the first.

## R7. A browser's token arrives as a subprotocol

**Decision**: for a socket route, when the request has no `Authorization` header and offers a
subprotocol `ankka.bearer.<token>`, the context the ACL and the handler are given carries
`Authorization: Bearer <token>`, and never the `Sec-WebSocket-Protocol` header. The upgrade
selects the subprotocol `ankka.socket` when the client offered it and none otherwise; it never
selects the bearer one. A browser therefore writes
`new WebSocket(url, ["ankka.socket", "ankka.bearer." + token])`.

**Rationale**: `Acl.Authenticate` is `RequestContext => AuthDecision` (`HttpEndpoint.scala:110`)
and the OIDC verifier reads the bearer token from the header (`auth-oidc/Oidc.scala:14, 65`).
Presenting the token where every authenticator already looks means `Oidc`, the control plane's
ACL and a service's own `Authenticate` work on a socket route with no change, and the sidecar
gets it for nothing since it serves through the same `HttpServer` (R13). A JWT is base64url
segments joined by dots, all legal in a subprotocol token. Selecting a fixed second subprotocol
is the shape Kubernetes uses for the same problem and for the same reason: a client that offered
subprotocols and is answered with none may fail the connection, and answering with the bearer
one would repeat the token in a response header (FR-017).

**Verify first**: what a browser, Node's `WebSocket` and the JDK's do when they offer only the
bearer subprotocol and none is selected; the documentation says "offer both" either way.

## R8. A stopping instance closes its sockets "going away"

**Decision**: `HttpServer` keeps the sockets it has open. `stop()` unbinds, sets every open
socket's close reason to "going away" and completes it, waits up to two seconds for the close
handshakes, and only then calls `terminate` as today. A handler blocked in `receive()` is told
the socket is closed and returns.

**Rationale**: `stop()` today is `terminate(5.seconds)` (`HttpServer.scala:159-161`), whose
deadline ends connections still open — for a socket, cut off. The stop is already started by
coordinated shutdown's first phase without waiting and awaited in its last
(`Ankka.scala:491-503`), so nothing about the ordering the shutdown trap protects changes: the
work is inside the extension's own stop. SIGTERM arrives after the five-second `preStop` sleep
(`Rendering.scala:103`), by which time the pod has left its Service's endpoints, so a socket
opened again reaches another instance (clarified, FR-013).

**Verify first**: that `unbind` followed by completing the sockets produces close frames before
`terminate` runs, in `ShutdownOrderSuite`'s style: coordinated shutdown alone, a client asserting
1001.

## R9. One span per socket, recorded when it closes

**Decision**: `Recorder` gains a way to reserve a span id and to record a span whole, with a
start in the past. A socket reserves its id when it opens, runs its handler inside
`Trace.within(traceId, thatId, origin)`, and records the span when it closes: `Ok` when the
handler returned (or let `SocketClosed` escape), `Failed` when it threw. The span's component is
`http` and its handler `SOCKET <template>`.

**Rationale**: `Tracing.request` begins a span before the handler and completes it after
(`HttpServer.scala:627-642`). `Recorder.begin` claims a slot in a ring at once; a reader skips a
slot still in flight, and `complete` leaves a slot alone if it has been reused since
(`Recorder.scala:76-96, 99-104`). A socket open for an hour on a service doing anything else
would have its slot overwritten long before it closed, and FR-015's "one invocation from the
upgrade to the close" would be recorded never. Recording at the close costs one thing, stated in
the spec's amendment: while the socket is open, the calls its handler makes are in a trace whose
root is not there yet, which the trace view already reports as partial rather than re-parenting.
Observed calls are unaffected: they are counted from the `CallOrigin`, not the span
(`HttpServer.scala:336-340`).

## R10. Where a socket route is listed

**Decision**: a socket route is `ServedRoute("SOCKET", path, streaming = true, endpoint)`. That
one value reaches the local console's service document, the topology's handlers and
`DeclaredNames`. The local console lists it beside the endpoint's routes and offers no form to
call it; `ankka services topology` and the console's topology page show it as the handler
`SOCKET /stream` with no change to a wire type.

**Rationale**: a gRPC method is listed the same way, as `ServedRoute("GRPC", …)`
(`GrpcServer.scala:154-156`), and the local console already refuses to call one
(`cli/.../ConsoleServer.scala:271-283`, `console/app.js:218-245`). The control plane reads
handlers by name from each instance (`TopologyHandler(name, type, streaming)`,
`controlplane-api/descriptors.scala:969-978`), so a new method word needs no field. `ankka
services get` prints no route of any kind (`Output.scala:157-172`) and `ServiceStatus` has no
field for one, which is why FR-014 was amended rather than a routes listing invented here.

## R11. The protocol: `HandleSocket`, at 1.9

*Renumbered on rebase, 2026-10-05:* features 024 and 025 merged first and took 1.7 and 1.8, so
socket routes are 1.9, as T002 provided for. The Rust crate states 1.7, as `main` left it: a module
cannot declare a socket route, and the crate does not adopt a minor for what it cannot use.

**Decision**: `endpoint.proto`'s `Http` service gains
`rpc HandleSocket (stream SocketIn) returns (stream SocketOut)`; `Route` gains `bool socket = 8`.
The messages are in [contracts/protocol.md](contracts/protocol.md). The runtime sends `open`
first, carrying the same `HttpRequest` a request route's call carries — path arguments, query,
headers, principal, caller and metadata — then a `frame` per client frame, then `closed` with the
close reason and half-closes. The process sends a `frame` per send and ends with `completed`, or
`failed`. `WireProtocol.Version` becomes 1.9 and `Compatibility.Protocol.version` (1, 9).

**Rationale**: it mirrors `HandleStream`'s `StreamFrame { text | completed | failed }`
(`endpoint.proto`) with an inbound side, as the spec's third decision says. Carrying the whole
`HttpRequest` in `open` is what makes "the handler receives the principal and the caller with the
first message" (FR-007) and gives the handler's calls their attribution: the metadata carries the
trace and `ankka-caller` exactly as `forwardOf` builds it today (`RemoteEndpoint.scala:87-107`).
`SocketFrame` is a oneof with one case so binary is one added case (clarified). A runtime reads a
case it does not know as no case at all, so an unset `kind` or `message` is a protocol violation
that closes the socket "failed", never a frame silently dropped — the trap `Translate` records
for `produce_all`. The open's metadata also states `ankka.protocol`, as a consumer request's does
(`RemoteProjection.scala:61`), so a later SDK can know whether it may send a new frame kind.

The number is the next free minor today. Features 024 to 027 are in flight in other worktrees
and some of them change the protocol; whichever merges second takes the next number, and the
contracts name the number in one place each.

## R12. An older counterpart is refused from both ends

**Decision**: `Discovery.validate` refuses a spec that declares a socket route under a
`protocol_version` whose minor is below 9, naming the route and both versions — the first minor
gate in the sidecar. Each SDK refuses to answer discovery with a socket route when the
`SidecarInfo.protocol_version` it was sent is below 1.9, and answers `Handle` or `HandleStream`
for a socket route with a failure naming 1.9.

**Rationale**: the sidecar today refuses only a different major (`Discovery.scala:136-140`), and
every minor feature so far has been guarded in the SDK at use (`sdks/python/.../server.py:444-476`,
`sdks/typescript/src/consumer.ts:14-31`). A socket route can be guarded earlier and better,
because discovery tells the process the runtime's version before the process answers
(`discovery.proto:14`, `SidecarInfo`). Without the SDK's check, an older runtime would read a
socket route as an ordinary `GET` route, since it does not know field 8, and call `Handle` on it;
the failure answer is the backstop for that. On the platform a descriptor naming a protocol newer
than the platform's is already refused before anything is deployed
(`ServiceProjection.scala:41-46`).

## R13. The sidecar relays; it does not interpret

**Decision**: `Conversation` gains `openSocket(request: HttpForward): SocketLink`, in plain
values, and `RemoteEndpoint` turns a discovered socket route into a `SocketRoute` whose handler
is a relay: one virtual thread moves the process's frames to `socket.send`, a second moves
`socket.receive()` to the process, and the handler returns when the process completes, or throws
when it fails. `GrpcConversation` implements the link over grpc-java's bidirectional call and
forwards a client frame only when the call is ready, so a process that does not read leaves
frames in the socket's own bounded queue, where R5's rule closes it.

**Rationale**: the sidecar already serves a process's routes through the same `HttpServer` by
building `Route` and `StreamRoute` values (`RemoteEndpoint.scala:51-73`), so the ACL at the
upgrade, the subprotocol token, the limits, the keep-alive, the close codes, the shutdown and the
span are all the Scala server's, unchanged, and `runtime` still never sees the generated protocol
(`Conversation.scala:254-303`). A process that dies surfaces as `onError` on the call
(`GrpcConversation.scala:499` for a stream today), which the relay turns into a thrown handler
and so into "failed" (FR-010) with no list of open sockets to keep. There is no bidirectional
endpoint call today; the entity sessions (`GrpcConversation.scala:114-406`) show the observer
pair, and the workflow servicers in both SDKs show the other side.

**Verify first**: that waiting on `ClientCallStreamObserver.isReady` from a virtual thread
bounds what grpc-java buffers; the HTTP/2 window is counted in bytes and grows, so the test uses
16 KiB frames, as the gRPC flow-control cases do.

## R14. A module is refused, with the route named

**Decision**: `WasmDiscovery` refuses a route with `socket` set: "endpoint '<id>': route '<id>'
is a socket route, and a module answers a request whole; a module cannot hold a socket".
`WasmConversation.openSocket` fails as `handleHttpStream` does. The Rust crate gains nothing.

**Rationale**: beside the existing refusal of a streaming route (`WasmDiscovery.scala:82-85`) and
its backstop (`WasmConversation.scala:369-371`). The crate cannot express a streaming route
(`sdks/rust/ankka/src/components/endpoint.rs:302-350, 524`) and the ABI's `ankka1_http` is whole
requests only (`protocol/WASM-ABI.md:53`), so the refusal is reached only by a module built
without the crate; the test builds the discovery message by hand.

## R15. Python: `@socket`, an async iterator and an awaited send

**Decision**: `ankka.endpoint` gains `socket = _route("GET", socket=True)` and a `Socket` with
`__aiter__`/`__anext__` over the frames, `async receive() -> str | None` and
`async send(text)`. `HttpServicer.HandleSocket` reads `open`, sets the request context for the
handler's task as `_handle_stream` does, and pulls the request iterator only as the handler
asks, so a handler that does not read is not read for. `EndpointTestKit` gains a socket double.

**Rationale**: the decorator table and `RouteSpec.streaming` are the shape to extend
(`endpoint.py:113-131, 147-175`); `WorkflowServicer.Handle` is the SDK's existing bidirectional
servicer (`server.py:350-398`). Pulling lazily is what lets R13's flow control reach the client.

## R16. TypeScript: `socket(...)`, an async iterable and an awaited send

**Decision**: `routes.ts` gains `socket<Ep>(template, run: (self, request, socket) =>
Promise<void>, options?)`; `Socket` is `AsyncIterable<string>` with `send(text): Promise<void>`.
`server/http.ts` gains `handleSocket`, an async generator over the request iterable, registered
beside `handle` and `handleStream`. The request iterable implements `return` and `throw`.

**Rationale**: `sse` and `RouteRef` are the shape (`routes.ts:93-195`), `handleWorkflow` the
existing bidirectional handler (`server/workflow.ts:184`), and the `throw` requirement is the
Connect trap already recorded for hand-built iterables.

## R17. The gateway: nothing rendered changes, and one thing cannot work

**Decision**: the operator renders nothing new. The cluster cases in `ExposureClusterSuite` are the
evidence: one holds a socket through the gateway for twenty seconds with a frame every five, and
another leaves one quiet for ten minutes. Only if the spike shows the route timeout cutting an upgraded
connection does the HTTP rule gain `timeouts.request: "0s"`, as the gRPC rule has.

**Rationale**: an exposed service has one `HTTPRoute` whose HTTP rule is a catch-all with no
timeouts (`Rendering.scala:474-529`), over HTTP/1.1 with the gateway's client certificate
(`ZeroTrust.scala:354-389`, `envoyproxy.yaml:17-20`), which is the protocol an upgrade rides on.
The rule's own comment says an unnamed timeout "would cut every stream" at fifteen seconds
(`Rendering.scala:470`); whether that applies after a successful upgrade is not something the
repository can answer, and the fallback changes what every exposed service renders, so it is
taken only on evidence. A route change of that kind rolls no pod.

**What cannot work**: a web-hosted service's proxy removes `Upgrade` and `Connection`
(`proxy-core/.../Headers.scala:22-31`) and is built on the JDK's HTTP server, which cannot
upgrade. A socket route of a mounted service is therefore not reached through the mount, and the
limitations page, which today says only that "WebSockets do not work" for web hosting
(`docs/reference/limitations.md:37-39`), says where a browser opens the socket instead: at the
service's own hostname, with the token as a subprotocol.

## R18. Where each scenario is tested

| Feature file | Level | Suite |
|---|---|---|
| `sockets/frames.feature` | a whole service on loopback | `SocketFeatures` (`GherkinSuite`), in `testkit`'s tests |
| `sockets/access.feature` | a whole service with the real verifier and the test issuer; the two named-service scenarios over `TlsServing` | `SocketAccessFeatures`, in `sidecar`'s tests; `SocketCallerSuite`, in `http`'s |
| `sockets/traces.feature` | a whole service | `SocketFeatures` |
| `sockets/languages.feature` | conformance, per target | `ConformanceSuite` `socket.*`; the stopped process and both version refusals in `ProtocolSuite` and `RemoteEndpointSuite` with `ProcessDouble`; the module in `WasmHostSuite`; each SDK's own tests |
| `sockets/deployed.feature` | k3s, through the gateway, on the cart's open `/callers/socket` | `ExposureClusterSuite`, which has the gateway, the control plane and the real cart; the browser's-token scenario in `SocketCallerSuite` with the gateway's certificate; the topology scenario in the fast topology features |
| `local-console/sockets.feature` | the service document | `ObservabilityDocuments` and console tests |
| `documentation/sockets.feature` | the docs build | `docs check`, includes and coverage |

The client is `TestSocket`, a small blocking wrapper over the JDK's `java.net.http.WebSocket`
added to `testkit`: it offers subprotocols, takes an `SSLContext` and reports the close code.
Where the suites live follows from who can see what. `http` sits below `testkit`, so the server's
own behaviour is tested from `testkit`'s tests, as `HttpSseSuite` is; only what needs `http`'s
private `TlsServing` stays in `http`'s tests and uses the JDK client directly
(`RotatingTls(dir).sslContext`, as `TlsServerSuite.scala:64-78` builds one). The real token
verifier and `TestIssuer` are `auth-oidc`'s, which `testkit` cannot see and `sidecar` can
(`build.sbt:562`), so `access.feature` is run from `sidecar`'s tests against an embedded service,
beside the conformance reference, which is one already. From the host through the gateway the JDK client needs the hostname to resolve, and
`-Djdk.net.hosts.file` is JVM-wide, so the cluster cases run a small probe `main` as a
subprocess, as `ControlPlaneClusterSuite` runs the CLI. The ten idle minutes are not slept: the
socket is opened when the suite starts and asserted in its last case, which waits only for what
remains of the ten minutes.

No suite has an issuer, the gateway and a service with an authenticated socket route at once.
`SidecarClusterSuite` has an issuer in the cluster and no gateway; `ExposureClusterSuite` has the
gateway and a cart that is `Acl.AllowAll` and does not depend on `auth-oidc`, and an authenticated
route on the sample would stop it starting on a laptop with no issuer listed. So the cluster
proves what only a cluster can — that the gateway carries the upgrade, the frames and the
subprotocols a browser offers, read from which subprotocol the 101 selects — and the token with
the gateway as the caller is proved on loopback against a server that is shown the gateway's
certificate.

**Could each pass while false?** The close-code cases assert the number, not "closed". The
keep-alive case has its negative twin (R6). The "no platform threads" case counts threads before
and after and first proves the count moves when handlers are deliberately pinned. The idle case
asserts a frame crosses *after* the wait. The version refusals assert the message names the
route and the version. The conformance filter needs its leading wildcard: `'*socket.*'`.

## R19. The sample and the documentation

**Decision**: the shopping cart sample's endpoint gains one socket route in each language's
example (`/carts/{cartId}/watch`: the client sends "refresh" and is sent the cart), marked as a
docs region and exercised by a test in each language. The Scala sample's `CallersEndpoint`, which
exists for the platform's suites, gains `/callers/socket`, answering each frame with who is
calling; that is what the cluster cases open. `docs/build/http-endpoints.md` gains a section on sockets with the three includes;
`limitations.md`, `configuration.md` (generated table plus prose for three variables),
`sidecar-protocol.md` (generated table plus the versioning prose for 1.9), the three SDK
reference pages, `wasm-abi.md`, `web-hosting.md` and `docs/reference/glossary.md` change. No new
page, so `mkdocs.yml`'s nav and the skills' `pages:` lists are untouched; the skills are rendered
again. `CLAUDE.md` gains the traps this feature found and the protocol's number.

## R20. Not done here

Binary frames; a pong deadline; a socket opened by a service through its service client; a
socket through a mount; frames recorded one by one; a routes listing on `ankka services get`;
anything the platform does with a socket beyond carrying it.

## Verify first, in order

1. The close-code shim (R4) — a spike in `modules/http`'s tests, before any route code.
2. The keep-alive against pekko-http's own idle timeout (R6).
3. `unbind`, close, then `terminate` (R8).
4. Subprotocol behaviour of three clients (R7).
5. grpc-java readiness from a virtual thread (R13).
6. The gateway (R17) — the only one that needs a cluster.

### Results

Run with `sbt -Dankka.spikes=on 'http/testOnly *SocketCloseCodeSpike'` on 2026-10-04.

- **V1, confirmed.** The JDK client read exactly 1000, 1001, 1003, 1008, 1009 and 1011, each with
  the reason text the server chose; a client's own close of an echo was answered with the
  client's code. The upgrade attribute is the low-level class. The shim compiles in pekko's
  package under Scala 3 with `-Wunused` clean.
- **V2, confirmed, with one correction to R6.** A 1-second keep-alive held a silent socket past a
  3-second idle timeout and a frame then crossed; without it the idle timeout ended the socket and
  the client saw a `SocketException`, not a close — *cut off*, as the glossary has it. The
  correction: because the shim builds pekko's message stack itself, the keep-alive is the
  `WebSocketSettings` passed to the shim, not the server binding's. `HttpServer` derives them from
  `ServerSettings(system).websocketSettings` with ankka's interval; nothing is set on the binding.
- **V4, for the JDK client.** Offering only `ankka.bearer.x` and having none selected, the client
  opened the socket; offering `ankka.socket` too, it reported `ankka.socket` selected. Node's
  client is recorded by T041.
