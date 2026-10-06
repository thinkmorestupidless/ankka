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
      read.forall(s => s.durationNanos >= 0L && s.spanId != 0L),
      "every span a reader is given is one that was finished being written"
    )
  }

  // What a collector needs of a span, beyond what a console in the same process does

  test("a span begun with both halves of a trace id reads back with both") {
    val r    = Recorder(16)
    val span = r.begin(0x1234L, 0x5678L, 0L, 1, 2, SpanKind.Server)
    r.complete(span, SpanOutcome.Ok)
    val read = r.snapshot().head
    assertEquals((read.traceIdHigh, read.traceId), (0x1234L, 0x5678L))
    assertEquals(read.kind, SpanKind.Server)
    assertEquals(span.context, TraceContext(0x1234L, 0x5678L, span.id))
  }

  test("a span's kind is Internal unless it is said to be another") {
    val r = Recorder(16)
    record(r, traceId = 1L)
    assertEquals(r.snapshot().head.kind, SpanKind.Internal)
  }

  test("two recorders do not number their spans alike, and neither starts at one") {
    // Two instances' spans meet in one trace in a collector; a parent id must name one span.
    val a = record(Recorder(16), traceId = 1L).id
    val b = record(Recorder(16), traceId = 1L).id
    assertNotEquals(a, b)
    assertNotEquals(a, 1L)
    assertNotEquals(b, 1L)
  }

  test("no span id is zero or the unknown caller's mark") {
    val r   = Recorder(16)
    val ids = (1 to 10_000).map(_ => record(r, traceId = 1L).id)
    assert(!ids.contains(0L))
    assert(!ids.contains(Recorder.UnknownCaller))
  }

  test("a span's start is a time of day once the recorder's clocks are set against each other") {
    val r    = Recorder(16)
    val span = record(r, traceId = 1L)
    val now  = System.currentTimeMillis() * 1_000_000L
    assert(math.abs(r.epochNanos(span.startedNanos) - now) < 1_000_000_000L)
  }

  test("a span whose caller is unknown reads as a root that says so") {
    val r = Recorder(16)
    r.complete(r.begin(0L, 9L, Recorder.UnknownCaller, 1, 2, SpanKind.Internal), SpanOutcome.Ok)
    val read = r.snapshot().head
    assertEquals(read.parentSpanId, 0L)
    assert(read.callerUnknown)
    assert(!r.snapshot().exists(s => s.parentSpanId == Recorder.UnknownCaller))
  }

  test("a root that began a trace is not one whose caller is unknown") {
    val r = Recorder(16)
    record(r, traceId = 1L)
    assert(!r.snapshot().head.callerUnknown)
  }

  test("every span completed is counted, whatever the window still holds") {
    val r = Recorder(16)
    (1 to 1000).foreach(_ => record(r, traceId = 1L))
    val counted = r.totals.snapshot().map(_.invocations).sum
    assertEquals(counted, 1000L)
  }

  // A socket is open for as long as its client wants, so its span cannot hold a slot from the open
  // to the close: the ring reuses a slot once enough newer spans exist, and a reader skips one still
  // in flight. Its id is reserved at the open and the span recorded whole at the close.

  test("a reserved span has an id no begun span has, and claims no slot") {
    val r        = Recorder(16)
    val before   = record(r, traceId = 1L).id
    val reserved = r.reserve(0L, 1L, componentRef = 1, handlerRef = 2)
    val after    = record(r, traceId = 1L).id
    assert(Set(before, after).forall(_ != reserved.id), s"$before, ${reserved.id}, $after")
    assertEquals(r.snapshot().size, 2, "reserving claims no slot")
  }

  test("a span recorded whole is readable at once, with its start, kind, outcome and trace") {
    val r    = Recorder(16)
    val span = r.reserve(5L, 7L, componentRef = 3, handlerRef = 4)
    Thread.sleep(50)
    r.record(span, 0L, SpanKind.Server, SpanOutcome.Failed)
    val read = r.snapshot().find(_.spanId == span.id).getOrElse(fail("not recorded"))
    assertEquals(
      (read.traceIdHigh, read.traceId, read.componentRef, read.handlerRef),
      (5L, 7L, 3, 4)
    )
    assertEquals((read.kind, read.outcome), (SpanKind.Server, SpanOutcome.Failed))
    assertEquals(read.startedNanos, span.startedNanos)
    assert(read.durationNanos >= 50_000_000L, s"duration ${read.durationNanos}")
  }

  test(
    "a child of a reserved span is recorded under it, and its parent appears only when recorded"
  ) {
    val r     = Recorder(16)
    val root  = r.reserve(0L, 9L, 1, 2)
    val child = record(r, traceId = 9L, parent = root.id)
    assertEquals(r.spansOf(9L).map(_.spanId), Vector(child.id), "no root yet: the trace is partial")
    r.record(root, 0L, SpanKind.Server, SpanOutcome.Ok)
    val spans = r.spansOf(9L)
    assertEquals(spans.map(_.spanId).toSet, Set(root.id, child.id))
    assertEquals(spans.find(_.spanId == child.id).map(_.parentSpanId), Some(root.id))
  }

  test("a span recorded whole after the ring has wrapped many times is still published") {
    val r    = Recorder(4)
    val span = r.reserve(0L, 99L, 1, 2)
    (1 to 50).foreach(i => record(r, traceId = i.toLong))
    r.record(span, 0L, SpanKind.Server, SpanOutcome.Ok)
    assert(
      r.snapshot().exists(_.spanId == span.id),
      "a long socket's span is never lost to the ring"
    )
  }
