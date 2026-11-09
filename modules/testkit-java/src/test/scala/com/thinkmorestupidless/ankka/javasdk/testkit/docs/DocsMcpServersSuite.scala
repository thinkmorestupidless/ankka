package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.TestMcpServer
import com.thinkmorestupidless.ankka.agent.javasdk.{AgentClients, Agents}
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/** The Java samples of the MCP servers page: an agent given a scripted server's tools. */
class DocsMcpServersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model                  = TestModelProvider()
  private var tickets: TestMcpServer = null
  private var kit: AnkkaTestKit      = null

  override def beforeAll(): Unit =
    tickets = TestMcpServer().tool("search", "Finds tickets")(_ => "2 tickets found")
    TicketAgent.ticketsUrl = tickets.url
    kit = AnkkaTestKit.start(
      Ankka
        .service("support")
        .register(classOf[TicketAgent])
        .withModule(Agents.module().withModelProvider(model))
    )

  override def afterAll(): Unit =
    try if kit != null then kit.close()
    finally if tickets != null then tickets.stop()

  test("the server's tools are offered to the model under the server's name, and called") {
    model
      .expectToolCall("mcp__tickets__search", """{"q":"open"}""")
      .expectText("There are 2 open tickets.")
    val answer = AgentClients
      .forAgent(kit.componentClient, "t-1")
      .call(DocsRefs.ticketsAsk)
      .invoke("How many open tickets?")
    assertEquals(answer, "There are 2 open tickets.")
    assert(tickets.calls.exists(_._1 == "search"), tickets.calls.toString)
  }

  test("a declaration's credential names a variable, never a value") {
    assert(TicketServers.tickets() != null)
    assert(TicketServers.guarded() != null)
    val e = intercept[IllegalArgumentException](
      com.thinkmorestupidless.ankka.agent.javasdk.McpServer
        .named("tickets")
        .header("Authorization", "TICKETS_TOKEN")
    )
    assert(e.getMessage.contains("ANKKA_MCP_"), e.getMessage)
  }
