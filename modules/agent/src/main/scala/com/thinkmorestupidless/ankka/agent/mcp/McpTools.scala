package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.agent.{ApprovalExpiry, FunctionTool, ToolOrigin, ToolSpec}
import com.thinkmorestupidless.ankka.sdk.ServiceClients

import scala.concurrent.duration.FiniteDuration

/**
 * Connects an agent's MCP servers when the service starts, and gives each server's tools as
 * function tools the agent offers its model beside its own.
 *
 * Everything that can be wrong is found here, before the agent is hosted, and fails the start
 * naming the server and the agent: a server with no address, a header whose variable is not set, a
 * time limit with no timers, a server that cannot be reached or refuses the platform, a tool whose
 * whole name a provider would refuse.
 */
private[ankka] object McpTools:

  /** The longest tool name the model's provider accepts. */
  val MaxToolName: Int = 128

  final case class Settings(
      connectTimeout: FiniteDuration,
      callTimeout: FiniteDuration,
      clientVersion: String
  )

  def connect(
      agentId: String,
      servers: Vector[McpServer],
      variables: String => Option[String],
      services: => ServiceClients,
      timersAvailable: Boolean,
      settings: Settings
  ): Vector[FunctionTool] =
    servers.flatMap { server =>
      def refuse(problem: String): Nothing =
        throw IllegalStateException(
          s"agent '$agentId' cannot use MCP server '${server.name}': $problem"
        )

      if server.approval.exists(_.within.isDefined) && !timersAvailable then
        refuse(ApprovalExpiry.needsTimers(s"MCP server '${server.name}'"))

      val transport: McpTransport =
        variables(McpServer.urlVariable(server.name)).filter(_.nonEmpty) match
          case Some(url) => McpTransport.Url(url, settings.connectTimeout, settings.callTimeout)
          case None =>
            server.address match
              case McpServer.Address.Url(url) =>
                McpTransport.Url(url, settings.connectTimeout, settings.callTimeout)
              case McpServer.Address.Service(project, name, path) =>
                McpTransport.Service(
                  project.fold(services(name))(p => services(p, name)),
                  path
                )
              case McpServer.Address.FromVariable =>
                refuse(s"it has no address: set the variable ${McpServer.urlVariable(server.name)}")

      val headers = server.headers.map { header =>
        header.name -> variables(header.variable).getOrElse(
          refuse(
            s"its header '${header.name}' takes its value from ${header.variable}, which is not set"
          )
        )
      }

      val client = McpClient(server.name, transport, headers, settings.clientVersion)
      val tools =
        try
          client.initialize()
          client.listTools()
        catch case failure: McpUnavailable => refuse(failure.getMessage)

      tools.map { tool =>
        val name = McpServer.toolName(server.name, tool.name)
        if name.length > MaxToolName then
          refuse(
            s"its tool '${tool.name}' would be offered as '$name', longer than $MaxToolName characters"
          )
        FunctionTool.raw(
          ToolSpec(
            name,
            if tool.description.nonEmpty then tool.description
            else s"The tool '${tool.name}' of the MCP server '${server.name}'.",
            tool.inputSchema
          ),
          approval = server.approval,
          origin = ToolOrigin.Mcp(server.name)
        )(arguments => client.callTool(tool.name, arguments))
      }
    }
