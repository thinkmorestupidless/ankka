package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaMachineSpec,
  AnkkaMachineStatus,
  AnkkaProjectSpec,
  ProjectGrantEntry
}
import com.thinkmorestupidless.ankka.operator.strimzi.{AclResource, AclRule, KafkaUserQuotas}

/**
 * A registered machine's user on the installation's broker (feature 040): no authentication of its
 * own, exactly its accepted grants' topics, its own group prefix, and its byte rates clamped to the
 * installation's ceiling.
 */
class MachineRenderingSuite extends munit.FunSuite:

  private val broker   = BrokerStack.settings
  private val defaults = MachineDefaults()
  private val spec     = AnkkaMachineSpec("affiliates", "network")

  private def grant(grantee: String, topic: String, right: String) =
    ProjectGrantEntry(
      id = s"$topic-$right",
      grantee = grantee,
      kind = "topic",
      topic = Some(topic),
      right = Some(right)
    )

  private val projects = Vector(
    AnkkaProjectSpec(
      projectId = "spinvibe",
      grants = List(
        grant("machine:affiliates/network", "affiliates.attribution", "consume"),
        grant("machine:affiliates/other", "affiliates.attribution", "consume"),
        grant("service:affiliates/network", "casino.players", "consume")
      )
    )
  )

  private val group =
    AclRule(AclResource("group", "ankka.machine.affiliates.network.", "prefix"), Vector("Read"))

  test(
    "the user is named for the machine, has no authentication, and reads its granted topics and its own groups"
  ) {
    val granted = MachineRendering.granted(projects, "affiliates", "network")
    assertEquals(granted, Vector(GrantedTopic("spinvibe", "affiliates.attribution", "consume")))
    val user = MachineRendering.user("affiliates", "network", Some(spec), granted, defaults, broker)
    assertEquals(user.getMetadata.getName, "machine.affiliates.network")
    assertEquals(user.getMetadata.getNamespace, broker.namespace)
    assertEquals(user.getSpec.authentication, None)
    assertEquals(
      user.getSpec.authorization.acls,
      Vector(
        AclRule(
          AclResource("topic", "spinvibe.affiliates.attribution", "literal"),
          Vector("Read", "Describe")
        ),
        group
      )
    )
  }

  test("byte rates are the machine's, else the installation's defaults, clamped to the ceiling") {
    val defaulted =
      MachineRendering.user("affiliates", "network", Some(spec), Vector.empty, defaults, broker)
    assertEquals(
      defaulted.getSpec.quotas,
      Some(KafkaUserQuotas(Some(1048576L), Some(4194304L), Some(50)))
    )
    val own = spec.copy(
      produceBytesPerSecond = Some(2048L),
      consumeBytesPerSecond = Some(999999999999L),
      requestPercentage = Some(20)
    )
    assertEquals(
      MachineRendering
        .user("affiliates", "network", Some(own), Vector.empty, defaults, broker)
        .getSpec
        .quotas,
      Some(KafkaUserQuotas(Some(2048L), Some(33554432L), Some(20)))
    )
  }

  test("a deleted machine keeps a user that reaches no topic, only its own groups") {
    val granted = MachineRendering.granted(projects, "affiliates", "network")
    val gone    = MachineRendering.user("affiliates", "network", None, granted, defaults, broker)
    assertEquals(gone.getSpec.authorization.acls, Vector(group))
  }

  test(
    "a pass renders the user, and the status only when the machine is there and it says something new"
  ) {
    val ready = BrokerObservation(StrimziObjectState(exists = true, ready = Some(true)))
    val pass =
      MachineReconciler.actions(
        "affiliates",
        "network",
        Some(spec),
        Vector.empty,
        defaults,
        broker,
        ready,
        None
      )
    assertEquals(
      pass.collect { case Action.SetMachineStatus(n, s) => (n, s) },
      Vector(
        "affiliates.network" -> AnkkaMachineStatus(
          Some("machine.affiliates.network"),
          "Provisioned"
        )
      )
    )
    val again = MachineReconciler.actions(
      "affiliates",
      "network",
      Some(spec),
      Vector.empty,
      defaults,
      broker,
      ready,
      Some(AnkkaMachineStatus(Some("machine.affiliates.network"), "Provisioned"))
    )
    assertEquals(again.collect { case a: Action.SetMachineStatus => a }, Vector.empty)
    val deleted =
      MachineReconciler.actions(
        "affiliates",
        "network",
        None,
        Vector.empty,
        defaults,
        broker,
        ready,
        None
      )
    assertEquals(deleted.map(_.getClass.getSimpleName), Vector("EnsureKafkaUser"))
  }

  test(
    "a machine's resource name is read back into its organization and name, and a grantee word likewise"
  ) {
    assertEquals(MachineReconciler.parse("affiliates.network"), Some(("affiliates", "network")))
    assertEquals(MachineReconciler.parse("nodot"), None)
    assertEquals(MachineReconciler.parse("a.b.c"), None)
    assertEquals(
      Operator.granteeMachine("machine:affiliates/network"),
      Some(("affiliates", "network"))
    )
    assertEquals(Operator.granteeMachine("service:affiliates/network"), None)
  }
