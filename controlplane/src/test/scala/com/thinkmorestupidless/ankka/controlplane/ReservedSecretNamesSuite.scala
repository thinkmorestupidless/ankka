package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{ProjectSecrets, Registries}
import com.thinkmorestupidless.ankka.operator.{CnpgRendering, Names, ZeroTrust}

/**
 * A project secret may not take a name the platform uses for its own Secrets in a project.
 *
 * The control plane writes a project secret by a member's chosen name, with a patch, into the
 * namespace where the operator keeps each service's database credential, certificates and secret
 * key — and it cannot look first. `controlplane-api` refuses the forms those names take, from a
 * list of its own, since it shares no module with the operator. This is the one place that sees
 * both, so it is where the list is held to the names the operator actually derives.
 */
final class ReservedSecretNamesSuite extends munit.FunSuite:

  private val services = Vector("cart", "payments", "a-b")

  private def platformNames(service: String): Vector[String] =
    Vector(
      CnpgRendering.credentialSecretName(service),
      ZeroTrust.clusterSecretName(service),
      ZeroTrust.serviceSecretName(service),
      ZeroTrust.Database.certificateSecret(service),
      Names.secretKeySecret(service)
    )

  private val projectNames: Vector[String] =
    Vector(Registries.SecretName, CnpgRendering.clientCaName, CnpgRendering.replicationName)

  test("every Secret name the operator derives for a service is refused as a project secret") {
    for
      service <- services
      name    <- platformNames(service)
    do assert(ProjectSecrets.nameProblems(name).nonEmpty, s"'$name' must be refused")
  }

  test("every Secret name the platform gives a project is refused as a project secret") {
    for name <- projectNames do
      assert(ProjectSecrets.nameProblems(name).nonEmpty, s"'$name' must be refused")
  }

  test("an ordinary name is not caught by the rule") {
    for name <- Vector("checkout", "stripe", "payments-live") do
      assertEquals(ProjectSecrets.nameProblems(name), Vector.empty, name)
  }
