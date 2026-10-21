package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.mcp.McpServer
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{Acl, Caller, Callers, HttpServer, LocalCallers}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** `support`'s agent, which lists the sibling service `tickets`'s MCP endpoint as an MCP server. */
final class SupportAgent extends Agent:
  def ask(question: String): Effect[String] =
    effects.systemMessage("You help with tickets.").userMessage(question).thenReply()

object SupportAgent extends Agent.Companion[SupportAgent](ComponentId("support-agent")):
  override def mcpServers: Vector[McpServer] = Vector(
    McpServer.service("tickets", "tickets", "/mcp")
  )
  def create(context: AgentContext) = new SupportAgent
  val ask                           = command("ask")(_.ask)

/**
 * The two scenarios of `features/agents/mcp-servers.feature` whose MCP server is a service's MCP
 * endpoint: "the platform connects to an MCP server that is a service as the agent's service" and
 * "an agent is offered the tools of a sibling service's MCP endpoint by listing the service". The
 * platform's client, feature 029's, against the platform's server, this feature's.
 *
 * Locally a call between services carries no identity: the service client removes every `X-Ankka-`
 * header it is given (`OutboundHeaders`), and with no certificate every caller is `Caller.Local`,
 * which every ACL admits. So here the call is shown to reach the endpoint through the service
 * client and to arrive as a local call, and the ACL is shown to refuse another named service; that
 * the endpoint is told `support`, from its certificate, is `McpEndpointsClusterFeatures`'s.
 */
class SiblingAgentSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val callers = ConcurrentLinkedQueue[(String, Caller)]()

  /** `tickets`'s MCP endpoint, admitting only `support`. */
  private final class TicketsMcp extends McpEndpoint("/mcp"):
    val acl: Acl = Acl.allowCallers(Callers.service("support"))

    val create = tool("create")
      .describedAs("Opens a ticket")
      .param[String]("title", "What the ticket is about")
      .handle { (title: String) =>
        callers.add("create" -> caller)
        s"opened '$title'"
      }

    val search = tool("search").describedAs("Finds tickets").handle { () =>
      callers.add("search" -> caller)
      "2 tickets found"
    }

  private val model                     = TestModelProvider()
  private var tickets: AnkkaTestKit     = null
  private var support: AnkkaTestKit     = null
  private var ticketsServer: HttpServer = null

  private def asService(name: String) = LocalCallers.header(Caller.Service("local", name))._2

  override def beforeAll(): Unit =
    ticketsServer = HttpServer.at("127.0.0.1", 0)(_ => TicketsMcp())
    tickets = AnkkaTestKit.start(Nil, Seq(ticketsServer))
    val address = s"http://127.0.0.1:${ticketsServer.boundPort.get}"
    support = AnkkaTestKit.start(
      Seq(SupportAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(
        AgentRuntime.withDefaultModel(model).withVariables(Map.empty[String, String].get)
      ),
      localServices = Map("tickets" -> address)
    )

  override def afterAll(): Unit =
    if support != null then support.stop()
    if tickets != null then tickets.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    callers.clear()

  private def ask(session: String) =
    support.componentClient.forAgent(SessionId(session)).ask(SupportAgent.ask).invoke("help me")

  test("the platform connects to an MCP server that is a service as the agent's service") {
    model.expectText("Hello."): Unit
    ask("s-offered"): Unit
    val offered = model.requests.head.tools.map(t => t.name -> t).toMap
    assert(
      Set("mcp__tickets__create", "mcp__tickets__search").subsetOf(offered.keySet),
      offered.keySet
    )
    assertEquals(offered("mcp__tickets__create").description, "Opens a ticket")
    assertEquals(
      offered("mcp__tickets__create")
        .inputSchema("properties")
        .flatMap(_("title"))
        .flatMap(_("type")),
      Some(Json.str("string"))
    )
    // And the endpoint's ACL is what admitted it: another service is refused.
    val refused = java.net.http.HttpClient
      .newHttpClient()
      .send(
        java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"http://127.0.0.1:${ticketsServer.boundPort.get}/mcp"))
          .header("Content-Type", "application/json")
          .header(LocalCallers.Header, asService("intruder"))
          .POST(
            java.net.http.HttpRequest.BodyPublishers
              .ofString("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
          )
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(refused.statusCode(), 403)
  }

  test("an agent is offered the tools of a sibling service's MCP endpoint by listing the service") {
    model.expectToolCall(
      "mcp__tickets__create",
      Json.obj("title" -> Json.str("printer")),
      "c-1"
    ): Unit
    model.expectText("Opened."): Unit
    ask("s-call"): Unit
    val ran = callers.asScala.toVector
    assertEquals(ran.map(_._1), Vector("create"))
    assertEquals(ran.head._2, Caller.Local, "a local call, which carries no identity")
    val results = model.lastRequest.messages.collect { case ChatMessage.ToolResults(r) =>
      r
    }.flatten
    assertEquals(results.map(r => (r.content, r.isError)), Vector(("opened 'printer'", false)))
  }
