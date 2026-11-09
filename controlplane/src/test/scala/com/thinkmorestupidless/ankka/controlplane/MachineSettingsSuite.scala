package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.auth.MachineSettings
import com.typesafe.config.{Config, ConfigFactory}

/**
 * The machine settings and the keys port, read from configuration as an installation loads it:
 * `reference.conf` and the overlay for the means of execution, never a hand-built `Config` naming
 * the paths the code reads, which is how both of #197 and #198 passed every suite.
 */
class MachineSettingsSuite extends munit.FunSuite:

  /** reference.conf with the values an installation's environment gives it. */
  private def installation(values: String): Config =
    ConfigFactory.parseString(values).withFallback(ConfigFactory.defaultReference()).resolve()

  test("the issuer is derived from the base domain where reference.conf keeps it") {
    val settings = MachineSettings.from(
      installation(
        "ankka.controlplane.kubernetes { base-domain = 127.0.0.1.sslip.io, https-port = 8443 }"
      )
    )
    assertEquals(settings.issuer, "https://api.127.0.0.1.sslip.io:8443")
    assertEquals(settings.tokenUrl, "https://api.127.0.0.1.sslip.io:8443/oauth/token")
  }

  test("the default https port names no port") {
    val settings =
      MachineSettings.from(installation("ankka.controlplane.kubernetes.base-domain = example.test"))
    assertEquals(settings.issuer, "https://api.example.test")
  }

  test("a stated issuer wins, and no base domain is the local address") {
    assertEquals(
      MachineSettings
        .from(
          installation(
            "ankka.controlplane { kubernetes.base-domain = example.test, machines.issuer = \"https://m.test\" }"
          )
        )
        .issuer,
      "https://m.test"
    )
    assertEquals(MachineSettings.from(installation("")).issuer, MachineSettings.local.issuer)
  }

  test("the keys port is served on Kubernetes, where the overlay turns TLS on, and not locally") {
    val loaded = ConfigFactory.load()
    // What the control plane's Deployment sets, so the overlay resolves.
    val pod = Map(
      "ANKKA_CLUSTER_MODE"           -> "kubernetes",
      "POD_IP"                       -> "10.0.0.1",
      "ANKKA_CLUSTER_SERVICE"        -> "ankka-controlplane",
      "ANKKA_CLUSTER_POD_SELECTOR"   -> "app.kubernetes.io/name=ankka-controlplane",
      "ANKKA_CLUSTER_CONTACT_POINTS" -> "1"
    )
    assert(
      ControlPlane.servesTls(loaded, pod),
      "the control plane on Kubernetes would not serve the keys port"
    )
    assert(!ControlPlane.servesTls(loaded, Map.empty), "a local control plane serves TLS")
  }
