package com.thinkmorestupidless.ankka.auth.oidc

import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{BadJOSEException, JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.{JOSEException, JWSAlgorithm, KeySourceException}
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import java.net.URI
import java.nio.file.{Files, Path}
import java.text.ParseException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/** What verifying a presented token established. Never an exception: the caller maps each. */
enum Verification:
  case Verified(claims: JWTClaimsSet, issuer: Issuer)

  /** The token is bad. The reason is safe to show the caller; it never echoes the token. */
  case Rejected(reason: String)

  /** Nothing can be verified right now: the keys could not be fetched and none are held. */
  case Unavailable(reason: String)

/**
 * Verifies OpenID Connect access tokens offline against each listed issuer's published keys.
 *
 * The token's `iss` chooses the issuer before anything is verified. That is safe: it chooses whose
 * keys check the signature, so a token naming one issuer and signed by another fails, and an issuer
 * the service does not list is refused with no key fetched at all. Then signature (asymmetric
 * algorithms only, so `none` and HMAC are refused by construction: the key selector is never given
 * a shared secret), issuer, audience, expiry and not-before with the issuer's skew, a present
 * `sub`, and the issuer's `typ` claim when it asked for one.
 *
 * Nothing is fetched when this is built. The first verification for an issuer fetches its keys;
 * after that, the request path makes no network call until a key id is unknown or the cache ages.
 */
final class OidcVerifier(config: OidcConfig, keys: Issuer => JWKSource[SecurityContext]):

  private val processors: Map[String, (Issuer, DefaultJWTProcessor[SecurityContext])] =
    config.issuers.map(i => i.issuer -> (i, processor(i))).toMap

  private def processor(issuer: Issuer): DefaultJWTProcessor[SecurityContext] =
    val p = new DefaultJWTProcessor[SecurityContext]()
    p.setJWSKeySelector(
      new JWSVerificationKeySelector(OidcVerifier.Asymmetric.asJava, keys(issuer))
    )
    val claims = new DefaultJWTClaimsVerifier[SecurityContext](
      Set(issuer.audience).asJava,
      new JWTClaimsSet.Builder().issuer(issuer.issuer).build(),
      Set("sub", "exp").asJava,
      null
    )
    claims.setMaxClockSkew(issuer.clockSkew.toSeconds.toInt)
    p.setJWTClaimsSetVerifier(claims)
    p

  def verify(token: String): Verification =
    if token.count(_ == '.') != 2 then Verification.Rejected("not a well-formed token")
    else
      try
        val named = Option(SignedJWT.parse(token).getJWTClaimsSet.getIssuer).map(_.stripSuffix("/"))
        named.flatMap(processors.get) match
          case None => Verification.Rejected("issuer not accepted")
          case Some((issuer, processor)) =>
            val claims = processor.process(token, null)
            issuer.typ match
              case None => Verification.Verified(claims, issuer)
              case Some(required) =>
                Option(claims.getStringClaim("typ")) match
                  case Some(found) if found == required => Verification.Verified(claims, issuer)
                  case Some(other) => Verification.Rejected(s"token type '$other' is not accepted")
                  case None        => Verification.Rejected("token carries no type")
      catch
        case failure: KeySourceException =>
          Verification.Unavailable(s"could not read the issuer's keys: ${failure.getMessage}")
        case failure: BadJOSEException => Verification.Rejected(reason(failure))
        case _: ParseException         => Verification.Rejected("not a well-formed token")
        case failure: JOSEException    => Verification.Rejected(reason(failure))

  private def reason(failure: Exception): String =
    Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)

object OidcVerifier:

  /** RSA and ECDSA only. A verifier that also accepted HMAC could be given a token it signed. */
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

  /** How long the last good keys are kept while an issuer cannot be reached. */
  val OutageTolerance: FiniteDuration = 1.hour

  /** A verifier over every issuer's keys URL, with production cache settings. */
  def remote(config: OidcConfig): OidcVerifier =
    new OidcVerifier(config, issuer => keySource(issuer, minTimeBetweenFetches = 30.seconds))

  /**
   * A cached, rate-limited, outage-tolerant remote key set.
   *
   * Cached keys are served for their TTL; an unknown key id triggers one refetch, no more often
   * than `minTimeBetweenFetches`; and if the issuer becomes unreachable, the last good set is kept
   * for `outageTolerance` rather than refusing every caller the moment the issuer restarts.
   */
  def keySource(
      issuer: Issuer,
      minTimeBetweenFetches: FiniteDuration,
      outageTolerance: FiniteDuration = OutageTolerance
  ): JWKSource[SecurityContext] =
    JWKSourceBuilder
      .create[SecurityContext](URI.create(issuer.jwksUrl).toURL, retriever(issuer.ca))
      .cache(5.minutes.toMillis, 15.seconds.toMillis)
      .rateLimited(minTimeBetweenFetches.toMillis)
      .outageTolerant(outageTolerance.toMillis)
      .retrying(true)
      .build()

  /**
   * How keys are fetched: over TLS verified against `trusting` alone when it names a bundle (an
   * installation's service authority, say), and the JVM's own trust store otherwise. The timeouts
   * and size limit are nimbus's defaults, stated because this constructor requires them.
   */
  private def retriever(trusting: Option[Path]): com.nimbusds.jose.util.ResourceRetriever =
    val socketFactory = trusting.map { path =>
      val certificates = java.security.cert.CertificateFactory
        .getInstance("X.509")
        .generateCertificates(Files.newInputStream(path))
      val store = java.security.KeyStore.getInstance("PKCS12")
      store.load(null, null)
      certificates.asScala.zipWithIndex.foreach((c, i) => store.setCertificateEntry(s"ca-$i", c))
      val trust = javax.net.ssl.TrustManagerFactory
        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
      trust.init(store)
      val context = javax.net.ssl.SSLContext.getInstance("TLS")
      context.init(null, trust.getTrustManagers, null)
      context.getSocketFactory
    }
    new com.nimbusds.jose.util.DefaultResourceRetriever(
      JWKSourceBuilder.DEFAULT_HTTP_CONNECT_TIMEOUT,
      JWKSourceBuilder.DEFAULT_HTTP_READ_TIMEOUT,
      JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT,
      true,
      socketFactory.orNull
    )
