package com.thinkmorestupidless.ankka.proxy.core

import CallingAddress.Target

/** Where a call at the calling address goes, and what the called service is sent. */
class CallingAddressSuite extends munit.FunSuite:

  private val url = "http://127.0.0.1:7630"

  private def parse(target: String) = CallingAddress.parse(target, "shop", url)

  test("a service of the proxy's own project is named alone") {
    assertEquals(parse("/cart/carts/c1"), Right(Target("shop", "cart", "/carts/c1")))
  }

  test("a service of another project is named with its project after a dot") {
    assertEquals(parse("/invoices.billing/issue"), Right(Target("billing", "invoices", "/issue")))
  }

  test("a call naming only the service is sent to its root") {
    assertEquals(parse("/cart"), Right(Target("shop", "cart", "/")))
    assertEquals(parse("/cart/"), Right(Target("shop", "cart", "/")))
  }

  test("a query is kept on what the service receives") {
    assertEquals(
      parse("/cart/carts?x=1&y=%20z"),
      Right(Target("shop", "cart", "/carts?x=1&y=%20z"))
    )
    assertEquals(parse("/cart?x=1"), Right(Target("shop", "cart", "/?x=1")))
  }

  test("a call that names no service is told the shape of one") {
    val refused = Left(s"a call names a service: $url/<service>/<path>")
    assertEquals(parse("/"), refused)
    assertEquals(parse(""), refused)
    assertEquals(parse("//x"), refused)
    assertEquals(parse("/?x=1"), refused)
  }

  test("a first segment that is not a service's name is refused, naming it") {
    for bad <- Vector("/Cart/x", "/cart.Billing/x", "/a.b.c/x", "/-cart/x", "/cart_1/x") do
      val result = parse(bad)
      assert(result.isLeft, s"$bad: $result")
      assert(result.left.exists(_.contains("does not name a service")), result.toString)
  }

  test("the headers sent on are the process's, less the platform's, the hop-by-hop ones and Host") {
    val sent = Headers.outbound(
      Vector(
        "Accept"         -> "application/json",
        "X-Ankka-Caller" -> "service shop/orders",
        "x-ankka-other"  -> "1",
        "Connection"     -> "keep-alive",
        "Host"           -> "127.0.0.1:7630",
        "Authorization"  -> "Bearer t",
        "Cookie"         -> "a=1"
      )
    )
    assertEquals(
      sent,
      Vector("Accept" -> "application/json", "Authorization" -> "Bearer t", "Cookie" -> "a=1")
    )
  }
