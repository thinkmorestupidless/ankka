# Contract: the Scala API

Everything here is in `ankka-http` unless it says otherwise.

## Declaring

```scala
abstract class HttpEndpoint(val prefix: String):
  protected def socket(template: String)(handler: Socket => Unit): Unit
  protected def socket[A: FromPath](template: String)(handler: (A, Socket) => Unit): Unit
  protected def socket[A: FromPath, B: FromPath](template: String)(handler: (A, B, Socket) => Unit): Unit
```

- Declared in the constructor body like every route; `withAcl` scopes it like every route.
- A handler whose arity does not match the template throws at construction.
- A socket route on the template of a `GET` route or an `sse` route of the same endpoint fails
  startup: `'<prefix>' declares GET <template> 2 times`.

## The handler's socket

```scala
trait Socket:
  /** The next frame, waiting for one. `None` once the socket is closed, by anyone, and for ever after. */
  def receive(): Option[String]

  /** Sends a frame, waiting while the client is not reading. Throws `SocketClosed` once closed. */
  def send(text: String): Unit

final class SocketClosed(val reason: String) extends RuntimeException
```

- The handler runs on a virtual thread from the upgrade until it returns. `request`, `caller`,
  `principal` and `query` answer for the opening request for all of that time, on that thread.
- Returning closes the socket "finished". Throwing closes it "failed" and is logged as an
  unhandled failure of the route. A `SocketClosed` that escapes is a return, not a failure.
- `send` from another thread is allowed and ordered; `receive` is for the handler's thread.
- A `CommandError` or `HttpProblem` thrown by the handler is a failure like any other: there is
  no response left to carry a status.

## The opening request

| Situation | Answer |
|---|---|
| the ACL refuses | exactly what a request route answers: 401 with `WWW-Authenticate`, 403, or 503 with `Retry-After`; no handler runs |
| admitted, not an upgrade | 426 with `Upgrade: websocket` |
| admitted, an upgrade | 101; `Sec-WebSocket-Protocol: ankka.socket` if and only if the client offered it |

A token is read from `Authorization`; with no such header, from an offered subprotocol
`ankka.bearer.<token>`. The handler and the ACL see `Authorization: Bearer <token>` either way
and never see `Sec-WebSocket-Protocol`.

## Configuration

`ankka.http.socket.max-frame-size`, `.unread-frames`, `.keep-alive`, with the variables and
defaults in [data-model.md](../data-model.md).

## Listing

`ServedRoute("SOCKET", "<prefix><template>", streaming = true, "<endpoint id>")` in
`RuntimeExtension.routes`.

## Runtime (`ankka-runtime`)

```scala
final class Recorder:
  def reserve(): Long
  def record(traceId: Long, spanId: Long, parentSpanId: Long, componentRef: Int,
             handlerRef: Int, startedNanos: Long, outcome: SpanOutcome): Unit
```

`PlatformVariables.RuntimeOnlyPrefixes` gains `"ANKKA_SOCKET_"` (in `core`, compiled into the
operator too).

## Test kit (`ankka-testkit`)

```scala
final class TestSocket:                      // over java.net.http.WebSocket
  def send(text: String): Unit
  def receive(within: FiniteDuration = 5.seconds): Option[String]   // None once closed
  def close(): Unit
  def closed(within: FiniteDuration = 5.seconds): TestSocket.Closed // code and reason; fails if cut off

object TestSocket:
  def open(url: String, headers: Map[String, String] = Map.empty,
           subprotocols: Seq[String] = Nil, tls: Option[SSLContext] = None): Either[Refused, TestSocket]
  final case class Refused(status: Int, headers: Map[String, String])
  final case class Closed(code: Int, reason: String)
```

`AnkkaTestKit` gains `socket(path, …)`, which opens one against the service's bound address.
