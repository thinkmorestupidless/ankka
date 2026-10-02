package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.{Observability, ServedRoute, SpanOutcome, Trace}
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService, RuntimeExtension}
import org.apache.pekko.actor.typed.ActorSystem
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.marshalling.sse.EventStreamMarshalling.*
import org.apache.pekko.http.scaladsl.marshalling.Marshal

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.Ordering.Implicits.seqOrdering
import scala.util.control.NonFatal

/**
 * Serves a set of endpoints over HTTP.
 *
 * Routing is done directly against `HttpRequest` rather than through pekko-http's Directives. The
 * routing table is already a value ankka owns — a list of parsed templates per endpoint — so
 * re-expressing it in a second DSL would add a layer without adding a capability.
 */
final class HttpServer private (
    factories: Seq[EndpointClients => HttpEndpoint],
    interface: Option[String],
    port: Option[Int],
    tlsDirectory: Option[java.nio.file.Path] = None
) extends RuntimeExtension:

  /**
   * This server alone serves mutual TLS from `directory`, whatever the configuration says — for a
   * suite that measures one server with TLS beside one without, in one service.
   */
  private[ankka] def withTls(directory: java.nio.file.Path): HttpServer =
    new HttpServer(factories, interface, port, Some(directory))

  @volatile private var binding: Option[Http.ServerBinding] = None
  @volatile private var served: Vector[ServedRoute]         = Vector.empty
  @volatile private var scheme: String                      = "http"

  def name: String = "http-server"

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system

    val config   = system.settings.config
    val host     = interface.getOrElse(config.getString("ankka.http.interface"))
    val bindPort = port.getOrElse(config.getInt("ankka.http.port"))
    val bodyTimeout = FiniteDuration(
      config.getDuration("ankka.http.body-timeout").toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )

    val clients = EndpointClients(service.componentClient, service.viewClient, service.services)
    serve(factories.map(_(clients)).toVector, host, bindPort, bodyTimeout)

  /**
   * Validates, logs and binds `endpoints` — everything `start` does once the endpoints exist. Split
   * out so a suite can serve real endpoints over real TLS without a whole service behind them.
   */
  private[http] def serve(
      endpoints: Vector[HttpEndpoint],
      host: String,
      bindPort: Int,
      bodyTimeout: FiniteDuration
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val config             = system.settings.config
    validate(endpoints)
    // Kept so the local console can render a form per route. A description, not a door.
    served = endpoints.flatMap { endpoint =>
      endpoint.routes.map(r =>
        ServedRoute(r.method, s"${endpoint.prefix}${r.template.render}", streaming = false)
      ) ++ endpoint.streamRoutes.map(r =>
        ServedRoute(r.method, s"${endpoint.prefix}${r.template.render}", streaming = true)
      )
    }

    endpoints.foreach { endpoint =>
      endpoint.routes.foreach { route =>
        system.log.info("route {} {}{}", route.method, endpoint.prefix, route.template.render)
      }
      endpoint.streamRoutes.foreach { route =>
        system.log.info("route {} {}{} (SSE)", route.method, endpoint.prefix, route.template.render)
      }
      // Judged per route, not on the endpoint's own acl: a DenyAll endpoint that opens one route
      // with `withAcl` is a deliberate shape, and warning about it would teach the reader to
      // ignore the warning that matters — an endpoint nothing can reach.
      val effective = endpoint.routes.map(_.acl.getOrElse(endpoint.acl)) ++
        endpoint.streamRoutes.map(_.acl.getOrElse(endpoint.acl))
      if effective.forall(_ == Acl.DenyAll) && (effective.nonEmpty || endpoint.acl == Acl.DenyAll)
      then
        system.log.warn(
          "nothing on endpoint '{}' is reachable; every route is denied by its acl",
          endpoint.prefix
        )
    }

    val tls     = serviceTls(config)
    val callers = CallerSource(tls)
    val handler = Router(endpoints, bodyTimeout, callers).handle

    val server = Http()(using system).newServerAt(host, bindPort)
    val bound = Await.result(
      tls match
        case Some(identity) =>
          // The caller is read from the session's client certificate, which Pekko hands a route
          // only as the `Tls-Session-Info` header, and only when asked. Without it every request
          // is refused as having no certificate, so a TLS binding asks for it itself rather than
          // trusting whichever configuration the process happened to load.
          val settings = org.apache.pekko.http.scaladsl.settings.ServerSettings(system)
          server
            .withSettings(
              settings.withParserSettings(
                settings.parserSettings.withIncludeTlsSessionInfoHeader(true)
              )
            )
            .enableHttps(ConnectionContext.httpsServer(() => identity.serverEngine()))
            .bind(handler)
        case None => server.bind(handler)
      ,
      30.seconds
    )
    binding = Some(bound)
    scheme = if tls.isDefined then "https" else "http"
    system.log.info(
      "ankka http listening on {}://{}:{}",
      scheme,
      bound.localAddress.getHostString,
      bound.localAddress.getPort
    )
    val namesCallers = endpoints.exists(e =>
      (e.acl +: (e.routes.flatMap(_.acl) ++ e.streamRoutes.flatMap(_.acl))).exists {
        case Acl.AllowCallers(_) => true
        case _                   => false
      }
    )
    if tls.isEmpty && namesCallers then
      system.log.info(
        "caller identity is not enforced outside a cluster: every request is Caller.Local"
      )

  override def stop(): Unit =
    binding.foreach(b => Await.ready(b.terminate(5.seconds), 10.seconds))
    binding = None

  /** Not ready until bound: a member that cannot yet answer a request must not receive one. */
  override def readiness: Option[() => Boolean] = Some(() => binding.isDefined)

  /** The bound port, useful when the configured port was 0. */
  def boundPort: Option[Int] = binding.map(_.localAddress.getPort)

  /**
   * Where this server is actually serving, for the local console's invoke panel.
   *
   * Loopback rather than the bound host: the console runs on the same machine, and a service bound
   * to 0.0.0.0 should not advertise that as an address to call.
   */
  override def routes: Vector[ServedRoute] = served

  override def boundAddress: Option[String] =
    binding.map(b => s"$scheme://127.0.0.1:${b.localAddress.getPort}")

  /**
   * The service certificate when this process serves mutual TLS — the Kubernetes overlay's setting,
   * never a local run's. A missing file fails startup naming it.
   */
  private def serviceTls(config: com.typesafe.config.Config): Option[RotatingTls] =
    val enabled =
      config.hasPath("ankka.http.tls.enabled") && config.getBoolean("ankka.http.tls.enabled")
    tlsDirectory
      .map(RotatingTls(_, 1.minute))
      .orElse(Option.when(enabled) {
        val directory = config.getString("ankka.tls.service-directory")
        if directory.isEmpty then
          throw IllegalStateException(
            "ankka.http.tls.enabled is on but ankka.tls.service-directory is empty"
          )
        RotatingTls(
          java.nio.file.Paths.get(directory),
          FiniteDuration(
            config.getDuration("ankka.tls.reload-interval").toMillis,
            java.util.concurrent.TimeUnit.MILLISECONDS
          )
        )
      })

  /**
   * Rejects two endpoints sharing a prefix, and duplicate routes within one endpoint.
   *
   * Overlapping prefixes would make dispatch depend on registration order, which is the kind of
   * thing that works locally and then serves the wrong handler in production.
   */
  private def validate(endpoints: Vector[HttpEndpoint]): Unit =
    val problems = Vector.newBuilder[String]

    endpoints.groupBy(_.prefix).foreach { (prefix, sharing) =>
      if sharing.sizeIs > 1 then problems += s"${sharing.size} endpoints share the prefix '$prefix'"
    }

    endpoints.foreach { endpoint =>
      val declared =
        endpoint.routes.map(r => (r.method, r.template.render)) ++
          endpoint.streamRoutes.map(r => (r.method, r.template.render))
      declared.groupBy(identity).foreach { (key, duplicated) =>
        if duplicated.sizeIs > 1 then
          problems += s"'${endpoint.prefix}' declares ${key._1} ${key._2} ${duplicated.size} times"
      }
    }

    val found = problems.result()
    if found.nonEmpty then
      throw IllegalArgumentException(
        found.mkString("invalid ankka http configuration:\n  - ", "\n  - ", "")
      )

object HttpServer:

  /** Serves `factories` on the configured interface and port. */
  def of(factories: (EndpointClients => HttpEndpoint)*): HttpServer =
    new HttpServer(factories, None, None)

  /** Serves on an explicit interface and port; port 0 picks a free one. */
  def at(interface: String, port: Int)(
      factories: (EndpointClients => HttpEndpoint)*
  ): HttpServer =
    new HttpServer(factories, Some(interface), Some(port))

/**
 * The route a request selected, with the path arguments it matched.
 *
 * Carried from matching to admission to dispatch so the route is found once. It has to be found
 * before the ACL is applied, because a route may state an ACL of its own.
 */
private enum Matched:
  case Plain(route: Route, args: Vector[String])
  case Streaming(route: StreamRoute, args: Vector[String])

  /** The route's own ACL, if it declared one; `None` defers to the endpoint's. */
  def acl: Option[Acl] = this match
    case Plain(route, _)     => route.acl
    case Streaming(route, _) => route.acl

/** Matches requests to routes and turns handler outcomes into responses. */
private final class Router(
    endpoints: Vector[HttpEndpoint],
    bodyTimeout: FiniteDuration,
    callers: CallerSource = CallerSource.local
):

  // Sorted once at startup: most specific template first, so a literal segment is never
  // shadowed by a parameter that happened to be declared earlier.
  private val routesByEndpoint: Map[String, Vector[Route]] =
    endpoints.map(e => e.prefix -> e.routes.sortBy(_.template.specificity)).toMap

  private val streamRoutesByEndpoint: Map[String, Vector[StreamRoute]] =
    endpoints.map(e => e.prefix -> e.streamRoutes.sortBy(_.template.specificity)).toMap

  private val HealthPath = Vector("_ankka", "health")

  def handle(request: HttpRequest)(using
      system: ActorSystem[?],
      ec: ExecutionContext
  ): Future[HttpResponse] =
    val path = request.uri.path.toString.split('/').iterator.filter(_.nonEmpty).toVector

    // Exempt from ACLs because it reveals nothing: liveness probes need to work before
    // any auth story exists.
    if path == HealthPath then Future.successful(text(200, "ok"))
    else
      // The longest matching prefix, not the first declared: `/auth/whoami` and `/auth` can then
      // be two endpoints with two ACLs (one open discovery route beside an authenticated one)
      // without the answer depending on registration order.
      endpoints.filter(e => path.startsWith(e.prefixPath)).maxByOption(_.prefixPath.size) match
        case None =>
          Future.successful(
            problem(HttpProblem.notFound(s"no endpoint for /${path.mkString("/")}"))
          )
        case Some(endpoint) =>
          val remaining = path.drop(endpoint.prefixPath.size)
          val found     = matched(endpoint, request.method.value, remaining)

          // The route is matched before admission because a route's ACL replaces the endpoint's.
          // Where nothing matched there is no route to ask, so the endpoint's ACL decides — which
          // is also what keeps a closed endpoint from disclosing which of its paths exist by
          // answering 404 for some and 403 for the rest.
          val effective = found.flatMap(_.acl).getOrElse(endpoint.acl)

          callers.callerOf(request) match
            case Left(reason) => Future.successful(problem(HttpProblem.forbidden(reason)))
            case Right(caller) =>
              admitted(endpoint, request, remaining, found, effective, caller)

  private def admitted(
      endpoint: HttpEndpoint,
      request: HttpRequest,
      remaining: Vector[String],
      found: Option[Matched],
      effective: Acl,
      caller: Caller
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[HttpResponse] =
    admit(effective, contextFor(request, caller)) match
      case Left(refused) => Future.successful(refused)
      case Right(context) =>
        found match
          case Some(Matched.Plain(route, args)) => dispatch(route, request, context, args)
          case Some(Matched.Streaming(route, args)) =>
            dispatchStream(route, request, context, args)
          case None => unmatched(endpoint, request, remaining)

  /**
   * The route this request selects, if any.
   *
   * Streaming routes are consulted first, as they always have been, so a prefix serving both kinds
   * resolves the same way it did before route ACLs existed.
   */
  private def matched(
      endpoint: HttpEndpoint,
      method: String,
      remaining: Vector[String]
  ): Option[Matched] =
    val streaming = streamRoutesByEndpoint(endpoint.prefix).iterator
      .map(route => route -> route.template.matches(remaining))
      .collectFirst {
        case (route, Some(args)) if route.method == method => Matched.Streaming(route, args)
      }

    streaming.orElse(
      routesByEndpoint(endpoint.prefix).iterator
        .map(route => route -> route.template.matches(remaining))
        .collectFirst {
          case (route, Some(args)) if route.method == method => Matched.Plain(route, args)
        }
    )

  /** No route of this endpoint answers for the path, or none answers for the method. */
  private def unmatched(
      endpoint: HttpEndpoint,
      request: HttpRequest,
      remaining: Vector[String]
  )(using system: ActorSystem[?]): Future[HttpResponse] =
    // Distinguish "wrong verb" from "no such path" — a 404 for a POST to a GET-only
    // route sends the caller looking for a routing bug that is not there.
    val pathExists = endpoint.routes.exists(_.template.matches(remaining).isDefined) ||
      endpoint.streamRoutes.exists(_.template.matches(remaining).isDefined)
    val failure =
      if pathExists then HttpProblem(405, s"${request.method.value} not allowed on this path")
      else HttpProblem.notFound(s"no route for ${request.method.value} ${request.uri.path}")
    request.discardEntityBytes()
    Future.successful(problem(failure))

  /**
   * The request as a handler and an ACL both see it.
   *
   * Built once per request and shared: an ACL predicate that inspects a query parameter should be
   * looking at exactly what the handler will.
   */
  private def contextFor(request: HttpRequest, caller: Caller): SimpleRequestContext =
    SimpleRequestContext(
      method = request.method.value,
      path = request.uri.path.toString,
      query = QueryParams(request.uri.query().toVector),
      // Pekko models Content-Type on the entity, not among the headers; a handler asking
      // `request.header("Content-Type")` should still get an answer. The local caller header is
      // withheld, since its value is this process's secret, and so is the TLS session pekko-http
      // attaches as a synthetic header: the caller it names is `caller`.
      headers = request.headers
        .filterNot(h =>
          h.lowercaseName == LocalCallers.Header.toLowerCase || h
            .isInstanceOf[headers.`Tls-Session-Info`]
        )
        .map(header => header.name -> header.value)
        .toVector ++
        Option
          .when(!request.entity.isKnownEmpty)("Content-Type" -> request.entity.contentType.value)
          .toVector,
      remoteAddress = None,
      caller = caller
    )

  /**
   * Applies the effective ACL: the context to dispatch with (carrying the principal, if the ACL
   * established one), or the response that refuses the request.
   */
  private def admit(
      acl: Acl,
      context: SimpleRequestContext
  ): Either[HttpResponse, RequestContext] =
    def forbidden(reason: String) = Left(problem(HttpProblem.forbidden(reason)))
    acl match
      case Acl.DenyAll  => forbidden("not permitted by this endpoint's acl")
      case Acl.AllowAll => Right(context)
      case Acl.AllowIf(predicate) =>
        if predicate(context) then Right(context)
        else forbidden("not permitted by this endpoint's acl")
      case Acl.AllowCallers(matchers) =>
        // The same refusal text as every other ACL: naming the callers that would have been
        // admitted tells an unauthorised caller whose certificate to go looking for.
        if matchers.exists(_.admits(context.caller, callers.self)) then Right(context)
        else forbidden("not permitted by this endpoint's acl")
      case Acl.Authenticate(decide) =>
        decide(context) match
          case AuthDecision.Allow(principal) => Right(context.copy(principal = Some(principal)))
          case AuthDecision.Unauthenticated(challenge) =>
            Left(
              problem(HttpProblem.unauthorized("authentication required"))
                .addHeader(headers.RawHeader("WWW-Authenticate", s"Bearer $challenge"))
            )
          case AuthDecision.Forbidden(reason) => forbidden(reason)
          case AuthDecision.Unavailable(reason) =>
            Left(problem(HttpProblem(503, reason)).addHeader(headers.RawHeader("Retry-After", "5")))

  private def dispatch(
      route: Route,
      request: HttpRequest,
      context: RequestContext,
      args: Vector[String]
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[HttpResponse] =
    val bodyBytes =
      if route.needsBody then request.entity.toStrict(bodyTimeout).map(_.data.toArray)
      else
        request.discardEntityBytes()
        Future.successful(Array.emptyByteArray)

    bodyBytes
      .flatMap { bytes =>
        // Handlers run on a virtual thread, which is what makes the blocking
        // `ComponentClient.invoke` inside them free rather than a dispatcher hazard —
        // and what makes the request context safe to hold in a ThreadLocal.
        Future(
          RequestScope.withContext(context)(
            // Traced here, on the handler's own virtual thread, and not around this
            // Future's creation: a trace set on the caller's thread is invisible to
            // this one. It is the same reason RequestScope sets its context here. This
            // is what makes the entity's span a child of the request instead of a root
            // of its own — the difference between a trace and a list.
            Tracing.request(route.describe)(route.run(args, bytes))
          )
        )(using AnkkaExecutors.virtual)
      }
      .map { encoded =>
        HttpResponse(
          status = StatusCode.int2StatusCode(encoded.status),
          // What the handler asked for beside the body: a `Location`, a `Set-Cookie`. Raw
          // headers, so `Content-Type` and `Content-Length` — Pekko models those on the
          // entity — are not the handler's to set here.
          headers = encoded.headers.map((name, value) => headers.RawHeader(name, value)),
          entity =
            if encoded.body.isEmpty then HttpEntity.Empty
            else
              ContentType.parse(encoded.contentType) match
                case Right(contentType) => HttpEntity(contentType, encoded.body)
                case Left(_) =>
                  HttpEntity(ContentTypes.`application/octet-stream`, encoded.body)
        )
      }
      .recover {
        case failure: HttpProblem => problem(failure)
        case Rejection(error)     => problem(HttpProblem.from(error))
        case failure: IllegalArgumentException =>
          problem(HttpProblem.badRequest(Option(failure.getMessage).getOrElse("bad request")))
        case NonFatal(failure) =>
          system.log.error(s"unhandled failure in ${route.describe}", failure)
          problem(HttpProblem(500, "internal error"))
      }

  /**
   * Serves a route's `Source` as server-sent events.
   *
   * The handler is invoked on a virtual thread like any other, but only to *build* the stream; the
   * elements themselves are pulled by pekko-http as the client reads, so nothing buffers the whole
   * reply.
   */
  private def dispatchStream(
      route: StreamRoute,
      request: HttpRequest,
      context: RequestContext,
      args: Vector[String]
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[HttpResponse] =
    val bodyBytes =
      if route.needsBody then request.entity.toStrict(bodyTimeout).map(_.data.toArray)
      else
        request.discardEntityBytes()
        Future.successful(Array.emptyByteArray)

    bodyBytes
      .flatMap { bytes =>
        // The context is scoped around *building* the source, not around draining it:
        // elements are pulled later, by pekko-http, on another thread entirely.
        Future(
          RequestScope.withContext(context)(
            // Traced here, on the handler's own virtual thread, and not around this
            // Future's creation: a trace set on the caller's thread is invisible to
            // this one. It is the same reason RequestScope sets its context here. This
            // is what makes the entity's span a child of the request instead of a root
            // of its own — the difference between a trace and a list.
            Tracing.request(route.describe)(route.run(args, bytes))
          )
        )(using AnkkaExecutors.virtual)
      }
      .flatMap { source =>
        // JSON-encoded per event: see JsonText for why raw text is not safe here.
        Marshal(source.map(text => ServerSentEvent(JsonText.encode(text)))).to[HttpResponse]
      }
      .recover {
        case failure: HttpProblem => problem(failure)
        case Rejection(error)     => problem(HttpProblem.from(error))
        case NonFatal(failure) =>
          system.log.error(s"unhandled failure building ${route.describe}", failure)
          problem(HttpProblem(500, "internal error"))
      }

  private def text(status: Int, body: String): HttpResponse =
    HttpResponse(StatusCode.int2StatusCode(status), entity = HttpEntity(body))

  /**
   * Errors come back as JSON so a client can act on them without scraping prose.
   *
   * The message is encoded by `JsonText`, never escaped by hand: an error's text is often someone
   * else's (a gRPC status, an exception), and a tab or other control character left raw makes the
   * whole body unparseable — so the client loses the error it was being told about.
   */
  private def problem(failure: HttpProblem): HttpResponse =
    HttpResponse(
      StatusCode.int2StatusCode(failure.status),
      entity = HttpEntity(
        ContentTypes.`application/json`,
        s"""{"status":${failure.status},"error":${JsonText.encode(failure.message)}}"""
      )
    )

/**
 * A modelled rejection, thrown as itself or carried as the cause of a transport's exception — a
 * call to another service that was refused arrives that way, and a handler that lets it pass should
 * answer its own caller with the same refusal rather than a 500.
 */
private object Rejection:
  def unapply(failure: Throwable): Option[CommandError] = CommandError.from(failure)

/**
 * Where a request's caller comes from: the client certificate under mutual TLS, the local
 * impersonation header otherwise.
 *
 * Under TLS a connection without a client certificate never reaches here — the handshake requires
 * one — so `Left` is a certificate the authority issued that names no caller the platform knows.
 */
private[http] final class CallerSource(val self: RotatingTls.Identity, tls: Boolean):
  def callerOf(request: HttpRequest): Either[String, Caller] =
    if tls then
      request.header[headers.`Tls-Session-Info`] match
        case Some(info) =>
          info.peerCertificates.headOption match
            case Some(certificate: java.security.cert.X509Certificate) =>
              Caller.fromCertificate(certificate)
            case _ => Left("no client certificate")
        case None => Left("no client certificate")
    else
      Right(
        request.headers
          .find(_.lowercaseName == LocalCallers.Header.toLowerCase)
          .map(h => LocalCallers.callerFrom(h.value))
          .getOrElse(Caller.Local)
      )

private[http] object CallerSource:
  /**
   * Outside a cluster this service has no certificate and so no identity of its own; `local/local`
   * is what `Callers.self` and `Callers.anyInProject` compare against, and only a test naming a
   * caller through the local header can ever present it.
   */
  val LocalIdentity: RotatingTls.Identity = RotatingTls.Identity("local", "local")

  val local: CallerSource = new CallerSource(LocalIdentity, tls = false)

  def apply(tls: Option[RotatingTls]): CallerSource = tls match
    case None => local
    case Some(identity) =>
      val self = identity.identity.getOrElse(
        throw IllegalStateException(
          s"the service certificate in ${identity.directory} names no ankka:// identity"
        )
      )
      new CallerSource(self, tls = true)

/**
 * The request's own span: the root every component invocation it causes hangs from.
 *
 * Lives in `http` rather than `runtime` because only this module knows what a request is, and
 * reaches the recorder through the extension `runtime` publishes — the same direction every other
 * part of the seam runs in.
 */
private[ankka] object Tracing:

  def request[A](describe: String)(body: => A)(using system: ActorSystem[?]): A =
    val observability = Observability(system)
    val span = observability.recorder.begin(
      traceId = Trace.mint(),
      parentSpanId = 0L,
      componentRef = observability.names.intern("http"),
      handlerRef = observability.names.intern(describe)
    )
    var outcome = SpanOutcome.Failed
    try
      val result = Trace.within(span.traceId, span.id)(body)
      outcome = SpanOutcome.Ok
      result
    finally observability.recorder.complete(span, outcome)
