# Contract: the Scala API of `ankka-grpc`

What a service's code sees. Package `com.thinkmorestupidless.ankka.grpc`. Types named `io.grpc.*`
are grpc-java's; `Acl`, `Caller`, `Principal` and `EndpointClients` are `ankka-http`'s, unchanged.
Decisions are in [research.md](../research.md) (R3, R5–R7, R13–R15).

## Declaring an endpoint

```scala
abstract class GrpcEndpoint(val service: io.grpc.ServiceDescriptor):

  /** Who may call this endpoint. Abstract: there is no default. */
  def acl: Acl

  /** Methods declared inside answer to `acl` instead of the endpoint's. Scopes nest. */
  protected def withAcl(acl: Acl)(declare: => Unit): Unit

  protected def unary[Req, Res](method: MethodDescriptor[Req, Res])(handler: Req => Res): Unit
  protected def serverStream[Req, Res](method: MethodDescriptor[Req, Res])(
      handler: Req => Source[Res, ?]): Unit
  protected def clientStream[Req, Res](method: MethodDescriptor[Req, Res])(
      handler: Requests[Req] => Res): Unit
  protected def bidiStream[Req, Res](method: MethodDescriptor[Req, Res])(
      handler: Requests[Req] => Source[Res, ?]): Unit

  /** Only on the handler's own thread, as `HttpEndpoint.request` is. */
  protected def caller: Caller
  protected def principal: Principal      // throws when the admitting ACL did not authenticate
  protected def metadata: CallMetadata
  protected def call: RequestContext      // method "POST", path "/<service definition>/<method>"
```

An endpoint in use, with ScalaPB's generated descriptors:

```scala
final class CartGrpcEndpoint(clients: EndpointClients) extends GrpcEndpoint(CartServiceGrpc.SERVICE):
  val acl: Acl = Acl.allowCallers(Callers.anyInProject)

  unary(CartServiceGrpc.METHOD_GET_CART) { request =>
    toProto(cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
  }

  withAcl(Acl.allowCallers(Callers.service("checkout"))) {
    unary(CartServiceGrpc.METHOD_ADD_ITEM) { request => … }
  }
```

**Checked when the service starts**, all problems reported together in one
`IllegalArgumentException("invalid ankka grpc configuration: …")`:

| Problem | Message names |
|---|---|
| a method of `service` has no handler | the service definition and the method |
| a handler's kind is not its method's (`unary` on a method that streams) | the method, the kind declared, the kind it is |
| a method declared twice | the method |
| a method that is not one of `service`'s | the method |
| two endpoints for one service definition | the service definition |

## Streams

```scala
/** The requests of a method that takes a stream. Used on the handler's thread. */
trait Requests[Req] extends Iterator[Req]:
  /** Blocks until the caller has sent a part or ended the stream. Asks the caller for one more
    * part each time it is called, and never before. */
  def hasNext: Boolean
  def next(): Req
  /** The same parts as a stream, with the same demand. Use one of the two, not both. */
  def asSource: Source[Req, NotUsed]

/** Thrown by `Requests`, and fails `asSource`, when the caller cancelled or disconnected. */
final class CallCancelled extends RuntimeException
```

- A part is taken from a handler's `Source` only while the caller can receive it.
- A handler that returns before `Requests` is exhausted ends the call; the remaining parts are not
  read.
- A part that cannot be decoded ends the call `INVALID_ARGUMENT`; `Requests` fails with
  `CallCancelled`.
- A `Source` that fails ends the call with the failure's status (below), after the parts already
  sent.

## How a call ends

| The handler | The caller is told |
|---|---|
| returns | `OK` |
| throws `CommandError(message, code)` | the status for `code` (table in research R7), with `message` |
| throws something carrying a `CommandError` as its cause | the same |
| throws `io.grpc.StatusRuntimeException` | that status, as thrown |
| throws anything else | `INTERNAL`, `internal error`; the failure is logged |
| is not reached: request cannot be decoded | `INVALID_ARGUMENT` |
| is not reached: ACL denies | `PERMISSION_DENIED`, `not permitted by this endpoint's acl` |
| is not reached: authenticator says unauthenticated | `UNAUTHENTICATED`, trailer `www-authenticate: Bearer <challenge>` |
| is not reached: authenticator says forbidden | `PERMISSION_DENIED`, the authenticator's reason |
| is not reached: authenticator cannot tell | `UNAVAILABLE`, the authenticator's reason |
| is not reached: no such method, or no such service definition | the endpoint's ACL first when the service definition is served; then `UNIMPLEMENTED` |
| is not reached: a request over `ankka.grpc.max-message-size` | `RESOURCE_EXHAUSTED` |

A caller's deadline is grpc-java's to enforce: the caller is told `DEADLINE_EXCEEDED`, and the
handler's thread is not interrupted. An answer's size is the caller's client's to limit.

A connection that is idle is pinged every `ankka.grpc.keepalive-time`, and one that does not
answer within `ankka.grpc.keepalive-timeout` is closed: its calls are cancelled, `Requests` fails
with `CallCancelled`, and a stream stops being produced.

## Metadata

```scala
trait CallMetadata:
  def get(name: String): Option[String]        // case-insensitive; text keys only
  def all(name: String): Vector[String]
  def toSeq: Vector[(String, String)]          // what an Acl sees as headers
```

Keys ending `-bin` are not shown. The local-caller key is withheld, as the HTTP server withholds
its header.

## Serving

```scala
object GrpcServer:
  def of(factories: (EndpointClients => GrpcEndpoint)*): GrpcServer
  def at(interface: String, port: Int)(factories: (EndpointClients => GrpcEndpoint)*): GrpcServer

final class GrpcServer extends RuntimeExtension:           // name "grpc-server"
  def withReflection(acl: Acl): GrpcServer
  def boundPort: Option[Int]
```

Registered like any extension:

```scala
Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .withExtension(GrpcServer.of(clients => CartGrpcEndpoint(clients)))
```

- Port 0 picks a free port. A port in use fails startup naming it.
- TLS is on exactly when the HTTP server's is: `ankka.http.tls.enabled`, set by the Kubernetes
  overlay and never by a local run. With it, a client certificate is required.
- `readiness` is false until bound. `stop()` stops accepting, gives calls in progress five
  seconds, then ends them `UNAVAILABLE`.
- `withReflection(acl)` serves the standard reflection service, v1 and v1alpha, to callers `acl`
  admits. It describes every endpoint registered, whatever that endpoint's ACL.

## Calling another service

```scala
final class GrpcClients extends RuntimeExtension:          // name "grpc-clients"
  /** A service of this service's own project. */
  def apply(name: String): io.grpc.Channel
  /** A service of another project. */
  def apply(project: String, name: String): io.grpc.Channel

object GrpcClients:
  def apply(): GrpcClients
```

```scala
val grpc = GrpcClients()
Ankka.service
  .register(…)
  .withExtension(grpc)
  .withExtension(HttpServer.of(clients => CheckoutEndpoint(clients.componentClient, grpc)))

// in a handler:
val cart = CartServiceGrpc.blockingStub(grpc("cart")).getCart(GetCartRequest(cartId))
```

- A channel is resolved when first used and kept. Using one before the service has started is an
  `IllegalStateException`.
- Thrown by the first call that cannot be made: `ServiceUnresolvable`, `ServiceServesNoGrpc`,
  `ServiceIdentityMismatch` (all in `ankka-sdk`).
- A refusal from the called service arrives as a `StatusRuntimeException` whose cause is the
  matching `CommandError`. Letting it propagate out of an HTTP or gRPC handler answers that
  handler's caller with the same refusal.

## Testing

```scala
object GrpcChannels:
  /** A plaintext channel to a service on this machine: what a test calls a `GrpcServer` with. */
  def plaintext(port: Int): io.grpc.ManagedChannel
  /** The same, with every call made as `caller`: the gRPC form of `LocalCallers.header`. */
  def plaintext(port: Int, caller: Caller): io.grpc.ManagedChannel
```

## Additions outside the module

| Where | Addition |
|---|---|
| `ankka-core` | `CommandError.from(t: Throwable): Option[CommandError]` — `t` itself, or its cause |
| `ankka-sdk` | `final case class ServiceServesNoGrpc(service: String) extends RuntimeException` |
| `ankka-runtime` | `RuntimeExtension.grpcAddress: Option[String] = None`; `RotatingTls.keyManager`, `.trustManager`, `.trustManagerRequiring(uri)`; `ServiceRegistration.grpcAddressOf(name)` |
| `ankka-http` | `RequestScope`, `Tracing`, `LocalCallers.callerFrom`: `private[http]` → `private[ankka]`; failure handling uses `CommandError.from` |
