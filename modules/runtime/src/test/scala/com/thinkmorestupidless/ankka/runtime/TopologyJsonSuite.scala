package com.thinkmorestupidless.ankka.runtime

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.{
  Codecs,
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  DeclaredHandler,
  HandlerKind,
  MethodName,
  Serializers
}
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteConsumerDescriptor,
  RemoteSource,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerDescriptor,
  View,
  ViewDescriptor
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

  // ── declared connections ─────────────────────────────────────────────────────

  private def events(of: String): ChangeSource[String] =
    ChangeSource.EventSourced(ComponentId(of), Serializers.string)
  private def state(of: String): ChangeSource[String] =
    ChangeSource.KeyValue(ComponentId(of), Serializers.string)
  private def topic(name: String): ChangeSource[String] =
    ChangeSource.Topic(name, Serializers.string)

  /** A real view descriptor: the renderer reads sources from the types a service registers. */
  private def view(id: String, source: ChangeSource[String]): ComponentDescriptor =
    ViewDescriptor[View[String, String], String, String](
      ComponentId(id),
      source,
      Serializers.string,
      _ => fail("a topology never creates a component"),
      parallelism = 1
    )

  private def consumer(
      id: String,
      source: ChangeSource[String],
      publishesTo: Option[String] = None
  ): ComponentDescriptor =
    ConsumerDescriptor[Consumer[String, String], String, String](
      ComponentId(id),
      source,
      publishesTo.map(_ => Serializers.string),
      publishesTo,
      _ => fail("a topology never creates a component"),
      parallelism = 1
    )

  private def remoteView(id: String, source: RemoteSource): ComponentDescriptor =
    RemoteViewDescriptor(ComponentId(id), source, "row", Set(MethodName("by-id")))

  private def remoteConsumer(
      id: String,
      source: RemoteSource,
      publishesTo: Option[String] = None
  ): ComponentDescriptor =
    RemoteConsumerDescriptor(ComponentId(id), source, publishesTo)

  private def remoteEntity(kind: ComponentKind, id: String): RemoteSource =
    RemoteSource.Component(kind, ComponentId(id))

  private val cart    = descriptor("cart", ComponentKind.EventSourcedEntity)
  private val profile = descriptor("profile", ComponentKind.KeyValueEntity)

  test("a view or a consumer is connected to what it reads, in either language") {
    val readers = Vector[(ComponentDescriptor, Edge)](
      view("v-events", events("cart"))     -> Edge("cart", "v-events", "events"),
      view("v-state", state("profile"))    -> Edge("profile", "v-state", "state"),
      view("v-topic", topic("orders"))     -> Edge("topic:orders", "v-topic", "topic-subscription"),
      consumer("c-events", events("cart")) -> Edge("cart", "c-events", "events"),
      consumer("c-state", state("profile")) -> Edge("profile", "c-state", "state"),
      consumer("c-topic", topic("orders")) -> Edge("topic:orders", "c-topic", "topic-subscription"),
      remoteView("rv-events", remoteEntity(ComponentKind.EventSourcedEntity, "cart")) ->
        Edge("cart", "rv-events", "events"),
      remoteView("rv-state", remoteEntity(ComponentKind.KeyValueEntity, "profile")) ->
        Edge("profile", "rv-state", "state"),
      remoteView("rv-topic", RemoteSource.Topic("orders")) ->
        Edge("topic:orders", "rv-topic", "topic-subscription"),
      remoteConsumer("rc-events", remoteEntity(ComponentKind.EventSourcedEntity, "cart")) ->
        Edge("cart", "rc-events", "events"),
      remoteConsumer("rc-state", remoteEntity(ComponentKind.KeyValueEntity, "profile")) ->
        Edge("profile", "rc-state", "state"),
      remoteConsumer("rc-topic", RemoteSource.Topic("orders")) ->
        Edge("topic:orders", "rc-topic", "topic-subscription")
    )
    for (reader, edge) <- readers do
      assertEquals(read(render(Seq(cart, profile, reader))).declared, Vector(edge), reader.toString)
  }

  test("a consumer that publishes is connected to both topics, and each topic is a node") {
    for shipper <- Vector(
        consumer("shipper", topic("orders"), publishesTo = Some("shipments")),
        remoteConsumer("shipper", RemoteSource.Topic("orders"), publishesTo = Some("shipments"))
      )
    do
      val document = read(render(Seq(shipper)))
      assertEquals(
        document.declared,
        Vector(
          Edge("shipper", "topic:shipments", "topic-publication"),
          Edge("topic:orders", "shipper", "topic-subscription")
        )
      )
      assertEquals(
        document.nodes.filter(_.kind == "Topic").map(n => n.id -> n.layer),
        Vector("topic:orders" -> 4, "topic:shipments" -> 4)
      )
  }

  test("a topic two components use is one node") {
    val document = read(
      render(Seq(view("levels", topic("stock")), consumer("notifier", topic("stock"))))
    )
    assertEquals(document.nodes.count(_.id == "topic:stock"), 1)
    assertEquals(document.declared.size, 2)
  }

  test("a source that is not registered is a node outside the service, never a local one") {
    val document = read(render(Seq(view("orders-by-customer", events("order")))))
    assertEquals(document.declared, Vector(Edge("external:order", "orders-by-customer", "events")))
    val outside = document.nodes.find(_.id == "external:order").getOrElse(fail(document.toString))
    assertEquals((outside.kind, outside.layer), ("ExternalComponent", 2))
    assert(!document.nodes.exists(_.id == "order"), "nothing is invented as this service's")
  }

  test("a source is the entity of the kind that was named, not another component of that id") {
    // The state of `cart` is asked for, and `cart` here keeps events: it is not the source.
    val document = read(render(Seq(cart, view("carts", state("cart")))))
    assertEquals(document.declared, Vector(Edge("external:cart", "carts", "state")))
  }

  test("every source and every destination is exactly one connection, and nothing else is") {
    val components = Seq(
      cart,
      profile,
      descriptor("checkout", ComponentKind.Workflow),
      view("carts-by-customer", events("cart")),
      view("profiles-by-city", state("profile")),
      consumer("recorder", events("cart")),
      consumer("shipper", topic("orders"), publishesTo = Some("shipments")),
      remoteView("stock-levels", RemoteSource.Topic("stock"))
    )
    // Five readers and one publisher.
    assertEquals(read(render(components)).declared.size, 6)
  }

  test("a remote source with no change stream is no connection: startup refuses that service") {
    val document = read(
      render(
        Seq(
          descriptor("checkout", ComponentKind.Workflow),
          remoteView("odd", remoteEntity(ComponentKind.Workflow, "checkout"))
        )
      )
    )
    assertEquals(document.declared, Vector.empty)
  }

  test("an entity and the view over it may share an id, and are then named with their kinds") {
    val document = read(render(Seq(cart, view("cart", events("cart")))))
    assertEquals(
      document.nodes.map(_.id).toSet,
      Set("eventsourcedentity:cart", "view:cart")
    )
    assertEquals(
      document.declared,
      Vector(Edge("eventsourcedentity:cart", "view:cart", "events"))
    )
  }

  test("connections are in one order, whatever order the service was registered in") {
    val components = Vector(
      cart,
      profile,
      view("b-view", events("cart")),
      view("a-view", events("cart")),
      consumer("shipper", topic("orders"), publishesTo = Some("shipments"))
    )
    assertEquals(render(components), render(components.reverse))
    assertEquals(
      read(render(components)).declared.map(e => (e.from, e.to)),
      Vector(
        "cart"         -> "a-view",
        "cart"         -> "b-view",
        "shipper"      -> "topic:shipments",
        "topic:orders" -> "shipper"
      )
    )
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
      declared: Vector[Edge],
      calls: Vector[String]
  )
  final case class Edge(from: String, to: String, kind: String)

  given JsonValueCodec[Document] = Codecs.make[Document]
