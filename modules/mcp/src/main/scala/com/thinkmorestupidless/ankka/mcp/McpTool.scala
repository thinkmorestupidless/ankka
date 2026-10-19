package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.{Json, ToolSpec}
import com.thinkmorestupidless.ankka.http.{Acl, RequestContext}

/**
 * One tool of an MCP endpoint: what a client is told about it, who may call it, and what calling it
 * does. Built with `McpEndpoint.tool`, never directly.
 *
 * @param acl
 *   the tool's own ACL, decided after the endpoint's; `None` is the endpoint's alone.
 */
final class McpTool private[mcp] (
    val spec: ToolSpec,
    val acl: Option[Acl],
    private[ankka] val kind: ToolKind,
    private[ankka] val run: ToolCall => ToolOutcome
):
  def name: String        = spec.name
  def description: String = spec.description

  /** The tool, answering to `acl` after the endpoint's. */
  private[mcp] def withAcl(acl: Acl): McpTool = new McpTool(spec, Some(acl), kind, run)

  override def toString: String = s"McpTool($name)"

/** A call of a tool: the arguments the client sent, and the request it came in. */
final case class ToolCall(arguments: Json, context: RequestContext)

/**
 * What a tool answered: text for every client, and structured content when the result is an object.
 */
final case class ToolAnswer(text: String, structured: Option[Json] = None)

/** How a call of a tool ended, before it is written as JSON-RPC. */
private[ankka] enum ToolOutcome:
  /** The handler answered. */
  case Answered(answer: ToolAnswer)

  /** The handler ran and refused, threw, or a referenced route answered ≥ 400: `isError: true`. */
  case Failed(message: String)

  /** The arguments were refused before any handler ran: a protocol error, `-32602`. */
  case BadArguments(message: String)

/**
 * What kind of thing a tool runs. Never told to a client: a reference is offered as any tool is.
 */
private[ankka] enum ToolKind:
  case Handler
  case Command(component: String, handler: String)
  case Query(component: String, handler: String)
  case Route(endpoint: String, method: String, path: String)

/** What a tool may be called. */
object ToolName:
  private val Shape = "[a-z0-9_-]{1,128}".r

  def problems(name: String): Vector[String] =
    if Shape.matches(name) then Vector.empty
    else Vector(s"tool '$name' is not a tool name ([a-z0-9_-]+, at most 128 characters)")
