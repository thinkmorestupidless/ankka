package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, EnvEntry, TopicEntry}
import com.thinkmorestupidless.ankka.operator.strimzi.{
  AclResource,
  AclRule,
  KafkaTopicResource,
  KafkaTopicSpec,
  KafkaUserAuthentication,
  KafkaUserAuthorization,
  KafkaUserResource,
  KafkaUserSpec,
  StrimziDefinitions
}

/**
 * What a service is on the installation's broker (feature 027): a user whose permissions end at its
 * project's topics and its own consumer groups, a topic for each it declares, and the variables
 * that tell its runtime where the broker is and how to reach it.
 *
 * Every object lives in the broker's namespace, where Strimzi's operators watch, and none carries
 * an owner reference: one cannot cross namespaces, and nothing the platform makes on the broker is
 * ever removed by the platform. They are labelled as ankka's, as every object it manages is.
 */
object StrimziRendering:

  /** The operations a service has on its project's topics: read, write, and see that they exist. */
  val TopicOperations: Vector[String] = Vector("Read", "Write", "Describe")

  /** On its own consumer groups: join them and commit their offsets. */
  val GroupOperations: Vector[String] = Vector("Read")

  /** Where the runtime finds the service certificate it presents to the broker. */
  val TlsDirectory: String = ZeroTrust.ServiceMount

  private def labels(spec: AnkkaServiceSpec, broker: BrokerSettings): Map[String, String] =
    Labels.identity(spec.projectId, spec.serviceName) +
      (StrimziDefinitions.ClusterLabel -> broker.cluster)

  def user(spec: AnkkaServiceSpec, broker: BrokerSettings): KafkaUserResource =
    KafkaUserResource(
      broker.namespace,
      BrokerNames.user(spec.projectId, spec.serviceName),
      labels(spec, broker),
      KafkaUserSpec(
        KafkaUserAuthentication("tls-external"),
        KafkaUserAuthorization(
          "simple",
          Vector(
            AclRule(
              AclResource("topic", BrokerNames.topicPrefix(spec.projectId), "prefix"),
              TopicOperations
            ),
            AclRule(
              AclResource(
                "group",
                BrokerNames.groupPrefix(spec.projectId, spec.serviceName),
                "prefix"
              ),
              GroupOperations
            )
          )
        )
      )
    )

  def topic(spec: AnkkaServiceSpec, entry: TopicEntry, broker: BrokerSettings): KafkaTopicResource =
    KafkaTopicResource(
      broker.namespace,
      BrokerNames.topic(spec.projectId, entry.name),
      labels(spec, broker),
      KafkaTopicSpec(partitions = entry.partitions)
    )

  /**
   * What the service's runtime is told. Each starts `ANKKA_KAFKA_`, which `PlatformVariables` gives
   * to both programs of a process-hosted service as it gives a broker variable a descriptor names.
   */
  def environment(spec: AnkkaServiceSpec, broker: BrokerSettings): List[EnvEntry] = List(
    EnvEntry("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some(broker.bootstrap), None, None),
    EnvEntry("ANKKA_KAFKA_TLS_DIRECTORY", Some(TlsDirectory), None, None),
    EnvEntry("ANKKA_KAFKA_TOPIC_PREFIX", Some(BrokerNames.topicPrefix(spec.projectId)), None, None)
  )
