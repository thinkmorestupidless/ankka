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
which is exactly the protocol a WebSocket upgrade rides on, so nothing at the gateway stands in
the way.

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

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Scala endpoint holds a socket (Priority: P1)

A developer building a notifications service declares `websocket("/stream")` on an endpoint whose
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

1. **Given** an endpoint with a socket route under an access control list that allows everyone,
   **When** a client opens the socket and sends ten text frames, **Then** the handler receives the
   ten frames in order through `receive()` and its ten replies through `send(text)` reach the
   client in order.
2. **Given** a socket route under `Acl.Authenticate`, **When** a client opens it with a valid
   token, **Then** the handler reads the principal the token established, and reads it unchanged
   on every frame for the life of the connection.
3. **Given** the same route, **When** a client opens it with no token, **Then** the upgrade is
   answered 401 with the challenge header a request route would send, and no handler runs.
4. **Given** the same route, **When** a client opens it with a token the list forbids, **Then**
   the upgrade is answered 403 and no handler runs.
5. **Given** a socket route under `allowCallers`, **When** another service in the cluster opens
   it with its certificate, **Then** the handler reads that service as the caller, and a service
   the matcher does not name is refused at the upgrade.
6. **Given** an open socket, **When** the client closes it, **Then** `receive()` reports the
   close to the handler rather than blocking forever, and the handler returns.
7. **Given** an open socket, **When** the handler returns while the client is still connected,
   **Then** the runtime sends a close frame and the client sees an orderly close, not a reset.
8. **Given** an open socket whose handler is blocked in `receive()`, **When** the handler throws,
   **Then** the connection is closed with a close frame carrying an error code, and the failure is
   recorded as a failed invocation on the route's span.
9. **Given** a socket handler, **When** it calls an entity through the component client between
   frames, **Then** the call is made on the handler's virtual thread and attributed to the route's
   trace.
10. **Given** a thousand open sockets whose handlers are all blocked in `receive()`, **When** the
    JVM's platform thread count is read, **Then** it has not grown with the sockets.

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

1. **Given** a Python endpoint whose discovery declares a socket route, **When** a client opens
   it through the sidecar and sends frames, **Then** the handler receives each frame as a message
   on the `HandleSocket` stream and its sends reach the client, in order both ways.
2. **Given** a socket route declared `AUTHENTICATED` or with caller matchers, **When** a client
   opens it, **Then** the sidecar decides the list at the upgrade exactly as it does for a
   request route, and the handler receives the principal and the caller with the first message.
3. **Given** an open socket, **When** the process closes its side of the `HandleSocket` stream,
   **Then** the sidecar sends a close frame to the client.
4. **Given** an open socket, **When** the process is killed, **Then** every open socket is closed
   with a close frame carrying an error code, and a client that reconnects after the process
   returns is served.
5. **Given** a process speaking a protocol minor version that predates `HandleSocket`, **When**
   its discovery declares no socket route, **Then** it is hosted as before; one that declares a
   socket route under the old version is refused at discovery naming the version.
6. **Given** the TypeScript SDK, **When** the same route is declared, **Then** the same five
   scenarios hold through the same sidecar.
7. **Given** a Rust module whose discovery declares a socket route, **When** the runtime loads
   it, **Then** discovery is refused naming the route and saying a module cannot stream, as for a
   streaming route today.

---

### User Story 3 - A socket crosses the gateway and survives operations (Priority: P2)

An operator exposes the service. A browser on the internet opens the socket at the service's
hostname through the gateway with the person's token, and holds it for an hour. A rolling restart
replaces the instance under the connection; the browser sees an orderly close, reconnects, and is
served by the surviving instance.

**Why this priority**: A socket that works on loopback and fails through the gateway or across a
deploy is a feature that works in tests only.

**Independent Test**: In the k3s suite, expose a service with a socket route, open a socket from
the host through the gateway with `--resolve` and the local CA, exchange frames, then restart the
service and assert on the close.

**Acceptance Scenarios**:

1. **Given** an exposed service with a socket route, **When** a client on the internet opens the
   socket at the service's hostname through the gateway, **Then** the upgrade succeeds, the
   handler sees the gateway as the caller and the token's principal, and frames flow both ways.
2. **Given** an open socket through the gateway, **When** no frame is sent for ten minutes,
   **Then** the connection is still open and a frame sent then is delivered.
3. **Given** an open socket on an instance, **When** `ankka services restart` replaces that
   instance, **Then** the client receives a close frame, not a reset, and a reconnection made at
   once is served by an instance that is ready.
4. **Given** a socket route on a service that is not exposed, **When** a client on the internet
   tries it, **Then** there is nothing to connect to, as for every route of an unexposed service.
5. **Given** a service with a socket route, **When** `ankka services get` and the local console
   show the service, **Then** the route is listed as a socket route beside the request routes.

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

1. **Given** the HTTP endpoints guide, **When** a reader looks for sockets, **Then** it shows a
   socket route in Scala, Python and TypeScript, each included from tested code, and states that
   the access control list is decided at the upgrade.
2. **Given** the limitations page, **When** a reader looks for sockets, **Then** it says that
   frames are text only, that a module cannot declare one, and that the platform carries the
   connection and keeps no presence or message store.
3. **Given** the sidecar protocol reference, **When** a reader looks up `HandleSocket`, **Then**
   it is described with the minor version that introduced it.

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
  SIGTERM. Whether a socket is closed at once or held through the sleep is an open question;
  either way the close is a close frame.
- **A principal whose token expires mid-connection.** The connection is not re-authenticated; the
  decision was made at the upgrade. A service that must enforce expiry reads the principal's
  expiry and closes the socket itself.
- **A socket route and a request route on one template.** Refused at registration, as two routes
  on one template are.
- **A socket opened by a service through its service client.** Out of scope: the service client
  makes requests, not connections.
- **Binary frames.** Refused with a close frame carrying the unsupported-data code; a later
  feature.

## Requirements *(mandatory)*

### Functional Requirements

**Declaration**

- **FR-001**: An endpoint MUST be able to declare a socket route on a path template, in the same
  place and under the same prefix as its request and SSE routes.
- **FR-002**: The socket route MUST be subject to the endpoint's access control list, including
  one set with `withAcl`, decided on the upgrade request and answered with the same statuses and
  headers a request route uses.
- **FR-003**: The handler MUST receive a socket value with a blocking `receive()` that returns the
  next text frame or reports the close, and a `send(text)` that writes a text frame and throws
  once the connection is closed.
- **FR-004**: The handler MUST run on a virtual thread for the life of the connection, and MUST
  see the caller and the principal established at the upgrade through the same accessors a
  request route uses.
- **FR-005**: When the handler returns, the runtime MUST send a close frame; when it throws, the
  runtime MUST send a close frame with an error code and record a failed invocation on the route.

**Hosting**

- **FR-006**: The sidecar protocol's `Http` service MUST gain `HandleSocket`, a bidirectional
  stream of frames, under a minor version bump; a process declaring a socket route under an older
  minor version MUST be refused at discovery.
- **FR-007**: The sidecar MUST decide the route's access control list at the upgrade and relay the
  principal and caller to the process with the first message.
- **FR-008**: The Python and TypeScript SDKs MUST expose a socket route as an asynchronous context
  that reads and writes frames.
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
  a reset. Whether that happens before or after the `preStop` sleep is [NEEDS CLARIFICATION: close
  at once so clients reconnect to a ready instance, or serve through the sleep].
- **FR-014**: The route MUST appear in the service's listed routes, in the CLI and the local
  console, as a socket route.

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
  before and after.
- **SC-004**: A socket opened through the gateway in the k3s suite survives a ten-minute idle
  period and is closed with a close frame on a restart of the service.
- **SC-005**: A Rust module declaring a socket route fails to start with a message naming the
  route.

## Assumptions

- The gateway, Envoy Gateway over HTTP/1.1, passes a WebSocket upgrade with its default
  configuration and no change to the rendered `HTTPRoute`.
- pekko-http's WebSocket support is sufficient for text frames and close codes without an added
  dependency.
- A socket's handler is one invocation of its route for recording purposes; frames are not
  recorded individually.
- The protocol's minor version bump for `HandleSocket` is accepted by the platform's existing
  rule: same major, a minor no higher than its own.

## Dependencies

- None of the other core features. The access control list decision reuses 022-service-identity
  when the list authenticates, but a socket route under `allowAll` or `allowCallers` needs nothing
  from it.
- Gates stage 4 of the domain plan: the engagement service's push connection and presence.

## Open Questions

- Binary frames: refused now, or carried as a second frame kind from the start so the protocol
  needs one minor bump rather than two?
- Should an instance close its sockets as soon as it leaves the Service's endpoints, so clients
  reconnect to a ready instance, or serve them through the `preStop` sleep and close at SIGTERM?
- Should a socket's handler be told the gateway's hostname the browser used, as a web-hosted
  service's process is, for a service that serves several brands on one route?
