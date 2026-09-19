package com.thinkmorestupidless.ankka.http

import scala.math.Ordering.Implicits.seqOrdering

/** Path parsing and dispatch precedence. */
class PathTemplateSuite extends munit.FunSuite:

  test("literal and parameter segments are parsed and rendered") {
    val template = PathTemplate.parse("/carts/{cartId}/items/{productId}")
    assertEquals(template.arity, 2)
    assertEquals(template.parameterNames, Vector("cartId", "productId"))
    assertEquals(template.render, "/carts/{cartId}/items/{productId}")
  }

  test("matching extracts parameters in declaration order") {
    val template = PathTemplate.parse("/carts/{cartId}/items/{productId}")
    assertEquals(template.matches(Vector("carts", "c1", "items", "p1")), Some(Vector("c1", "p1")))
    assertEquals(template.matches(Vector("carts", "c1", "items")), None)
    assertEquals(template.matches(Vector("orders", "c1", "items", "p1")), None)
  }

  test("a malformed or duplicated parameter is rejected at parse time") {
    intercept[IllegalArgumentException](PathTemplate.parse("/carts/pre{cartId}"))
    intercept[IllegalArgumentException](PathTemplate.parse("/carts/{id}/items/{id}"))
  }

  test("literal segments outrank parameters") {
    // Regression: `/chat/{session}` was declared before `/chat/awkward` and swallowed it,
    // so dispatch depended on declaration order.
    val literal = PathTemplate.parse("/awkward")
    val param   = PathTemplate.parse("/{session}")

    assert(
      Ordering[Vector[Int]].lt(literal.specificity, param.specificity),
      s"${literal.specificity} should sort before ${param.specificity}"
    )

    val sorted = Vector(param, literal).sortBy(_.specificity)
    assertEquals(sorted.head.render, "/awkward")
  }

  test("precedence is decided position by position, not by count") {
    // `/a/{x}/b` is more specific than `/a/{x}/{y}` at the segment that differs.
    val earlier = PathTemplate.parse("/a/{x}/b")
    val later   = PathTemplate.parse("/a/{x}/{y}")
    assert(Ordering[Vector[Int]].lt(earlier.specificity, later.specificity))
  }

  test("both routes still match; precedence only decides which is tried first") {
    val param = PathTemplate.parse("/{session}")
    assertEquals(param.matches(Vector("awkward")), Some(Vector("awkward")))
  }
