package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.auth.oidc.{IdTokenVerifier, Issuer}
import com.thinkmorestupidless.ankka.http.Principal

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try
import scala.util.control.NonFatal

/**
 * The sign-in broker as a relying party of one of the service's issuers (feature 054, R11): the
 * plainest OpenID Connect code flow, under the broker's own client id there, with PKCE always and
 * the client secret when the issuer was given one.
 *
 * The issuer's endpoints are read from its discovery document the first time they are needed, and
 * read again only after a failure. The ID token is verified as `OidcVerifier` verifies a token,
 * audience the client id, and must carry the nonce the broker sent.
 */
final class IssuerClient(
    val issuer: Issuer,
    verifier: => IdTokenVerifier,
    http: HttpClient = IssuerClient.defaultHttp
):
  import IssuerClient.*

  val clientId: String = issuer.clientId.getOrElse(
    throw IllegalArgumentException(s"issuer '${issuer.name}' has no client id")
  )

  private lazy val verifying = verifier
  private val discovered     = AtomicReference[Option[Endpoints]](None)

  /** Where the issuer signs people in and hands back tokens. */
  def endpoints(): Either[String, Endpoints] =
    discovered.get match
      case Some(found) => Right(found)
      case None        =>
        val url = s"${issuer.issuer.stripSuffix("/")}/.well-known/openid-configuration"
        get(url)
          .flatMap { body =>
            (for
              document  <- Json.parse(body).toOption
              authorize <- document("authorization_endpoint").flatMap(_.asString)
              token     <- document("token_endpoint").flatMap(_.asString)
            yield Endpoints(authorize, token))
              .toRight(s"the issuer's discovery document at $url names no endpoints")
          }
          .map { found =>
            discovered.set(Some(found))
            found
          }

  /** Where to send the person: the issuer's sign-in, back to `redirectUri` with `state`. */
  def authorizationUrl(
      redirectUri: String,
      state: String,
      nonce: String,
      verifier: String
  ): Either[String, String] =
    endpoints().map { found =>
      val query = Vector(
        "response_type"         -> "code",
        "client_id"             -> clientId,
        "redirect_uri"          -> redirectUri,
        "scope"                 -> "openid",
        "state"                 -> state,
        "nonce"                 -> nonce,
        "code_challenge"        -> challenge(verifier),
        "code_challenge_method" -> "S256"
      ).map((k, v) => s"$k=${encode(v)}").mkString("&")
      val separator = if found.authorization.contains('?') then "&" else "?"
      s"${found.authorization}$separator$query"
    }

  /** Exchanges the issuer's code for its ID token. */
  def exchange(code: String, redirectUri: String, verifier: String): Either[String, String] =
    endpoints().flatMap { found =>
      val form = (Vector(
        "grant_type"    -> "authorization_code",
        "code"          -> code,
        "redirect_uri"  -> redirectUri,
        "client_id"     -> clientId,
        "code_verifier" -> verifier
      ) ++ issuer.clientSecret.map("client_secret" -> _))
        .map((k, v) => s"$k=${encode(v)}")
        .mkString("&")
      val request = HttpRequest
        .newBuilder(URI.create(found.token))
        .timeout(Timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
      send(request).flatMap { (status, body) =>
        if status != 200 then Left(s"the issuer refused the code ($status)")
        else
          Json
            .parse(body)
            .toOption
            .flatMap(_("id_token"))
            .flatMap(_.asString)
            .toRight(
              "the issuer answered no ID token"
            )
      }
    }

  /** The person an ID token names, when it is the issuer's and carries `nonce`. */
  def verifyIdToken(token: String, nonce: String): Either[String, Principal] =
    verifying.verify(token, nonce)

  private def get(url: String): Either[String, String] =
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Timeout).GET().build()
    send(request).flatMap((status, body) =>
      if status == 200 then Right(body) else Left(s"the issuer answered $status at $url")
    )

  private def send(request: HttpRequest): Either[String, (Int, String)] =
    try
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      Right(response.statusCode() -> response.body())
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("interrupted")
      case NonFatal(e) => Left(s"the issuer could not be reached: ${e.getClass.getSimpleName}")

object IssuerClient:

  final case class Endpoints(authorization: String, token: String)

  private val Timeout = Duration.ofSeconds(10)

  private lazy val defaultHttp: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Timeout)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  def apply(issuer: Issuer): IssuerClient =
    new IssuerClient(issuer, IdTokenVerifier.remote(issuer, issuer.clientId.getOrElse("")))

  private val random = SecureRandom()

  /** A random value for a state, a nonce, a code or a PKCE verifier: 32 bytes, base64url. */
  def randomValue(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** S256: the base64url SHA-256 of the verifier. */
  def challenge(verifier: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(UTF_8))
    )

  /** The digest a record keeps of a code or a refresh token. */
  def digest(value: String): String =
    java.util.HexFormat
      .of()
      .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))

  def encode(value: String): String = java.net.URLEncoder.encode(value, UTF_8)

  def decode(value: String): String = Try(java.net.URLDecoder.decode(value, UTF_8)).getOrElse(value)

  /** `a=1&b=2`, decoded; the first of a repeated name. */
  def form(text: String): Map[String, String] =
    text
      .split('&')
      .filter(_.nonEmpty)
      .map { pair =>
        val (k, v) = pair.span(_ != '=')
        decode(k) -> decode(v.drop(1))
      }
      .reverse
      .toMap
