package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.Approval

import scala.concurrent.duration.DurationInt

/** An MCP server as an agent lists it: its name, its address, its credential, its approval. */
class McpServerSuite extends munit.FunSuite:

  test("a server's name is lower-case letters, digits and hyphens") {
    McpServer.named("tickets-2"): Unit
    Vector("Tickets", "tick_ets", "tick ets", "", "mcp:tickets").foreach { bad =>
      val refused = intercept[IllegalArgumentException](McpServer.named(bad))
      assert(refused.getMessage.contains("[a-z0-9-]"), refused.getMessage)
    }
  }

  test("a server is reached at a URL, as an ankka service, or at the address its variable gives") {
    assertEquals(
      McpServer.at("tickets", "https://tickets.example.com/mcp").address,
      McpServer.Address.Url("https://tickets.example.com/mcp")
    )
    assertEquals(
      McpServer.service("catalogue", "catalogue", "/mcp").address,
      McpServer.Address.Service(None, "catalogue", "/mcp")
    )
    assertEquals(
      McpServer.service("catalogue", "shop", "catalogue", "/mcp").address,
      McpServer.Address.Service(Some("shop"), "catalogue", "/mcp")
    )
    assertEquals(McpServer.named("search").address, McpServer.Address.FromVariable)
    assertEquals(McpServer.urlVariable("search-two"), "ANKKA_MCP_SEARCH_TWO_URL")
  }

  test("a header's value comes from a variable the platform keeps for its own program") {
    val server = McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN")
    assertEquals(
      server.headers,
      Vector(McpServer.Header("Authorization", "ANKKA_MCP_TICKETS_TOKEN"))
    )
    val refused = intercept[IllegalArgumentException](
      McpServer.named("tickets").header("Authorization", "TICKETS_TOKEN")
    )
    assert(refused.getMessage.contains("ANKKA_MCP_"), refused.getMessage)
  }

  test("a server can require approval for every one of its tools, with a time limit or without") {
    assertEquals(McpServer.named("tickets").approval, None)
    assertEquals(McpServer.named("tickets").requiresApproval.approval, Some(Approval(None)))
    assertEquals(
      McpServer.named("tickets").requiresApproval(30.minutes).approval,
      Some(Approval(Some(30.minutes)))
    )
  }

  Vector(
    "the MCP server \"tickets\" twice" -> Vector(
      McpServer.named("tickets"),
      McpServer.named("tickets")
    ),
    "two MCP servers named \"tickets\"" ->
      Vector(McpServer.named("tickets"), McpServer.at("tickets", "https://other.example.com/mcp"))
  ).foreach { (what, servers) =>
    test(s"an agent that lists one MCP server twice is refused where it is built ($what)") {
      val problems = McpServer.problems(servers, ownTools = Vector.empty)
      assert(problems.exists(_.contains("'tickets'")), problems.toString)
    }
  }

  test("an agent's own tool may not take the name an MCP server's tool is offered under") {
    val problems = McpServer.problems(Vector.empty, ownTools = Vector("mcp__tickets__create"))
    assert(problems.exists(_.contains("mcp__tickets__create")), problems.toString)
  }

  test("a tool is offered as mcp__<server>__<tool>, and the name reads back one way") {
    assertEquals(McpServer.toolName("tickets", "create"), "mcp__tickets__create")
    assertEquals(McpServer.parseToolName("mcp__tickets__create"), Some(("tickets", "create")))
    // The server's name has no underscore, so the first `__` after the prefix ends it.
    assertEquals(
      McpServer.parseToolName("mcp__tickets__create__now"),
      Some(("tickets", "create__now"))
    )
    assertEquals(McpServer.parseToolName("create"), None)
  }
