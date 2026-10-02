package com.thinkmorestupidless.ankka.runtime

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.{
  Codecs,
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  DeclaredHandler,
  HandlerKind
}
import munit.FunSuite

/**
 * The topology document, read back as a reader of it would read it.
 *
 * The renderer writes JSON by hand, so every case decodes what it wrote: a document that only
 * contains the right words is not one a console can draw.
 */
final class TopologyJsonSuite extends FunSuite:

  import TopologyJsonSuite.*

  private def descriptor(
      id: String,
      of: ComponentKind,
      handlers: Vector[DeclaredHandler] = Vector.empty,
      thePlatforms: Boolean = false
  ): ComponentDescriptor =
    new ComponentDescriptor:
      val componentId: ComponentId                           = ComponentId(id)
      val kind: ComponentKind                                = of
      override def declaredHandlers: Vector[DeclaredHandler] = handlers
      override def platform: Boolean                         = thePlatforms

  private def render(
      components: Seq[ComponentDescriptor],
      routes: Vector[ServedRoute] = Vector.empty,
      name: String = "cart"
  ): String =
    TopologyJson.render(
      name,
      "48213",
      "2026-10-01T09:12:03Z",
      ComponentRegistry.fromOrThrow(components),
      routes
    )

  private def read(json: String): Document = readFromString[Document](json)

  test("a service with nothing registered is a document with nothing in it, not an error") {
    val document = read(render(Nil))
    assertEquals(document.service, Service("cart", document.service.runtime, "48213", Started))
    assertEquals(document.nodes, Vector.empty)
    assertEquals(document.declared, Vector.empty)
    assertEquals(document.calls, Vector.empty)
    assertEquals(document.window, Window(0, Started, 0))
  }

  test("each kind of component is a node of that kind, in the layer every console draws it in") {
    val layers = Vector(
      ComponentKind.TimedAction        -> 0,
      ComponentKind.Workflow           -> 1,
      ComponentKind.Agent              -> 1,
      ComponentKind.AutonomousAgent    -> 1,
      ComponentKind.EventSourcedEntity -> 2,
      ComponentKind.KeyValueEntity     -> 2,
      ComponentKind.View               -> 3,
      ComponentKind.Consumer           -> 3
    )
    val components = layers.map((kind, _) => descriptor(s"a-${kind.toString.toLowerCase}", kind))
    val nodes      = read(render(components)).nodes.map(n => n.kind -> n.layer)
    assertEquals(nodes.toSet, layers.map((kind, layer) => kind.toString -> layer).toSet)
  }

  test("a component's handlers are listed by name, each saying what it is for") {
    val cart = descriptor(
      "cart",
      ComponentKind.EventSourcedEntity,
      Vector(
        DeclaredHandler("add-item", HandlerKind.Command),
        DeclaredHandler("get-cart", HandlerKind.Query)
      )
    )
    assertEquals(
      read(render(Seq(cart))).nodes.head.handlers,
      Vector(Handler("add-item", "command"), Handler("get-cart", "query"))
    )
  }

  test("a component is the platform's only when it says so, whatever it is called") {
    val nodes = read(
      render(
        Seq(
          descriptor("ankka-session-memory", ComponentKind.EventSourcedEntity, thePlatforms = true),
          descriptor("ankka-looking", ComponentKind.EventSourcedEntity)
        )
      )
    ).nodes
    assertEquals(
      nodes.map(n => n.id -> n.platform).toMap,
      Map("ankka-session-memory" -> true, "ankka-looking" -> false)
    )
  }

  test("an endpoint is one node, whose handlers are the routes it serves") {
    val routes = Vector(
      ServedRoute("POST", "/carts/{cartId}/items", streaming = false, "endpoint:/carts"),
      ServedRoute("GET", "/carts/{cartId}", streaming = false, "endpoint:/carts"),
      ServedRoute("GET", "/chat/{session}", streaming = true, "endpoint:/chat")
    )
    val nodes = read(render(Nil, routes)).nodes
    assertEquals(nodes.map(_.id), Vector("endpoint:/carts", "endpoint:/chat"))
    assert(nodes.forall(n => n.kind == "Endpoint" && n.layer == 0 && !n.platform), nodes)
    assertEquals(
      nodes.head.handlers,
      Vector(
        Handler("GET /carts/{cartId}", "route", Some(false)),
        Handler("POST /carts/{cartId}/items", "route", Some(false))
      )
    )
    assertEquals(nodes.last.handlers, Vector(Handler("GET /chat/{session}", "route", Some(true))))
  }

  test("an endpoint that is also registered as a component is drawn once, from its routes") {
    // A remote endpoint is declared in discovery, so it is in the registry too.
    val registered = descriptor("orders-api", ComponentKind.Endpoint)
    val routes     = Vector(ServedRoute("GET", "/orders", streaming = false, "endpoint:orders-api"))
    assertEquals(
      read(render(Seq(registered), routes)).nodes.map(_.id),
      Vector("endpoint:orders-api")
    )
  }

  test("the same service renders the same document, whatever order it was registered in") {
    val components = Vector(
      descriptor("wallet", ComponentKind.KeyValueEntity),
      descriptor("cart", ComponentKind.EventSourcedEntity),
      descriptor("carts-by-customer", ComponentKind.View),
      descriptor("checkout", ComponentKind.Workflow)
    )
    val forwards  = render(components)
    val backwards = render(components.reverse)
    assertEquals(forwards, backwards)
    assertEquals(
      read(forwards).nodes.map(_.id),
      Vector("checkout", "cart", "wallet", "carts-by-customer"),
      "by layer, then by id"
    )
  }

  test("a name with a quote in it is still one valid document") {
    val document = read(
      render(
        Seq(descriptor("cart", ComponentKind.EventSourcedEntity)),
        Vector(ServedRoute("GET", "/a\"b\\c", streaming = false, "endpoint:/a")),
        name = "the \"cart\" service"
      )
    )
    assertEquals(document.service.name, "the \"cart\" service")
    assertEquals(document.nodes.head.handlers.head.name, "GET /a\"b\\c")
  }

object TopologyJsonSuite:

  private val Started = "2026-10-01T09:12:03Z"

  // The document as a reader models it. A field the renderer drops or renames fails to decode.
  final case class Service(name: String, runtime: String, instance: String, startedAt: String)
  final case class Window(seconds: Long, since: String, calls: Long)
  final case class Handler(name: String, `type`: String, streaming: Option[Boolean] = None)
  final case class Node(
      id: String,
      kind: String,
      layer: Int,
      platform: Boolean,
      handlers: Vector[Handler]
  )
  final case class Document(
      service: Service,
      window: Window,
      nodes: Vector[Node],
      declared: Vector[String],
      calls: Vector[String]
  )

  given JsonValueCodec[Document] = Codecs.make[Document]
