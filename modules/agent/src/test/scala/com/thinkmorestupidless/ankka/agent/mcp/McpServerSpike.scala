package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.Json

import scala.concurrent.duration.DurationInt

/**
 * The platform's MCP client against a server it was not written beside.
 *
 * The scripted server in this module implements the protocol's rules a second time and agrees with
 * the client by construction; this is the check that does not share their assumptions. Run under
 * `-Dankka.spikes=on` with `-Dankka.mcp.spike.url` naming a running server, such as the reference
 * "everything" server over streamable HTTP:
 *
 * {{{
 * npx -y @modelcontextprotocol/server-everything streamableHttp     # prints its port
 * sbt -Dankka.spikes=on -Dankka.mcp.spike.url=http://127.0.0.1:3001/mcp 'agent/testOnly *McpServerSpike'
 * }}}
 */
class McpServerSpike extends munit.FunSuite:

  private val url = sys.props.get("ankka.mcp.spike.url")

  override def munitIgnore: Boolean =
    !sys.props.get("ankka.spikes").contains("on") || url.isEmpty

  test("initialize, list the tools and call echo on a real MCP server") {
    val client =
      McpClient("everything", McpTransport.Url(url.get, 5.seconds, 30.seconds), Nil, "spike")
    client.initialize()
    val tools = client.listTools()
    println(s"tools: ${tools.map(_.name).mkString(", ")}")
    assert(tools.exists(_.name == "echo"), tools.map(_.name).toString)
    val echoed = client.callTool("echo", Json.obj("message" -> Json.str("hello from ankka")))
    println(s"echo: $echoed")
    assert(echoed.exists(_.contains("hello from ankka")), echoed.toString)
  }
