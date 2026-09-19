package com.thinkmorestupidless.ankka.controlplane.deploy

import com.typesafe.config.Config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Everything the control plane needs to know about its deployment target. */
final case class DeployConfig(
    namespacePrefix: String,
    sweepInterval: FiniteDuration,
    retryMinBackoff: FiniteDuration,
    retryMaxBackoff: FiniteDuration,
    progressDeadline: FiniteDuration,
    failFastOnUnreachableCluster: Boolean,
    /**
     * Where exposed services live: `<service>-<project>.<base domain>`. `None` means nothing can be
     * exposed. The operator holds the same value and derives the same hostname (feature 005).
     */
    baseDomain: Option[String] = None,
    /** The port clients reach HTTPS on; omitted from URLs when it is the default 443. */
    httpsPort: Int = 443,
    /**
     * This platform's version, against which a descriptor's declared `runtime` is checked
     * (`Compatibility`). From the build; overridable so a test can be a platform of any version.
     */
    platformVersion: String = com.thinkmorestupidless.ankka.core.BuildInfo.version
):
  def namespaceFor(projectId: String): String = s"$namespacePrefix-$projectId"

  /** An exposed service's URL, usable verbatim, if a base domain is configured. */
  def hostnameFor(projectId: String, serviceName: String): Option[String] =
    baseDomain.map { base =>
      val port = if httpsPort == 443 then "" else s":$httpsPort"
      s"https://${com.thinkmorestupidless.ankka.crd.Hostnames.of(serviceName, projectId, base)}$port"
    }

  /** Bounded, doubling. A permanently failing service costs a few attempts a minute. */
  def backoffFor(attempts: Int): FiniteDuration =
    val factor  = 1L << math.min(math.max(attempts, 0), 10)
    val doubled = retryMinBackoff * factor
    if doubled > retryMaxBackoff then retryMaxBackoff else doubled

object DeployConfig:

  val default: DeployConfig = DeployConfig(
    namespacePrefix = "ankka",
    sweepInterval = 30.seconds,
    retryMinBackoff = 5.seconds,
    retryMaxBackoff = 120.seconds,
    progressDeadline = 600.seconds,
    failFastOnUnreachableCluster = false
  )

  def from(config: Config): DeployConfig =
    val section = config.getConfig("ankka.controlplane.kubernetes")
    DeployConfig(
      namespacePrefix = section.getString("namespace-prefix"),
      sweepInterval = section.getDuration("sweep-interval").toMillis.millis,
      retryMinBackoff = section.getDuration("retry-min-backoff").toMillis.millis,
      retryMaxBackoff = section.getDuration("retry-max-backoff").toMillis.millis,
      progressDeadline = section.getDuration("progress-deadline").toMillis.millis,
      failFastOnUnreachableCluster = section.getBoolean("fail-fast-on-unreachable-cluster"),
      baseDomain = Option(section.getString("base-domain")).map(_.trim).filter(_.nonEmpty),
      httpsPort = section.getInt("https-port")
    )
