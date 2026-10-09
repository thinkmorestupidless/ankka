package com.thinkmorestupidless.ankka.operator.strimzi

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import io.fabric8.kubernetes.client.utils.Serialization

/**
 * The Strimzi models survive the serialization the operator uses, in the exact shape of
 * `contracts/operator.md`, and read the statuses Strimzi 1.2.0 was seen writing.
 */
class StrimziModelsSuite extends munit.FunSuite:

  private val serialization = AnkkaSerialization()

  private def fromYaml[A](yaml: String, cls: Class[A]): A =
    serialization.unmarshal(
      serialization.asJson(Serialization.unmarshal(yaml, classOf[java.util.Map[?, ?]])),
      cls
    )

  test("a KafkaUser in the contract's shape round-trips") {
    val yaml =
      """apiVersion: kafka.strimzi.io/v1
        |kind: KafkaUser
        |metadata:
        |  name: money.wallet
        |  namespace: ankka-broker
        |  labels: { strimzi.io/cluster: ankka }
        |spec:
        |  authentication: { type: tls-external }
        |  authorization:
        |    type: simple
        |    acls:
        |      - resource: { type: topic, name: "money.", patternType: prefix }
        |        operations: [Read, Write, Describe]
        |      - resource: { type: group, name: "ankka.money.wallet.", patternType: prefix }
        |        operations: [Read]
        |""".stripMargin
    val user = fromYaml(yaml, classOf[KafkaUserResource])
    assertEquals(
      user.getSpec,
      KafkaUserSpec(
        KafkaUserAuthentication("tls-external"),
        KafkaUserAuthorization(
          "simple",
          Vector(
            AclRule(AclResource("topic", "money.", "prefix"), Vector("Read", "Write", "Describe")),
            AclRule(AclResource("group", "ankka.money.wallet.", "prefix"), Vector("Read"))
          )
        )
      )
    )
    assertEquals(user.getMetadata.getName, "money.wallet")
    val again = serialization.unmarshal(serialization.asJson(user), classOf[KafkaUserResource])
    assertEquals(again.getSpec, user.getSpec)
  }

  test("a KafkaTopic in the contract's shape round-trips, with no replicas") {
    val topic = KafkaTopicResource(
      "ankka-broker",
      "money.transactions",
      Map(StrimziDefinitions.ClusterLabel -> "ankka"),
      KafkaTopicSpec(partitions = 12)
    )
    val json = serialization.asJson(topic)
    assert(!json.contains("replicas"), s"replicas are the broker's to decide: $json")
    assertEquals(
      serialization.unmarshal(json, classOf[KafkaTopicResource]).getSpec,
      KafkaTopicSpec(12)
    )
  }

  test(
    "a compacted KafkaTopic round-trips its config, and a value Strimzi wrote as a number is read"
  ) {
    val topic = KafkaTopicResource(
      "ankka-broker",
      "shop.cart-deltas",
      Map(StrimziDefinitions.ClusterLabel -> "ankka"),
      KafkaTopicSpec(3, config = Some(Map("cleanup.policy" -> "compact")))
    )
    val json = serialization.asJson(topic)
    assert(json.contains("\"cleanup.policy\":\"compact\""), json)
    // An uncompacted topic's applied object carries no config key at all (feature 037).
    val plain = KafkaTopicResource(
      "ankka-broker",
      "shop.orders",
      Map(StrimziDefinitions.ClusterLabel -> "ankka"),
      KafkaTopicSpec(3)
    )
    assert(!serialization.asJson(plain).contains("config"), serialization.asJson(plain))
    assertEquals(serialization.unmarshal(json, classOf[KafkaTopicResource]).getSpec, topic.getSpec)
    val written = json.replace("\"config\":{", "\"config\":{\"segment.ms\":100,")
    val read    = serialization.unmarshal(written, classOf[KafkaTopicResource]).getSpec
    assert(com.thinkmorestupidless.ankka.operator.StrimziRendering.compacted(read.config))
    assertEquals(read.config.get("segment.ms").toString, "100")
  }

  test(
    "a topic's copies and every setting round-trip, and copies are absent where unstated (feature 043)"
  ) {
    val topic = KafkaTopicResource(
      "ankka-broker",
      "money.transactions",
      Map(StrimziDefinitions.ClusterLabel -> "ankka"),
      KafkaTopicSpec(12, replicas = Some(3), config = Some(Map("retention.ms" -> "7776000000")))
    )
    val json = serialization.asJson(topic)
    assert(json.contains("\"replicas\":3"), json)
    val read = serialization.unmarshal(json, classOf[KafkaTopicResource]).getSpec
    assertEquals(read.replicas, Some(3))
    assertEquals(
      read.config.map(_.view.mapValues(_.toString).toMap),
      Some(Map("retention.ms" -> "7776000000"))
    )
  }

  test(
    "the operator renders the Kafka keys the control plane's settings say, row by row (feature 043)"
  ) {
    // protocol/fixtures/topics/settings.json is written by controlplane-api's TopicSettingsSuite from
    // TopicSettings.toKafka; each row's Kafka map, carried as the resource's numbers, must render to
    // itself here. A key spelled or written differently on either side fails one of the two.
    val file = Iterator
      .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/fixtures/topics/settings.json"))
      .find(java.nio.file.Files.exists(_))
      .getOrElse(fail("protocol/fixtures/topics/settings.json is missing"))
    val rows = com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile)
    import scala.jdk.CollectionConverters.*
    assert(rows.size >= 4, s"${rows.size} rows")
    rows.elements.asScala.foreach { row =>
      val kafka = row.get("kafka").properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap
      def long(key: String) = kafka.get(key).map(_.toLong)
      val entry = com.thinkmorestupidless.ankka.crd.ProjectTopicEntry(
        "t",
        1,
        "",
        retentionMs = long("retention.ms"),
        retentionBytes = long("retention.bytes"),
        cleanupPolicy = kafka.get("cleanup.policy"),
        deleteRetentionMs = long("delete.retention.ms"),
        minCompactionLagMs = long("min.compaction.lag.ms"),
        maxCompactionLagMs = long("max.compaction.lag.ms"),
        minInsyncReplicas = kafka.get("min.insync.replicas").map(_.toInt)
      )
      val rendered = com.thinkmorestupidless.ankka.operator.StrimziRendering
        .kafkaConfig(entry)
        .map(_.view.mapValues(_.toString).toMap)
      assertEquals(rendered, Some(kafka), row.toString)
    }
  }

  test("the broker nodes are the replicas of the pools that hold partitions") {
    assertEquals(
      KafkaNodePoolResource.brokerNodes(
        Vector(
          KafkaNodePoolSpec(3, Vector("controller", "broker")),
          KafkaNodePoolSpec(2, Vector("broker")),
          KafkaNodePoolSpec(3, Vector("controller"))
        )
      ),
      5
    )
    val json =
      """{"apiVersion":"kafka.strimzi.io/v1","kind":"KafkaNodePool","metadata":{"name":"dual"},""" +
        """"spec":{"replicas":3,"roles":["controller","broker"],"storage":{"type":"jbod"}}}"""
    assertEquals(
      serialization.unmarshal(json, classOf[KafkaNodePoolResource]).getSpec,
      KafkaNodePoolSpec(3, Vector("controller", "broker"))
    )
  }

  test("a resource Strimzi has not reported on has no status") {
    val topic = KafkaTopicResource("ankka-broker", "money.t", Map.empty, KafkaTopicSpec(1))
    assertEquals(topic.getStatus, null)
    val again = serialization.unmarshal(serialization.asJson(topic), classOf[KafkaTopicResource])
    assertEquals(again.getStatus, null)
  }

  test("a topic refused fewer partitions is read with Strimzi's reason and message") {
    // As Strimzi 1.2.0 wrote it when a topic's partitions were lowered (research R5).
    val json =
      """{"conditions":[{"type":"Ready","status":"False","reason":"NotSupported",
        |"message":"Decreasing partitions not supported","lastTransitionTime":"2026-10-04T16:30:00Z"}],
        |"observedGeneration":3,"topicName":"money.transactions","topicId":"Kneg--j0QVmfPTNoAP14ZQ"}""".stripMargin
    val status = serialization.unmarshal(json, classOf[KafkaTopicStatus])
    assertEquals(
      status.ready,
      Some(
        StrimziCondition(
          "Ready",
          "False",
          Some("NotSupported"),
          Some("Decreasing partitions not supported")
        )
      )
    )
    assertEquals(status.observedGeneration, Some(3L))
    // Read as a Long, as the operator compares it with the resource's generation: equality alone
    // passes for a boxed Integer too.
    assertEquals(status.observedGeneration.map(_ + 1L), Some(4L))
    assertEquals(
      com.thinkmorestupidless.ankka.operator.StrimziObjectState
        .found(Some(4L), Some(status), None)
        .ready,
      None
    )
  }

  test("a user's status names the principal the broker knows it as") {
    val json =
      """{"conditions":[{"type":"Ready","status":"True"}],"observedGeneration":1,"username":"CN=money.wallet"}"""
    val status = serialization.unmarshal(json, classOf[KafkaUserStatus])
    assertEquals(status.username, Some("CN=money.wallet"))
    assertEquals(status.ready.map(_.status), Some("True"))
    assertEquals(status.observedGeneration.map(_ + 1L), Some(2L))
  }

  test("the identities are Strimzi's v1 kinds") {
    assertEquals(KafkaTopicResource.identity.apiVersion, "kafka.strimzi.io/v1")
    assertEquals(KafkaTopicResource.identity.plural, "kafkatopics")
    assertEquals(KafkaUserResource.identity.kind, "KafkaUser")
    assertEquals(KafkaUserResource.identity.plural, "kafkausers")
  }
