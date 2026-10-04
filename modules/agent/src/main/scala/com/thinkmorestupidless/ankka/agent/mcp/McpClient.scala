package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.Json

import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** A tool an MCP server has, as it describes it. */
private[ankka] final case class McpTool(name: String, description: String, inputSchema: Json)

/**
 * An MCP server could not be reached, or would not answer. Says where, never with what credential.
 */
private[ankka] final class McpUnavailable(message: String) extends RuntimeException(message)

/**
 * The part of the Model Context Protocol an agent needs: JSON-RPC 2.0 over streamable HTTP, to
 * `initialize`, list the server's tools and call them.
 *
 * Every request is a POST accepting plain JSON or an event stream, and either answer is read. A
 * session id the server gives is sent back on every later request; told the session is gone, the
 * client starts one again and repeats the request once. It declares no capability, so a server has
 * nothing to ask of it. Headers are sent on every request and appear in no message this raises.
 */
private[ankka] final class McpClient(
    server: String,
    transport: McpTransport,
    headers: Seq[(String, String)],
    clientVersion: String
):
  import McpClient.*

  private val ids                               = AtomicLong(0)
  @volatile private var session: Option[String] = None
  @volatile private var protocol: String        = ProtocolVersion

  /** Opens a session: `initialize`, then `notifications/initialized`. */
  def initialize(): Unit = synchronized {
    session = None
    val result = send(
      "initialize",
      Json.obj(
        "protocolVersion" -> Json.str(ProtocolVersion),
        "capabilities"    -> Json.obj(),
        "clientInfo" -> Json.obj("name" -> Json.str("ankka"), "version" -> Json.str(clientVersion))
      ),
      retry = false
    ) match
      case Right(result) => result
      case Left(error)   => throw McpUnavailable(error)
    protocol = result("protocolVersion").flatMap(_.asString).getOrElse(ProtocolVersion)
    notify("notifications/initialized")
  }

  /** Every tool the server has, across its pages. */
  def listTools(): Vector[McpTool] =
    def page(cursor: Option[String]): Vector[McpTool] =
      val params = cursor.fold(Json.obj())(c => Json.obj("cursor" -> Json.str(c)))
      val result = request("tools/list", params).fold(e => throw McpUnavailable(e), identity)
      val tools = result("tools").flatMap(_.asArray).getOrElse(Vector.empty).flatMap { tool =>
        tool("name").flatMap(_.asString).map { name =>
          McpTool(
            name,
            tool("description").flatMap(_.asString).getOrElse(""),
            tool("inputSchema").getOrElse(Json.obj("type" -> Json.str("object")))
          )
        }
      }
      result("nextCursor").flatMap(_.asString).filter(_.nonEmpty) match
        case Some(next) => tools ++ page(Some(next))
        case None       => tools
    page(None)

  /**
   * Calls a tool. A `Left` is what the model is told as the tool's error: the server's own error, a
   * result it marked as one, or that it could not be reached.
   */
  def callTool(name: String, arguments: Json): Either[String, String] =
    request("tools/call", Json.obj("name" -> Json.str(name), "arguments" -> arguments)).flatMap {
      result =>
        val text = textOf(result)
        if result("isError").flatMap(_.asBoolean).contains(true) then
          Left(
            if text.isEmpty then s"MCP server '$server' answered '$name' with an error" else text
          )
        else Right(text)
    }

  // ── Requests ──────────────────────────────────────────────────────────────

  private def request(method: String, params: Json): Either[String, Json] =
    send(method, params, retry = true)

  private def send(method: String, params: Json, retry: Boolean): Either[String, Json] =
    val id = ids.incrementAndGet()
    val message = Json.obj(
      "jsonrpc" -> Json.str("2.0"),
      "id"      -> Json.num(id.toDouble),
      "method"  -> Json.str(method),
      "params"  -> params
    )
    val sentWith = session
    val response =
      try transport.post(message.render, requestHeaders(sentWith))
      catch
        case NonFatal(failure) =>
          return Left(
            s"MCP server '$server' at ${transport.describe} could not be reached: " +
              Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)
          )
    response.status match
      // The session the server gave is gone: start another, and ask once more.
      case 404 if retry && sentWith.isDefined =>
        try
          initialize()
          send(method, params, retry = false)
        catch case failure: McpUnavailable => Left(failure.getMessage)
      case status if status / 100 != 2 =>
        Left(s"MCP server '$server' at ${transport.describe} answered $method with HTTP $status")
      case _ =>
        response.header(SessionHeader).foreach(id => session = Some(id))
        answerTo(id, response).flatMap { reply =>
          reply("error") match
            case Some(error) =>
              val text = error("message").flatMap(_.asString).getOrElse(error.render)
              Left(s"MCP server '$server' answered $method with an error: $text")
            case None => Right(reply("result").getOrElse(Json.obj()))
        }

  private def notify(method: String): Unit =
    val message = Json.obj("jsonrpc" -> Json.str("2.0"), "method" -> Json.str(method))
    try transport.post(message.render, requestHeaders(session)): Unit
    catch case NonFatal(_) => () // a notification has no answer to lose

  private def requestHeaders(sentWith: Option[String]): Seq[(String, String)] =
    Seq(
      "Content-Type"         -> "application/json",
      "Accept"               -> "application/json, text/event-stream",
      "MCP-Protocol-Version" -> protocol
    ) ++ sentWith.map(SessionHeader -> _) ++ headers

  /** The JSON-RPC response to `id`, from a plain JSON answer or from an event stream. */
  private def answerTo(id: Long, response: McpTransport.Response): Either[String, Json] =
    val messages =
      if response.header("content-type").exists(_.startsWith("text/event-stream")) then
        events(response.body)
      else Vector(response.body)
    messages.iterator
      .flatMap(text => Json.parse(text).toOption)
      .flatMap {
        case Json.Arr(batch) => batch
        case one             => Vector(one)
      }
      .find(_("id").flatMap(_.asDouble).contains(id.toDouble))
      .toRight(s"MCP server '$server' did not answer request $id")

private[ankka] object McpClient:

  val ProtocolVersion: String = "2025-06-18"
  val SessionHeader: String   = "Mcp-Session-Id"

  /** The data of each event in a server-sent event stream, lines of one event joined. */
  def events(body: String): Vector[String] =
    body
      .split("\r?\n\r?\n")
      .toVector
      .map(
        _.linesIterator
          .collect {
            case line if line.startsWith("data:") => line.drop(5).stripPrefix(" ")
          }
          .mkString("\n")
      )
      .filter(_.nonEmpty)

  /**
   * What a tool's result says, as text for the model: its text parts joined; with none, its
   * structured content as JSON. A part that is not text is named and left out.
   */
  def textOf(result: Json): String =
    val parts = result("content").flatMap(_.asArray).getOrElse(Vector.empty)
    val texts = parts.flatMap(p =>
      if p("type").flatMap(_.asString).contains("text") then p("text").flatMap(_.asString)
      else None
    )
    val left = parts
      .flatMap(p => p("type").flatMap(_.asString))
      .filterNot(_ == "text")
      .map(kind => s"(a part of type '$kind' was left out)")
    val body =
      if texts.nonEmpty then texts.mkString("\n")
      else result("structuredContent").map(_.render).getOrElse("")
    (Vector(body).filter(_.nonEmpty) ++ left).mkString("\n")
