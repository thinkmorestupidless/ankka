package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  GrantChange,
  GrantState,
  GrantTarget,
  Grantee,
  ProjectDetail
}
import com.thinkmorestupidless.ankka.controlplane.application.{
  GrantMirror,
  MachineEntity,
  OrganizationEntity,
  ProjectEntity
}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{CommandError, Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}

/**
 * The grantee side's copy of a grant is derived from the granting project's events, one change at a
 * time, on the right entity, with the actor who made the change — and a redelivery writes nothing.
 */
class GrantMirrorSuite extends munit.FunSuite with LogCapturing:

  private val now      = java.time.Instant.parse("2026-10-08T10:00:00Z")
  private val ada      = Actor("ada", Some("ada@eitheror.test"))
  private val bo       = Actor("bo", Some("bo@affiliates.test"))
  private val merchant = Grantee.Service("payments", "merchant")
  private val network  = Grantee.Machine("affiliates", "network")
  private val route =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")
  private val topic = GrantTarget.topic("affiliates.attribution", GrantTarget.Consume)

  private def transport(
      held: Vector[Grant] = Vector.empty,
      conflict: Boolean = false,
      machineDeleted: Option[Deletion] = None
  ) =
    RecordingTransport()
      .answer(ProjectEntity.get)(id => ProjectDetail(id, id, "eitheror"))
      .answer(ProjectEntity.deletion)(_ => None)
      .answer(MachineEntity.deletion)(_ => machineDeleted)
      .answer(ProjectEntity.lapseGrant)((_, _) => Done)
      .answer(ProjectEntity.grants)(_ => held)
      .answer(ProjectEntity.recordGrantChange) { (_, _) =>
        if conflict then throw CommandError("already", ErrorCode.Conflict) else Done
      }
      .answer(OrganizationEntity.recordGrantChange) { (_, _) =>
        if conflict then throw CommandError("already", ErrorCode.Conflict) else Done
      }

  private def records(t: RecordingTransport) =
    t.recorded.filter(_.method == "record-grant-change")

  test("a grant within one organization is recorded as made, on the grantee's project") {
    val t   = transport()
    val kit = ConsumerTestKit.of(GrantMirror, t.client)
    val _ = kit.onMessage(
      GrantMade("g1", merchant, route, pending = false, Some(ada), Some(now)),
      "spinvibe"
    )
    val call = records(t).loneElement
    assertEquals((call.componentId, call.entityId), ("project", "payments"))
    assertEquals(
      call.input,
      RecordGrantChange("g1", "spinvibe", "eitheror", merchant, route, GrantChange.Made)
    )
    assertEquals(Attribution.from(call.metadata).map(_.actor), Some(ada))
  }

  test("a pending grant to a machine is recorded as offered, on the machine's organization") {
    val t = transport()
    val _ = ConsumerTestKit
      .of(GrantMirror, t.client)
      .onMessage(GrantMade("g1", network, topic, pending = true, Some(ada), Some(now)), "spinvibe")
    val call = records(t).loneElement
    assertEquals((call.componentId, call.entityId), ("organization", "affiliates"))
    assertEquals(call.input.asInstanceOf[RecordGrantChange].change, GrantChange.Offered)
  }

  test("a grant made to a machine deleted before it was recorded lapses, under who deleted it") {
    // The deletion's trigger read the organization's side before this grant reached it.
    val t = transport(machineDeleted = Some(Deletion(Some(bo), Some(now))))
    val _ = ConsumerTestKit
      .of(GrantMirror, t.client)
      .onMessage(GrantMade("g1", network, topic, pending = true, Some(ada), Some(now)), "spinvibe")
    val lapse = t.recorded.filter(_.method == "lapse-grant").loneElement
    assertEquals((lapse.componentId, lapse.entityId, lapse.input), ("project", "spinvibe", "g1"))
    assertEquals(Attribution.from(lapse.metadata).map(_.actor), Some(bo))
  }

  test("a grant to a grantee that still exists is not lapsed by the mirror") {
    val t = transport()
    val _ = ConsumerTestKit
      .of(GrantMirror, t.client)
      .onMessage(GrantMade("g1", network, topic, pending = true, Some(ada), Some(now)), "spinvibe")
    assertEquals(t.recorded.filter(_.method == "lapse-grant"), Vector.empty)
  }

  test(
    "each later change is recorded with its own actor, the grantee and target read from the project"
  ) {
    val held = Vector(Grant("g1", network, topic, GrantState.Accepted))
    for (event, change, actor) <- Vector(
        (GrantAccepted("g1", Some(bo), Some(now)), GrantChange.Accepted, bo),
        (GrantDeclined("g1", Some(bo), Some(now)), GrantChange.Declined, bo),
        (GrantWithdrawn("g1", Some(ada), Some(now)), GrantChange.Withdrawn, ada),
        (GrantRevoked("g1", Some(ada), Some(now)), GrantChange.Revoked, ada),
        (GrantRelinquished("g1", Some(bo), Some(now)), GrantChange.Relinquished, bo),
        (GrantLapsed("g1", Some(bo), Some(now)), GrantChange.Lapsed, bo)
      )
    do
      val t    = transport(held)
      val _    = ConsumerTestKit.of(GrantMirror, t.client).onMessage(event, "spinvibe")
      val call = records(t).loneElement
      assertEquals(call.entityId, "affiliates", event.toString)
      assertEquals(
        call.input,
        RecordGrantChange("g1", "spinvibe", "eitheror", network, topic, change),
        event.toString
      )
      assertEquals(Attribution.from(call.metadata).map(_.actor), Some(actor), event.toString)
  }

  test("a change already recorded is done: the redelivery completes and records nothing new") {
    val t = transport(conflict = true)
    val result = ConsumerTestKit
      .of(GrantMirror, t.client)
      .onMessage(
        GrantMade("g1", merchant, route, pending = false, Some(ada), Some(now)),
        "spinvibe"
      )
    assert(result.messages.isEmpty)
  }

  test("an event that is not a grant's records nothing") {
    val t = transport()
    val _ = ConsumerTestKit.of(GrantMirror, t.client).onMessage(ProjectRenamed("Spin"), "spinvibe")
    assertEquals(records(t), Vector.empty)
  }

  extension [A](values: Vector[A])
    private def loneElement: A =
      assertEquals(values.size, 1, values.toString)
      values.head
