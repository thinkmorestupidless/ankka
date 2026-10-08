package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.graph.GraphDelta

/**
 * features/graph-deltas/sink.feature: the rules a store applies deltas under, on the reference
 * store. A store over a database proves the same cases in its own suite.
 */
class InMemoryGraphStoreSuite extends munit.FunSuite:

  import GraphFixtures.delta

  private def fresh = InMemoryGraphStore()

  test("a node delta creates the node with its labels, properties and version") {
    val store = fresh
    store(
      delta(
        """{"kind":"node","id":"cart:1","version":2,"labels":["Cart"],"properties":{"items":3,"open":true}}"""
      )
    )
    val cart = store.node("cart:1").get
    assertEquals(cart.version, 2L)
    assertEquals(cart.labels, Vector("Cart"))
    assertEquals(cart.properties("items"), 3L)
    assertEquals(cart.properties("open"), true)
    assert(!cart.deleted)
  }

  test("a higher version replaces labels and properties whole; an equal or lower one is stale") {
    val store = fresh
    store(
      delta(
        """{"kind":"node","id":"cart:1","version":2,"labels":["Cart","Open"],"properties":{"items":3,"note":"x"}}"""
      )
    )
    store(
      delta(
        """{"kind":"node","id":"cart:1","version":5,"labels":["Cart"],"properties":{"items":5}}"""
      )
    )
    assertEquals(store.node("cart:1").get.labels, Vector("Cart"))
    assertEquals(store.node("cart:1").get.properties, Map("items" -> 5L))
    store(delta("""{"kind":"node","id":"cart:1","version":5,"labels":["Stale"],"properties":{}}"""))
    store(delta("""{"kind":"node","id":"cart:1","version":3,"labels":["Older"],"properties":{}}"""))
    assertEquals(store.node("cart:1").get.version, 5L)
    assertEquals(store.node("cart:1").get.labels, Vector("Cart"))
  }

  test("an edge creates placeholder endpoints that the nodes' own deltas replace") {
    val store = fresh
    store(
      delta(
        """{"kind":"edge","id":"holds:1","version":1,"type":"HOLDS","from":"cart:1","to":"item:1","properties":{"qty":2}}"""
      )
    )
    assert(store.node("cart:1").exists(_.isPlaceholder))
    assert(store.node("item:1").exists(_.isPlaceholder))
    val edge = store.edge("holds:1").get
    assertEquals(edge.edgeType, Some("HOLDS"))
    assertEquals((edge.from, edge.to), (Some("cart:1"), Some("item:1")))
    store(delta("""{"kind":"node","id":"cart:1","version":4,"labels":["Cart"],"properties":{}}"""))
    assert(!store.node("cart:1").get.isPlaceholder)
    assertEquals(store.node("cart:1").get.version, 4L)
  }

  test(
    "a tombstone marks the element deleted and clears its labels and properties; a later merge revives it"
  ) {
    val store = fresh
    store(
      delta(
        """{"kind":"node","id":"cart:1","version":2,"labels":["Cart"],"properties":{"items":3}}"""
      )
    )
    store(delta("""{"kind":"tombstone","element":"node","id":"cart:1","version":3}"""))
    val gone = store.node("cart:1").get
    assert(gone.deleted)
    assertEquals(gone.version, 3L)
    assertEquals(gone.labels, Vector.empty)
    assertEquals(gone.properties, Map.empty)
    store(
      delta(
        """{"kind":"node","id":"cart:1","version":4,"labels":["Cart"],"properties":{"items":1}}"""
      )
    )
    assert(!store.node("cart:1").get.deleted)
    // A tombstone of an element never held is the same bare marker.
    store(delta("""{"kind":"tombstone","element":"node","id":"cart:9","version":1}"""))
    assertEquals(
      store.node("cart:9"),
      Some(InMemoryGraphStore.Element(GraphDelta.Element.Node, "cart:9", 1L, deleted = true))
    )
  }

  test("a node and an edge with the same id are different elements under different keys") {
    val store = fresh
    store(delta("""{"kind":"node","id":"x","version":1,"labels":[],"properties":{}}"""))
    store(
      delta(
        """{"kind":"edge","id":"x","version":1,"type":"T","from":"a","to":"b","properties":{}}"""
      )
    )
    assert(store.node("x").isDefined && store.edge("x").isDefined)
    assertEquals(store.elements.keySet, Set("node:x", "edge:x", "node:a", "node:b"))
  }

  test("two stores built from one topic in different batches compare equal") {
    val rows =
      GraphFixtures.rows("deltas.json").map(row => GraphFixtures.build(row.field("delta").get))
    val once  = fresh
    val twice = fresh
    rows.foreach(once.apply)
    rows.foreach(twice.apply)
    rows.foreach(twice.apply)
    assertEquals(once.elements, twice.elements)
    assert(once.elements.nonEmpty)
  }
