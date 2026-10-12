package com.thinkmorestupidless.ankka.operator.strimzi

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * What the operator reads of a broker's node pool (feature 043): how many nodes it has and what
 * they do. A pool whose roles include `broker` holds partitions; the operator sums those pools'
 * replicas to learn how many copies a topic can have. Read, never written: the pool is the
 * installation's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaNodePoolSpec(
    replicas: Int = 0,
    roles: Vector[String] = Vector.empty
)

@Group("kafka.strimzi.io")
@Version("v1")
@Kind("KafkaNodePool")
@Plural("kafkanodepools")
class KafkaNodePoolResource extends CustomResource[KafkaNodePoolSpec, Void] with Namespaced:
  override protected def initSpec(): KafkaNodePoolSpec = KafkaNodePoolSpec()
  override protected def initStatus(): Void            = null

object KafkaNodePoolResource:

  /** How many broker nodes these pools hold between them. */
  def brokerNodes(pools: Iterable[KafkaNodePoolSpec]): Int =
    pools.filter(_.roles.contains("broker")).map(_.replicas).sum
