package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, EnvEntry, ProjectTopicEntry}
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
 * project's topics and its own consumer groups, and the variables that tell its runtime where the
 * broker is and how to reach it; and a topic for each its project declares.
 *
 * Every object lives in the broker's namespace, where Strimzi's operators watch, and none carries
 * an owner reference: one cannot cross namespaces, and nothing the platform makes on the broker is
 * ever removed by the platform. They are labelled as ankka's, as every object it manages is.
 */
object StrimziRendering:

  /** What a compacted topic's `KafkaTopic` carries (feature 037). */
  val CompactedConfig: Map[String, Object] = Map("cleanup.policy" -> "compact")

  /** Whether a topic's config says the broker keeps the last message under each key. */
  def compacted(config: Option[Map[String, Object]]): Boolean =
    config.exists(
      _.get("cleanup.policy").exists(_.toString.split(",").map(_.trim).contains("compact"))
    )

  /** The operations a service has on its project's topics: read, write, and see that they exist. */
  val TopicOperations: Vector[String] = Vector("Read", "Write", "Describe")

  /** On its own consumer groups: join them and commit their offsets. */
  val GroupOperations: Vector[String] = Vector("Read")

  /** Where the runtime finds the service certificate it presents to the broker. */
  val TlsDirectory: String = ZeroTrust.ServiceMount

  private def labels(spec: AnkkaServiceSpec, broker: BrokerSettings): Map[String, String] =
    Labels.identity(spec.projectId, spec.serviceName) +
      (StrimziDefinitions.ClusterLabel -> broker.cluster)

  def user(
      spec: AnkkaServiceSpec,
      broker: BrokerSettings,
      // Feature 040: other projects' topics granted to this service, each one literal entry.
      granted: Vector[GrantedTopic] = Vector.empty
  ): KafkaUserResource =
    KafkaUserResource(
      broker.namespace,
      BrokerNames.user(spec.projectId, spec.serviceName),
      labels(spec, broker),
      KafkaUserSpec(
        Some(KafkaUserAuthentication("tls-external")),
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
          ) ++ grantedRules(granted)
        )
      )
    )

  /**
   * One literal entry per granted right on another project's topic, in a stable order so an
   * unchanged set of grants applies an unchanged user. No group and no prefix: a grantee reads
   * under its own groups, which its own prefix entry already covers.
   */
  def grantedRules(granted: Vector[GrantedTopic]): Vector[AclRule] =
    granted.distinct.sortBy(g => (g.project, g.topic, g.right)).flatMap { g =>
      val operations = g.right match
        case GrantedTopic.Consume => Some(Vector("Read", "Describe"))
        case GrantedTopic.Produce => Some(Vector("Write", "Describe"))
        case _                    => None
      operations.map(ops =>
        AclRule(AclResource("topic", BrokerNames.topic(g.project, g.topic), "literal"), ops)
      )
    }

  /** A topic the project declares: the project's, so labelled for the project alone. */
  def topic(
      projectId: String,
      entry: ProjectTopicEntry,
      broker: BrokerSettings
  ): KafkaTopicResource =
    KafkaTopicResource(
      broker.namespace,
      BrokerNames.topic(projectId, entry.name),
      Map(
        Labels.ManagedByKey             -> Labels.ManagedByAnkka,
        Labels.ProjectKey               -> projectId,
        StrimziDefinitions.ClusterLabel -> broker.cluster
      ),
      KafkaTopicSpec(
        partitions = entry.partitions,
        config = Option.when(entry.compacted)(StrimziRendering.CompactedConfig)
      )
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
