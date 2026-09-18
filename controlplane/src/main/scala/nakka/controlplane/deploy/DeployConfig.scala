package nakka.controlplane.deploy

import com.typesafe.config.Config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Everything the control plane needs to know about its deployment target. */
final case class DeployConfig(
    namespacePrefix: String,
    sweepInterval: FiniteDuration,
    retryMinBackoff: FiniteDuration,
    retryMaxBackoff: FiniteDuration,
    progressDeadline: FiniteDuration,
    failFastOnUnreachableCluster: Boolean
):
  def namespaceFor(projectId: String): String = s"$namespacePrefix-$projectId"

  /** Bounded, doubling. A permanently failing service costs a few attempts a minute. */
  def backoffFor(attempts: Int): FiniteDuration =
    val factor  = 1L << math.min(math.max(attempts, 0), 10)
    val doubled = retryMinBackoff * factor
    if doubled > retryMaxBackoff then retryMaxBackoff else doubled

object DeployConfig:

  val default: DeployConfig = DeployConfig(
    namespacePrefix = "nakka",
    sweepInterval = 30.seconds,
    retryMinBackoff = 5.seconds,
    retryMaxBackoff = 120.seconds,
    progressDeadline = 600.seconds,
    failFastOnUnreachableCluster = false
  )

  def from(config: Config): DeployConfig =
    val section = config.getConfig("nakka.controlplane.kubernetes")
    DeployConfig(
      namespacePrefix = section.getString("namespace-prefix"),
      sweepInterval = section.getDuration("sweep-interval").toMillis.millis,
      retryMinBackoff = section.getDuration("retry-min-backoff").toMillis.millis,
      retryMaxBackoff = section.getDuration("retry-max-backoff").toMillis.millis,
      progressDeadline = section.getDuration("progress-deadline").toMillis.millis,
      failFastOnUnreachableCluster = section.getBoolean("fail-fast-on-unreachable-cluster")
    )
