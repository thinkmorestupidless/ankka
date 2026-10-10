package com.thinkmorestupidless.ankka.proxy.core

/** What a request's headers become on the way to the process. */
class HeadersSuite extends munit.FunSuite:

  private val web = ProxySettings(
    project = "shop",
    service = "web",
    port = 9000,
    processPort = 3000,
    publicAuthority = Some("web-shop.example.test")
  )

  private val internet = Sender.Internet(None)

  private def inbound(sender: Sender, settings: ProxySettings, received: (String, String)*) =
    Headers.inbound(sender, settings, received.toVector)

  private def value(headers: Vector[(String, String)], name: String): Option[String] =
    headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  private def values(headers: Vector[(String, String)], name: String): Vector[String] =
    headers.collect { case (n, v) if n.equalsIgnoreCase(name) => v }

  test("a request from the internet is told so, and the public authority as its address") {
    val headers = inbound(internet, web, "Host" -> "10.0.0.7:9000", "Accept" -> "text/html")
    assertEquals(value(headers, "X-Ankka-Caller"), Some("internet"))
    assertEquals(value(headers, "X-Forwarded-Proto"), Some("https"))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("web-shop.example.test"))
    assertEquals(value(headers, "X-Forwarded-Port"), Some("443"))
    assertEquals(values(headers, "Host"), Vector("web-shop.example.test"))
    assertEquals(value(headers, "Accept"), Some("text/html"))
  }

  test("a public authority with a port names it in the host and the port alike") {
    val settings = web.copy(publicAuthority = Some("web-shop.example.test:8443"))
    val headers  = inbound(internet, settings)
    assertEquals(value(headers, "X-Forwarded-Host"), Some("web-shop.example.test:8443"))
    assertEquals(value(headers, "X-Forwarded-Port"), Some("8443"))
    assertEquals(value(headers, "Host"), Some("web-shop.example.test:8443"))
  }

  test("a public authority naming the scheme's own port is told without it") {
    val headers = inbound(internet, web.copy(publicAuthority = Some("web-shop.example.test:443")))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("web-shop.example.test"))
    assertEquals(value(headers, "X-Forwarded-Port"), Some("443"))
  }

  test("a request cannot say that another service sent it") {
    val headers = inbound(internet, web, "X-Ankka-Caller" -> "service shop/orders")
    assertEquals(values(headers, "X-Ankka-Caller"), Vector("internet"))
  }

  test("a request cannot say that it was sent to another address") {
    val headers = inbound(
      internet,
      web,
      "X-Forwarded-Host"  -> "bank.example",
      "X-Forwarded-Proto" -> "http",
      "X-Forwarded-Port"  -> "80",
      "Forwarded"         -> "host=bank.example",
      "Host"              -> "bank.example"
    )
    assertEquals(values(headers, "X-Forwarded-Host"), Vector("web-shop.example.test"))
    assertEquals(values(headers, "X-Forwarded-Proto"), Vector("https"))
    assertEquals(values(headers, "X-Forwarded-Port"), Vector("443"))
    assertEquals(values(headers, "Host"), Vector("web-shop.example.test"))
    assertEquals(values(headers, "Forwarded"), Vector.empty)
  }

  test("a custom hostname the gateway routed is the address, and the request cannot say another") {
    val headers = inbound(
      Sender.Internet(Some("app.example.com")),
      web,
      "X-Forwarded-Host"  -> "bank.example",
      "X-Forwarded-Proto" -> "http",
      "X-Forwarded-Port"  -> "80",
      "Forwarded"         -> "host=bank.example",
      "Host"              -> "app.example.com"
    )
    assertEquals(values(headers, "X-Forwarded-Host"), Vector("app.example.com"))
    assertEquals(values(headers, "X-Forwarded-Proto"), Vector("https"))
    assertEquals(values(headers, "X-Forwarded-Port"), Vector("443"))
    assertEquals(values(headers, "Host"), Vector("app.example.com"))
    assertEquals(values(headers, "Forwarded"), Vector.empty)
  }

  test("a routed hostname keeps the port the browser used") {
    val headers = inbound(Sender.Internet(Some("app.example.com:8443")), web)
    assertEquals(values(headers, "X-Forwarded-Host"), Vector("app.example.com:8443"))
    assertEquals(values(headers, "X-Forwarded-Port"), Vector("8443"))
  }

  test("every header starting X-Ankka- is removed, whatever its case") {
    val headers = inbound(internet, web, "x-ankka-anything" -> "1", "X-ANKKA-OTHER" -> "2")
    assert(headers.forall((n, _) => !n.toLowerCase.startsWith("x-ankka-") || n == "X-Ankka-Caller"))
  }

  test("the hop-by-hop headers are removed") {
    val received = Headers.HopByHop.toVector.map(n => n -> "x")
    val headers  = inbound(internet, web, received*)
    for name <- Headers.HopByHop do assertEquals(values(headers, name), Vector.empty, name)
  }

  test("X-Forwarded-For is kept from the internet, where the gateway wrote it") {
    val headers = inbound(internet, web, "X-Forwarded-For" -> "203.0.113.9, 10.0.0.1")
    assertEquals(value(headers, "X-Forwarded-For"), Some("203.0.113.9, 10.0.0.1"))
  }

  test("X-Forwarded-For is removed from a service's request, and locally") {
    val service = inbound(Sender.Service("shop", "orders"), web, "X-Forwarded-For" -> "203.0.113.9")
    assertEquals(value(service, "X-Forwarded-For"), None)
    val local = inbound(Sender.Local, web, "X-Forwarded-For" -> "203.0.113.9")
    assertEquals(value(local, "X-Forwarded-For"), None)
  }

  test(
    "with no public authority the forwarded headers are left out and Host passes as it arrived"
  ) {
    val headers = inbound(
      internet,
      web.copy(publicAuthority = None),
      "Host"             -> "10.0.0.7:9000",
      "X-Forwarded-Host" -> "bank.example"
    )
    assertEquals(value(headers, "X-Ankka-Caller"), Some("internet"))
    assertEquals(value(headers, "X-Forwarded-Proto"), None)
    assertEquals(value(headers, "X-Forwarded-Host"), None)
    assertEquals(value(headers, "X-Forwarded-Port"), None)
    assertEquals(values(headers, "Host"), Vector("10.0.0.7:9000"))
  }

  test("an authority a mounting proxy stated is what the process is told, over the public one") {
    val headers = inbound(Sender.Internet(Some("shop.example.test:8443")), web, "Host" -> "x")
    assertEquals(value(headers, "X-Forwarded-Host"), Some("shop.example.test:8443"))
    assertEquals(value(headers, "X-Forwarded-Port"), Some("8443"))
    assertEquals(value(headers, "X-Forwarded-Proto"), Some("https"))
    assertEquals(value(headers, "Host"), Some("shop.example.test:8443"))
  }

  test("a stated authority is told even when there is no public one") {
    val headers =
      inbound(Sender.Internet(Some("shop.example.test")), web.copy(publicAuthority = None))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("shop.example.test"))
    assertEquals(value(headers, "Host"), Some("shop.example.test"))
  }

  test("a service's request is told the in-cluster address the service used") {
    val headers = inbound(Sender.Service("shop", "orders"), web, "Host" -> "web:9000")
    assertEquals(value(headers, "X-Ankka-Caller"), Some("service shop/orders"))
    assertEquals(value(headers, "X-Forwarded-Proto"), Some("https"))
    assertEquals(
      value(headers, "X-Forwarded-Host"),
      Some("web.ankka-shop.svc.cluster.local:9000")
    )
    assertEquals(value(headers, "X-Forwarded-Port"), Some("9000"))
    assertEquals(values(headers, "Host"), Vector("web.ankka-shop.svc.cluster.local:9000"))
  }

  test("the in-cluster address uses the installation's namespace prefix") {
    val headers = inbound(Sender.Service("shop", "orders"), web.copy(namespacePrefix = "acme"))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("web.acme-shop.svc.cluster.local:9000"))
  }

  test("locally the process is told the proxy's own address over plain HTTP") {
    val settings = web.copy(publicAuthority = Some("127.0.0.1:3000"))
    val headers  = inbound(Sender.Local, settings, "Host" -> "localhost:3000")
    assertEquals(value(headers, "X-Ankka-Caller"), Some("local"))
    assertEquals(value(headers, "X-Forwarded-Proto"), Some("http"))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("127.0.0.1:3000"))
    assertEquals(value(headers, "X-Forwarded-Port"), Some("3000"))
    assertEquals(values(headers, "Host"), Vector("127.0.0.1:3000"))
  }

  test("locally with no address given the proxy's port on loopback is told") {
    val headers = inbound(Sender.Local, web.copy(publicAuthority = None))
    assertEquals(value(headers, "X-Forwarded-Host"), Some("127.0.0.1:9000"))
  }

  test("the other headers keep their order and their repeats") {
    val headers = inbound(internet, web, "Accept" -> "a", "Cookie" -> "x=1", "Cookie" -> "y=2")
    assertEquals(
      headers.filter((n, _) => n == "Accept" || n == "Cookie"),
      Vector("Accept" -> "a", "Cookie" -> "x=1", "Cookie" -> "y=2")
    )
  }

  test("the hop-by-hop headers and the length are dropped from a response, and nothing else") {
    assert(Headers.droppedFromResponse("Transfer-Encoding"))
    assert(Headers.droppedFromResponse("connection"))
    assert(Headers.droppedFromResponse("Content-Length"))
    assert(!Headers.droppedFromResponse("Content-Type"))
    assert(!Headers.droppedFromResponse("Set-Cookie"))
  }

  test("an IPv6 authority's brackets are not a port") {
    assertEquals(Headers.portOf("[::1]:8443"), Some(8443))
    assertEquals(Headers.portOf("[::1]"), None)
    assertEquals(Headers.hostOf("[::1]:8443"), "[::1]")
    assertEquals(Headers.hostOf("example.test"), "example.test")
  }
