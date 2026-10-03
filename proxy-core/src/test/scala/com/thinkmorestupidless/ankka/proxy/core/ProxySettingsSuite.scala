package com.thinkmorestupidless.ankka.proxy.core

import ProxySettings.Variables.*

/** What a proxy reads from the environment the operator gives it. */
class ProxySettingsSuite extends munit.FunSuite:

  private val example = Map(
    Project         -> "shop",
    Service         -> "web",
    Port            -> "9000",
    ProcessPort     -> "3000",
    Mounts          -> "/api/cart=cart,/api/orders=orders",
    Callers         -> "orders,billing/invoices,*",
    PublicAuthority -> "web-shop.example.test",
    NamespacePrefix -> "ankka"
  )

  private def read(env: Map[String, String]) = ProxySettings.fromEnvironment(env.get)

  test("the data model's example is read whole") {
    assertEquals(
      read(example),
      Right(
        ProxySettings(
          project = "shop",
          service = "web",
          port = 9000,
          processPort = 3000,
          mounts = Vector("/api/cart" -> "cart", "/api/orders" -> "orders"),
          callers = Vector(
            Admitted.Service("shop", "orders"),
            Admitted.Service("billing", "invoices"),
            Admitted.AnyInProject
          ),
          publicAuthority = Some("web-shop.example.test")
        )
      )
    )
  }

  test("no mounts, no callers and no public authority are none, not problems") {
    val settings = read(example + (Mounts -> "") + (Callers -> "") - PublicAuthority)
    assertEquals(
      settings.map(s => (s.mounts, s.callers, s.publicAuthority)),
      Right((Vector.empty, Vector.empty, None))
    )
  }

  test("what the operator writes reads back as what it wrote") {
    val mounts = Vector("/api/cart" -> "cart", "/a/b" -> "c")
    val read   = this.read(example + (Mounts -> ProxySettings.encodeMounts(mounts)))
    assertEquals(read.map(_.mounts), Right(mounts))
  }

  test("every problem is reported at once, each naming its variable") {
    val problems = read(
      example - Service + (Port -> "port") + (Mounts -> "/api/cart") + (Callers -> "Bad Name")
    ).left.getOrElse(fail("read"))
    assertEquals(problems.size, 4, problems)
    for variable <- Vector(Service, Port, Mounts, Callers) do
      assert(problems.exists(_.startsWith(variable)), s"$variable: $problems")
  }
