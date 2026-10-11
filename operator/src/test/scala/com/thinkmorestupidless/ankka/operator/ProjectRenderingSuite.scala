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
      KafkaTopicSpec(3, Some(Map("cleanup.policy" -> "compact")))
    )
    assertEquals(rendered("shop.orders"), KafkaTopicSpec(3))
    assertEquals(rendered("shop.orders").config, None)
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

  // features/cross-project/route-grants.feature, listing.feature (feature 040)
  test("the project's accepted grants are rendered beside its declarations, in the one ConfigMap") {
    import com.thinkmorestupidless.ankka.crd.ProjectGrantEntry
    val spec = money.copy(grants =
      List(
        ProjectGrantEntry(
          "b2",
          "machine:affiliates/network",
          "topic",
          topic = Some("transactions"),
          right = Some("consume"),
          decrypt = true
        ),
        ProjectGrantEntry(
          "a1",
          "service:payments/merchant",
          "route",
          service = Some("wallet"),
          httpMethod = Some("POST"),
          path = Some("/v1/wallets/{player}/{currency}/deposits")
        ),
        ProjectGrantEntry("c3", "service:payments/merchant", "erasure")
      )
    )
    val config = actions(spec).collectFirst { case Action.EnsureProjectConfig(c) => c }.get
    assertEquals(config.getMetadata.getName, ProjectConfig.Name)
    assertEquals(
      config.getData.keySet,
      java.util.Set.of(ProjectConfig.Key, ProjectConfig.GrantsKey)
    )
    assertEquals(
      config.getData.get(ProjectConfig.GrantsKey),
      """{"project":"money","grants":[""" +
        """{"id":"a1","grantee":"service:payments/merchant","kind":"route","service":"wallet","httpMethod":"POST","path":"/v1/wallets/{player}/{currency}/deposits"},""" +
        """{"id":"b2","grantee":"machine:affiliates/network","kind":"topic","topic":"transactions","right":"consume","decrypt":true},""" +
        """{"id":"c3","grantee":"service:payments/merchant","kind":"erasure"}]}"""
    )
  }

  test("a project with no grants renders an empty grants file, so every service reads none") {
    val config = actions().collectFirst { case Action.EnsureProjectConfig(c) => c }.get
    assertEquals(
      config.getData.get(ProjectConfig.GrantsKey),
      """{"project":"money","grants":[]}"""
    )
  }

  test("where machines' tokens come from is written beside the grants, and nothing without it") {
    val machines =
      Settings.MachineIssuer("https://api.example.test", Settings.DefaultMachineJwksUrl)
    val config =
      ProjectConfig.configMap("ankka-money", AnkkaProjectSpec(projectId = "money"), Some(machines))
    assertEquals(
      config.getData.get(ProjectConfig.MachinesKey),
      """{"issuer":"https://api.example.test","jwksUrl":"https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json"}"""
    )
    val without = ProjectConfig.configMap("ankka-money", AnkkaProjectSpec(projectId = "money"))
    assert(!without.getData.containsKey(ProjectConfig.MachinesKey), without.getData.toString)
  }

  test("the machine issuer is the control plane's own address, derived as the control plane does") {
    assertEquals(
      Settings.machineIssuer(None, None, Some("example.test"), 8443),
      Some(Settings.MachineIssuer("https://api.example.test:8443", Settings.DefaultMachineJwksUrl))
    )
    assertEquals(
      Settings.machineIssuer(None, None, Some("example.test"), 443).map(_.issuer),
      Some("https://api.example.test")
    )
    assertEquals(
      Settings.machineIssuer(Some("https://other.test"), Some("https://k/jwks"), None, 443),
      Some(Settings.MachineIssuer("https://other.test", "https://k/jwks"))
    )
    assertEquals(Settings.machineIssuer(None, None, None, 443), None)
  }
