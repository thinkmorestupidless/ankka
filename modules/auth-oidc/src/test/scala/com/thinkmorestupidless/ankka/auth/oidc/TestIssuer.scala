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
    val defaultAudience: String = "test"
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

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
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
