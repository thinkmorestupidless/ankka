package com.thinkmorestupidless.ankka.sdk.graph

import com.thinkmorestupidless.ankka.core.graph.GraphElementRefused

/** Describing elements: what an author's calls give back, and what they refuse, where. */
class GraphElementsSuite extends munit.FunSuite:

  private val graph   = new GraphElements
  private val effects = new GraphEffects

  test("an element knows its kind, its id and the key it is published under") {
    val cart = graph.node("cart:c1", Seq("Cart"), Map("cartId" -> "c1"))
    assertEquals((cart.kind, cart.id, cart.key), ("node", "cart:c1", "node:cart:c1"))
    val edge = graph.edge("checked-out:c1", "CHECKED_OUT", from = "cart:c1", to = "checkout:c1")
    assertEquals((edge.kind, edge.id, edge.key), ("edge", "checked-out:c1", "edge:checked-out:c1"))
    // A tombstone has the key of the element it marks.
    assertEquals(graph.tombstoneNode("cart:c1").key, "node:cart:c1")
    assertEquals(graph.tombstoneNode("cart:c1").kind, "tombstone")
    assertEquals(graph.tombstoneEdge("e", "T", "a", "b").key, "edge:e")
  }

  test("an element is refused where it is described, saying what is wrong") {
    val failure = intercept[GraphElementRefused](graph.node("", Seq("Cart")))
    assertEquals(failure.why, "id")
    val reserved = intercept[GraphElementRefused](graph.node("n", properties = Map("id" -> "x")))
    assertEquals(reserved.why, "reserved")
    assert(reserved.getMessage.contains("'id'"), reserved.getMessage)
    val untyped = intercept[GraphElementRefused](graph.edge("e", "", "a", "b"))
    assertEquals(untyped.why, "endpoints")
    val mixed =
      intercept[GraphElementRefused](graph.node("n", properties = Map("p" -> Seq(1.5, 2.0))))
    assertEquals(mixed.why, "property-value")
    // Refusals are IllegalArgumentExceptions: a handler that does not catch them fails its change.
    assert(failure.isInstanceOf[IllegalArgumentException])
  }

  test("a stated version must be at least 1, and is refused where it is stated") {
    val node = graph.node("n")
    assertEquals(intercept[GraphElementRefused](node.at(0)).why, "version")
    assertEquals(intercept[GraphElementRefused](node.at(-3)).why, "version")
    assertEquals(node.at(1).stated, Some(1L))
    // Stating a version makes a new element; the one described is unchanged.
    assertEquals(node.stated, None)
  }

  test("a result takes the change's sequence number, or each element's stated version") {
    val effect = effects.publish(graph.node("a"), graph.node("b").at(40), graph.tombstoneNode("c"))
    val deltas = GraphEffect.resolve(effect, sequenceNumber = 7)
    assertEquals(
      deltas.map(d => d.key -> d.version),
      Vector("node:a" -> 7L, "node:b" -> 40L, "node:c" -> 7L)
    )
  }

  test(
    "the same element twice in one result is refused; a node and an edge sharing an id are not"
  ) {
    val twice = effects.publish(graph.node("n"), graph.tombstoneNode("n"))
    assertEquals(intercept[GraphElementRefused](GraphEffect.resolve(twice, 1)).why, "duplicate")
    val both = effects.publish(graph.node("x"), graph.edge("x", "T", "a", "b"))
    assertEquals(GraphEffect.resolve(both, 1).map(_.key), Vector("node:x", "edge:x"))
  }

  test("with no sequence number an element must state its version") {
    val failure =
      intercept[GraphElementRefused](GraphEffect.resolve(effects.publish(graph.node("n")), 0))
    assertEquals(failure.why, "no-sequence")
    assert(failure.getMessage.contains("state a version"), failure.getMessage)
    assertEquals(
      GraphEffect.resolve(effects.publish(graph.node("n").at(9)), 0).map(_.version),
      Vector(9L)
    )
  }

  test("publishing nothing, done and ignore come to no deltas") {
    assertEquals(GraphEffect.resolve(effects.publish(), 3), Vector.empty)
    assertEquals(GraphEffect.resolve(effects.done(), 3), Vector.empty)
    assertEquals(GraphEffect.resolve(effects.ignore(), 3), Vector.empty)
  }

  test("effects are values: building one publishes nothing and needs no change in hand") {
    assertEquals(effects.done(), GraphEffect.Done)
    assertEquals(effects.ignore(), GraphEffect.Ignore)
    val node = graph.node("n")
    assertEquals(effects.publish(node), GraphEffect.Publish(Seq(node)))
  }
