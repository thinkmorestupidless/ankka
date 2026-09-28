package com.thinkmorestupidless.ankka.controlplane.auth

import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{BadJOSEException, JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.{JOSEException, JWSAlgorithm, KeySourceException}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}

import java.net.URL
import java.text.ParseException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/** What verifying a presented token established. Never an exception: the caller maps each. */
enum Verification:
  case Verified(claims: JWTClaimsSet)

  /** The token is bad. The reason is safe to show the caller; it never echoes the token. */
  case Rejected(reason: String)

  /** Nothing can be verified right now — the keys could not be fetched and none are cached. */
  case Unavailable(reason: String)

/**
 * Verifies OpenID Connect access tokens offline against the issuer's published keys.
 *
 * Signature (asymmetric algorithms only — `none` and HMAC are refused by construction, since the
 * key selector is never given a shared secret), issuer, audience, expiry and not-before with a
 * skew, `sub` present, and Keycloak's `typ: Bearer` claim so a refresh or ID token presented as an
 * access token is refused. Keys are cached and refreshed on a schedule and once on an unknown key
 * id; after the first fetch the request path makes no network call (research R4, SC-005).
 */
final class TokenVerifier(config: AuthConfig, keys: JWKSource[SecurityContext]):

  private val processor =
    val p = new DefaultJWTProcessor[SecurityContext]()
    p.setJWSKeySelector(new JWSVerificationKeySelector(TokenVerifier.Asymmetric.asJava, keys))
    val claims = new DefaultJWTClaimsVerifier[SecurityContext](
      Set(config.audience).asJava,
      new JWTClaimsSet.Builder().issuer(config.issuer).build(),
      Set("sub", "exp").asJava,
      null
    )
    claims.setMaxClockSkew(config.clockSkew.toSeconds.toInt)
    p.setJWTClaimsSetVerifier(claims)
    p

  def verify(token: String): Verification =
    if token.count(_ == '.') != 2 then
      Verification.Rejected("shared tokens are no longer accepted; run 'ankka login'")
    else
      try
        val claims = processor.process(token, null)
        Option(claims.getStringClaim("typ")) match
          case Some("Bearer") => Verification.Verified(claims)
          case Some(other) => Verification.Rejected(s"token type '$other' is not an access token")
          case None        => Verification.Rejected("token carries no type")
      catch
        case failure: KeySourceException =>
          Verification.Unavailable(s"could not read the issuer's keys: ${failure.getMessage}")
        case failure: BadJOSEException => Verification.Rejected(reason(failure))
        case failure: ParseException   => Verification.Rejected("not a well-formed token")
        case failure: JOSEException    => Verification.Rejected(reason(failure))

  private def reason(failure: Exception): String =
    Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)

object TokenVerifier:

  /**
   * RSA and ECDSA only. A verifier that also accepted HMAC could be given a token it signed itself.
   */
  val Asymmetric: Set[JWSAlgorithm] = Set(
    JWSAlgorithm.RS256,
    JWSAlgorithm.RS384,
    JWSAlgorithm.RS512,
    JWSAlgorithm.PS256,
    JWSAlgorithm.PS384,
    JWSAlgorithm.PS512,
    JWSAlgorithm.ES256,
    JWSAlgorithm.ES384,
    JWSAlgorithm.ES512
  )

  /** A verifier over the configured JWKS URL, with production cache settings. */
  def remote(config: AuthConfig): TokenVerifier =
    new TokenVerifier(
      config,
      keySource(config.jwksUrl, minTimeBetweenFetches = 30.seconds, trusting = config.jwksCa)
    )

  /**
   * How keys are fetched: over TLS verified against `trusting` alone when it names a root — the
   * installation's service authority, which issued the identity provider's in-cluster certificate —
   * and the JVM's trust store otherwise (feature 014). The timeouts and size limit are nimbus's own
   * defaults, stated because this constructor requires them.
   */
  private def retriever(trusting: Option[String]): com.nimbusds.jose.util.ResourceRetriever =
    val socketFactory = trusting.map { path =>
      val certificates = java.security.cert.CertificateFactory
        .getInstance("X.509")
        .generateCertificates(java.nio.file.Files.newInputStream(java.nio.file.Paths.get(path)))
      val store = java.security.KeyStore.getInstance("PKCS12")
      store.load(null, null)
      var i = 0
      certificates.forEach { c =>
        store.setCertificateEntry(s"ca-$i", c)
        i += 1
      }
      val trust = javax.net.ssl.TrustManagerFactory
        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
      trust.init(store)
      val context = javax.net.ssl.SSLContext.getInstance("TLS")
      context.init(null, trust.getTrustManagers, null)
      context.getSocketFactory
    }
    new com.nimbusds.jose.util.DefaultResourceRetriever(
      com.nimbusds.jose.jwk.source.JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT,
      com.nimbusds.jose.jwk.source.JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT,
      com.nimbusds.jose.jwk.source.JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT,
      true,
      socketFactory.orNull
    )

  /**
   * A cached, rate-limited, outage-tolerant remote key set.
   *
   * Cached keys are served for their TTL; an unknown key id triggers one refetch, no more often
   * than `minTimeBetweenFetches`; and if the issuer becomes unreachable, the last good set is kept
   * for an hour rather than refusing every caller the moment Keycloak restarts.
   */
  def keySource(
      jwksUrl: String,
      minTimeBetweenFetches: FiniteDuration,
      trusting: Option[String] = None
  ): JWKSource[SecurityContext] =
    JWKSourceBuilder
      .create[SecurityContext](new URL(jwksUrl), retriever(trusting))
      .cache(5.minutes.toMillis, 15.seconds.toMillis)
      .rateLimited(minTimeBetweenFetches.toMillis)
      .outageTolerant(1.hour.toMillis)
      .retrying(true)
      .build()
