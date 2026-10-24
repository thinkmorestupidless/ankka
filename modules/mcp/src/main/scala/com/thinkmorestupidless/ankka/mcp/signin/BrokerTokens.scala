package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.http.{
  AuthDecision,
  NeedsService,
  Principal,
  RequestContext,
  TokenAuthenticator
}
import com.thinkmorestupidless.ankka.mcp.{McpEndpoint, McpRuntime}
import com.thinkmorestupidless.ankka.runtime.AnkkaService

import java.net.URI
import java.security.PublicKey
import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The access tokens a sign-in broker mints and its MCP endpoint accepts (feature 054, R11): RS256
 * under the service's broker keys, `iss` the broker, `aud` the endpoint a client asked for. A token
 * is accepted only at the endpoint its `aud` names, and only by the service whose keys signed it.
 */
final class BrokerTokens(
    signing: () => (String, java.security.PrivateKey),
    publicKey: String => Option[PublicKey],
    clock: Clock,
    lifetime: FiniteDuration
):
  import BrokerTokens.*

  /** A token for `signedIn` at `resource`, from the broker at `issuerUrl`. */
  def mint(issuerUrl: String, resource: String, signedIn: SignedIn): String =
    val now                         = clock.instant().getEpochSecond
    val (kid, key)                  = signing()
    val person: Seq[(String, Json)] =
      signedIn.name.map(n => "name" -> Json.str(n)).toSeq ++
        signedIn.email.map(e => "email" -> Json.str(e)).toSeq ++
        Seq(
          "email_verified" -> Json.bool(signedIn.emailVerified),
          "roles"          -> Json.Arr(signedIn.roles.toVector.sorted.map(Json.str))
        )
    val kept =
      signedIn.claims.filterNot((k, _) => Reserved.contains(k)).map((k, v) => k -> Json.str(v))
    val claims = Json.Obj(
      kept ++ person ++ Map(
        "iss"            -> Json.str(issuerUrl),
        "sub"            -> Json.str(signedIn.subject),
        "aud"            -> Json.str(resource),
        "iat"            -> Json.num(now.toDouble),
        "exp"            -> Json.num((now + lifetime.toSeconds).toDouble),
        "jti"            -> Json.str(UUID.randomUUID().toString),
        "typ"            -> Json.str("Bearer"),
        "sign_in_issuer" -> Json.str(signedIn.issuer)
      )
    )
    Jwt.sign(kid, claims, key)

  /** The person a token proves at the endpoint at `path`, or why it proves nobody. */
  def verify(token: String, path: String): Either[String, Principal] =
    for
      parts <- Jwt.parts(token)
      _     <- Either.cond(
        parts.alg.contains("RS256"),
        (),
        s"algorithm '${parts.alg.getOrElse("")}' is not RS256"
      )
      kid <- parts.kid.filter(_.nonEmpty).toRight("no key id")
      key <- publicKey(kid).toRight(s"key '$kid' is not this service's")
      _   <- Either.cond(Jwt.verifies(key, parts), (), "a bad signature")
      claims = parts.claims
      iss <- claims("iss").flatMap(_.asString).toRight("no issuer")
      aud <- claims("aud").flatMap(_.asString).toRight("no audience")
      _   <- Either.cond(sameOrigin(iss, aud), (), "issuer and audience are not one service's")
      _   <- Either.cond(
        pathOf(iss).contains(BrokerPrefix + McpEndpoint.normalised(path)),
        (),
        "not this endpoint's sign-in broker"
      )
      _ <- Either.cond(
        pathOf(aud).contains(McpEndpoint.normalised(path)),
        (),
        "audience is not this endpoint"
      )
      exp <- claims("exp").flatMap(_.asDouble).toRight("no expiry")
      _   <- Either.cond(
        Instant.ofEpochSecond(exp.toLong).plusSeconds(Skew).isAfter(clock.instant()),
        (),
        "expired"
      )
      _ <- Either.cond(
        claims("typ").flatMap(_.asString).contains("Bearer"),
        (),
        "not a bearer token"
      )
      subject <- claims("sub").flatMap(_.asString).filter(_.nonEmpty).toRight("no subject")
    yield principalOf(subject, claims)

  private def principalOf(subject: String, claims: Json): Principal =
    val fields = claims match
      case Json.Obj(f) => f
      case _           => Map.empty[String, Json]
    Principal(
      subject = subject,
      name = claims("name").flatMap(_.asString),
      email = claims("email").flatMap(_.asString),
      emailVerified = claims("email_verified").flatMap(_.asBoolean).getOrElse(false),
      roles =
        claims("roles").flatMap(_.asArray).map(_.flatMap(_.asString).toSet).getOrElse(Set.empty),
      claims = fields.collect {
        case (k, Json.Str(v)) if !Structural.contains(k) => k -> v
      },
      issuer = Some(Issuer)
    )

object BrokerTokens:

  /** What a broker token's principal says verified it. */
  val Issuer: String = "sign-in"

  /** Under which every broker's routes are: `/_ankka/sign-in<path>`. */
  val BrokerPrefix: String = "/_ankka/sign-in"

  private val Skew: Long = 60L

  /** Claims a token states itself; a person's claim of the same name is not carried. */
  private val Reserved: Set[String] =
    Set(
      "iss",
      "sub",
      "aud",
      "iat",
      "exp",
      "nbf",
      "jti",
      "typ",
      "sign_in_issuer",
      "name",
      "email",
      "email_verified",
      "roles"
    )

  /** Claims a principal holds in fields of its own, or that only describe the token. */
  private val Structural: Set[String] =
    Set("iss", "sub", "aud", "iat", "exp", "nbf", "jti", "typ", "name", "email")

  private def uri(text: String) = Try(URI.create(text)).toOption

  private def pathOf(url: String): Option[String] =
    uri(url).flatMap(u => Option(u.getPath)).map(McpEndpoint.normalised)

  private def sameOrigin(a: String, b: String): Boolean =
    (uri(a), uri(b)) match
      case (Some(x), Some(y)) =>
        x.getScheme == y.getScheme && x.getHost == y.getHost && x.getPort == y.getPort
      case _ => false

/**
 * The broker's tokens as an ACL sees them, bound to the service's `McpRuntime` when the server
 * starts. Only a bearer the broker could have minted is looked at; anything else is the challenge
 * the endpoint's own authenticators answer.
 */
private[mcp] final class BrokerAuthenticator(path: String)
    extends TokenAuthenticator
    with NeedsService:

  private val tokens = AtomicReference[BrokerTokens]()

  /**
   * Named per request by the endpoint's metadata, not here: the broker's URL is the request's host.
   */
  def authorizationServers: Vector[String] = Vector.empty

  def bind(service: AnkkaService): Either[String, Unit] =
    service.extension[McpRuntime] match
      case Some(runtime) =>
        runtime.attach(service)
        tokens.set(runtime.brokerTokens)
        Right(())
      case None =>
        Left(
          "an MCP endpoint opts into brokered sign-in and the service registers no McpRuntime; " +
            "add McpRuntime() and McpRuntime.descriptors to the service"
        )

  def apply(context: RequestContext): AuthDecision =
    val challenge = """realm="ankka""""
    bearer(context) match
      case None        => AuthDecision.Unauthenticated(challenge)
      case Some(token) =>
        Option(tokens.get()) match
          case None         => AuthDecision.Unavailable("the sign-in broker has not started")
          case Some(broker) =>
            broker.verify(token, path) match
              case Right(principal) => AuthDecision.Allow(principal)
              case Left(reason)     =>
                AuthDecision.Unauthenticated(
                  s"""$challenge, error="invalid_token", error_description="${reason
                      .replace("\"", "'")}""""
                )

  private def bearer(context: RequestContext): Option[String] =
    context
      .header("authorization")
      .map(_.trim)
      .collect { case value if value.startsWith("Bearer ") => value.drop(7).trim }
      .filter(_.nonEmpty)
