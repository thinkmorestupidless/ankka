package com.thinkmorestupidless.ankka.proxy.core

/** Who the proxy lets through, from the certificate alone. */
class AdmissionSuite extends munit.FunSuite:

  private val web =
    ProxySettings(project = "shop", service = "web", port = 9000, processPort = 3000)

  private def admits(settings: ProxySettings, sender: Sender) = Admission.admits(settings, sender)

  test("the internet is always admitted, whatever the descriptor names") {
    assert(admits(web, Sender.Internet(None)))
    assert(admits(web, Sender.Internet(Some("web-shop.example.test"))))
    assert(
      admits(web.copy(callers = Vector(Admitted.Service("shop", "orders"))), Sender.Internet(None))
    )
  }

  test("the local machine is always admitted") {
    assert(admits(web, Sender.Local))
  }

  test("the service itself is admitted with no callers named") {
    assert(admits(web, Sender.Service("shop", "web")))
  }

  test("a service of the same name in another project is not the service itself") {
    assert(!admits(web, Sender.Service("billing", "web")))
  }

  test("no service is admitted when none is named") {
    assert(!admits(web, Sender.Service("shop", "orders")))
  }

  test("a named service of this project is admitted, and an unnamed one is not") {
    val settings = web.copy(callers = Vector(Admitted.Service("shop", "orders")))
    assert(admits(settings, Sender.Service("shop", "orders")))
    assert(!admits(settings, Sender.Service("shop", "ledger")))
  }

  test("a service named in this project does not admit its namesake in another") {
    val settings = web.copy(callers = Vector(Admitted.Service("shop", "orders")))
    assert(!admits(settings, Sender.Service("billing", "orders")))
  }

  test("a service named with its project is admitted from that project") {
    val settings = web.copy(callers = Vector(Admitted.Service("billing", "invoices")))
    assert(admits(settings, Sender.Service("billing", "invoices")))
    assert(!admits(settings, Sender.Service("shop", "invoices")))
  }

  test("every service of this project admits any of them, and none of another project's") {
    val settings = web.copy(callers = Vector(Admitted.AnyInProject))
    assert(admits(settings, Sender.Service("shop", "orders")))
    assert(admits(settings, Sender.Service("shop", "anything")))
    assert(!admits(settings, Sender.Service("billing", "invoices")))
  }

  test("a sender is described as the process is told it") {
    assertEquals(Sender.describe(Sender.Internet(None)), "internet")
    assertEquals(Sender.describe(Sender.Internet(Some("a.example"))), "internet")
    assertEquals(Sender.describe(Sender.Service("shop", "orders")), "service shop/orders")
    assertEquals(Sender.describe(Sender.Local), "local")
  }
