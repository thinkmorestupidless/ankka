package com.thinkmorestupidless.ankka.core.graph

/**
 * What the builder refuses: every row of `refused.json`, the fixture all four SDKs share, for the
 * reason the row names — and the faults JSON cannot carry.
 */
class GraphRulesSuite extends munit.FunSuite:
  import GraphFixtures.*

  private val refused = rows("refused.json")

  private def sequenceOf(row: GraphJson): Long = row.field("sequence") match
    case Some(GraphJson.Num(n)) => n.toLongExact
    case _                      => 1L

  private def elementsOf(row: GraphJson): Vector[GraphJson] =
    row.field("elements") match
      case Some(GraphJson.Arr(items)) => items
      case _                          => Vector(row.field("element").get)

  test("the fixture names every reason a JSON row can show") {
    assertEquals(
      refused.map(text(_, "why")).toSet,
      Set(
        "id",
        "endpoints",
        "identifier",
        "reserved",
        "property-value",
        "integer-range",
        "version",
        "duplicate",
        "no-sequence"
      )
    )
  }

  refused.zipWithIndex.foreach { (row, index) =>
    val why = text(row, "why")
    test(s"refused.json row $index: ${text(row, "name")} — refused as '$why'") {
      val failure = intercept[GraphElementRefused] {
        GraphRules.resolve(elementsOf(row).map(describe), sequenceOf(row))
      }
      assertEquals(failure.why, why, failure.getMessage)
      assert(failure.getMessage.nonEmpty)
    }
  }

  test("a float that is not finite is refused, which JSON cannot say") {
    Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { number =>
      val failure = intercept[GraphElementRefused](GraphRules.node("n", Nil, Map("p" -> number)))
      assertEquals(failure.why, "property-value", failure.getMessage)
      val inList =
        intercept[GraphElementRefused](GraphRules.node("n", Nil, Map("p" -> Seq(1.5, number))))
      assertEquals(inList.why, "property-value", inList.getMessage)
    }
  }

  test("a refusal says which element and what about it") {
    val failure = intercept[GraphElementRefused] {
      GraphRules.node("cart:c1", Seq("Cart", "Shopping Cart"), Map.empty)
    }
    assert(failure.getMessage.contains("node 'cart:c1'"), failure.getMessage)
    assert(failure.getMessage.contains("'Shopping Cart'"), failure.getMessage)
  }

  test("a node and an edge that share an id are two elements, not one twice") {
    val result = GraphRules.resolve(
      Seq(
        GraphRules.node("x", Nil, Map.empty)           -> None,
        GraphRules.edge("x", "T", "a", "b", Map.empty) -> None
      ),
      sequenceNumber = 4
    )
    assertEquals(result.map(_.key), Vector("node:x", "edge:x"))
    assertEquals(result.map(_.version), Vector(4L, 4L))
  }

  test("an element takes the change's sequence number unless it states a version") {
    val result = GraphRules.resolve(
      Seq(
        GraphRules.node("a", Nil, Map.empty) -> None,
        GraphRules.node("b", Nil, Map.empty) -> Some(GraphRules.stated("node 'b'", 99))
      ),
      sequenceNumber = 7
    )
    assertEquals(result.map(d => d.id -> d.version), Vector("a" -> 7L, "b" -> 99L))
  }

  test("with no sequence number, a stated version is what makes an element publishable") {
    val element = GraphRules.node("a", Nil, Map.empty)
    val failure = intercept[GraphElementRefused](GraphRules.resolve(Seq(element -> None), 0))
    assertEquals(failure.why, "no-sequence")
    assert(failure.getMessage.contains("state a version"), failure.getMessage)
    assertEquals(GraphRules.resolve(Seq(element -> Some(5L)), 0).map(_.version), Vector(5L))
  }

  test("an empty result is an empty result") {
    assertEquals(GraphRules.resolve(Nil, 3), Vector.empty)
  }
