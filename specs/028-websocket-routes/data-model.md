# Data Model: Socket Routes

Nothing is stored. No table, no event, no state, no field on the `AnkkaService` resource and no
wire type of the control plane changes. What this feature adds is declared values, one runtime
object per open socket, three settings and four protocol messages.

## Socket route

A declared value on an endpoint, beside its routes and its streaming routes.

| Field | Meaning | Rule |
|---|---|---|
| template | the path under the endpoint's prefix, with named parameters | parsed by `PathTemplate`; the handler's arity must match, checked at construction |
| acl | the route's own ACL, or none | none means the endpoint's; set by `withAcl` |
| run | the handler | given the path arguments and a `Socket`; returns nothing |

**Identity**: `("GET", template)` within its endpoint. A socket route and a `GET` or SSE route on
one template are refused at startup. A `POST` on the same template is another route.

**Described as**: `SOCKET <template>` in a span and a log line; `ServedRoute("SOCKET",
<prefix><template>, streaming = true, <endpoint id>)` to the local console and the topology.

## Socket

One per open connection, held by the server from the upgrade to the close. Never serialized.

| Part | Meaning |
|---|---|
| the request context | caller, principal, path, query and headers of the opening request; fixed for the socket's life |
| inbound | whole text frames waiting for `receive()`; at most `unread-frames` |
| outbound | frames `send` has offered and the client has not yet taken |
| close reason | unset while open; set once, by whoever ends the socket first |
| span | a reserved id, the trace id and the start; recorded once, at the close |

**States**: *open* → *closed*. It becomes closed when the handler returns or throws, when the
client closes or its connection fails, when a limit is exceeded, or when the instance stops.
`receive()` answers `None` from then on and `send` throws `SocketClosed`.

## Close reason

| Reason | Code | Set by |
|---|---|---|
| finished | 1000 | the handler returning; the process completing its stream |
| going away | 1001 | the instance stopping |
| not text | 1003 | a binary frame |
| unread | 1008 | a frame arriving with `unread-frames` already waiting |
| too large | 1009 | a frame over `max-frame-size` |
| failed | 1011 | the handler throwing; the process failing or stopping; a protocol violation |

A client's own close is answered with the client's code, as pekko-http does.

## Frame

Text, whole. Its size is its UTF-8 length in bytes. A ping, a pong and a close are not frames.

## Settings

| Key | Variable | Default | Rule |
|---|---|---|---|
| `ankka.http.socket.max-frame-size` | `ANKKA_SOCKET_MAX_FRAME_SIZE` | 64 KiB | positive; behind a sidecar, at most 3 MiB |
| `ankka.http.socket.unread-frames` | `ANKKA_SOCKET_UNREAD_FRAMES` | 64 | at least 1 |
| `ankka.http.socket.keep-alive` | `ANKKA_SOCKET_KEEP_ALIVE` | 20s | positive, and under pekko-http's idle timeout or startup is refused naming both |

`ANKKA_SOCKET_` is a runtime-only prefix in `PlatformVariables`: a descriptor may give these, a
process is not given them, and a module that asks for one is told it is not set.

## Protocol (1.9)

`Route.socket` (field 8) marks a socket route in discovery. `SocketIn`, `SocketOut`,
`SocketFrame` and `SocketClosed` are in [contracts/protocol.md](contracts/protocol.md).

## Recorder

`Recorder` gains `reserve(): Long` (a span id) and `record(traceId, spanId, parentSpanId,
componentRef, handlerRef, startedNanos, outcome)`, which claims a slot and publishes it at once.
Nothing a reader sees changes shape.
