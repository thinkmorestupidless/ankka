package nakka.controlplane

import nakka.controlplane.api.*
import nakka.controlplane.deploy.DeployConfig
import nakka.controlplane.domain.{Service, ServiceKey}

/** The four reasons a service cannot be exposed, as values — contracts/expose-api.md. */
class ExposureRulesSuite extends munit.FunSuite:

  private val configured = DeployConfig.default.copy(baseDomain = Some("example.test"))

  private def service(name: String = "cart", projectId: String = "checkout", http: Boolean = true) =
    Service
      .empty(ServiceKey(projectId, name))
      .onApplied(ServiceDescriptor(name, ServiceSpec("cart:1.0", http = http)), 1L)

  test("nothing can be exposed without a base domain, and the message names the variable") {
    val refusal = ExposureRules.refusal(service(), DeployConfig.default, None)
    assert(refusal.exists(_.contains("NAKKA_BASE_DOMAIN")), refusal)
  }

  test("a service that serves no HTTP has nothing to expose") {
    val refusal = ExposureRules.refusal(service(http = false), configured, None)
    assertEquals(
      refusal,
      Some("""service 'cart' serves no HTTP ("http": false); there is nothing to expose""")
    )
  }

  test("a label over 63 characters is refused with the length and the limit") {
    val refusal = ExposureRules.refusal(service(name = "a" * 60), configured, None)
    assert(refusal.exists(r => r.contains("69 characters") && r.contains("63")), refusal)
  }

  test("a hostname another exposed service already holds is refused, naming the holder") {
    // `a-b` in `c` and `a` in `b-c` both derive a-b-c.example.test.
    val refusal =
      ExposureRules.refusal(service("a", "b-c"), configured, Some(ServiceKey("c", "a-b")))
    assertEquals(
      refusal,
      Some("hostname a-b-c.example.test is already exposed by service 'a-b' in project 'c'")
    )
  }

  test("a plain service with a base domain and no holder may be exposed") {
    assertEquals(ExposureRules.refusal(service(), configured, None), None)
  }

  test("the URL a configured control plane reports is https at the derived hostname") {
    assertEquals(
      configured.hostnameFor("checkout", "cart"),
      Some("https://cart-checkout.example.test")
    )
    assertEquals(DeployConfig.default.hostnameFor("checkout", "cart"), None)
    // A non-default port is part of the URL, so what is printed is what works — kind publishes
    // the gateway on 8443.
    assertEquals(
      configured.copy(httpsPort = 8443).hostnameFor("checkout", "cart"),
      Some("https://cart-checkout.example.test:8443")
    )
  }
