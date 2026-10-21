package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{
  Endpoint as EndpointSpec,
  McpAcl as McpAclSpec,
  McpEndpoint as McpEndpointSpec
}
import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, Caller}
import com.thinkmorestupidless.ankka.mcp.{ClientKeys, McpEndpoint, ToolAnswer}
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  RemoteCaller,
  RemoteMcpCall,
  RemotePrincipal
}
import com.thinkmorestupidless.ankka.runtime.{Trace, TraceContext}
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * An MCP endpoint the developer's process or module declared in discovery, served by the sidecar
 * (feature 054).
 *
 * It is an ordinary `McpEndpoint`: the sidecar answers the protocol, applies the endpoint's and
 * each tool's ACL, checks a call's arguments against the tool's schema and opens the tool's span,
 * exactly as for a Scala service, and asks the process only to run the tool. The process is given
 * the principal the credential established and the caller, never the credential.
 */
final class RemoteMcpEndpoint private (
    spec: McpEndpointSpec,
    conversation: Conversation,
    settings: Settings,
    authenticated: Acl
) extends McpEndpoint(spec.path):
  import RemoteMcpEndpoint.*

  val acl: Acl = aclOf(spec.acl, authenticated)

  override def brokered: Boolean = spec.brokered

  override def name: String = Option(spec.name).filter(_.nonEmpty).getOrElse(super.name)

  override def version: String =
    Option(spec.version)
      .filter(_.nonEmpty)
      .getOrElse(com.thinkmorestupidless.ankka.core.BuildInfo.version)

  spec.tools.foreach { t =>
    val described = tool(t.name).describedAs(t.description)
    val owned     = t.acl.fold(described)(a => described.withAcl(aclOf(a, authenticated)))
    owned.raw(Json.parse(t.inputSchemaJson).getOrElse(Json.obj())) { call =>
      run(t.name, call.arguments, call.context)
    }: Unit
  }

  private def run(
      tool: String,
      arguments: Json,
      context: com.thinkmorestupidless.ankka.http.RequestContext
  ): Either[String, ToolAnswer] =
    // The tool's span, which the endpoint opened around this call; the tool as the caller of
    // whatever the process calls for it.
    val trace = Trace.currentContext.getOrElse(TraceContext(Trace.mintHigh(), Trace.mint(), 0L))
    val call = RemoteMcpCall(
      endpointId = spec.id,
      tool = tool,
      argumentsJson = arguments.render,
      principal = context.principal.map(p =>
        RemotePrincipal(p.subject, p.name, p.email, p.emailVerified, p.roles, p.claims, p.issuer)
      ),
      caller = context.caller match
        case Caller.Gateway          => RemoteCaller.Gateway
        case Caller.Service(p, name) => RemoteCaller.Service(p, name)
        case Caller.Machine(o, name) => RemoteCaller.Machine(o, name)
        case Caller.Local            => RemoteCaller.Local,
      metadata = Trace.outbound(Trace.into(Metadata.empty, trace))
    )
    val answered =
      try Await.result(conversation.invokeMcpTool(call), settings.requestTimeout)
      catch
        case _: TimeoutException =>
          return Left(s"tool '$tool' did not answer within ${settings.requestTimeout}")
        case NonFatal(e) =>
          return Left(s"tool '$tool' could not be run: ${e.getMessage}")
    answered match
      case Right(Right(answer)) =>
        Right(
          ToolAnswer(
            answer.text,
            answer.structuredJson.flatMap(Json.parse(_).toOption).collect { case o: Json.Obj => o }
          )
        )
      case Right(Left(message)) => Left(message)
      case Left(failure) =>
        log.warn(
          "MCP endpoint {}: the process could not run tool '{}': {}",
          spec.path,
          tool,
          failure.error.message
        )
        Left(s"tool '$tool' could not be run: ${failure.error.message}")

object RemoteMcpEndpoint:

  private val log = LoggerFactory.getLogger(classOf[RemoteMcpEndpoint])

  def from(
      spec: McpEndpointSpec,
      conversation: Conversation,
      settings: Settings,
      authenticated: Acl = RemoteEndpoint.NoVerifier
  ): RemoteMcpEndpoint =
    new RemoteMcpEndpoint(spec, conversation, settings, authenticated)

  /** Whether any of `specs` admits client keys or is brokered: what needs the MCP runtime. */
  def needsRuntime(specs: Seq[McpEndpointSpec]): Boolean =
    specs.exists(e =>
      e.brokered || e.acl.exists(admitsKeys) || e.tools.exists(_.acl.exists(admitsKeys))
    )

  private def admitsKeys(acl: McpAclSpec): Boolean = acl.clientKeys

  /**
   * An `McpAcl` as the Scala endpoint would state it: admitted when any set member admits. Nothing
   * set, or `deny_all` beside anything, is refused by discovery; here both deny, the backstop.
   */
  private[sidecar] def aclOf(acl: Option[McpAclSpec], authenticated: Acl): Acl =
    acl.fold(Acl.DenyAll)(aclOf(_, authenticated))

  private[sidecar] def aclOf(acl: McpAclSpec, authenticated: Acl): Acl =
    val members = Vector(
      Option.when(acl.allowAll)(Acl.AllowAll),
      Option.when(acl.tokens)(authenticated),
      Option.when(acl.clientKeys)(ClientKeys.authenticate()),
      Option.when(acl.callers.nonEmpty)(RemoteEndpoint.aclOf(EndpointSpec.Acl.CALLERS, acl.callers))
    ).flatten
    if acl.denyAll || members.isEmpty then Acl.DenyAll
    else if members.sizeIs == 1 then members.head
    else Acl.anyOf(members.head, members.tail*)
