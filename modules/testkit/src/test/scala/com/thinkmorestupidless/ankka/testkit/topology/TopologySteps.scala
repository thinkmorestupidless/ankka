package com.thinkmorestupidless.ankka.testkit.topology

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, TestModelProvider, forAgent}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{
  InMemoryBroker,
  Observability,
  ProjectionRuntime,
  RecordedSpan,
  RuntimeExtension,
  SpanOutcome,
  TimerRuntime
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.ConfigFactory

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The steps the topology features share, run against a real service.
 *
 * A `Given` only describes the service: nothing is started until something is done to it, and then
 * exactly the components the scenario named are hosted on `AnkkaTestKit`. What a scenario does, it
 * does as a developer's service would have it done: a request over HTTP to an endpoint whose
 * handler calls a component, a timer that comes due, a workflow that is started. A `Then` reads the
 * topology the way the local console does, over HTTP from the service's own endpoint, and one about
 * a count waits for that count: a call is counted where it ends, which may be after its caller has
 * an answer. So a scenario passes when a developer would see what it says, and a step with no
 * definition here fails its scenario: a feature this suite runs cannot hold a scenario nothing
 * tests.
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
  private var components = Vector.empty[ComponentDescriptor]
  private var entities   = Map.empty[String, EventSourcedKit]
  private var workflows  = Map.empty[String, WorkflowKit]
  private var actions    = Map.empty[String, TimedActionKit]
  private var agents     = Map.empty[String, AgentKit]
  private var readers    = Map.empty[String, String]
  private var planned    = Vector.empty[Planned]
  private var settings   = Map.empty[String, String]
  private var others     = Vector.empty[String]

  // The other services a scenario's endpoint calls, all answering at one address.
  private var callee: Option[com.sun.net.httpserver.HttpServer] = None

  // The service, once something has been done to it.
  private var kit: Option[AnkkaTestKit]    = None
  private var server: Option[HttpServer]   = None
  private var timers: Option[TimerRuntime] = None
  private var model                        = TestModelProvider()

  // What the scenario has done and seen.
  private var read: Option[Reading]           = None
  private var about: Option[(String, String)] = None
  private var quantity                        = 1
  private var instances                       = Vector.empty[String]
  private var timer: Option[String]           = None
  private var restartedAt: Option[Instant]    = None
  private var stepSpan: Option[RecordedSpan]  = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    components = Vector.empty
    entities = Map.empty
    workflows = Map.empty
    actions = Map.empty
    agents = Map.empty
    readers = Map.empty
    planned = Vector.empty
    others = Vector.empty
    // A scenario about the window passing cannot wait ten minutes for it. The window is the
    // service's configuration, read when it starts, so it is decided by which scenario this is.
    settings =
      if context.test.name.contains("leaves the window") then
        Map(
          "ankka.observability.call-window"  -> s"${ShortWindow.toSeconds}s",
          "ankka.observability.call-buckets" -> ShortWindow.toSeconds.toString
        )
      else Map.empty
    model = TestModelProvider()
    read = None
    about = None
    quantity = 1
    instances = Vector.empty
    timer = None
    restartedAt = None
    stepSpan = None

  override def afterEach(context: AfterEach): Unit =
    try kit.foreach(_.stop())
    finally
      kit = None
      server = None
      timers = None
      callee.foreach(_.stop(0))
      callee = None
      settings.keys.foreach(sys.props.remove)
      ConfigFactory.invalidateCaches()
      super.afterEach(context)

  // ── the service a scenario describes ────────────────────────────────────────

  private def add(descriptor: ComponentDescriptor): Unit =
    assert(kit.isEmpty, "a scenario describes its service before anything is done to it")
    components :+= descriptor

  private def entity(id: String, manner: Manner = Manner.Answers): EventSourcedKit =
    val made = EventSourcedKit(id, manner)
    entities += id -> made
    add(made.descriptor)
    made

  private def plan(route: String, serve: EndpointClients => KitEndpoint.Serve): Unit =
    assert(kit.isEmpty, "a scenario describes its service before anything is done to it")
    planned :+= Planned(route, serve)

  /** A caller that waits less long than the service's own ten seconds, for a scenario about it. */
  private def impatient(): Unit = settings += "ankka.ask-timeout" -> s"${Patience.toSeconds}s"

  /**
   * The other services the scenario's endpoint calls, by name. Each is another process as far as
   * this service is concerned: one plain HTTP server on loopback answers for all of them, and the
   * service finds each by its name, as it finds a service announced on this machine. The endpoint
   * gets one route that calls whichever service its path names.
   */
  private def callsServices(names: String*): Unit =
    val server = callee.getOrElse {
      val started =
        com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      started.createContext(
        "/",
        exchange =>
          val body = "hello".getBytes("UTF-8")
          exchange.sendResponseHeaders(200, body.length.toLong)
          exchange.getResponseBody.write(body)
          exchange.close()
      )
      started.start()
      callee = Some(started)
      plan(
        ServicesRoute,
        clients => KitEndpoint.Serve.Answer(args => clients.services(args.head).getText("/hello"))
      )
      started
    }
    names.foreach { name =>
      settings += s"ankka.local-services.$name" -> s"http://127.0.0.1:${server.getAddress.getPort}"
    }
    others ++= names

  private def callService(name: String): Unit =
    assertEquals(request(ServicesRoute, name), "hello")

  /** An id no other call in this run uses, and one a `Then` can look for and not find. */
  private def instance(): String =
    val id = s"instance-${java.util.UUID.randomUUID().toString.take(8)}"
    instances :+= id
    id

  /** Hosts what the scenario described, once, the first time something is done to the service. */
  private def service: AnkkaTestKit =
    kit.getOrElse {
      settings.foreach((key, value) => sys.props.put(key, value))
      ConfigFactory.invalidateCaches()

      val broker = InMemoryBroker()
      val endpoints: Vector[EndpointClients => HttpEndpoint] = planned
        .map(p => KitEndpoint.split(p.route) -> p)
        .groupBy { case ((_, prefix, _), _) => prefix }
        .toVector
        .sortBy((prefix, _) => prefix)
        .map { (prefix, served) => (clients: EndpointClients) =>
          KitEndpoint(
            prefix,
            served.map { case ((method, _, template), p) =>
              KitEndpoint.Route(method, template, p.serve(clients))
            }
          )
        }
      // Loopback and an ephemeral port: these suites run beside a service on 9000.
      server = Option.when(endpoints.nonEmpty)(HttpServer.at("127.0.0.1", 0)(endpoints*))
      timers = Option.when(actions.nonEmpty)(TimerRuntime(pollInterval = 200.millis))
      val agentRuntime = Option.when(agents.nonEmpty)(AgentRuntime.withDefaultModel(model))
      val extensions: Seq[RuntimeExtension] =
        ProjectionRuntime.withBroker(broker, broker) +:
          (server.toSeq ++ timers.toSeq ++ agentRuntime.toSeq)
      // An agent needs the platform's own components, and only a scenario with one gets them.
      val hosted  = components ++ (if agents.nonEmpty then AgentRuntime.descriptors else Nil)
      val started = AnkkaTestKit.start(hosted, extensions)
      kit = Some(started)
      started
    }

  private def observability: Observability = Observability(service.service.system)

  // ── doing things to the service ─────────────────────────────────────────────

  /** A request to one of the service's routes, with its path parameter filled in. */
  private def request(route: String, parameter: String): String =
    val (method, path) = route.split(" ", 2) match
      case Array(method, path) => (method, path.replaceAll("\\{[^}]+\\}", parameter))
      case _                   => fail(s"'$route' is not a method and a path")
    val started = service
    val port = server.flatMap(_.boundPort).getOrElse(fail("the scenario's service has no endpoint"))
    val _    = started
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, s"$route: ${response.body}")
    response.body

  /**
   * What a route does when it calls a handler of a component: the call an endpoint's handler makes,
   * by name, so a scenario can name a handler the component does not have. The answer is the reply
   * or how the call was refused; a route does not fail because the call it made did.
   */
  private def calling(component: String, handler: String)(
      clients: EndpointClients
  ): KitEndpoint.Serve =
    KitEndpoint.Serve.Answer(args => call(clients, component, args.head, handler))

  private def call(
      clients: EndpointClients,
      component: String,
      id: String,
      handler: String
  ): String =
    try
      val reply = Await.result(
        clients.componentClient.transportRef.ask(
          ComponentId(component),
          EntityId(id),
          MethodName(handler),
          Serializers.int.toBytes(quantity),
          Metadata.empty
        ),
        1.minute
      )
      String(reply, "UTF-8")
    catch case refused: CommandError => refused.code.toString

  /** The route an endpoint calls a component by when the scenario names no route of its own. */
  private def callsRoute(component: String): String = s"POST /calls/$component/{id}"
  private def streamRoute(agent: String): String    = s"GET /streams/$agent/{session}"
  private def startsRoute(workflow: String): String = s"POST /starts/$workflow/{id}"
  private def outsideRoute(entity: String): String  = s"POST /outside/$entity/{id}"

  private def streaming(agent: String)(clients: EndpointClients): KitEndpoint.Serve =
    KitEndpoint.Serve.Stream(args =>
      clients.componentClient
        .forAgent(SessionId(args.head))
        .stream(agents(agent).ask)("What is the weather?")
    )

  /** A handler that hands the call to a thread of its own making, and waits for it. */
  private def fromAnotherThread(entity: String)(clients: EndpointClients): KitEndpoint.Serve =
    KitEndpoint.Serve.Answer { args =>
      var answer = ""
      val other  = Thread(() => answer = call(clients, entity, args.head, "add-item"))
      other.start()
      other.join()
      answer
    }

  private def calledOutsideAnyHandler(named: String*): Unit =
    named.foreach(entity => plan(outsideRoute(entity), fromAnotherThread(entity)))
    named.foreach(entity => assertEquals(request(outsideRoute(entity), instance()), "1"))

  // ── reading the topology ────────────────────────────────────────────────────

  private def topology: Reading =
    read.getOrElse(fail("the scenario has not read the topology yet"))

  private def readTopology(): Reading =
    val address = service.service.observabilityAddress
      .getOrElse(fail("the service has no local console endpoint to read"))
    val response = http.send(
      HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body)
    val reading = Reading(readFromString[Document](response.body), response.body)
    read = Some(reading)
    reading

  /**
   * Reads the topology until it shows what the step says, and fails with what it shows if it never
   * does. The wait is for the thing asserted, and for nothing weaker.
   */
  private def eventually(what: String)(shown: Reading => Boolean): Reading =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var last     = readTopology()
    while !shown(last) && System.nanoTime() < deadline do
      Thread.sleep(200)
      last = readTopology()
    if !shown(last) then fail(s"the topology never showed $what: ${last.raw}")
    last

  /**
   * The node a scenario means by a name. A scenario says "cart" or "orders", never an id: a
   * component is named by its id, a topic and a component outside the service by theirs.
   */
  private def nodeNamed(name: String): Node =
    val candidates = Vector(name, s"topic:$name", s"external:$name", serviceNode(name))
    topology.document.nodes.filter(n => candidates.contains(n.id)) match
      case Vector(one) => one
      case Vector() =>
        fail(s"the topology shows nothing called '$name': ${topology.document.nodes.map(_.id)}")
      case several => fail(s"'$name' could be any of ${several.map(_.id)}")

  private def shows(name: String, kind: String): Unit =
    assertEquals(nodeNamed(name).kind, kind, s"'$name' in ${topology.document.nodes}")

  private def connected(from: String, to: String, kind: String): Unit =
    val edge = Edge(nodeNamed(from).id, nodeNamed(to).id, kind)
    assert(
      topology.document.declared.contains(edge),
      s"no $edge among ${topology.document.declared}"
    )

  /** The scenario's one endpoint, as the topology names it. */
  private def endpointOf(reading: Reading): Option[String] =
    reading.document.nodes.filter(_.kind == "Endpoint") match
      case Vector(one) => Some(one.id)
      case Vector()    => None
      case several => fail(s"the scenario means one endpoint, and there are ${several.map(_.id)}")

  private def callBetween(reading: Reading, from: String, to: String): Option[Call] =
    reading.document.calls.find(c => c.from == from && c.to == to)

  /** Waits for an observed call between two nodes, and makes it the one the scenario is about. */
  private def observed(from: Reading => Option[String], to: String, what: String)(
      holds: Call => Boolean
  ): Call =
    val reading = eventually(what) { r =>
      from(r).flatMap(callBetween(r, _, to)).exists(holds)
    }
    val origin = from(reading).get
    about = Some(origin -> to)
    callBetween(reading, origin, to).get

  /** Waits for something to be true of the observed call the scenario is about. */
  private def theObservedCall(what: String)(holds: Call => Boolean): Call =
    val (from, to) = about.getOrElse(fail("the scenario has not said which observed call it means"))
    val reading = eventually(s"an observed call from $from to $to $what")(
      callBetween(_, from, to).exists(holds)
    )
    callBetween(reading, from, to).get

  private def handled(call: Call, how: String): Long = how match
    case "ok"      => call.pairs.map(_.handled.ok).sum
    case "refused" => call.pairs.map(_.handled.refused).sum
    case "failed"  => call.pairs.map(_.handled.failed).sum
    case other     => fail(s"a handled call ends ok, refused or failed, not '$other'")

  private val anyEndpoint: Reading => Option[String]         = endpointOf
  private def named(node: String): Reading => Option[String] = _ => Some(node)

  // ── reading traces ──────────────────────────────────────────────────────────

  private def nameOf(ref: Int): String = observability.names.nameOf(ref).getOrElse("?")

  private def spans: Vector[RecordedSpan] = observability.recorder.snapshot()

  /** Waits for a span of this component, and of this handler when one is named. */
  private def span(component: String, handler: Option[String] = None)(
      also: RecordedSpan => Boolean = _ => true
  ): RecordedSpan =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    def find: Option[RecordedSpan] = spans.find { s =>
      nameOf(s.componentRef) == component &&
      handler.forall(_ == nameOf(s.handlerRef)) && also(s)
    }
    var found = find
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(200)
      found = find
    found.getOrElse(
      fail(
        s"no span of $component${handler.fold("")(h => s"#$h")} among " +
          spans.map(s => s"${nameOf(s.componentRef)}#${nameOf(s.handlerRef)}").distinct
      )
    )

  /** That a span's trace began with a request: the endpoint's span is in it, at its root. */
  private def inTheTraceOfARequest(of: RecordedSpan): Unit =
    val trace = spans.filter(_.traceId == of.traceId)
    assert(
      trace.exists(s => nameOf(s.componentRef) == "http" && s.parentSpanId == 0L),
      s"no request in the trace: ${trace.map(s => nameOf(s.componentRef))}"
    )

  // ── Given: components ────────────────────────────────────────────────────────

  Given("a service with an event sourced entity {string}") { (id: String) =>
    val _ = entity(id)
  }

  Given("a service with an event sourced entity {string} and no endpoint") { (id: String) =>
    val _ = entity(id)
    assertEquals(planned, Vector.empty, "the scenario's service serves no route")
  }

  Given("a service with an event sourced entity {string} and an event sourced entity {string}") {
    (first: String, second: String) =>
      val _ = entity(first)
      val _ = entity(second)
  }

  Given(
    "a service with an event sourced entity {string} whose command {string} refuses a quantity of 0"
  ) { (id: String, command: String) =>
    assertEquals(command, "add-item", "the kit's entity has the one command")
    val _ = entity(id)
  }

  Given("a service with an event sourced entity {string} whose command {string} fails") {
    (id: String, command: String) =>
      assertEquals(command, "add-item", "the kit's entity has the one command")
      val _ = entity(id, Manner.Fails)
      // A handler that throws sends no reply, so its caller waits out its patience.
      impatient()
  }

  Given(
    "a service with an event sourced entity {string} whose command {string} takes longer than its caller waits"
  ) { (id: String, command: String) =>
    assertEquals(command, "add-item", "the kit's entity has the one command")
    val _ = entity(id, Manner.Slow(Patience + 1500.millis))
    impatient()
  }

  Given("a service with an event sourced entity {string} that declares no handler {string}") {
    (id: String, handler: String) =>
      val made = entity(id)
      assert(
        !made.descriptor.declaredHandlers.exists(_.name == handler),
        s"'$id' declares '$handler': ${made.descriptor.declaredHandlers}"
      )
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

  Given("a consumer {string} that reads the events of {string}") { (id: String, entity: String) =>
    readers += id -> entity
    add(ConsumerKit(id, Kit.eventsOf(entity), None).descriptor)
  }

  Given(
    "a service with a consumer {string} that reads the topic {string} and publishes to the topic {string}"
  ) { (id: String, reads: String, publishes: String) =>
    add(ConsumerKit(id, Kit.topic(reads), Some(publishes)).descriptor)
  }

  Given("a workflow {string} whose step {string} calls {string} and {string}") {
    (id: String, step: String, first: String, second: String) =>
      val made = WorkflowKit(id, step, Vector(entities(first), entities(second)))
      workflows += id -> made
      add(made.descriptor)
  }

  Given("a workflow {string} whose step {string} calls {string}") {
    (id: String, step: String, called: String) =>
      val made = WorkflowKit(id, step, Vector(entities(called)))
      workflows += id -> made
      add(made.descriptor)
  }

  Given("a service with a timed action {string} that calls the event sourced entity {string}") {
    (id: String, called: String) =>
      val made = TimedActionKit(id, entity(called))
      actions += id -> made
      add(made.descriptor)
  }

  Given("a service with an agent {string}") { (id: String) =>
    val made = AgentKit(id)
    agents += id -> made
    add(made.descriptor)
  }

  Given("a service with an agent {string} whose handler {string} answers as a stream") {
    (id: String, handler: String) =>
      assertEquals(handler, "ask", "the kit's agent has the one handler")
      val made = AgentKit(id)
      agents += id -> made
      add(made.descriptor)
  }

  Given("a service {string} with an endpoint that calls the service {string}") {
    (_: String, other: String) => callsServices(other)
  }

  Given("a service {string} that shows at most {int} other services by name") {
    (_: String, limit: Int) =>
      settings += "ankka.observability.max-external-services" -> limit.toString
  }

  Given(
    "an endpoint that calls the service {string}, the service {string} and the service {string}"
  )((a: String, b: String, c: String) => callsServices(a, b, c))

  Given("the service has no component {string}") { (id: String) =>
    assert(!components.exists(_.componentId.toString == id), s"'$id' was registered: $components")
  }

  // Nothing is sent to a service unless a step sends it, so this is true of every scenario that
  // says it; the step is there so the scenario says so, and to hold the kit to it.
  Given("the service has handled nothing since it started") { () =>
    assert(kit.isEmpty, "the service was started, and may have handled something")
  }

  // ── Given: endpoints ─────────────────────────────────────────────────────────

  Given("a service with an endpoint that serves the route {string} and no other component") {
    (route: String) =>
      assertEquals(components, Vector.empty, "the scenario's service has no component")
      plan(route, _ => KitEndpoint.Serve.Answer(_ => "ok"))
  }

  Given("an endpoint whose route {string} calls the command {string} of {string}") {
    (route: String, command: String, component: String) => plan(route, calling(component, command))
  }

  Given("an endpoint that calls the command {string} of {string}") {
    (command: String, component: String) => plan(callsRoute(component), calling(component, command))
  }

  Given("an endpoint that calls the handler {string} of {string}") {
    (handler: String, component: String) =>
      if agents.contains(component) then plan(streamRoute(component), streaming(component))
      else plan(callsRoute(component), calling(component, handler))
  }

  Given("an endpoint that calls {string} for one route and {string} for another") {
    (first: String, second: String) =>
      plan(callsRoute(first), calling(first, "add-item"))
      plan(callsRoute(second), calling(second, "add-item"))
  }

  Given("an endpoint that calls {string}") { (component: String) =>
    if agents.contains(component) then plan(streamRoute(component), streaming(component))
    else plan(callsRoute(component), calling(component, "add-item"))
  }

  Given("an endpoint that starts {string}") { (workflow: String) =>
    val starts = workflows(workflow)
    plan(
      startsRoute(workflow),
      clients =>
        KitEndpoint.Serve.Answer { args =>
          clients.componentClient
            .forWorkflow(EntityId(args.head))
            .call(starts.start)
            .invoke(args.head)
            .toString
        }
    )
  }

  // The call is made, and counted, before the scenario goes on: what follows is about a call the
  // topology has already shown.
  Given("an endpoint that has called {string} {int} time(s)") { (component: String, times: Int) =>
    plan(callsRoute(component), calling(component, "add-item"))
    val id = instance()
    (1 to times).foreach(_ => request(callsRoute(component), id))
    val _ =
      observed(anyEndpoint, component, s"$times calls to $component")(handled(_, "ok") == times)
  }

  Given("a timer set for {string}") { (action: String) =>
    val started = service
    val name    = s"timer-${instance()}"
    val _       = started
    timers
      .getOrElse(fail("the scenario's service has no timed action"))
      .timerScheduler
      .createSingleTimer(name, 300.millis, actions(action).remind.deferred(instance()))
    timer = Some(name)
  }

  // ── When ─────────────────────────────────────────────────────────────────────

  When("a developer reads the service's topology") { () =>
    val _ = readTopology()
  }

  When("the service handles {int} request(s) to {string}") { (times: Int, route: String) =>
    val id = instance()
    (1 to times).foreach(_ => request(route, id))
  }

  When("the service handles {int} requests to {string}, each for a different entity id") {
    (times: Int, route: String) => (1 to times).foreach(_ => request(route, instance()))
  }

  When("the service handles {int} request to the route that calls {string}") {
    (times: Int, component: String) =>
      val id = instance()
      (1 to times).foreach(_ => request(callsRoute(component), id))
  }

  When("the endpoint calls {string} with a quantity of {int}") { (handler: String, of: Int) =>
    quantity = of
    val route = planned.map(_.route).filter(_.startsWith("POST /calls/")) match
      case Vector(one) => one
      case other       => fail(s"the scenario's endpoint calls one component: $other")
    val _ = (handler, request(route, instance()))
  }

  // What the endpoint calls is a handler of a component, or another service when the scenario
  // said it calls one by that name.
  When("the endpoint calls {string}") { (named: String) =>
    if others.contains(named) then callService(named)
    else
      val route = planned.map(_.route).filter(_.startsWith("POST /calls/")) match
        case Vector(one) => one
        case other       => fail(s"the scenario's endpoint calls one component: $other")
      val _ = (named, request(route, instance()))
  }

  When("the endpoint calls {string}, then {string}, then {string}") {
    (a: String, b: String, c: String) => Vector(a, b, c).foreach(callService)
  }

  When("the endpoint calls {string} and the stream ends") { (handler: String) =>
    assertEquals(handler, "ask", "the kit's agent has the one handler")
    model.expectText("It is sunny in Berlin today")
    // The response is read to its end, which is the stream's.
    val body = request(streamRoute(agents.keys.head), instance())
    assert(body.contains("sunny"), body)
  }

  When("{string} runs the step {string}") { (workflow: String, step: String) =>
    val started = workflows(workflow)
    assertEquals(started.only.name.toString, step, "the kit's workflow has the one step")
    val id = instance()
    val _  = service.componentClient.forWorkflow(EntityId(id)).call(started.start).invoke(id)
  }

  When("{string} is called from outside any handler") { (entity: String) =>
    calledOutsideAnyHandler(entity)
  }

  When("{string} and {string} are called from outside any handler") {
    (first: String, second: String) => calledOutsideAnyHandler(first, second)
  }

  When("the timer fires") { () =>
    val name      = timer.getOrElse(fail("the scenario set no timer"))
    val scheduler = timers.get.timerScheduler
    val deadline  = System.nanoTime() + 30.seconds.toNanos
    while scheduler.exists(name) && System.nanoTime() < deadline do Thread.sleep(100)
    assert(!scheduler.exists(name), "the timer never fired")
  }

  When("the window passes with no other call to {string}") { (_: String) =>
    // The whole window, and the slice of it the call's own bucket may still be in.
    Thread.sleep((ShortWindow + 1500.millis).toMillis)
  }

  When("the service restarts") { () =>
    restartedAt = Some(Instant.now())
    service.restartService()
  }

  When("a developer reads the state of {string} through the local console") { (entity: String) =>
    val address = service.service.observabilityAddress
      .getOrElse(fail("the service has no local console endpoint to read"))
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(s"$address/observability/query/$entity/${instance()}/get-cart"))
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body)
  }

  When("the service handles {int} request that runs the step {string}") {
    (times: Int, step: String) =>
      val (id, started) = workflows.head
      assertEquals(started.only.name.toString, step, "the kit's workflow has the one step")
      (1 to times).foreach(_ => request(startsRoute(id), instance()))
  }

  When("the service handles {int} request that {string} answers") { (times: Int, agent: String) =>
    (1 to times).foreach { _ =>
      model.expectText("It is sunny in Berlin today")
      val _ = request(streamRoute(agent), instance())
    }
  }

  When("{string} handles {int} event") { (consumer: String, events: Int) =>
    val source = entities(readers(consumer))
    val id     = instance()
    val _      = service
    (1 to events).foreach { _ =>
      service.componentClient.forEventSourcedEntity(EntityId(id)).call(source.addItem).invoke(1)
    }
  }

  // ── Then: what a service is made of ──────────────────────────────────────────

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
    "the topology shows a declared connection from {string} to {string} as a workflow subscription"
  )((from: String, to: String) => connected(from, to, "workflow"))

  Then(
    "the topology shows a declared connection from {string} to {string} as a topic subscription"
  )((from: String, to: String) => connected(from, to, "topic-subscription"))

  Then(
    "the topology shows a declared connection from {string} to {string} as a topic publication"
  )((from: String, to: String) => connected(from, to, "topic-publication"))

  Then("the topology shows no declared connection") { () =>
    assertEquals(topology.document.declared, Vector.empty)
  }

  Then("the topology shows the endpoint with the route {string}") { (route: String) =>
    topology.document.nodes.filter(_.kind == "Endpoint") match
      case Vector(endpoint) =>
        assertEquals(endpoint.handlers.map(_.name), Vector(route), endpoint.toString)
      case other => fail(s"one endpoint was expected: $other")
  }

  Then("the topology shows no endpoint") { () =>
    assertEquals(topology.document.nodes.filter(_.kind == "Endpoint"), Vector.empty)
  }

  // ── Then: observed calls ─────────────────────────────────────────────────────

  Then("the topology shows an observed call from the endpoint to {string}") { (to: String) =>
    val _ = observed(anyEndpoint, to, s"an observed call from the endpoint to $to")(_ => true)
  }

  Then("the topology shows {int} observed call from the endpoint to {string}") {
    (count: Int, to: String) =>
      val call = observed(anyEndpoint, to, s"an observed call from the endpoint to $to")(_ => true)
      assertEquals(topology.document.calls.count(_.to == to), count, topology.raw)
      assertEquals(call.pairs.size, 1, "one route calling one handler is one pair")
  }

  Then("the topology shows an observed call from {string} to {string}") {
    (from: String, to: String) =>
      val _ = observed(named(from), to, s"an observed call from $from to $to")(_ => true)
  }

  Then("the topology shows an observed call from {string} to {string} from the step {string}") {
    (from: String, to: String, step: String) =>
      val call = observed(named(from), to, s"an observed call from $from to $to by $step")(
        _.pairs.exists(_.caller == step)
      )
      assertEquals(call.pairs.map(_.caller).distinct, Vector(step), "and from nothing else")
  }

  Then("the topology shows an observed call from the unknown caller to {string}") { (to: String) =>
    val _ =
      observed(named(Unknown), to, s"an observed call from the unknown caller to $to")(_ => true)
  }

  // ── Then: other services ─────────────────────────────────────────────────────

  Then("the topology of {string} shows {string} as a service outside it") {
    (_: String, name: String) =>
      val _ = eventually(s"$name as a service outside it")(
        _.document.nodes.exists(_.id == serviceNode(name))
      )
      shows(name, "ExternalService")
  }

  Then("the topology of {string} shows {string} and {string} as services outside it") {
    (_: String, a: String, b: String) =>
      Vector(a, b).foreach { name =>
        val _ = eventually(s"$name as a service outside it")(
          _.document.nodes.exists(_.id == serviceNode(name))
        )
        shows(name, "ExternalService")
      }
  }

  Then("the topology of {string} shows an observed call from the endpoint to {string}") {
    (_: String, to: String) =>
      val _ = observed(anyEndpoint, serviceNode(to), s"an observed call from the endpoint to $to")(
        _ => true
      )
  }

  Then("the topology of {string} shows an observed call from the endpoint to other services") {
    (_: String) =>
      val _ = observed(anyEndpoint, OtherServices, "an observed call to other services")(_ => true)
  }

  Then("the topology of {string} shows {int} calls from the endpoint in all") {
    (_: String, count: Int) =>
      // Handled and unanswered, counted apart and added here only to say that none was dropped.
      val _ = eventually(s"$count calls from the endpoint in all") { reading =>
        endpointOf(reading).exists { endpoint =>
          reading.document.calls
            .filter(_.from == endpoint)
            .flatMap(_.pairs)
            .map(p =>
              p.handled.ok + p.handled.refused + p.handled.failed +
                p.unanswered.timedOut + p.unanswered.undelivered
            )
            .sum == count
        }
      }
  }

  Then("the topology shows no observed call from a component to {string}") { (to: String) =>
    assertEquals(
      readTopology().document.calls.filter(_.to == to).map(_.from),
      Vector(Unknown),
      topology.raw
    )
  }

  Then("the topology shows no observed call to {string}") { (to: String) =>
    assertEquals(readTopology().document.calls.filter(_.to == to), Vector.empty, topology.raw)
  }

  Then("the topology shows no observed call") { () =>
    assertEquals(readTopology().document.calls, Vector.empty, topology.raw)
  }

  Then("the observed call is from the route {string} to the handler {string}") {
    (route: String, handler: String) =>
      val call = theObservedCall(s"from $route to $handler")(
        _.pairs.exists(p => p.caller == route && p.callee == handler)
      )
      assertEquals(call.pairs.size, 1, call.toString)
  }

  Then("the observed call is handled {int} time(s), as {word}") { (times: Int, how: String) =>
    val _ = theObservedCall(s"handled $times times as $how")(handled(_, how) == times)
  }

  Then("the observed call from the endpoint to {string} is handled {int} time(s), as {word}") {
    (to: String, times: Int, how: String) =>
      val _ = observed(anyEndpoint, to, s"a call to $to handled $times times as $how")(
        handled(_, how) == times
      )
  }

  Then("the observed call is unanswered {int} time(s), as timed out") { (times: Int) =>
    val _ =
      theObservedCall(s"timed out $times times")(_.pairs.map(_.unanswered.timedOut).sum == times)
  }

  Then(
    "the observed call from the endpoint to {string} is unanswered {int} time(s), as timed out"
  ) { (to: String, times: Int) =>
    val _ = observed(anyEndpoint, to, s"a call to $to timed out $times times")(
      _.pairs.map(_.unanswered.timedOut).sum == times
    )
  }

  Then(
    "the observed call from the endpoint to {string} is unanswered {int} time(s), as undelivered"
  ) { (to: String, times: Int) =>
    val _ = observed(anyEndpoint, to, s"a call to $to undelivered $times times")(
      _.pairs.map(_.unanswered.undelivered).sum == times
    )
  }

  // What a console marks an observed call as failing by: a handler that failed, or a call nothing
  // answered. A refusal is a handler doing its job, and is neither.
  Then("the observed call is not marked as failing") { () =>
    val call = theObservedCall("at all")(_ => true)
    assertEquals(handled(call, "failed"), 0L, call.toString)
    assertEquals(call.pairs.map(p => p.unanswered.timedOut + p.unanswered.undelivered).sum, 0L)
  }

  Then("the topology shows the handled count and the unanswered count apart") { () =>
    val call = theObservedCall("with both counts")(c =>
      handled(c, "failed") == 1 && c.pairs.map(_.unanswered.timedOut).sum == 1
    )
    assertEquals(call.pairs.size, 1, "one call, seen from both ends, is one pair")
    // One call was made. It is in each total once, and no number anywhere says two.
    assertEquals(topology.document.window.calls, 1L, topology.raw)
    assertEquals(topology.document.window.unanswered, 1L, topology.raw)
  }

  Then("the observed call is to an undeclared handler") { () =>
    val call = theObservedCall("to an undeclared handler")(_ => true)
    assertEquals(call.pairs.map(_.callee), Vector("(undeclared)"))
  }

  Then("the observed call is marked as a stream") { () =>
    val _ = theObservedCall("marked as a stream")(_.pairs.exists(_.streaming))
  }

  Then("the topology shows no handler {string}") { (handler: String) =>
    assert(!readTopology().raw.contains(handler), topology.raw)
  }

  Then("the topology shows no entity id") { () =>
    val reading = readTopology()
    assert(instances.sizeIs > 1, "the scenario used more than one entity id")
    instances.foreach(id => assert(!reading.raw.contains(id), s"$id is in ${reading.raw}"))
  }

  Then("the topology shows no unknown caller") { () =>
    assertEquals(readTopology().document.nodes.filter(_.kind == "UnknownCaller"), Vector.empty)
  }

  Then("the topology shows {int} unknown caller") { (count: Int) =>
    val _ = eventually(s"$count unknown caller")(
      _.document.nodes.count(_.kind == "UnknownCaller") == count
    )
  }

  Then(
    "the topology says that its observed calls are the calls made in the window, not every call the service can make"
  ) { () =>
    // It says so by saying what its window is and how many calls were made in it, which are the
    // calls it shows and no others.
    val window = topology.document.window
    assert(window.seconds > 0, topology.raw)
    assertEquals(
      window.calls,
      topology.document.calls
        .flatMap(_.pairs)
        .map(p => p.handled.ok + p.handled.refused + p.handled.failed)
        .sum,
      topology.raw
    )
  }

  Then("the topology says how long its window is") { () =>
    assertEquals(topology.document.window.seconds, ShortWindow.toSeconds, topology.raw)
  }

  Then("the topology says that its observed calls are counted since the service started") { () =>
    val restarted = restartedAt.getOrElse(fail("the scenario did not restart the service"))
    val window    = topology.document.window
    val since     = Instant.parse(window.since)
    assert(!since.isBefore(restarted.minusSeconds(1)), s"since $since, restarted $restarted")
    assert(
      since.isAfter(Instant.now().minusSeconds(window.seconds)),
      "the window is longer than the service has been running, and says where it starts"
    )
  }

  // ── Then: traces ─────────────────────────────────────────────────────────────

  Then("the trace of the request shows the step {string} of {string}") {
    (step: String, workflow: String) =>
      val found = span(workflow, Some(step))()
      assertEquals(found.outcome, SpanOutcome.Ok)
      inTheTraceOfARequest(found)
      stepSpan = Some(found)
  }

  Then("the trace shows the call to {string} inside the step {string}") {
    (entity: String, step: String) =>
      val inside = stepSpan.getOrElse(fail(s"the scenario has not found the step '$step'"))
      val _      = span(entity)(s => s.traceId == inside.traceId && s.parentSpanId == inside.spanId)
  }

  Then("the trace of the request shows {string}") { (component: String) =>
    inTheTraceOfARequest(span(component)())
  }

  Then("the trace of the event shows {string}") { (consumer: String) =>
    assertEquals(span(consumer, Some("on-message"))().outcome, SpanOutcome.Ok)
  }

object TopologySteps:

  /** Who a call is from when nobody can say. */
  private val Unknown = "unknown"

  /** The route a scenario's endpoint calls another service by. */
  private val ServicesRoute = "GET /services/{name}"

  /** Another service as the topology names it: locally, every service is in the project `local`. */
  private def serviceNode(name: String): String = s"service:local/$name"

  /** Every other service beyond the limit, as the topology names them together. */
  private val OtherServices = "service:(other)"

  /** The window a scenario about the window passing runs with, a slice of it a second long. */
  private val ShortWindow: FiniteDuration = 3.seconds

  /** How long a caller waits in a scenario about a call nobody answers in time. */
  private val Patience: FiniteDuration = 2.seconds

  /**
   * A route a scenario's endpoint serves, and what serving it does once the service has clients.
   */
  final case class Planned(route: String, serve: EndpointClients => KitEndpoint.Serve)

  /** The topology as it was read: as a reader models it, and as it was written. */
  final case class Reading(document: Document, raw: String)

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
  final case class Handled(ok: Long, refused: Long, failed: Long)
  final case class Unanswered(timedOut: Long, undelivered: Long)
  final case class Pair(
      caller: String,
      callee: String,
      handled: Handled,
      unanswered: Unanswered,
      streaming: Boolean
  )
  final case class Call(from: String, to: String, pairs: Vector[Pair])
  final case class Window(seconds: Long, since: String, calls: Long, unanswered: Long)
  final case class Document(
      window: Window,
      nodes: Vector[Node],
      declared: Vector[Edge],
      calls: Vector[Call]
  )

  given JsonValueCodec[Document] = Codecs.make[Document]
