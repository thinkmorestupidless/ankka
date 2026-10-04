package com.thinkmorestupidless.ankka.proxy.core

/** Which service answers a path, and the path it receives. */
class MountsSuite extends munit.FunSuite:

  private val mounts = Mounts(
    Vector("/api/cart" -> "cart", "/api/orders" -> "orders", "/admin" -> "admin")
  )

  test("the contract's three examples") {
    assertEquals(mounts.find("/api/cart/carts/c1"), Some("cart" -> "/carts/c1"))
    assertEquals(mounts.find("/api/cart"), Some("cart" -> "/"))
    assertEquals(mounts.find("/api/cartoons"), None, "a mount matches whole segments")
  }

  test("several mounts, each matched to its own") {
    assertEquals(mounts.find("/api/orders/o1"), Some("orders" -> "/o1"))
    assertEquals(mounts.find("/admin/accounts"), Some("admin" -> "/accounts"))
    assertEquals(mounts.find("/api/cart/"), Some("cart" -> "/"))
  }

  test("a path under no mount is the process's") {
    for path <- Vector("/", "/about", "/api", "/api/", "/administrator") do
      assertEquals(mounts.find(path), None, path)
  }

  test("encoded and dot segments are passed through untouched") {
    // The mounted service, not the proxy, interprets its own path: the proxy only removes the
    // mount's prefix, and never decodes or normalises what follows it.
    assertEquals(mounts.find("/api/cart/a%2Fb"), Some("cart" -> "/a%2Fb"))
    assertEquals(mounts.find("/api/cart/../x"), Some("cart" -> "/../x"))
    assertEquals(mounts.find("/api/cart/./carts"), Some("cart" -> "/./carts"))
  }

  test("no mounts match nothing") {
    assertEquals(Mounts.none.find("/api/cart"), None)
  }
