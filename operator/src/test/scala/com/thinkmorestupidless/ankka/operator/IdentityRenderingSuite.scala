package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}

import scala.jdk.CollectionConverters.*

/**
 * The identity a service's pods run as, and the one permission it carries — pure, no cluster.
 *
 * See
 * [contracts/identity-and-rbac.md](../../../../../specs/004-multi-node-clusters/contracts/identity-and-rbac.md).
 */
class IdentityRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default

  private def resource(spec: AnkkaServiceSpec, uid: String): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-checkout").withName("cart").withUid(uid).build()
    )
    r.setSpec(spec)
    r

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 1L,
    image = "cart:1.0"
  )

  private def actions(uid: String = "uid-1") =
    Rendering.render(resource(spec, uid), settings, ProvisioningPlan.Supplied, "unused") match
      case Right(a)       => a
      case Left(problems) => fail(s"expected a render, got $problems")

  private def serviceAccount = actions().collectFirst { case Action.EnsureServiceAccount(sa) =>
    sa
  }.get
  private def role        = actions().collectFirst { case Action.EnsureRole(r) => r }.get
  private def roleBinding = actions().collectFirst { case Action.EnsureRoleBinding(b) => b }.get

  test("all three are rendered, in the project's namespace, named after the service") {
    assertEquals(serviceAccount.getMetadata.getName, "cart")
    assertEquals(role.getMetadata.getName, "cart-peers")
    assertEquals(roleBinding.getMetadata.getName, "cart-peers")
    for meta <- Vector(serviceAccount.getMetadata, role.getMetadata, roleBinding.getMetadata) do
      assertEquals(meta.getNamespace, "ankka-checkout")
  }

  test("all three are owned by the resource, so they go with it and need no delete verb") {
    for (what, owners) <- Vector(
        "ServiceAccount" -> serviceAccount.getMetadata.getOwnerReferences,
        "Role"           -> role.getMetadata.getOwnerReferences,
        "RoleBinding"    -> roleBinding.getMetadata.getOwnerReferences
      )
    do
      assertEquals(owners.size, 1, what)
      assertEquals(owners.get(0).getUid, "uid-1", what)
      assertEquals(owners.get(0).getKind, "AnkkaService", what)
  }

  test("the Role grants exactly one thing: reading pods") {
    val rules = role.getRules.asScala
    assertEquals(rules.size, 1)
    assertEquals(rules.head.getApiGroups.asScala.toList, List(""))
    assertEquals(rules.head.getResources.asScala.toList, List("pods"))
    assertEquals(rules.head.getVerbs.asScala.toSet, Set("get", "list", "watch"))
    assert(rules.head.getResourceNames == null || rules.head.getResourceNames.isEmpty)
  }

  test("the RoleBinding binds that Role to that ServiceAccount and nothing else") {
    assertEquals(roleBinding.getRoleRef.getKind, "Role")
    assertEquals(roleBinding.getRoleRef.getName, "cart-peers")
    val subjects = roleBinding.getSubjects.asScala
    assertEquals(subjects.size, 1)
    assertEquals(subjects.head.getKind, "ServiceAccount")
    assertEquals(subjects.head.getName, "cart")
    assertEquals(subjects.head.getNamespace, "ankka-checkout")
  }

  test("identity comes before the Deployment that runs as it") {
    val a            = actions()
    val deploymentAt = a.indexWhere(_.isInstanceOf[Action.ApplyDeployment])
    for kind <- Vector(
        classOf[Action.EnsureServiceAccount],
        classOf[Action.EnsureRole],
        classOf[Action.EnsureRoleBinding]
      )
    do assert(a.indexWhere(kind.isInstance) < deploymentAt, kind.getSimpleName)
  }

  test("rendering twice is identical") {
    assertEquals(role, role)
    assertEquals(
      actions().collect { case Action.EnsureRole(r) => r },
      actions().collect { case Action.EnsureRole(r) => r }
    )
  }
