package com.thinkmorestupidless.ankka.agent

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*

/**
 * An MCP server a test scripts: the tools it has, what each answers, and how it answers — so an
 * agent's use of MCP tools is tested with no server anyone else runs.
 *
 * It speaks streamable HTTP on loopback, on a port of its own choosing, and records every request.
 * It is a second implementation of the protocol's rules beside the platform's client, so it agrees
 * with that client by construction; a test that must show the client works with servers in the
 * world runs against one of those.
 *
 * {{{
 * val tickets = TestMcpServer()
 *   .tool("create", "Opens a ticket")(args => s"opened ${args("title").flatMap(_.asString).get}")
 * // the agent lists McpServer.named("tickets"); the service runs with ANKKA_MCP_TICKETS_URL = tickets.url
 * }}}
 */
final class TestMcpServer private (http: HttpServer):
  import TestMcpServer.*

  private val tools        = ConcurrentHashMap[String, Scripted]()
  private val order        = CopyOnWriteArrayList[String]()
  private val seen         = CopyOnWriteArrayList[Seen]()
  private val sessions     = ConcurrentHashMap.newKeySet[String]()
  private val nextSession  = AtomicInteger(0)
  private val eventStream  = AtomicBoolean(false)
  private val pageSizeRef  = AtomicInteger(0)
  private val refusing     = AtomicInteger(0)
  private val failing      = ConcurrentHashMap[String, String]()
  private val rpcFailing   = ConcurrentHashMap[String, String]()
  private val expireOnce   = AtomicBoolean(false)
  private val lastRequired = AtomicReference[Option[(String, String)]](None)

  /** Where an agent finds it: give this as the server's address. */
  def url: String = s"http://127.0.0.1:${http.getAddress.getPort}/mcp"

  /** A tool answering with text. Its schema is an open object unless one is given. */
  def tool(name: String, description: String, schema: Json = OpenObject)(
      answer: Json => String
  ): TestMcpServer =
    toolResult(name, description, schema)(args =>
      Json.obj(
        "content" -> Json.arr(
          Json.obj("type" -> Json.str("text"), "text" -> Json.str(answer(args)))
        )
      )
    )

  /** A tool answering with a whole `tools/call` result, for content that is not just text. */
  def toolResult(name: String, description: String, schema: Json = OpenObject)(
      answer: Json => Json
  ): TestMcpServer =
    if !order.contains(name) then order.add(name): Unit
    tools.put(name, Scripted(description, schema, answer)): Unit
    this

  /** The server no longer has `name`: a call to it is answered as an unknown tool. */
  def removeTool(name: String): TestMcpServer =
    tools.remove(name): Unit
    order.remove(name): Unit
    this

  /** The next call to `tool` answers with a result marked as an error, carrying `error`. */
  def failNext(tool: String, error: String): TestMcpServer =
    failing.put(tool, error): Unit
    this

  /** The next call to `tool` answers with a JSON-RPC error carrying `error`. */
  def failNextWithRpcError(tool: String, error: String): TestMcpServer =
    rpcFailing.put(tool, error): Unit
    this

  /** Whether to answer as an event stream rather than plain JSON. */
  def answerAsEventStream(on: Boolean): TestMcpServer =
    eventStream.set(on)
    this

  /** The next request in a session is told the session is gone, as a server that restarted is. */
  def expireSessionOnce(): TestMcpServer =
    expireOnce.set(true)
    this

  /** Lists tools `n` to a page, with a cursor; zero lists them all at once. */
  def pageSize(n: Int): TestMcpServer =
    pageSizeRef.set(n)
    this

  /** Answers every request with `status` — a server that refuses the credential, say. */
  def refuseWith(status: Int): TestMcpServer =
    refusing.set(status)
    this

  /** Answers 401 to any request without this header and value, as a server wanting a credential. */
  def requireHeader(name: String, value: String): TestMcpServer =
    lastRequired.set(Some(name.toLowerCase -> value))
    this

  /** Every request, in order. */
  def requests: Vector[Seen] = seen.asScala.toVector

  /** The `tools/call` requests, as the tool's name and its arguments. */
  def calls: Vector[(String, Json)] =
    requests.filter(_.method == "tools/call").map { r =>
      val params = r.body("params").getOrElse(Json.obj())
      (params("name").flatMap(_.asString).getOrElse(""), params("arguments").getOrElse(Json.obj()))
    }

  def stop(): Unit = http.stop(0)

  // ── Serving ───────────────────────────────────────────────────────────────

  http.createContext("/mcp", exchange => serve(exchange)): Unit

  private def serve(exchange: HttpExchange): Unit =
    try
      val body    = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val message = Json.parse(body).getOrElse(Json.obj())
      val headers = exchange.getRequestHeaders.asScala.collect {
        case (name, values) if !values.isEmpty => name.toLowerCase -> values.get(0)
      }.toMap
      val method = message("method").flatMap(_.asString).getOrElse("")
      seen.add(Seen(method, headers, message)): Unit
      val session = headers.get("mcp-session-id")

      if refusing.get() != 0 then respond(exchange, refusing.get(), "", "text/plain")
      else if lastRequired.get().exists((name, value) => !headers.get(name).contains(value)) then
        respond(exchange, 401, "", "text/plain")
      else if method != "initialize" && session.exists(id => !sessions.contains(id)) then
        respond(exchange, 404, "", "text/plain")
      else if method != "initialize" && session.isDefined && expireOnce.compareAndSet(true, false)
      then
        sessions.clear()
        respond(exchange, 404, "", "text/plain")
      else if message("id").isEmpty then respond(exchange, 202, "", "text/plain")
      else
        val id = message("id").get
        val (extra, reply) = method match
          case "initialize" =>
            val session = s"session-${nextSession.incrementAndGet()}"
            sessions.add(session): Unit
            Map("Mcp-Session-Id" -> session) -> result(
              id,
              Json.obj(
                "protocolVersion" -> Json.str("2025-06-18"),
                "capabilities"    -> Json.obj("tools" -> Json.obj()),
                "serverInfo"      -> Json.obj("name" -> Json.str("test-mcp-server"))
              )
            )
          case "tools/list" => Map.empty -> result(id, listing(message))
          case "tools/call" => Map.empty -> call(id, message)
          case other        => Map.empty -> rpcError(id, -32601, s"no method '$other'")
        extra.foreach((name, value) => exchange.getResponseHeaders.add(name, value))
        if eventStream.get() then
          respond(exchange, 200, s"event: message\ndata: ${reply.render}\n\n", "text/event-stream")
        else respond(exchange, 200, reply.render, "application/json")
    catch case scala.util.control.NonFatal(_) => respond(exchange, 500, "", "text/plain")

  private def listing(message: Json): Json =
    val all  = order.asScala.toVector.flatMap(name => Option(tools.get(name)).map(name -> _))
    val size = pageSizeRef.get()
    val cursor =
      message("params").flatMap(_("cursor")).flatMap(_.asString).map(_.toInt).getOrElse(0)
    val page = if size <= 0 then all else all.slice(cursor, cursor + size)
    val next = Option.when(size > 0 && cursor + size < all.size)(cursor + size)
    Json.Obj(
      Map(
        "tools" -> Json.Arr(page.map { (name, tool) =>
          Json.obj(
            "name"        -> Json.str(name),
            "description" -> Json.str(tool.description),
            "inputSchema" -> tool.schema
          )
        })
      ) ++ next.map(n => "nextCursor" -> Json.str(n.toString))
    )

  private def call(id: Json, message: Json): Json =
    val params    = message("params").getOrElse(Json.obj())
    val name      = params("name").flatMap(_.asString).getOrElse("")
    val arguments = params("arguments").getOrElse(Json.obj())
    Option(rpcFailing.remove(name)) match
      case Some(error) => rpcError(id, -32000, error)
      case None =>
        Option(failing.remove(name)) match
          case Some(error) =>
            result(
              id,
              Json.obj(
                "content" -> Json.arr(
                  Json.obj("type" -> Json.str("text"), "text" -> Json.str(error))
                ),
                "isError" -> Json.bool(true)
              )
            )
          case None =>
            Option(tools.get(name)) match
              case Some(tool) => result(id, tool.answer(arguments))
              case None       => rpcError(id, -32602, s"Unknown tool: $name")

  private def result(id: Json, value: Json): Json =
    Json.obj("jsonrpc" -> Json.str("2.0"), "id" -> id, "result" -> value)

  private def rpcError(id: Json, code: Int, message: String): Json =
    Json.obj(
      "jsonrpc" -> Json.str("2.0"),
      "id"      -> id,
      "error"   -> Json.obj("code" -> Json.num(code.toDouble), "message" -> Json.str(message))
    )

  private def respond(exchange: HttpExchange, status: Int, body: String, contentType: String) =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", contentType)
    if bytes.isEmpty then exchange.sendResponseHeaders(status, -1)
    else
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
    exchange.close()

object TestMcpServer:

  /** One request the server saw: its JSON-RPC method, its headers in lower case, and its body. */
  final case class Seen(method: String, headers: Map[String, String], body: Json)

  private final case class Scripted(description: String, schema: Json, answer: Json => Json)

  private val OpenObject = Json.obj("type" -> Json.str("object"))

  /** Starts a server on loopback, on a port of its own choosing. */
  def apply(): TestMcpServer =
    val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    val server = new TestMcpServer(http)
    http.start()
    server
