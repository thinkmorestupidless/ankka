package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  ProjectGrantEntry
}
import com.thinkmorestupidless.ankka.operator.strimzi.{
  AclResource,
  AclRule,
  KafkaUserQuotas,
  KafkaUserResource,
  KafkaUserSpec
}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

/**
 * A topic grant on the broker (feature 040): one literal entry on the grantee's `KafkaUser` per
 * granted right, and nothing else, from the granting projects' `AnkkaProject` resources.
 */
class BrokerGrantsRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(broker = Some(BrokerStack.settings))

  private val attribution = AnkkaServiceSpec(
    projectId = "affiliates-hub",
    serviceName = "attribution",
    generation = 1L,
    image = "attribution:1",
    port = Some(9000),
    provisionDatabase = false
  )

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace(s"ankka-${spec.projectId}")
        .withName(spec.serviceName)
        .withUid("u")
        .build()
    )
    r.setSpec(spec)
    r

  private def user(granted: Vector[GrantedTopic]): KafkaUserResource =
    Rendering
      .render(
        resource(attribution),
        settings,
        ProvisioningPlan.Supplied,
        knownToBroker = true,
        granted = granted
      )
      .fold(problems => fail(problems.mkString("; ")), identity)
      .collect { case Action.EnsureKafkaUser(u) => u }
      .head

  private val players = GrantedTopic("spinvibe", "casino.players", GrantedTopic.Consume)
  private val ownEntries = Vector(
    AclRule(AclResource("topic", "affiliates-hub.", "prefix"), Vector("Read", "Write", "Describe")),
    AclRule(AclResource("group", "ankka.affiliates-hub.attribution.", "prefix"), Vector("Read"))
  )

  test("a consume grant adds exactly one literal entry to read and describe the topic") {
    assertEquals(
      user(Vector(players)).getSpec.authorization.acls,
      ownEntries :+ AclRule(
        AclResource("topic", "spinvibe.casino.players", "literal"),
        Vector("Read", "Describe")
      )
    )
  }

  test("a produce grant writes and describes; both rights are two entries; no group, no prefix") {
    val produce = GrantedTopic("spinvibe", "casino.players", GrantedTopic.Produce)
    val acls    = user(Vector(produce, players)).getSpec.authorization.acls
    assertEquals(
      acls.drop(2),
      Vector(
        AclRule(
          AclResource("topic", "spinvibe.casino.players", "literal"),
          Vector("Read", "Describe")
        ),
        AclRule(
          AclResource("topic", "spinvibe.casino.players", "literal"),
          Vector("Write", "Describe")
        )
      )
    )
    assert(!acls.drop(2).exists(_.resource.`type` == "group"), acls.toString)
    assert(!acls.drop(2).exists(_.resource.patternType == "prefix"), acls.toString)
  }

  test("with no grants the user is what it was before grants existed") {
    assertEquals(user(Vector.empty).getSpec.authorization.acls, ownEntries)
    assertEquals(user(Vector.empty).getSpec.quotas, None)
  }

  test("a user's spec says authentication and quotas as Strimzi reads them, or not at all") {
    val serialization = AnkkaSerialization()
    val service       = serialization.asJson(KafkaUserSpec())
    assert(service.contains("\"tls-external\""), service)
    assert(!service.contains("quotas"), service)
    val machine = serialization.asJson(
      KafkaUserSpec(
        authentication = None,
        quotas = Some(KafkaUserQuotas(Some(1048576L), Some(2097152L), Some(25)))
      )
    )
    assert(!machine.contains("authentication"), machine)
    assert(
      machine.contains("\"producerByteRate\":1048576") &&
        machine.contains("\"consumerByteRate\":2097152") &&
        machine.contains("\"requestPercentage\":25"),
      machine
    )
    assertEquals(
      serialization.unmarshal(machine, classOf[KafkaUserSpec]).quotas.flatMap(_.producerByteRate),
      Some(1048576L)
    )
  }

  // ── which grants name a service ───────────────────────────────────────────

  private def grant(grantee: String, topic: String, right: String, kind: String = "topic") =
    ProjectGrantEntry(
      id = s"g-$topic-$right",
      grantee = grantee,
      kind = kind,
      topic = Option.when(kind == "topic")(topic),
      right = Option.when(kind == "topic")(right),
      service = Option.when(kind == "route")("wallet"),
      grantedAt = "2026-10-09T00:00:00Z"
    )

  private val projects = Vector(
    AnkkaProjectSpec(
      projectId = "spinvibe",
      grants = List(
        grant("service:affiliates-hub/attribution", "casino.players", "consume"),
        grant("service:affiliates-hub/other", "casino.players", "consume"),
        grant("service:affiliates-hub/attribution", "x", "consume", kind = "route")
      )
    ),
    AnkkaProjectSpec(
      projectId = "payments",
      grants = List(grant("service:affiliates-hub/attribution", "deposits", "produce"))
    ),
    // A project's grant to its own service adds nothing its prefix entry does not cover.
    AnkkaProjectSpec(
      projectId = "affiliates-hub",
      grants = List(grant("service:affiliates-hub/attribution", "clicks", "consume"))
    )
  )

  test(
    "the grants naming a service are every project's accepted topic grants to it, and no other"
  ) {
    assertEquals(
      GrantedTopic.naming(projects, "affiliates-hub", "attribution"),
      Vector(
        GrantedTopic("payments", "deposits", "produce"),
        GrantedTopic("spinvibe", "casino.players", "consume")
      )
    )
    assertEquals(GrantedTopic.naming(projects, "affiliates-hub", "nobody"), Vector.empty)
  }

  test("a grantee word names the service a project change must reconcile, or nothing") {
    assertEquals(
      Operator.granteeService("service:affiliates-hub/attribution"),
      Some(("affiliates-hub", "attribution"))
    )
    assertEquals(Operator.granteeService("machine:eitheror/network"), None)
    assertEquals(Operator.granteeService("service:a/b/c"), None)
    assertEquals(Operator.granteeService("service:a/"), None)
  }
