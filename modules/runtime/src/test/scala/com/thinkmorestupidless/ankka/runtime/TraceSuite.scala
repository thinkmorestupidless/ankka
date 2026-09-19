package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import munit.FunSuite

/**
 * Trace identity and assembly.
 *
 * Most of these cases pin the two rules that stop a console misleading its reader: unattributed
 * time is reported rather than redistributed, and an orphan span is never re-parented. Both
 * failures would produce a trace that *reads correctly and is wrong*, which is worse than a visible
 * hole.
 */
final class TraceSuite extends FunSuite:

  private def span(
      id: Long,
      parent: Long,
      started: Long,
      duration: Long,
      outcome: SpanOutcome = SpanOutcome.Ok
  ) = RecordedSpan(
    traceId = 1L,
    spanId = id,
    parentSpanId = parent,
    componentRef = 1,
    handlerRef = 2,
    startedNanos = started,
    durationNanos = duration,
    outcome = outcome
  )

  // Identity and propagation

  test("a minted trace is never zero, because zero means 'no parent'") {
    (1 to 1000).foreach(_ => assertNotEquals(Trace.mint(), 0L))
  }

  test("a trace round-trips through metadata, which is what crosses a node boundary") {
    val out = Trace.into(Metadata.empty, traceId = 0xdeadbeefL, parentSpanId = 42L)

    assertEquals(Trace.traceIdOf(out), Some(0xdeadbeefL))
    assertEquals(Trace.parentSpanIdOf(out), Some(42L))
  }

  test("a negative trace id survives the round trip") {
    // mint() returns any non-zero long, including negatives; hex must not lose the sign.
    val out = Trace.into(Metadata.empty, traceId = -1L, parentSpanId = -2L)
    assertEquals(Trace.traceIdOf(out), Some(-1L))
    assertEquals(Trace.parentSpanIdOf(out), Some(-2L))
  }

  test("metadata without a trace reads as absent, not as zero") {
    assertEquals(Trace.traceIdOf(Metadata.empty), None)
    assertEquals(Trace.parentSpanIdOf(Metadata.empty), None)
  }

  test("unparseable metadata reads as absent rather than throwing") {
    val junk = Metadata.empty.set(Trace.TraceIdKey, "not-a-number")
    assertEquals(Trace.traceIdOf(junk), None)
  }

  test("propagation does not disturb the application's own metadata") {
    val mine = Metadata.empty.set("x-request-id", "abc")
    val out  = Trace.into(mine, 1L, 2L)
    assertEquals(out.get("x-request-id"), Some("abc"))
  }

  // Assembly

  test("nesting is recovered from flat records") {
    val spans =
      Vector(span(1, 0, 0, 100), span(2, 1, 10, 30), span(3, 1, 50, 20), span(4, 2, 15, 5))
    val assembled = Trace.assemble(1L, spans, oldestOverwritten = false)

    assertEquals(assembled.roots.size, 1)
    val root = assembled.roots.head
    assertEquals(root.spanId, 1L)
    assertEquals(root.children.map(_.spanId), Vector(2L, 3L), "children in call order")
    assertEquals(root.children.head.children.map(_.spanId), Vector(4L), "and grandchildren")
  }

  test("unattributed time is reported, not redistributed across spans") {
    // A root that took 100 with a single child of 20: 80 went somewhere the platform cannot see.
    val assembled = Trace.assemble(1L, Vector(span(1, 0, 0, 100), span(2, 1, 10, 20)), false)

    assertEquals(assembled.durationNanos, 100L)
    assertEquals(assembled.unattributedNanos, 0L, "a root's own duration accounts for its children")

    // Two sibling roots, 30 apart: the gap between them is nobody's.
    val siblings = Trace.assemble(1L, Vector(span(1, 0, 0, 10), span(2, 0, 40, 10)), false)
    assertEquals(siblings.durationNanos, 50L)
    assertEquals(siblings.unattributedNanos, 30L)
    assertEquals(
      siblings.roots.map(_.durationNanos),
      Vector(10L, 10L),
      "and the spans keep their own durations — the gap is not spread over them"
    )
  }

  test("an orphan stays at the root with its parent marked unknown, never re-parented") {
    // span 2's parent (99) is not in this window — evicted, or on another node.
    val assembled = Trace.assemble(1L, Vector(span(1, 0, 0, 100), span(2, 99, 10, 20)), false)

    assertEquals(assembled.roots.size, 2, "the orphan is a root, not hung off the plausible parent")
    val orphan = assembled.roots.find(_.spanId == 2L).get
    assert(orphan.parentUnknown, "and it says its parent is unknown")
    assertEquals(orphan.parentSpanId, 99L, "keeping the id it actually claimed")

    val real = assembled.roots.find(_.spanId == 1L).get
    assert(!real.parentUnknown)
    assert(real.children.isEmpty, "the orphan was not adopted")
  }

  test("an orphan makes the trace partial — something is missing from this window") {
    val assembled = Trace.assemble(1L, Vector(span(2, 99, 10, 20)), oldestOverwritten = false)
    assert(assembled.partial)
  }

  test("a wrapped ring makes every trace partial, even a complete-looking one") {
    val assembled = Trace.assemble(1L, Vector(span(1, 0, 0, 100)), oldestOverwritten = true)
    assert(
      assembled.partial,
      "the window has dropped spans, so no trace from it can claim to be whole"
    )
  }

  test("a trace with no spans is empty and partial if the ring has wrapped") {
    assertEquals(Trace.assemble(1L, Vector.empty, oldestOverwritten = false).partial, false)
    assertEquals(Trace.assemble(1L, Vector.empty, oldestOverwritten = true).partial, true)
  }

  test("a failure is carried through assembly, so the console can name the component") {
    val assembled =
      Trace.assemble(1L, Vector(span(1, 0, 0, 100), span(2, 1, 10, 20, SpanOutcome.Failed)), false)
    assertEquals(assembled.roots.head.children.head.outcome, SpanOutcome.Failed)
  }

  test("assembly reads end to end out of a real recorder") {
    val recorder = Recorder(64)
    val traceId  = Trace.mint()

    val root = recorder.begin(traceId, 0L, componentRef = 1, handlerRef = 1)
    val kid  = recorder.begin(traceId, root.id, componentRef = 2, handlerRef = 2)
    recorder.complete(kid, SpanOutcome.Ok)
    recorder.complete(root, SpanOutcome.Ok)

    val assembled = Trace.assemble(traceId, recorder.spansOf(traceId), recorder.oldestOverwritten)
    assertEquals(assembled.roots.size, 1)
    assertEquals(assembled.roots.head.children.size, 1)
    assert(!assembled.partial)
  }
