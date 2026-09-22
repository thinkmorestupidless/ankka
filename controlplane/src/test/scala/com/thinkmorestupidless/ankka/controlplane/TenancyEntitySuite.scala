package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{CreateProject, Invite, Role}
import com.thinkmorestupidless.ankka.controlplane.application.{OrganizationEntity, ProjectEntity}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  Actor,
  Attribution,
  ChangeRole,
  ClaimInvitation
}
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** Organizations and projects: the tenancy rules each entity can enforce on its own. */
class TenancyEntitySuite extends munit.FunSuite:

  private def organization = EventSourcedTestKit.of(OrganizationEntity, "acme")
  private def project      = EventSourcedTestKit.of(ProjectEntity, "checkout")

  test("creating an organization persists one event") {
    val kit    = organization
    val result = kit.call(OrganizationEntity.createOrganization)("Acme Corp")

    assertEquals(result.replyValue, Done)
    assertEquals(result.events, Vector(OrganizationCreated("Acme Corp")))
    assertEquals(kit.call(OrganizationEntity.get).replyValue.name, "Acme Corp")
    assertEquals(kit.call(OrganizationEntity.get).replyValue.id, "acme")
  }

  test("a command's attribution is recorded on the event it produces (feature 008)") {
    val at      = java.time.Instant.parse("2026-09-22T10:00:00Z")
    val by      = Attribution(Actor("alice", Some("alice@example.test")), at)
    val kit     = organization
    val created = kit.call(OrganizationEntity.createOrganization, by.metadata)("Acme Corp")
    assertEquals(created.events, Vector(OrganizationCreated("Acme Corp", Some(by.actor), Some(at))))
    // Unattributed calls still work and say so — that is how pre-feature events read too.
    val renamed = kit.call(OrganizationEntity.rename)("Acme Limited")
    assertEquals(renamed.events, Vector(OrganizationRenamed("Acme Limited", None, None)))
  }

  // ── membership (feature 008) ──────────────────────────────────────────────

  private val now   = java.time.Instant.parse("2026-09-22T10:00:00Z")
  private val alice = Attribution(Actor("alice", Some("alice@example.test")), now)
  private val bob   = Attribution(Actor("bob", Some("bob@example.test")), now)
  private val carol =
    Attribution(Actor("carol", Some("carol@example.test"), administrative = true), now)

  private def acme =
    val kit = organization
    val _   = kit.call(OrganizationEntity.createOrganization, alice.metadata)("Acme Corp")
    kit

  test("the creator is the first owner; an unattributed create has no owner (pre-feature)") {
    val kit = acme
    assertEquals(kit.call(OrganizationEntity.roleOf)("alice").replyValue.role, Some(Role.Owner))
    assertEquals(kit.call(OrganizationEntity.roleOf)("bob").replyValue.role, None)
    val old = organization
    val _   = old.call(OrganizationEntity.createOrganization)("Old Corp")
    assertEquals(old.call(OrganizationEntity.members).replyValue.members, Vector.empty)
  }

  test("invite, claim with a verified email, and the invitation is spent") {
    val kit = acme
    assertEquals(
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("Bob@Example.test")).replyValue,
      Done
    )
    assertEquals(
      kit.call(OrganizationEntity.pendingFor)("bob@example.test").replyValue,
      Some(Role.Member)
    )
    assertEquals(
      kit.call(OrganizationEntity.members).replyValue.invitations.map(_.email),
      Vector("bob@example.test")
    )
    // The endpoint has checked the email is verified; the entity spends the invitation on the subject.
    val claimed = kit.call(OrganizationEntity.claimInvitation, bob.metadata)(
      ClaimInvitation("bob", "bob@example.test", Some("bob@example.test"))
    )
    assertEquals(claimed.replyValue, Some(Role.Member))
    assertEquals(kit.call(OrganizationEntity.roleOf)("bob").replyValue.role, Some(Role.Member))
    assertEquals(kit.call(OrganizationEntity.pendingFor)("bob@example.test").replyValue, None)
    val members = kit.call(OrganizationEntity.members).replyValue
    assertEquals(members.invitations, Vector.empty)
    assertEquals(
      members.members.find(_.subject == "bob").flatMap(_.addedBy),
      Some("alice@example.test"),
      "who invited them"
    )
  }

  test(
    "inviting a member or an already invited email conflicts; a revoked invitation cannot be claimed"
  ) {
    val kit = acme
    assertEquals(
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("bob@example.test")).replyValue,
      Done
    )
    assertEquals(
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("bob@example.test")).error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("alice@example.test")).error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("not-an-email")).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      kit.call(OrganizationEntity.revokeInvitation, alice.metadata)("bob@example.test").replyValue,
      Done
    )
    assertEquals(
      kit
        .call(OrganizationEntity.claimInvitation, bob.metadata)(
          ClaimInvitation("bob", "bob@example.test")
        )
        .replyValue,
      None
    )
    assertEquals(kit.call(OrganizationEntity.roleOf)("bob").replyValue.role, None)
  }

  test("the last owner cannot be removed or demoted; with two owners, either can go") {
    val kit = acme
    assertEquals(
      kit.call(OrganizationEntity.removeMember, alice.metadata)("alice").error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      kit
        .call(OrganizationEntity.changeRole, alice.metadata)(ChangeRole("alice", Role.Member))
        .error
        .code,
      ErrorCode.Conflict
    )
    val _ =
      kit.call(OrganizationEntity.invite, alice.metadata)(Invite("bob@example.test", Role.Owner))
    val _ = kit.call(OrganizationEntity.claimInvitation, bob.metadata)(
      ClaimInvitation("bob", "bob@example.test")
    )
    assertEquals(
      kit
        .call(OrganizationEntity.changeRole, alice.metadata)(ChangeRole("alice", Role.Member))
        .replyValue,
      Done
    )
    assertEquals(kit.call(OrganizationEntity.roleOf)("alice").replyValue.role, Some(Role.Member))
    assertEquals(
      kit.call(OrganizationEntity.removeMember, bob.metadata)("bob").error.code,
      ErrorCode.Conflict,
      "bob is now the last owner"
    )
    assertEquals(kit.call(OrganizationEntity.removeMember, bob.metadata)("alice").replyValue, Done)
    assertEquals(kit.call(OrganizationEntity.roleOf)("alice").replyValue.role, None)
    assertEquals(
      kit.call(OrganizationEntity.removeMember, bob.metadata)("nobody").error.code,
      ErrorCode.NotFound
    )
  }

  test(
    "a disabled organization refuses every change but repair, enable and delete, and keeps its people"
  ) {
    val kit = acme
    val _   = kit.call(OrganizationEntity.invite, alice.metadata)(Invite("bob@example.test"))
    assertEquals(kit.call(OrganizationEntity.disable, carol.metadata).replyValue, Done)
    assertEquals(
      kit.call(OrganizationEntity.disable, carol.metadata).error.code,
      ErrorCode.Conflict
    )
    val answer = kit.call(OrganizationEntity.roleOf)("alice").replyValue
    assert(answer.disabled && answer.role.contains(Role.Owner))
    for refused <- Vector(
        kit.call(OrganizationEntity.rename, alice.metadata)("Acme Ltd").error,
        kit.call(OrganizationEntity.invite, alice.metadata)(Invite("dan@example.test")).error,
        kit.call(OrganizationEntity.revokeInvitation, alice.metadata)("bob@example.test").error,
        kit.call(OrganizationEntity.removeMember, alice.metadata)("alice").error
      )
    do
      assertEquals(refused.code, ErrorCode.Conflict)
      assert(refused.message.contains("is disabled"), refused.message)
    assertEquals(
      kit
        .call(OrganizationEntity.claimInvitation, bob.metadata)(
          ClaimInvitation("bob", "bob@example.test")
        )
        .replyValue,
      None,
      "nothing joins a disabled organization"
    )
    assertEquals(
      kit.call(OrganizationEntity.members).replyValue.invitations.map(_.email),
      Vector("bob@example.test"),
      "nothing is revoked either"
    )
    assertEquals(
      kit
        .call(OrganizationEntity.addMember, carol.metadata)(
          com.thinkmorestupidless.ankka.controlplane.domain.AddMember("erin", Role.Owner)
        )
        .replyValue,
      Done,
      "repair is allowed"
    )
    assertEquals(kit.call(OrganizationEntity.enable, carol.metadata).replyValue, Done)
    assertEquals(kit.call(OrganizationEntity.rename, alice.metadata)("Acme Ltd").replyValue, Done)
    assertEquals(kit.call(OrganizationEntity.enable, carol.metadata).error.code, ErrorCode.Conflict)
  }

  test("deleting an organization lets go of its members and invitations") {
    val kit = acme
    val _   = kit.call(OrganizationEntity.invite, alice.metadata)(Invite("bob@example.test"))
    val _   = kit.call(OrganizationEntity.delete, alice.metadata)
    assertEquals(
      kit.call(OrganizationEntity.roleOf)("alice").replyValue,
      com.thinkmorestupidless.ankka.controlplane.domain
        .MembershipAnswer(None, exists = false, disabled = false)
    )
    assertEquals(kit.call(OrganizationEntity.pendingFor)("bob@example.test").replyValue, None)
  }

  test("creating the same organization twice conflicts") {
    val kit = organization
    val _   = kit.call(OrganizationEntity.createOrganization)("Acme Corp")

    val again = kit.call(OrganizationEntity.createOrganization)("Acme Corp")
    assertEquals(again.error.code, ErrorCode.Conflict)
    assertEquals(again.events, Vector.empty)
  }

  test("an empty organization name is refused") {
    val result = organization.call(OrganizationEntity.createOrganization)("")
    assertEquals(result.error.code, ErrorCode.BadRequest)
  }

  test("renaming and reading back") {
    val kit = organization
    val _   = kit.call(OrganizationEntity.createOrganization)("Acme Corp")
    val _   = kit.call(OrganizationEntity.rename)("Acme Limited")
    assertEquals(kit.call(OrganizationEntity.get).replyValue.name, "Acme Limited")
  }

  test("renaming an organization that does not exist is a 404") {
    assertEquals(organization.call(OrganizationEntity.rename)("x").error.code, ErrorCode.NotFound)
  }

  test("deleting an organization is a tombstone, so the id is not reusable") {
    val kit = organization
    val _   = kit.call(OrganizationEntity.createOrganization)("Acme Corp")
    val _   = kit.call(OrganizationEntity.delete)

    assert(!kit.isDeleted, "the journal is the audit trail")
    assertEquals(kit.call(OrganizationEntity.exists).replyValue, false)
    assertEquals(kit.call(OrganizationEntity.get).error.code, ErrorCode.NotFound)

    // A tenancy boundary that quietly came back with someone else's name would be worse
    // than an error.
    val recreated = kit.call(OrganizationEntity.createOrganization)("Someone Else")
    assertEquals(recreated.error.code, ErrorCode.Conflict)
    assert(recreated.errorMessage.contains("not reused"), recreated.errorMessage)
  }

  test("exists answers without failing on an unknown organization") {
    assertEquals(organization.call(OrganizationEntity.exists).replyValue, false)
  }

  test("creating a project records its organization") {
    val kit    = project
    val result = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))

    assertEquals(result.events, Vector(ProjectCreated("Checkout", "acme")))
    val detail = kit.call(ProjectEntity.get).replyValue
    assertEquals(detail.id, "checkout")
    assertEquals(detail.name, "Checkout")
    assertEquals(detail.organizationId, "acme")
  }

  test("a project needs both a name and an organization") {
    assertEquals(
      project.call(ProjectEntity.createProject)(CreateProject("", "acme")).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      project.call(ProjectEntity.createProject)(CreateProject("Checkout", "")).error.code,
      ErrorCode.BadRequest
    )
  }

  test("creating the same project twice conflicts") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    assertEquals(
      kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme")).error.code,
      ErrorCode.Conflict
    )
  }

  test("project state is rebuilt purely by folding events") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    val _   = kit.call(ProjectEntity.rename)("Checkout v2")
    val _   = kit.call(ProjectEntity.delete)

    assertEquals(
      kit.allEvents,
      Vector(ProjectCreated("Checkout", "acme"), ProjectRenamed("Checkout v2"), ProjectDeleted())
    )
    assertEquals(kit.call(ProjectEntity.exists).replyValue, false)
  }
