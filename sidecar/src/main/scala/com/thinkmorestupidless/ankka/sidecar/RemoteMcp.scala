package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{
  Approval as PbApproval,
  McpServer as PbMcpServer,
  Tool as PbTool
}
import com.thinkmorestupidless.ankka.agent.{Approval, Guardrail}
import com.thinkmorestupidless.ankka.agent.mcp.McpServer

import scala.concurrent.duration.DurationLong
import scala.util.Try

/**
 * What a process declares for an agent's approvals, MCP servers and result guardrails (protocol
 * 1.11), as the Scala agent's own declarations — so the sidecar hosts the same loop, connects to
 * the same servers and enforces the same approvals as for an agent written in Scala.
 */
private[sidecar] object RemoteMcp:

  /** Everything wrong with what was declared, found in discovery rather than at the first call. */
  def problems(
      owner: String,
      servers: Seq[PbMcpServer],
      tools: Seq[PbTool],
      resultGuardrails: Seq[String]
  ): Vector[String] =
    val built = servers.map(s => Try(server(s)).toEither.left.map(e => s"$owner: ${e.getMessage}"))
    val invalid = built.collect { case Left(problem) => problem }
    val valid   = built.collect { case Right(server) => server }
    val listed =
      McpServer.problems(valid.toVector, tools.map(_.name).toVector).map(p => s"$owner: $p")
    val limits = tools.flatMap(t => t.approval.flatMap(_.withinMillis)).filter(_ <= 0).map { ms =>
      s"$owner: a tool's time limit for approval must be positive, not ${ms}ms"
    }
    val doubled = resultGuardrails.groupBy(identity).collect {
      case (name, names) if names.sizeIs > 1 =>
        s"$owner: result guardrail '$name' is declared twice"
    }
    (invalid ++ listed ++ limits ++ doubled).toVector.distinct

  /** A declared server as the agent's own declaration; throws what the declaration would refuse. */
  def server(declared: PbMcpServer): McpServer =
    val address = declared.address match
      case PbMcpServer.Address.Url(url) => McpServer.at(declared.name, url)
      case PbMcpServer.Address.Service(service) =>
        service.project match
          case Some(project) =>
            McpServer.service(declared.name, project, service.name, service.path)
          case None => McpServer.service(declared.name, service.name, service.path)
      case PbMcpServer.Address.Empty => McpServer.named(declared.name)
    val withHeaders = declared.headers.foldLeft(address)((s, h) => s.header(h.name, h.variable))
    declared.approval match
      case Some(PbApproval(Some(ms), _)) => withHeaders.requiresApproval(ms.millis)
      case Some(_)                       => withHeaders.requiresApproval
      case None                          => withHeaders

  def servers(declared: Seq[PbMcpServer]): Vector[McpServer] = declared.map(server).toVector

  /** A tool's approval, as declared. */
  def approval(tool: PbTool): Option[Approval] =
    tool.approval.map(a => Approval(a.withinMillis.map(_.millis)))

  /** A result guardrail the process answers: the result check only. */
  def resultGuardrail(guardName: String)(check: String => Either[String, Unit]): Guardrail =
    new Guardrail:
      val name: String                                             = guardName
      override def checkResult(text: String): Either[String, Unit] = check(text)
