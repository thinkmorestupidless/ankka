package nakka.controlplane

import nakka.controlplane.api.CreateProject
import nakka.controlplane.application.{OrganizationEntity, ProjectEntity}
import nakka.controlplane.domain.OrganizationEvent.*
import nakka.controlplane.domain.ProjectEvent.*
import nakka.core.{Done, ErrorCode}
import nakka.testkit.EventSourcedTestKit

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
      Vector(ProjectCreated("Checkout", "acme"), ProjectRenamed("Checkout v2"), ProjectDeleted)
    )
    assertEquals(kit.call(ProjectEntity.exists).replyValue, false)
  }
