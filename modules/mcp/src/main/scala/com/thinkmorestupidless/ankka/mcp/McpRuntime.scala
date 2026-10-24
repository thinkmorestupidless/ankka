package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentDescriptor, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  ClientKeyRequest,
  ClientKeySummary,
  ClientKeysProvider,
  IssuedClientKey,
  NeedsService,
  OpaqueCredentials,
  Principal,
  RequestContext
}
import com.thinkmorestupidless.ankka.http.ClientKeys as HttpClientKeys
import com.thinkmorestupidless.ankka.mcp.ClientKeyIndex.{Live, Lookup}
import com.thinkmorestupidless.ankka.mcp.signin.{
  BrokerKeys,
  BrokerTokens,
  SignInExpiry,
  SignInKeysEntity,
  SignInRecordEntity,
  SignInSettings
}
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, TimerRuntime}
import com.thinkmorestupidless.ankka.sdk.TimerScheduler

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * What a service's MCP endpoints need of the platform beyond serving (feature 054): its client keys
 * now, its sign-in broker next. Register it beside the server, with its components:
 *
 * {{{
 * Ankka.service("plumbers")
 *   .registerAll(McpRuntime.descriptors)
 *   .withExtension(HttpServer.of(clients => PlumbersMcp(clients)))
 *   .withExtension(McpRuntime())
 * }}}
 *
 * The order of the two extensions does not matter: an ACL that admits client keys is bound to this
 * runtime when the server starts, and reads its index on each request, so a key is refused
 * "unavailable" until the index has replayed, never wrongly. Readiness is the index's.
 */
final class McpRuntime private (clock: Clock, lastUseInterval: Option[FiniteDuration])
    extends ClientKeysProvider:

  val name: String = "mcp"

  private[mcp] val index = ClientKeyIndex(clock)

  @volatile private var service: Option[AnkkaService] = None

  /** The MCP endpoints the server mounted, by path: what a key is issued for. */
  private val mounted = new ConcurrentHashMap[String, McpEndpoint]()

  override def readiness: Option[() => Boolean] = Some(() => index.ready)

  def start(service: AnkkaService): Unit =
    attach(service)
    val registered = service.registry.components.map(_.componentId).toSet
    val missing    = McpRuntime.descriptors.map(_.componentId).filterNot(registered)
    if missing.nonEmpty then
      service.system.log.warn(
        "McpRuntime is registered but {} are not; use registerAll(McpRuntime.descriptors)",
        missing.mkString(", ")
      )
    val interval = lastUseInterval.getOrElse(
      FiniteDuration(
        service.system.settings.config
          .getDuration("ankka.mcp.client-keys.last-use-interval")
          .toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
    )
    index.start(service, interval)

    // The broker's keys are rotated hourly on every instance, as the control plane's machine keys
    // are; a service with no brokered endpoint never mints one.
    given scala.concurrent.ExecutionContext = service.system.executionContext
    rotation = Some(
      service.system.scheduler
        .scheduleWithFixedDelay(McpRuntime.RotationCheck, McpRuntime.RotationCheck) { () =>
          if endpoints.exists(_.brokered) then
            Thread.ofVirtual().name("sign-in-key-rotation").start { () =>
              try brokerKeys.rotate()
              catch
                case scala.util.control.NonFatal(e) =>
                  service.system.log
                    .warn("the sign-in broker's keys could not be rotated: {}", e.toString)
            }: Unit
        }
    )

  @volatile private var rotation: Option[org.apache.pekko.actor.Cancellable] = None

  override def stop(): Unit =
    rotation.foreach(_.cancel())
    rotation = None
    index.stop()
    mounted.clear()

  private[mcp] def attach(running: AnkkaService): Unit = service = Some(running)

  // ── the sign-in broker's ────────────────────────────────────────────────

  private def attached: AnkkaService =
    service.getOrElse(throw IllegalStateException("the MCP runtime is not attached to a service"))

  private[mcp] def clockOf: Clock = clock

  private[mcp] lazy val signInSettings: SignInSettings =
    SignInSettings.from(attached.system.settings.config)

  /** The broker's keys on this instance: one set for every brokered endpoint of the service. */
  private[mcp] lazy val brokerKeys: BrokerKeys =
    BrokerKeys(
      attached.componentClient,
      attached.secrets,
      clock,
      signInSettings.rotateEvery,
      signInSettings.keepFor
    )

  private[mcp] lazy val brokerTokens: BrokerTokens =
    BrokerTokens(
      () => brokerKeys.signing(),
      brokerKeys.publicKey,
      clock,
      signInSettings.tokenLifetime
    )

  /** The service's timers once they run, for deleting what the broker keeps at its expiry. */
  private[mcp] def timers: () => Option[TimerScheduler] = () =>
    service
      .flatMap(_.extension[TimerRuntime])
      .flatMap(t => scala.util.Try(t.timerScheduler).toOption)

  private[mcp] def register(endpoint: McpEndpoint): Unit =
    mounted.put(McpEndpoint.normalised(endpoint.path), endpoint): Unit

  private[mcp] def endpoints: Vector[McpEndpoint] =
    mounted.asScala.toVector.sortBy(_._1).map(_._2)

  // ── what an ACL asks ──────────────────────────────────────────────────────

  private[mcp] def authenticate(context: RequestContext): AuthDecision =
    import McpRuntime.*
    presented(context) match
      case None                                            => AuthDecision.Unauthenticated(Realm)
      case Some(token) if !Credentials.looksLikeOne(token) => AuthDecision.Unauthenticated(Realm)
      case Some(token)                                     =>
        Credentials.parse(token) match
          case None                    => invalid(NotRecognised)
          case Some(_) if !index.ready => AuthDecision.Unavailable("client keys are not loaded yet")
          case Some((id, secret))      =>
            index.lookup(id) match
              case Lookup.Missing      => invalid(NotRecognised)
              case Lookup.Expired(key) =>
                invalid(if Credentials.matches(key.digest, secret) then Expired else NotRecognised)
              case Lookup.Usable(key) =>
                if !Credentials.matches(key.digest, secret) then invalid(NotRecognised)
                else if McpEndpoint.normalised(context.path) != key.endpoint then
                  invalid(NotRecognised)
                else
                  index.touch(id)
                  AuthDecision.Allow(principal(id, key))

  /**
   * The tools a request may see and call by its key: every tool for a request no key admitted and
   * for a key scoped to none, and none for a key this instance no longer holds.
   */
  private[mcp] def scopeOf(context: RequestContext): Scope =
    context.principal
      .filter(_.issuer.contains(McpRuntime.Issuer))
      .flatMap(_.claims.get("client_key"))
      .fold(Scope.Every)(id =>
        index.lookup(id) match
          case Lookup.Usable(key) if key.scope.isEmpty => Scope.Every
          case Lookup.Usable(key)                      => Scope.Only(key.scope)
          case _                                       => Scope.Only(Set.empty)
      )

  // ── what a handler asks ───────────────────────────────────────────────────

  val clientKeys: HttpClientKeys = new HttpClientKeys:

    private def refuse(message: String, code: ErrorCode = ErrorCode.BadRequest) =
      throw CommandError(message, code)

    private def running: AnkkaService =
      service.getOrElse(refuse("the MCP runtime has not started", ErrorCode.Unavailable))

    private def caughtUp(): Unit =
      if !index.ready then
        refuse("client keys are not loaded yet on this instance", ErrorCode.Unavailable)

    private def endpointFor(named: Option[String]): McpEndpoint =
      val all = endpoints
      named.map(McpEndpoint.normalised) match
        case Some(path) =>
          all.find(e => McpEndpoint.normalised(e.path) == path).getOrElse {
            refuse(s"the service has no MCP endpoint at $path")
          }
        case None =>
          all match
            case Vector(only) => only
            case Vector()     => refuse("the service has no MCP endpoint", ErrorCode.Unavailable)
            case several      =>
              val paths = several.map(_.path)
              refuse(
                s"the service has MCP endpoints ${paths.init.mkString(", ")} and ${paths.last}; name one"
              )

    private[ankka] def issueRequest(request: ClientKeyRequest): IssuedClientKey =
      val now = clock.instant()
      ClientKeyRules
        .problem(request.subject, request.attributes, request.expiry, now)
        .foreach(refuse(_))
      val endpoint = endpointFor(request.endpoint)
      val path     = McpEndpoint.normalised(endpoint.path)
      val names    = endpoint.tools.map(_.name).toSet
      request.scope.toVector.sorted.find(!names.contains(_)).foreach { tool =>
        refuse(s"the tool '$tool' is not a tool of $path")
      }
      val client = running.componentClient
      val minted = McpRuntime.Credentials.mint()
      val scope  = request.scope.toVector.sorted
      client
        .forEventSourcedEntity(EntityId(minted.id))
        .call(ClientKeyEntity.issue)
        .invoke(
          IssueClientKey(
            minted.digest,
            request.subject,
            request.attributes,
            request.label,
            path,
            scope,
            now,
            request.expiry
          )
        ): Unit
      index.admit(
        minted.id,
        Live(
          minted.digest,
          request.subject,
          request.attributes,
          request.label,
          path,
          scope.toSet,
          now,
          request.expiry.flatMap(ClientKeyRules.expiresAt(_, now))
        )
      )
      IssuedClientKey(minted.id, minted.presented)

    def list(): Vector[ClientKeySummary] =
      caughtUp()
      index.summaries

    def revoke(id: String): Unit =
      caughtUp()
      val now = clock.instant()
      running.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(ClientKeyEntity.revoke)
        .invoke(RevokeClientKey(now)): Unit
      index.evict(id, now)

object McpRuntime:

  /** The runtime a service registers. */
  def apply(): McpRuntime = new McpRuntime(Clock.systemUTC(), None)

  /** For tests: a clock to move, and a sweep interval of the suite's choosing. */
  private[ankka] def apply(clock: Clock, lastUseInterval: FiniteDuration): McpRuntime =
    new McpRuntime(clock, Some(lastUseInterval))

  /** What a service registering `McpRuntime()` must register too. */
  def descriptors: Seq[ComponentDescriptor] = Seq(
    ClientKeyEntity.descriptor,
    SignInRecordEntity.descriptor,
    SignInKeysEntity.descriptor,
    SignInExpiry.platformDescriptor
  )

  /** How often each instance checks whether the broker's keys are due a rotation. */
  private val RotationCheck =
    scala.concurrent.duration.FiniteDuration(1, java.util.concurrent.TimeUnit.HOURS)

  /** The credential: `ankka_ck_`, 16 hex of id, 64 hex of secret. */
  private[mcp] val Credentials = OpaqueCredentials("ankka_ck_")

  /** What a key's principal names as its issuer. */
  val Issuer = "client-key"

  private val Realm         = """realm="ankka""""
  private val NotRecognised = "client key not recognised"
  private val Expired       = "client key expired"

  private def invalid(reason: String): AuthDecision =
    AuthDecision.Unauthenticated(s"""$Realm, error="invalid_token", error_description="$reason"""")

  private def presented(context: RequestContext): Option[String] =
    context
      .header("authorization")
      .map(_.trim)
      .collect {
        case value if value.regionMatches(true, 0, "Bearer ", 0, 7) => value.substring(7).trim
      }
      .filter(_.nonEmpty)

  private def principal(id: String, key: Live): Principal =
    Principal(
      subject = key.subject,
      name = key.label,
      claims = key.attributes + ("kind" -> "client-key") + ("client_key" -> id),
      issuer = Some(Issuer)
    )

/** Which of an endpoint's tools a request may see and call. */
private[mcp] enum Scope:
  case Every
  case Only(tools: Set[String])

  def admits(tool: String): Boolean = this match
    case Every       => true
    case Only(tools) => tools.contains(tool)

/** Client keys at an MCP endpoint's ACL (feature 054). */
object ClientKeys:

  /**
   * An ACL admitting a request that carries one of the service's client keys, for the endpoint it
   * was issued for, as the key's subject with its attributes as claims. Needs `McpRuntime()`; a
   * service without it does not start.
   */
  def authenticate(): Acl = Acl.Authenticate(ClientKeyAuthenticator())

/** Bound to the service's `McpRuntime` when the server starts; reads its index on each request. */
private[mcp] final class ClientKeyAuthenticator
    extends (RequestContext => AuthDecision)
    with NeedsService:

  @volatile private var runtime: Option[McpRuntime] = None

  private[mcp] def boundTo: Option[McpRuntime] = runtime

  def bind(service: AnkkaService): Either[String, Unit] =
    service.extension[McpRuntime] match
      case Some(found) =>
        found.attach(service)
        runtime = Some(found)
        Right(())
      case None =>
        Left(
          "an ACL admits client keys and the service registers no McpRuntime; " +
            "add McpRuntime() and McpRuntime.descriptors to the service"
        )

  def apply(context: RequestContext): AuthDecision =
    runtime match
      case Some(bound) => bound.authenticate(context)
      case None        =>
        AuthDecision.Unavailable(
          "client keys are not bound to the service; declare the ACL as a val, not a def"
        )
