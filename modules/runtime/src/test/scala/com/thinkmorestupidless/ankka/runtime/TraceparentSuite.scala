package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/** The one reader and writer of W3C's `traceparent` every transport uses. */
final class TraceparentSuite extends FunSuite:

  // W3C Trace Context's own example.
  private val example = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

  test("W3C's example is read as its three numbers and written back the same") {
    val context = Traceparent.parse(example).get
    assertEquals(context.traceIdHex, "4bf92f3577b34da6a3ce929d0e0e4736")
    assertEquals(context.spanIdHex, "00f067aa0ba902b7")
    assertEquals(Traceparent.render(context), example)
  }

  test("the high half, the low half and the span are the numbers the hex names") {
    val context = Traceparent.parse(example).get
    assertEquals(context.traceIdHigh, java.lang.Long.parseUnsignedLong("4bf92f3577b34da6", 16))
    assertEquals(context.traceId, java.lang.Long.parseUnsignedLong("a3ce929d0e0e4736", 16))
    assertEquals(context.spanId, java.lang.Long.parseUnsignedLong("00f067aa0ba902b7", 16))
  }

  test("what is written pads both halves and the span to their full width") {
    assertEquals(
      Traceparent.render(TraceContext(0L, 1L, 2L)),
      "00-00000000000000000000000000000001-0000000000000002-01"
    )
  }

  test("a value that is not W3C's is no context") {
    val bad = Vector(
      "",
      example.dropRight(1),                                      // too short
      example + "-extra",                                        // version 00 has four fields
      example.toUpperCase,                                       // upper-case hex
      "00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01", // not hex
      "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // the zero trace
      "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // the zero span
      "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // the forbidden version
      "00_4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"  // not a dash
    )
    bad.foreach(value => assertEquals(Traceparent.parse(value), None, value))
  }

  test("a later version is read by its first four fields") {
    val later = "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-whatever"
    assertEquals(Traceparent.parse(later).map(_.spanIdHex), Some("00f067aa0ba902b7"))
  }

  test("what is written is read back") {
    val context = TraceContext(-5L, 77L, -9L)
    assertEquals(Traceparent.parse(Traceparent.render(context)), Some(context))
  }
