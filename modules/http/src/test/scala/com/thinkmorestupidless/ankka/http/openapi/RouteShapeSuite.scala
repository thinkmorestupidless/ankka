package com.thinkmorestupidless.ankka.http.openapi

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.core.shape.{Shape, ShapeOf}
import com.thinkmorestupidless.ankka.core.{Codecs, Done}
import com.thinkmorestupidless.ankka.http.*
import org.apache.pekko.stream.scaladsl.Source

object RouteShapeFixtures:
  final case class Item(productId: String, quantity: Int)
  final case class Cart(id: String, items: List[Item])

  /** A codec written by hand: no shape can be derived. */
  final case class Opaque(word: String)
  object Opaque:
    given JsonValueCodec[Opaque] = new JsonValueCodec[Opaque]:
      def nullValue: Opaque                              = null
      def encodeValue(x: Opaque, out: JsonWriter): Unit  = out.writeVal(x.word)
      def decodeValue(in: JsonReader, d: Opaque): Opaque = Opaque(in.readString(null))

/** What each recorder writes into a route's `RouteShape` (feature 067, T009). */
class RouteShapeSuite extends munit.FunSuite:
  import RouteShapeFixtures.*

  private given JsonValueCodec[Item] = Codecs.make
  private given JsonValueCodec[Cart] = Codecs.make

  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll

    get("/{cartId}")((cartId: String) => Cart(cartId, Nil))
    get("/{cartId}/lines/{n}")((_: String, n: Int) => n)
    delete("/{cartId}")((_: String) => Done.done)
    post("/{cartId}/touch")((_: String) => ())
    postBody("/{cartId}/items")((cartId: String, item: Item) => Cart(cartId, List(item)))
    putBody("/{cartId}/note")((_: String, note: String) => note)
    patchBody("/{cartId}/opaque")((_: String, o: Opaque) => o)
    postStream("/{cartId}/upload")((_: String, _: StreamedBody) => Done.done)
    get("/{cartId}/download")((_: String) =>
      Streamed("image/png", None, java.io.InputStream.nullInputStream())
    )
    get("/{cartId}/bytes")((_: String) => Bytes("application/pdf", Array.emptyByteArray))
    get("/{cartId}/page")((_: String) => Html("<p/>"))
    get("/{cartId}/created")((cartId: String) => Respond(Cart(cartId, Nil), 201))
    get("/{cartId}/by/{id}")((_: String, id: java.util.UUID) => id.toString)
    sse("/{cartId}/events")((_: String) => Source.single("hi"))
    sseBody("/{cartId}/ask")((_: String, question: Item) => Source.single(question.productId))

  private val endpoint = Carts()

  private def route(method: String, template: String): RouteShape =
    endpoint.routes
      .find(r => r.method == method && r.template.render == template)
      .map(_.shape)
      .orElse(
        endpoint.streamRoutes
          .find(r => r.method == method && r.template.render == template)
          .map(_.shape)
      )
      .getOrElse(fail(s"no $method $template"))

  private def rendered(s: Option[ShapeOf[?]]): Option[String] =
    s.map(o => Shape.write(Shape.render(o.shape, _.simple)))

  test("get: path parameters with their types, no body, a typed answer's shape and status") {
    val shape = route("GET", "/{cartId}")
    assertEquals(shape.pathParameters, Vector("cartId" -> "string"))
    assertEquals(shape.body, None)
    assertEquals(shape.answer.status, Some(200))
    assertEquals(shape.answer.contentType, "application/json")
    assertEquals(shape.answer.kind, AnswerKind.Body)
    assertEquals(rendered(shape.answer.shape), Some("""{"$ref":"Cart"}"""))
    assertEquals(
      shape.answer.shape.toVector.flatMap(_.definitions.map(_.name.simple)).sorted,
      Vector("Cart", "Item")
    )
  }

  test("each path parameter has the type its handler names, in the path's order") {
    assertEquals(
      route("GET", "/{cartId}/lines/{n}").pathParameters,
      Vector("cartId" -> "string", "n" -> "int")
    )
    assertEquals(
      route("GET", "/{cartId}/by/{id}").pathParameters,
      Vector("cartId" -> "string", "id" -> "uuid")
    )
    assertEquals(
      rendered(route("GET", "/{cartId}/lines/{n}").answer.shape),
      Some("""{"type":"integer"}""")
    )
  }

  test("delete and post answering Done or Unit answer nothing, 204") {
    for (m, t) <- Seq("DELETE" -> "/{cartId}", "POST" -> "/{cartId}/touch") do
      val answer = route(m, t).answer
      assertEquals(answer.status, Some(204), s"$m $t")
      assertEquals(answer.kind, AnswerKind.Nothing, s"$m $t")
      assertEquals(answer.shape, None, s"$m $t")
  }

  test("postBody: the body's content type and shape") {
    val body = route("POST", "/{cartId}/items").body.getOrElse(fail("no body"))
    assertEquals(body.contentType, "application/json")
    assertEquals(rendered(body.shape), Some("""{"$ref":"Item"}"""))
    assert(!body.streamed)
  }

  test("putBody of text: text/plain with no shape, answered as text with none (FR-018: opaque)") {
    val shape = route("PUT", "/{cartId}/note")
    assertEquals(shape.body.map(_.contentType), Some("text/plain"))
    assertEquals(shape.body.flatMap(_.shape), None)
    assertEquals(shape.answer.contentType, "text/plain; charset=UTF-8")
    assertEquals(shape.answer.shape, None)
  }

  test("patchBody of a type with a hand-written codec: no shape for the body or the answer") {
    val shape = route("PATCH", "/{cartId}/opaque")
    assertEquals(shape.body.flatMap(_.shape), None)
    assertEquals(shape.answer.shape, None)
    assertEquals(shape.answer.kind, AnswerKind.Body)
  }

  test("postStream: a streamed body, with no shape") {
    val body = route("POST", "/{cartId}/upload").body.getOrElse(fail("no body"))
    assert(body.streamed)
    assertEquals(body.shape, None)
  }

  test("a Streamed answer is a stream; Bytes and Html are bodies with no shape") {
    assertEquals(route("GET", "/{cartId}/download").answer.kind, AnswerKind.Stream)
    val bytes = route("GET", "/{cartId}/bytes").answer
    assertEquals((bytes.kind, bytes.shape, bytes.status), (AnswerKind.Body, None, Some(200)))
    assertEquals(route("GET", "/{cartId}/page").answer.shape, None)
  }

  test("Respond: the handler decides the status, and the shape is its body's") {
    val answer = route("GET", "/{cartId}/created").answer
    assertEquals(answer.status, None)
    assertEquals(rendered(answer.shape), Some("""{"$ref":"Cart"}"""))
  }

  test("sse and sseBody: an event stream; sseBody's body has its shape") {
    val events = route("GET", "/{cartId}/events")
    assertEquals(events.answer.kind, AnswerKind.Events)
    assertEquals(events.answer.contentType, "text/event-stream")
    assertEquals(events.body, None)
    val asked = route("POST", "/{cartId}/ask")
    assertEquals(rendered(asked.body.flatMap(_.shape)), Some("""{"$ref":"Item"}"""))
    assertEquals(asked.pathParameters, Vector("cartId" -> "string"))
  }

  test("a route built anywhere else knows nothing") {
    assertEquals(RouteShape.unknown.pathParameters, Vector.empty)
    assertEquals(RouteShape.unknown.answer.shape, None)
  }

  // T018: described scopes.

  /** The same routes, described or not; a description must change nothing of what they answer. */
  private final class Scoped(describe: Boolean) extends HttpEndpoint("/scoped"):
    val acl: Acl = Acl.AllowAll

    override protected def description: Description =
      if describe then Description.none.tag("carts") else Description.none

    private def maybe(d: Description => Description)(declare: => Unit): Unit =
      if describe then described(d)(declare) else declare

    maybe(_.tag("outer").refuses(404, "no cart")) {
      get("/{cartId}")((cartId: String) => Cart(cartId, Nil))
      maybe(_.summary("the total")) {
        get("/{cartId}/total")((_: String) => 7)
      }
    }
    get("/{cartId}/plain")((cartId: String) => cartId)
    withAcl(Acl.DenyAll) {
      maybe(_.summary("inside an acl")) {
        postBody("/{cartId}/items")((cartId: String, item: Item) => Cart(cartId, List(item)))
      }
    }
    maybe(_.summary("around an acl")) {
      withAcl(Acl.AllowAll) {
        delete("/{cartId}")((_: String) => Done.done)
      }
    }

  private val scoped = Scoped(describe = true)

  private def scopedRoute(method: String, template: String): RouteShape =
    scoped.routes
      .find(r => r.method == method && r.template.render == template)
      .map(_.shape)
      .getOrElse(fail(s"no $method $template"))

  test("nested scopes add, outermost first, and each counts the routes it held") {
    val total = scopedRoute("GET", "/{cartId}/total")
    assertEquals(total.description.tags, Vector("outer"))
    assertEquals(total.description.summaries, Vector("the total"))
    assertEquals(total.description.refusals.map(r => (r._1, r._2)), Vector(404 -> "no cart"))
    assertEquals(total.scopes.map(_.held), Vector(2, 1))
    assertEquals(scopedRoute("GET", "/{cartId}").scopes.map(_.held), Vector(2))
  }

  test("a route outside every scope carries only the endpoint's description") {
    val plain = scopedRoute("GET", "/{cartId}/plain")
    assertEquals(plain.scopes, Vector.empty)
    assertEquals(plain.description.summaries, Vector.empty)
    assertEquals(scoped.endpointDescription.andThen(plain.description).tags, Vector("carts"))
  }

  test("described inside a withAcl and around one: both describe, and the acl still holds") {
    val inside = scoped.routes.find(_.template.render == "/{cartId}/items").get
    assertEquals(inside.shape.description.summaries, Vector("inside an acl"))
    assertEquals(inside.acl, Some(Acl.DenyAll))
    val around = scoped.routes.find(r => r.method == "DELETE").get
    assertEquals(around.shape.description.summaries, Vector("around an acl"))
    assertEquals(around.acl, Some(Acl.AllowAll))
    assertEquals(scopedRoute("GET", "/{cartId}").description.summaries, Vector.empty)
  }

  test("a described route runs byte for byte as the plain one: status, type, body, headers, ACL") {
    val plain                    = Scoped(describe = false)
    def answers(e: HttpEndpoint) = e.routes.map { r =>
      val body =
        if r.needsBody then """{"productId":"p1","quantity":2}""".getBytes else Array.emptyByteArray
      val a = r.run(Vector("c1"), body)
      (r.method, r.template.render, r.acl, a.status, a.contentType, a.body.toSeq, a.headers)
    }
    assertEquals(answers(scoped), answers(plain))
    assertEquals(answers(scoped).size, 5)
  }

  test("answers and refuses: with a type they carry its shape, without one they carry none") {
    val d = Description.none
      .answers(204)
      .answers[Cart](201)
      .refuses(404, "no cart")
      .refuses[Item](409, "a line already held")
    assertEquals(
      d.answered.map((s, shape) => s -> shape.isDefined),
      Vector(204 -> false, 201 -> true)
    )
    assertEquals(
      d.refusals.map((s, r, shape) => (s, r, shape.isDefined)),
      Vector((404, "no cart", false), (409, "a line already held", true))
    )
    assertEquals(
      d.answered.collectFirst { case (201, Some(s)) => rendered(Some(s)) }.flatten,
      Some("""{"$ref":"Cart"}""")
    )
  }

  test("a type with no shape given to answers is refused at compile time") {
    val errors = compileErrors("Description.none.answers[Opaque](200)")
    assert(errors.contains("ShapeOf"), errors)
  }
