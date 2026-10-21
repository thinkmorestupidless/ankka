package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.http.{
  Acl,
  Admission,
  EncodedResponse,
  GrantTarget,
  HttpEndpoint,
  Mounting,
  PathTemplate,
  RequestContext,
  RequestScope,
  Route,
  ServesRoutes,
  SocketRoute,
  StreamRoute
}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Observability,
  ServedRoute,
  SpanKind,
  SpanOutcome,
  Trace
}
import com.thinkmorestupidless.ankka.sdk.{
  EventSourcedEntityDescriptor,
  KeyValueEntityDescriptor,
  WorkflowDescriptor
}
import com.thinkmorestupidless.ankka.auth.oidc.{OidcConfig, OidcIssuers}
import com.thinkmorestupidless.ankka.mcp.signin.{BrokerAuthenticator, IssuerClient, SignInBroker}
import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

/**
 * A service's MCP endpoint (feature 054): its tools, served to any MCP client over Streamable HTTP
 * at `path`, behind the endpoint's ACL.
 *
 * Underneath it is an `HttpEndpoint` mounted like any other (`HttpServer.of(clients =>
 * CartMcp(clients))`): on the wire it is `POST <path>` for the protocol's messages, and `GET` and
 * `DELETE` answered 405, each behind `acl`. To every reader of a service's routes — the topology,
 * grants, a listing — it is one record per tool, `MCP <path> <tool>`.
 *
 * {{{
 * final class CartMcp(clients: EndpointClients) extends McpEndpoint("/mcp"):
 *   def acl = Acl.AllowAll
 *   val getCart = tool("get_cart").describedAs("Reads one cart").query(ShoppingCart.getCart, id = "cartId")
 * }}}
 *
 * The verbs an `HttpEndpoint` declares routes with (`get`, `post`, `sse`, `socket` and the rest)
 * are not for an MCP endpoint, and the start refuses one that used them.
 */
abstract class McpEndpoint(val path: String = "/mcp") extends HttpEndpoint(path) with ServesRoutes:

  /** `serverInfo.name`: the service's name where it has one. */
  def name: String = mountedName.get()

  /** `serverInfo.version`. */
  def version: String = "0.0.0"

  /**
   * Opts into brokered sign-in (research R11): the platform runs an authorization server for this
   * endpoint at `/_ankka/sign-in<path>`, for a client that can only sign in and an issuer that will
   * not register it. Needs an ACL that admits tokens, a client id at the issuer
   * (`ANKKA_AUTH_<NAME>_CLIENT_ID`), `McpRuntime` and the service's `TimerRuntime`.
   */
  def brokered: Boolean = false

  private val declared    = mutable.ListBuffer.empty[McpTool]
  private val bindings    = Reference.Bindings()
  private val system      = AtomicReference[ActorSystem[?]]()
  private val mountedName = AtomicReference[String]("ankka")
  private val runtime     = AtomicReference[McpRuntime]()
  private val secure      = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * The ACL as the routes carry it, read once. The server reads an endpoint's `acl` on every
   * request, and an ACL that admits client keys is bound to the service when the server starts: a
   * `def acl` would hand each request a fresh, unbound one.
   */
  private lazy val declaredAcl: Acl = acl

  /** The broker's tokens beside the declared ACL's, when the endpoint is brokered. */
  private lazy val brokerTokens = BrokerAuthenticator(path)

  private lazy val frozenAcl: Acl =
    if brokered && declaredAcl != null then Acl.anyOf(declaredAcl, Acl.Authenticate(brokerTokens))
    else declaredAcl

  /** The sign-in broker, once the endpoint is mounted. */
  private val broker = AtomicReference[SignInBroker]()

  /** Declares a tool; finished by `handle`, or by naming what it refers to. */
  protected def tool(name: String): ToolBuilder =
    ToolBuilder(name, "", None, register, bindings)

  private def register(tool: McpTool): McpTool =
    declared += tool
    tool

  /** The tools this endpoint declared, in the order it declared them. */
  final def tools: Vector[McpTool] = declared.toVector

  private def label = s"MCP endpoint $path"

  // The wire: three routes, each behind the endpoint's ACL.

  private lazy val built: Vector[Route] =
    def answering(context: RequestContext, body: Array[Byte]): EncodedResponse =
      val response = Protocol.handle(
        Protocol.Request(context.method, context.headers, body),
        context,
        Protocol.ServerInfo(name, version),
        visibleTo,
        invoke
      )
      EncodedResponse(
        response.status,
        "application/json",
        response.body.map(_.render.getBytes("UTF-8")).getOrElse(Array.emptyByteArray),
        response.headers
      )
    def route(method: String, needsBody: Boolean) = Route(
      method,
      PathTemplate.parse(""),
      needsBody,
      (_, body) =>
        val context = RequestScope.currentContext.getOrElse(
          throw IllegalStateException("an MCP endpoint answers only inside a request")
        )
        answering(context, body)
      ,
      Some(frozenAcl)
    )
    Vector(route("POST", needsBody = true), route("GET", false), route("DELETE", false))

  final override private[ankka] def routes: Vector[Route]             = built
  final override private[ankka] def streamRoutes: Vector[StreamRoute] = Vector.empty
  final override private[ankka] def socketRoutes: Vector[SocketRoute] = Vector.empty

  // Where a client with no credential may sign in: published only when the ACL admits tokens.

  private lazy val metadataRoutes: Vector[Route] =
    if !ResourceMetadata.admitsTokens(declaredAcl) then Vector.empty
    else
      // A brokered endpoint names its broker alone, at the host the client reached it by.
      val declaredServers = ResourceMetadata.authorizationServers(declaredAcl)
      def servers(context: RequestContext) =
        if brokered then Option(broker.get()).map(_.issuerUrl(context)).toVector
        else declaredServers
      Vector(
        Route(
          "GET",
          PathTemplate.parse(ResourceMetadata.documentPath(path)),
          needsBody = false,
          (_, _) =>
            val context = RequestScope.currentContext.getOrElse(
              throw IllegalStateException("resource metadata answers only inside a request")
            )
            EncodedResponse(
              200,
              "application/json",
              ResourceMetadata
                .document(context, path, secure.get(), servers(context))
                .render
                .getBytes("UTF-8"),
              Vector("Cache-Control" -> "max-age=300")
            )
          ,
          Some(Acl.AllowAll)
        )
      )

  private lazy val brokerRoutes: Vector[Route] =
    if brokered then SignInBroker.routes(path, () => Option(broker.get())) else Vector.empty

  final override private[ankka] def rootRoutes: Vector[Route] = metadataRoutes ++ brokerRoutes

  /** A challenge from an endpoint that admits tokens names where its resource metadata is. */
  final override private[ankka] def challenge(request: RequestContext, answered: String): String =
    if metadataRoutes.isEmpty then answered
    else ResourceMetadata.challenge(answered, request, path, secure.get())

  final override private[ankka] def innerAcls: Vector[Acl] = tools.flatMap(_.acl)

  /** A grant names a tool by its own record, which is the target a tool's ACL is decided for. */
  private def target(tool: McpTool) = Some(GrantTarget.Route("MCP", s"$path ${tool.name}"))

  /**
   * The tools the request's caller may see and call: the endpoint admitted the request, so what is
   * left is each tool's own ACL and, for a client key, its scope.
   */
  private def visibleTo(context: RequestContext): Vector[McpTool] =
    val scope = Option(runtime.get()).fold(Scope.Every)(_.scopeOf(context))
    tools.filter(tool => scope.admits(tool.name) && admits(context, tool))

  private[mcp] def admits(
      @scala.annotation.unused context: RequestContext,
      tool: McpTool
  ): Boolean =
    tool.acl.forall(acl => Admission.decide(acl, target(tool)).isRight)

  /**
   * A tool call is a span of its own under the request's: component `mcp`, handler `<path> <tool>`.
   */
  private def invoke(tool: McpTool, call: ToolCall): ToolOutcome =
    Option(system.get()) match
      case None         => tool.run(call)
      case Some(system) => traced(system, tool, call)

  private def traced(system: ActorSystem[?], tool: McpTool, call: ToolCall): ToolOutcome =
    val observability = Observability(system)
    val recorder      = observability.recorder
    val componentRef  = observability.names.intern("mcp")
    val handlerRef    = observability.names.intern(s"$path ${tool.name}")
    val span = Trace.currentContext match
      case Some(parent) =>
        recorder.begin(
          parent.traceIdHigh,
          parent.traceId,
          parent.spanId,
          componentRef,
          handlerRef,
          SpanKind.Server
        )
      case None => recorder.beginRoot(componentRef, handlerRef, SpanKind.Server)
    call.context.principal.foreach { p =>
      recorder.withSubject(span, p.subject)
      if p.issuer.contains(McpRuntime.Issuer) then
        p.claims.get("client_key").foreach(recorder.withAttribute(span, "client_key", _))
    }
    var outcome = SpanOutcome.Failed
    try
      val result =
        Trace.within(span, CallOrigin(ServedRoute.endpointId(path), s"MCP $path ${tool.name}")) {
          tool.run(call)
        }
      outcome = result match
        case ToolOutcome.Answered(_)     => SpanOutcome.Ok
        case ToolOutcome.Failed(_)       => SpanOutcome.Failed
        case ToolOutcome.BadArguments(_) => SpanOutcome.Refused
      result
    finally recorder.complete(span, outcome)

  // What every reader of the service's routes sees.

  final def served: Vector[ServedRoute] =
    val id = ServedRoute.endpointId(path)
    rootRoutes.map(r => ServedRoute(r.method, r.template.render, streaming = false, id)) ++
      tools.map(tool =>
        ServedRoute(
          "MCP",
          s"$path ${tool.name}",
          streaming = false,
          id,
          Acl.namesGranted(tool.acl.getOrElse(acl))
        )
      )

  // What fails the start.

  final override private[ankka] def problems(mounting: Mounting): Vector[String] =
    mounting.service.foreach { service =>
      system.set(service.system)
      val config = service.system.settings.config
      secure.set(
        config.hasPath("ankka.http.tls.enabled") && config.getBoolean("ankka.http.tls.enabled")
      )
      service.identity.toOption
        .flatMap(_.service)
        .orElse(Some(service.system.name))
        .foreach(
          mountedName.set
        )
      service.extension[McpRuntime].foreach { found =>
        runtime.set(found)
        found.register(this)
      }
    }
    val found = Vector.newBuilder[String]
    val here  = McpEndpoint.normalised(path)
    if here.startsWith("/_ankka") || here.startsWith("/.well-known") then
      found += s"$label path is reserved"
    if acl == null then found += s"$label states no ACL; an MCP endpoint must state its ACL"
    if brokered && acl != null then found ++= brokerProblems(mounting)
    if super.routes.nonEmpty || super.streamRoutes.nonEmpty || super.socketRoutes.nonEmpty then
      found += s"$label declares routes; an MCP endpoint declares tools"
    if tools.isEmpty then found += s"$label declares no tool"
    tools.foreach(tool => ToolName.problems(tool.name).foreach(p => found += s"$label: $p"))
    tools.groupBy(_.name).foreach { (name, same) =>
      if same.sizeIs > 1 then found += s"$label declares the tool '$name' ${same.size} times"
    }
    tools.foreach { tool =>
      if tool.description.trim.isEmpty then
        found += s"$label: tool '${tool.name}' has no description"
    }
    tools.foreach(tool => resolve(tool, mounting).foreach(p => found += s"$label: $p"))
    found ++= clashes(mounting)
    found.result()

  /**
   * What a brokered endpoint needs, and the broker built from it: the service's issuers from its
   * ACL, a client id at the first, the MCP runtime and the timers.
   */
  private def brokerProblems(mounting: Mounting): Vector[String] =
    val issuers = ResourceMetadata
      .tokenAuthenticators(declaredAcl)
      .collect { case o: OidcIssuers => o.configured }
      .flatten
    val found = Vector.newBuilder[String]
    if issuers.isEmpty then
      found += s"$label opts into brokered sign-in and its ACL admits no token; use Oidc.authenticate()"
    issuers.filter(_.clientId.isEmpty).foreach { issuer =>
      found += s"$label opts into brokered sign-in and the issuer '${issuer.name}' has no client id; " +
        s"set ANKKA_AUTH_${OidcConfig.segment(issuer.name)}_CLIENT_ID"
    }
    mounting.service.foreach { service =>
      if service.extension[TimerRuntime].isEmpty then
        found += s"$label opts into brokered sign-in, which needs the service's TimerRuntime: register TimerRuntime()"
      (service.extension[McpRuntime], issuers.find(_.clientId.isDefined)) match
        case (None, _) =>
          found += s"$label opts into brokered sign-in and the service registers no McpRuntime; " +
            "add McpRuntime() and McpRuntime.descriptors to the service"
        case (Some(runtime), Some(issuer)) =>
          runtime.attach(service)
          broker.set(
            SignInBroker(
              path,
              IssuerClient(issuer),
              service.componentClient,
              runtime.timers,
              runtime.brokerTokens,
              runtime.brokerKeys,
              runtime.signInSettings,
              runtime.clockOf,
              secure.get()
            )
          )
        case _ => ()
    }
    found.result()

  /** Binds a reference to what it names, or says why it cannot be. */
  private def resolve(tool: McpTool, mounting: Mounting): Option[String] =
    def handler(component: String, handler: String, kind: String): Option[String] =
      mounting.registry.components.find(_.componentId.toString == component) match
        case None =>
          Some(
            s"tool '${tool.name}' refers to the component '$component', which this service does not register"
          )
        case Some(descriptor) =>
          val handlers = descriptor match
            case d: EventSourcedEntityDescriptor[?, ?, ?] => d.handlers.keySet.map(_.toString)
            case d: KeyValueEntityDescriptor[?, ?]        => d.handlers.keySet.map(_.toString)
            case d: WorkflowDescriptor[?, ?]              => d.handlers.keySet.map(_.toString)
            case _                                        => Set.empty[String]
          if !handlers.contains(handler) then
            Some(
              s"tool '${tool.name}' refers to the $kind '$handler' of '$component', which has no such handler"
            )
          else
            mounting.service.foreach { service =>
              val transport = service.componentClient.transportRef
              bindings.transports
                .get(tool.name)
                .foreach(_.bind(Reference.Transport(transport, transport.askTimeout)))
            }
            None
    tool.kind match
      case ToolKind.Handler                 => None
      case ToolKind.Command(component, cmd) => handler(component, cmd, "command")
      case ToolKind.Query(component, query) => handler(component, query, "query")
      case ToolKind.Route(prefix, method, whole) =>
        val wanted = McpEndpoint.normalised(whole)
        val found = mounting.endpoints
          .filter(e => McpEndpoint.normalised(e.prefix) == McpEndpoint.normalised(prefix))
          .flatMap(e => e.routes.map(r => (e, r)))
          .find((e, r) =>
            r.method == method && McpEndpoint.normalised(
              s"${e.prefix}${r.template.render}"
            ) == wanted
          )
        found match
          case None =>
            Some(
              s"tool '${tool.name}' refers to the route '$method $whole' of '$prefix', which has no such route"
            )
          case Some((_, route)) =>
            bindings.routes.get(tool.name).foreach(_.bind(route))
            None

  /** The endpoint's path claimed twice: by another MCP endpoint, or by another endpoint's route. */
  private def clashes(mounting: Mounting): Vector[String] =
    val here = McpEndpoint.normalised(path)
    val mcps = mounting.endpoints.collect {
      case m: McpEndpoint if McpEndpoint.normalised(m.path) == here => m
    }
    val twice =
      if mcps.sizeIs > 1 && (mcps.head eq this) then
        Vector(
          s"MCP endpoints ${mcps.map(m => s"'${m.getClass.getSimpleName}'").mkString(" and ")} " +
            s"are both at $path"
        )
      else Vector.empty
    val routes = mounting.endpoints
      .filterNot(_.isInstanceOf[McpEndpoint])
      .flatMap(e =>
        (e.routes.map(r => (r.method, r.template)) ++ e.streamRoutes.map(r =>
          (r.method, r.template)
        ))
          .map((method, template) => (method, s"${e.prefix}${template.render}", e.prefix))
      )
      .filter((_, full, _) => McpEndpoint.normalised(full) == here)
      .map((method, full, prefix) =>
        s"$label and the route '$method ${McpEndpoint.normalised(full)}' of '$prefix' claim one path"
      )
    twice ++ routes

object McpEndpoint:
  private[mcp] def normalised(path: String): String =
    "/" + path.split('/').filter(_.nonEmpty).mkString("/")
