package com.thinkmorestupidless.ankka.controlplane.auth

import com.typesafe.config.Config

import java.time.{Clock, Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/**
 * The installation's settings for machines (feature 040): the issuer their tokens name, which is
 * also where the token route is; how many tokens one client id may ask for a minute; the most bytes
 * a second a machine may be allowed on the broker; and the broker's address outside the cluster,
 * when it is exposed.
 */
final case class MachineSettings(
    issuer: String,
    tokenRate: Int = MachineSettings.DefaultTokenRate,
    byteRateCeiling: Long = MachineSettings.DefaultByteRateCeiling,
    brokerBootstrap: Option[String] = None,
    keysDirectory: Option[String] = None,
    keysSecret: String = "ankka-controlplane-machine-keys",
    namespace: String = "ankka-controlplane"
):
  def tokenUrl: String = s"${issuer.stripSuffix("/")}/oauth/token"
  def jwksUrl: String  = s"${issuer.stripSuffix("/")}/.well-known/jwks.json"

object MachineSettings:
  val DefaultTokenRate: Int        = 12
  val DefaultByteRateCeiling: Long = 32L * 1024 * 1024

  /** Lifetime of a machine token. */
  val TokenLifetime: Duration = Duration.ofMinutes(15)

  /** A control plane with no base domain and no issuer set: its own loopback address. */
  val local: MachineSettings = MachineSettings("http://localhost:9000")

  /** `https://api.<base>[:port]`: the control plane's own public address. */
  def derivedIssuer(baseDomain: String, httpsPort: Int): String =
    val port = if httpsPort == 443 then "" else s":$httpsPort"
    s"https://api.$baseDomain$port"

  def from(config: Config): MachineSettings =
    def setting(path: String) =
      if config.hasPath(path) then config.getString(path).trim else ""
    val base = setting("ankka.controlplane.deploy.base-domain")
    val port =
      if config.hasPath("ankka.controlplane.deploy.https-port") then
        config.getInt("ankka.controlplane.deploy.https-port")
      else 443
    val stated = setting("ankka.controlplane.machines.issuer")
    val issuer =
      if stated.nonEmpty then stated
      else if base.nonEmpty then derivedIssuer(base, port)
      else local.issuer
    MachineSettings(
      issuer = issuer,
      tokenRate =
        if config.hasPath("ankka.controlplane.machines.token-rate") then
          config.getInt("ankka.controlplane.machines.token-rate")
        else DefaultTokenRate,
      byteRateCeiling =
        if config.hasPath("ankka.controlplane.machines.byte-rate-ceiling") then
          config.getLong("ankka.controlplane.machines.byte-rate-ceiling")
        else DefaultByteRateCeiling,
      brokerBootstrap =
        Option(setting("ankka.controlplane.machines.broker-bootstrap")).filter(_.nonEmpty),
      keysDirectory =
        Option(setting("ankka.controlplane.machines.keys-directory")).filter(_.nonEmpty),
      keysSecret = Option(setting("ankka.controlplane.machines.keys-secret"))
        .filter(_.nonEmpty)
        .getOrElse("ankka-controlplane-machine-keys"),
      namespace = Option(setting("ankka.controlplane.machines.namespace"))
        .filter(_.nonEmpty)
        .getOrElse("ankka-controlplane")
    )

/**
 * A bucket per client id (feature 040): `rate` requests a minute, refilled continuously, so a burst
 * of `rate` is allowed and the next waits. Unknown client ids are counted too, so guessing is
 * slowed. Per node: an installation of several control plane instances allows each its own minute.
 */
final class TokenBucket(rate: Int, clock: Clock = Clock.systemUTC()):
  private final case class Bucket(tokens: Double, at: Instant)
  private val buckets   = ConcurrentHashMap[String, Bucket]()
  private val perSecond = rate.toDouble / 60.0

  /** `None` when `key` may go ahead now, else how many seconds until it may. */
  def take(key: String): Option[Long] =
    val now     = clock.instant()
    var refused = Option.empty[Long]
    buckets.compute(
      key,
      (_, held) =>
        val start   = Option(held).getOrElse(Bucket(rate.toDouble, now))
        val elapsed = Duration.between(start.at, now).toMillis.max(0L) / 1000.0
        val filled  = (start.tokens + elapsed * perSecond).min(rate.toDouble)
        if filled >= 1.0 then
          refused = None
          Bucket(filled - 1.0, now)
        else
          refused = Some(math.ceil((1.0 - filled) / perSecond).toLong.max(1L))
          Bucket(filled, now)
    ): Unit
    refused
