package com.thinkmorestupidless.ankka.cli.mcp

import com.thinkmorestupidless.ankka.cli.ApiError

import java.io.{BufferedReader, PrintStream}

/**
 * What a tool call produced: text for the model, and whether it is an error the model should see.
 */
private[cli] final case class ToolResult(text: String, isError: Boolean = false)

/**
 * One tool: a name, what it does, the JSON Schema of its arguments, and how far it reaches.
 *
 * The hints are the protocol's tool annotations, and they are how a client decides what to ask a
 * person about before running: a read-only tool changes nothing, a destructive one may remove or
 * replace something, and an idempotent one does nothing more the second time.
 */
private[cli] final case class Tool(
    name: String,
    title: String,
    description: String,
    inputSchema: Json,
    readOnly: Boolean,
    destructive: Boolean = false,
    idempotent: Boolean = false,
    openWorld: Boolean = false
)(val run: Json => ToolResult):

  def describe: Json = Json.obj(
    "name"        -> Json.str(name),
    "title"       -> Json.str(title),
    "description" -> Json.str(description),
    "inputSchema" -> inputSchema,
    "annotations" -> Json.obj(
      "title"           -> Json.str(title),
      "readOnlyHint"    -> Json.bool(readOnly),
      "destructiveHint" -> Json.bool(destructive),
      "idempotentHint"  -> Json.bool(idempotent),
      "openWorldHint"   -> Json.bool(openWorld)
    )
  )

/** A document the server can hand over whole: one page of the documentation. */
private[cli] final case class Resource(
    uri: String,
    name: String,
    title: String,
    description: String,
    mimeType: String
)(val read: () => String):

  def describe: Json = Json.obj(
    "uri"         -> Json.str(uri),
    "name"        -> Json.str(name),
    "title"       -> Json.str(title),
    "description" -> Json.str(description),
    "mimeType"    -> Json.str(mimeType)
  )

/**
 * A Model Context Protocol server over stdio: JSON-RPC 2.0, one message per line.
 *
 * Stdout carries protocol messages and nothing else; anything a person should see goes to stderr.
 * The server holds no state beyond the conversation's negotiated version — every tool call resolves
 * settings and credentials afresh, so an `ankka login` in another terminal is picked up by the next
 * call rather than requiring the client to restart the server.
 */
private[cli] final class McpServer(
    name: String,
    version: String,
    instructions: String,
    tools: Vector[Tool],
    resources: () => Vector[Resource]
):

  private val byName = tools.map(t => t.name -> t).toMap

  /** Serves until the input ends, which is how a client says it is done. */
  def serve(in: BufferedReader, out: PrintStream, log: PrintStream): Unit =
    var line = in.readLine()
    while line != null do
      if line.trim.nonEmpty then
        handleLine(line, log).foreach { response =>
          out.println(response.render)
          out.flush()
        }
      line = in.readLine()

  /** One line in; the reply to send, if the message expects one. */
  def handleLine(line: String, log: PrintStream): Option[Json] =
    Json.parse(line) match
      case Left(problem) => Some(error(Json.Null, McpServer.ParseError, s"not JSON: $problem"))
      case Right(Json.Arr(messages)) =>
        // Batches were dropped from the protocol in 2025-06-18; answering them costs nothing and
        // keeps an older client working.
        val replies = messages.flatMap(handle(_, log))
        Option.when(replies.nonEmpty)(Json.Arr(replies))
      case Right(message) => handle(message, log)

  private def handle(message: Json, log: PrintStream): Option[Json] =
    val id     = message("id")
    val method = message.string("method")
    val params = message("params").getOrElse(Json.obj())
    (id, method) match
      // A notification: `notifications/initialized`, `notifications/cancelled`. Nothing to answer.
      case (None, Some(_)) => None
      // A response to a request this server never sends.
      case (_, None) if message("result").isDefined || message("error").isDefined => None
      case (_, None) =>
        Some(error(id.getOrElse(Json.Null), McpServer.InvalidRequest, "no method"))
      case (Some(requestId), Some(m)) =>
        val reply =
          try dispatch(m, params)
          catch
            case failure: McpServer.Failure => Left(failure)
            case failure: Exception =>
              log.println(s"ankka mcp: $m failed: ${failure.getMessage}")
              Left(
                McpServer.Failure(
                  McpServer.InternalError,
                  Option(failure.getMessage).getOrElse(failure.toString)
                )
              )
        Some(reply match
          case Right(result) =>
            Json.obj("jsonrpc" -> Json.str("2.0"), "id" -> requestId, "result" -> result)
          case Left(failure) => error(requestId, failure.code, failure.message))

  private def dispatch(method: String, params: Json): Either[McpServer.Failure, Json] =
    method match
      case "initialize" =>
        val requested = params.string("protocolVersion")
        val agreed =
          requested
            .filter(McpServer.SupportedVersions.contains)
            .getOrElse(McpServer.SupportedVersions.head)
        Right(
          Json.obj(
            "protocolVersion" -> Json.str(agreed),
            "capabilities" -> Json.obj(
              "tools"     -> Json.obj("listChanged" -> Json.bool(false)),
              "resources" -> Json.obj("listChanged" -> Json.bool(false))
            ),
            "serverInfo"   -> Json.obj("name" -> Json.str(name), "version" -> Json.str(version)),
            "instructions" -> Json.str(instructions)
          )
        )
      case "ping"       => Right(Json.obj())
      case "tools/list" => Right(Json.obj("tools" -> Json.Arr(tools.map(_.describe))))
      case "tools/call" =>
        val toolName = params
          .string("name")
          .getOrElse(throw McpServer.Failure(McpServer.InvalidParams, "no tool name"))
        val tool = byName.getOrElse(
          toolName,
          throw McpServer.Failure(McpServer.InvalidParams, s"no tool named '$toolName'")
        )
        val arguments = params("arguments").getOrElse(Json.obj())
        val result =
          try tool.run(arguments)
          catch
            case failure: ApiError                 => ToolResult(failure.detail, isError = true)
            case failure: IllegalArgumentException => ToolResult(failure.getMessage, isError = true)
        Right(
          Json.obj(
            "content" -> Json.arr(
              Json.obj("type" -> Json.str("text"), "text" -> Json.str(result.text))
            ),
            "isError" -> Json.bool(result.isError)
          )
        )
      case "resources/list" =>
        Right(Json.obj("resources" -> Json.Arr(resources().map(_.describe))))
      case "resources/read" =>
        val uri =
          params.string("uri").getOrElse(throw McpServer.Failure(McpServer.InvalidParams, "no uri"))
        val resource = resources()
          .find(_.uri == uri)
          .getOrElse(throw McpServer.Failure(McpServer.ResourceNotFound, s"no resource '$uri'"))
        Right(
          Json.obj(
            "contents" -> Json.arr(
              Json.obj(
                "uri"      -> Json.str(resource.uri),
                "mimeType" -> Json.str(resource.mimeType),
                "text"     -> Json.str(resource.read())
              )
            )
          )
        )
      case "resources/templates/list" => Right(Json.obj("resourceTemplates" -> Json.arr()))
      case other => Left(McpServer.Failure(McpServer.MethodNotFound, s"no method '$other'"))

  private def error(id: Json, code: Int, message: String): Json =
    Json.obj(
      "jsonrpc" -> Json.str("2.0"),
      "id"      -> id,
      "error"   -> Json.obj("code" -> Json.num(code), "message" -> Json.str(message))
    )

private[cli] object McpServer:

  /** Newest first: an unknown request is answered with the newest, as the protocol asks. */
  val SupportedVersions: Vector[String] =
    Vector("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

  val ParseError       = -32700
  val InvalidRequest   = -32600
  val MethodNotFound   = -32601
  val InvalidParams    = -32602
  val InternalError    = -32603
  val ResourceNotFound = -32002

  final case class Failure(code: Int, message: String) extends RuntimeException(message)
