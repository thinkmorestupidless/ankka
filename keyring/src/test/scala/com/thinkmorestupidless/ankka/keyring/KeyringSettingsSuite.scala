package com.thinkmorestupidless.ankka.keyring

import java.net.URI

final class KeyringSettingsSuite extends munit.FunSuite:

  private val base = Map(
    KeyringSettings.ControlPlane -> "https://ankka-controlplane.ankka-controlplane.svc:9000",
    "ANKKA_SECRET_KEY"           -> "a2V5"
  )

  private def machines(extra: (String, String)*) =
    KeyringSettings.fromEnvironment((base ++ extra).get).map(_.machines)

  test("with no base domain and no issuer named, no machine is admitted") {
    assertEquals(machines(), Right(None))
  }

  test("the issuer is the control plane's, derived from the base domain and the HTTPS port") {
    assertEquals(
      machines(KeyringSettings.BaseDomain -> "example.test"),
      Right(Some("https://api.example.test" -> URI.create(KeyringSettings.DefaultMachineJwks)))
    )
    assertEquals(
      machines(
        KeyringSettings.BaseDomain -> "127.0.0.1.sslip.io",
        KeyringSettings.HttpsPort  -> "8443"
      ),
      Right(
        Some(
          "https://api.127.0.0.1.sslip.io:8443" -> URI.create(KeyringSettings.DefaultMachineJwks)
        )
      )
    )
  }

  test("an issuer named wins over the derived one, and is named with its keys") {
    assertEquals(
      machines(
        KeyringSettings.BaseDomain    -> "example.test",
        KeyringSettings.MachineIssuer -> "https://issuer.test",
        KeyringSettings.MachineJwks   -> "http://jwks.test/jwks.json"
      ),
      Right(Some("https://issuer.test" -> URI.create("http://jwks.test/jwks.json")))
    )
    assert(machines(KeyringSettings.MachineIssuer -> "https://issuer.test").isLeft)
  }

  test("a port that is not one is named") {
    val problems =
      machines(KeyringSettings.BaseDomain -> "example.test", KeyringSettings.HttpsPort -> "x")
    assert(problems.left.exists(_.exists(_.contains(KeyringSettings.HttpsPort))), problems.toString)
  }
