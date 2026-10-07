package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{Observability, Trace}
import com.typesafe.config.ConfigFactory

import com.sun.net.httpserver.HttpServer as JdkHttpServer
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

// docs:start service-call-tool
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
final class WalletAgent(context: AgentContext) extends Agent:

  private val readBalance = FunctionTool
    .named("read_balance")
    .describedAs("Reads a customer's wallet balance.")
    .param[String]("customer", "The customer whose balance to read.")
    .handle(customer => context.services("wallet").getText(s"/wallet/balances/$customer"))
  // docs:end service-call-tool

  private val readVault = FunctionTool
    .named("read_vault")
    .describedAs("Reads the vault, which admits nobody.")
    .handle(() => context.services("wallet").getText("/vault/secret"))

  def ask(question: String): Effect[String] =
    effects
      .systemMessage("You answer questions about wallets.")
      .userMessage(question)
      .tools(readBalance, readVault)
      .thenReply()

object WalletAgent extends Agent.Companion[WalletAgent](ComponentId("wallet-agent")):
  def create(context: AgentContext) = new WalletAgent(context)
  val ask                           = command("ask")(_.ask)

/**
 * A tool calling another service through the agent's context: the call reaches the service and its
 * answer reaches the model, a refusal the service makes reaches the model as the tool's error, and
 * the call is recorded inside the tool call that made it.
 *
 * The wallet here is a plain HTTP server on loopback, without TLS, found through
 * `ankka.local-services`, as on a developer's machine. That the call presents the agent's service's
 * certificate, and that a list of callers admits it by name, is the service client's own behaviour
 * — held by `ServiceClientSuite` for the same client, and on a cluster by `EndToEndClusterSuite`.
 */
class AgentServiceCallSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private val model                 = TestModelProvider()
  private val served                = ConcurrentLinkedQueue[String]()
  private var testKit: AnkkaTestKit = null
  private var wallet: JdkHttpServer = null

  /**
   * The wallet: balances are answered, and the vault is refused, as an ACL that denies all does.
   */
  private def startWallet(): JdkHttpServer =
    val server = JdkHttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/wallet/balances/",
      exchange =>
        val customer = exchange.getRequestURI.getPath.stripPrefix("/wallet/balances/")
        served.add(customer): Unit
        respond(exchange, 200, s"$customer has 40")
    ): Unit
    server.createContext("/vault/", exchange => respond(exchange, 403, "forbidden")): Unit
    server.start()
    server

  private def respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) =
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  override def beforeAll(): Unit =
    wallet = startWallet()
    System.setProperty(
      "ankka.local-services.wallet",
      s"http://127.0.0.1:${wallet.getAddress.getPort}"
    )
    ConfigFactory.invalidateCaches()
    testKit = AnkkaTestKit.start(
      Seq(WalletAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    if wallet != null then wallet.stop(0)
    System.clearProperty("ankka.local-services.wallet")
    ConfigFactory.invalidateCaches()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    served.clear()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private def resultsSent: Vector[ToolResult] =
    model.lastRequest.messages.collect { case ChatMessage.ToolResults(r) => r }.flatten

  test("a tool's call is admitted by an ACL that names the agent's service") {
    model.expectToolCall("read_balance", Json.obj("customer" -> Json.str("c-1")), "call-1"): Unit
    model.expectText("c-1 has 40 in their wallet."): Unit

    val answer = agent("s-balance").call(WalletAgent.ask).invoke("what is c-1's balance?")

    assertEquals(answer, "c-1 has 40 in their wallet.")
    assertEquals(served.toArray.toVector, Vector("c-1"), "the wallet was asked")
    assertEquals(resultsSent.map(r => (r.content, r.isError)), Vector(("c-1 has 40", false)))
  }

  test("a refusal by the called service reaches the model as the tool's error") {
    model.expectToolCall("read_vault", Json.obj(), "call-1"): Unit
    model.expectText("I am not allowed to read the vault."): Unit

    agent("s-vault").call(WalletAgent.ask).invoke("what is in the vault?"): Unit

    val result = resultsSent.head
    assert(result.isError, result.toString)
    assert(result.content.contains("403"), result.content)
  }

  test("a tool's call to another service is in the trace inside the tool call") {
    model.expectToolCall("read_balance", Json.obj("customer" -> Json.str("c-2")), "call-1"): Unit
    model.expectText("c-2 has 40."): Unit
    val recorder = Observability(testKit.service.system).recorder
    val names    = Observability(testKit.service.system).names

    // What an endpoint's request span is to the agent it calls.
    val traceId = Trace.mint()
    Trace.within(traceId, 4242L) {
      agent("s-trace").call(WalletAgent.ask).invoke("what is c-2's balance?"): Unit
    }

    val spans = recorder.spansOf(traceId)
    def named(s: com.thinkmorestupidless.ankka.runtime.RecordedSpan) =
      (names.nameOf(s.componentRef).getOrElse("?"), names.nameOf(s.handlerRef).getOrElse("?"))
    val handler = spans.find(s => named(s) == ("wallet-agent", "ask")).getOrElse(fail(s"$spans"))
    val tool = spans
      .find(s => named(s)._2 == "read_balance")
      .getOrElse(fail(spans.map(named).toString))
    assertEquals(tool.parentSpanId, handler.spanId, "the tool call is under the agent")
    val call = spans
      .find(_.parentSpanId == tool.spanId)
      .getOrElse(fail(s"nothing inside the tool call: ${spans.map(named)}"))
    assert(named(call)._1.contains("wallet"), named(call).toString)
    assertEquals(named(call)._2, "GET")
  }
