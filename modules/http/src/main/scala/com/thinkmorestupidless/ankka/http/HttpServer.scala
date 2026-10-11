package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Observability,
  ServedRoute,
  SpanKind,
  SpanOutcome,
  Trace,
  Traceparent
}
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService, RuntimeExtension}
import org.apache.pekko.actor.typed.ActorSystem
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.marshalling.sse.EventStreamMarshalling.*
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.impl.engine.ws.AnkkaSocketUpgrade

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
    tlsDirectory: Option[java.nio.file.Path] = None,
    configuredGrants: Option[Grants] = None,
    configuredMachines: Option[MachineTokens] = None,
    clientCertificates: Boolean = true
) extends RuntimeExtension:

  /**
   * This server alone serves mutual TLS from `directory`, whatever the configuration says — for a
   * suite that measures one server with TLS beside one without, in one service.
   */
  private[ankka] def withTls(directory: java.nio.file.Path): HttpServer =
    new HttpServer(
      factories,
      interface,
      port,
      Some(directory),
      configuredGrants,
      configuredMachines,
      clientCertificates
    )

  /**
   * The grants `Callers.granted` reads, in place of the file a deployed service is given (feature
   * 040): how a test presents a caller holding a grant, or one that has just lost it.
   */
  def withGrants(grants: Grants): HttpServer =
    new HttpServer(
      factories,
      interface,
      port,
      tlsDirectory,
      Some(grants),
      configuredMachines,
      clientCertificates
    )

  /**
   * The verifier of machines' tokens, in place of the one the platform's settings name (feature
   * 040): how a test presents a machine through the gateway with keys of its own.
   */
  private[ankka] def withMachines(machines: MachineTokens): HttpServer =
    new HttpServer(
      factories,
      interface,
      port,
      tlsDirectory,
      configuredGrants,
      Some(machines),
      clientCertificates
    )

  /**
   * Under TLS, serves without asking the client for a certificate (feature 040): for a port whose
   * content is public, read by a client that holds none. Every request on it is the internet's.
   * Used by the control plane's `keys` port and nothing else.
   */
  private[ankka] def withoutClientCertificates: HttpServer =
    new HttpServer(
      factories,
      interface,
      port,
      tlsDirectory,
      configuredGrants,
      configuredMachines,
      clientCertificates = false
    )

  @volatile private var binding: Option[Http.ServerBinding]                     = None
  @volatile private var served: Vector[ServedRoute]                             = Vector.empty
  @volatile private var scheme: String                                          = "http"
  private val sockets                                                           = OpenSockets()
  @volatile private var grantsCheck: Option[org.apache.pekko.actor.Cancellable] = None

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

    val clients =
      EndpointClients(
        service.componentClient,
        service.viewClient,
        service.services,
        service.secrets
      )
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
      val id                          = ServedRoute.endpointId(endpoint.prefix)
      def grantable(acl: Option[Acl]) = Acl.namesGranted(acl.getOrElse(endpoint.acl))
      endpoint.routes.map(r =>
        ServedRoute(
          r.method,
          s"${endpoint.prefix}${r.template.render}",
          streaming = false,
          id,
          grantable(r.acl)
        )
      ) ++ endpoint.streamRoutes.map(r =>
        ServedRoute(
          r.method,
          s"${endpoint.prefix}${r.template.render}",
          streaming = true,
          id,
          grantable(r.acl)
        )
      ) ++ endpoint.socketRoutes.map(r =>
        ServedRoute(
          "SOCKET",
          s"${endpoint.prefix}${r.template.render}",
          streaming = true,
          id,
          grantable(r.acl)
        )
      )
    }

    endpoints.foreach { endpoint =>
      endpoint.routes.foreach { route =>
        system.log.info("route {} {}{}", route.method, endpoint.prefix, route.template.render)
      }
      endpoint.streamRoutes.foreach { route =>
        system.log.info("route {} {}{} (SSE)", route.method, endpoint.prefix, route.template.render)
      }
      endpoint.socketRoutes.foreach { route =>
        system.log.info("route SOCKET {}{}", endpoint.prefix, route.template.render)
      }
      // Judged per route, not on the endpoint's own acl: a DenyAll endpoint that opens one route
      // with `withAcl` is a deliberate shape, and warning about it would teach the reader to
      // ignore the warning that matters — an endpoint nothing can reach.
      val effective = endpoint.routes.map(_.acl.getOrElse(endpoint.acl)) ++
        endpoint.streamRoutes.map(_.acl.getOrElse(endpoint.acl)) ++
        endpoint.socketRoutes.map(_.acl.getOrElse(endpoint.acl))
      if effective.forall(_ == Acl.DenyAll) && (effective.nonEmpty || endpoint.acl == Acl.DenyAll)
      then
        system.log.warn(
          "nothing on endpoint '{}' is reachable; every route is denied by its acl",
          endpoint.prefix
        )
    }

    val upgrades = SocketUpgrades.from(system, sockets)

    val tls = serviceTls(config)
    // A machine's token is read only under TLS, where the gateway's certificate says the request
    // came from outside: the keys are fetched now, so the first token finds them held.
    val machines = configuredMachines.orElse(
      tls.flatMap(t => MachineTokens.fromConfig(config, Some(t.directory)))
    )
    machines.foreach(_.start())
    val callers = CallerSource(tls, machines, clientCertificates)
    val grants = configuredGrants
      .orElse(GrantsFile.fromConfig(config, callers.self.service))
      .getOrElse(Grants.none)
    // A file is otherwise looked at only when a request asks: checked on a timer too, so a
    // revocation ends a socket or a stream that nothing else is asking about.
    grants match
      case file: GrantsFile =>
        val every = FiniteDuration(
          config.getDuration("ankka.grants.reload-interval").toMillis,
          java.util.concurrent.TimeUnit.MILLISECONDS
        )
        grantsCheck = Some(system.scheduler.scheduleAtFixedRate(every, every)(() => file.check()))
      case _ => ()
    val handler = Router(endpoints, bodyTimeout, callers, Some(upgrades), grants).handle

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
            .enableHttps(
              ConnectionContext.httpsServer(() => identity.serverEngine(clientCertificates))
            )
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
      (e.acl +: (e.routes.flatMap(_.acl) ++ e.streamRoutes.flatMap(_.acl) ++
        e.socketRoutes.flatMap(_.acl))).exists {
        case Acl.AllowCallers(_) => true
        case _                   => false
      }
    )
    if tls.isEmpty && namesCallers then
      system.log.info(
        "caller identity is not enforced outside a cluster: every request is Caller.Local"
      )

  /**
   * Stops accepting, closes every open socket "going away" so its client knows to open another,
   * gives the close handshakes a moment, then ends what is left as before. Without the middle step
   * `terminate`'s deadline would cut every socket off.
   */
  override def stop(): Unit =
    grantsCheck.foreach(_.cancel(): Unit)
    grantsCheck = None
    binding.foreach { b =>
      Await.ready(b.unbind(), 10.seconds)
      sockets.closeAll(CloseReason.GoingAway, 2.seconds)
      Await.ready(b.terminate(5.seconds), 10.seconds)
    }
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
      // A socket route is a GET: one on the template of a GET or an SSE route would make which
      // one answers depend on whether the request asked to upgrade.
      val declared =
        endpoint.routes.map(r => (r.method, r.template.render)) ++
          endpoint.streamRoutes.map(r => (r.method, r.template.render)) ++
          endpoint.socketRoutes.map(r => (r.method, r.template.render))
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
  case Socket(route: SocketRoute, args: Vector[String])

  /** The route's own ACL, if it declared one; `None` defers to the endpoint's. */
  def acl: Option[Acl] = this match
    case Plain(route, _)     => route.acl
    case Streaming(route, _) => route.acl
    case Socket(route, _)    => route.acl

  /** The route as its span names it. */
  def describe: String = this match
    case Plain(route, _)     => route.describe
    case Streaming(route, _) => route.describe
    case Socket(route, _)    => route.describe

  /**
   * What a grant on this route opens, under `prefix`: its method and its whole path as a template.
   * A socket is opened by a `GET`.
   */
  def grantTarget(prefix: String): GrantTarget = this match
    case Plain(route, _)     => GrantTarget.Route(route.method, s"$prefix${route.template.render}")
    case Streaming(route, _) => GrantTarget.Route(route.method, s"$prefix${route.template.render}")
    case Socket(route, _)    => GrantTarget.Route("GET", s"$prefix${route.template.render}")

/**
 * What a grant admitted and is still open: a socket or a server-sent event stream (feature 040). A
 * change to the grants asks each whether it is still admitted, and closes the ones that are not.
 */
private[http] final class Revocable(val stillAdmitted: () => Boolean, val close: () => Unit)

/** Matches requests to routes and turns handler outcomes into responses. */
private final class Router(
    endpoints: Vector[HttpEndpoint],
    bodyTimeout: FiniteDuration,
    callers: CallerSource = CallerSource.local,
    configuredUpgrades: Option[SocketUpgrades] = None,
    grants: Grants = Grants.none
):

  private val revocable = java.util.concurrent.ConcurrentHashMap.newKeySet[Revocable]()

  grants.onChange { () =>
    revocable.forEach { open =>
      if !open.stillAdmitted() then
        revocable.remove(open)
        open.close()
    }
  }

  /**
   * Whether `acl` might admit `context` by grant alone, so that a revocation must be able to end
   * what it admitted. A local caller is admitted by every matcher, grant or none.
   */
  private def admittedByGrant(acl: Acl, context: RequestContext): Boolean = acl match
    case Acl.AllowCallers(matchers) =>
      matchers.contains(CallerMatcher.Granted) && context.caller != Caller.Local
    case _ => false

  private def stillAdmits(acl: Acl, context: RequestContext, target: GrantTarget): () => Boolean =
    () =>
      acl match
        case Acl.AllowCallers(matchers) =>
          matchers.exists(_.admits(context.caller, callers.self, Some(target), grants))
        case _ => true

  // Sorted once at startup: most specific template first, so a literal segment is never
  // shadowed by a parameter that happened to be declared earlier.
  private val routesByEndpoint: Map[String, Vector[Route]] =
    endpoints.map(e => e.prefix -> e.routes.sortBy(_.template.specificity)).toMap

  private val streamRoutesByEndpoint: Map[String, Vector[StreamRoute]] =
    endpoints.map(e => e.prefix -> e.streamRoutes.sortBy(_.template.specificity)).toMap

  private val socketRoutesByEndpoint: Map[String, Vector[SocketRoute]] =
    endpoints.map(e => e.prefix -> e.socketRoutes.sortBy(_.template.specificity)).toMap

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

  /** The subprotocol a browser's token arrives in, since a browser cannot set a header. */
  private val BearerProtocol = "ankka.bearer."

  /** The subprotocol a socket's 101 selects when offered; never the bearer one. */
  private val SocketProtocol = "ankka.socket"

  private def offeredProtocols(request: HttpRequest): Vector[String] =
    request.headers
      .collect { case h if h.lowercaseName == "sec-websocket-protocol" => h.value }
      .flatMap(_.split(',').iterator.map(_.trim).filter(_.nonEmpty))
      .toVector

  private def admitted(
      endpoint: HttpEndpoint,
      request: HttpRequest,
      remaining: Vector[String],
      found: Option[Matched],
      effective: Acl,
      caller: Caller
  )(using system: ActorSystem[?], ec: ExecutionContext): Future[HttpResponse] =
    val context = found match
      case Some(Matched.Socket(_, _)) => forSocket(request, contextFor(request, caller))
      case _                          => contextFor(request, caller)
    val target = found.map(_.grantTarget(endpoint.prefix))
    admit(effective, context, target) match
      case Left(refused) =>
        // A refusal is the callee's answer, not its fault: recorded as one (feature 040), so the
        // topology counts it apart from a failure. Nothing matched has no route to name.
        found.foreach { matched =>
          val origin = matched match
            case Matched.Socket(route, _) => originOf(endpoint, "SOCKET", route.template)
            case Matched.Plain(route, _)  => originOf(endpoint, route.method, route.template)
            case Matched.Streaming(route, _) =>
              originOf(endpoint, route.method, route.template)
          RequestScope.withContext(context)(Tracing.refused(matched.describe, origin))
        }
        request.discardEntityBytes()
        Future.successful(refused)
      case Right(context) =>
        val revoke = target.filter(_ => admittedByGrant(effective, context)).map { t =>
          stillAdmits(effective, context, t)
        }
        found match
          case Some(Matched.Plain(route, args)) =>
            val origin = originOf(endpoint, route.method, route.template)
            dispatch(route, request, context, args, origin)
          case Some(Matched.Streaming(route, args)) =>
            val origin = originOf(endpoint, route.method, route.template)
            dispatchStream(route, request, context, args, origin, revoke)
          case Some(Matched.Socket(route, args)) =>
            val origin = originOf(endpoint, "SOCKET", route.template)
            dispatchSocket(route, request, context, args, origin, revoke)
          case None => unmatched(endpoint, request, remaining)

  /**
   * Who a call made while serving this route is from: the endpoint, as a topology names it, and the
   * route by its method and its whole path as a template. The request's span keeps the name it has
   * always had, the route within its endpoint; this is the name a reader of the topology sees,
   * where two endpoints may each have a `GET /{id}`.
   */
  private def originOf(endpoint: HttpEndpoint, method: String, template: PathTemplate): CallOrigin =
    CallOrigin(
      ServedRoute.endpointId(endpoint.prefix),
      s"$method ${endpoint.prefix}${template.render}"
    )

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
    // Validation keeps a socket route off the template of any GET, so consulting it first changes
    // nothing for the routes that were there before it.
    val socket =
      if method != "GET" then None
      else
        socketRoutesByEndpoint(endpoint.prefix).iterator
          .map(route => route -> route.template.matches(remaining))
          .collectFirst { case (route, Some(args)) => Matched.Socket(route, args) }
    lazy val streaming = streamRoutesByEndpoint(endpoint.prefix).iterator
      .map(route => route -> route.template.matches(remaining))
      .collectFirst {
        case (route, Some(args)) if route.method == method => Matched.Streaming(route, args)
      }

    socket
      .orElse(streaming)
      .orElse(
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
      endpoint.streamRoutes.exists(_.template.matches(remaining).isDefined) ||
      endpoint.socketRoutes.exists(_.template.matches(remaining).isDefined)
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
      context: SimpleRequestContext,
      target: Option[GrantTarget]
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
        // admitted tells an unauthorised caller whose certificate to go looking for — and says
        // nothing of which grants exist.
        if matchers.exists(_.admits(context.caller, callers.self, target, grants)) then
          Right(context)
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
      args: Vector[String],
      origin: CallOrigin
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
            Tracing.request(route.describe, origin)(route.run(args, bytes))
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
      args: Vector[String],
      origin: CallOrigin,
      revoke: Option[() => Boolean]
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
            Tracing.request(route.describe, origin)(route.run(args, bytes))
          )
        )(using AnkkaExecutors.virtual)
      }
      .flatMap { source =>
        val events = source.map(event => ServerSentEvent(event.data, event.name))
        // A stream a grant admitted ends when the grant does (feature 040): a kill switch the
        // router holds while the stream is open, completing it as if it had ended on its own.
        val served = revoke match
          case None => events
          case Some(stillAdmitted) =>
            events
              .viaMat(org.apache.pekko.stream.KillSwitches.single)(
                org.apache.pekko.stream.scaladsl.Keep.right
              )
              .mapMaterializedValue { switch =>
                val open = Revocable(stillAdmitted, () => switch.shutdown())
                revocable.add(open)
                open
              }
              .watchTermination() { (open, done) =>
                done.onComplete(_ => revocable.remove(open))
                open
              }
        // JSON per event, named or not: see SseEvent for why raw text is not safe here.
        Marshal(served).to[HttpResponse]
      }
      .recover {
        case failure: HttpProblem => problem(failure)
        case Rejection(error)     => problem(HttpProblem.from(error))
        case NonFatal(failure) =>
          system.log.error(s"unhandled failure building ${route.describe}", failure)
          problem(HttpProblem(500, "internal error"))
      }

  /**
   * The opening request of a socket as its ACL and its handler see it. A browser cannot set
   * `Authorization` on a socket, so a token it offers as the subprotocol `ankka.bearer.<token>` is
   * presented as that header when there is none — exactly where every authenticator already looks.
   * A header the client did send wins. `Sec-WebSocket-Protocol` is withheld either way, so the
   * token reaches a handler, a log or a trace only as the header every route already treats with
   * care.
   */
  private def forSocket(request: HttpRequest, context: SimpleRequestContext): SimpleRequestContext =
    val withoutProtocols =
      context.headers.filterNot((name, _) => name.equalsIgnoreCase("sec-websocket-protocol"))
    val offered = offeredProtocols(request).collectFirst {
      case p if p.startsWith(BearerProtocol) && p.length > BearerProtocol.length =>
        p.drop(BearerProtocol.length)
    }
    val hasHeader = withoutProtocols.exists((name, _) => name.equalsIgnoreCase("authorization"))
    context.copy(headers = offered match
      case Some(token) if !hasHeader => withoutProtocols :+ ("Authorization" -> s"Bearer $token")
      case _                         => withoutProtocols)

  /**
   * Opens a socket for an admitted request: 426 when it did not ask to upgrade, a 400 when its path
   * does not parse, and otherwise the 101 — after which the handler runs on a virtual thread until
   * it returns, and the socket is closed with what ended it.
   */
  private def dispatchSocket(
      route: SocketRoute,
      request: HttpRequest,
      context: RequestContext,
      args: Vector[String],
      origin: CallOrigin,
      revoke: Option[() => Boolean]
  )(using system: ActorSystem[?]): Future[HttpResponse] =
    AnkkaSocketUpgrade.of(request) match
      case None =>
        request.discardEntityBytes()
        Future.successful(
          problem(HttpProblem(426, s"${route.describe} is opened as a socket"))
            .addHeader(headers.RawHeader("Upgrade", "websocket"))
        )
      case Some(upgrade) =>
        val handler =
          try Right(route.run(args))
          catch case failure: HttpProblem => Left(problem(failure))
        handler match
          case Left(refused) => Future.successful(refused)
          case Right(run) =>
            val upgrades = configuredUpgrades.getOrElse(SocketUpgrades.from(system, OpenSockets()))
            val socket   = upgrades.open()
            // A socket a grant admitted is closed when the grant ends (feature 040).
            val open =
              revoke.map(still => Revocable(still, () => socket.close(CloseReason.Revoked)))
            open.foreach(revocable.add(_): Unit)
            val flow = socket.flow { opened =>
              Future(
                RequestScope.withContext(context)(
                  Tracing.socket(route.describe, origin)(run(opened))
                )
              )(using AnkkaExecutors.virtual).onComplete { outcome =>
                open.foreach(revocable.remove(_): Unit)
                outcome match
                  case scala.util.Success(_) => opened.close(CloseReason.Finished)
                  case scala.util.Failure(failure) =>
                    system.log.error(s"unhandled failure in ${route.describe}", failure)
                    opened.close(CloseReason.Failed)
              }(using AnkkaExecutors.virtual)
            }
            val protocol =
              Option.when(offeredProtocols(request).contains(SocketProtocol))(SocketProtocol)
            Future.successful(upgrades.respond(upgrade, flow, protocol, socket))

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
private[http] final class CallerSource(
    val self: RotatingTls.Identity,
    tls: Boolean,
    machines: Option[MachineTokens] = None,
    clientCertificates: Boolean = true
):
  def callerOf(request: HttpRequest): Either[String, Caller] =
    if tls then
      request.header[headers.`Tls-Session-Info`] match
        case Some(info) =>
          info.peerCertificates.headOption match
            case Some(certificate: java.security.cert.X509Certificate) =>
              Caller.fromCertificate(certificate, Some(self)).map {
                // Only a request from outside, through the gateway, may say which machine it is:
                // over a connection between services the certificate is the caller (feature 040).
                case Caller.Gateway => machineOf(request).getOrElse(Caller.Gateway)
                case other          => other
              }
            case _ if !clientCertificates => Right(Caller.Gateway)
            case _                        => Left("no client certificate")
        case None if !clientCertificates => Right(Caller.Gateway)
        case None                        => Left("no client certificate")
    else
      Right(
        request.headers
          .find(_.lowercaseName == LocalCallers.Header.toLowerCase)
          .map(h => LocalCallers.callerFrom(h.value))
          .getOrElse(Caller.Local)
      )

  /** The machine a bearer token from this installation's issuer proves, if it proves one. */
  private def machineOf(request: HttpRequest): Option[Caller] =
    for
      tokens <- machines
      bearer <- CallerSource.bearer(request.headers.find(_.is("authorization")).map(_.value))
      if tokens.issuerOf(bearer).contains(tokens.issuer)
      machine <- tokens.verify(bearer).toOption
    yield machine

private[http] object CallerSource:

  /** The token of an `Authorization: Bearer <token>` header. */
  def bearer(authorization: Option[String]): Option[String] =
    authorization
      .map(_.trim)
      .filter(_.regionMatches(true, 0, "Bearer ", 0, 7))
      .map(_.drop(7).trim)
      .filter(_.nonEmpty)

  /**
   * Outside a cluster this service has no certificate and so no identity of its own; `local/local`
   * is what `Callers.self` and `Callers.anyInProject` compare against, and only a test naming a
   * caller through the local header can ever present it.
   */
  val LocalIdentity: RotatingTls.Identity = RotatingTls.Identity("local", "local")

  val local: CallerSource = new CallerSource(LocalIdentity, tls = false)

  def apply(
      tls: Option[RotatingTls],
      machines: Option[MachineTokens] = None,
      clientCertificates: Boolean = true
  ): CallerSource =
    tls match
      case None => local
      case Some(identity) =>
        val self = identity.identity.getOrElse(
          throw IllegalStateException(
            s"the service certificate in ${identity.directory} names no ankka:// identity"
          )
        )
        new CallerSource(self, tls = true, machines, clientCertificates)

/**
 * The request's own span: what every component invocation it causes hangs from.
 *
 * Lives in `http` rather than `runtime` because only this module knows what a request is, and
 * reaches the recorder through the extension `runtime` publishes — the same direction every other
 * part of the seam runs in.
 *
 * A request that carries a `traceparent` — another service's call, or an edge proxy's request from
 * outside the cluster — is continued: the span joins that trace under that span. One that carries
 * none, or one that cannot be read, starts a trace of its own. The header is read from the request
 * context already on this thread, and nothing is echoed.
 */
private[ankka] object Tracing:

  /**
   * A socket's span: from its opening to its close, recorded once it has closed. A span begun at
   * the open would hold a slot in the ring for as long as the socket lasted, and the ring reuses a
   * slot once enough newer spans exist — so the span of exactly the long sockets worth looking at
   * would never be recorded. The calls the handler makes meanwhile name this span as their parent.
   *
   * A handler ended by `SocketClosed` ended as a handler ends when its client goes, so it is `Ok`.
   */
  def socket(describe: String, origin: CallOrigin)(body: => Unit)(using
      system: ActorSystem[?]
  ): Unit =
    val observability = Observability(system)
    val recorder      = observability.recorder
    // A socket opened by a caller that sent its trace continues that trace, as a request does.
    val continued =
      RequestScope.currentContext.flatMap(_.header(Traceparent.Name)).flatMap(Traceparent.parse)
    val span = recorder.reserve(
      continued.fold(Trace.mintHigh())(_.traceIdHigh),
      continued.fold(Trace.mint())(_.traceId),
      observability.names.intern("http"),
      observability.names.intern(describe)
    )
    var outcome = SpanOutcome.Failed
    try
      try Trace.within(span, origin)(body)
      catch case _: SocketClosed => ()
      outcome = SpanOutcome.Ok
    finally recorder.record(span, continued.fold(0L)(_.spanId), SpanKind.Server, outcome)

  /**
   * A request its ACL refused: a span of its own, recorded `Refused` at once, so a refusal is
   * counted as the callee's answer and never as its failure. Continues the caller's trace when the
   * request carried one, as a served request does.
   */
  def refused(describe: String, origin: CallOrigin)(using system: ActorSystem[?]): Unit =
    val observability = Observability(system)
    val componentRef  = observability.names.intern("http")
    val handlerRef    = observability.names.intern(describe)
    val continued =
      RequestScope.currentContext.flatMap(_.header(Traceparent.Name)).flatMap(Traceparent.parse)
    val span = continued match
      case Some(parent) =>
        observability.recorder.begin(
          parent.traceIdHigh,
          parent.traceId,
          parent.spanId,
          componentRef,
          handlerRef,
          SpanKind.Server
        )
      case None => observability.recorder.beginRoot(componentRef, handlerRef, SpanKind.Server)
    Trace.within(span, origin)(())
    observability.recorder.complete(span, SpanOutcome.Refused)

  def request[A](describe: String, origin: CallOrigin)(body: => A)(using
      system: ActorSystem[?]
  ): A =
    val observability = Observability(system)
    val componentRef  = observability.names.intern("http")
    val handlerRef    = observability.names.intern(describe)
    val continued =
      RequestScope.currentContext.flatMap(_.header(Traceparent.Name)).flatMap(Traceparent.parse)
    val span = continued match
      case Some(parent) =>
        observability.recorder.begin(
          parent.traceIdHigh,
          parent.traceId,
          parent.spanId,
          componentRef,
          handlerRef,
          SpanKind.Server
        )
      case None => observability.recorder.beginRoot(componentRef, handlerRef, SpanKind.Server)
    var outcome = SpanOutcome.Failed
    try
      val result = Trace.within(span, origin)(body)
      outcome = SpanOutcome.Ok
      result
    finally observability.recorder.complete(span, outcome)

/**
 * What a server needs to open a socket: the limits, pekko-http's settings for the upgrade with the
 * keep-alive set, and where the open sockets are kept so the server can close them when it stops.
 */
private[http] final class SocketUpgrades(
    settings: SocketSettings,
    upgradeSettings: org.apache.pekko.http.scaladsl.settings.WebSocketSettings,
    log: org.apache.pekko.event.LoggingAdapter,
    registry: OpenSockets
):
  def open(): OpenSocket = OpenSocket(settings, registry)

  def respond(
      upgrade: org.apache.pekko.http.scaladsl.model.ws.WebSocketUpgrade,
      flow: org.apache.pekko.stream.scaladsl.Flow[
        org.apache.pekko.http.scaladsl.model.ws.Message,
        org.apache.pekko.http.scaladsl.model.ws.Message,
        ?
      ],
      subprotocol: Option[String],
      socket: OpenSocket
  ): HttpResponse =
    AnkkaSocketUpgrade.respond(
      upgrade,
      flow,
      subprotocol,
      () => socket.chosen(),
      upgradeSettings,
      log
    )

private[http] object SocketUpgrades:

  /**
   * From the system's configuration, refusing limits that cannot work: a keep-alive not shorter
   * than pekko-http's own idle timeout would leave every quiet socket to be cut off.
   */
  def from(system: ActorSystem[?], registry: OpenSockets): SocketUpgrades =
    val settings = SocketSettings.from(system.settings.config)
    val server   = org.apache.pekko.http.scaladsl.settings.ServerSettings(system)
    SocketSettings.problems(settings, server.timeouts.idleTimeout) match
      case Vector() => ()
      case found =>
        throw IllegalArgumentException(
          found.mkString("invalid ankka http configuration:\n  - ", "\n  - ", "")
        )
    SocketUpgrades(
      settings,
      server.websocketSettings.withPeriodicKeepAliveMaxIdle(settings.keepAlive),
      org.apache.pekko.event.Logging(system.classicSystem, classOf[HttpServer]),
      registry
    )
