# Contract: the Scala surface

Signatures a service or its tests may write. Everything else the feature adds is `private[ankka]`.

## `core.shape`

```scala
trait ShapeOf[A]:
  def shape: Shape

object ShapeOf:
  inline given derived[A]: ShapeOf[A]            // products, sealed hierarchies, enumerations
  def of(jsonSchema: String): ShapeOf[Nothing]   // a shape written as JSON Schema
  // instances: String, Boolean, Int, Long, Double, BigDecimal, Instant, Duration, LocalDate, UUID,
  // Option[A], List/Vector/Seq/Set[A], Map[String, A]
```

A type with a hand-written codec in its companion states its shape there:

```scala
object ServiceLifecycle:
  given JsonValueCodec[ServiceLifecycle] = ...
  given ShapeOf[ServiceLifecycle] = ShapeOf.of("""{"type":"string","enum":["Ready","Paused"]}""")
```

Without the second line a route using the type is incomplete; it is never given a wrong shape.

## `HttpEndpoint`

```scala
protected def described(describe: Description => Description)(declare: => Unit): Unit

/** What every route of this endpoint carries; nothing by default. */
protected def description: Description = Description.none
```

```scala
final class Description:
  def summary(text: String): Description
  def body[A](using ShapeOf[A]): Description
  def body(jsonSchema: String): Description
  def answers[A](using ShapeOf[A]): Description                 // status 200
  def answers[A](status: Int)(using ShapeOf[A]): Description
  def answers(status: Int): Description                          // no body
  def answers(status: Int, jsonSchema: String): Description
  def refuses(status: Int, reason: String): Description
  def refuses[A](status: Int, reason: String)(using ShapeOf[A]): Description
  def refusalShape[A](using ShapeOf[A]): Description
  def query[A](name: String)(using FromQuery[A]): Description          // optional
  def queryRequired[A](name: String)(using FromQuery[A]): Description
  def tag(name: String, more: String*): Description
```

Example, the case the feature was asked for:

```scala
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/players/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  override protected def description =
    Description.none.tag("player").refusalShape[Refusal].refuses(403, "wrong brand")

  described(_.summary("A player's messages").answers[Messages].refuses(404, "not found")
             .query[String]("status").query[Int]("page").query[Int]("pageSize")) {
    get("/{playerId}/messages") { (id: String) =>
      profile.messages(principal, id, query.raw("status"), query.raw("page"), query.raw("pageSize"))
    }
  }
```

Every existing declaration (`get`, `postBody`, `sse`, `withAcl`, ...) keeps its signature.
`FromBody.json` and `ToResponse.json` take one more `using` each; no caller writes it.

## `HttpServer`

```scala
def withOpenApi(acl: Acl): HttpServer    // serve GET /_ankka/openapi.json to whom acl admits
```

## `ankka-testkit`

```scala
object HeldToDocument:
  def server(server: HttpServer): HttpServer     // the same server, checked
  def violations: Vector[String]                 // since the last clear
  def assertHeld(): Unit                         // fails, listing them, and clears
  def clear(): Unit

/** A munit suite that asserts after each test, as LogCapturing prints after a failed one. */
trait HeldToDocument extends munit.FunSuite
```

```scala
class CartHttpSuite extends munit.FunSuite with HeldToDocument with LogCapturing:
  server = HeldToDocument.server(HttpServer.at("127.0.0.1", 0)(clients => CartEndpoint(clients)))
```

A violation reads `GET /carts/{cartId}: answered 409, which its description does not name (200,
404)`.

## The mode a service's `main` runs in

Not a declaration, and nothing a service writes: `ServiceBuilder.start` reads the system
properties in [command.md](command.md) before anything is started.
