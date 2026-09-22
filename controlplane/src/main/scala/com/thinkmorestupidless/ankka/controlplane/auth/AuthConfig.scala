package com.thinkmorestupidless.ankka.controlplane.auth

import com.typesafe.config.Config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * Where tokens come from and what they must say — `ankka.controlplane.auth` in `reference.conf`.
 *
 * Two URLs, deliberately: `issuer` is what a token's `iss` must equal (the external, TLS,
 * gateway-routed address, `https://auth.<base>/realms/ankka`) and `jwksUrl` is where this process
 * reads the realm's keys (inside a cluster, the plain service address, with no certificate to
 * trust). Locally they are the same and `jwksUrl` is derived.
 */
final case class AuthConfig(
    issuer: String,
    jwksUrl: String,
    audience: String,
    clientId: String,
    realmHint: String,
    clockSkew: FiniteDuration
)

object AuthConfig:

  /**
   * Refuses an unset issuer: a control plane that verifies nothing must not come up.
   *
   * In a cluster the issuer is *derived* — `https://auth.<base domain>[:port]/realms/ankka` from
   * the base domain and HTTPS port the control plane already carries for exposed services — so the
   * overlay writes the base domain once and this needs no replacement of its own. An explicit
   * `ANKKA_AUTH_ISSUER` wins, which is how `sbt controlPlane/run` points at the compose Keycloak.
   */
  def from(config: Config): AuthConfig =
    val section  = config.getConfig("ankka.controlplane.auth")
    val explicit = section.getString("issuer").trim.stripSuffix("/")
    val issuer =
      if explicit.nonEmpty then explicit
      else
        val k8s        = config.getConfig("ankka.controlplane.kubernetes")
        val baseDomain = k8s.getString("base-domain").trim
        val port       = k8s.getInt("https-port")
        if baseDomain.isEmpty then
          throw IllegalStateException(
            "ankka.controlplane.auth.issuer is not set and no base domain is configured to derive " +
              "it from; set ANKKA_AUTH_ISSUER to the realm's issuer URL " +
              "(https://auth.<base domain>[:port]/realms/ankka) or pass an Acl explicitly"
          )
        derivedIssuer(baseDomain, port)
    val jwks = Option(section.getString("jwks-url")).map(_.trim).filter(_.nonEmpty)
    AuthConfig(
      issuer = issuer,
      jwksUrl = jwks.getOrElse(jwksFor(issuer)),
      audience = section.getString("audience"),
      clientId = section.getString("client-id"),
      realmHint = section.getString("realm-hint"),
      clockSkew = section.getDuration("clock-skew").toMillis.millis
    )

  /** The issuer of the platform's realm, as tokens obtained through the gateway name it. */
  def derivedIssuer(baseDomain: String, httpsPort: Int): String =
    val port = if httpsPort == 443 then "" else s":$httpsPort"
    s"https://auth.$baseDomain$port/realms/ankka"

  /** Keycloak's key endpoint, relative to a realm issuer. */
  def jwksFor(issuer: String): String = s"${issuer.stripSuffix("/")}/protocol/openid-connect/certs"
