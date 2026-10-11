package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The operator's whole configuration surface.
 *
 * Read from system properties first, then the environment, then a default. No HOCON, because the
 * operator carries no config library and should not gain one for thirteen values — and because a
 * file baked into a container image is the least useful place to configure a container.
 *
 * System properties come first for the reason the CLI's `Settings.path` does it: an environment
 * variable cannot be set in-process, so an env-only reader is untestable without spawning a
 * subprocess.
 */
final case class Settings(
    namespacePrefix: String,
    resyncInterval: FiniteDuration,
    retryMinBackoff: FiniteDuration,
    retryMaxBackoff: FiniteDuration,
    maxConcurrentReconciles: Int,
    /**
     * The PVC size for a project's shared Postgres capacity. One value for every project for now —
     * per-project sizing is out of scope for this feature.
     */
    databaseStorageSize: String,
    /**
     * The domain every exposed service's hostname sits under (feature 005). `None` means the
     * operator cannot render a route, and says so in the resource's status rather than silently
     * leaving an exposed service unrouted. Must match the control plane's.
     */
    baseDomain: Option[String] = None,
    /**
     * The runtime image: run beside a `hosting: process` service (feature 009), and as the one
     * container of a `hosting: wasm` service with its module loaded (feature 016). Not in the
     * resource, by design: a descriptor cannot name it. `ANKKA_SIDECAR_IMAGE` on the operator's own
     * Deployment, set by the manifests to the tag the same build produced; the operator has no
     * version of its own to derive one from.
     */
    sidecarImage: String = "ankka-sidecar:latest",
    /**
     * The platform's proxy, run beside a `hosting: web` service's process (feature 021). As the
     * sidecar image: not in the resource, set on the operator's Deployment to the tag the same
     * build produced.
     */
    proxyImage: String = "ankka-proxy:latest",
    /**
     * The port the gateway's HTTPS listener is reached on, so a web-hosted service's proxy can tell
     * the process the address a browser used (feature 021). The same `ankka-platform` value the
     * control plane reads; 443 when the overlay says nothing.
     */
    httpsPort: Int = 443,
    /**
     * The collector every workload that runs the platform's runtime exports its telemetry to
     * (feature 026): the installation's to say, once, on the `ankka-platform` ConfigMap. `None`
     * renders nothing at all, so a Deployment is what it was before the feature.
     */
    otlpEndpoint: Option[String] = None,
    /**
     * What is sent with the telemetry so the collector accepts it: a credential, from the
     * installation's `ankka-telemetry` Secret. Written into each service's own Secret, never onto a
     * Deployment, and never printed.
     */
    otlpHeaders: Option[Settings.Credential] = None,
    /**
     * The installation's broker, when it has one (feature 027). `None` means it has none: no
     * service is told of one, and a service that declares a topic reports that it cannot have it.
     */
    broker: Option[BrokerSettings] = None,
    /**
     * The installation's object store (feature 034). `None` when the installation has none: a
     * service that asks for a bucket is then reported as failed, with that reason, and nothing is
     * asked of anything. Set by the object store's component, not the operator's own manifest, so
     * an overlay without the component renders an operator without a store.
     */
    objectStore: Option[ObjectStoreSettings] = None,
    /**
     * The store new buckets are made in (feature 039). `Garage` whenever `objectStore` is set and
     * nothing says otherwise; `None` when the installation has no store.
     */
    objectStoreBackend: Option[ObjectStoreBackend] = None,
    /** Google Cloud Storage's settings, when the backend is `Gcs` (feature 039). */
    gcs: Option[GcsSettings] = None,
    /**
     * The installation's cloud provider and account (feature 044). `None` when it has none: no
     * cloud request is written, and everything is served by the installation itself.
     */
    cloud: Option[CloudSettings] = None,
    /** The image of the program a move runs as a Job (feature 039), as `sidecarImage` is. */
    storageMoverImage: String = "ankka-storage-mover:latest",
    /**
     * How long an old storage credential goes on working after a new one is in place: feature 044's
     * rotation grace (`ANKKA_CLOUD_ROTATION_GRACE`), an hour as shipped, which feature 039 applies
     * to Garage's keys too.
     */
    rotationGrace: FiniteDuration = 1.hour,
    /**
     * The installation's backups (feature 041): where they go, for how long, and how often. Set by
     * the `backups` component; without it nothing is archived and everything renders as before.
     */
    backups: BackupSettings = BackupSettings.none
):
  /**
   * The store new buckets are made in (research R1a D1): the backend the installation names, else
   * Garage when it is installed, else the cloud account when a provider is named. One rule, so a
   * `Settings` built in a test with `copy` answers as one read from the environment does.
   */
  def bucketBackend: Option[ObjectStoreBackend] =
    objectStoreBackend
      .orElse(objectStore.map(_ => ObjectStoreBackend.Garage))
      .orElse(cloud.map(_ => ObjectStoreBackend.Gcs))

  /**
   * Backoff for the nth consecutive failure, doubling to the ceiling.
   *
   * Bounded so a permanently failing resource costs a handful of attempts a minute rather than
   * saturating the API server — the same shape `TimerStore.reschedule` already uses.
   */
  def backoffFor(attempts: Int): FiniteDuration =
    // Shift rather than pow: multiplying a FiniteDuration by a Double widens it to Duration,
    // and casting that back is how an Infinite sneaks into a scheduler.
    val factor  = 1L << math.min(math.max(attempts, 0), 10)
    val doubled = retryMinBackoff * factor
    if doubled > retryMaxBackoff then retryMaxBackoff else doubled

object Settings:

  /** A value that is a credential: kept, passed on, and never printed by `toString`. */
  final case class Credential(value: String):
    override def toString: String = "Credential(****)"

  val default: Settings = Settings(
    namespacePrefix = "ankka",
    resyncInterval = 5.minutes,
    retryMinBackoff = 2.seconds,
    retryMaxBackoff = 5.minutes,
    maxConcurrentReconciles = 16,
    databaseStorageSize = "1Gi",
    sidecarImage = "ankka-sidecar:latest",
    proxyImage = "ankka-proxy:latest",
    httpsPort = 443
  )

  /**
   * `-Dankka.operator.namespace-prefix`, then `ANKKA_K8S_NAMESPACE_PREFIX`, then the default.
   *
   * The prefix **must** match the control plane's. A mismatch is the most likely misconfiguration
   * of a two-process design, and it presents as every service reporting that no operator has
   * reported on it — which is exactly why that signal exists.
   */
  def fromEnvironment(): Settings =
    val store = objectStore()
    Settings(
      namespacePrefix = string(
        "ankka.operator.namespace-prefix",
        "ANKKA_K8S_NAMESPACE_PREFIX",
        default.namespacePrefix
      ),
      resyncInterval = seconds(
        "ankka.operator.resync-seconds",
        "ANKKA_OPERATOR_RESYNC_SECONDS",
        default.resyncInterval
      ),
      retryMinBackoff = seconds(
        "ankka.operator.retry-min-backoff-seconds",
        "ANKKA_OPERATOR_RETRY_MIN_BACKOFF_SECONDS",
        default.retryMinBackoff
      ),
      retryMaxBackoff = seconds(
        "ankka.operator.retry-max-backoff-seconds",
        "ANKKA_OPERATOR_RETRY_MAX_BACKOFF_SECONDS",
        default.retryMaxBackoff
      ),
      maxConcurrentReconciles = int(
        "ankka.operator.max-concurrent-reconciles",
        "ANKKA_OPERATOR_MAX_CONCURRENT_RECONCILES",
        default.maxConcurrentReconciles
      ),
      databaseStorageSize = string(
        "ankka.operator.database-storage-size",
        "ANKKA_OPERATOR_DATABASE_STORAGE_SIZE",
        default.databaseStorageSize
      ),
      baseDomain = raw("ankka.operator.base-domain", "ANKKA_BASE_DOMAIN"),
      sidecarImage = string(
        "ankka.operator.sidecar-image",
        "ANKKA_SIDECAR_IMAGE",
        default.sidecarImage
      ),
      proxyImage = string("ankka.operator.proxy-image", "ANKKA_PROXY_IMAGE", default.proxyImage),
      httpsPort = int("ankka.operator.https-port", "ANKKA_HTTPS_PORT", default.httpsPort),
      // One rotation grace for every store (feature 039, research R1a D8): the one a cloud
      // provider honours, read whether or not the installation names a provider.
      rotationGrace =
        raw("ankka.operator.cloud-rotation-grace", PlatformVariables.CloudRotationGrace)
          .map(CloudSettings.duration(_, PlatformVariables.CloudRotationGrace))
          .getOrElse(default.rotationGrace),
      otlpEndpoint = raw("ankka.operator.otlp-endpoint", PlatformVariables.OtlpEndpoint),
      otlpHeaders =
        raw("ankka.operator.otlp-headers", PlatformVariables.OtlpHeaders).map(Credential(_)),
      broker = BrokerSettings.read(raw),
      objectStore = store,
      backups = BackupSettings.read(raw, store),
      cloud = CloudSettings.read(raw),
      storageMoverImage = string(
        "ankka.operator.storage-mover-image",
        "ANKKA_STORAGE_MOVER_IMAGE",
        default.storageMoverImage
      )
    ).withBackend()

  extension (settings: Settings)
    /**
     * The store new buckets are made in, and Google Cloud Storage's settings when that is it
     * (feature 039). Every combination that cannot give a service a bucket is a startup failure
     * naming the setting, never a store that half works.
     */
    private def withBackend(): Settings =
      val named = raw("ankka.operator.object-store.backend", PlatformVariables.ObjectStoreBackend)
      named.map(ObjectStoreBackend.parse) match
        case None =>
          // Unset: Garage when it is installed, else the cloud account when a provider is named,
          // with no prefix of the installation's (research R1a D1). Every installation keeps the
          // store it had before the setting existed.
          if settings.objectStore.isDefined then
            settings.copy(objectStoreBackend = Some(ObjectStoreBackend.Garage))
          else if settings.cloud.isDefined then
            settings.copy(
              objectStoreBackend = Some(ObjectStoreBackend.Gcs),
              gcs = Some(GcsSettings(prefix = "", softDeleteDays = softDeleteDays()))
            )
          else settings
        case Some(Left(bad)) =>
          throw new IllegalArgumentException(
            s"${PlatformVariables.ObjectStoreBackend} is '$bad'; it must be garage or gcs"
          )
        case Some(Right(ObjectStoreBackend.Garage)) =>
          if settings.objectStore.isEmpty then
            throw new IllegalArgumentException(
              s"${PlatformVariables.ObjectStoreBackend} is garage, so ANKKA_OBJECT_STORE_ADMIN_URL " +
                "and its companions must be set: the installation has no Garage to make buckets in"
            )
          settings.copy(objectStoreBackend = Some(ObjectStoreBackend.Garage))
        case Some(Right(ObjectStoreBackend.Gcs)) =>
          if settings.cloud.isEmpty then
            throw new IllegalArgumentException(
              s"${PlatformVariables.ObjectStoreBackend} is gcs, so ANKKA_CLOUD_PROVIDER must name a " +
                "cloud provider: the operator makes nothing in Google Cloud itself"
            )
          val prefix = raw(
            "ankka.operator.object-store.prefix",
            PlatformVariables.ObjectStorePrefix
          )
            .getOrElse(
              throw new IllegalArgumentException(
                s"${PlatformVariables.ObjectStoreBackend} is gcs, so " +
                  s"${PlatformVariables.ObjectStorePrefix} must be set: a bucket's name in Google " +
                  "Cloud Storage starts with the installation's prefix"
              )
            )
          settings.copy(
            objectStoreBackend = Some(ObjectStoreBackend.Gcs),
            gcs = Some(GcsSettings(prefix, softDeleteDays()))
          )

  /** How many days a new bucket in Google Cloud Storage keeps a deleted object: 7 to 90. */
  private def softDeleteDays(): Int =
    raw(
      "ankka.operator.object-store.soft-delete-days",
      PlatformVariables.ObjectStoreSoftDeleteDays
    ) match
      case None => GcsSettings.DefaultSoftDeleteDays
      case Some(value) =>
        value.toIntOption
          .filter(GcsSettings.SoftDeleteDays.contains)
          .getOrElse(
            throw new IllegalArgumentException(
              s"${PlatformVariables.ObjectStoreSoftDeleteDays} is '$value'; it must be a " +
                s"number of days from ${GcsSettings.SoftDeleteDays.start} to " +
                s"${GcsSettings.SoftDeleteDays.end}"
            )
          )

  /**
   * The object store, whole or not at all. The administration URL says there is one; with it set, a
   * missing companion is a startup failure naming the variable, never a store that half works.
   */
  private def objectStore(): Option[ObjectStoreSettings] =
    raw("ankka.operator.object-store.admin-url", "ANKKA_OBJECT_STORE_ADMIN_URL").map { adminUrl =>
      def required(key: String, variable: String): String =
        raw(s"ankka.operator.object-store.$key", variable).getOrElse(
          throw new IllegalArgumentException(
            s"ANKKA_OBJECT_STORE_ADMIN_URL is set, so $variable must be too: the operator cannot " +
              "give a service a bucket without it"
          )
        )
      val service = required("service", "ANKKA_OBJECT_STORE_SERVICE")
      ObjectStoreSettings(
        adminUrl = adminUrl,
        adminToken = required("admin-token", "ANKKA_OBJECT_STORE_ADMIN_TOKEN"),
        endpoint = required("endpoint", "ANKKA_OBJECT_STORE_ENDPOINT"),
        region = required("region", "ANKKA_OBJECT_STORE_REGION"),
        service = ObjectStoreSettings.ServiceRef
          .parse(service)
          .getOrElse(
            throw new IllegalArgumentException(
              s"ANKKA_OBJECT_STORE_SERVICE is '$service'; it must be <namespace>/<name>:<port>"
            )
          )
      )
    }

  private def raw(property: String, variable: String): Option[String] =
    Option(System.getProperty(property))
      .orElse(Option(System.getenv(variable)))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def string(property: String, variable: String, fallback: String): String =
    raw(property, variable).getOrElse(fallback)

  private def int(property: String, variable: String, fallback: Int): Int =
    raw(property, variable).flatMap(_.toIntOption).getOrElse(fallback)

  // Durations are whole seconds rather than "5m": an env var is a string with no parser
  // behind it, and a malformed duration that silently became a default would be worse than
  // a slightly clumsier name.
  private def seconds(
      property: String,
      variable: String,
      fallback: FiniteDuration
  ): FiniteDuration =
    raw(property, variable).flatMap(_.toIntOption).map(_.seconds).getOrElse(fallback)

/**
 * Where the installation's broker is, and where the operator writes the topics and users it holds.
 *
 * @param bootstrap
 *   the address services connect to, `<cluster>-kafka-bootstrap.<namespace>.svc:9093`
 * @param namespace
 *   the namespace the broker's topic and user operators watch
 * @param cluster
 *   the broker's name, which every topic and user names in its `strimzi.io/cluster` label
 */
final case class BrokerSettings(bootstrap: String, namespace: String, cluster: String)

object BrokerSettings:

  /** Each setting: its system property, then the variable the broker component sets. */
  val Variables: Vector[(String, String)] = Vector(
    "ankka.operator.broker-bootstrap" -> "ANKKA_BROKER_BOOTSTRAP",
    "ankka.operator.broker-namespace" -> "ANKKA_BROKER_NAMESPACE",
    "ankka.operator.broker-cluster"   -> "ANKKA_BROKER_CLUSTER"
  )

  /**
   * All three, or none. Some and not others is a component installed by halves, and an operator
   * that guessed the rest would write topics where no broker reads them; it refuses to start
   * instead, naming what is missing.
   */
  def read(lookup: (String, String) => Option[String]): Option[BrokerSettings] =
    Variables.map(lookup.tupled) match
      case Vector(Some(bootstrap), Some(namespace), Some(cluster)) =>
        Some(BrokerSettings(bootstrap, namespace, cluster))
      case values if values.forall(_.isEmpty) => None
      case values =>
        val missing = Variables.zip(values).collect { case ((_, variable), None) => variable }
        throw IllegalStateException(
          s"the broker is configured by halves: ${missing.mkString(", ")} " +
            s"${if missing.size == 1 then "is" else "are"} not set; set all three or none"
        )
