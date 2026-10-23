package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.http.{Acl, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.io.Source

/**
 * Spike S2 (research R3, R19): a client nobody here wrote, the MCP inspector's CLI, against this
 * server. Serves an MCP endpoint on loopback and either runs the inspector itself (`npx` on the
 * path) or, with `-Dankka.mcp.spike.seconds=N`, prints the URL and waits for one run by hand.
 *
 * The `headers` tool answers the headers its request came with, which is what this spike asks: what
 * the inspector sends. Skipped unless `-Dankka.spikes=on`; `.claude/rules/mcp.md` records it.
 */
class OutsideClientSpike extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private final class Echo extends McpEndpoint("/mcp"):
    val acl: Acl = Acl.AllowAll
    val headers  =
      tool("headers").describedAs("Answers the headers this call came with").handle { () =>
        request.headers.map((n, v) => s"$n: $v").sorted.mkString("\n")
      }
    val add = tool("add")
      .describedAs("Adds two numbers")
      .param[Int]("a", "The first")
      .param[Int]("b", "The second")
      .handle((a: Int, b: Int) => a + b)

  private def inspector(url: String, args: String*): (Int, String) =
    val command = Seq(
      "npx",
      "-y",
      "@modelcontextprotocol/inspector",
      "--cli",
      url,
      "--transport",
      "http"
    ) ++ args
    val process = ProcessBuilder(command*).redirectErrorStream(true).start()
    val output  = Source.fromInputStream(process.getInputStream).mkString
    process.waitFor(3, TimeUnit.MINUTES)
    (process.exitValue(), output)

  test("the MCP inspector lists and calls this server's tools") {
    val server = HttpServer.at("127.0.0.1", 0)(_ => Echo())
    val kit    = AnkkaTestKit.start(Nil, Seq(server))
    try
      val url = s"http://127.0.0.1:${server.boundPort.get}/mcp"
      sys.props.get("ankka.mcp.spike.seconds").map(_.toInt) match
        case Some(seconds) =>
          println(s"S2: serving $url for ${seconds}s")
          Thread.sleep(seconds.seconds.toMillis)
        case None =>
          val listed = inspector(url, "--method", "tools/list")
          println(s"S2 tools/list exit ${listed._1}:\n${listed._2}")
          val called = inspector(
            url,
            "--method",
            "tools/call",
            "--tool-name",
            "add",
            "--tool-arg",
            "a=2",
            "b=3"
          )
          println(s"S2 tools/call add exit ${called._1}:\n${called._2}")
          val headers = inspector(url, "--method", "tools/call", "--tool-name", "headers")
          println(s"S2 tools/call headers exit ${headers._1}:\n${headers._2}")
          assertEquals(listed._1, 0, listed._2)
          assert(listed._2.contains("\"headers\"") && listed._2.contains("\"add\""), listed._2)
          assertEquals(called._1, 0, called._2)
          assert(called._2.contains("5"), called._2)
    finally kit.stop()
  }
