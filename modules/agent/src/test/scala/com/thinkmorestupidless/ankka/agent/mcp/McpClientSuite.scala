package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.{Json, TestMcpServer}

import scala.concurrent.duration.DurationInt

/** The platform's MCP client against a scripted server: each part of the protocol it speaks. */
class McpClientSuite extends munit.FunSuite:

  private var servers = Vector.empty[TestMcpServer]

  override def afterEach(context: AfterEach): Unit =
    servers.foreach(_.stop())
    servers = Vector.empty

  private def server(): TestMcpServer =
    val s = TestMcpServer()
      .tool("create", "Opens a ticket")(args =>
        s"opened ${args("title").flatMap(_.asString).getOrElse("?")}"
      )
      .tool("search", "Finds tickets")(_ => "2 tickets found")
    servers = servers :+ s
    s

  private def client(s: TestMcpServer, headers: Seq[(String, String)] = Nil) =
    McpClient("tickets", McpTransport.Url(s.url, 2.seconds, 5.seconds), headers, "test")

  test("a session opens with initialize, then notifications/initialized") {
    val s = server()
    client(s).initialize()
    assertEquals(s.requests.map(_.method), Vector("initialize", "notifications/initialized"))
    assert(s.requests(1).headers.contains("mcp-session-id"), "the session id is sent back")
  }

  test("every tool is listed, across pages") {
    val s = server().tool("close", "Closes a ticket")(_ => "closed").pageSize(2)
    val c = client(s)
    c.initialize()
    assertEquals(c.listTools().map(_.name), Vector("create", "search", "close"))
    assertEquals(s.requests.count(_.method == "tools/list"), 2)
  }

  test("a call's arguments reach the server and its text comes back") {
    val s = server()
    val c = client(s)
    c.initialize()
    assertEquals(
      c.callTool("create", Json.obj("title" -> Json.str("printer"))),
      Right("opened printer")
    )
    assertEquals(s.calls, Vector("create" -> Json.obj("title" -> Json.str("printer"))))
  }

  test("an answer as an event stream is read as plain JSON is") {
    val s = server().answerAsEventStream(true)
    val c = client(s)
    c.initialize()
    assertEquals(c.listTools().size, 2)
    assertEquals(c.callTool("search", Json.obj()), Right("2 tickets found"))
  }

  test("text parts are joined; with none, structured content is the JSON; other parts are named") {
    val s = server()
      .toolResult("parts", "Several parts")(_ =>
        Json.obj(
          "content" -> Json.arr(
            Json.obj("type" -> Json.str("text"), "text"  -> Json.str("one")),
            Json.obj("type" -> Json.str("image"), "data" -> Json.str("…")),
            Json.obj("type" -> Json.str("text"), "text"  -> Json.str("two"))
          )
        )
      )
      .toolResult("structured", "Structured only")(_ =>
        Json.obj("content" -> Json.arr(), "structuredContent" -> Json.obj("count" -> Json.num(2)))
      )
    val c = client(s)
    c.initialize()
    assertEquals(
      c.callTool("parts", Json.obj()),
      Right("one\ntwo\n(a part of type 'image' was left out)")
    )
    assertEquals(c.callTool("structured", Json.obj()), Right("""{"count":2}"""))
  }

  test("a result marked as an error, and a JSON-RPC error, are each the tool's error") {
    val s = server().failNext("create", "queue is closed").failNextWithRpcError("search", "boom")
    val c = client(s)
    c.initialize()
    assertEquals(c.callTool("create", Json.obj()), Left("queue is closed"))
    val rpc = c.callTool("search", Json.obj())
    assert(rpc.left.exists(_.contains("boom")), rpc.toString)
  }

  test("a lost session is started again and the request repeated once") {
    val s = server()
    val c = client(s)
    c.initialize()
    s.expireSessionOnce()
    assertEquals(c.callTool("search", Json.obj()), Right("2 tickets found"))
    assertEquals(s.requests.count(_.method == "initialize"), 2)
  }

  test("headers are sent on every request") {
    val s = server().requireHeader("Authorization", "Bearer t-1")
    val c = client(s, Seq("Authorization" -> "Bearer t-1"))
    c.initialize()
    c.callTool("search", Json.obj()): Unit
    assert(
      s.requests.forall(_.headers.get("authorization").contains("Bearer t-1")),
      s.requests.toString
    )
  }

  test("a server that is down is a failure naming where it was looked for") {
    val s = server()
    s.stop()
    val refused = intercept[McpUnavailable](client(s).initialize())
    assert(refused.getMessage.contains("'tickets'"), refused.getMessage)
    assert(refused.getMessage.contains(s.url), refused.getMessage)
  }

  test("a server that refuses the credential is a failure naming the status, never the value") {
    val s = server().refuseWith(401)
    val refused = intercept[McpUnavailable](
      client(s, Seq("Authorization" -> "Bearer secret-value")).initialize()
    )
    assert(refused.getMessage.contains("401"), refused.getMessage)
    assert(!refused.getMessage.contains("secret-value"), refused.getMessage)
  }
