package com.thinkmorestupidless.ankka.auth.oidc

import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.thinkmorestupidless.ankka.http.Principal

import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * An OpenID Connect ID token, verified as a relying party verifies one (feature 054): what a
 * sign-in broker is handed by a service's issuer after a person signs in there.
 *
 * Everything `OidcVerifier` checks of an access token — signature by the issuer's keys (asymmetric
 * only), issuer, expiry, a subject — with the audience the broker's own client id, no `typ` (an ID
 * token is not a bearer), and the `nonce` the broker sent. The principal is `Principals.from`'s,
 * without the claims that only describe the token itself.
 */
final class IdTokenVerifier(issuer: Issuer, clientId: String, keys: JWKSource[SecurityContext]):

  private val verifier =
    new OidcVerifier(OidcConfig(Vector(issuer.copy(audience = clientId, typ = None))), _ => keys)

  /** The person the token names, or why it names nobody. */
  def verify(token: String, nonce: String): Either[String, Principal] =
    verifier.verify(token) match
      case Verification.Verified(claims, verified) =>
        Try(Option(claims.getStringClaim("nonce"))).toOption.flatten match
          case Some(found) if found == nonce =>
            val principal = Principals.from(claims, verified)
            Right(principal.copy(claims = principal.claims -- IdTokenVerifier.TokenClaims))
          case Some(_) => Left("the ID token's nonce is not the one sent")
          case None    => Left("the ID token carries no nonce")
      case Verification.Rejected(reason)    => Left(reason)
      case Verification.Unavailable(reason) => Left(reason)

object IdTokenVerifier:

  /** Claims that say what a token is rather than who the person is: never carried further. */
  val TokenClaims: Set[String] = Set(
    "iss",
    "aud",
    "exp",
    "iat",
    "nbf",
    "jti",
    "nonce",
    "azp",
    "auth_time",
    "at_hash",
    "c_hash",
    "sid",
    "typ",
    "session_state",
    "acr",
    "amr"
  )

  /** Over the issuer's published keys, cached and rate limited as a service's tokens are. */
  def remote(issuer: Issuer, clientId: String): IdTokenVerifier =
    new IdTokenVerifier(
      issuer,
      clientId,
      OidcVerifier.keySource(issuer, minTimeBetweenFetches = 30.seconds)
    )
