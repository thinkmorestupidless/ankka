package com.thinkmorestupidless.ankka.operator.strimzi

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.{Namespaced, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * A topic as ankka declares it: its partitions, its copies and every setting of its configuration
 * (feature 043), so the broker's configuration of the topic names each and none is the broker's
 * default. Replicas are stated because a topic's copies are its durability and are fixed when it is
 * declared: Strimzi changes them only through Cruise Control, which the platform does not install.
 * A topic declared before topics stated their copies has none, and the broker's
 * `default.replication.factor` stays what decided them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaTopicSpec(
    partitions: Int = 1,
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    replicas: Option[Int] = None,
    /**
     * Feature 037: `cleanup.policy: compact` for a topic the project declares compacted; absent
     * otherwise, so an existing topic's applied object does not change (`NON_ABSENT` would still
     * write an empty map). Strimzi's values may be numbers or booleans, so `Object`.
     */
    config: Option[Map[String, Object]] = None
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaTopicStatus(
    conditions: Vector[StrimziCondition] = Vector.empty,
    // Erasure hides an Option's element from Jackson, which reads the number as an Integer; the
    // first comparison with a generation then fails to unbox it.
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
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
