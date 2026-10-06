package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * The names a view's progress is recorded under (`contracts/rebuild.md`): an existing view keeps
 * its name, and no two views, versions or sources share one. A name that moved would make every
 * existing view read its whole source again on upgrade, which no suite on an empty database could
 * see; this one can.
 */
class ViewProjectionsSuite extends munit.FunSuite:

  private def plain(id: String, version: Int = 1) =
    ViewProjections.name(ComponentId(id), None, version)
  private def keyed(id: String, source: String, version: Int = 1) =
    ViewProjections.name(ComponentId(id), Some(ComponentId(source)), version)

  test("a plain view at version 1 is named exactly as its offsets have always been stored") {
    // The literal ProjectionRuntime built before views had versions; not derived from the function.
    assertEquals(plain("cart-rows"), "ankka-view-cart-rows")
  }

  test("the four forms") {
    assertEquals(plain("summary", 2), "ankka-view.v2-summary")
    assertEquals(keyed("shipments", "customer"), "ankka-keyed-view-shipments+customer")
    assertEquals(keyed("shipments", "customer", 3), "ankka-keyed-view.v3-shipments+customer")
  }

  test("no two views, versions or sources share a name, however their ids try") {
    val names = Vector(
      plain("summary", 2),
      plain("summary-v2"),
      plain("v2-summary"),
      plain("summary.v2"),
      plain("summary.v2", 2),
      plain("2-summary", 2),
      plain("summary", 23),
      plain("keyed-view-x"),
      keyed("x", "y"),
      keyed("a", "b-c"),
      keyed("a-b", "c"),
      keyed("a", "b-c", 2),
      keyed("a.v2-a", "b"),
      keyed("a", "b", 2)
    )
    assertEquals(
      names.distinct.size,
      names.size,
      names.groupBy(identity).filter(_._2.size > 1).keys
    )
  }

  test("a view's daemon process is named for version 1, whatever its version") {
    // Every instance runs the same daemon process, so its coordinator can run on the oldest.
    assertEquals(ViewProjections.daemon(ComponentId("summary"), None), "ankka-view-summary")
    assertEquals(
      ViewProjections.daemon(ComponentId("shipments"), Some(ComponentId("customer"))),
      "ankka-keyed-view-shipments+customer"
    )
  }

  test("a version below 1 is a programming error") {
    intercept[IllegalArgumentException](plain("summary", 0))
  }
