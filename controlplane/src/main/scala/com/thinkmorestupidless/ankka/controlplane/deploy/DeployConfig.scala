package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.{CustomHostnames, DnsRecord}
import com.typesafe.config.Config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** The store an installation makes new buckets in (feature 039). */
enum ObjectStoreKind:
  case Garage, Gcs

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
    platformVersion: String = com.thinkmorestupidless.ankka.core.BuildInfo.version,
    /** The store new buckets are made in (feature 039). Garage unless the installation says. */
    objectStore: ObjectStoreKind = ObjectStoreKind.Garage,
    /** Google Cloud Storage's bucket name prefix, when that is the store. */
    objectStorePrefix: Option[String] = None,
    /** How long a new bucket in Google Cloud Storage keeps a deleted object, 7 to 90 days. */
    softDeleteDays: Int = 7,
    /** The installation's cloud provider (feature 044); `None` when it names none. */
    cloudProvider: Option[String] = None,
    /**
     * The ClusterIssuer that obtains a custom hostname's certificate (feature 045,
     * `ANKKA_HOSTNAME_ISSUER`). The operator holds the same name and asks it; here it only decides
     * whether a hostname can be added at all.
     */
    hostnameIssuer: Option[String] = None,
    /**
     * `host:port` of the resolver the proof lookup asks (`ANKKA_DNS_RESOLVER`); else the system's.
     */
    dnsResolver: Option[String] = None,
    /**
     * The address an apex custom hostname's record points at (`ANKKA_GATEWAY_ADDRESS`): the
     * gateway's load balancer, which the installation publishes because nothing inside the cluster
     * can learn it reliably. Absent, an apex has no record to create, and says why.
     */
    gatewayAddress: Option[String] = None
):
  def namespaceFor(projectId: String): String = s"$namespacePrefix-$projectId"

  /** An exposed service's URL, usable verbatim, if a base domain is configured. */
  def hostnameFor(projectId: String, serviceName: String): Option[String] =
    baseDomain.map { base =>
      val port = if httpsPort == 443 then "" else s":$httpsPort"
      s"https://${com.thinkmorestupidless.ankka.crd.Hostnames.of(serviceName, projectId, base)}$port"
    }

  /**
   * A bucket's address on the internet (feature 034), if a base domain is configured: the store's
   * one hostname, then the bucket. The operator derives the same one (`Buckets`).
   */
  def bucketAddressFor(projectId: String, serviceName: String): Option[String] =
    baseDomain.map(base =>
      com.thinkmorestupidless.ankka.crd.Buckets
        .publicAddress(projectId, serviceName, base, httpsPort)
    )

  /**
   * The proof record every custom hostname of a project needs (feature 045), written with
   * `<hostname>` literally: the same record, beside each name.
   */
  def proofRecord(projectId: String): DnsRecord =
    DnsRecord(
      com.thinkmorestupidless.ankka.controlplane.api.ProofLookup.recordName("<hostname>"),
      "TXT",
      com.thinkmorestupidless.ankka.controlplane.api.ProofLookup.recordValue(projectId)
    )

  /**
   * The record that points `hostname` at this installation, or why none can be said: a `CNAME` to
   * the service's derived hostname, except at an apex, where a `CNAME` cannot sit and an address
   * record to the gateway is needed, if the installation has published one. A port other than 443
   * is said too, since a record cannot carry one.
   */
  def recordFor(
      projectId: String,
      serviceName: String,
      hostname: String
  ): (Option[DnsRecord], Option[String]) =
    val port =
      Option.when(httpsPort != 443)(s"the installation answers on port $httpsPort")
    val record =
      if CustomHostnames.isApex(hostname) then
        gatewayAddress match
          case Some(address) => Right(DnsRecord(hostname, "A", address))
          case None =>
            Left("an apex cannot be a CNAME; this installation has published no address")
      else
        baseDomain match
          case Some(base) =>
            Right(
              DnsRecord(
                hostname,
                "CNAME",
                com.thinkmorestupidless.ankka.crd.Hostnames.of(serviceName, projectId, base)
              )
            )
          case None => Left("this installation has no base domain to point at")
    record match
      case Right(r)   => (Some(r), port)
      case Left(note) => (None, Some((note +: port.toVector).mkString("; ")))

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
    val store   = config.getConfig("ankka.controlplane.object-store")
    val cloudProvider =
      Option(config.getString("ankka.controlplane.cloud.provider"))
        .map(_.trim)
        .filter(p => p.nonEmpty && p != "none")
    // Empty is what a manifest renders when its overlay names no store: Garage, as before.
    val backend = store.getString("backend").trim match
      case "garage" | "" => ObjectStoreKind.Garage
      case "gcs"         => ObjectStoreKind.Gcs
      case other =>
        throw new IllegalArgumentException(
          s"ANKKA_OBJECT_STORE_BACKEND is '$other'; it must be garage or gcs"
        )
    val prefix = Option(store.getString("prefix")).map(_.trim).filter(_.nonEmpty)
    val days   = store.getInt("soft-delete-days")
    if days < 7 || days > 90 then
      throw new IllegalArgumentException(
        s"ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS is $days; it must be a number of days from 7 to 90"
      )
    if backend == ObjectStoreKind.Gcs then
      if cloudProvider.isEmpty then
        throw new IllegalArgumentException(
          "ANKKA_OBJECT_STORE_BACKEND is gcs, so ANKKA_CLOUD_PROVIDER must name a cloud provider"
        )
      if prefix.isEmpty then
        throw new IllegalArgumentException(
          "ANKKA_OBJECT_STORE_BACKEND is gcs, so ANKKA_OBJECT_STORE_PREFIX must be set"
        )
    DeployConfig(
      objectStore = backend,
      objectStorePrefix = prefix,
      softDeleteDays = days,
      cloudProvider = cloudProvider,
      namespacePrefix = section.getString("namespace-prefix"),
      sweepInterval = section.getDuration("sweep-interval").toMillis.millis,
      retryMinBackoff = section.getDuration("retry-min-backoff").toMillis.millis,
      retryMaxBackoff = section.getDuration("retry-max-backoff").toMillis.millis,
      progressDeadline = section.getDuration("progress-deadline").toMillis.millis,
      failFastOnUnreachableCluster = section.getBoolean("fail-fast-on-unreachable-cluster"),
      baseDomain = Option(section.getString("base-domain")).map(_.trim).filter(_.nonEmpty),
      httpsPort = section.getInt("https-port"),
      hostnameIssuer = optional(config, "ankka.controlplane.hostnames.issuer"),
      dnsResolver = optional(config, "ankka.controlplane.hostnames.resolver"),
      gatewayAddress = optional(config, "ankka.controlplane.hostnames.gateway-address")
    )

  private def optional(config: Config, path: String): Option[String] =
    Option.when(config.hasPath(path))(config.getString(path)).map(_.trim).filter(_.nonEmpty)
