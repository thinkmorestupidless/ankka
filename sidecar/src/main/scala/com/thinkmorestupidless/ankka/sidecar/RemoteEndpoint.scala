package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{Endpoint as EndpointSpec, Route as RouteSpec}
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  EncodedResponse,
  HttpEndpoint,
  HttpProblem,
  PathTemplate,
  Route,
  StreamRoute
}
import com.thinkmorestupidless.ankka.runtime.remote.{Conversation, HttpForward, RemotePrincipal}
import com.thinkmorestupidless.ankka.runtime.{ServedRoute, Trace}

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
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
    settings: Settings
) extends HttpEndpoint(spec.prefix):

  def acl: Acl = spec.acl match
    case EndpointSpec.Acl.DENY_ALL      => Acl.DenyAll
    case EndpointSpec.Acl.AUTHENTICATED =>
      // The sidecar configures no verifier in this feature; an authenticated route answers 503,
      // exactly as a Scala endpoint with `Authenticate` and no verifier would.
      Acl.Authenticate(_ =>
        AuthDecision.Unavailable("no authenticator is configured on this sidecar")
      )
    case _ => Acl.AllowAll

  private val (plain, streaming) = spec.routes.toVector.partition(!_.streaming)

  private[ankka] override def routes: Vector[Route] =
    plain.map { r =>
      Route(
        r.method.toUpperCase,
        PathTemplate.parse(r.template),
        r.hasBody,
        (args, body) => forward(r, args, body)
      )
    }

  private[ankka] override def streamRoutes: Vector[StreamRoute] =
    streaming.map { r =>
      StreamRoute(
        r.method.toUpperCase,
        PathTemplate.parse(r.template),
        r.hasBody,
        (args, body) => conversation.handleHttpStream(forwardOf(r, args, body))
      )
    }

  /** What the local console lists for this endpoint. */
  def served: Vector[ServedRoute] =
    plain.map(r =>
      ServedRoute(r.method.toUpperCase, spec.prefix + r.template, streaming = false)
    ) ++
      streaming.map(r =>
        ServedRoute(r.method.toUpperCase, spec.prefix + r.template, streaming = true)
      )

  private def forwardOf(r: RouteSpec, args: Vector[String], body: Array[Byte]): HttpForward =
    val ctx               = request
    val (traceId, spanId) = Trace.currentTrace.getOrElse((Trace.mint(), 0L))
    HttpForward(
      endpointId = spec.id,
      routeId = r.id,
      pathArgs = args,
      query = ctx.query.toSeq.toVector,
      headers = ctx.headers,
      contentType = ctx.header("Content-Type").getOrElse(""),
      body = body,
      principal = ctx.principal.map(p =>
        RemotePrincipal(p.subject, p.name, p.email, p.emailVerified, p.roles)
      ),
      metadata = Trace.into(Metadata.empty, traceId, spanId)
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

object RemoteEndpoint:
  def from(spec: EndpointSpec, conversation: Conversation, settings: Settings): RemoteEndpoint =
    new RemoteEndpoint(spec, conversation, settings)
