package nakka.operator

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The operator's whole configuration surface.
 *
 * Read from system properties first, then the environment, then a default. No HOCON, because the
 * operator carries no config library and should not gain one for eleven values — and because a file
 * baked into a container image is the least useful place to configure a container.
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
    baseDomain: Option[String] = None
):
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

  val default: Settings = Settings(
    namespacePrefix = "nakka",
    resyncInterval = 5.minutes,
    retryMinBackoff = 2.seconds,
    retryMaxBackoff = 5.minutes,
    maxConcurrentReconciles = 16,
    databaseStorageSize = "1Gi"
  )

  /**
   * `-Dnakka.operator.namespace-prefix`, then `NAKKA_K8S_NAMESPACE_PREFIX`, then the default.
   *
   * The prefix **must** match the control plane's. A mismatch is the most likely misconfiguration
   * of a two-process design, and it presents as every service reporting that no operator has
   * reported on it — which is exactly why that signal exists.
   */
  def fromEnvironment(): Settings =
    Settings(
      namespacePrefix = string(
        "nakka.operator.namespace-prefix",
        "NAKKA_K8S_NAMESPACE_PREFIX",
        default.namespacePrefix
      ),
      resyncInterval = seconds(
        "nakka.operator.resync-seconds",
        "NAKKA_OPERATOR_RESYNC_SECONDS",
        default.resyncInterval
      ),
      retryMinBackoff = seconds(
        "nakka.operator.retry-min-backoff-seconds",
        "NAKKA_OPERATOR_RETRY_MIN_BACKOFF_SECONDS",
        default.retryMinBackoff
      ),
      retryMaxBackoff = seconds(
        "nakka.operator.retry-max-backoff-seconds",
        "NAKKA_OPERATOR_RETRY_MAX_BACKOFF_SECONDS",
        default.retryMaxBackoff
      ),
      maxConcurrentReconciles = int(
        "nakka.operator.max-concurrent-reconciles",
        "NAKKA_OPERATOR_MAX_CONCURRENT_RECONCILES",
        default.maxConcurrentReconciles
      ),
      databaseStorageSize = string(
        "nakka.operator.database-storage-size",
        "NAKKA_OPERATOR_DATABASE_STORAGE_SIZE",
        default.databaseStorageSize
      ),
      baseDomain = raw("nakka.operator.base-domain", "NAKKA_BASE_DOMAIN")
    )

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
