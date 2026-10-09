package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.api.{
  CreateProject,
  Invite,
  Owner,
  Quota,
  Role,
  SetProjectLocation,
  Usage
}
import com.thinkmorestupidless.ankka.controlplane.application.{
  DeployTokenEntity,
  OrganizationEntity,
  ProjectEntity
}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  Actor,
  Attribution,
  ChangeRole,
  ClaimInvitation,
  ConfigureRegistry,
  CreateForOwner,
  Organization,
  OrganizationEvent,
  Project,
  ProjectEvent,
  RecordDeployToken,
  RecordService,
  RemoveSecretEntry,
  ReserveService,
  SetQuota,
  SetSecretEntries,
  UsageRecord
}
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent.*
import com.thinkmorestupidless.ankka.controlplane.domain.OrganizationEvent.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** Organizations and projects: the tenancy rules each entity can enforce on its own. */
class TenancyEntitySuite extends munit.FunSuite with LogCapturing:

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

  // ── creating for an owner (feature 011) ───────────────────────────────────

  private val aliceAsOwner =
    Owner("alice", Some("Alice@Example.test"), Some("Alice Example"))

  test("an administrator creates an organization whose only owner is the one named") {
    val kit = organization
    val result = kit.call(OrganizationEntity.createForOwner, carol.metadata)(
      CreateForOwner("Acme Corp", aliceAsOwner)
    )
    assertEquals(result.replyValue, Done)
    assertEquals(
      result.events,
      Vector(OrganizationCreated("Acme Corp", Some(carol.actor), Some(now), Some(aliceAsOwner)))
    )
    val members = kit.call(OrganizationEntity.members).replyValue.members
    assertEquals(members.map(_.subject), Vector("alice"))
    val alone = members.head
    assertEquals(alone.role, Role.Owner)
    assertEquals(alone.email, Some("alice@example.test"), "keyed like every other email")
    assertEquals(alone.display, Some("Alice Example"))
    assertEquals(alone.addedBy, Some("carol"))
    assertEquals(kit.call(OrganizationEntity.roleOf)("carol").replyValue.role, None)
  }

  test("the owner named is who the fold seats, replayed from the events alone") {
    val kit = organization
    val _ = kit.call(OrganizationEntity.createForOwner, carol.metadata)(
      CreateForOwner("Acme Corp", aliceAsOwner)
    )
    val replayed = kit.allEvents.foldLeft(Organization.empty("acme")) {
      case (state, OrganizationCreated(name, actor, at, owner)) =>
        state.onCreated(name, actor, at, owner)
      case (state, _) => state
    }
    assertEquals(replayed, kit.currentState)
  }

  test("an owner named by subject alone is enough, and the member is shown by subject") {
    val kit = organization
    val _ = kit.call(OrganizationEntity.createForOwner, carol.metadata)(
      CreateForOwner("Acme Corp", Owner("alice"))
    )
    val only = kit.call(OrganizationEntity.members).replyValue.members.head
    assertEquals((only.subject, only.email, only.display), ("alice", None, None))
  }

  test("creating for an owner keeps create's refusals, and refuses an empty subject") {
    val known = acme
    assertEquals(
      known
        .call(OrganizationEntity.createForOwner, carol.metadata)(
          CreateForOwner("Again", aliceAsOwner)
        )
        .error
        .code,
      ErrorCode.Conflict
    )
    val deleted = acme
    val _       = deleted.call(OrganizationEntity.delete, alice.metadata)
    assertEquals(
      deleted
        .call(OrganizationEntity.createForOwner, carol.metadata)(
          CreateForOwner("Again", aliceAsOwner)
        )
        .error
        .code,
      ErrorCode.Conflict
    )
    assert(
      organization
        .call(OrganizationEntity.createForOwner, carol.metadata)(CreateForOwner("Acme", Owner(" ")))
        .isError
    )
    assert(
      organization
        .call(OrganizationEntity.createForOwner, carol.metadata)(CreateForOwner("", aliceAsOwner))
        .isError
    )
  }

  test("an administrator who names themselves is the sole owner, as if they named nobody") {
    val kit = organization
    val _ = kit.call(OrganizationEntity.createForOwner, carol.metadata)(
      CreateForOwner("Ops", Owner("carol", Some("carol@example.test")))
    )
    assertEquals(kit.call(OrganizationEntity.roleOf)("carol").replyValue.role, Some(Role.Owner))
    assertEquals(kit.call(OrganizationEntity.members).replyValue.members.size, 1)
  }

  // ── a project's secrets (feature 023) ──────────────────────────────────────────────────────────

  private def withProject =
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    kit

  private def entriesOf(kit: EventSourcedTestKit[ProjectEntity, Project, ProjectEvent]) =
    kit.call(ProjectEntity.secrets).replyValue.map(s => s.name -> s.entries).toMap

  test("a member sets a project secret: the names are recorded, with who set them") {
    val kit = withProject
    val by  = Attribution(Actor("alice", Some("alice@example.test")), now)
    val result = kit.call(ProjectEntity.setSecretEntries, by.metadata)(
      SetSecretEntries("checkout", Vector("STRIPE_KEY"))
    )
    assertEquals(
      result.events,
      Vector(ProjectSecretEntriesSet("checkout", Vector("STRIPE_KEY"), Some(by.actor), Some(now)))
    )
    val listed = kit.call(ProjectEntity.secrets).replyValue
    assertEquals(listed.map(_.setBy), Vector(Some("alice@example.test")))
  }

  test("setting an entry keeps the other entries of the project secret") {
    val kit = withProject
    val _   = kit.call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("A")))
    val _ = kit.call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("B", "A")))
    assertEquals(entriesOf(kit), Map("checkout" -> Vector("A", "B")))
  }

  test("a project secret whose last entry is removed leaves the record, and comes back when set") {
    val kit = withProject
    val _ = kit.call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("A", "B")))
    val _ = kit.call(ProjectEntity.removeSecretEntry)(RemoveSecretEntry("checkout", "B"))
    assertEquals(entriesOf(kit), Map("checkout" -> Vector("A")))
    val _ = kit.call(ProjectEntity.removeSecretEntry)(RemoveSecretEntry("checkout", "A"))
    assertEquals(entriesOf(kit), Map.empty)
    val _ = kit.call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("C")))
    assertEquals(entriesOf(kit), Map("checkout" -> Vector("C")))
  }

  test("removing an entry that was never set is not found and records nothing") {
    val kit    = withProject
    val _      = kit.call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("A")))
    val result = kit.call(ProjectEntity.removeSecretEntry)(RemoveSecretEntry("checkout", "MISSING"))
    assertEquals(result.error.code, ErrorCode.NotFound)
    assertEquals(result.events, Vector.empty)
  }

  test("project secrets of a project that does not exist are not found") {
    val kit = project
    assertEquals(
      kit
        .call(ProjectEntity.setSecretEntries)(SetSecretEntries("checkout", Vector("A")))
        .error
        .code,
      ErrorCode.NotFound
    )
  }

  // ── a project's registry (feature 013) ─────────────────────────────────────────────────────────

  private val credential = ConfigureRegistry("ghcr.io", "octocat", "ankka-registry")

  test("configuring a registry records the server, the user and the secret's name") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    val by  = Attribution(Actor("alice", Some("alice@example.test")), now)

    val result = kit.call(ProjectEntity.configureRegistry, by.metadata)(credential)
    assertEquals(
      result.events,
      Vector(
        RegistryConfigured("ghcr.io", "octocat", "ankka-registry", Some(by.actor), Some(now))
      )
    )

    // And no password anywhere: not in the event, and not in what a reader is told.
    val summary = kit.call(ProjectEntity.get).replyValue.registry
    assertEquals(summary.map(_.server), Some("ghcr.io"))
    assertEquals(summary.map(_.username), Some("octocat"))
    assertEquals(summary.flatMap(_.setBy), Some("alice@example.test"))
    assertEquals(
      kit.call(ProjectEntity.registry).replyValue.map(_.secretName),
      Some("ankka-registry")
    )
  }

  test("a second credential replaces the first rather than accumulating") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    val _   = kit.call(ProjectEntity.configureRegistry)(credential)
    val _ = kit.call(ProjectEntity.configureRegistry)(
      ConfigureRegistry("registry.example.test", "robot", "ankka-registry")
    )
    assertEquals(
      kit.call(ProjectEntity.get).replyValue.registry.map(_.server),
      Some("registry.example.test")
    )
  }

  test("clearing a registry drops the reference; clearing none is a not-found") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    assertEquals(kit.call(ProjectEntity.clearRegistry).error.code, ErrorCode.NotFound)

    val _ = kit.call(ProjectEntity.configureRegistry)(credential)
    assertEquals(kit.call(ProjectEntity.clearRegistry).replyValue, Done)
    assertEquals(kit.call(ProjectEntity.get).replyValue.registry, None)
    assertEquals(kit.call(ProjectEntity.registry).replyValue, None)
  }

  test("a registry cannot be configured on a project that does not exist") {
    assertEquals(
      project.call(ProjectEntity.configureRegistry)(credential).error.code,
      ErrorCode.NotFound
    )
  }

  test("the registry is rebuilt purely by folding events, set and cleared") {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    val _   = kit.call(ProjectEntity.configureRegistry)(credential)
    val _   = kit.call(ProjectEntity.clearRegistry)
    val _   = kit.call(ProjectEntity.configureRegistry)(credential)

    assertEquals(
      kit.allEvents,
      Vector(
        ProjectCreated("Checkout", "acme"),
        RegistryConfigured("ghcr.io", "octocat", "ankka-registry"),
        RegistryCleared(),
        RegistryConfigured("ghcr.io", "octocat", "ankka-registry")
      )
    )
    assertEquals(kit.call(ProjectEntity.registry).replyValue.map(_.server), Some("ghcr.io"))
  }

  // ── deploy tokens (feature 013) ───────────────────────────────────────────

  private def token = EventSourcedTestKit.of(DeployTokenEntity, "3f9a1c2e7b4d8f01")

  private val expiry  = java.time.Instant.parse("2026-12-24T10:00:00Z")
  private val request = RecordDeployToken("acme", "github-deploy", "a" * 64, Some(expiry))

  test("creating a deploy token records everything but the secret") {
    val kit    = token
    val by     = Attribution(Actor("alice", Some("alice@example.test")), now)
    val result = kit.call(DeployTokenEntity.createToken, by.metadata)(request)

    assertEquals(result.replyValue, Done)
    assertEquals(
      result.events,
      Vector(
        DeployTokenCreated(
          "acme",
          "github-deploy",
          "a" * 64,
          Some(expiry),
          Some(by.actor),
          Some(now)
        )
      )
    )

    val detail = kit.call(DeployTokenEntity.get).replyValue
    assertEquals(detail.id, "3f9a1c2e7b4d8f01")
    assertEquals(detail.subject, "token:3f9a1c2e7b4d8f01")
    assertEquals(detail.organizationId, "acme")
    assertEquals(detail.label, "github-deploy")
    assertEquals(detail.createdBy, Some("alice@example.test"))
    assertEquals(detail.expiresAt, Some(expiry))
    assertEquals(detail.lastUsed, None)
  }

  test("a deploy token needs an organization, a label and a digest") {
    assertEquals(
      token.call(DeployTokenEntity.createToken)(request.copy(organizationId = "")).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      token.call(DeployTokenEntity.createToken)(request.copy(label = "")).error.code,
      ErrorCode.BadRequest
    )
    assertEquals(
      token.call(DeployTokenEntity.createToken)(request.copy(digest = "")).error.code,
      ErrorCode.BadRequest
    )
  }

  test("creating the same deploy token twice conflicts") {
    val kit = token
    val _   = kit.call(DeployTokenEntity.createToken)(request)
    assertEquals(
      kit.call(DeployTokenEntity.createToken)(request).error.code,
      ErrorCode.Conflict
    )
  }

  test("a revoked deploy token's id is never reused") {
    val kit = token
    val _   = kit.call(DeployTokenEntity.createToken)(request)
    assertEquals(kit.call(DeployTokenEntity.revoke).replyValue, Done)
    // Revoked, so it no longer exists — and a create must not resurrect it under the same id,
    // or an audit trail could not say which credential made a change.
    val again = kit.call(DeployTokenEntity.createToken)(request)
    assertEquals(again.error.code, ErrorCode.Conflict)
    assert(again.error.message.contains("revoked"), again.error.message)
    assertEquals(kit.call(DeployTokenEntity.get).error.code, ErrorCode.NotFound)
  }

  test("revoking twice is a not-found, as removing a member twice is") {
    val kit = token
    val _   = kit.call(DeployTokenEntity.createToken)(request)
    val _   = kit.call(DeployTokenEntity.revoke)
    assertEquals(kit.call(DeployTokenEntity.revoke).error.code, ErrorCode.NotFound)
  }

  test("a use is recorded once per day, however many nodes report it") {
    val kit   = token
    val day   = java.time.LocalDate.parse("2026-09-25")
    val later = java.time.LocalDate.parse("2026-09-26")
    val _     = kit.call(DeployTokenEntity.createToken)(request)

    assertEquals(kit.call(DeployTokenEntity.recordUse)(day).events, Vector(DeployTokenUsed(day)))

    // A second node reporting the same day, and a late report of an earlier day, both persist
    // nothing: without this a busy token would write to its own journal on every sweep.
    assertEquals(kit.call(DeployTokenEntity.recordUse)(day).events, Vector.empty)
    assertEquals(kit.call(DeployTokenEntity.recordUse)(day.minusDays(1)).events, Vector.empty)
    assertEquals(kit.call(DeployTokenEntity.recordUse)(day).replyValue, Done)

    assertEquals(
      kit.call(DeployTokenEntity.recordUse)(later).events,
      Vector(DeployTokenUsed(later))
    )
    assertEquals(kit.call(DeployTokenEntity.get).replyValue.lastUsed, Some(later))
  }

  test("a use of an unknown or revoked token is a not-found") {
    val day = java.time.LocalDate.parse("2026-09-25")
    assertEquals(token.call(DeployTokenEntity.recordUse)(day).error.code, ErrorCode.NotFound)

    val kit = token
    val _   = kit.call(DeployTokenEntity.createToken)(request)
    val _   = kit.call(DeployTokenEntity.revoke)
    assertEquals(kit.call(DeployTokenEntity.recordUse)(day).error.code, ErrorCode.NotFound)
  }

  test("a token that never expires is distinguishable from one that does") {
    val kit = token
    val _   = kit.call(DeployTokenEntity.createToken)(request.copy(expiresAt = None))
    assertEquals(kit.call(DeployTokenEntity.get).replyValue.expiresAt, None)
  }

  // ── quotas (feature 015) ──────────────────────────────────────────────────

  private type OrganizationKit =
    EventSourcedTestKit[OrganizationEntity, Organization, OrganizationEvent]

  private def usageOf(kit: OrganizationKit) =
    kit.call(OrganizationEntity.get).replyValue.usage

  // The snapshot an endpoint sends is what exists; here that is what the record already says.
  private def quota(kit: OrganizationKit)(quota: Quota) =
    val record = kit.currentState.record
    kit.call(OrganizationEntity.setQuota, carol.metadata)(
      SetQuota(quota, record.projects, record.services)
    )

  test("a quota is set whole, replaced whole, and cleared; none is the default") {
    val kit = acme
    assertEquals(kit.call(OrganizationEntity.get).replyValue.quota, None)
    assertEquals(usageOf(kit), Usage.zero)

    val set = quota(kit)(Quota(projects = Some(2), instances = Some(4)))
    assertEquals(
      set.events,
      Vector(QuotaSet(Quota(Some(2), None, Some(4)), Some(carol.actor), Some(now)))
    )
    assertEquals(
      kit.call(OrganizationEntity.get).replyValue.quota,
      Some(Quota(Some(2), None, Some(4)))
    )

    val _ = quota(kit)(Quota(services = Some(1)))
    assertEquals(
      kit.call(OrganizationEntity.get).replyValue.quota,
      Some(Quota(None, Some(1), None))
    )

    val cleared = kit.call(OrganizationEntity.clearQuota, carol.metadata)
    assertEquals(cleared.events, Vector(QuotaCleared(Some(carol.actor), Some(now))))
    assertEquals(kit.call(OrganizationEntity.get).replyValue.quota, None)
    // Clearing nothing is not an error: a plan change that lifts every limit is idempotent.
    assertEquals(kit.call(OrganizationEntity.clearQuota, carol.metadata).events, Vector.empty)
  }

  test("a negative limit and a quota naming no limit are refused; zero is a limit") {
    val kit = acme
    assertEquals(quota(kit)(Quota(projects = Some(-1))).error.code, ErrorCode.BadRequest)
    assert(quota(kit)(Quota(projects = Some(-1))).errorMessage.contains("cannot be negative"))
    assertEquals(quota(kit)(Quota()).error.code, ErrorCode.BadRequest)
    assert(quota(kit)(Quota()).errorMessage.contains("clear the quota instead"))
    assert(!quota(kit)(Quota(projects = Some(0))).isError)
    val refused = kit.call(OrganizationEntity.reserveProject, alice.metadata)("checkout")
    assertEquals(refused.error.code, ErrorCode.Conflict)
    assert(refused.errorMessage.contains("quota of 0 project(s) (0 in use)"), refused.errorMessage)
  }

  test(
    "a project slot is reserved once, refused at the quota, and released; the reply says which"
  ) {
    val kit = acme
    val _   = quota(kit)(Quota(projects = Some(2)))
    assertEquals(kit.call(OrganizationEntity.reserveProject, alice.metadata)("a").replyValue, true)
    assertEquals(kit.call(OrganizationEntity.reserveProject, alice.metadata)("b").replyValue, true)
    // Already counted: a retry, or a create about to fail as a duplicate — nothing to give back.
    val again = kit.call(OrganizationEntity.reserveProject, alice.metadata)("a")
    assertEquals(again.replyValue, false)
    assertEquals(again.events, Vector.empty)

    val refused = kit.call(OrganizationEntity.reserveProject, alice.metadata)("c")
    assertEquals(refused.error.code, ErrorCode.Conflict)
    assertEquals(
      refused.errorMessage,
      "organization 'acme' has reached its quota of 2 project(s) (2 in use)"
    )
    assertEquals(usageOf(kit).projects, 2)

    val _ = kit.call(OrganizationEntity.releaseProject, alice.metadata)("a")
    assertEquals(usageOf(kit).projects, 1)
    assertEquals(
      kit.call(OrganizationEntity.releaseProject, alice.metadata)("zz").events,
      Vector.empty
    )
    assertEquals(kit.call(OrganizationEntity.reserveProject, alice.metadata)("c").replyValue, true)
  }

  test("a service reservation checks the service count for a new key and only the increase") {
    val kit = acme
    val _   = quota(kit)(Quota(services = Some(2), instances = Some(4)))

    val first =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 2))
    assertEquals(first.replyValue, None)
    assertEquals(usageOf(kit), Usage(0, 1, 2))

    // Over the instance quota: refused, naming where it would land and what is in use.
    val tooMany =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/b", 3))
    assertEquals(tooMany.error.code, ErrorCode.Conflict)
    assertEquals(
      tooMany.errorMessage,
      "applying 'p/b' with 3 instance(s) would take organization 'acme' to 5 instances, " +
        "over its quota of 4 (2 in use)"
    )
    assertEquals(
      kit
        .call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/b", 1))
        .replyValue,
      None
    )
    assertEquals(usageOf(kit), Usage(0, 2, 3))

    // At the service quota: the service count is what refuses, before instances are looked at.
    val third =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/c", 1))
    assertEquals(
      third.errorMessage,
      "organization 'acme' has reached its quota of 2 service(s) (2 in use)"
    )

    // A re-apply at the same count is a reply and no event; lower is always accepted; higher is
    // checked as an increase over what it already holds.
    val same = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 2))
    assertEquals((same.replyValue, same.events), (Some(2), Vector.empty))
    val lower =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 1))
    assertEquals(lower.replyValue, Some(2))
    assertEquals(usageOf(kit), Usage(0, 2, 2))
    val higher =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 3))
    assertEquals(higher.replyValue, Some(1))
    assertEquals(usageOf(kit), Usage(0, 2, 4))
    assertEquals(
      kit
        .call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 4))
        .error
        .code,
      ErrorCode.Conflict
    )
  }

  test("recording is unchecked: it undoes a reservation and forgets a deleted service") {
    val kit = acme
    val _   = quota(kit)(Quota(instances = Some(1)))
    val _   = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("p/a", 1))
    // The apply behind the reservation failed: put back what was there — nothing.
    val _ = kit.call(OrganizationEntity.recordService, alice.metadata)(RecordService("p/a", None))
    assertEquals(usageOf(kit), Usage.zero)
    // And a restore may well exceed the quota: it is what exists, whatever the limit.
    val _ =
      kit.call(OrganizationEntity.recordService, alice.metadata)(RecordService("p/a", Some(5)))
    assertEquals(usageOf(kit), Usage(0, 1, 5))
    assertEquals(
      kit
        .call(OrganizationEntity.recordService, alice.metadata)(RecordService("p/a", Some(5)))
        .events,
      Vector.empty
    )
    assertEquals(
      kit
        .call(OrganizationEntity.recordService, alice.metadata)(RecordService("p/zz", None))
        .events,
      Vector.empty
    )
  }

  test(
    "setting a quota merges in what the endpoint saw existing, forgetting nothing the record knows"
  ) {
    val kit = acme
    // Recorded a moment ago: a listing may not show these yet, and they must survive.
    val _ = kit.call(OrganizationEntity.reserveProject, alice.metadata)("fresh")
    val _ =
      kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("fresh/x", 1))
    // Recorded at a count that drifted: the snapshot's, read from the descriptor, wins.
    val _ = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("old/y", 9))
    val set = kit.call(OrganizationEntity.setQuota, carol.metadata)(
      SetQuota(Quota(projects = Some(5)), Set("old"), Map("old/y" -> 2, "old/z" -> 1))
    )
    val expected = UsageRecord(Set("fresh", "old"), Map("fresh/x" -> 1, "old/y" -> 2, "old/z" -> 1))
    assertEquals(
      set.events,
      Vector(
        UsageReconciled(expected.projects, expected.services, Some(carol.actor), Some(now)),
        QuotaSet(Quota(Some(5), None, None), Some(carol.actor), Some(now))
      )
    )
    assertEquals(usageOf(kit), Usage(2, 3, 4))
    // A snapshot that changes nothing writes only the quota.
    val again = kit.call(OrganizationEntity.setQuota, carol.metadata)(
      SetQuota(Quota(projects = Some(6)), Set("old"), Map("old/y" -> 2))
    )
    assertEquals(again.events.map(_.getClass.getSimpleName), Vector("QuotaSet"))
  }

  test("a lowered quota is accepted below usage; deleting the organization forgets it all") {
    val kit = acme
    val _   = kit.call(OrganizationEntity.reserveProject, alice.metadata)("a")
    val _   = kit.call(OrganizationEntity.reserveProject, alice.metadata)("b")
    assert(!quota(kit)(Quota(projects = Some(0))).isError)
    assertEquals(usageOf(kit).projects, 2)
    val _ = kit.call(OrganizationEntity.releaseProject, alice.metadata)("a")
    val _ = kit.call(OrganizationEntity.releaseProject, alice.metadata)("b")
    val _ = kit.call(OrganizationEntity.delete, alice.metadata)
    assertEquals(kit.currentState.quota, None)
    assertEquals(kit.currentState.record, UsageRecord())
  }

  test("usage and the quota are rebuilt purely by folding events") {
    val kit = acme
    val _   = quota(kit)(Quota(projects = Some(3), services = Some(3), instances = Some(9)))
    val _   = kit.call(OrganizationEntity.reserveProject, alice.metadata)("a")
    val _   = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("a/x", 2))
    val _   = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("a/y", 1))
    val _   = kit.call(OrganizationEntity.reserveService, alice.metadata)(ReserveService("a/x", 3))
    val _   = kit.call(OrganizationEntity.recordService, alice.metadata)(RecordService("a/y", None))
    val replayed = kit.allEvents.foldLeft(Organization.empty("acme")) { (state, event) =>
      event match
        case QuotaSet(q, _, _) => state.onQuotaSet(q)
        case _: QuotaCleared   => state.onQuotaCleared
        case OrganizationCreated(name, creator, at, owner) =>
          state.onCreated(name, creator, at, owner)
        case other => state.onUsage(other)
    }
    assertEquals(replayed, kit.currentState)
    assertEquals(replayed.usage, Usage(1, 1, 3))
  }

  // Feature 039: where a project's new buckets in Google Cloud Storage are made.

  test(
    "a project names a location for its buckets, takes it back, and the same one again records nothing"
  ) {
    val kit = project
    val _   = kit.call(ProjectEntity.createProject)(CreateProject("Checkout", "acme"))
    assertEquals(kit.call(ProjectEntity.bucketLocation).replyValue, None)
    val set = kit.call(ProjectEntity.setLocation)(SetProjectLocation("europe-west6"))
    assertEquals(set.events.size, 1)
    assertEquals(kit.call(ProjectEntity.bucketLocation).replyValue, Some("europe-west6"))
    assertEquals(
      kit.call(ProjectEntity.setLocation)(SetProjectLocation("europe-west6")).events,
      Vector.empty
    )
    val cleared = kit.call(ProjectEntity.setLocation)(SetProjectLocation(""))
    assertEquals(cleared.events.size, 1)
    assertEquals(kit.call(ProjectEntity.bucketLocation).replyValue, None)
  }

  test("a project's location is written onto its resource, and absent when it names none") {
    import com.thinkmorestupidless.ankka.controlplane.deploy.ProjectProjection
    assertEquals(
      ProjectProjection.spec("checkout", Map.empty, Map.empty, Some("europe-west6")).bucketLocation,
      Some("europe-west6")
    )
    assertEquals(ProjectProjection.spec("checkout", Map.empty).bucketLocation, None)
  }
