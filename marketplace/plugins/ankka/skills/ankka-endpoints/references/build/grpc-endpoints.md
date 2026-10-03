# gRPC endpoints

> Serve a .proto service definition from a Scala service — every kind of method, access control, statuses, streams, reflection, calling another service's gRPC endpoint, and testing it.

Source: https://docs.ankka.cloud/build/grpc-endpoints/
A gRPC endpoint serves the methods of a service definition written in a `.proto` file. Like an
[HTTP endpoint](http-endpoints.md) it is the outermost layer of a service: it holds no state, turns each
call into calls on the service's components, and states who may call it. What differs is the contract,
which is written first and shared with whatever calls the service, in any language with a gRPC client.

A service may serve HTTP endpoints, gRPC endpoints, or both. gRPC endpoints are for services written in
Scala.

## The service definition

The API is a `.proto` file. This is the shopping cart sample's, the same cart its HTTP endpoint serves:

```protobuf
service CartService {
  rpc GetCart (GetCartRequest) returns (Cart);
  rpc AddItem (AddItemRequest) returns (Cart);
  // Who the service read the call as having come from, and which instance answered: what the
  // platform's own tests ask a deployed cart.
  rpc WhoCalled (WhoCalledRequest) returns (WhoCalledReply);
}

// The same cart, a part at a time: one kind of method each way a stream can go.
service CartStreams {
  // The cart after each change, for as long as the caller watches.
  rpc WatchCart   (GetCartRequest)        returns (stream Cart);
  // Items sent one at a time, answered once with how many were added.
  rpc ImportItems (stream AddItemRequest) returns (ImportSummary);
  // A conversation: each line answered as it arrives.
  rpc Converse    (stream Line)           returns (stream Line);
}

message ImportSummary { int32 added = 1; }
message Line          { string text = 1; }

message GetCartRequest { string cart_id = 1; }
message AddItemRequest { string cart_id = 1; LineItem item = 2; }
message LineItem       { string product_id = 1; string name = 2; int32 quantity = 3; }
message Cart           { string cart_id = 1; repeated LineItem items = 2; bool checked_out = 3; }
```

A method's name in the `.proto` file is its wire name: a caller names it, a trace records it, and
renaming a Scala method changes neither.

### Generating the code

ScalaPB generates the messages and the descriptors an endpoint is declared against. Keep the `.proto`
files and the generated code in a subproject of their own, because generated code does not compile
cleanly under the warnings a service's own code is held to. In `project/plugins.sbt`:

```scala
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.20"
```

And in `build.sbt`, the subproject beside the service, which depends on it and on `ankka-grpc`. Both
must be built with the same Scala version, so set it for the whole build (`ThisBuild / scalaVersion`)
rather than on the service's project alone; a service made with `ankka init` already does:

```scala
lazy val api = project
  .in(file("api"))
  .settings(
    scalacOptions := Seq("-encoding", "UTF-8", "-source:3.3"),
    Compile / PB.targets := Seq(scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  )

lazy val service = project
  .dependsOn(api)
  .settings(libraryDependencies += "com.thinkmorestupidless" %% "ankka-grpc" % ankkaVersion)
```

`ankka-grpc` brings grpc-java itself. A service that serves no gRPC does not depend on it and carries
none of it.

## An endpoint

An endpoint names the service definition it implements, states its access control list (ACL), and
declares a handler for each method. This is the excerpt of the sample's endpoint that answers the two
cart methods; the whole file is the shopping cart sample's `CartGrpcEndpoint.scala`:

```scala
/**
 * The cart's gRPC API: the service definition in `shopping-cart-api/…/cart.proto`, implemented over
 * the same entity the HTTP endpoint calls.
 *
 * Like the HTTP endpoint it holds no state and maps no errors: a rejection from the entity carries
 * its `ErrorCode`, which reaches the caller as the matching gRPC status. What it does do is
 * translate, between the messages of the API and the cart's own types — the API is a contract with
 * callers, and the domain is free to change behind it.
 */
final class CartGrpcEndpoint(clients: EndpointClients)
    extends GrpcEndpoint(CartServiceGrpc.SERVICE):

  /**
   * Any service of this project, and the internet through the gateway when the cart is exposed. A
   * deployment that sets `CART_GRPC_CALLER` admits that one service and nobody else, which is how
   * the platform's own tests show a refusal by name.
   */
  val acl: Acl = sys.env.get("CART_GRPC_CALLER").filter(_.nonEmpty) match
    case Some(only) => Acl.allowCallers(Callers.service(only))
    case None       => Acl.allowCallers(Callers.anyInProject, Callers.internet)

  unary(CartServiceGrpc.METHOD_GET_CART) { request =>
    toProto(cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
  }

  unary(CartServiceGrpc.METHOD_ADD_ITEM) { request =>
    val item = request.item.getOrElse(LineItem())
    val _ = cart(request.cartId)
      .call(ShoppingCartEntity.addItem)
      .invoke(domain.LineItem(item.productId, item.name, item.quantity))
    toProto(cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
  }
```

Handlers block. Each runs on a virtual thread of its own, so a blocking component call inside one costs
nothing, exactly as in an HTTP handler. The endpoint translates between the API's messages and the
service's own types: the API is a contract with callers, and the domain is free to change behind it.

When the service starts, every endpoint is checked and every problem is reported at once: a method of
the definition with no handler, a handler declared as the wrong kind of method, a method declared twice
or not the definition's, and two endpoints for one definition. Such a service does not start.

### The four kinds of method

A method takes one request or a stream of them, and answers once or with a stream. Each kind has its own
declaration:

| The method | Declared with | The handler |
|---|---|---|
| one request, one answer | `unary` | `Req => Res` |
| one request, a stream of answers | `serverStream` | `Req => Source[Res, ?]` |
| a stream of requests, one answer | `clientStream` | `Requests[Req] => Res` |
| a stream each way | `bidiStream` | `Requests[Req] => Source[Res, ?]` |

## Streams

A stream moves no faster than the side reading it, in either direction, so neither a slow caller nor a
slow handler makes the service hold what has not been read.

An answer that is a stream is a Pekko Streams `Source`. Each part reaches the caller as it is produced,
and a part is taken from the `Source` only while the caller can receive it. When the caller goes away
the `Source` is cancelled. A `Source` that fails ends the call with the failure's status, after every
part already produced:

```scala
// The cart after each change, for as long as the caller watches. An entity does not stream its
// state, so this reads it every half second and sends it when it differs; the caller's going
// away cancels the stream.
serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
  Source
    .tick(0.seconds, 500.millis, ())
    .map(_ => cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
    .statefulMap(() => Option.empty[domain.ShoppingCart])(
      (last, now) => (Some(now), Option.when(!last.contains(now))(toProto(now))),
      _ => None
    )
    .collect { case Some(changed) => changed }
}
```

A stream of requests arrives as `Requests`, a blocking iterator that asks the caller for one more part
each time it is read, and never before. A handler may answer — or refuse — before it has read them all,
and the rest is never read. When the caller goes away before ending its stream, the next read throws
`CallCancelled`:

```scala
// Each item is added as it arrives, and the caller is answered once the stream ends. A refusal —
// a checked-out cart — ends the call at once, with the items before it added.
clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS) { items =>
  var added = 0
  items.foreach { request =>
    val item = request.item.getOrElse(LineItem())
    val _ = cart(request.cartId)
      .call(ShoppingCartEntity.addItem)
      .invoke(domain.LineItem(item.productId, item.name, item.quantity))
    added += 1
  }
  ImportSummary(added)
}
```

`Requests.asSource` gives the same parts as a `Source`, with the same demand, which is the natural shape
for a conversation:

```scala
bidiStream(CartStreamsGrpc.METHOD_CONVERSE) { lines =>
  lines.asSource.map(line => Line(s"heard: ${line.text}"))
}
```

A part of a stream of requests that is not a request of the method's type ends the call as an invalid
argument.

## How a call ends

Every call ends with a status. A component's refusal reaches the caller as the status for its code, with
its message, so the endpoint maps no errors:

| The component's `ErrorCode` | The caller's status |
|---|---|
| `BadRequest` | `INVALID_ARGUMENT` |
| `Unauthorized` | `UNAUTHENTICATED` |
| `Forbidden` | `PERMISSION_DENIED` |
| `NotFound` | `NOT_FOUND` |
| `Conflict` | `FAILED_PRECONDITION` |
| `Timeout` | `DEADLINE_EXCEEDED` |
| `Unavailable` | `UNAVAILABLE` |
| `Internal` | `INTERNAL` |

`Conflict` is the state not permitting what was asked — a checked-out cart refusing an item — which is
what failed precondition means; a caller that retries it unchanged gets the same answer.

A handler may throw an `io.grpc.StatusRuntimeException` of its own choosing for a case the table does not
fit, and it is sent as thrown. Anything else a handler throws ends the call `INTERNAL` with the message
`internal error`, and what went wrong is written to the service's log, never to the caller.

A request that is not a request of the method's type is `INVALID_ARGUMENT`, and no handler runs. A request
larger than `ankka.grpc.max-message-size`, 4 MiB unless set, is `RESOURCE_EXHAUSTED`. How large an answer a
caller accepts is the caller's own client's limit. A caller's deadline is enforced on the caller's side:
it is told `DEADLINE_EXCEEDED`, and the handler is not interrupted.

## Access control

A gRPC endpoint states who may call it in exactly the words an HTTP endpoint does: `Acl.DenyAll`,
`Acl.AllowAll`, `Acl.allowCallers(…)`, `Acl.AllowIf(…)` and `Acl.Authenticate(…)`. There is no default.
`withAcl` gives the methods declared inside it an ACL of their own, which replaces the endpoint's for those
methods alone.

An ACL sees a call as a request: method `POST`, path `/<service definition>/<method>`, and the call's text
metadata as its headers. So an authenticator written for an HTTP endpoint, reading the `Authorization`
header, admits a gRPC call carrying `authorization` metadata unchanged. A refused call runs no handler and
reads no request. The refusals are statuses:

| The ACL | The caller's status |
|---|---|
| denies | `PERMISSION_DENIED` |
| an authenticator answers unauthenticated | `UNAUTHENTICATED`, with a `www-authenticate` trailer carrying the challenge |
| an authenticator answers forbidden | `PERMISSION_DENIED`, with the authenticator's reason |
| an authenticator cannot tell | `UNAVAILABLE` |

A call to a method the endpoint does not have is judged by the endpoint's ACL before it is answered
`UNIMPLEMENTED`, so a closed endpoint does not disclose which methods it has.

A handler reads `caller`, `principal`, `metadata` and `call` on its own thread, as an HTTP handler reads
`caller`, `principal` and `request`. In a cluster the caller is read from the client certificate the
platform issued the calling workload, and nothing the call says about itself is believed; on a developer's
machine every caller is the local caller. See [Tenancy and access](../concepts/tenancy-and-access.md).

## Registering endpoints

A gRPC endpoint is served by a `GrpcServer`, registered with the service like any other extension. Its
factories take the same clients an HTTP endpoint's do:

```scala
val server = GrpcServer.of(
  clients => CartGrpcEndpoint(clients),
  clients => CartStreamsEndpoint(clients)
)
```

The server listens on `ankka.grpc.port`, 9090 unless set, beside the HTTP port. A deployed service serves
gRPC only when its descriptor says `"grpc": true`; see
[the service descriptor](../reference/service-descriptor.md). A service started with a gRPC port and no
`GrpcServer` registered refuses to start, saying why, and the platform reports that reason.

## Reflection

A service can let a tool such as `grpcurl` ask what it serves — its service definitions, their methods and
their messages — with no `.proto` file. It opts in, and states who may ask:

```scala
// A tool such as grpcurl may ask what the cart serves: from this machine, and through the
// gateway when the cart is exposed. CART_REFLECTION=off leaves it out.
val reflecting =
  if sys.env.get("CART_REFLECTION").contains("off") then server
  else server.withReflection(Acl.allowCallers(Callers.internet))
```

Opting in describes the whole service: every endpoint's methods are listed to whoever reflection's ACL
admits, whatever that endpoint's own ACL. It changes nothing about who may call them. A service that has
not opted in answers a reflection call `UNIMPLEMENTED`.

```bash
grpcurl -plaintext localhost:9090 list
# shoppingcart.v1.CartService
# shoppingcart.v1.CartStreams
```

## Call another service's gRPC endpoint

A service calls another's gRPC endpoint through `GrpcClients`, created once and handed to whatever calls
it, as everything a component uses is:

```scala
val grpcClients = GrpcClients()
```

`grpcClients("cart")` is a channel to the `cart` service of this project, and
`grpcClients("shop", "cart")` to the one in project `shop`; the stub generated from that service's
`.proto` file is built on it:

```scala
// Asks another cart service of this project who it read the call as coming from: the answer is
// how that service saw this one.
get("/{service}") { (service: String) =>
  CartServiceGrpc.blockingStub(grpc(service)).whoCalled(WhoCalledRequest()).caller
}
```

In a cluster the call presents this service's certificate, so the called service's ACL sees who is
calling, and it is only ever sent to a workload whose certificate names the service asked for. Calls are
balanced across the called service's ready instances one by one, not connection by connection. On a
developer's machine the called service is found where `ankka.local-grpc-services."<name>"` says, or else
where it announced itself while running.

A call that cannot be made says why, on the first call: `ServiceUnresolvable` when there is no such
service, `ServiceServesNoGrpc` when it serves no gRPC, and a call ended `UNAVAILABLE` whose cause is
`ServiceIdentityMismatch` when the workload that answered is not that service. A refusal from the called
service arrives as a `StatusRuntimeException` whose cause is the matching `CommandError`, so a handler that
lets it pass answers its own caller with the same refusal.

## Test it

A test starts the whole service with the test kit, the gRPC server on a port the system picks, and calls it
through the stub generated from the `.proto` file:

```scala
private val grpc = GrpcServer.at("127.0.0.1", 0)(clients => CartGrpcEndpoint(clients))
private val http =
  HttpServer.at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))

private var testKit: AnkkaTestKit   = null
private var channel: ManagedChannel = null

override def beforeAll(): Unit =
  testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(grpc, http))
  channel = GrpcChannels.plaintext(grpc.boundPort.getOrElse(fail("the gRPC server did not bind")))

override def afterAll(): Unit =
  if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
  if testKit != null then testKit.stop()

private def carts = CartServiceGrpc.blockingStub(channel)

test("an item added is in the cart read back") {
  val _    = carts.addItem(AddItemRequest("grpc-1", Some(LineItem("p1", "Widget", 2))))
  val cart = carts.getCart(GetCartRequest("grpc-1"))
  assertEquals(cart.items.map(i => (i.productId, i.quantity)), Seq("p1" -> 2))
}
```

`GrpcChannels.plaintext(port)` is a channel to a service on this machine; `GrpcChannels.plaintext(port,
caller)` makes every call as `caller`, which is how a test shows an ACL that names callers admitting one
and refusing another. See [Testing](testing.md).
