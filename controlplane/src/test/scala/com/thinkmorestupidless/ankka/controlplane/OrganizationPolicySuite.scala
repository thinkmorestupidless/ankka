package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.tenancy.{OrganizationCreation, OrganizationPolicy}
import com.typesafe.config.ConfigFactory

/**
 * The installation's organization policy, from configuration (feature 011, FR-001, FR-003, FR-005):
 * the default is open with nowhere to send anyone, both values load, the refusal reads as
 * documented, and a typo does not come up as "open".
 */
class OrganizationPolicySuite extends munit.FunSuite:

  private def load(overrides: String = "") =
    OrganizationPolicy.from(
      ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load())
    )

  test("the reference configuration is the default: open, no sign-up address") {
    assertEquals(load(), OrganizationPolicy.default)
    assertEquals(load().creation, OrganizationCreation.Open)
    assertEquals(load().signupUrl, None)
  }

  test("platform-admin loads, with and without a sign-up address") {
    val bare = load("ankka.controlplane.organizations.creation = platform-admin")
    assertEquals(bare, OrganizationPolicy(OrganizationCreation.PlatformAdmin, None))
    val withUrl = load(
      """ankka.controlplane.organizations {
        |  creation = "Platform-Admin"
        |  signup-url = " https://ankka.cloud "
        |}""".stripMargin
    )
    assertEquals(
      withUrl,
      OrganizationPolicy(OrganizationCreation.PlatformAdmin, Some("https://ankka.cloud"))
    )
  }

  test("the refusal names the reason, and the address when there is one") {
    assertEquals(
      OrganizationPolicy(OrganizationCreation.PlatformAdmin).refusal,
      "organizations in this installation are created by the platform administrator"
    )
    assertEquals(
      OrganizationPolicy(OrganizationCreation.PlatformAdmin, Some("https://ankka.cloud")).refusal,
      "organizations in this installation are created by the platform administrator; " +
        "sign up at https://ankka.cloud"
    )
  }

  test("any other value refuses to load, naming the key, the variable and both values") {
    val failure = intercept[IllegalArgumentException] {
      load("ankka.controlplane.organizations.creation = anyone")
    }
    val message = failure.getMessage
    assert(message.contains("ankka.controlplane.organizations.creation"), message)
    assert(message.contains("ANKKA_ORGANIZATION_CREATION"), message)
    assert(message.contains("'anyone'"), message)
    assert(message.contains("'open'") && message.contains("'platform-admin'"), message)
  }
