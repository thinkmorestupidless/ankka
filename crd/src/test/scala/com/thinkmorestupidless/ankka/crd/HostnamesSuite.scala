package com.thinkmorestupidless.ankka.crd

/**
 * The one derivation of an exposed service's hostname, shared by the control plane (display, the
 * collision refusal) and the operator (rendering). See specs/005-expose-services/research.md R2 for
 * why it is one label: a wildcard — the certificate's and the Gateway listener's alike — is exactly
 * one label deep.
 */
class HostnamesSuite extends munit.FunSuite:

  test("service-project, one label under the base domain") {
    assertEquals(Hostnames.of("cart", "checkout", "example.test"), "cart-checkout.example.test")
    assertEquals(Hostnames.label("cart", "checkout"), "cart-checkout")
  }

  test("the control plane is a reserved label directly under the base domain") {
    assertEquals(Hostnames.controlPlane("example.test"), "api.example.test")
  }

  test("a label over 63 characters is refused, naming the length and the limit") {
    val name     = "a" * 40
    val project  = "b" * 40
    val problems = Hostnames.problems(name, project)
    assertEquals(problems.size, 1)
    assert(problems.head.contains("81 characters"), problems.head)
    assert(problems.head.contains("63"), problems.head)
    assert(problems.head.contains(Hostnames.label(name, project)), problems.head)
  }

  test("the longest pair that fits has no problem") {
    assertEquals(Hostnames.problems("a" * 31, "b" * 31), Vector.empty)
    assertEquals(Hostnames.label("a" * 31, "b" * 31).length, 63)
    assertEquals(Hostnames.problems("a" * 32, "b" * 31).size, 1)
  }

  test("the control plane's label can never be derived: every service label contains a hyphen") {
    // Names are [a-z]([-a-z0-9]{0,61}[a-z0-9])?; whatever they are, name + "-" + project contains
    // a hyphen, and "api" does not. The reserved label is safe by construction, not by a check.
    val names = Vector("api", "a", "ap", "a-pi", "cart", "x-y-z")
    for n <- names; p <- names do
      assertNotEquals(Hostnames.label(n, p), "api")
      assert(Hostnames.label(n, p).contains("-"))
  }

  test("a derived hostname is DNS-shaped: no empty labels, no leading or trailing hyphen") {
    for (n, p) <- Vector("cart" -> "checkout", "a" -> "b", "x-y" -> "z-w") do
      val host = Hostnames.of(n, p, "127.0.0.1.sslip.io")
      assert(!host.contains(".."), host)
      assert(!host.startsWith("-") && !host.endsWith("-"), host)
      assert(
        host.split('.').forall(l => l.nonEmpty && !l.startsWith("-") && !l.endsWith("-")),
        host
      )
  }
