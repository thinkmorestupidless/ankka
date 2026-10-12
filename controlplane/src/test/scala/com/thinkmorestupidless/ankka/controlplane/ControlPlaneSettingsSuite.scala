package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.CloudConfig
import com.thinkmorestupidless.ankka.controlplane.secrets.{SecretBackendConfig, SecretRecordsConfig}
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
    // The backend's refusals are IllegalArgumentException, the cloud's (044's CloudConfig) IllegalState.
    intercept[RuntimeException](
      ControlPlane.builder(Acl.DenyAll, config = settings(hocon))
    ).getMessage

  test("a retention left blank, as the control plane's manifest leaves it, is the default") {
    // The shipped Deployment's placeholder is `""`, which the overlays replace; a control plane
    // applied without that replacement must start, as it does for every other setting left blank.
    val read =
      SecretRecordsConfig.from(settings("ankka.controlplane.secret-records.retention = \"\""))
    assertEquals(read.retention, SecretRecordsConfig.DefaultRetention)
    assertEquals(read.retentionText, "365d")
  }

  test("an installation that says nothing is on the Postgres backend with no cloud") {
    val read = SecretBackendConfig.from(settings(""))
    assertEquals(read, SecretBackendConfig(SecretBackend.Postgres, None))
  }

  test("secret-manager is refused while the cloud provider is none, naming the provider needed") {
    val message = refusal("""ankka.secrets.backend = secret-manager
                            |ankka.controlplane.cloud.provider = none
                            |ankka.controlplane.cloud.account = spinvibe-prod""".stripMargin)
    assert(message.contains("ANKKA_CLOUD_PROVIDER") && message.contains("gcp"), message)
  }

  test("secret-manager is refused without a cloud account") {
    val message = refusal("""ankka.secrets.backend = secret-manager
                            |ankka.controlplane.cloud.provider = gcp""".stripMargin)
    assert(message.contains("ANKKA_CLOUD_ACCOUNT"), message)
  }

  test("an unknown backend or provider is refused by name") {
    assert(refusal("ankka.secrets.backend = vault").contains("vault"))
    assert(refusal("ankka.controlplane.cloud.provider = azure").contains("azure"))
  }

  test("secret-manager on gcp with an account and a location is read whole") {
    val read = SecretBackendConfig.from(
      settings(
        """ankka.secrets.backend = secret-manager
                                                   |ankka.controlplane.cloud.provider = gcp
                                                   |ankka.controlplane.cloud.account = spinvibe-prod
                                                   |ankka.controlplane.cloud.location = europe-west2""".stripMargin
      )
    )
    assertEquals(
      read,
      SecretBackendConfig(
        SecretBackend.SecretManager,
        Some(CloudConfig("gcp", "spinvibe-prod", "europe-west2", None))
      )
    )
  }
