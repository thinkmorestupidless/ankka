package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/**
 * The read cursor an exporter reads the ring with: each span handed over once, nothing handed over
 * half-written, and every span that left the ring unread counted.
 */
final class RecorderCursorSuite extends FunSuite:

  private def record(r: Recorder, traceId: Long = 1L, component: Int = 1): Span =
    val span = r.begin(0L, traceId, 0L, component, 2, SpanKind.Internal)
    r.complete(span, SpanOutcome.Ok)
    span

  private def drain(cursor: Recorder.Cursor, max: Int = 1000): Vector[RecordedSpan] =
    val batch = cursor.read(max)
    cursor.commit(batch)
    batch.spans

  test("every span is read once, across reads") {
    val r      = Recorder(64)
    val cursor = r.cursor()
    val first  = (1 to 10).map(_ => record(r).id)
    val a      = drain(cursor)
    val second = (1 to 5).map(_ => record(r).id)
    val b      = drain(cursor)
    assertEquals(a.map(_.spanId), first.toVector)
    assertEquals(b.map(_.spanId), second.toVector)
    assertEquals(drain(cursor), Vector.empty)
    assertEquals(cursor.lost, 0L)
  }

  test("a batch that is not committed is read again") {
    val r      = Recorder(64)
    val cursor = r.cursor()
    (1 to 3).foreach(_ => record(r))
    val once  = cursor.read(100)
    val again = cursor.read(100)
    assertEquals(again.spans.map(_.spanId), once.spans.map(_.spanId))
  }

  test("what the ring overwrote before the cursor read it is counted lost, and the rest is read") {
    val r      = Recorder(8)
    val cursor = r.cursor()
    val ids    = (1 to 20).map(_ => record(r).id)
    val read   = drain(cursor)
    assertEquals(read.map(_.spanId), ids.takeRight(8).toVector)
    assertEquals(cursor.lost, 12L)
  }

  test("a span still in flight when passed is read once it completes, and holds nothing back") {
    val r      = Recorder(64)
    val cursor = r.cursor()
    val root   = r.begin(0L, 1L, 0L, 1, 2, SpanKind.Server)
    val child  = record(r)
    assertEquals(drain(cursor).map(_.spanId), Vector(child.id), "the child is not held back")
    r.complete(root, SpanOutcome.Ok)
    assertEquals(drain(cursor).map(_.spanId), Vector(root.id), "the root arrives when it ends")
    assertEquals(drain(cursor), Vector.empty)
  }

  test("a span in flight whose slot is reused before it completes is counted lost") {
    val r      = Recorder(4)
    val cursor = r.cursor()
    val _      = r.begin(0L, 1L, 0L, 1, 2, SpanKind.Internal)
    drain(cursor)
    (1 to 8).foreach(_ => record(r))
    drain(cursor)
    assertEquals(cursor.lost, 5L, "the stale span and the four the ring overwrote")
  }

  test("a read returns at most what it is asked for, and the next read carries on") {
    val r      = Recorder(64)
    val cursor = r.cursor()
    val ids    = (1 to 10).map(_ => record(r).id)
    val a      = drain(cursor, max = 4)
    val b      = drain(cursor, max = 4)
    val c      = drain(cursor, max = 4)
    assertEquals(a.size, 4)
    assertEquals((a ++ b ++ c).map(_.spanId), ids.toVector)
  }

  test("unread is what has begun and not been passed") {
    val r      = Recorder(64)
    val cursor = r.cursor()
    (1 to 7).foreach(_ => record(r))
    assertEquals(cursor.unread, 7L)
    drain(cursor)
    assertEquals(cursor.unread, 0L)
  }

  test(
    "under concurrent writers, what is read plus what is lost is everything, and nothing is torn"
  ) {
    val r              = Recorder(256)
    val cursor         = r.cursor()
    val writers        = 4
    @volatile var stop = false
    // Each writer's spans carry its number as both the component and the trace, so a span made of
    // two writers' fields shows as a mismatch.
    val threads = (1 to writers).map { w =>
      Thread.ofPlatform().unstarted { () =>
        while !stop do record(r, traceId = w.toLong, component = w): Unit
      }
    }
    threads.foreach(_.start())
    val read  = Vector.newBuilder[RecordedSpan]
    val until = System.nanoTime() + 200_000_000L
    while System.nanoTime() < until do read ++= drain(cursor, max = 128)
    stop = true
    threads.foreach(_.join())
    read ++= drain(cursor, max = Int.MaxValue)
    read ++= drain(cursor, max = Int.MaxValue)

    val spans = read.result()
    assertEquals(spans.size.toLong + cursor.lost, r.recorded)
    assertEquals(spans.map(_.spanId).distinct.size, spans.size, "a span was read twice")
    assert(spans.forall(s => s.traceId == s.componentRef.toLong), "a span was read torn")
  }
