package com.thinkmorestupidless.ankka.auth.oidc

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * An in-process issuer: a signing key, its keys served on loopback, and tokens minted to order.
 *
 * What every suite that verifies tokens runs against instead of a real identity provider:
 * deterministic, instant, and able to do what a real one will not on demand: rotate its key, go
 * away, answer slowly, sign with the wrong algorithm. The control plane's suites extend it, so
 * there is one test issuer as there is one verifier.
 *
 * `name` is what a service would call it in `ANKKA_AUTH_ISSUERS`; `defaultAudience` is the audience
 * a token is minted for when a test does not say.
 */
class TestIssuer(
    val issuer: String = "https://auth.example.test/realms/test",
    val name: String = "test",
    val defaultAudience: String = "test",
    /** The loopback port to serve on; 0 picks one. `TestIssuer.serving` names it in the issuer. */
    port: Int = 0
):

  private val generator = KeyPairGenerator.getInstance("RSA")
  generator.initialize(2048)

  @volatile private var current: (String, RSAPrivateKey, RSAPublicKey) = generate("k1")
  @volatile private var offline: Boolean                               = false
  @volatile private var delay: FiniteDuration                          = 0.millis

  /** How many times the keys have been asked for, answered or not. */
  val fetches = new AtomicInteger(0)

  private def generate(kid: String) =
    val pair = generator.generateKeyPair()
    (kid, pair.getPrivate.asInstanceOf[RSAPrivateKey], pair.getPublic.asInstanceOf[RSAPublicKey])

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
  // Several threads, so a test that delays one fetch does not hold up the next.
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
  server.createContext(
    "/jwks",
    exchange =>
      fetches.incrementAndGet()
      if delay.toMillis > 0 then Thread.sleep(delay.toMillis)
      if offline then
        exchange.sendResponseHeaders(503, -1)
        exchange.close()
      else
        val (kid, _, pub) = current
        val body = new JWKSet(new RSAKey.Builder(pub).keyID(kid).build().toPublicJWK)
          .toString(true)
          .getBytes("UTF-8")
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
  )
  codeFlow()
  server.start()

  def jwksUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/jwks"
  def kid: String     = current._1

  def rotate(kid: String): Unit                = current = generate(kid)
  def setOffline(value: Boolean): Unit         = offline = value
  def delayResponses(by: FiniteDuration): Unit = delay = by
  def stop(): Unit                             = server.stop(0)

  /** This issuer as a service would list it. */
  def asIssuer(
      audience: String = defaultAudience,
      typ: Option[String] = None,
      skew: FiniteDuration = Issuer.DefaultClockSkew
  ): Issuer = Issuer(name, issuer, jwksUrl, audience, typ = typ, clockSkew = skew)

  /** A key source that refetches quickly enough for a test to see a rotation. */
  def keySource(
      outageTolerance: FiniteDuration = OidcVerifier.OutageTolerance,
      cacheFor: FiniteDuration = 5.minutes
  ) =
    OidcVerifier.keySource(
      asIssuer(),
      minTimeBetweenFetches = 200.millis,
      outageTolerance,
      cacheFor
    )

  /** A token as Keycloak would mint it. */
  def token(
      subject: String,
      email: Option[String] = None,
      emailVerified: Boolean = true,
      roles: Set[String] = Set.empty,
      name: Option[String] = None,
      audience: Seq[String] = Seq(defaultAudience),
      issuer: String = this.issuer,
      expiresIn: FiniteDuration = 5.minutes,
      notBefore: Option[Instant] = None,
      typ: Option[String] = Some("Bearer"),
      kid: Option[String] = None,
      claims: Map[String, Any] = Map.empty
  ): String =
    val now = Instant.now()
    val builder = new JWTClaimsSet.Builder()
      .subject(subject)
      .issuer(issuer)
      .audience(audience.asJava)
      .issueTime(java.util.Date.from(now))
      .expirationTime(java.util.Date.from(now.plusMillis(expiresIn.toMillis)))
      .claim("realm_access", Map("roles" -> roles.toList.asJava).asJava)
      .claim("email_verified", emailVerified)
    email.foreach(e => builder.claim("email", e): Unit)
    name.foreach(n => builder.claim("name", n): Unit)
    typ.foreach(t => builder.claim("typ", t): Unit)
    notBefore.foreach(t => builder.notBeforeTime(java.util.Date.from(t)): Unit)
    claims.foreach((k, v) => builder.claim(k, v): Unit)
    sign(builder.build(), kid.getOrElse(current._1))

  /** A token with every claim right but no subject: a principal without one is not a principal. */
  def withoutSubject(audience: String = defaultAudience): String =
    val now = Instant.now()
    sign(
      new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .expirationTime(java.util.Date.from(now.plusSeconds(300)))
        .claim("typ", "Bearer")
        .build(),
      current._1
    )

  /** Claims signed with this issuer's key, whatever they say: how a forgery is built in a test. */
  def signed(claims: JWTClaimsSet): String = sign(claims, current._1)

  private def sign(claims: JWTClaimsSet, kid: String): String =
    val header =
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).`type`(JOSEObjectType.JWT).build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(current._2))
    jwt.serialize()

  /** `alg: none`, which must be refused whatever it says. */
  def unsigned(subject: String): String =
    new PlainJWT(baseClaims(subject)).serialize()

  /** HMAC with a secret the verifier was never given, which must be refused, not looked up. */
  def hmac(subject: String): String =
    val jwt = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(current._1).build(),
      baseClaims(subject)
    )
    jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"))
    jwt.serialize()

  private def baseClaims(subject: String) =
    val now = Instant.now()
    new JWTClaimsSet.Builder()
      .subject(subject)
      .issuer(issuer)
      .audience(defaultAudience)
      .expirationTime(java.util.Date.from(now.plusSeconds(300)))
      .claim("typ", "Bearer")
      .build()

  // ── An OpenID Connect code flow (feature 054) ────────────────────────────
  //
  // What a sign-in broker meets at a service's issuer: discovery, an authorization endpoint that
  // signs a configured person in without a page (or refuses one), and a token endpoint that hands
  // back an ID token for the code. Served on the same loopback server as the keys; the issuer's
  // own URL must be the server's for a client to discover it, which `TestIssuer.serving` arranges.

  /** Who `/authorize` signs in: the person a test is about to sign in as. */
  @volatile var signingIn: String = "ada"

  /** People `/authorize` refuses with `error=access_denied`. */
  val refusing = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** The client ids `/authorize` and `/token` accept, and the secret each must present, if any. */
  val registeredClients = java.util.concurrent.ConcurrentHashMap[String, Option[String]]()

  /** Signs ID tokens with a key that is not this issuer's: what a forgery looks like (R19.7). */
  @volatile var signIdTokensWithForeignKey: Boolean = false

  /** Every request the code flow was asked, in order: its path, then each parameter's name. */
  val asked = java.util.concurrent.ConcurrentLinkedQueue[(String, Set[String])]()

  /** The `nonce` and `state` each authorization carried, in order. */
  val authorizations = java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()

  private final case class Issued(
      clientId: String,
      redirectUri: String,
      nonce: String,
      person: String
  )
  private val codes = java.util.concurrent.ConcurrentHashMap[String, Issued]()

  def base: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def codeFlow(): Unit =
    server.createContext(
      "/.well-known/openid-configuration",
      exchange =>
        asked.add("discovery" -> Set.empty)
        respond(
          exchange,
          200,
          s"""{"issuer":"$issuer","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token",""" +
            s""""jwks_uri":"$jwksUrl","response_types_supported":["code"],"subject_types_supported":["public"],""" +
            """"id_token_signing_alg_values_supported":["RS256"]}"""
        )
    )
    server.createContext(
      "/authorize",
      exchange =>
        val params      = TestIssuer.form(Option(exchange.getRequestURI.getRawQuery).getOrElse(""))
        asked.add("authorize" -> params.keySet)
        val clientId    = params.getOrElse("client_id", "")
        val redirectUri = params.getOrElse("redirect_uri", "")
        val state       = params.getOrElse("state", "")
        val nonce       = params.getOrElse("nonce", "")
        authorizations.add(nonce -> state)
        if !registeredClients.containsKey(clientId) || redirectUri.isEmpty then
          respond(exchange, 400, """{"error":"unauthorized_client"}""")
        else if refusing.contains(signingIn) then
          redirect(exchange, s"$redirectUri?error=access_denied&state=${TestIssuer.encode(state)}")
        else
          val code = java.util.UUID.randomUUID().toString
          codes.put(code, Issued(clientId, redirectUri, nonce, signingIn))
          redirect(exchange, s"$redirectUri?code=$code&state=${TestIssuer.encode(state)}")
    )
    server.createContext(
      "/token",
      exchange =>
        val params   = TestIssuer.form(String(exchange.getRequestBody.readAllBytes(), "UTF-8"))
        asked.add("token" -> params.keySet)
        val clientId = params.getOrElse("client_id", "")
        val issued   = Option(codes.remove(params.getOrElse("code", "")))
        val secretOk = Option(registeredClients.get(clientId))
          .exists(_.forall(params.get("client_secret").contains))
        issued match
          case Some(found)
              if params
                .get("grant_type")
                .contains("authorization_code") && found.clientId == clientId &&
                params.get("redirect_uri").contains(found.redirectUri) && secretOk =>
            val now = Instant.now()
            val claims = new JWTClaimsSet.Builder()
              .subject(found.person)
              .issuer(issuer)
              .audience(clientId)
              .issueTime(java.util.Date.from(now))
              .expirationTime(java.util.Date.from(now.plusSeconds(300)))
              .claim("nonce", found.nonce)
              .claim("name", found.person.capitalize)
              .claim("email", s"${found.person}@example.test")
              .claim("email_verified", true)
              .build()
            val idToken =
              if signIdTokensWithForeignKey then TestIssuer.foreign.signed(claims)
              else signed(claims)
            respond(
              exchange,
              200,
              s"""{"id_token":"$idToken","access_token":"opaque","token_type":"Bearer","expires_in":300}"""
            )
          case _ => respond(exchange, 400, """{"error":"invalid_grant"}""")
    ): Unit

  private def respond(
      exchange: com.sun.net.httpserver.HttpExchange,
      status: Int,
      body: String
  ): Unit =
    val bytes = body.getBytes("UTF-8")
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def redirect(exchange: com.sun.net.httpserver.HttpExchange, location: String): Unit =
    exchange.getResponseHeaders.add("Location", location)
    exchange.sendResponseHeaders(303, -1)
    exchange.close()

object TestIssuer:

  /**
   * An issuer whose URL is its own loopback server's, so a client can discover it as it would a
   * real one: a sign-in broker reads `<issuer>/.well-known/openid-configuration`.
   */
  def serving(name: String, defaultAudience: String): TestIssuer =
    val reserved = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    val port     = reserved.getLocalPort
    reserved.close()
    new TestIssuer(s"http://127.0.0.1:$port", name, defaultAudience, port)

  /** An issuer nobody lists, whose key signs forgeries. */
  private lazy val foreign: TestIssuer = new TestIssuer("https://foreign.example.test", "foreign")

  def encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

  /** `a=1&b=2`, percent-decoded. */
  def form(text: String): Map[String, String] =
    text
      .split('&')
      .filter(_.nonEmpty)
      .map { pair =>
        val (k, v) = pair.span(_ != '=')
        java.net.URLDecoder.decode(k, "UTF-8") -> java.net.URLDecoder.decode(v.drop(1), "UTF-8")
      }
      .toMap
