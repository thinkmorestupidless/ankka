package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  CreateProject,
  GrantChange,
  GrantState,
  GrantTarget,
  Grantee
}
import com.thinkmorestupidless.ankka.controlplane.application.{OrganizationEntity, ProjectEntity}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

/**
 * A grant on the project that makes it (feature 040): the rules the project can enforce on its own
 * state, and the seven states a grant passes through. Which organization the grantee belongs to,
 * and so whether a grant waits, is the endpoint's to say; here it arrives as `pending`.
 */
class GrantEntitySuite extends munit.FunSuite with LogCapturing:

  private val now      = java.time.Instant.parse("2026-10-08T10:00:00Z")
  private val ada      = Attribution(Actor("ada", Some("ada@eitheror.test")), now)
  private val bo       = Attribution(Actor("bo", Some("bo@affiliates.test")), now.plusSeconds(60))
  private val merchant = Grantee.Service("payments", "merchant")
  private val network  = Grantee.Machine("affiliates", "network")
  private val deposits =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")
  private val attribution = GrantTarget.topic("affiliates.attribution", GrantTarget.Consume)

  private def spinvibe =
    val kit = EventSourcedTestKit.of(ProjectEntity, "spinvibe")
    val _ =
      kit.call(ProjectEntity.createProject, ada.metadata)(CreateProject("Spinvibe", "eitheror"))
    val _ = kit.call(ProjectEntity.declareTopic, ada.metadata)(
      DeclareTopic("affiliates.attribution", 3)
    )
    kit

  private def make(
      kit: EventSourcedTestKit[ProjectEntity, Project, ProjectEvent],
      id: String,
      grantee: Grantee,
      target: GrantTarget,
      pending: Boolean
  ) = kit.call(ProjectEntity.makeGrant, ada.metadata)(MakeGrant(id, grantee, target, pending))

  test("a grant within the organization is accepted when it is made, attributed to its owner") {
    val kit    = spinvibe
    val result = make(kit, "g1", merchant, deposits, pending = false)
    assertEquals(
      result.events,
      Vector(GrantMade("g1", merchant, deposits, pending = false, Some(ada.actor), Some(now)))
    )
    assertEquals(result.replyValue.state, GrantState.Accepted)
    assertEquals(result.replyValue.granted.actor, Some(ada.actor))
  }

  test("a grant to another organization's grantee is pending, and opens nothing until answered") {
    val kit    = spinvibe
    val result = make(kit, "g1", network, attribution, pending = true)
    assertEquals(result.replyValue.state, GrantState.Pending)
    assertEquals(kit.call(ProjectEntity.grants).replyValue.map(_.state), Vector(GrantState.Pending))
  }

  test("the same live grant made twice is one grant: the second persists nothing") {
    val kit    = spinvibe
    val first  = make(kit, "g1", merchant, deposits, pending = false)
    val second = make(kit, "g2", merchant, deposits, pending = false)
    assertEquals(second.events, Vector.empty)
    assertEquals(second.replyValue.id, "g1")
    assertEquals(kit.call(ProjectEntity.grants).replyValue.map(_.id), Vector(first.replyValue.id))
  }

  test("an ended grant is never reopened: granting again makes a new grant with its own history") {
    val kit = spinvibe
    val _   = make(kit, "g1", merchant, deposits, pending = false)
    assertEquals(kit.call(ProjectEntity.revokeGrant, ada.metadata)("g1").replyValue, Done)
    val again = make(kit, "g2", merchant, deposits, pending = false)
    assertEquals(again.replyValue.id, "g2")
    val states = kit.call(ProjectEntity.grants).replyValue.map(g => g.id -> g.state).toMap
    assertEquals(states, Map("g1" -> GrantState.Revoked, "g2" -> GrantState.Accepted))
  }

  test("a pending grant is accepted or declined, once, by whoever the endpoint let through") {
    val kit      = spinvibe
    val _        = make(kit, "g1", network, attribution, pending = true)
    val accepted = kit.call(ProjectEntity.acceptGrant, bo.metadata)("g1")
    assertEquals(accepted.events, Vector(GrantAccepted("g1", Some(bo.actor), Some(bo.at))))
    assertEquals(
      kit.call(ProjectEntity.acceptGrant, bo.metadata)("g1").error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(ProjectEntity.declineGrant, bo.metadata)("g1").error.code,
      ErrorCode.Conflict
    )

    val _ = make(
      kit,
      "g2",
      network,
      GrantTarget.topic("affiliates.attribution", "produce"),
      pending = true
    )
    val declined = kit.call(ProjectEntity.declineGrant, bo.metadata)("g2")
    assertEquals(declined.events, Vector(GrantDeclined("g2", Some(bo.actor), Some(bo.at))))
    val g2 = kit.call(ProjectEntity.grants).replyValue.find(_.id == "g2").get
    assertEquals(g2.state, GrantState.Declined)
    assertEquals(g2.answered.flatMap(_.actor), Some(bo.actor))
    assertEquals(g2.ended.flatMap(_.actor), Some(bo.actor))
  }

  test("withdraw applies to a pending grant; revoke and relinquish to an accepted one") {
    val kit = spinvibe
    val _   = make(kit, "p", network, attribution, pending = true)
    val _   = make(kit, "a", merchant, deposits, pending = false)
    assertEquals(
      kit.call(ProjectEntity.revokeGrant, ada.metadata)("p").error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(ProjectEntity.relinquishGrant, bo.metadata)("p").error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(ProjectEntity.withdrawGrant, ada.metadata)("a").error.code,
      ErrorCode.Conflict
    )
    assert(
      kit
        .call(ProjectEntity.withdrawGrant, ada.metadata)("a")
        .error
        .message
        .contains("is accepted"),
      "the refusal names the state the grant is in"
    )
    assertEquals(
      kit.call(ProjectEntity.withdrawGrant, ada.metadata)("p").events.map(_.getClass),
      Vector(classOf[GrantWithdrawn])
    )
    assertEquals(
      kit.call(ProjectEntity.relinquishGrant, bo.metadata)("a").events.map(_.getClass),
      Vector(classOf[GrantRelinquished])
    )
    assertEquals(
      kit.call(ProjectEntity.revokeGrant, ada.metadata)("a").error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(ProjectEntity.revokeGrant, ada.metadata)("missing").error.code,
      ErrorCode.NotFound
    )
  }

  test("a grantee's deletion lapses a live grant, and lapsing an ended one records nothing") {
    val kit    = spinvibe
    val _      = make(kit, "p", network, attribution, pending = true)
    val _      = make(kit, "a", merchant, deposits, pending = false)
    val _      = kit.call(ProjectEntity.revokeGrant, ada.metadata)("a")
    val lapsed = kit.call(ProjectEntity.lapseGrant, bo.metadata)("p")
    assertEquals(lapsed.events, Vector(GrantLapsed("p", Some(bo.actor), Some(bo.at))))
    assertEquals(kit.call(ProjectEntity.lapseGrant, bo.metadata)("p").events, Vector.empty)
    assertEquals(kit.call(ProjectEntity.lapseGrant, bo.metadata)("a").events, Vector.empty)
    val states = kit.call(ProjectEntity.grants).replyValue.map(g => g.id -> g.state).toMap
    assertEquals(states, Map("p" -> GrantState.Lapsed, "a" -> GrantState.Revoked))
  }

  test("a topic the project has not declared cannot be granted, and the refusal says so") {
    val kit = spinvibe
    val result =
      make(kit, "g1", merchant, GrantTarget.topic("casino.sessions", "consume"), pending = false)
    assertEquals(result.error.code, ErrorCode.NotFound)
    assert(
      result.error.message.contains("'spinvibe' has not declared the topic 'casino.sessions'"),
      result.error.message
    )
    assertEquals(result.events, Vector.empty)
  }

  test("a project's own services need no grant") {
    val result =
      make(spinvibe, "g1", Grantee.Service("spinvibe", "lobby"), deposits, pending = false)
    assertEquals(result.error.code, ErrorCode.BadRequest)
  }

  test("a grant's target and grantee are checked here too, whoever sent the command") {
    val kit = spinvibe
    assertEquals(
      make(kit, "g1", merchant, deposits.copy(method = Some("FETCH")), pending = false).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      make(kit, "g1", Grantee.Machine("acme.inc", "network"), deposits, pending = true).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      make(
        kit,
        "g1",
        merchant,
        GrantTarget.topic("affiliates.attribution", "produce", decrypt = true),
        pending = false
      ).error.code,
      ErrorCode.BadRequest
    )
  }

  test("a grant that allows decryption, and the erasure right, are held as any grant is") {
    val kit = spinvibe
    val _ = make(
      kit,
      "d",
      merchant,
      GrantTarget.topic("affiliates.attribution", "consume", decrypt = true),
      pending = false
    )
    val _    = make(kit, "e", merchant, GrantTarget.erasure, pending = false)
    val held = kit.call(ProjectEntity.grants).replyValue.map(g => g.id -> g.target).toMap
    assert(held("d").decrypt)
    assertEquals(held("e").kind, GrantTarget.Erasure)
  }

  test("a deleted project makes no grant") {
    val kit = spinvibe
    val _   = kit.call(ProjectEntity.delete, ada.metadata)
    assertEquals(
      make(kit, "g1", merchant, deposits, pending = false).error.code,
      ErrorCode.NotFound
    )
  }

  // ── the grantee side's record ─────────────────────────────────────────────

  private def record(id: String, change: GrantChange) =
    RecordGrantChange(id, "spinvibe", "eitheror", merchant, deposits, change)

  test("a grantee project records each change once, and a change recorded twice is a conflict") {
    val kit = EventSourcedTestKit.of(ProjectEntity, "payments")
    val _ =
      kit.call(ProjectEntity.createProject, ada.metadata)(CreateProject("Payments", "eitheror"))
    val made =
      kit.call(ProjectEntity.recordGrantChange, ada.metadata)(record("g1", GrantChange.Made))
    assertEquals(made.replyValue, Done)
    assertEquals(
      kit
        .call(ProjectEntity.recordGrantChange, ada.metadata)(record("g1", GrantChange.Made))
        .error
        .code,
      ErrorCode.Conflict
    )
    val _ =
      kit.call(ProjectEntity.recordGrantChange, ada.metadata)(record("g1", GrantChange.Revoked))
    val received = kit.call(ProjectEntity.receivedGrants).replyValue
    assertEquals(received.map(_.state), Vector(GrantState.Revoked))
    assertEquals(received.head.changes.map(_.change), Vector(GrantChange.Made, GrantChange.Revoked))
    assertEquals(received.head.changes.flatMap(_.actor), Vector(ada.actor, ada.actor))
  }

  test("a deleted grantee project still reads what it was granted, so a deletion can lapse it") {
    val kit = EventSourcedTestKit.of(ProjectEntity, "payments")
    val _ =
      kit.call(ProjectEntity.createProject, ada.metadata)(CreateProject("Payments", "eitheror"))
    val _ = kit.call(ProjectEntity.recordGrantChange, ada.metadata)(record("g1", GrantChange.Made))
    val _ = kit.call(ProjectEntity.delete, ada.metadata)
    assertEquals(kit.call(ProjectEntity.receivedGrants).replyValue.map(_.id), Vector("g1"))
  }

  test("an organization records the changes to grants its machines were offered") {
    val kit = EventSourcedTestKit.of(OrganizationEntity, "affiliates")
    val _   = kit.call(OrganizationEntity.createOrganization, bo.metadata)("Affiliates")
    val offered =
      RecordGrantChange("g1", "spinvibe", "eitheror", network, attribution, GrantChange.Offered)
    assertEquals(
      kit.call(OrganizationEntity.recordGrantChange, ada.metadata)(offered).replyValue,
      Done
    )
    val accepted = offered.copy(change = GrantChange.Accepted)
    val _        = kit.call(OrganizationEntity.recordGrantChange, bo.metadata)(accepted)
    val received = kit.call(OrganizationEntity.receivedGrants).replyValue
    assertEquals(received.map(r => r.id -> r.state), Vector("g1" -> GrantState.Accepted))
    assertEquals(received.head.changes.flatMap(_.actor.map(_.subject)), Vector("ada", "bo"))
  }
