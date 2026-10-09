package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.MachineEntity
import com.thinkmorestupidless.ankka.controlplane.auth.{
  MachineKeys,
  MachineSecrets,
  MachineSettings,
  Principals,
  TokenBucket
}
import com.thinkmorestupidless.ankka.controlplane.domain.Machine
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * What the token route answers, in RFC 6749's spelling: a token, or an error and nothing else.
 */
final case class TokenAnswer(
    access_token: Option[String] = None,
    token_type: Option[String] = None,
    expires_in: Option[Long] = None,
    error: Option[String] = None
)

object TokenAnswer:
  given JsonValueCodec[TokenAnswer] = JsonCodecMaker.make

/** The issuer and where its keys are, as a verifier discovers them. */
final case class MachineIssuerDocument(issuer: String, jwks_uri: String, token_endpoint: String)

object MachineIssuerDocument:
  given JsonValueCodec[MachineIssuerDocument] = JsonCodecMaker.make

/**
 * `POST /oauth/token` (feature 040): OAuth 2.0 client credentials for a registered machine. The one
 * write on the control plane that takes no token, so it is the one that is rate limited: a bucket
 * per client id, unknown ones included. Every way a client can be wrong — an unknown id, a wrong
 * secret, a deleted machine — is the same `401 invalid_client`, so the route tells a guesser
 * nothing.
 */
final class MachineTokenEndpoint(
    clients: EndpointClients,
    keys: MachineKeys,
    settings: MachineSettings,
    bucket: TokenBucket,
    clock: java.time.Clock = java.time.Clock.systemUTC()
) extends HttpEndpoint("/oauth"):

  val acl: Acl = Acl.AllowAll

  postBody("/token") { (body: String) =>
    val form     = MachineTokenEndpoint.form(body)
    val basic    = MachineTokenEndpoint.basic(request.header("authorization"))
    val clientId = basic.map(_._1).orElse(form.get("client_id")).getOrElse("")
    val secret   = basic.map(_._2).orElse(form.get("client_secret")).getOrElse("")
    bucket.take(clientId) match
      case Some(wait) =>
        Respond(
          TokenAnswer(error = Some("slow_down")),
          429,
          Vector("Retry-After" -> wait.toString)
        )
      case None =>
        form.get("grant_type") match
          case None => Respond(TokenAnswer(error = Some("invalid_request")), 400)
          case Some(grant) if grant != "client_credentials" =>
            Respond(TokenAnswer(error = Some("unsupported_grant_type")), 400)
          case Some(_) =>
            MachineSecrets.parseClientId(clientId).filter(_ => secret.nonEmpty) match
              case None => MachineTokenEndpoint.invalidClient
              case Some((organization, name)) =>
                val found =
                  try
                    clients.componentClient
                      .forEventSourcedEntity(EntityId(Machine.key(organization, name)))
                      .call(MachineEntity.checkSecret)
                      .invoke(secret)
                  catch case _: CommandError => None
                found match
                  case None => MachineTokenEndpoint.invalidClient
                  case Some(_) =>
                    Respond(
                      TokenAnswer(
                        access_token = Some(
                          keys.sign(
                            MachineTokenEndpoint.claims(settings, organization, name, clock)
                          )
                        ),
                        token_type = Some("Bearer"),
                        expires_in = Some(MachineSettings.TokenLifetime.toSeconds)
                      ),
                      200,
                      Vector("Cache-Control" -> "no-store")
                    )
  }

object MachineTokenEndpoint:

  private val random = new SecureRandom()

  private def invalidClient =
    Respond(
      TokenAnswer(error = Some("invalid_client")),
      401,
      Vector("WWW-Authenticate" -> """Basic realm="ankka machines"""")
    )

  /** A form body's fields, decoded; a field given twice is its first. */
  def form(body: String): Map[String, String] =
    body
      .split('&')
      .toVector
      .filter(_.nonEmpty)
      .flatMap { pair =>
        val (k, v) = pair.span(_ != '=')
        scala.util
          .Try(
            URLDecoder.decode(k, StandardCharsets.UTF_8) ->
              URLDecoder.decode(v.drop(1), StandardCharsets.UTF_8)
          )
          .toOption
      }
      .reverse
      .toMap

  /**
   * The client id and secret of an `Authorization: Basic` header, each form-decoded per RFC 6749.
   */
  def basic(header: Option[String]): Option[(String, String)] =
    header
      .map(_.trim)
      .filter(_.regionMatches(true, 0, "Basic ", 0, 6))
      .flatMap(h =>
        scala.util
          .Try(String(Base64.getDecoder.decode(h.drop(6).trim), StandardCharsets.UTF_8))
          .toOption
      )
      .filter(_.contains(':'))
      .map { pair =>
        val (id, secret) = pair.span(_ != ':')
        (
          URLDecoder.decode(id, StandardCharsets.UTF_8),
          URLDecoder.decode(secret.drop(1), StandardCharsets.UTF_8)
        )
      }

  /** A machine token's claims, as `data-model.md` lists them. */
  def claims(
      settings: MachineSettings,
      organization: String,
      name: String,
      clock: java.time.Clock
  ): String =
    val now = clock.instant().getEpochSecond
    val jti = Array.ofDim[Byte](16)
    random.nextBytes(jti)
    // Names are DNS labels and the issuer a URL; quotes and backslashes are escaped all the same.
    def str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    Vector(
      s""""iss":${str(settings.issuer)}""",
      s""""sub":${str(Machines.clientId(organization, name))}""",
      """"aud":["ankka"]""",
      s""""iat":$now""",
      s""""nbf":$now""",
      s""""exp":${now + MachineSettings.TokenLifetime.toSeconds}""",
      s""""jti":"${jti.map(b => f"${b & 0xff}%02x").mkString}"""",
      """"typ":"Bearer"""",
      s""""organization":${str(organization)}""",
      s""""machine":${str(name)}""",
      s""""broker_user":${str(Machines.brokerUser(organization, name))}"""
    ).mkString("{", ",", "}")

/**
 * `GET /.well-known/jwks.json` and `GET /.well-known/openid-configuration` (feature 040): the keys
 * machine tokens are signed with, and where they and the token route are. Public by nature: what
 * they serve proves nothing without the private halves.
 */
final class MachineKeysEndpoint(keys: MachineKeys, settings: MachineSettings)
    extends HttpEndpoint("/.well-known"):

  val acl: Acl = Acl.AllowAll

  get("/jwks.json")(() => keys.jwks)

  get("/openid-configuration")(() =>
    MachineIssuerDocument(settings.issuer, settings.jwksUrl, settings.tokenUrl)
  )

/**
 * `POST /platform/machine-keys/rotate` (feature 040): a platform administrator adds a signing key,
 * which signs from then on. The previous one stays in the key set, so a token it signed verifies
 * until it expires.
 */
final class MachineKeyRotationEndpoint(keys: MachineKeys, val acl: Acl)
    extends HttpEndpoint("/platform/machine-keys"):

  post("/rotate") { () =>
    if !Principals.isPlatformAdmin(principal) then
      throw CommandError("only a platform administrator may rotate the keys", ErrorCode.Forbidden)
    keys.rotate()
  }
