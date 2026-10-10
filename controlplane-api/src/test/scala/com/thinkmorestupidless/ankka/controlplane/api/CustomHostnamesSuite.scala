package com.thinkmorestupidless.ankka.controlplane.api

class CustomHostnamesSuite extends munit.FunSuite:

  private val Base = Some("example.test")

  private def refusal(h: String) = CustomHostnames.problem(h, Base)

  test("a name is lowercased and loses the root's trailing dot") {
    assertEquals(CustomHostnames.normalise("App.Example.COM."), "app.example.com")
  }

  test("a name under another domain is a custom hostname, apex or not") {
    assertEquals(refusal("app.example.com"), None)
    assertEquals(refusal("example.com"), None)
    assertEquals(refusal("App.Example.COM."), None)
    assertEquals(refusal("a-1.b2.example.co.uk"), None)
  }

  test("a scheme, a path or a port: a custom hostname is a name alone") {
    for h <- Vector(
        "https://app.example.com",
        "app.example.com/shop",
        "app.example.com:8443",
        "app example.com"
      )
    do
      assert(
        refusal(h).exists(_.contains("a custom hostname is a name alone")),
        s"$h: ${refusal(h)}"
      )
  }

  test("a wildcard is refused as a wildcard") {
    assertEquals(refusal("*.example.com"), Some("a custom hostname cannot be a wildcard"))
  }

  test("a label over 63 characters is refused naming the label and the limit") {
    val long = "a" * 64
    val r    = refusal(s"$long.example.com").getOrElse(fail("not refused"))
    assert(r.contains(long) && r.contains("63"), r)
    assertEquals(refusal(s"${"a" * 63}.example.com"), None)
  }

  test("a name over 253 characters is refused naming the limit") {
    val name = Vector.fill(4)("a" * 62).mkString(".") + ".example.com"
    val r    = refusal(name).getOrElse(fail("not refused"))
    assert(r.contains("253"), r)
  }

  test("a character outside a-z, 0-9 and '-', or a label starting or ending with '-', is refused") {
    for h <- Vector("a_b.example.com", "-a.example.com", "a-.example.com") do
      assert(refusal(h).exists(_.contains("must be letters, digits and '-'")), s"$h: ${refusal(h)}")
  }

  test("an empty label is refused") {
    assert(refusal("a..example.com").exists(_.contains("empty label")))
  }

  test("a single label is not a name on the internet") {
    assert(refusal("localhost").exists(_.contains("at least two labels")))
  }

  test("the base domain and every name under it are the platform's") {
    for h <- Vector(
        "shop.example.test",
        "cart-checkout.example.test",
        "example.test",
        "api.example.test"
      )
    do
      assert(
        refusal(h).exists(_.contains("a custom hostname cannot be under the base domain")),
        s"$h: ${refusal(h)}"
      )
    assertEquals(refusal("notexample.test"), None)
  }

  test("an installation with no base domain refuses nothing for being under it") {
    assertEquals(CustomHostnames.problem("shop.example.test", None), None)
  }

  test("an apex is a name of two labels") {
    assert(CustomHostnames.isApex("example.com"))
    assert(CustomHostnames.isApex("Example.com."))
    assert(!CustomHostnames.isApex("app.example.com"))
  }

  test("a service holds at most five") {
    assertEquals(CustomHostnames.MaxPerService, 5)
  }
