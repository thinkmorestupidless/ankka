package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{
  Acl,
  EncodedResponse,
  PathTemplate,
  RequestContext,
  RequestScope,
  Route
}
import com.thinkmorestupidless.ankka.mcp.{McpEndpoint, ResourceMetadata}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, TimerScheduler}
import com.typesafe.config.Config

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.NonFatal

/** How long each thing the broker makes lives (`ankka.mcp.sign-in`). */
final case class SignInSettings(
    tokenLifetime: FiniteDuration,
    refreshLifetime: FiniteDuration,
    grantLifetime: FiniteDuration,
    clientLifetime: FiniteDuration,
    rotateEvery: FiniteDuration,
    keepFor: FiniteDuration
)

object SignInSettings:
  def from(config: Config): SignInSettings =
    def read(key: String) = config.getDuration(s"ankka.mcp.sign-in.$key").toMillis.millis
    SignInSettings(
      read("token-lifetime"),
      read("refresh-lifetime"),
      read("grant-lifetime"),
      read("client-lifetime"),
      read("rotate-every"),
      read("keep-for")
    )

/**
 * A brokered MCP endpoint's sign-in broker (feature 054, R11): an OAuth authorization server at
 * `/_ankka/sign-in<path>` that registers any client, sends the person to the service's issuer, and
 * mints a token for this endpoint alone. It holds no account and no password: the issuer signs the
 * person in, and the broker keeps what the issuer said about them for as long as a code or a
 * refresh token lives.
 */
private[mcp] final class SignInBroker(
    path: String,
    issuer: IssuerClient,
    client: ComponentClient,
    timers: () => Option[TimerScheduler],
    tokens: BrokerTokens,
    keys: BrokerKeys,
    settings: SignInSettings,
    clock: Clock,
    secure: Boolean
):
  import SignInBroker.*
  import SignInRecord.*

  private val endpointPath = McpEndpoint.normalised(path)
  private val limiter      = RateLimit(TokensPerMinute, clock)

  private def records(id: String) = client.forKeyValueEntity(EntityId(id))

  private def origin(context: RequestContext) =
    s"${ResourceMetadata.scheme(context, secure)}://${context.header("Host").getOrElse("localhost")}"

  /** The broker's issuer URL as the client reached it. */
  def issuerUrl(context: RequestContext): String = s"${origin(context)}$BrokerPrefix$endpointPath"

  private def resourceOf(context: RequestContext): String = s"${origin(context)}$endpointPath"

  /** Writes a record, and schedules its deletion first, as an approval's expiry is scheduled. */
  private def put(id: String, record: SignInRecord, lifetime: FiniteDuration): Unit =
    val now = clock.instant()
    timers().foreach(SignInExpiry.schedule(_, id, now.plusMillis(lifetime.toMillis), now))
    records(id)
      .call(SignInRecordEntity.put)
      .invoke(PutSignInRecord(record, lifetime.toMillis)): Unit

  // ── the routes ────────────────────────────────────────────────────────────

  def metadata(context: RequestContext): EncodedResponse =
    val issuerAt = issuerUrl(context)
    json(
      200,
      Json.obj(
        "issuer"                   -> Json.str(issuerAt),
        "authorization_endpoint"   -> Json.str(s"$issuerAt/authorize"),
        "token_endpoint"           -> Json.str(s"$issuerAt/token"),
        "registration_endpoint"    -> Json.str(s"$issuerAt/register"),
        "jwks_uri"                 -> Json.str(s"$issuerAt/jwks.json"),
        "response_types_supported" -> Json.arr(Json.str("code")),
        "grant_types_supported"    -> Json
          .arr(Json.str("authorization_code"), Json.str("refresh_token")),
        "code_challenge_methods_supported"      -> Json.arr(Json.str("S256")),
        "token_endpoint_auth_methods_supported" -> Json.arr(Json.str("none")),
        "scopes_supported"                      -> Json.arr()
      )
    )

  def register(body: Array[Byte]): EncodedResponse =
    Json.parse(String(body, UTF_8)).toOption match
      case None        => oauthError(400, "invalid_client_metadata", "the body is not JSON")
      case Some(asked) =>
        val uris =
          asked("redirect_uris").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
        val name   = asked("client_name").flatMap(_.asString)
        val method = asked("token_endpoint_auth_method").flatMap(_.asString)
        if uris.isEmpty then oauthError(400, "invalid_redirect_uri", "redirect_uris is required")
        else if uris.exists(u => !acceptableRedirect(u)) then
          oauthError(
            400,
            "invalid_redirect_uri",
            "a redirect URI must be https, or http on loopback"
          )
        else if method.exists(_ != "none") then
          oauthError(
            400,
            "invalid_client_metadata",
            "only public clients are registered: token_endpoint_auth_method none"
          )
        else
          val now = clock.instant()
          val id  = IssuerClient.randomValue()
          put(
            SignInRecordEntity.clientId(id),
            Client(id, uris, name, now, now),
            settings.clientLifetime
          )
          json(
            201,
            Json.Obj(
              Map(
                "client_id"                  -> Json.str(id),
                "client_id_issued_at"        -> Json.num(now.getEpochSecond.toDouble),
                "redirect_uris"              -> Json.Arr(uris.map(Json.str)),
                "token_endpoint_auth_method" -> Json.str("none"),
                "grant_types"                -> Json
                  .arr(Json.str("authorization_code"), Json.str("refresh_token")),
                "response_types" -> Json.arr(Json.str("code"))
              ) ++ name.map("client_name" -> Json.str(_))
            )
          )

  def authorize(context: RequestContext): EncodedResponse =
    val query               = context.query
    def param(name: String) = query.raw(name).map(_.trim).filter(_.nonEmpty)
    val asked               = for
      clientId  <- param("client_id").toRight("client_id")
      found     <- registered(clientId).toRight("client_id")
      redirect  <- param("redirect_uri").toRight("redirect_uri")
      _         <- Either.cond(found.redirectUris.contains(redirect), (), "redirect_uri")
      _         <- Either.cond(param("response_type").contains("code"), (), "response_type")
      challenge <- param("code_challenge").toRight("code_challenge")
      _ <- Either.cond(param("code_challenge_method").contains("S256"), (), "code_challenge_method")
      resource = param("resource").getOrElse(resourceOf(context))
      _ <- Either.cond(pathOf(resource).contains(endpointPath), (), "resource")
    yield (found, redirect, challenge, resource)
    asked match
      case Left(parameter) =>
        page(400, s"The sign-in request has a missing or unregistered $parameter.")
      case Right((found, redirect, challenge, resource)) =>
        val state    = IssuerClient.randomValue()
        val nonce    = IssuerClient.randomValue()
        val verifier = IssuerClient.randomValue()
        issuer.authorizationUrl(s"${issuerUrl(context)}/callback", state, nonce, verifier) match
          case Left(reason)  => page(503, s"The service's issuer cannot be reached: $reason")
          case Right(sendTo) =>
            val now   = clock.instant()
            val grant = Grant(
              found.clientId,
              redirect,
              challenge,
              resource,
              param("state"),
              nonce,
              verifier,
              issuer.issuer.name,
              now
            )
            val id = SignInRecordEntity.grantId(state)
            timers().foreach(
              SignInExpiry.schedule(_, id, now.plusMillis(settings.grantLifetime.toMillis), now)
            )
            records(id)
              .call(SignInRecordEntity.beginGrant)
              .invoke(PutSignInRecord(grant, settings.grantLifetime.toMillis))
            // Used: kept for another client lifetime from now.
            put(
              SignInRecordEntity.clientId(found.clientId),
              found.copy(lastUsedAt = now),
              settings.clientLifetime
            )
            redirectTo(sendTo)

  def callback(context: RequestContext): EncodedResponse =
    val query = context.query
    val state = query.raw("state").getOrElse("")
    val id    = SignInRecordEntity.grantId(state)
    records(id).call(SignInRecordEntity.get).invoke() match
      case grant: Grant if grant.codeDigest.isEmpty =>
        def refused(why: String) =
          forget(id)
          redirectTo(
            withQuery(
              grant.redirectUri,
              Vector("error" -> "access_denied", "error_description" -> why) ++ grant.clientState
                .map("state" -> _)
            )
          )
        query.raw("error") match
          case Some(_) => refused("the issuer did not sign the person in")
          case None    =>
            val signedIn = for
              code    <- query.raw("code").filter(_.nonEmpty).toRight("the issuer sent no code")
              idToken <- issuer.exchange(
                code,
                s"${issuerUrl(context)}/callback",
                grant.issuerVerifier
              )
              person <- issuer.verifyIdToken(idToken, grant.nonce)
            yield SignedIn.of(person, grant.issuer)
            signedIn match
              case Left(reason)  => refused(reason)
              case Right(person) =>
                val secret = IssuerClient.randomValue()
                try
                  records(id)
                    .call(SignInRecordEntity.completeGrant)
                    .invoke(
                      CompleteGrant(
                        person,
                        IssuerClient.digest(secret),
                        settings.grantLifetime.toMillis
                      )
                    )
                  redirectTo(
                    withQuery(
                      grant.redirectUri,
                      Vector("code" -> s"$state.$secret") ++ grant.clientState.map("state" -> _)
                    )
                  )
                catch
                  case e: CommandError if e.code == ErrorCode.Conflict =>
                    page(400, "This sign-in has already finished.")
      case _ => page(400, "This sign-in is unknown or has expired; start again from the assistant.")

  def token(body: Array[Byte]): EncodedResponse =
    val form     = IssuerClient.form(String(body, UTF_8))
    val clientId = form.getOrElse("client_id", "")
    limiter.take(if clientId.isEmpty then "-" else clientId) match
      case Some(wait) =>
        oauthError(
          429,
          "slow_down",
          "too many token requests",
          Vector("Retry-After" -> wait.toString)
        )
      case None =>
        registered(clientId) match
          case None    => oauthError(400, "invalid_client", "the client is not registered")
          case Some(_) =>
            form.get("grant_type") match
              case Some("authorization_code") => byCode(clientId, form)
              case Some("refresh_token")      => byRefresh(clientId, form)
              case _                          =>
                oauthError(
                  400,
                  "unsupported_grant_type",
                  "grant_type is authorization_code or refresh_token"
                )

  def jwks: EncodedResponse = json(200, keys.jwks)

  // ── the token endpoint's two grants ──────────────────────────────────────

  private def byCode(clientId: String, form: Map[String, String]): EncodedResponse =
    val (state, secret) = form.getOrElse("code", "").span(_ != '.')
    val id              = SignInRecordEntity.grantId(state)
    val taken           = Try(
      records(id)
        .call(SignInRecordEntity.takeCode)
        .invoke(TakeCode(IssuerClient.digest(secret.drop(1))))
    ).toOption
    timers().foreach(SignInExpiry.forget(_, id))
    taken match
      case Some(grant: Grant)
          if grant.clientId == clientId &&
            form.get("redirect_uri").contains(grant.redirectUri) &&
            form
              .get("code_verifier")
              .exists(v => IssuerClient.challenge(v) == grant.codeChallenge) &&
            form.get("resource").forall(r => pathOf(r) == pathOf(grant.resource)) =>
        grant.signedIn match
          case Some(person) => issue(clientId, person, grant.resource)
          case None         => oauthError(400, "invalid_grant", "the code is not valid")
      case _ => oauthError(400, "invalid_grant", "the code is not valid")

  private def byRefresh(clientId: String, form: Map[String, String]): EncodedResponse =
    val id = SignInRecordEntity.refreshId(IssuerClient.digest(form.getOrElse("refresh_token", "")))
    val taken = Try(records(id).call(SignInRecordEntity.takeRefresh).invoke()).toOption
    timers().foreach(SignInExpiry.forget(_, id))
    taken match
      case Some(refresh: RefreshToken)
          if refresh.clientId == clientId && refresh.expiresAt.isAfter(clock.instant()) =>
        issue(clientId, refresh.signedIn, refresh.resource)
      case _ => oauthError(400, "invalid_grant", "the refresh token is not valid")

  /** An access token, and a refresh token that replaces any used to get it. */
  private def issue(clientId: String, person: SignedIn, resource: String): EncodedResponse =
    val context =
      RequestScope.currentContext.getOrElse(throw IllegalStateException("outside a request"))
    val access  = tokens.mint(issuerUrl(context), resource, person)
    val refresh = IssuerClient.randomValue()
    val now     = clock.instant()
    val expires = now.plusMillis(settings.refreshLifetime.toMillis)
    put(
      SignInRecordEntity.refreshId(IssuerClient.digest(refresh)),
      RefreshToken(clientId, person, resource, now, expires),
      settings.refreshLifetime
    )
    json(
      200,
      Json.obj(
        "access_token"  -> Json.str(access),
        "token_type"    -> Json.str("Bearer"),
        "expires_in"    -> Json.num(settings.tokenLifetime.toSeconds.toDouble),
        "refresh_token" -> Json.str(refresh)
      ),
      Vector("Cache-Control" -> "no-store", "Pragma" -> "no-cache")
    )

  private def registered(clientId: String): Option[Client] =
    if clientId.isEmpty then None
    else
      Try(
        records(SignInRecordEntity.clientId(clientId)).call(SignInRecordEntity.get).invoke()
      ).toOption.collect { case found: Client =>
        found
      }

  private def forget(id: String): Unit =
    try records(id).call(SignInRecordEntity.delete).invoke(): Unit
    catch case NonFatal(_) => ()
    timers().foreach(SignInExpiry.forget(_, id))

object SignInBroker:

  /** Under which every broker's routes are. */
  val BrokerPrefix: String = BrokerTokens.BrokerPrefix

  /**
   * Token requests per client id per minute, per instance: the control plane's token route has 12.
   */
  val TokensPerMinute: Int = 30

  /**
   * The six routes, at whole paths of their own, open to every caller: a person signing in has no
   * credential yet. Each answers 503 until the endpoint is mounted and its broker is bound.
   */
  def routes(path: String, bound: () => Option[SignInBroker]): Vector[Route] =
    val at = s"$BrokerPrefix${McpEndpoint.normalised(path)}"
    def route(method: String, template: String, body: Boolean)(
        answer: (SignInBroker, RequestContext, Array[Byte]) => EncodedResponse
    ) =
      Route(
        method,
        PathTemplate.parse(template),
        body,
        (_, bytes) =>
          val context = RequestScope.currentContext.getOrElse(
            throw IllegalStateException("the sign-in broker answers only inside a request")
          )
          bound() match
            case None         => page(503, "The sign-in broker is not running yet.")
            case Some(broker) => answer(broker, context, bytes)
        ,
        Some(Acl.AllowAll)
      )
    Vector(
      route("GET", s"/.well-known/oauth-authorization-server$at", false)((b, c, _) =>
        b.metadata(c)
      ),
      route("POST", s"$at/register", true)((b, _, body) => b.register(body)),
      route("GET", s"$at/authorize", false)((b, c, _) => b.authorize(c)),
      route("GET", s"$at/callback", false)((b, c, _) => b.callback(c)),
      route("POST", s"$at/token", true)((b, _, body) => b.token(body)),
      route("GET", s"$at/jwks.json", false)((b, _, _) => b.jwks)
    )

  /** `https`, or `http` on a loopback address: what a native or desktop client listens on. */
  def acceptableRedirect(uri: String): Boolean =
    Try(URI.create(uri)).toOption.exists { u =>
      Option(u.getFragment).isEmpty && (u.getScheme match
        case "https" => Option(u.getHost).exists(_.nonEmpty)
        case "http"  =>
          Option(u.getHost)
            .exists(h => h == "localhost" || h == "127.0.0.1" || h == "[::1]" || h == "::1")
        case _ => false)
    }

  private def pathOf(url: String): Option[String] =
    Try(URI.create(url)).toOption.flatMap(u => Option(u.getPath)).map(McpEndpoint.normalised)

  private def withQuery(base: String, params: Vector[(String, String)]): String =
    val query = params.map((k, v) => s"$k=${IssuerClient.encode(v)}").mkString("&")
    s"$base${if base.contains('?') then "&" else "?"}$query"

  private def json(status: Int, body: Json, headers: Vector[(String, String)] = Vector.empty) =
    EncodedResponse(status, "application/json", body.render.getBytes(UTF_8), headers)

  private def oauthError(
      status: Int,
      error: String,
      description: String,
      headers: Vector[(String, String)] = Vector.empty
  ) =
    json(
      status,
      Json.obj("error" -> Json.str(error), "error_description" -> Json.str(description)),
      headers :+ ("Cache-Control" -> "no-store")
    )

  private def redirectTo(location: String) =
    EncodedResponse(
      303,
      "text/plain",
      Array.emptyByteArray,
      Vector("Location" -> location, "Cache-Control" -> "no-store")
    )

  private def page(status: Int, text: String) =
    val escaped = text.flatMap {
      case '<'   => "&lt;"
      case '>'   => "&gt;"
      case '&'   => "&amp;"
      case '"'   => "&quot;"
      case other => other.toString
    }
    EncodedResponse(
      status,
      "text/html; charset=utf-8",
      s"<!doctype html><html><head><meta charset=\"utf-8\"><title>Sign-in</title></head><body><p>$escaped</p></body></html>"
        .getBytes(UTF_8),
      Vector("Cache-Control" -> "no-store")
    )

/**
 * Token requests per key per minute, as a bucket that refills: a copy of the control plane's
 * `TokenBucket`, whose module `mcp` cannot see.
 */
private[signin] final class RateLimit(rate: Int, clock: Clock):
  private final case class Bucket(tokens: Double, at: Instant)
  private val buckets   = ConcurrentHashMap[String, Bucket]()
  private val perSecond = rate.toDouble / 60.0

  /** `None` when `key` may go ahead now, else how many seconds until it may. */
  def take(key: String): Option[Long] =
    val now     = clock.instant()
    var refused = Option.empty[Long]
    buckets.compute(
      key,
      (_, held) =>
        val start   = Option(held).getOrElse(Bucket(rate.toDouble, now))
        val elapsed = Duration.between(start.at, now).toMillis.max(0L) / 1000.0
        val filled  = (start.tokens + elapsed * perSecond).min(rate.toDouble)
        if filled >= 1.0 then
          refused = None
          Bucket(filled - 1.0, now)
        else
          refused = Some(math.ceil((1.0 - filled) / perSecond).toLong.max(1L))
          Bucket(filled, now)
    ): Unit
    refused
