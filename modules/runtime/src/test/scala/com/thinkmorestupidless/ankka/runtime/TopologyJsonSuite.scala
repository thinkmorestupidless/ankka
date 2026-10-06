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
import com.thinkmorestupidless.ankka.runtime
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
      name: String = "cart",
      counted: Counted = Counted()
  ): String =
    TopologyJson.render(
      name,
      "48213",
      Started,
      ComponentRegistry.fromOrThrow(components),
      routes,
      counted.counts.snapshot(counted.now),
      counted.names.nameOf
    )

  private def read(json: String): Document = readFromString[Document](json)

  test("a service with nothing registered is a document with nothing in it, not an error") {
    val document = read(render(Nil))
    assertEquals(document.service, Service("cart", document.service.runtime, "48213", Started))
    assertEquals(document.nodes, Vector.empty)
    assertEquals(document.declared, Vector.empty)
    assertEquals(document.calls, Vector.empty)
    assertEquals(document.window, Window(600, Started, 0, 0))
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

  test("a socket route is one of its endpoint's handlers, beside the endpoint's other routes") {
    val routes = Vector(
      ServedRoute("GET", "/notices", streaming = false, "endpoint:/notices"),
      ServedRoute("SOCKET", "/notices/stream", streaming = true, "endpoint:/notices")
    )
    val nodes = read(render(Nil, routes)).nodes
    assertEquals(nodes.map(_.id), Vector("endpoint:/notices"))
    assertEquals(
      nodes.head.handlers,
      Vector(
        Handler("GET /notices", "route", Some(false)),
        Handler("SOCKET /notices/stream", "route", Some(true))
      )
    )
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
    ChangeSource.Topic(
      name,
      Serializers.string,
      Some(com.thinkmorestupidless.ankka.sdk.StartFrom.Earliest)
    )

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

  // ── observed calls ───────────────────────────────────────────────────────────

  private val shoppingCart = Vector(
    descriptor(
      "shopping-cart",
      ComponentKind.EventSourcedEntity,
      Vector(
        DeclaredHandler("add-item", HandlerKind.Command),
        DeclaredHandler("get-cart", HandlerKind.Query)
      )
    )
  )
  private val cartRoutes = Vector(
    ServedRoute("POST", "/carts/{cartId}/items", streaming = false, "endpoint:/carts"),
    ServedRoute("GET", "/carts/{cartId}", streaming = false, "endpoint:/carts")
  )

  test("the pairs of handlers between two nodes are one observed call, in order") {
    val counted = Counted()
    counted.handled("endpoint:/carts", "POST /carts/{cartId}/items", "shopping-cart", "add-item")
    counted.handled("endpoint:/carts", "GET /carts/{cartId}", "shopping-cart", "get-cart")
    counted.handled("endpoint:/carts", "GET /carts/{cartId}", "shopping-cart", "get-cart")

    val document = read(render(shoppingCart, cartRoutes, counted = counted))
    assertEquals(
      document.calls.map(c => (c.from, c.to)),
      Vector("endpoint:/carts" -> "shopping-cart")
    )
    assertEquals(
      document.calls.head.pairs.map(p => (p.caller, p.callee, p.handled.ok)),
      Vector(
        ("GET /carts/{cartId}", "get-cart", 2L),
        ("POST /carts/{cartId}/items", "add-item", 1L)
      )
    )
    assertEquals(document.window.calls, 3L)
    assert(!document.nodes.exists(_.id == "unknown"), "nobody was unknown")
  }

  test("a call counts by how its handler ended, and a refusal is not a failure") {
    val counted = Counted()
    val from    = ("endpoint:/carts", "POST /carts/{cartId}/items")
    counted.handled(from._1, from._2, "shopping-cart", "add-item", SpanOutcome.Ok)
    counted.handled(from._1, from._2, "shopping-cart", "add-item", SpanOutcome.Refused)
    counted.handled(from._1, from._2, "shopping-cart", "add-item", SpanOutcome.Failed)
    counted.handled(from._1, from._2, "shopping-cart", "add-item", SpanOutcome.Failed)

    val pair = read(render(shoppingCart, cartRoutes, counted = counted)).calls.head.pairs.head
    assertEquals(pair.handled, Handled(ok = 1, refused = 1, failed = 2))
    assertEquals(pair.unanswered, Unanswered(timedOut = 0, undelivered = 0))
  }

  test("handled and unanswered are two counts of the same calls, and nothing adds them up") {
    val counted = Counted()
    val from    = ("endpoint:/carts", "POST /carts/{cartId}/items")
    // A handler that threw: failed where it ran, and timed out where it was called from.
    counted.handled(from._1, from._2, "shopping-cart", "add-item", SpanOutcome.Failed)
    counted.unanswered(from._1, from._2, "shopping-cart", "add-item", runtime.Unanswered.TimedOut)

    val json     = render(shoppingCart, cartRoutes, counted = counted)
    val document = read(json)
    val pair     = document.calls.head.pairs.head
    assertEquals(pair.handled, Handled(0, 0, 1))
    assertEquals(pair.unanswered, Unanswered(timedOut = 1, undelivered = 0))
    assertEquals(document.window, Window(600, Started, calls = 1, unanswered = 1))
    assert(
      !json.contains("\"total\""),
      "there is one call here, and no number in the document says two"
    )
  }

  test("a call from nobody is from the unknown caller, a node that is there only when it is used") {
    val counted = Counted()
    counted.handled(CallCounts.UnknownOrigin, CallCounts.UnknownOrigin, "shopping-cart", "add-item")

    val document = read(render(shoppingCart, cartRoutes, counted = counted))
    assertEquals(
      document.nodes.find(_.id == "unknown"),
      Some(Node("unknown", "UnknownCaller", 0, platform = false, Vector.empty))
    )
    assertEquals(document.calls.map(c => (c.from, c.to)), Vector("unknown" -> "shopping-cart"))
    assertEquals(document.calls.head.pairs.map(_.callee), Vector("add-item"))
  }

  test("a caller that is no node of this service is the unknown caller, not the nearest one") {
    val counted = Counted()
    counted.handled("endpoint:/orders", "GET /orders/{id}", "shopping-cart", "get-cart")

    val document = read(render(shoppingCart, cartRoutes, counted = counted))
    assertEquals(document.calls.map(c => (c.from, c.to)), Vector("unknown" -> "shopping-cart"))
  }

  test("a handler nobody declared is shown as undeclared, never by the name that was sent") {
    val counted = Counted()
    counted.unanswered(
      "endpoint:/carts",
      "GET /carts/{cartId}",
      "shopping-cart",
      CallCounts.Undeclared,
      runtime.Unanswered.Undelivered
    )

    val pair = read(render(shoppingCart, cartRoutes, counted = counted)).calls.head.pairs.head
    assertEquals(pair.callee, "(undeclared)")
    assertEquals(pair.unanswered, Unanswered(timedOut = 0, undelivered = 1))
    assertEquals(pair.handled, Handled(0, 0, 0))
  }

  test("another service is a node one layer after whatever calls it, and the rest are one node") {
    val counted = Counted()
    counted.handled("shopping-cart", "add-item", "service:checkout/pricing", "POST /prices")
    counted.handled("shopping-cart", "add-item", "service:(other)", "(other)")

    val document = read(render(shoppingCart, cartRoutes, counted = counted))
    assertEquals(
      document.nodes.filter(_.kind == "ExternalService").map(n => (n.id, n.layer)),
      Vector("service:(other)" -> 3, "service:checkout/pricing" -> 3)
    )
  }

  test("when an entity and its view share an id, the handler says which one a call is to") {
    val components = Vector(
      descriptor(
        "cart",
        ComponentKind.EventSourcedEntity,
        Vector(DeclaredHandler("get-cart", HandlerKind.Query))
      ),
      descriptor(
        "cart",
        ComponentKind.View,
        Vector(DeclaredHandler("on-change", HandlerKind.Update))
      )
    )
    val counted = Counted()
    counted.handled("endpoint:/carts", "GET /carts/{cartId}", "cart", "get-cart")
    counted.handled("endpoint:/carts", "GET /carts/{cartId}", "cart", "where")
    counted.handled("cart", "on-change", "cart", "get-cart")

    val document = read(render(components, cartRoutes, counted = counted))
    assertEquals(
      document.calls.map(c => (c.from, c.to)),
      Vector(
        "endpoint:/carts" -> "eventsourcedentity:cart",
        "endpoint:/carts" -> "view:cart",
        "view:cart"       -> "eventsourcedentity:cart"
      )
    )
  }

  test("durations are read from the histogram, and say so") {
    val counted = Counted()
    val from    = ("endpoint:/carts", "GET /carts/{cartId}")
    // Ninety-nine calls of about a millisecond and one of about a second.
    (1 to 99).foreach(_ =>
      counted.handled(from._1, from._2, "shopping-cart", "get-cart", nanos = 1_500_000L)
    )
    counted.handled(from._1, from._2, "shopping-cart", "get-cart", nanos = 1_500_000_000L)

    val pair = read(render(shoppingCart, cartRoutes, counted = counted)).calls.head.pairs.head
    assertEquals(pair.histogram.size, CallCounts.HistogramBuckets)
    assertEquals(pair.histogram.sum, 100L)
    // 1.5 ms is in the bucket [1, 2) ms, whose upper edge is 2; 1.5 s is in [1.024, 2.048) s.
    assertEquals(pair.durationMillis, Duration(p50 = 2.0, p99 = 2.0, max = 2048.0, bucketed = true))
  }

  test("a stream is one call, and marked as a stream") {
    val counted = Counted()
    counted.handled(
      "endpoint:/carts",
      "GET /carts/{cartId}",
      "shopping-cart",
      "get-cart",
      streaming = true
    )
    val pair = read(render(shoppingCart, cartRoutes, counted = counted)).calls.head.pairs.head
    assertEquals(pair.handled.ok, 1L)
    assert(pair.streaming)
  }

  test(
    "the window says how far back it reaches: to the start, until the service is older than it"
  ) {
    val young = Counted()
    assertEquals(read(render(shoppingCart, cartRoutes, counted = young)).window.since, Started)

    val old = Counted(now = StartedMillis + 3_600_000L)
    assertEquals(
      read(render(shoppingCart, cartRoutes, counted = old)).window.since,
      java.time.Instant.ofEpochMilli(StartedMillis + 3_000_000L).toString
    )
  }

  test("a call that left the window is not in the document") {
    val counted = Counted()
    counted.handled("endpoint:/carts", "GET /carts/{cartId}", "shopping-cart", "get-cart")
    val later = counted.copy(now = counted.now + 601_000L)
    assertEquals(read(render(shoppingCart, cartRoutes, counted = later)).calls, Vector.empty)
    assertEquals(read(render(shoppingCart, cartRoutes, counted = later)).window.calls, 0L)
  }

object TopologyJsonSuite:

  private val Started       = "2026-10-01T09:12:03Z"
  private val StartedMillis = java.time.Instant.parse(Started).toEpochMilli

  /** Calls counted as the hosts count them, at a time the test chooses. */
  final case class Counted(
      now: Long = StartedMillis + 5_000L,
      names: Names = new Names,
      counts: CallCounts = CallCounts(600_000L, 60, StartedMillis)
  ):
    private def key(from: String, caller: String, to: String, callee: String): Long =
      CallCounts
        .key(names.intern(from), names.intern(caller), names.intern(to), names.intern(callee))
        .get

    def handled(
        from: String,
        caller: String,
        to: String,
        callee: String,
        outcome: SpanOutcome = SpanOutcome.Ok,
        nanos: Long = 1_000_000L,
        streaming: Boolean = false
    ): Unit = counts.handled(key(from, caller, to, callee), outcome, nanos, streaming, now)

    def unanswered(
        from: String,
        caller: String,
        to: String,
        callee: String,
        kind: runtime.Unanswered
    ): Unit = counts.unanswered(key(from, caller, to, callee), kind, now)

  // The document as a reader models it. A field the renderer drops or renames fails to decode.
  final case class Service(name: String, runtime: String, instance: String, startedAt: String)
  final case class Window(seconds: Long, since: String, calls: Long, unanswered: Long)
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
      calls: Vector[Call]
  )
  final case class Edge(from: String, to: String, kind: String)
  final case class Call(from: String, to: String, pairs: Vector[Pair])
  final case class Pair(
      caller: String,
      callee: String,
      handled: Handled,
      unanswered: Unanswered,
      durationMillis: Duration,
      histogram: Vector[Long],
      streaming: Boolean
  )
  final case class Handled(ok: Long, refused: Long, failed: Long)
  final case class Unanswered(timedOut: Long, undelivered: Long)
  final case class Duration(p50: Double, p99: Double, max: Double, bucketed: Boolean)

  given JsonValueCodec[Document] = Codecs.make[Document]
