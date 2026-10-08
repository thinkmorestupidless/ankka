package com.thinkmorestupidless.ankka.controlplane.api

/**
 * features/topics/brokers.feature: a broker's declaration, its refusals worded once (feature 037).
 */
class ProjectBrokersSuite extends munit.FunSuite:

  private val sasl = BrokerDeclarationRequest("kafka.legacy:9094", "sasl", "legacy-credential")

  test("a declaration with an address, a shape and a project secret is accepted") {
    assertEquals(ProjectBrokers.problems("legacy", sasl), Vector.empty)
    assertEquals(ProjectBrokers.problems("legacy", sasl.copy(shape = "certificate")), Vector.empty)
    assertEquals(ProjectBrokers.problems("legacy", sasl.copy(bootstrap = "a:1,b:2")), Vector.empty)
  }

  test(
    "a name outside the rule, a malformed address, an unknown shape and a reserved secret are refused, naming each"
  ) {
    assert(
      ProjectBrokers.problems("Legacy", sasl).exists(_.startsWith("broker 'Legacy': a name is"))
    )
    assert(
      ProjectBrokers
        .problems("legacy", sasl.copy(bootstrap = "kafka"))
        .exists(_.contains("is not host:port"))
    )
    assert(
      ProjectBrokers
        .problems("legacy", sasl.copy(shape = "plain"))
        .exists(_.contains("shape 'plain' is not one of certificate, sasl"))
    )
    assert(
      ProjectBrokers
        .problems("legacy", sasl.copy(secret = "ankka-secret"))
        .exists(_.contains("platform uses"))
    )
    assertEquals(ProjectBrokers.problems("Legacy", sasl.copy(bootstrap = "", shape = "x")).size, 3)
  }

  test("what each shape needs of the secret, and how lacking it is worded") {
    assertEquals(ProjectBrokers.needs("certificate"), Vector("ca.crt", "tls.crt", "tls.key"))
    assertEquals(ProjectBrokers.needs("sasl"), Vector("ca.crt", "username", "password"))
    assertEquals(
      ProjectBrokers.lacking("legacy-credential", "sasl", Vector("ca.crt")),
      "project secret 'legacy-credential' lacks 'ca.crt', which shape 'sasl' needs"
    )
  }
