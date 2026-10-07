package com.thinkmorestupidless.ankka.core

import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8

class ContractSuite extends munit.FunSuite:

  private def fp(text: String) = Contract.fingerprint(text.getBytes(UTF_8))

  test("key order and whitespace do not change the fingerprint") {
    val a = fp("""{"b": 1, "a": {"y": [1, 2], "x": "s"}}""")
    val b = fp("{\"a\":{\"x\":\"s\",\"y\":[1,2]},\"b\":1}\n")
    assertEquals(a, b)
    assert(a.exists(_.matches("sha256:[0-9a-f]{64}")))
  }

  test("a changed field changes the fingerprint") {
    assertNotEquals(fp("""{"a": 1}"""), fp("""{"a": 2}"""))
    assertNotEquals(fp("""{"a": 1}"""), fp("""{"a": 1, "b": null}"""))
  }

  test("a document that is not JSON is refused") {
    assert(fp("{not json").isLeft)
    assert(Contract.fromSchema("order.v1", "[".getBytes(UTF_8)).isLeft)
  }

  test("the name rule") {
    assert(Contract.fromSchema("order.v1", "{}".getBytes(UTF_8)).isRight)
    assert(Contract.fromSchema("cart-events_v2", "{}".getBytes(UTF_8)).isRight)
    assert(Contract.fromSchema("Order", "{}".getBytes(UTF_8)).isLeft)
    assert(Contract.fromSchema(".v1", "{}".getBytes(UTF_8)).isLeft)
    assert(Contract.fromSchema("a", "{}".getBytes(UTF_8)).isLeft)
  }

  test("the canonical form is RFC 8785's") {
    def canonical(text: String) =
      Contract.Canonical.render(
        GraphJson.parse(text.getBytes(UTF_8)).fold(why => fail(s"$text: $why"), identity)
      )
    // The escapes of RFC 8785 section 3.2.2.2 and the numbers of 3.2.2.3, as JSON text with no
    // Scala escape between the test and the document.
    val input = "{\"s\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\/\", " +
      "\"n\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001], \"l\": [null, true, false]}"
    val output = "{\"l\":[null,true,false],\"n\":[333333333.3333333,1e+30,4.5,0.002,1e-27]," +
      "\"s\":\"\u20ac$\\u000f\\nA'B\\\"\\\\/\"}"
    assertEquals(canonical(input), output)
    assertEquals(canonical("""{"b":{"d":1,"c":2},"a":[]}"""), """{"a":[],"b":{"c":2,"d":1}}""")
    assertEquals(canonical("1e21"), "1e+21")
    assertEquals(canonical("123456789012345680000"), "123456789012345680000")
    assertEquals(canonical("0.000001"), "0.000001")
    assertEquals(canonical("1e-7"), "1e-7")
    assertEquals(canonical("-0"), "0")
    assertEquals(canonical("-1.5"), "-1.5")
  }

  test("a 64 KiB document fingerprints in under ten milliseconds") {
    val big = "{" + (1 to 1500)
      .map(i => s""""field$i":{"type":"string","description":"${"x" * 20}"}""")
      .mkString(",") + "}"
    assert(big.length > 64 * 1024, big.length)
    (1 to 20).foreach(_ => fp(big)) // warm
    val start = System.nanoTime()
    assert(fp(big).isRight)
    assert((System.nanoTime() - start) < 10_000_000L, "slower than ten milliseconds")
  }
