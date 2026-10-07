package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.{Json, TestMcpServer, ToolOrigin}
import com.thinkmorestupidless.ankka.sdk.ServiceClients

import scala.concurrent.duration.DurationInt

/**
 * An agent's MCP servers, connected as a service starts: the tools they give, and every refusal.
 */
class McpToolsSuite extends munit.FunSuite:

  private var servers = Vector.empty[TestMcpServer]

  override def afterEach(context: AfterEach): Unit =
    servers.foreach(_.stop())
    servers = Vector.empty

  private def tickets(): TestMcpServer =
    val s = TestMcpServer()
      .tool("create", "Opens a ticket")(_ => "opened")
      .tool("search", "Finds tickets")(_ => "found")
    servers = servers :+ s
    s

  private val settings = McpTools.Settings(2.seconds, 5.seconds, "test")

  private def connect(
      servers: Vector[McpServer],
      variables: Map[String, String],
      timers: Boolean = true
  ) =
    McpTools.connect("helper", servers, variables.get, ServiceClients.unavailable, timers, settings)

  private def refusal(body: => Any): String = intercept[IllegalStateException](body).getMessage

  test("each tool is offered under its server's name, as the server describes it") {
    val s     = tickets()
    val tools = connect(Vector(McpServer.named("tickets")), Map("ANKKA_MCP_TICKETS_URL" -> s.url))
    assertEquals(tools.map(_.name), Vector("mcp__tickets__create", "mcp__tickets__search"))
    assertEquals(tools.head.spec.description, "Opens a ticket")
    assertEquals(tools.head.origin, ToolOrigin.Mcp("tickets"))
    assertEquals(tools.head.invoke(Json.obj()), Right("opened"))
    assertEquals(s.calls.map(_._1), Vector("create"), "the server is asked by its own tool name")
  }

  test("a server's approval is every one of its tools'") {
    val s = tickets()
    val tools = connect(
      Vector(McpServer.named("tickets").requiresApproval),
      Map("ANKKA_MCP_TICKETS_URL" -> s.url)
    )
    assert(tools.forall(_.approval.isDefined), tools.map(_.approval).toString)
  }

  test("an MCP server's address is taken from a variable when one is set") {
    val s = tickets()
    // Port 1 answers nothing: were the definition's address used, the connection would fail.
    val tools = connect(
      Vector(McpServer.at("tickets", "http://127.0.0.1:1/mcp")),
      Map("ANKKA_MCP_TICKETS_URL" -> s.url)
    )
    assertEquals(tools.size, 2)
  }

  test("a service whose agent lists an MCP server with no address does not start") {
    val problem = refusal(connect(Vector(McpServer.named("search")), Map.empty))
    assert(problem.contains("ANKKA_MCP_SEARCH_URL"), problem)
    assert(problem.contains("'helper'"), problem)
  }

  test("a service whose agent takes a credential from a variable that is not set does not start") {
    val s = tickets()
    val problem = refusal(
      connect(
        Vector(McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN")),
        Map("ANKKA_MCP_TICKETS_URL" -> s.url)
      )
    )
    Vector("ANKKA_MCP_TICKETS_TOKEN", "'tickets'", "'helper'").foreach(word =>
      assert(problem.contains(word), problem)
    )
  }

  test("a service whose agent lists an MCP server that cannot be reached does not start") {
    val problem = refusal(
      connect(
        Vector(McpServer.named("tickets")),
        Map("ANKKA_MCP_TICKETS_URL" -> "http://127.0.0.1:1/mcp")
      )
    )
    assert(problem.contains("'tickets'") && problem.contains("'helper'"), problem)
  }

  test("a server that refuses the credential fails the start, naming the server") {
    val s = tickets().requireHeader("Authorization", "Bearer right")
    val problem = refusal(
      connect(
        Vector(McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN")),
        Map("ANKKA_MCP_TICKETS_URL" -> s.url, "ANKKA_MCP_TICKETS_TOKEN" -> "Bearer wrong")
      )
    )
    assert(problem.contains("'tickets'") && problem.contains("401"), problem)
    assert(!problem.contains("Bearer wrong"), "the credential is never in a message")
  }

  test("the platform sends an MCP server the credential its agent lists for it, and no other") {
    val s     = tickets()
    val other = tickets()
    val tools = connect(
      Vector(
        McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN"),
        McpServer.named("other")
      ),
      Map(
        "ANKKA_MCP_TICKETS_URL"   -> s.url,
        "ANKKA_MCP_TICKETS_TOKEN" -> "Bearer t-1",
        "ANKKA_MCP_OTHER_URL"     -> other.url
      )
    )
    tools.find(_.name == "mcp__tickets__create").get.invoke(Json.obj()): Unit
    assert(s.requests.forall(_.headers.get("authorization").contains("Bearer t-1")))
    assert(
      other.requests.forall(!_.headers.contains("authorization")),
      "no other server is sent it"
    )
  }

  Vector(false, true).foreach { eventStream =>
    test(
      s"an MCP server that is a service is reached through the service client (event stream: $eventStream)"
    ) {
      val s = tickets().answerAsEventStream(eventStream)
      // The service client a service has, finding `catalogue` as it does on a developer's machine.
      val services = com.thinkmorestupidless.ankka.runtime.HttpServiceClients(
        com.typesafe.config.ConfigFactory
          .parseString(s"""ankka.local-services.catalogue = "${s.url.stripSuffix("/mcp")}"""")
          .withFallback(com.typesafe.config.ConfigFactory.load()),
        None
      )
      val tools = McpTools.connect(
        "helper",
        Vector(McpServer.service("catalogue", "catalogue", "/mcp")),
        Map.empty[String, String].get,
        services,
        timersAvailable = true,
        settings
      )
      assertEquals(tools.map(_.name), Vector("mcp__catalogue__create", "mcp__catalogue__search"))
      assertEquals(tools.last.invoke(Json.obj()), Right("found"))
    }
  }

  test("a time limit for a server's approval needs timers") {
    val s = tickets()
    val problem = refusal(
      connect(
        Vector(McpServer.named("tickets").requiresApproval(1.minute)),
        Map("ANKKA_MCP_TICKETS_URL" -> s.url),
        timers = false
      )
    )
    assert(problem.contains("TimerRuntime"), problem)
  }

  test("a tool whose whole name a provider would refuse fails the start, naming it") {
    val long = "t" * 120
    val s    = TestMcpServer().tool(long, "Too long a name")(_ => "")
    servers = servers :+ s
    val problem =
      refusal(connect(Vector(McpServer.named("tickets")), Map("ANKKA_MCP_TICKETS_URL" -> s.url)))
    assert(problem.contains(long) && problem.contains("128"), problem)
  }
