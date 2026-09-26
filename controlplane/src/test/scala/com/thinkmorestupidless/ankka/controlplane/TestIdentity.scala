package com.thinkmorestupidless.ankka.controlplane

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.controlplane.auth.{AuthConfig, TokenVerifier}
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl

import java.net.InetSocketAddress
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * An in-process issuer: a signing key, a JWKS served on loopback, and tokens minted to order.
 *
 * What the HTTP and authorization suites run against instead of Keycloak — deterministic, instant,
 * and able to do things a real issuer will not (rotate keys on demand, go away, sign with the wrong
 * algorithm). `KeycloakRealmSuite` is the one place real tokens are read; everything the verifier
 * must refuse is exercised here.
 */
/**
 * A clock a test moves by hand.
 *
 * Deadlines the control plane enforces — a deploy token's expiry — are days away, and a suite
 * cannot wait for them or fake them by constructing the endpoints differently from the way they
 * ship. `ControlPlane.endpoints` takes one clock and gives it to every endpoint, so advancing this
 * moves the whole control plane's idea of now.
 */
final class MutableClock(
    start: java.time.Instant = java.time.Instant.now(),
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC
) extends java.time.Clock:

  @volatile private var current: java.time.Instant = start

  def getZone: java.time.ZoneId    = zone
  def instant(): java.time.Instant = current

  override def withZone(other: java.time.ZoneId): MutableClock = new MutableClock(current, other)

  def advance(by: java.time.Duration): Unit = current = current.plus(by)
  def advanceDays(days: Long): Unit         = advance(java.time.Duration.ofDays(days))
  def set(to: java.time.Instant): Unit      = current = to

final class TestIdentity(val issuer: String = "https://auth.example.test/realms/ankka"):

  /** The control plane's clock for this suite; hand it to `ControlPlane.endpoints`. */
  val clock: MutableClock = new MutableClock()

  private val generator = KeyPairGenerator.getInstance("RSA")
  generator.initialize(2048)

  @volatile private var current: (String, RSAPrivateKey, RSAPublicKey) = generate("k1")
  @volatile private var offline: Boolean                               = false
  val fetches                                                          = new AtomicInteger(0)

  private def generate(kid: String) =
    val pair = generator.generateKeyPair()
    (kid, pair.getPrivate.asInstanceOf[RSAPrivateKey], pair.getPublic.asInstanceOf[RSAPublicKey])

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/jwks",
    exchange =>
      fetches.incrementAndGet()
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

  def rotate(kid: String): Unit        = current = generate(kid)
  def setOffline(value: Boolean): Unit = offline = value
  def stop(): Unit                     = server.stop(0)

  def config(
      audience: String = "ankka-controlplane",
      skew: FiniteDuration = 60.seconds
  ): AuthConfig =
    AuthConfig(issuer, jwksUrl, audience, "ankka-cli", "ankka", skew)

  /** A verifier that refetches keys quickly enough for a test to see a rotation. */
  def verifier(config: AuthConfig = this.config()): TokenVerifier =
    new TokenVerifier(config, TokenVerifier.keySource(jwksUrl, minTimeBetweenFetches = 200.millis))

  def acl(): Acl =
    val c = config()
    ControlPlaneAcl.oidc(verifier(c), c)

  /** A token as Keycloak would mint it for the control plane's audience. */
  def token(
      subject: String,
      email: Option[String] = None,
      emailVerified: Boolean = true,
      roles: Set[String] = Set.empty,
      name: Option[String] = None,
      audience: Seq[String] = Seq("ankka-controlplane"),
      issuer: String = this.issuer,
      expiresIn: FiniteDuration = 5.minutes,
      notBefore: Option[Instant] = None,
      typ: Option[String] = Some("Bearer"),
      kid: Option[String] = None
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
    sign(builder.build(), kid.getOrElse(current._1))

  private def sign(claims: JWTClaimsSet, kid: String): String =
    val header =
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).`type`(JOSEObjectType.JWT).build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(current._2))
    jwt.serialize()

  /** `alg: none` — must be refused whatever it says. */
  def unsigned(subject: String): String =
    new PlainJWT(baseClaims(subject)).serialize()

  /** HMAC with a secret the verifier was never given — must be refused, not looked up. */
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
      .audience("ankka-controlplane")
      .expirationTime(java.util.Date.from(now.plusSeconds(300)))
      .claim("typ", "Bearer")
      .build()
