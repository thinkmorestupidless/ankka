package com.thinkmorestupidless.ankka.operator.strimzi

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import io.fabric8.kubernetes.api.model.{Namespaced, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * A topic as ankka declares it: its partitions and nothing else. Replicas are left out, so the
 * broker's own `default.replication.factor` decides them and ankka never states a number that has
 * to match the installation's size.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaTopicSpec(partitions: Int = 1)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaTopicStatus(
    conditions: Vector[StrimziCondition] = Vector.empty,
    observedGeneration: Option[Long] = None,
    topicName: Option[String] = None
) extends StrimziStatus

@Group("kafka.strimzi.io")
@Version("v1")
@Kind("KafkaTopic")
@Plural("kafkatopics")
class KafkaTopicResource extends CustomResource[KafkaTopicSpec, KafkaTopicStatus] with Namespaced:
  override protected def initSpec(): KafkaTopicSpec     = KafkaTopicSpec()
  override protected def initStatus(): KafkaTopicStatus = null

object KafkaTopicResource:
  val identity: StrimziDefinitions.Identity =
    StrimziDefinitions.identityOf(classOf[KafkaTopicResource])

  def apply(
      namespace: String,
      name: String,
      labels: Map[String, String],
      spec: KafkaTopicSpec
  ): KafkaTopicResource =
    val resource = new KafkaTopicResource
    resource.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .withLabels(scala.jdk.CollectionConverters.MapHasAsJava(labels).asJava)
        .build()
    )
    resource.setSpec(spec)
    resource
