# Feature Specification: WebSocket Routes — A Long-Lived Connection on an Endpoint

**Feature Branch**: `028-websocket-routes`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "An HTTP endpoint can declare a WebSocket route beside its request
routes and its server-sent event routes. The handler runs for the life of the connection on a
virtual thread, reads and writes frames with blocking calls, and sees the caller and the principal
as any route does. The endpoint's access control list is decided at the upgrade request, so a
connection nobody may open is refused before it exists. The same route is available to a Python
or TypeScript service through the sidecar, and refused at discovery for a WebAssembly module,
which cannot stream. The gateway passes an upgrade without a change. Out of scope: binary frames,
HTTP/2 and gRPC to a service, streaming request bodies, streaming entity or workflow handlers, and
anything the platform does with the connection beyond carrying it, such as presence, which is a
service's own key value entity and timer."

## Context

An endpoint today has request routes and streaming routes. `HttpEndpoint` in `modules/http`
declares `get`, `post`, `put`, `delete` and `patch`, their body variants, and `sse` and `sseBody`,
whose handler returns a source of strings that `HttpServer` sends as server-sent events, each
frame's data JSON-encoded so a leading space or a newline in a token cannot corrupt it. Server-sent
events are not limited to agents: a Python endpoint streams an async generator through `@sse`, a
TypeScript endpoint streams an iterator, and the autonomous agent's notification stream is served
that way. "Only agents stream" is a statement about component handlers, not routes: only an agent
handler may be declared streaming and invoked through `InvokeStream`, and an entity or workflow
refuses one.

What an endpoint cannot do is hold a connection the client also writes to. There is no WebSocket
in `modules/http`, none in the sidecar, and the protocol's `Http` service in `endpoint.proto` has
`Handle` for a request and `HandleStream` for a one-way stream of frames, nothing with an inbound
side. pekko-http supports the upgrade; nothing in ankka asks it to. The gateway is HTTP/1.1 only,
which is exactly the protocol a WebSocket upgrade rides on, so nothing the operator renders is
expected to change; the cluster suite is what proves it.

A service that needs a long-lived connection in both directions, a push channel with a heartbeat
coming back, a live editor, a game, has today two bad choices: an SSE route for the server's half
and a request route the client polls or posts to for its own, or a second process outside the
platform. The first doubles every connection and loses the one thing a socket gives, a single
ordered channel; the second loses the platform.

Four decisions shape this feature.

- **A socket is a route.** It is declared on an endpoint beside the others, under the same prefix,
  and the endpoint's access control list applies to it. The decision is made on the upgrade
  request, which is the one HTTP request a socket has: a caller the list refuses never gets a
  connection, and gets the same 401 or 403 a request route would give. The caller and the
  principal the upgrade established are what the handler sees for the connection's whole life.
- **The handler is ordinary blocking code.** It receives a socket with a blocking `receive()` and
  a `send(text)` and runs on a virtual thread for as long as the connection lasts. That is what
  makes a thousand idle sockets cost a thousand parked virtual threads and no carrier, the same
  property that makes `invoke` free in a workflow step. The handler returns when it is done or
  when the client has gone, and the runtime closes what is left.
- **The sidecar carries it as a bidirectional stream.** The protocol's `Http` service gains
  `HandleSocket`, a stream of frames in each direction whose shape mirrors `HandleStream`'s with an
  inbound side, so a Python or TypeScript handler is an async context that awaits frames and sends
  them. A module cannot stream, so a module declaring a socket route is refused at discovery, as a
  module declaring a streaming route is today.
- **The platform carries the connection and nothing else.** No presence, no fan-out, no
  reconnection, no message store. Presence is a key value entity and a timer in the service that
  wants it; fan-out is the service's consumer. A socket is a transport the handler owns.

What this feature is not: it is not binary frames, which are a later addition when a service
needs them; it is not HTTP/2 or gRPC to a service through the gateway; it is not a streaming
request body; and it is not a change to which component handlers may stream.

## Clarifications

### Session 2026-10-04

- Q: When an instance is replaced, when are its open sockets closed? → A: At SIGTERM. The instance
  serves its sockets through the `preStop` sleep and closes every one, with the close reason "going
  away", as shutdown begins; by then it has left the Service's endpoints, so a socket opened again
  reaches an instance that is ready.
- Q: Who keeps an idle socket open through the gateway, which ends a stream with no activity?
  → A: The platform. The runtime sends a keep-alive ping on every socket that has been quiet for a
  configured interval; the handler never sees it. The rendered route changes only if the k3s run
  shows the route's own timeout cuts an upgraded connection.
- Q: Binary frames: refused now, or carried from the start? → A: Text only now. A binary frame
  closes the socket with the unsupported-data code. `HandleSocket`'s frame message is a oneof with
  the one text case, so binary is one added case under a later minor version.
- Q: How does a browser, whose WebSocket API cannot set an `Authorization` header, present its
  token when opening a socket? → A: In the subprotocol. An authenticated socket route reads the
  token from a subprotocol the client offers, `ankka.bearer.<token>`, as well as from the
  `Authorization` header, which other clients go on using. The token never appears in a URL, and
  the answer to the upgrade never repeats it.
- Q: Should a socket's handler be told the hostname the browser used? → A: No new accessor. The
  handler reads the opening request's path parameters, query parameters and headers for the life
  of the socket, as a route's handler reads its request's. A service serving several brands on one
  route waits for custom domains, a feature of its own.
- Q: Are the seven proposed glossary terms the right words? → A: Yes, all seven: socket, socket
  route, frame, closed, close reason (finished, failed, too large, unread, not text, going away),
  cut off (a socket ended without the other side being told; "reset" in the requirements) and
  thread.

### Amended in planning, 2026-10-04

- FR-014 and its scenario said the route is listed by `ankka services get`. That command lists no
  routes of any kind; a deployed service's routes are read with `ankka services topology` and on
  the console's topology page. The socket route is listed there and in the local console
  (research R10).
- FR-005, FR-012 and FR-013 name close codes that pekko-http's public WebSocket API cannot send:
  it closes 1000 when a handler's stream completes and 1011 when it fails. The codes are sent
  through one file over pekko-http's internal frame-level upgrade, held by a suite that asserts
  each code on the wire (research R4). The assumption that said otherwise is corrected.
- FR-016's interval has a second reason: pekko-http itself ends a connection that has been idle
  for sixty seconds, so without the keep-alive an idle socket is cut off on a developer's machine
  too, gateway or no gateway (research R6).
- FR-015 is recorded when the socket closes, with its start as when it was opened: the recorder
  drops a span that is still open once enough newer spans have been recorded, so a socket held
  for an hour would otherwise never be recorded at all. While a socket is open, the calls its
  handler makes are in a trace whose root has not been recorded yet (research R9).
- FR-006's refusal is checked from both ends: the runtime refuses a process that declares a
  socket route under a minor version older than the one that introduced it, and an SDK refuses to
  declare one to a runtime that states an older version at discovery (research R12).
- FR-018 was added: the three limits are platform settings a descriptor may give, because a
  process-hosted service has no configuration file of the runtime's to put them in (research R5).
- An edge case was added: a socket route cannot be reached under a mount of a web-hosted service,
  whose proxy does not pass an upgrade (research R17).
- User Story 3's first scenario became two. No suite has an issuer, the gateway and a service
  with an authenticated socket route at once, and the cart sample is open to everyone by design:
  the cluster proves the gateway carries a socket and the subprotocols a browser offers, and the
  token and the gateway as caller together are proved against a server holding the gateway's
  certificate on loopback (research R18).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Scala endpoint holds a socket (Priority: P1)

A developer building a notifications service declares `socket("/stream")` on an endpoint whose
access control list authenticates the caller. The handler reads the principal, subscribes to what
that person should see, and loops: it sends each notice as it arrives and reads the client's
acknowledgements as they come. When the client closes the connection the loop ends and the
handler returns. A client without a token is refused at the upgrade with the same 401 and
challenge a request route gives.

**Why this priority**: This is the capability; every other story is a way of hosting, routing or
documenting it.

**Independent Test**: With a test service bound to loopback on an ephemeral port, open a socket
with a WebSocket client, send and receive frames, close, and assert on what the handler saw;
repeat without credentials and assert the upgrade is refused.

**Acceptance Scenarios**:

- added `features/sockets/frames.feature`: frames cross a socket both ways, each in the order it was sent
- added `features/sockets/access.feature`: the handler of an authenticated socket route is told the principal of whoever opened the socket
- added `features/sockets/access.feature`: a browser opens a socket to an authenticated socket route with a token
- added `features/sockets/access.feature`: a handler reads the same principal for as long as the socket is open
- added `features/sockets/access.feature`: a socket stays open after the token it was opened with has expired
- added `features/sockets/access.feature`: a request to open a socket with no token is challenged as a request to any authenticated route is
- added `features/sockets/access.feature`: a request to open a socket that the ACL forbids is refused, and no handler runs
- added `features/sockets/access.feature`: a socket route that admits a named service tells its handler the calling workload
- added `features/sockets/access.feature`: a socket route that admits a named service refuses any other service
- added `features/sockets/frames.feature`: a handler reads what the request that opened the socket carried for as long as the socket is open
- added `features/sockets/frames.feature`: a handler waiting for a frame is told when whoever opened the socket closes it
- added `features/sockets/frames.feature`: a socket is closed when its handler finishes
- added `features/sockets/frames.feature`: a socket whose handler fails is closed as failed, and recorded as failed
- added `features/sockets/frames.feature`: a handler that sends a frame over a closed socket is told that the socket is closed
- added `features/sockets/frames.feature`: a frame larger than a frame may be closes the socket
- added `features/sockets/frames.feature`: a socket holds only so many frames its handler has not read
- added `features/sockets/frames.feature`: a frame that is not text closes the socket
- added `features/sockets/frames.feature`: a service with a socket route and a route on the same path does not start
- added `features/sockets/frames.feature`: the platform keeps a quiet socket open, and neither side is given a frame for it
- added `features/sockets/traces.feature`: a socket is the root of the trace of everything its handler caused
- added `features/sockets/traces.feature`: a socket is one root in the trace however many frames cross it
- added `features/sockets/traces.feature`: a socket closed by whoever opened it is recorded as ok
- added `features/sockets/frames.feature`: a socket whose handler is waiting for a frame holds no thread

---

### User Story 2 - A Python or TypeScript endpoint holds a socket (Priority: P1)

A developer writing the same service in Python declares the socket route as an async context:
`async for frame in socket` reads and `await socket.send(text)` writes. The sidecar holds the
connection, decides the access control list at the upgrade from the route's declaration, and
relays frames both ways over the protocol. The developer's process never sees the connection.

**Why this priority**: A route that exists in Scala alone is a limitation the day it ships; the
sidecar is where every hosted language meets the platform, and the protocol change is the larger
part of the work.

**Independent Test**: The conformance suite gains cases for a socket route, run against the Scala
reference in-process and against the Python and TypeScript targets through a sidecar: open, send,
receive, close, refuse.

**Acceptance Scenarios**:

- added `features/sockets/languages.feature`: frames cross a socket both ways in every language
- added `features/sockets/languages.feature`: the handler of an authenticated socket route is told the principal in every language
- added `features/sockets/languages.feature`: a request to open a socket with no token is challenged in every language
- added `features/sockets/languages.feature`: a socket is closed when its handler finishes in every language
- added `features/sockets/languages.feature`: the sockets of a process that stops are closed as failed
- added `features/sockets/languages.feature`: a socket is opened again once a process that stopped is running again
- added `features/sockets/languages.feature`: a service made for a protocol version older than socket routes is hosted as it was
- added `features/sockets/languages.feature`: a service that declares a socket route for a protocol version older than socket routes does not start
- added `features/sockets/languages.feature`: a service does not declare a socket route to a platform older than socket routes
- added `features/sockets/languages.feature`: a module that declares a socket route does not start

---

### User Story 3 - A socket crosses the gateway and survives operations (Priority: P2)

An operator exposes the service. A browser on the internet opens the socket at the service's
hostname through the gateway with the person's token, and holds it for an hour. A rolling restart
replaces the instance under the connection; the browser sees an orderly close, reconnects, and is
served by the surviving instance.

**Why this priority**: A socket that works on loopback and fails through the gateway or across a
deploy is a feature that works in tests only.

**Independent Test**: In the k3s suite, expose a service with a socket route, open a socket from
the host through the gateway at the service's hostname with the local CA, exchange frames, then
restart the service and assert on the close code.

**Acceptance Scenarios**:

- added `features/sockets/deployed.feature`: a socket to an exposed service is opened at its hostname, and its calling workload is the gateway
- added `features/sockets/deployed.feature`: a browser's token opens an authenticated socket route at an exposed service's hostname
- added `features/sockets/deployed.feature`: a socket that no frame crosses for ten minutes is still open
- added `features/sockets/deployed.feature`: a socket on an instance that is replaced is closed, not cut off
- added `features/sockets/deployed.feature`: a socket opened while a service's instances are replaced is served by an instance that is ready
- added `features/sockets/deployed.feature`: a socket route of a service that is not exposed opens no socket from the internet
- added `features/sockets/deployed.feature`: the topology of a service shows its socket routes
- added `features/local-console/sockets.feature`: the local console lists a service's socket routes beside its routes

---

### User Story 4 - The route is documented and its limits stated (Priority: P3)

A developer reads the HTTP endpoints guide and finds the socket route beside the SSE route, with a
sample from tested code in each language, the statement that presence and fan-out are the
service's own, and the limitations page saying what a socket does not yet carry.

**Why this priority**: The feature is usable without it by someone reading the source, and by
nobody else.

**Independent Test**: The documentation build passes with the new sections; every sample is an
included region of tested code.

**Acceptance Scenarios**:

- added `features/documentation/sockets.feature`: the documentation describes a socket route in every language that has one
- added `features/documentation/sockets.feature`: the documentation says what a socket does not do
- added `features/documentation/sockets.feature`: the documentation states the limits of a socket
- added `features/documentation/sockets.feature`: the documentation of how the platform talks to a process describes sockets

---

### Edge Cases

- **A frame larger than the server accepts.** The connection is closed with a close frame
  carrying the size error; the limit is the runtime's and is stated in the configuration
  reference.
- **A handler that never calls `receive()`.** Inbound frames are buffered up to a bounded size,
  after which the connection is closed; a handler that only sends must still drain.
- **A `send` after the client has closed.** Throws, so a handler loop ends; it is not a silent
  drop.
- **The `preStop` sleep.** A pod leaving its Service's endpoints serves for five seconds before
  SIGTERM, and its sockets are served through that sleep. At SIGTERM every open socket is closed
  with a close frame carrying the going-away code, before the HTTP port is unbound.
- **A principal whose token expires mid-connection.** The connection is not re-authenticated; the
  decision was made at the upgrade. A service that must enforce expiry reads the principal's
  expiry and closes the socket itself.
- **A token offered both ways.** The `Authorization` header is the one read; a subprotocol is
  read only when there is no header, so the two can never disagree about who the caller is.
- **A socket route and a request route on one template.** Refused at registration, as two routes
  on one template are.
- **A socket route under a mount.** A web-hosted service's proxy passes a request on without its
  upgrade, so a socket route of a mounted service is not reached through the mount; a browser
  opens the socket at the mounted service's own hostname. The limitation is stated.
- **A client that vanishes without closing.** The keep-alive is sent and not answered for; the
  socket ends when the connection's own failure is noticed, and the handler is told it is closed.
- **A socket opened by a service through its service client.** Out of scope: the service client
  makes requests, not connections.
- **Binary frames.** Refused with a close frame carrying the unsupported-data code; a later
  feature, which adds a case to the protocol's frame message under a minor version of its own.

## Requirements *(mandatory)*

### Functional Requirements

**Declaration**

- **FR-001**: An endpoint MUST be able to declare a socket route on a path template, in the same
  place and under the same prefix as its request and SSE routes.
- **FR-002**: The socket route MUST be subject to the endpoint's access control list, including
  one set with `withAcl`, decided on the upgrade request and answered with the same statuses and
  headers a request route uses.
- **FR-017**: A socket route whose access control list authenticates MUST read the token from the
  `Authorization` header or, when there is none, from a subprotocol the client offers of the form
  `ankka.bearer.<token>`, in every hosted language; the answer to the upgrade MUST NOT repeat the
  token, and the token MUST NOT be recorded in a trace or a log.
- **FR-003**: The handler MUST receive a socket value with a blocking `receive()` that returns the
  next text frame or reports the close, and a `send(text)` that writes a text frame and throws
  once the connection is closed.
- **FR-004**: The handler MUST run on a virtual thread for the life of the connection, and MUST
  see the caller and the principal established at the upgrade, and the upgrade request's path
  parameters, query parameters and headers, through the same accessors a request route uses. It is
  told nothing else of where the request was sent.
- **FR-005**: When the handler returns, the runtime MUST send a close frame; when it throws, the
  runtime MUST send a close frame with an error code and record a failed invocation on the route.

**Hosting**

- **FR-006**: The sidecar protocol's `Http` service MUST gain `HandleSocket`, a bidirectional
  stream of frames, under a minor version bump, whose frame message is a oneof with the one text
  case so that a later frame kind is an added case; a process declaring a socket route under an older
  minor version MUST be refused at discovery.
- **FR-007**: The sidecar MUST decide the route's access control list at the upgrade and relay the
  principal and caller to the process with the first message.
- **FR-008**: The Python and TypeScript SDKs MUST expose a socket route as a handler given a
  socket: an asynchronous iterator of frames with an awaited send.
- **FR-009**: A WebAssembly module declaring a socket route MUST be refused at discovery, naming
  the route.
- **FR-010**: When a process stops, the sidecar MUST close every open socket with a close frame
  carrying an error code.

**Transport**

- **FR-011**: A socket route on an exposed service MUST be reachable through the gateway at the
  service's hostname with no change to the route the operator renders.
- **FR-012**: The runtime MUST bound the size of a frame and the number of inbound frames
  buffered for a handler that has not received them, and close the connection with the relevant
  close code when either is exceeded; both bounds MUST appear in the configuration reference.
- **FR-013**: A rolling replacement of an instance MUST close its sockets with a close frame, not
  a reset: the instance serves its sockets through the `preStop` sleep and, at SIGTERM, closes
  each with the going-away close code as shutdown begins.
- **FR-016**: The runtime MUST send a keep-alive ping on a socket no frame has crossed for a
  configured interval, shorter than the gateway's idle limit, so that an idle socket stays open
  through the gateway; a ping MUST NOT reach the handler or count as a frame, and the interval
  MUST appear in the configuration reference.
- **FR-014**: The route MUST appear in the service's listed routes as a socket route: in the local
  console, in `ankka services topology` and on the console's topology page.
- **FR-018**: The frame bound, the unread-frame bound and the keep-alive interval MUST be settable
  by a service's own configuration and by `ANKKA_SOCKET_`-prefixed variables a descriptor may
  give, which go to the platform's program and never to a process.

**Observability**

- **FR-015**: A socket connection MUST be recorded as one invocation of its route from the upgrade
  to the close, with its outcome, and calls the handler makes between frames MUST be attributed
  to it.

### Key Entities

- **Socket route**: a declared path template on an endpoint, with the access control list that
  applies, served by the WebSocket upgrade.
- **Socket**: the handler's handle on one connection: receive, send, the caller and the principal
  established at the upgrade, and the close.
- **Frame**: a text message in either direction; a close carries a code.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client exchanges ten frames each way with a Scala, a Python and a TypeScript
  service over one socket, in order, in the conformance suite.
- **SC-002**: An upgrade without credentials on an authenticated socket route is refused with 401
  before any handler runs, in every hosted language.
- **SC-003**: A thousand idle sockets on one instance add no platform threads to the JVM, measured
  before and after: a difference of at most eight, where a thousand parked platform threads
  measured the same way add about a thousand.
- **SC-004**: A socket opened through the gateway in the k3s suite survives a ten-minute idle
  period and is closed with a close frame on a restart of the service.
- **SC-005**: A Rust module declaring a socket route fails to start with a message naming the
  route.

## Assumptions

- The gateway, Envoy Gateway over HTTP/1.1, passes a WebSocket upgrade with its default
  configuration, and the runtime's keep-alive pings count as activity against its idle limit. No
  change to the rendered `HTTPRoute` is expected; the k3s suite is what proves it, and a route
  timeout that cuts an upgraded connection would be a change to the HTTP rule for socket routes.
- pekko-http's WebSocket support is sufficient for text frames without an added dependency. Its
  public API sends two close codes only; the others go through its internal frame-level upgrade
  (see *Amended in planning*).
- A socket's handler is one invocation of its route for recording purposes; frames are not
  recorded individually.
- The protocol's minor version bump for `HandleSocket` is accepted by the platform's existing
  rule: same major, a minor no higher than its own.

## Dependencies

- None of the other core features. The access control list decision reuses 022-service-identity
  when the list authenticates, but a socket route under `allowAll` or `allowCallers` needs nothing
  from it.
- Gates stage 4 of the domain plan: the engagement service's push connection and presence.
