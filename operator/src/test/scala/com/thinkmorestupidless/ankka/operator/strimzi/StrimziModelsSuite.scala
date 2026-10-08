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
      KafkaTopicSpec(3, Some(Map("cleanup.policy" -> "compact")))
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
