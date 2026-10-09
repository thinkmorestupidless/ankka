package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.secrets.SecretBackendConfig
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.runtime.secrets.SecretBackend
import com.typesafe.config.ConfigFactory

/**
 * The installation's settings the control plane refuses to start with: a backend its cloud cannot
 * fulfil is a control plane that would write project secrets where nothing reads them.
 */
final class ControlPlaneSettingsSuite extends munit.FunSuite:

  private def settings(hocon: String) =
    ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load()).resolve()

  private def refusal(hocon: String): String =
    intercept[IllegalArgumentException](
      ControlPlane.builder(Acl.DenyAll, config = settings(hocon))
    ).getMessage

  test("an installation that says nothing is on the Postgres backend with no cloud") {
    val read = SecretBackendConfig.from(settings(""))
    assertEquals(read, SecretBackendConfig(SecretBackend.Postgres, "none", None, None))
  }

  test("secret-manager is refused while the cloud provider is none, naming the provider needed") {
    val message = refusal("""ankka.secrets.backend = secret-manager
                            |ankka.cloud.provider = none
                            |ankka.cloud.account = spinvibe-prod""".stripMargin)
    assert(message.contains("ANKKA_CLOUD_PROVIDER") && message.contains("gcp"), message)
  }

  test("secret-manager is refused without a cloud account") {
    val message = refusal("""ankka.secrets.backend = secret-manager
                            |ankka.cloud.provider = gcp""".stripMargin)
    assert(message.contains("ANKKA_CLOUD_ACCOUNT"), message)
  }

  test("an unknown backend or provider is refused by name") {
    assert(refusal("ankka.secrets.backend = vault").contains("vault"))
    assert(refusal("ankka.cloud.provider = azure").contains("azure"))
  }

  test("secret-manager on gcp with an account and a location is read whole") {
    val read = SecretBackendConfig.from(
      settings(
        """ankka.secrets.backend = secret-manager
                                                   |ankka.cloud.provider = gcp
                                                   |ankka.cloud.account = spinvibe-prod
                                                   |ankka.cloud.location = europe-west2""".stripMargin
      )
    )
    assertEquals(
      read,
      SecretBackendConfig(
        SecretBackend.SecretManager,
        "gcp",
        Some("spinvibe-prod"),
        Some("europe-west2")
      )
    )
  }
