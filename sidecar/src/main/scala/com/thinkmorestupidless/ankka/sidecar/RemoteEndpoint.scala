package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{
  CallerMatcher as CallerMatcherSpec,
  Endpoint as EndpointSpec,
  Route as RouteSpec
}
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  Caller,
  CallerMatcher,
  EncodedResponse,
  HttpEndpoint,
  HttpProblem,
  PathTemplate,
  Route,
  Socket,
  SocketClosed,
  SocketRoute,
  StreamRoute
}
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  HttpForward,
  RemoteCaller,
  RemotePrincipal,
  SocketOutput
}
import com.thinkmorestupidless.ankka.runtime.{ServedRoute, Trace, TraceContext}

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * An HTTP endpoint the developer's process declared in discovery, served by the sidecar.
 *
 * It is an ordinary `HttpEndpoint`: the sidecar's router matches the route by the existing
 * specificity rule, applies the ACL and opens the request span before this class is asked for
 * anything, and what it is asked for is one forwarded request per route. The handler runs on a
 * virtual thread like any Scala handler, so the wait on the process is a blocking `await` and the
 * request context is sound for its duration.
 */
final class RemoteEndpoint private (
    spec: EndpointSpec,
    conversation: Conversation,
    settings: Settings,
    authenticated: Acl
) extends HttpEndpoint(spec.prefix):

  def acl: Acl = RemoteEndpoint.aclOf(spec.acl, spec.allowCallers, authenticated)

  private val (sockets, requests) = spec.routes.toVector.partition(_.socket)
  private val (plain, streaming)  = requests.partition(!_.streaming)

  private[ankka] override def routes: Vector[Route] =
    plain.map { r =>
      Route(
        r.method.toUpperCase,
        PathTemplate.parse(r.template),
        r.hasBody,
        (args, body) => forward(r, args, body),
        r.acl.map(RemoteEndpoint.aclOf(_, r.allowCallers, authenticated))
      )
    }

  private[ankka] override def streamRoutes: Vector[StreamRoute] =
    streaming.map { r =>
      StreamRoute(
        r.method.toUpperCase,
        PathTemplate.parse(r.template),
        r.hasBody,
        (args, body) => conversation.handleHttpStream(forwardOf(r, args, body)),
        r.acl.map(RemoteEndpoint.aclOf(_, r.allowCallers, authenticated))
      )
    }

  private[ankka] override def socketRoutes: Vector[SocketRoute] =
    sockets.map { r =>
      SocketRoute(
        PathTemplate.parse(r.template),
        args => socket => relay(r, args, socket),
        r.acl.map(RemoteEndpoint.aclOf(_, r.allowCallers, authenticated))
      )
    }

  /**
   * Relays one open socket to the process and back. The sidecar's own server already decided the
   * ACL, holds the limits and will send the close code; this moves frames. The client's frames go
   * on a virtual thread of their own, the process's on this one, and the handler ends when the
   * process does: returning when its handler returned, throwing when it failed or went away — which
   * the server closes as "failed".
   */
  private def relay(r: RouteSpec, args: Vector[String], socket: Socket): Unit =
    val link = conversation.openSocket(forwardOf(r, args, Array.emptyByteArray))
    val toProcess = Thread.ofVirtual().start { () =>
      var open = true
      while open do
        socket.receive() match
          case Some(text) => open = link.send(text)
          case None       => open = false
      link.close(socket.closedBecause.getOrElse("client"))
    }
    try
      // A closed socket's process is given the request timeout to finish; one that never does is
      // let go of, rather than holding a virtual thread for ever.
      var closedAt: Option[Long] = None
      var done                   = false
      while !done do
        link.next(200.millis) match
          case Some(SocketOutput.Frame(text)) =>
            try socket.send(text)
            catch case _: SocketClosed => () // the client has gone; the process is being told
          case Some(SocketOutput.Completed)     => done = true
          case Some(SocketOutput.Failed(error)) => throw ProcessSocketFailed(error)
          case None =>
            if socket.closedBecause.isDefined then
              val since = closedAt.getOrElse {
                val now = System.nanoTime(); closedAt = Some(now); now
              }
              if (System.nanoTime() - since).nanos > settings.requestTimeout then done = true
    finally
      link.close(socket.closedBecause.getOrElse("finished"))
      toProcess.join(1000): Unit

  /** What the local console lists for this endpoint. */
  def served: Vector[ServedRoute] =
    // Named by its prefix, as the HTTP server names every endpoint it serves: the server reports
    // these same routes, and one endpoint under two names would be drawn twice.
    val id = ServedRoute.endpointId(spec.prefix)
    plain.map(r =>
      ServedRoute(r.method.toUpperCase, spec.prefix + r.template, streaming = false, id)
    ) ++
      streaming.map(r =>
        ServedRoute(r.method.toUpperCase, spec.prefix + r.template, streaming = true, id)
      ) ++
      sockets.map(r => ServedRoute("SOCKET", spec.prefix + r.template, streaming = true, id))

  private def forwardOf(r: RouteSpec, args: Vector[String], body: Array[Byte]): HttpForward =
    val ctx   = request
    val trace = Trace.currentContext.getOrElse(TraceContext(Trace.mintHigh(), Trace.mint(), 0L))
    HttpForward(
      endpointId = spec.id,
      routeId = r.id,
      pathArgs = args,
      query = ctx.query.toSeq.toVector,
      headers = ctx.headers,
      contentType = ctx.header("Content-Type").getOrElse(""),
      body = body,
      principal = ctx.principal.map(p =>
        RemotePrincipal(p.subject, p.name, p.email, p.emailVerified, p.roles, p.claims, p.issuer)
      ),
      caller = ctx.caller match
        case Caller.Gateway          => RemoteCaller.Gateway
        case Caller.Service(p, name) => RemoteCaller.Service(p, name)
        case Caller.Local            => RemoteCaller.Local,
      // The request's span, and the route as the caller of whatever the process calls for it.
      metadata = Trace.outbound(Trace.into(Metadata.empty, trace))
    )

  private def forward(r: RouteSpec, args: Vector[String], body: Array[Byte]): EncodedResponse =
    val result =
      try Await.result(conversation.handleHttp(forwardOf(r, args, body)), settings.requestTimeout)
      catch
        case _: TimeoutException =>
          throw HttpProblem(503, s"the process did not answer within ${settings.requestTimeout}")
        case NonFatal(e) =>
          throw HttpProblem(503, s"the process could not be reached: ${e.getMessage}")
    result match
      case Right(response) =>
        EncodedResponse(response.status, response.contentType, response.body)
      case Left(failure) =>
        throw HttpProblem(500, failure.error.message)

/** The process's handler for a socket failed, or the process went away while it was open. */
final class ProcessSocketFailed(message: String) extends RuntimeException(message):
  override def fillInStackTrace(): Throwable = this

object RemoteEndpoint:

  /**
   * The largest frame a socket behind a sidecar may be given. A frame crosses to the process as one
   * gRPC message, whose bound is 4 MiB; what is left is for the message around it.
   */
  val MaxRelayedFrame: Long = 3L * 1024 * 1024

  /** What is wrong with a sidecar's socket limits, for a service that declares a socket route. */
  def socketProblems(config: com.typesafe.config.Config): Vector[String] =
    val max = config.getBytes("ankka.http.socket.max-frame-size")
    Vector(
      Option.when(max > MaxRelayedFrame)(
        s"ankka.http.socket.max-frame-size ($max bytes) is larger than a frame a sidecar can relay " +
          s"to its process ($MaxRelayedFrame bytes, under gRPC's 4 MiB message bound)"
      )
    ).flatten

  /**
   * What an `AUTHENTICATED` route answers when the sidecar has no issuer: 503, exactly as a Scala
   * endpoint with an `Authenticate` that cannot verify would. Discovery refuses such a route before
   * the service starts (feature 022), so this is the backstop, never the answer a caller sees.
   */
  val NoVerifier: Acl =
    Acl.Authenticate(_ => AuthDecision.Unavailable("no issuer is configured on this sidecar"))

  /**
   * `authenticated` is the rule every `AUTHENTICATED` route answers with: the issuers' verifier.
   */
  def from(
      spec: EndpointSpec,
      conversation: Conversation,
      settings: Settings,
      authenticated: Acl = NoVerifier
  ): RemoteEndpoint =
    new RemoteEndpoint(spec, conversation, settings, authenticated)

  /**
   * One mapping, used for an endpoint's ACL and for a route's own.
   *
   * A route that declares nothing is `None` in the protocol, which `HttpEndpoint` already reads as
   * "the endpoint's" — so a process built against a protocol without the field keeps exactly the
   * endpoint-wide behaviour it was written for.
   */
  private[sidecar] def aclOf(
      acl: EndpointSpec.Acl,
      callers: Seq[CallerMatcherSpec],
      authenticated: Acl = NoVerifier
  ): Acl =
    acl match
      case EndpointSpec.Acl.CALLERS =>
        // An empty list would admit only Local — silently open locally and closed in a cluster, the
        // worst shape a misconfiguration can have. Discovery refuses it; this is the backstop.
        if callers.isEmpty then Acl.DenyAll else Acl.AllowCallers(callers.toVector.map(matcherOf))
      case other => plainAcl(other, authenticated)

  private def matcherOf(spec: CallerMatcherSpec): CallerMatcher = spec.kind match
    case CallerMatcherSpec.Kind.Internet(_) => CallerMatcher.Internet
    case CallerMatcherSpec.Kind.Service(named) =>
      CallerMatcher.NamedService(named.project, named.name)
    case CallerMatcherSpec.Kind.AnyInProject(_) => CallerMatcher.AnyInProject
    case CallerMatcherSpec.Kind.Self(_)         => CallerMatcher.Self
    // A matcher kind this sidecar does not know — a newer SDK's — admits nobody rather than guessing.
    case CallerMatcherSpec.Kind.Empty => CallerMatcher.NamedService(Some(""), "")

  private def plainAcl(acl: EndpointSpec.Acl, authenticated: Acl): Acl =
    acl match
      case EndpointSpec.Acl.DENY_ALL      => Acl.DenyAll
      case EndpointSpec.Acl.AUTHENTICATED => authenticated
      case _                              => Acl.AllowAll
