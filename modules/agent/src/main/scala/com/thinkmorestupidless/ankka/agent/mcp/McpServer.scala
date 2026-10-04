package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.Approval

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * An MCP server whose tools an agent offers its model, beside its own.
 *
 * Listed on the agent and connected when the service starts: its tools are read then, and offered
 * as `mcp__<server>__<tool>`. A server that cannot be reached fails the start, naming it — the same
 * rule that fails a service whose component is not registered at startup rather than at its first
 * request.
 *
 * Where it is, and what it is sent, belong to the environment more than to the code: a server named
 * here with no address is found at the variable `ANKKA_MCP_<NAME>_URL`, which replaces any address
 * given here when set, and a credential is a header whose value is a variable's. Every variable
 * starting `ANKKA_MCP_` goes to the platform's program only, which is what connects.
 */
final case class McpServer private (
    name: String,
    address: McpServer.Address,
    approval: Option[Approval],
    headers: Vector[McpServer.Header]
):

  /**
   * A header sent on every request to this server and no other, its value taken from `variable`
   * when the service starts. The value appears in no definition, discovery, log or trace.
   */
  def header(name: String, variable: String): McpServer =
    if name.isBlank then
      throw IllegalArgumentException(s"MCP server '${this.name}' has a header with no name")
    else if !variable.startsWith(McpServer.VariablePrefix) then
      throw IllegalArgumentException(
        s"MCP server '${this.name}': the header '$name' takes its value from '$variable', which " +
          s"must start ${McpServer.VariablePrefix} — only those variables reach the platform's " +
          "program, which is what connects to the server"
      )
    else copy(headers = headers :+ McpServer.Header(name, variable))

  /** Every tool of this server waits for a person's decision before it runs. */
  def requiresApproval: McpServer = copy(approval = Some(Approval(None)))

  /** As `requiresApproval`, refused by the platform when `within` passes with no decision. */
  def requiresApproval(within: FiniteDuration): McpServer =
    if within <= Duration.Zero then
      throw IllegalArgumentException(
        s"MCP server '$name' needs a positive time limit for approval, not $within"
      )
    else copy(approval = Some(Approval(Some(within))))

object McpServer:

  /** Where a server is. */
  enum Address:
    case Url(url: String)

    /** An ankka service, called as one: the agent's service presents its certificate. */
    case Service(project: Option[String], name: String, path: String)

    /** Nowhere in the code: the variable `ANKKA_MCP_<NAME>_URL` says. */
    case FromVariable

  final case class Header(name: String, variable: String)

  val VariablePrefix: String = "ANKKA_MCP_"

  /** What every MCP tool's name starts with. */
  val ToolPrefix: String = "mcp__"

  private val NamePattern = "[a-z0-9-]+".r

  /** A server at a URL. */
  def at(name: String, url: String): McpServer =
    if url.isBlank then throw IllegalArgumentException(s"MCP server '$name' needs a URL")
    else checked(name, Address.Url(url))

  /** A server that is an ankka service in this service's project, at `path`. */
  def service(name: String, service: String, path: String): McpServer =
    checked(name, Address.Service(None, service, path))

  /** A server that is an ankka service in another project, at `path`. */
  def service(name: String, project: String, service: String, path: String): McpServer =
    checked(name, Address.Service(Some(project), service, path))

  /** A server whose address is the variable `ANKKA_MCP_<NAME>_URL`'s. */
  def named(name: String): McpServer = checked(name, Address.FromVariable)

  private def checked(name: String, address: Address): McpServer =
    if !NamePattern.matches(name) then
      throw IllegalArgumentException(
        s"an MCP server's name is lower-case letters, digits and hyphens ([a-z0-9-]), not '$name'"
      )
    else McpServer(name, address, None, Vector.empty)

  /** The variable that gives, or replaces, a server's address. */
  def urlVariable(server: String): String =
    s"$VariablePrefix${server.toUpperCase.replace('-', '_')}_URL"

  /** The name a server's tool is offered to the model under. */
  def toolName(server: String, tool: String): String = s"$ToolPrefix${server}__$tool"

  /** The server and tool a name was made from, if it is an MCP tool's. */
  def parseToolName(name: String): Option[(String, String)] =
    if !name.startsWith(ToolPrefix) then None
    else
      val rest = name.drop(ToolPrefix.length)
      rest.indexOf("__") match
        case -1 => None
        case i  => Some((rest.take(i), rest.drop(i + 2)))

  /**
   * What is wrong with an agent's servers, found where its definition is built: one name used
   * twice, and an agent's own tool named as an MCP tool would be.
   */
  def problems(servers: Vector[McpServer], ownTools: Vector[String]): Vector[String] =
    val twice = servers.groupBy(_.name).collect {
      case (name, listed) if listed.sizeIs > 1 =>
        s"MCP server '$name' is listed ${listed.size} times"
    }
    val squatting = ownTools.filter(_.startsWith(ToolPrefix)).map { tool =>
      s"tool '$tool' takes the prefix '$ToolPrefix', which names an MCP server's tools"
    }
    (twice ++ squatting).toVector.sorted
