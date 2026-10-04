package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/** Counts since the instance started, by handler and outcome, bounded absolutely. */
final class InvocationTotalsSuite extends FunSuite:

  private def counts(t: InvocationTotals) =
    t.snapshot().map(e => e.pair -> e).toMap

  test("each pair is counted by outcome, with its durations summed") {
    val t = InvocationTotals(16)
    t.add(1, 2, SpanOutcome.Ok, 100L)
    t.add(1, 2, SpanOutcome.Ok, 50L)
    t.add(1, 2, SpanOutcome.Refused, 10L)
    t.add(3, 4, SpanOutcome.Failed, 7L)
    val by = counts(t)
    assertEquals(by(Some((1, 2))).byOutcome(SpanOutcome.Ok), 2L)
    assertEquals(by(Some((1, 2))).byOutcome(SpanOutcome.Refused), 1L)
    assertEquals(by(Some((1, 2))).durationNanos, 160L)
    assertEquals(by(Some((3, 4))).byOutcome(SpanOutcome.Failed), 1L)
    assertEquals(by(Some((3, 4))).byOutcome(SpanOutcome.Ok), 0L)
  }

  test("the zeroth component and handler are a pair like any other") {
    val t = InvocationTotals(4)
    t.add(0, 0, SpanOutcome.Ok, 1L)
    assertEquals(counts(t)(Some((0, 0))).invocations, 1L)
  }

  test("totals do not depend on the window: a ring of 16 counts 1000 spans") {
    val r = Recorder(16)
    (1 to 1000).foreach(_ => r.complete(r.begin(1L, 0L, 1, 2), SpanOutcome.Ok))
    assertEquals(r.totals.snapshot().map(_.invocations).sum, 1000L)
  }

  test("a span whose slot was reused before it completed is still counted, with its duration") {
    val r     = Recorder(4)
    val stale = r.begin(1L, 0L, 5, 6)
    (1 to 8).foreach(_ => r.complete(r.begin(1L, 0L, 1, 2), SpanOutcome.Ok))
    Thread.sleep(2)
    r.complete(stale, SpanOutcome.TimedOut)
    val entry = counts(r.totals)(Some((5, 6)))
    assertEquals(entry.byOutcome(SpanOutcome.TimedOut), 1L)
    assert(entry.durationNanos >= 1_000_000L)
  }

  test("pairs beyond the capacity are counted under the overflow, and no count is lost") {
    val t = InvocationTotals(4)
    (1 to 6).foreach(i => t.add(i, i, SpanOutcome.Ok, 1L))
    val all = t.snapshot()
    assertEquals(all.count(_.pair.isDefined), 4)
    assertEquals(all.find(_.pair.isEmpty).map(_.invocations), Some(2L))
    assertEquals(all.map(_.invocations).sum, 6L)
  }

  test("eight threads adding to one pair lose no increment") {
    val t = InvocationTotals(16)
    val threads = (1 to 8).map(_ =>
      Thread
        .ofPlatform()
        .unstarted(() => (1 to 10_000).foreach(_ => t.add(7, 8, SpanOutcome.Ok, 1L)))
    )
    threads.foreach(_.start())
    threads.foreach(_.join())
    val entry = counts(t)(Some((7, 8)))
    assertEquals(entry.invocations, 80_000L)
    assertEquals(entry.durationNanos, 80_000L)
  }

  test("threads claiming different pairs at once each get their own") {
    val t = InvocationTotals(64)
    val threads = (1 to 8).map(w =>
      Thread
        .ofPlatform()
        .unstarted(() =>
          (1 to 8).foreach(h => (1 to 4).foreach(_ => t.add(w, h, SpanOutcome.Ok, 1L)))
        )
    )
    threads.foreach(_.start())
    threads.foreach(_.join())
    val all = t.snapshot()
    assertEquals(all.map(_.invocations).sum, 256L)
    assertEquals(all.count(_.pair.isDefined), 64)
  }
