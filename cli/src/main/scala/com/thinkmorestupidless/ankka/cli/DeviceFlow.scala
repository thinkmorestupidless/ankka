package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.Codecs

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import scala.util.Try

/** Where the issuer's endpoints are, from its discovery document. */
final case class Discovery(
    deviceAuthorizationEndpoint: String,
    tokenEndpoint: String,
    revocationEndpoint: Option[String]
)

/** What the issuer handed back to start a device login. */
final case class DeviceAuthorization(
    deviceCode: String,
    userCode: String,
    verificationUri: String,
    verificationUriComplete: Option[String],
    expiresIn: Int,
    interval: Int
)

/** A minted token pair. */
final case class Tokens(accessToken: String, refreshToken: Option[String], expiresIn: Int)

/**
 * The OAuth 2.0 device authorization grant (RFC 8628), as `ankka login` runs it, plus refresh and
 * revocation. No browser on this machine is assumed: the code is shown, and the sign-in can be
 * completed anywhere.
 *
 * The JDK client, like the rest of the CLI, with the same trust root the control plane's URL is
 * given: the identity provider sits behind the same gateway and certificate.
 */
final class DeviceFlow(settings: Settings, sleep: Long => Unit = millis => Thread.sleep(millis)):

  import DeviceFlow.*

  val Scope = "openid offline_access"

  private val http =
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
    settings.ca.foreach(pem => builder.sslContext(Trust.sslContext(Paths.get(pem))): Unit)
    builder.build()

  def discover(issuer: String): Discovery =
    val response = get(s"${issuer.stripSuffix("/")}/.well-known/openid-configuration")
    if response.statusCode != 200 then
      throw ApiError(
        0,
        s"could not read the identity provider's discovery document at $issuer: HTTP ${response.statusCode}"
      )
    val document = decode[DiscoveryDocument](response.body)
    Discovery(
      document.device_authorization_endpoint.getOrElse(
        throw ApiError(
          0,
          s"the identity provider at $issuer does not offer the device authorization grant"
        )
      ),
      document.token_endpoint,
      document.revocation_endpoint
    )

  def start(discovery: Discovery, clientId: String): DeviceAuthorization =
    val response =
      form(discovery.deviceAuthorizationEndpoint, Map("client_id" -> clientId, "scope" -> Scope))
    if response.statusCode != 200 then
      throw ApiError(
        0,
        s"the identity provider refused to start a login: ${failure(response.body)}"
      )
    val body = decode[DeviceResponse](response.body)
    DeviceAuthorization(
      body.device_code,
      body.user_code,
      body.verification_uri,
      body.verification_uri_complete,
      body.expires_in,
      body.interval.getOrElse(5)
    )

  /**
   * Polls until the user has signed in, the code expires, or the issuer says no.
   *
   * `slow_down` lengthens the interval by five seconds, as the RFC says; `authorization_pending` is
   * the normal answer while the browser is open.
   */
  def poll(discovery: Discovery, clientId: String, device: DeviceAuthorization): Tokens =
    var interval               = device.interval.toLong
    val deadline               = System.nanoTime() + device.expiresIn.toLong * 1_000_000_000L
    var result: Option[Tokens] = None
    while result.isEmpty do
      if System.nanoTime() > deadline then
        throw ApiError(0, "the code expired before the login completed; run 'ankka login' again")
      sleep(interval * 1000)
      val response = form(
        discovery.tokenEndpoint,
        Map(
          "grant_type"  -> "urn:ietf:params:oauth:grant-type:device_code",
          "device_code" -> device.deviceCode,
          "client_id"   -> clientId
        )
      )
      if response.statusCode == 200 then result = Some(tokens(response.body))
      else
        decodeOption[ErrorResponse](response.body).map(_.error) match
          case Some("authorization_pending") => ()
          case Some("slow_down")             => interval += 5
          case Some("expired_token") =>
            throw ApiError(
              0,
              "the code expired before the login completed; run 'ankka login' again"
            )
          case Some("access_denied") => throw ApiError(0, "the login was refused")
          case _ =>
            throw ApiError(0, s"the identity provider refused the login: ${failure(response.body)}")
    result.get

  def refresh(
      discovery: Discovery,
      clientId: String,
      refreshToken: String
  ): Either[String, Tokens] =
    val response = form(
      discovery.tokenEndpoint,
      Map("grant_type" -> "refresh_token", "client_id" -> clientId, "refresh_token" -> refreshToken)
    )
    if response.statusCode == 200 then Right(tokens(response.body))
    else Left(failure(response.body))

  /** Best effort: a logout that cannot reach the issuer still forgets the login locally. */
  def revoke(discovery: Discovery, clientId: String, refreshToken: String): Either[String, Unit] =
    discovery.revocationEndpoint match
      case None => Right(())
      case Some(endpoint) =>
        Try(
          form(
            endpoint,
            Map(
              "client_id"       -> clientId,
              "token"           -> refreshToken,
              "token_type_hint" -> "refresh_token"
            )
          )
        ).toEither.left
          .map(_.getMessage)
          .flatMap(r => if r.statusCode == 200 then Right(()) else Left(failure(r.body)))

  private def tokens(body: String): Tokens =
    val t = decode[TokenResponse](body)
    Tokens(t.access_token, t.refresh_token, t.expires_in)

  private def failure(body: String): String =
    decodeOption[ErrorResponse](body)
      .map(e => e.error_description.getOrElse(e.error))
      .getOrElse(body.take(200))

  private def get(url: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build())

  private def form(url: String, fields: Map[String, String]): HttpResponse[String] =
    val encoded = fields
      .map((k, v) =>
        URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder
          .encode(v, StandardCharsets.UTF_8)
      )
      .mkString("&")
    send(
      HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encoded))
        .build()
    )

  private def send(request: HttpRequest): HttpResponse[String] =
    try http.send(request, HttpResponse.BodyHandlers.ofString())
    catch
      case error: javax.net.ssl.SSLHandshakeException =>
        throw ApiError(
          0,
          s"could not verify ${request.uri}: ${error.getMessage}\n  if this is a local ankka cluster, run: ankka config set ca ~/.ankka/local-ca.crt"
        )
      case error: java.io.IOException =>
        throw ApiError(0, s"could not reach ${request.uri}: ${error.getMessage}")

  private def decode[A](body: String)(using JsonValueCodec[A]): A =
    decodeOption[A](body).getOrElse(
      throw ApiError(0, s"unexpected answer from the identity provider: ${body.take(200)}")
    )

  private def decodeOption[A](body: String)(using JsonValueCodec[A]): Option[A] =
    Try(readFromString[A](body)).toOption

object DeviceFlow:
  // Field names are the wire's own (snake case), so no annotation is needed to decode them.
  private final case class DiscoveryDocument(
      token_endpoint: String,
      device_authorization_endpoint: Option[String] = None,
      revocation_endpoint: Option[String] = None
  )
  private final case class DeviceResponse(
      device_code: String,
      user_code: String,
      verification_uri: String,
      verification_uri_complete: Option[String] = None,
      expires_in: Int,
      interval: Option[Int] = None
  )
  private final case class TokenResponse(
      access_token: String,
      refresh_token: Option[String] = None,
      expires_in: Int
  )
  private final case class ErrorResponse(error: String, error_description: Option[String] = None)

  private given JsonValueCodec[DiscoveryDocument] = Codecs.make[DiscoveryDocument]
  private given JsonValueCodec[DeviceResponse]    = Codecs.make[DeviceResponse]
  private given JsonValueCodec[TokenResponse]     = Codecs.make[TokenResponse]
  private given JsonValueCodec[ErrorResponse]     = Codecs.make[ErrorResponse]
