package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.{BrokerTopics, LocalPlan, TopicDeclarationRequests}
import com.thinkmorestupidless.ankka.core.TopicDeclarations

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** A project's declared topics on the local broker (feature 066, research R5), with no broker. */
class BrokerTopicsSuite extends munit.FunSuite:

  /** What `kafka-topics.sh --describe` printed against `apache/kafka:3.8.0`. */
  private val Described =
    "Topic: shop.orders-placed\tTopicId: 5oQ9yYyNQbe1oQ6w9gq0vA\tPartitionCount: 1\tReplicationFactor: 1\tConfigs: \n" +
      "\tTopic: shop.orders-placed\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: \n" +
      "Topic: shop.customers\tTopicId: k3L2mQ3cRs6l7kQ1uR0eHw\tPartitionCount: 3\tReplicationFactor: 1\tConfigs: cleanup.policy=compact,delete,segment.bytes=1073741824\n" +
      "\tTopic: shop.customers\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: \n" +
      "Topic: __consumer_offsets\tTopicId: Q\tPartitionCount: 50\tReplicationFactor: 1\tConfigs: compression.type=producer,cleanup.policy=compact,segment.bytes=104857600\n"

  test("the describe output is read as each topic's partitions and cleanup policy") {
    assertEquals(
      BrokerTopics.parseDescribe(Described),
      Map(
        "shop.orders-placed" -> (1, "delete"),
        "shop.customers"     -> (3, "compact,delete"),
        "__consumer_offsets" -> (50, "compact")
      )
    )
  }

  private val declarations = TopicDeclarations(
    Vector(
      TopicDeclarations.Topic("orders-placed", 6),
      TopicDeclarations.Topic("customers", 3, compacted = true, retention = Some("everything")),
      TopicDeclarations.Topic(
        "audit",
        2,
        retention = Some("7d"),
        cleanup = Some("delete"),
        copies = Some(3)
      )
    )
  )

  test(
    "each declaration is wanted under the project's prefix, with Kafka's names for its settings"
  ) {
    assertEquals(
      BrokerTopics.wanted("shop", declarations),
      Vector(
        LocalPlan.BrokerTopic("shop.orders-placed", 6, Map.empty),
        LocalPlan.BrokerTopic(
          "shop.customers",
          3,
          Map("cleanup.policy" -> "compact", "retention.ms" -> "-1")
        ),
        LocalPlan.BrokerTopic(
          "shop.audit",
          2,
          Map("cleanup.policy" -> "delete", "retention.ms" -> "604800000")
        )
      )
    )
  }

  test("what is missing is made, and what exists otherwise than declared is a problem") {
    val (toCreate, problems) =
      BrokerTopics.diff(
        BrokerTopics.wanted("shop", declarations),
        BrokerTopics.parseDescribe(Described)
      )
    assertEquals(toCreate.map(_.name), Vector("shop.audit"))
    assertEquals(
      problems,
      Vector(
        "the topic \"shop.orders-placed\" has 1 partition on the local broker and 6 declared; ankka local down --volumes removes it"
      )
    )
    val notCompacted = BrokerTopics.diff(
      Vector(LocalPlan.BrokerTopic("shop.orders-placed", 1, Map("cleanup.policy" -> "compact"))),
      BrokerTopics.parseDescribe(Described)
    )
    assertEquals(
      notCompacted._2,
      Vector(
        "the topic \"shop.orders-placed\" is not compacted on the local broker and is declared compacted; ankka local down --volumes removes it"
      )
    )
  }

  test("the creations run together in one shell line against the broker's container listener") {
    val command = BrokerTopics.createCommand(BrokerTopics.wanted("shop", declarations).take(2))
    assertEquals(command.take(2), Vector("sh", "-c"))
    assertEquals(
      command(2),
      "/opt/kafka/bin/kafka-topics.sh --bootstrap-server ankka-kafka:9094 --create --if-not-exists " +
        "--topic shop.orders-placed --partitions 6 --replication-factor 1 && " +
        "/opt/kafka/bin/kafka-topics.sh --bootstrap-server ankka-kafka:9094 --create --if-not-exists " +
        "--topic shop.customers --partitions 3 --replication-factor 1 " +
        "--config cleanup.policy=compact --config retention.ms=-1"
    )
  }

  test("a declaration becomes the request topics set sends, held to its rules") {
    val root = Files.createTempDirectory("topics")
    Files.createDirectories(root.resolve("contracts"))
    Files.writeString(root.resolve("contracts/order.v1.json"), """{"type":"object"}""", UTF_8)
    val request = TopicDeclarationRequests
      .of(
        root,
        TopicDeclarations.Topic(
          "orders-placed",
          6,
          contract = Some("order.v1"),
          schema = Some("contracts/order.v1.json")
        )
      )
      .fold(p => fail(p.mkString("; ")), identity)
    assertEquals(request.partitions, Some(6))
    assertEquals(request.contract.map(_.name), Some("order.v1"))
    assertEquals(
      TopicDeclarationRequests
        .of(root, TopicDeclarations.Topic("audit", 1, retention = Some("soon")))
        .left
        .map(_.size),
      Left(1)
    )
  }
