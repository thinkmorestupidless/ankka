package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentId, ComponentKind}

/**
 * The one function that names a topic source's consumer group.
 *
 * Two services that share a group each receive half a topic's messages and nothing says so, so the
 * property that matters most is the second one here: different inputs, different names, whatever a
 * component happens to be called.
 */
class ConsumerGroupsSuite extends munit.FunSuite:

  import ComponentKind.{Consumer, View}

  private val deployed = ServiceIdentity.deployed("shop", "orders")
  private val named    = ServiceIdentity.local("orders")
  private val unnamed  = ServiceIdentity.unnamed

  private def name(identity: ServiceIdentity, kind: ComponentKind, id: String, version: Int = 1) =
    ConsumerGroups.name(identity, kind, ComponentId(id), version)

  test("a deployed service's group is named for its project, its service, its kind and its id") {
    assertEquals(name(deployed, View, "summary"), "ankka.shop.orders.view.summary")
    assertEquals(name(deployed, Consumer, "summary"), "ankka.shop.orders.consumer.summary")
    assertEquals(name(deployed, View, "summary", 2), "ankka.shop.orders.view-v2.summary")
  }

  test("a local service that states its name has a group named for it") {
    assertEquals(name(named, View, "summary"), "ankka.local.orders.view.summary")
    assertEquals(name(named, Consumer, "summary"), "ankka.local.orders.consumer.summary")
    assertEquals(name(named, Consumer, "summary", 3), "ankka.local.orders.consumer-v3.summary")
  }

  test("a local service that states no name has a group named for its kind and id alone") {
    // Exactly the names every group had before services were named, so such a service keeps
    // reading where it was.
    assertEquals(name(unnamed, View, "summary"), "ankka-view-summary")
    assertEquals(name(unnamed, Consumer, "summary"), "ankka-consumer-summary")
    assertEquals(name(unnamed, View, "summary", 2), "ankka-view.v2-summary")
  }

  test("version 1 and no version are the same name") {
    for identity <- Seq(deployed, named, unnamed) do
      assertEquals(
        name(identity, View, "summary", 1),
        ConsumerGroups.name(identity, View, ComponentId("summary"))
      )
  }

  test("distinct inputs give distinct names, whatever the component is called") {
    val ids = Vector(
      "summary",
      "v2",
      "v2.summary",
      "v2-summary",
      "view",
      "view.summary",
      "view-v2.summary",
      "consumer",
      "consumer.v2",
      "2-summary",
      "summary.v2",
      "a.b.c",
      "a_b",
      "a-b",
      "orders.view.summary",
      "shop.orders.view.summary"
    )
    val identities = Vector(
      deployed,
      ServiceIdentity.deployed("shop", "orders2"),
      ServiceIdentity.deployed("shop-orders", "x"),
      ServiceIdentity.deployed("orders", "shop"),
      named,
      ServiceIdentity.local("shop"),
      unnamed
    )
    val inputs =
      for
        identity <- identities
        kind     <- Vector(View, Consumer)
        id       <- ids
        version  <- Vector(1, 2, 10, 22)
      yield (identity, kind, id, version)
    val byName = inputs.groupBy((i, k, c, v) => name(i, k, c, v))
    val shared = byName.filter(_._2.size > 1)
    assert(shared.isEmpty, shared.map((n, ins) => s"$n <- $ins").mkString("\n"))
  }

  test(
    "every group of a deployed service starts with its project and service, and no other's does"
  ) {
    val ids = Vector("summary", "x.y", "view", "orders.view.summary")
    def all(identity: ServiceIdentity) =
      for kind <- Vector(View, Consumer); id <- ids; v <- Vector(1, 2)
      yield name(identity, kind, id, v)
    assert(all(deployed).forall(_.startsWith("ankka.shop.orders.")))
    for other <- Seq(
        ServiceIdentity.deployed("shop", "orders2"),
        ServiceIdentity.deployed("shop-orders", "x"),
        ServiceIdentity.deployed("shopx", "orders"),
        named,
        unnamed
      )
    do assert(all(other).forall(!_.startsWith("ankka.shop.orders.")), other.toString)
  }

  test("the longest permitted ids make a name of 277 characters in a broker's alphabet") {
    val longest = name(
      ServiceIdentity.deployed("p" * 57, "s" * 63),
      Consumer,
      "c" * 128,
      Int.MaxValue
    )
    assertEquals(longest.length, 277)
    assert(longest.forall(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '_'), longest)
    assertEquals(ConsumerGroups.Longest, longest)
  }

  test("only views and consumers have groups") {
    intercept[IllegalArgumentException](name(deployed, ComponentKind.Workflow, "x")): Unit
  }

  test("a version below 1 is not a version") {
    intercept[IllegalArgumentException](name(deployed, View, "x", 0)): Unit
  }
