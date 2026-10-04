package com.thinkmorestupidless.ankka.operator.strimzi

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import io.fabric8.kubernetes.api.model.HasMetadata

/**
 * Strimzi's identity and the parts of its status ankka reads, shared by `KafkaTopic` and
 * `KafkaUser`.
 *
 * As with CloudNativePG's models, these are partial: they carry what ankka writes and what its
 * decision reads, and ignore the rest, so a field Strimzi adds in a later version decodes to
 * nothing rather than failing. The operator's one client is built with Scala-aware Jackson, which
 * serves these as it serves the CNPG models.
 */
object StrimziDefinitions:

  val Group: String   = "kafka.strimzi.io"
  val Version: String = "v1"

  /** The label every topic and user carries, naming the `Kafka` whose operators manage it. */
  val ClusterLabel: String = "strimzi.io/cluster"

  final case class Identity(kind: String, plural: String, apiVersion: String)

  def identityOf(resourceClass: Class[?]): Identity =
    Identity(
      kind = HasMetadata.getKind(resourceClass),
      plural = HasMetadata.getPlural(resourceClass),
      apiVersion = HasMetadata.getApiVersion(resourceClass)
    )

/**
 * One of Strimzi's conditions. `Ready` is the one ankka reads: `True` when the topic or user exists
 * as declared; `False` with a `reason` and a `message` when Strimzi could not make it so, which is
 * transient unless the reason says otherwise.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class StrimziCondition(
    `type`: String = "",
    status: String = "",
    reason: Option[String] = None,
    message: Option[String] = None
)

/** What every Strimzi status has: conditions and the generation they describe. */
trait StrimziStatus:
  def conditions: Vector[StrimziCondition]
  def observedGeneration: Option[Long]

  /** The `Ready` condition, if Strimzi has reported one. */
  def ready: Option[StrimziCondition] = conditions.find(_.`type` == "Ready")
