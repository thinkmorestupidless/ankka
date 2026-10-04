package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.Headers
import com.thinkmorestupidless.ankka.runtime.OutboundHeaders

/**
 * The service client's rule for what a handler may not send, held to the proxy's.
 *
 * Two lists of the platform's own headers, kept in two modules that cannot see each other: the
 * proxy's, in `proxy-core`, which depends on nothing, and the service client's, in `runtime`. This
 * module sees both, so a header the proxy learns to own and the client does not is a failure here.
 */
class OutboundHeadersSuite extends munit.FunSuite:

  private def sent(names: String*): Seq[String] =
    OutboundHeaders.sent(names.map(_ -> "v")).map(_._1)

  test("every hop-by-hop header the proxy removes, the service client removes") {
    Headers.HopByHop.foreach { name =>
      assertEquals(sent(name), Nil, name)
      assertEquals(sent(name.toUpperCase), Nil, name.toUpperCase)
    }
  }

  test("every header named with the platform's prefix is removed, in any letter case") {
    val prefix = Headers.PlatformPrefix
    for name <- Seq(prefix + "caller", (prefix + "local-caller").toUpperCase, "X-Ankka-Anything")
    do assertEquals(sent(name), Nil, name)
  }

  test("the headers that say where a request was sent are removed") {
    for name <- Seq(
        Headers.ForwardedProto,
        Headers.ForwardedHost,
        Headers.ForwardedPort,
        Headers.ForwardedFor,
        "Forwarded",
        Headers.Host
      )
    do assertEquals(sent(name), Nil, name)
  }

  test("every other header is sent as given, in order, and twice when given twice") {
    val headers = Seq("X-Request-Id" -> "1", "Accept" -> "a", "x-request-id" -> "2")
    assertEquals(OutboundHeaders.sent(headers), headers)
  }
