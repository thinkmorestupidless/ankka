package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  ProjectBrokerEntry,
  AnkkaProjectSpec,
  AnkkaProjectStatus,
  ProjectTopicEntry,
  ProjectTopicStatus
}
import com.thinkmorestupidless.ankka.operator.strimzi.{KafkaTopicSpec, StrimziDefinitions}

/**
 * What one pass over a project's resource does, as values (`ProjectReconciler.actions`): the
 * project's topics on the installation's broker, and their status. The objects themselves are
 * asserted, never a string found somewhere.
 */
class ProjectRenderingSuite extends munit.FunSuite:

  private val broker = Some(BrokerStack.settings)
  private val ref    = ServiceRef("ankka-money", "money")
  private val money = AnkkaProjectSpec(
    "money",
    List(
      ProjectTopicEntry("transactions", 12, "2026-10-05T10:00:00Z"),
      ProjectTopicEntry("wallet-events", 3, "2026-10-05T10:00:00Z")
    )
  )
  private val made =
    StrimziObjectState(exists = true, ready = Some(true), createdAt = None)

  private def actions(
      spec: AnkkaProjectSpec = money,
      settings: Option[BrokerSettings] = broker,
      observed: Map[String, TopicState] = Map.empty,
      current: Option[AnkkaProjectStatus] = None
  ) = ProjectReconciler.actions(ref, spec, settings, observed, current)

  private def topics(as: Vector[Action]) = as.collect { case Action.EnsureKafkaTopic(t) => t }

  // features/broker/declaring.feature
  test("a declared topic is made on the installation's broker for its project") {
    val rendered = topics(actions())
    assertEquals(
      rendered.map(_.getMetadata.getName),
      Vector("money.transactions", "money.wallet-events")
    )
    assertEquals(rendered.map(_.getSpec), Vector(KafkaTopicSpec(12), KafkaTopicSpec(3)))
    assert(rendered.forall(_.getMetadata.getNamespace == "ankka-broker"))
    for t <- rendered do
      val labels = t.getMetadata.getLabels
      assertEquals(labels.get(StrimziDefinitions.ClusterLabel), "ankka")
      assertEquals(labels.get(Labels.ManagedByKey), Labels.ManagedByAnkka)
      assertEquals(labels.get(Labels.ProjectKey), "money")
  }

  // features/topics/contracts.feature: the declarations every service reads at start (feature 037)
  test(
    "the project's declarations are rendered for every service to read, with or without a broker"
  ) {
    val spec = AnkkaProjectSpec(
      "money",
      List(
        ProjectTopicEntry("wallet-events", 3, "2026-10-07T10:00:00Z"),
        ProjectTopicEntry(
          "transactions",
          12,
          "2026-10-07T10:00:00Z",
          compacted = true,
          contractName = Some("transaction.v1"),
          contractFingerprint = Some("sha256:ab")
        )
      ),
      List(
        ProjectBrokerEntry(
          "legacy",
          "kafka.legacy:9094",
          "sasl",
          "legacy-credential",
          "2026-10-07T10:00:00Z"
        )
      )
    )
    val expected =
      """{"project":"money","topics":[""" +
        """{"name":"transactions","partitions":12,"compacted":true,"contract":{"name":"transaction.v1","fingerprint":"sha256:ab"}},""" +
        """{"name":"wallet-events","partitions":3,"compacted":false}],""" +
        """"brokers":[{"name":"legacy","bootstrap":"kafka.legacy:9094","shape":"sasl"}]}"""
    for settings <- Vector(broker, None) do
      val configs = actions(spec, settings).collect { case Action.EnsureProjectConfig(cm) => cm }
      assertEquals(configs.size, 1)
      val cm = configs.head
      assertEquals(cm.getMetadata.getNamespace, "ankka-money")
      assertEquals(cm.getMetadata.getName, ProjectConfig.Name)
      assertEquals(cm.getData.get(ProjectConfig.Key), expected)
    // Before the topics: a service started between the two sees the declarations.
    val as = actions(spec)
    assert(as.head.isInstanceOf[Action.EnsureProjectConfig], as.map(_.describe).toString)
  }

  // features/broker/compaction.feature
  test(
    "a topic declared compacted is rendered with cleanup.policy compact, and one not declared without a config"
  ) {
    val spec = AnkkaProjectSpec(
      "shop",
      List(
        ProjectTopicEntry("cart-deltas", 3, "2026-10-07T10:00:00Z", compacted = true),
        ProjectTopicEntry("orders", 3, "2026-10-07T10:00:00Z")
      )
    )
    val rendered = topics(actions(spec)).map(t => t.getMetadata.getName -> t.getSpec).toMap
    assertEquals(
      rendered("shop.cart-deltas"),
      KafkaTopicSpec(3, config = Some(Map("cleanup.policy" -> "compact")))
    )
    assertEquals(rendered("shop.orders"), KafkaTopicSpec(3))
    assertEquals(rendered("shop.orders").config, None)
  }

  // features/broker/retention.feature: the broker holds each setting on the topic itself
  test(
    "a topic with settings states every one of them and its copies on the KafkaTopic (feature 043)"
  ) {
    val spec = AnkkaProjectSpec(
      "money",
      List(
        ProjectTopicEntry(
          "transactions",
          12,
          "2026-10-08T10:00:00Z",
          retentionMs = Some(7776000000L),
          retentionBytes = Some(-1L),
          cleanupPolicy = Some("delete"),
          deleteRetentionMs = Some(86400000L),
          minCompactionLagMs = Some(0L),
          maxCompactionLagMs = Some(Long.MaxValue),
          replicas = Some(3),
          minInsyncReplicas = Some(2)
        ),
        // Filled by the sweep: settings, but its copies are the broker's.
        ProjectTopicEntry(
          "notices",
          3,
          "2026-10-08T10:00:00Z",
          retentionMs = Some(604800000L),
          retentionBytes = Some(-1L),
          cleanupPolicy = Some("delete"),
          deleteRetentionMs = Some(86400000L),
          minCompactionLagMs = Some(0L),
          maxCompactionLagMs = Some(Long.MaxValue)
        ),
        // Declared before topics stated settings, and not yet filled: rendered as it always was.
        ProjectTopicEntry("orders", 3, "2026-10-07T10:00:00Z")
      )
    )
    val rendered = topics(actions(spec)).map(t => t.getMetadata.getName -> t.getSpec).toMap
    val config   = rendered("money.transactions").config.map(_.view.mapValues(_.toString).toMap)
    assertEquals(rendered("money.transactions").replicas, Some(3))
    assertEquals(
      config,
      Some(
        Map(
          "retention.ms"          -> "7776000000",
          "retention.bytes"       -> "-1",
          "cleanup.policy"        -> "delete",
          "delete.retention.ms"   -> "86400000",
          "min.compaction.lag.ms" -> "0",
          "max.compaction.lag.ms" -> Long.MaxValue.toString,
          "min.insync.replicas"   -> "2"
        )
      )
    )
    assertEquals(rendered("money.notices").replicas, None)
    assert(!rendered("money.notices").config.exists(_.contains("min.insync.replicas")))
    assertEquals(rendered("money.orders"), KafkaTopicSpec(3))
  }

  // features/broker/copies.feature: more copies than broker nodes
  test(
    "a topic asking for more copies than the broker has broker nodes is not rendered, and fails"
  ) {
    val five = ProjectTopicEntry("transactions", 12, "2026-10-08T10:00:00Z", replicas = Some(5))
    val spec = AnkkaProjectSpec("money", List(five))
    val as = ProjectReconciler.actions(
      ServiceRef("ankka-money", "money"),
      spec,
      broker,
      Map.empty,
      None,
      Some(3)
    )
    assertEquals(topics(as), Vector.empty)
    val status = as.collectFirst { case Action.SetProjectStatus(_, _, s) => s }.get
    assertEquals(status.brokerNodes, Some(3))
    assertEquals(status.topics.map(_.phase), List("Failed"))
    assertEquals(
      status.topics.flatMap(_.detail),
      List("topic 'money.transactions' asks for 5 copies and the broker has 3 broker nodes")
    )
  }

  test("a project's topics are owned by nothing, and nothing removes one") {
    val as = actions()
    for t <- topics(as) do
      assert(Option(t.getMetadata.getOwnerReferences).forall(_.isEmpty), t.getMetadata.getName)
    assert(!as.exists(_.describe.matches("(?i).*(remove|delete).*")), as.map(_.describe).toString)
  }

  // features/broker/kept.feature
  test("a topic no longer declared is neither made nor reported, and nothing removes it") {
    val fewer = money.copy(topics = money.topics.take(1))
    val as    = actions(fewer)
    assertEquals(topics(as).map(_.getMetadata.getName), Vector("money.transactions"))
    assertEquals(
      as.collect { case Action.SetProjectStatus(_, _, s) => s.topics.map(_.name) },
      Vector(List("transactions"))
    )
  }

  test("a topic is never rendered with fewer partitions than it has") {
    val grown = Map("money.transactions" -> TopicState(made, Some(13)))
    assertEquals(
      topics(actions(observed = grown)).map(_.getMetadata.getName),
      Vector("money.wallet-events")
    )
  }

  // features/broker/installation.feature
  test("a topic declared on an installation with no broker says why it is not made") {
    val as = actions(settings = None)
    assertEquals(topics(as), Vector.empty)
    assertEquals(
      as.collect { case Action.SetProjectStatus(ns, name, s) => (ns, name, s.topics.map(_.phase)) },
      Vector(("ankka-money", "money", List("Failed", "Failed")))
    )
  }

  test("a status that says what the resource already says is not written again") {
    val seen = Map(
      "money.transactions"  -> TopicState(made, Some(12)),
      "money.wallet-events" -> TopicState(made, Some(3))
    )
    val same = AnkkaProjectStatus(
      List(
        ProjectTopicStatus("transactions", "Provisioned", Some(12)),
        ProjectTopicStatus("wallet-events", "Provisioned", Some(3))
      )
    )
    assertEquals(
      actions(observed = seen, current = Some(same)).collect { case s: Action.SetProjectStatus =>
        s
      },
      Vector.empty
    )
    assertEquals(
      actions(observed = seen, current = None).collect { case s: Action.SetProjectStatus =>
        s
      }.size,
      1
    )
  }
