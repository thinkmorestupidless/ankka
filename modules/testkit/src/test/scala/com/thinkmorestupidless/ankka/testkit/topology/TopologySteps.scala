package com.thinkmorestupidless.ankka.testkit.topology

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentDescriptor}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, RuntimeExtension}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/**
 * The steps the topology features share, run against a real service.
 *
 * A `Given` only describes the service: nothing is started until a scenario's first `When`, which
 * hosts exactly the components the scenario named on `AnkkaTestKit`. A `Then` reads the topology
 * the way the local console does, over HTTP from the service's own endpoint. So a scenario passes
 * when a developer would see what it says, and a step with no definition here fails its scenario: a
 * feature this suite runs cannot hold a scenario nothing tests.
 *
 * @param feature
 *   one feature file, relative to `modules/testkit`, where a forked test runs: the repository's
 *   features are two levels up.
 */
abstract class TopologySteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  import TopologySteps.*

  override val munitTimeout: Duration = 5.minutes

  private val http = HttpClient.newHttpClient()

  // What the scenario has said about the service, until it is started.
  private var components                = Vector.empty[ComponentDescriptor]
  private var routes                    = Vector.empty[String]
  private var kit: Option[AnkkaTestKit] = None
  private var read: Option[Document]    = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    components = Vector.empty
    routes = Vector.empty
    read = None

  override def afterEach(context: AfterEach): Unit =
    try kit.foreach(_.stop())
    finally
      kit = None
      super.afterEach(context)

  // ── the service a scenario describes ────────────────────────────────────────

  private def add(descriptor: ComponentDescriptor): Unit =
    assert(kit.isEmpty, "a scenario describes its service before anything is done to it")
    components :+= descriptor

  /** Hosts what the scenario described, once, the first time something is done to the service. */
  private def service: AnkkaTestKit =
    kit.getOrElse {
      val broker = InMemoryBroker()
      val endpoints = routes
        .map(KitEndpoint.split)
        .groupBy((_, prefix, _) => prefix)
        .toVector
        .sortBy((prefix, _) => prefix)
        .map { (prefix, served) =>
          KitEndpoint(
            prefix,
            served.map((method, _, template) => KitEndpoint.Route(method, template, _ => "ok"))
          )
        }
      val extensions: Seq[RuntimeExtension] =
        ProjectionRuntime.withBroker(broker, broker) +:
          (if endpoints.isEmpty then Nil
           else
             // Loopback and an ephemeral port: these suites run beside a service on 9000.
             val served = endpoints.map(endpoint => (_: Any) => endpoint)
             Seq(HttpServer.at("127.0.0.1", 0)(served*)))
      val started = AnkkaTestKit.start(components, extensions)
      kit = Some(started)
      started
    }

  private def topology: Document =
    read.getOrElse(fail("the scenario has not read the topology yet"))

  private def readTopology(): Document =
    val address = service.service.observabilityAddress
      .getOrElse(fail("the service has no local console endpoint to read"))
    val response = http.send(
      HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body)
    readFromString[Document](response.body)

  /**
   * The node a scenario means by a name. A scenario says "cart" or "orders", never an id: a
   * component is named by its id, a topic and a component outside the service by theirs.
   */
  private def nodeNamed(name: String): Node =
    val candidates = Vector(name, s"topic:$name", s"external:$name")
    topology.nodes.filter(n => candidates.contains(n.id)) match
      case Vector(one) => one
      case Vector() =>
        fail(s"the topology shows nothing called '$name': ${topology.nodes.map(_.id)}")
      case several => fail(s"'$name' could be any of ${several.map(_.id)}")

  private def shows(name: String, kind: String): Unit =
    assertEquals(nodeNamed(name).kind, kind, s"'$name' in ${topology.nodes}")

  private def connected(from: String, to: String, kind: String): Unit =
    val edge = Edge(nodeNamed(from).id, nodeNamed(to).id, kind)
    assert(topology.declared.contains(edge), s"no $edge among ${topology.declared}")

  // ── Given ────────────────────────────────────────────────────────────────────

  Given("a service with an event sourced entity {string}") { (id: String) =>
    add(EventSourcedKit(id).descriptor)
  }

  Given("a service with an event sourced entity {string} and no endpoint") { (id: String) =>
    add(EventSourcedKit(id).descriptor)
    assertEquals(routes, Vector.empty, "the scenario's service serves no route")
  }

  Given("a service with a key value entity {string}") { (id: String) =>
    add(KeyValueKit(id).descriptor)
  }

  Given("a view {string} that reads the events of {string}") { (id: String, entity: String) =>
    add(ViewKit(id, Kit.eventsOf(entity)).descriptor)
  }

  Given("a service with a view {string} that reads the events of {string}") {
    (id: String, entity: String) => add(ViewKit(id, Kit.eventsOf(entity)).descriptor)
  }

  Given("a view {string} that reads the state of {string}") { (id: String, entity: String) =>
    add(ViewKit(id, Kit.stateOf(entity)).descriptor)
  }

  Given(
    "a service with a consumer {string} that reads the topic {string} and publishes to the topic {string}"
  ) { (id: String, reads: String, publishes: String) =>
    add(ConsumerKit(id, Kit.topic(reads), Some(publishes)).descriptor)
  }

  Given("a service with an endpoint that serves the route {string} and no other component") {
    (route: String) =>
      assertEquals(components, Vector.empty, "the scenario's service has no component")
      routes :+= route
  }

  Given("the service has no component {string}") { (id: String) =>
    assert(!components.exists(_.componentId.toString == id), s"'$id' was registered: $components")
  }

  // Nothing is sent to a service unless a step sends it, so this is true of every scenario that
  // says it; the step is there so the scenario says so, and to hold the kit to it.
  Given("the service has handled nothing since it started") { () =>
    assert(kit.isEmpty, "the service was started, and may have handled something")
  }

  // ── When ─────────────────────────────────────────────────────────────────────

  When("a developer reads the service's topology") { () => read = Some(readTopology()) }

  // ── Then ─────────────────────────────────────────────────────────────────────

  Then("the topology shows {string} as an event sourced entity") { (name: String) =>
    shows(name, "EventSourcedEntity")
  }

  Then("the topology shows {string} as a view")((name: String) => shows(name, "View"))

  Then("the topology shows {string} as a consumer")((name: String) => shows(name, "Consumer"))

  Then("the topology shows {string} as a component outside the service") { (name: String) =>
    assertEquals(nodeNamed(name).id, s"external:$name", "it is not one of the service's own")
    shows(name, "ExternalComponent")
  }

  Then("the topology shows the topic {string} and the topic {string}") {
    (first: String, second: String) =>
      shows(first, "Topic")
      shows(second, "Topic")
  }

  Then(
    "the topology shows a declared connection from {string} to {string} as an event subscription"
  )((from: String, to: String) => connected(from, to, "events"))

  Then(
    "the topology shows a declared connection from {string} to {string} as a state subscription"
  )((from: String, to: String) => connected(from, to, "state"))

  Then(
    "the topology shows a declared connection from {string} to {string} as a topic subscription"
  )((from: String, to: String) => connected(from, to, "topic-subscription"))

  Then(
    "the topology shows a declared connection from {string} to {string} as a topic publication"
  )((from: String, to: String) => connected(from, to, "topic-publication"))

  Then("the topology shows no declared connection") { () =>
    assertEquals(topology.declared, Vector.empty)
  }

  Then("the topology shows no observed call")(() => assertEquals(topology.calls, Vector.empty))

  Then("the topology shows the endpoint with the route {string}") { (route: String) =>
    topology.nodes.filter(_.kind == "Endpoint") match
      case Vector(endpoint) =>
        assertEquals(endpoint.handlers.map(_.name), Vector(route), endpoint.toString)
      case other => fail(s"one endpoint was expected: $other")
  }

  Then("the topology shows no endpoint") { () =>
    assertEquals(topology.nodes.filter(_.kind == "Endpoint"), Vector.empty)
  }

object TopologySteps:

  // The document as the console reads it. A field the service stops sending fails to decode.
  final case class Handler(name: String, `type`: String, streaming: Option[Boolean] = None)
  final case class Node(
      id: String,
      kind: String,
      layer: Int,
      platform: Boolean,
      handlers: Vector[Handler]
  )
  final case class Edge(from: String, to: String, kind: String)
  final case class Call(from: String, to: String)
  final case class Document(nodes: Vector[Node], declared: Vector[Edge], calls: Vector[Call])

  given JsonValueCodec[Document] = Codecs.make[Document]
