package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import scala.jdk.CollectionConverters.*

/** Why a call got no answer from a handler. */
enum Unanswered:
  /** Its caller stopped waiting for a reply. */
  case TimedOut

  /** No handler ever ran: the handler is not declared, or no instance could be reached. */
  case Undelivered

/**
 * How many times each handler called each handler, over a recent window.
 *
 * Counted from the two places that can see a call end. The host that ran the handler knows how it
 * ended (ok, refused, failed) and how long it took; the caller knows when no handler answered
 * (timed out, undelivered). They are different observers of one call and are kept as two sets of
 * numbers: a handler that throws sends no reply, so that call is failed where it ran and timed out
 * where it was made, and adding the two would count it twice.
 *
 * A key is four names as the recorder's integers, sixteen bits each: who called, from which
 * handler, what was called, and which handler of it. Names reach the recorder only after they have
 * been checked against what the service declared, so the number of keys is bounded by what was
 * registered and never by what was sent, and an entity id is not a name at all.
 *
 * Each key holds a ring of buckets, one per slice of the window. A write lands in the bucket for
 * the present moment, clearing it first if it last held an earlier one; a read adds up the buckets
 * that are still inside the window. Memory is keys times buckets times a fixed width, whatever the
 * traffic. Durations go into a histogram of powers of two, so percentiles are of buckets and are
 * reported as such, and so two instances' numbers can be added before a percentile is read.
 *
 * @param startedMillis
 *   when counting began. A window that reaches back before it says so: the counts are since then.
 */
final class CallCounts(val windowMillis: Long, buckets: Int, val startedMillis: Long):
  require(windowMillis > 0 && buckets > 0, "a window needs a length and at least one bucket")

  import CallCounts.*

  private val bucketMillis = math.max(1L, windowMillis / buckets)
  private val edges        = new ConcurrentHashMap[java.lang.Long, Edge]()

  /** A call a host ran a handler for, how the handler ended, and how long it took. */
  def handled(
      key: Long,
      outcome: SpanOutcome,
      durationNanos: Long,
      streaming: Boolean,
      nowMillis: Long
  ): Unit =
    val edge = edgeFor(key)
    val slot = edge.slotFor(nowMillis / bucketMillis)
    outcome match
      case SpanOutcome.Ok      => edge.add(slot, Ok)
      case SpanOutcome.Refused => edge.add(slot, Refused)
      // A host that ran a handler knows of no fourth way for it to end; a time-out recorded by a
      // host is a handler that did not finish, which is a failure of it.
      case SpanOutcome.Failed | SpanOutcome.TimedOut => edge.add(slot, Failed)
    edge.add(slot, Histogram + bucketOf(durationNanos))
    if streaming then edge.streaming = true

  /** A call no handler answered, as its caller or the platform saw it. */
  def unanswered(key: Long, kind: Unanswered, nowMillis: Long): Unit =
    val edge = edgeFor(key)
    val slot = edge.slotFor(nowMillis / bucketMillis)
    kind match
      case Unanswered.TimedOut    => edge.add(slot, TimedOut)
      case Unanswered.Undelivered => edge.add(slot, Undelivered)

  /** What the window holds now. A pair nothing in the window touched is not in it. */
  def snapshot(nowMillis: Long): Snapshot =
    val present = nowMillis / bucketMillis
    val oldest  = present - buckets + 1
    val pairs = edges.asScala.iterator.flatMap { (key, edge) =>
      val totals = edge.total(oldest, present)
      Option.when(totals.exists(_ != 0L))(
        Pair(
          callerComponent = part(key, 3),
          callerHandler = part(key, 2),
          calleeComponent = part(key, 1),
          calleeHandler = part(key, 0),
          ok = totals(Ok),
          refused = totals(Refused),
          failed = totals(Failed),
          timedOut = totals(TimedOut),
          undelivered = totals(Undelivered),
          histogram = totals.slice(Histogram, Histogram + HistogramBuckets).toVector,
          streaming = edge.streaming
        )
      )
    }.toVector
    Snapshot(
      windowSeconds = windowMillis / 1000,
      sinceMillis = math.max(startedMillis, nowMillis - windowMillis),
      pairs = pairs
    )

  /** How many pairs are held, in or out of the window: what memory is proportional to. */
  def size: Int = edges.size

  private def edgeFor(key: Long): Edge =
    val boxed    = java.lang.Long.valueOf(key)
    val existing = edges.get(boxed)
    if existing ne null then existing
    else
      val created = Edge(buckets)
      val raced   = edges.putIfAbsent(boxed, created)
      if raced ne null then raced else created

object CallCounts:

  /** The names a key may hold when a real one cannot be had. Interned first, so they are fixed. */
  val UnknownOrigin: String = "(unknown)"
  val Undeclared: String    = "(undeclared)"
  val OtherServices: String = "(other)"

  /** The largest id a key has room for. */
  val MaxNameId: Int = 0xffff

  /**
   * Four names in one number. `None` when a name's id does not fit, which a call is not failed for.
   */
  def key(
      callerComponent: Int,
      callerHandler: Int,
      calleeComponent: Int,
      calleeHandler: Int
  ): Option[Long] =
    val ids = Array(callerComponent, callerHandler, calleeComponent, calleeHandler)
    Option.when(ids.forall(id => id >= 0 && id <= MaxNameId))(
      ids.foldLeft(0L)((packed, id) => (packed << 16) | id.toLong)
    )

  private def part(key: java.lang.Long, index: Int): Int =
    ((key.longValue >>> (16 * index)) & 0xffffL).toInt

  // A bucket's counters, in this order, then the histogram.
  private val Ok            = 0
  private val Refused       = 1
  private val Failed        = 2
  private val TimedOut      = 3
  private val Undelivered   = 4
  private val Histogram     = 5
  val HistogramBuckets: Int = 32
  private val Width         = Histogram + HistogramBuckets

  /**
   * Which histogram bucket a duration falls in: bucket `i` holds `[2^(i-10), 2^(i-9))`
   * milliseconds, the first also everything shorter and the last everything longer. So the first
   * bucket ends at about two microseconds and the last begins at about thirty-five minutes.
   */
  def bucketOf(durationNanos: Long): Int =
    // In units of 2^-10 ms. A duration long enough to overflow this is in the last bucket anyway.
    val units =
      if durationNanos >= Long.MaxValue / 1024 then Long.MaxValue
      else durationNanos * 1024 / 1_000_000
    if units < 2 then 0
    else math.min(HistogramBuckets - 1, 63 - java.lang.Long.numberOfLeadingZeros(units))

  /** The upper edge of a histogram bucket, in milliseconds: what a percentile read from it says. */
  def upperMillis(bucket: Int): Double = math.pow(2, bucket - 9)

  /** One caller and handler, one callee and handler, and what the window saw between them. */
  final case class Pair(
      callerComponent: Int,
      callerHandler: Int,
      calleeComponent: Int,
      calleeHandler: Int,
      ok: Long,
      refused: Long,
      failed: Long,
      timedOut: Long,
      undelivered: Long,
      histogram: Vector[Long],
      streaming: Boolean
  ):
    def handled: Long    = ok + refused + failed
    def unanswered: Long = timedOut + undelivered

    /** The duration below which this share of the handled calls fell, as a bucket's upper edge. */
    def percentileMillis(share: Double): Double =
      val total = histogram.sum
      if total == 0 then 0.0
      else
        val wanted  = math.max(1L, math.ceil(total * share).toLong)
        var seen    = 0L
        var bucket  = 0
        var reached = false
        while !reached && bucket < histogram.size do
          seen += histogram(bucket)
          if seen >= wanted then reached = true else bucket += 1
        upperMillis(math.min(bucket, histogram.size - 1))

    def maxMillis: Double =
      histogram.lastIndexWhere(_ != 0L) match
        case -1     => 0.0
        case bucket => upperMillis(bucket)

  final case class Snapshot(windowSeconds: Long, sinceMillis: Long, pairs: Vector[Pair]):
    def handled: Long    = pairs.iterator.map(_.handled).sum
    def unanswered: Long = pairs.iterator.map(_.unanswered).sum

  object Snapshot:
    def empty(windowSeconds: Long, sinceMillis: Long): Snapshot =
      Snapshot(windowSeconds, sinceMillis, Vector.empty)

  /** One pair's ring of buckets. */
  private final class Edge(buckets: Int):
    // The slice of time each bucket last held; `Long.MinValue` for one never written.
    private val epochs               = new AtomicLongArray(buckets)
    private val counts               = new AtomicLongArray(buckets * Width)
    @volatile var streaming: Boolean = false

    (0 until buckets).foreach(epochs.set(_, Long.MinValue))

    /** The bucket for this slice of time, emptied first if it last held an earlier one. */
    def slotFor(epoch: Long): Int =
      val slot = Math.floorMod(epoch, buckets.toLong).toInt
      if epochs.get(slot) != epoch then
        // Once per bucket per slice of time, so the lock is not on the path a call usually takes.
        synchronized {
          if epochs.get(slot) != epoch then
            var field = 0
            while field < Width do
              counts.set(slot * Width + field, 0L)
              field += 1
            epochs.set(slot, epoch)
        }
      slot

    def add(slot: Int, field: Int): Unit = counts.incrementAndGet(slot * Width + field): Unit

    /** Every counter, added up over the buckets that hold a slice of time inside the window. */
    def total(oldest: Long, present: Long): Array[Long] =
      val totals = new Array[Long](Width)
      var slot   = 0
      while slot < buckets do
        val epoch = epochs.get(slot)
        if epoch >= oldest && epoch <= present then
          var field = 0
          while field < Width do
            totals(field) += counts.get(slot * Width + field)
            field += 1
        slot += 1
      totals
