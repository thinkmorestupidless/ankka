package com.thinkmorestupidless.ankka.testkit.topology

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  InMemoryBroker,
  Observability,
  ProjectionRuntime
}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import munit.FunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** An agent whose tool calls an entity, as a developer's would: through the agent's own client. */
final class StockAgent(context: AgentContext) extends Agent:

  private val checkStock = FunctionTool
    .named("check_stock")
    .describedAs("How many of an item are in stock.")
    .param[String]("item", "The item's id.")
    .handle { (item: String) =>
      context.componentClient
        .forEventSourcedEntity(EntityId(item))
        .call(TopologySuite.stock.getCart)
        .invoke()
        .toString
    }

  def ask(question: String): Effect[String] =
    effects
      .systemMessage("Answer from the stock.")
      .userMessage(question)
      .tools(checkStock)
      .thenReply()

object StockAgent extends Agent.Companion[StockAgent](ComponentId("stock-agent")):
  def create(context: AgentContext) = new StockAgent(context)
  val ask                           = command("ask")(_.ask)

/** A consumer that calls an entity for each event it is given. */
final class Auditor(context: ConsumerContext) extends Consumer[Noted, String]:
  def onMessage(message: Noted): Effect =
    val _ = context.componentClient
      .forEventSourcedEntity(EntityId("audit"))
      .call(TopologySuite.ledger.addItem)
      .invoke(1)
    effects.ignore()

object Auditor
    extends Consumer.Companion[Auditor, Noted, String](
      componentId = ComponentId("auditor"),
      source = Kit.eventsOf("stock")
    ):
  def create(context: ConsumerContext) = new Auditor(context)

/**
 * What a topology says about calls, for the cases no scenario states: a call made by a tool, a
 * query of a view, a call made by a consumer, an origin somebody forwarded, and that none of it
 * grows with the number of things a service is asked about.
 *
 * One real service, driven over HTTP, and read as the local console reads it.
 */
final class TopologySuite extends FunSuite with LogCapturing:

  import TopologySteps.{Call, Document, Pair, given}
  import TopologySuite.*

  override val munitTimeout: Duration = 5.minutes

  private val http  = HttpClient.newHttpClient()
  private val model = TestModelProvider()

  private var kit: AnkkaTestKit  = null
  private var server: HttpServer = null

  override def beforeAll(): Unit =
    val broker = InMemoryBroker()
    val endpoint = (clients: EndpointClients) =>
      KitEndpoint(
        "/t",
        Vector(
          KitEndpoint.Route("POST", "/stock/{id}", answer(id => add(clients, stock, id))),
          KitEndpoint.Route("GET", "/stock/{id}", answer(id => read(clients, id))),
          KitEndpoint.Route("GET", "/rows/{id}", answer(id => rows(clients).get(id).toString)),
          KitEndpoint.Route("GET", "/every/{id}", answer(_ => rows(clients).all().size.toString)),
          KitEndpoint.Route("POST", "/ask/{session}", answer(session => ask(clients, session)))
        )
      ): HttpEndpoint
    server = HttpServer.at("127.0.0.1", 0)(endpoint)
    kit = AnkkaTestKit.start(
      Seq(stock.descriptor, ledger.descriptor, stockRows.descriptor, Auditor.descriptor) ++
        Seq(StockAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime.withBroker(broker, broker),
        server,
        AgentRuntime.withDefaultModel(model)
      )
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private def answer(run: String => String): KitEndpoint.Serve =
    KitEndpoint.Serve.Answer(args => run(args.head))

  private def add(clients: EndpointClients, to: EventSourcedKit, id: String): String =
    clients.componentClient.forEventSourcedEntity(EntityId(id)).call(to.addItem).invoke(1).toString

  private def read(clients: EndpointClients, id: String): String =
    clients.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(stock.getCart)
      .invoke()
      .toString

  private def rows(clients: EndpointClients) = clients.viewClient.forView(stockRows)

  private def ask(clients: EndpointClients, session: String): String =
    clients.componentClient.forAgent(SessionId(session)).call(StockAgent.ask).invoke("Any pens?")

  private def request(method: String, path: String): String =
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}$path"))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body)
    response.body

  private def topology(): (Document, String) =
    val address = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))
    val response = http.send(
      HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body)
    (readFromString[Document](response.body), response.body)

  /** Waits for a pair of handlers to have been counted, and answers with it. */
  private def pair(from: String, to: String, caller: String, callee: String)(
      counted: Pair => Boolean = _.handled.ok >= 1
  ): Pair =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    def find: Option[Pair] =
      topology()._1.calls
        .filter(c => c.from == from && c.to == to)
        .flatMap(_.pairs)
        .find(p => p.caller == caller && p.callee == callee && counted(p))
    var found = find
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(200)
      found = find
    found.getOrElse(fail(s"no $from#$caller → $to#$callee in ${topology()._2}"))

  private def callsTo(to: String): Vector[Call] = topology()._1.calls.filter(_.to == to)

  test("a call a tool makes is the agent's call, from the handler that was asked") {
    model
      .expectToolCall("check_stock", Json.obj("item" -> Json.str("pens")))
      .expectText("There are none.")
    assertEquals(request("POST", "/t/ask/s-1"), "There are none.")

    val _ = pair("endpoint:/t", "stock-agent", "POST /t/ask/{session}", "ask")()
    val _ = pair("stock-agent", "stock", "ask", "get-cart")()
    // The tool ran after the handler had returned, on the loop's thread; it is still the agent's.
    assert(!callsTo("stock").exists(_.from == "unknown"), topology()._2)
  }

  test("what an agent keeps of a session is an observed call to a component of the platform") {
    model.expectText("None.")
    assertEquals(request("POST", "/t/ask/s-2"), "None.")

    val (document, raw) = topology()
    val memory = document.nodes
      .find(_.id == "ankka-session-memory")
      .getOrElse(fail(s"no session memory in $raw"))
    assert(memory.platform, "it is the platform's own, and marked so a reader can leave it out")
    val remembered = document.calls
      .filter(c => c.from == "stock-agent" && c.to == "ankka-session-memory")
      .flatMap(_.pairs)
    assert(remembered.nonEmpty && remembered.forall(_.caller == "ask"), raw)
  }

  test("a query of a view is a call to the view, by the way it was asked") {
    val _ = request("POST", "/t/stock/v-1")
    val _ = request("GET", "/t/rows/v-1")
    val _ = request("GET", "/t/every/v-1")

    val _ = pair("endpoint:/t", "stock-rows", "GET /t/rows/{id}", "get")()
    val _ = pair("endpoint:/t", "stock-rows", "GET /t/every/{id}", "where")()
  }

  test("a call a consumer makes is the consumer's, from the one handler it has") {
    val _ = request("POST", "/t/stock/c-1")
    val _ = pair("auditor", "ledger", "on-message", "add-item")()
    assert(!callsTo("ledger").exists(_.from == "unknown"), topology()._2)
  }

  test("a caller written into a call's metadata by hand is not believed") {
    // From a thread that is no handler's, naming a handler that exists: exactly what a forwarded
    // origin looks like. The call is from nobody, and is counted as from nobody.
    val forged = CallOrigin.into(Metadata.empty, CallOrigin("auditor", "on-message"))
    val _ = Await.result(
      kit.componentClient.transportRef.ask(
        ComponentId("ledger"),
        EntityId("forged"),
        MethodName("get-cart"),
        Array.emptyByteArray,
        forged
      ),
      30.seconds
    )
    val _ = pair("unknown", "ledger", "unknown", "get-cart")()
    assert(
      !callsTo("ledger")
        .flatMap(_.pairs)
        .exists(p => p.caller == "on-message" && p.callee == "get-cart"),
      topology()._2
    )
  }

  test("a request's span is still named for the route within its endpoint") {
    val _              = request("GET", "/t/stock/n-1")
    val observability  = Observability(kit.service.system)
    def name(ref: Int) = observability.names.nameOf(ref).getOrElse("?")
    val named = observability.recorder
      .snapshot()
      .map(s => name(s.componentRef) -> name(s.handlerRef))
    // What a metric's labels are made of, and what they were before a topology existed.
    assert(named.contains("http" -> "GET /stock/{id}"), named.distinct.toString)
    assert(named.contains("stock" -> "get-cart"), named.distinct.toString)
  }

  test("ten thousand entity ids are one observed call, and nothing grows with them") {
    val observability = Observability(kit.service.system)
    val _             = request("GET", "/t/stock/bound-0")
    val _             = pair("endpoint:/t", "stock", "GET /t/stock/{id}", "get-cart")()
    val pairs         = observability.calls.size
    val names         = observability.names.size

    val calls = 10_000
    (1 to calls).foreach(i => request("GET", s"/t/stock/bound-$i"))

    val counted =
      pair("endpoint:/t", "stock", "GET /t/stock/{id}", "get-cart")(_.handled.ok > calls)
    assert(counted.handled.ok > calls, counted.toString)
    // Every id went through the endpoint, the transport and the entity's host. None of them is a
    // name, so nothing that is kept for the life of the process is any bigger than it was.
    assertEquals(observability.calls.size, pairs, "pairs of handlers")
    assertEquals(observability.names.size, names, "names")
    val (_, raw) = topology()
    assert(!raw.contains("bound-"), "and no id is in the topology")
  }

object TopologySuite:
  val stock: EventSourcedKit    = EventSourcedKit("stock")
  val ledger: EventSourcedKit   = EventSourcedKit("ledger")
  val stockRows: ViewKit[Noted] = ViewKit("stock-rows", Kit.eventsOf("stock"))
