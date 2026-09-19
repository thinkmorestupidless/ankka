package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/**
 * The ring: that it bounds memory, that it overwrites oldest-first, and that a reader is never
 * handed a span that was still being written.
 */
final class RecorderSuite extends FunSuite:

  private def record(
      r: Recorder,
      traceId: Long,
      parent: Long = 0L,
      outcome: SpanOutcome = SpanOutcome.Ok
  ) =
    val span = r.begin(traceId, parent, componentRef = 1, handlerRef = 2)
    r.complete(span, outcome)
    span

  test("a completed span is readable, with what was recorded about it") {
    val r    = Recorder(16)
    val span = record(r, traceId = 7L, outcome = SpanOutcome.Refused)
    val read = r.snapshot()

    assertEquals(read.size, 1)
    assertEquals(read.head.traceId, 7L)
    assertEquals(read.head.spanId, span.id)
    assertEquals(read.head.outcome, SpanOutcome.Refused)
    assert(read.head.durationNanos >= 0L, "a completed span has a duration")
  }

  test("capacity is a hard bound — the oldest are overwritten, not queued") {
    val r = Recorder(16)
    (1 to 100).foreach(i => record(r, traceId = i.toLong))

    assertEquals(r.snapshot().size, 16, "the ring never holds more than its capacity")
    assertEquals(r.recorded, 100L, "but it knows how many went through it")
    assert(r.oldestOverwritten, "and says so, which is what lets a reader admit it saw a window")
  }

  test("what survives overwriting is the newest, not an arbitrary sixteen") {
    val r = Recorder(16)
    (1 to 100).foreach(i => record(r, traceId = i.toLong))

    val traces = r.snapshot().map(_.traceId).toSet
    assertEquals(traces, (85L to 100L).toSet)
  }

  test("a span in flight is not readable — a reader never sees a half-written one") {
    val r = Recorder(16)
    val _ = r.begin(traceId = 1L, parentSpanId = 0L, componentRef = 1, handlerRef = 2)

    assertEquals(r.snapshot(), Vector.empty, "begun but not completed is not yet a fact")
  }

  test("completing a span whose slot was reused writes nothing") {
    val r     = Recorder(4)
    val stale = r.begin(traceId = 1L, parentSpanId = 0L, componentRef = 1, handlerRef = 2)
    // Five more spans wrap the ring and take the slot `stale` is holding.
    (2 to 6).foreach(i => record(r, traceId = i.toLong))
    r.complete(stale, SpanOutcome.Failed)

    assert(
      !r.snapshot().exists(_.spanId == stale.id),
      "a late completion must not resurrect itself on top of a newer span"
    )
  }

  test("spansOf returns one trace's spans, oldest first") {
    val r = Recorder(32)
    val a = record(r, traceId = 1L)
    val b = record(r, traceId = 2L)
    val c = record(r, traceId = 1L, parent = a.id)

    val spans = r.spansOf(1L)
    assertEquals(spans.map(_.spanId), Vector(a.id, c.id))
    assert(!spans.exists(_.spanId == b.id), "another trace's spans are not mixed in")
  }

  test("memory is flat in the number of requests, not growing with them (SC-004)") {
    def heldAfter(requests: Int): Long =
      val r = Recorder(4096)
      (1 to requests).foreach(i => record(r, traceId = i.toLong))
      System.gc()
      Thread.sleep(50)
      val rt = Runtime.getRuntime
      rt.totalMemory() - rt.freeMemory()

    val small = heldAfter(10_000)
    val large = heldAfter(100_000)

    assert(
      large <= small * 1.5,
      s"held memory went from $small to $large across ten times the traffic — that is not a ring"
    )
  }

  test("concurrent writers do not produce a torn span") {
    val r = Recorder(1024)
    val threads = (1 to 8).map { t =>
      Thread
        .ofVirtual()
        .unstarted(() => (1 to 2000).foreach(i => record(r, traceId = (t * 10000 + i).toLong)))
    }
    threads.foreach(_.start())
    threads.foreach(_.join())

    val read = r.snapshot()
    assert(read.size <= 1024)
    assert(
      read.forall(s => s.durationNanos >= 0L && s.spanId > 0L),
      "every span a reader is given is one that was finished being written"
    )
  }
