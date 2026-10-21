package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.http.RequestContext

import java.net.URI
import scala.util.Try

/**
 * The MCP protocol as one pure function (feature 054, research R3): a request in, a response out,
 * no exchange, actor or I/O. Stateless, JSON only, tools only — revision 2025-06-18, and
 * 2025-03-26, between which nothing a tools-only server does differs.
 *
 * A refusal by the endpoint's ACL is not the kernel's: the router answered 401 or 403 before it
 * ran. What the kernel decides is the protocol's own rules, and which tools the caller may see,
 * which it is told rather than knows (`ToolsFor`).
 */
private[ankka] object Protocol:

  val Latest: String                    = "2025-06-18"
  val SupportedVersions: Vector[String] = Vector(Latest, "2025-03-26")

  val ParseError: Int     = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int  = -32602

  val VersionHeader: String = "MCP-Protocol-Version"

  /** An HTTP request as the kernel reads it: its method, its headers and its body. */
  final case class Request(method: String, headers: Vector[(String, String)], body: Array[Byte]):
    def header(name: String): Option[String] =
      headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  /** What to answer: a status, headers, and a JSON body or none. */
  final case class Response(status: Int, headers: Vector[(String, String)], body: Option[Json])

  /** Who the server says it is, in `initialize`. */
  final case class ServerInfo(name: String, version: String)

  /**
   * The tools the caller of a request may see and call, in declaration order: the endpoint's, less
   * those its key's scope or a tool's own ACL keep from it (R7).
   */
  type ToolsFor = RequestContext => Vector[McpTool]

  /** Runs one tool's call: the endpoint wraps it in the tool's span. */
  type Invoke = (McpTool, ToolCall) => ToolOutcome

  def handle(
      request: Request,
      context: RequestContext,
      server: ServerInfo,
      tools: ToolsFor,
      invoke: Invoke = (tool, call) => tool.run(call)
  ): Response =
    request.method.toUpperCase match
      case "POST" => post(request, context, server, tools, invoke)
      case _      =>
        // No stream is offered at this endpoint, and no session is minted, so none can be ended.
        Response(405, Vector("Allow" -> "POST"), None)

  private def post(
      request: Request,
      context: RequestContext,
      server: ServerInfo,
      tools: ToolsFor,
      invoke: Invoke
  ): Response =
    request.header(VersionHeader) match
      case Some(version) if !SupportedVersions.contains(version.trim) =>
        failure(400, InvalidRequest, s"unsupported MCP-Protocol-Version: $version")
      case _ =>
        if !sameOrigin(request) then
          failure(403, InvalidRequest, "the request's Origin is not this server")
        else
          Json.parse(String(request.body, "UTF-8")) match
            case Left(reason) => failure(400, ParseError, s"parse error: $reason")
            case Right(_: Json.Arr) =>
              failure(400, InvalidRequest, "batches are not accepted in protocol 2025-06-18")
            case Right(message: Json.Obj) => message_(message, context, server, tools, invoke)
            case Right(_) => failure(400, InvalidRequest, "a message is a JSON object")

  private def message_(
      message: Json.Obj,
      context: RequestContext,
      server: ServerInfo,
      tools: ToolsFor,
      invoke: Invoke
  ): Response =
    (message("method").flatMap(_.asString), message("id")) match
      // A notification asks for no answer, and one the server does not know is ignored all the same.
      case (Some(_), None) => accepted
      case (Some(method), Some(id)) =>
        val params = message("params").getOrElse(Json.Null)
        method match
          case "initialize" => result(id, initialize(params, server))
          case "ping"       => result(id, Json.obj())
          case "tools/list" => result(id, list(tools(context)))
          case "tools/call" => call(id, params, context, tools, invoke)
          case other        => error(200, id, MethodNotFound, s"method not found: $other")
      // A response to a request this server never sends, or an error answering one: nothing to do.
      case (None, _) if message("result").isDefined || message("error").isDefined => accepted
      case _ => failure(400, InvalidRequest, "a message names a method, or is a response")

  private def initialize(params: Json, server: ServerInfo): Json =
    val asked = params("protocolVersion").flatMap(_.asString)
    Json.obj(
      "protocolVersion" -> Json.str(asked.filter(SupportedVersions.contains).getOrElse(Latest)),
      "capabilities"    -> Json.obj("tools" -> Json.obj("listChanged" -> Json.bool(false))),
      "serverInfo" -> Json.obj(
        "name"    -> Json.str(server.name),
        "version" -> Json.str(server.version)
      )
    )

  private def list(visible: Vector[McpTool]): Json =
    Json.obj("tools" -> Json.Arr(visible.map { tool =>
      Json.obj(
        "name"        -> Json.str(tool.name),
        "description" -> Json.str(tool.description),
        "inputSchema" -> tool.spec.inputSchema
      )
    }))

  private def call(
      id: Json,
      params: Json,
      context: RequestContext,
      tools: ToolsFor,
      invoke: Invoke
  ): Response =
    params("name").flatMap(_.asString) match
      case None       => error(200, id, InvalidParams, "a tool call names its tool")
      case Some(name) =>
        // No such tool, one outside the key's scope and one the caller's ACL refuses are the same
        // words, so a listing's absence and a call's refusal agree and neither says which it was.
        tools(context).find(_.name == name) match
          case None => error(200, id, InvalidParams, s"tool not available: $name")
          case Some(tool) =>
            val arguments = params("arguments").getOrElse(Json.Null)
            invoke(tool, ToolCall(arguments, context)) match
              case ToolOutcome.BadArguments(reason) => error(200, id, InvalidParams, reason)
              case ToolOutcome.Failed(reason) =>
                result(id, content(reason, None, isError = true))
              case ToolOutcome.Answered(answer) =>
                result(id, content(answer.text, answer.structured, isError = false))

  private def content(text: String, structured: Option[Json], isError: Boolean): Json =
    Json.Obj(
      Map(
        "content" -> Json.arr(Json.obj("type" -> Json.str("text"), "text" -> Json.str(text))),
        "isError" -> Json.bool(isError)
      ) ++ structured.map("structuredContent" -> _)
    )

  /**
   * The protocol's DNS-rebinding rule: a browser's request from another origin is refused. A
   * request with no `Origin` is not a browser's, and is the commonest kind.
   */
  private def sameOrigin(request: Request): Boolean =
    request.header("Origin") match
      case None => true
      case Some(origin) =>
        val authority = Try(URI(origin.trim)).toOption.flatMap(u => Option(u.getRawAuthority))
        authority.exists(a => request.header("Host").exists(_.trim.equalsIgnoreCase(a)))

  private val accepted = Response(202, Vector.empty, None)

  private def result(id: Json, value: Json): Response =
    Response(
      200,
      Vector.empty,
      Some(Json.obj("jsonrpc" -> Json.str("2.0"), "id" -> id, "result" -> value))
    )

  private def error(status: Int, id: Json, code: Int, message: String): Response =
    Response(
      status,
      Vector.empty,
      Some(
        Json.obj(
          "jsonrpc" -> Json.str("2.0"),
          "id"      -> id,
          "error"   -> Json.obj("code" -> Json.num(code.toDouble), "message" -> Json.str(message))
        )
      )
    )

  /** An error answering no request the server could read, so its `id` is `null`. */
  private def failure(status: Int, code: Int, message: String): Response =
    error(status, Json.Null, code, message)
