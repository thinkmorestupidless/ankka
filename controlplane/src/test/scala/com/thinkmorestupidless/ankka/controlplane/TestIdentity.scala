package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.auth.oidc.{OidcVerifier, TestIssuer}
import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.AuthConfig
import com.thinkmorestupidless.ankka.http.Acl

import scala.concurrent.duration.{DurationInt, FiniteDuration}

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

/**
 * The installation's identity provider, in process: the shared test issuer (`ankka-auth-oidc`'s, so
 * there is one test issuer as there is one verifier) with the control plane's audience and
 * configuration, and the control plane's clock.
 *
 * What the HTTP and authorization suites run against instead of Keycloak. `KeycloakRealmSuite` is
 * the one place real tokens are read; everything the verifier must refuse is exercised in the
 * module's own suites.
 */
final class TestIdentity(issuer: String = "https://auth.example.test/realms/ankka")
    extends TestIssuer(issuer, name = "ankka", defaultAudience = "ankka-controlplane"):

  /** The control plane's clock for this suite; hand it to `ControlPlane.endpoints`. */
  val clock: MutableClock = new MutableClock()

  def config(
      audience: String = "ankka-controlplane",
      skew: FiniteDuration = 60.seconds
  ): AuthConfig =
    AuthConfig(issuer, jwksUrl, audience, "ankka-cli", "ankka", skew)

  /** The control plane's verifier, refetching keys quickly enough for a test to see a rotation. */
  def verifier(config: AuthConfig = this.config()): OidcVerifier =
    new OidcVerifier(
      config.toOidc,
      i => OidcVerifier.keySource(i, minTimeBetweenFetches = 200.millis)
    )

  def acl(): Acl =
    val c = config()
    ControlPlaneAcl.oidc(verifier(c), c)
