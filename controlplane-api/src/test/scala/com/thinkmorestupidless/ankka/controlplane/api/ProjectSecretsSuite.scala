package com.thinkmorestupidless.ankka.controlplane.api

/** What a project secret may be called and hold, as the CLI and the control plane both check it. */
class ProjectSecretsSuite extends munit.FunSuite:

  private val ok = Map("STRIPE_KEY" -> "sk_live_1")

  test("a lowercase name of letters, digits, '-' and '.' is accepted") {
    for name <- Vector("checkout", "stripe.live", "a", "payments-2") do
      assertEquals(ProjectSecrets.problems(name, ok), Vector.empty, name)
  }

  test("a project secret may not take a name the platform uses") {
    for name <- Vector(
        "ankka-registry",
        "payments-db",
        "payments-cluster-tls",
        "payments-service-tls",
        "payments-database-tls",
        "payments-secret-key",
        "payments-telemetry"
      )
    do
      val problems = ProjectSecrets.nameProblems(name)
      assert(problems.exists(_.contains("one the platform uses")), s"$name: $problems")
  }

  test("a name that is not a Kubernetes Secret name is refused") {
    for name <- Vector("Checkout", "-checkout", "checkout-", "check out", "a" * 254, "") do
      assert(ProjectSecrets.nameProblems(name).nonEmpty, name)
  }

  test("entry names are letters, digits, '.', '_' and '-'") {
    assertEquals(ProjectSecrets.entryProblems("STRIPE_KEY"), Vector.empty)
    assertEquals(ProjectSecrets.entryProblems("a.b-c"), Vector.empty)
    for entry <- Vector("STRIPE KEY", "A=B", "") do
      assert(ProjectSecrets.entryProblems(entry).nonEmpty, entry)
  }

  test("an empty value, an oversized value and no entries are refused, never quoting a value") {
    val problems =
      ProjectSecrets.problems("checkout", Map("EMPTY" -> "", "BIG" -> ("x" * 65537)))
    assert(problems.exists(_.contains("'EMPTY' must not be empty")), problems.toString)
    assert(problems.exists(_.contains("65536")), problems.toString)
    assert(!problems.exists(_.contains("xxxx")), problems.toString)
    assert(ProjectSecrets.problems("checkout", Map.empty).nonEmpty)
  }

  test("every problem of a request is reported together") {
    val problems = ProjectSecrets.problems("payments-db", Map("BAD KEY" -> ""))
    assertEquals(problems.size, 3, problems.toString)
  }
