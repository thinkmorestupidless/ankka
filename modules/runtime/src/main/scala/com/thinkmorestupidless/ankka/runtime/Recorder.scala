package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * How an invocation ended, as a span records it. A byte on the hot path, not a string.
 *
 * `SpanOutcome` and not `Outcome` because `core.effect.Outcome` already exists and means something
 * else — what materialising an effect produced. Two types called `Outcome` in one file would be
 * read as one type by everyone who came after.
 *
 * `Refused` is distinct from `Failed` because a refusal is the platform working correctly — an ACL
 * or a validation saying no — and a console that shows the two the same way teaches the reader that
 * red means broken, which is how real failures get ignored.
 */
enum SpanOutcome:
  case Ok, Failed, Refused, TimedOut

/**
 * Every component invocation the runtime interprets, in a fixed-size ring.
 *
 * This exists here, in `runtime`, because effects are inert data that the runtime interprets — so
 * the runtime is the one place that already sees every invocation. No component author writes
 * instrumentation and no existing component changed to be observed.
 *
 * Three constraints shape every line of it, and none of them are style:
 *
 *   - **No dependency.** `ankka-runtime` is published, so anything added here lands in the build of
 *     every application using the platform. There is no metrics library and no tracing library;
 *     there are longs in an array.
 *   - **Nothing built on the hot path.** A span is ints and longs; no name is copied and nothing is
 *     allocated. Components and handlers arrive already reduced to integers — see `Names`, which
 *     documents the one map lookup that reduction costs, rather than pretending it is free. A
 *     reader turns them back into names later, when somebody is actually looking.
 *   - **Bounded, absolutely.** Capacity is the only knob. The oldest span is overwritten, so memory
 *     is a function of the capacity and nothing else — not of uptime, not of traffic. This is what
 *     lets always-on be a defensible default (SC-003, SC-004).
 *
 * Spans are written by many threads and read by one occasional reader, so the ring takes writes
 * with a single atomic increment and accepts that a reader may catch a slot mid-write. A reader
 * therefore validates what it reads rather than trusting it; a torn span is dropped, and its trace
 * is reported partial. Locking writers to give a reader a perfect view would be paying on the hot
 * path for the benefit of the cold one.
 */
final class Recorder(val capacity: Int):
  require(capacity > 0 && (capacity & (capacity - 1)) == 0, "capacity must be a power of two")

  private val mask = capacity - 1

  // One array per field rather than an array of objects: a span costs no allocation at all, and
  // writing one touches a handful of primitive slots.
  private val traceIds     = new Array[Long](capacity)
  private val spanIds      = new Array[Long](capacity)
  private val parentIds    = new Array[Long](capacity)
  private val componentRef = new Array[Int](capacity)
  private val handlerRef   = new Array[Int](capacity)
  private val startedNanos = new Array[Long](capacity)
  private val durations    = new Array[Long](capacity)
  private val outcomes     = new Array[Byte](capacity)

  /** 0 means "never written"; otherwise the sequence that claimed the slot. Guards torn reads. */
  private val sequences = new Array[Long](capacity)

  private val next   = new AtomicLong(0L)
  private val spanId = new AtomicLong(0L)

  /** Total spans ever begun — `> capacity` means the oldest have been overwritten. */
  def recorded: Long = next.get()

  def oldestOverwritten: Boolean = recorded > capacity

  /**
   * Claims a slot and starts timing. The returned handle is the slot and its sequence packed
   * together, so `complete` can refuse to write into a slot that has since been reused.
   */
  def begin(traceId: Long, parentSpanId: Long, componentRef: Int, handlerRef: Int): Span =
    val seq  = next.incrementAndGet()
    val slot = ((seq - 1) & mask).toInt
    val id   = spanId.incrementAndGet()
    sequences(slot) = 0L // mark in-flight: a reader must not trust this slot yet
    traceIds(slot) = traceId
    spanIds(slot) = id
    parentIds(slot) = parentSpanId
    this.componentRef(slot) = componentRef
    this.handlerRef(slot) = handlerRef
    startedNanos(slot) = System.nanoTime()
    durations(slot) = -1L
    outcomes(slot) = SpanOutcome.Ok.ordinal.toByte
    Span(slot, seq, id, traceId)

  /** Stops timing and publishes the span. A slot reused since `begin` is left alone. */
  def complete(span: Span, outcome: SpanOutcome): Unit =
    val slot = span.slot
    if spanIds(slot) == span.id then
      durations(slot) = System.nanoTime() - startedNanos(slot)
      outcomes(slot) = outcome.ordinal.toByte
      sequences(slot) = span.sequence // publish last: now a reader may trust it

  /**
   * Every complete span currently held, newest first.
   *
   * Spans still in flight and spans torn by a concurrent overwrite are skipped rather than reported
   * half-read — a reader that returned a half-written span would produce a trace that looks whole
   * and is not.
   */
  def snapshot(): Vector[RecordedSpan] =
    val end     = next.get()
    val builder = Vector.newBuilder[RecordedSpan]
    var seq     = end
    val floor   = math.max(1L, end - capacity + 1)
    while seq >= floor do
      val slot = ((seq - 1) & mask).toInt
      if sequences(slot) == seq && durations(slot) >= 0L then
        builder += RecordedSpan(
          traceId = traceIds(slot),
          spanId = spanIds(slot),
          parentSpanId = parentIds(slot),
          componentRef = componentRef(slot),
          handlerRef = handlerRef(slot),
          startedNanos = startedNanos(slot),
          durationNanos = durations(slot),
          outcome = SpanOutcome.fromOrdinal(outcomes(slot).toInt)
        )
      seq -= 1
    builder.result()

  /** Every complete span of one trace, oldest first — what trace assembly consumes. */
  def spansOf(traceId: Long): Vector[RecordedSpan] =
    snapshot().filter(_.traceId == traceId).sortBy(_.startedNanos)

object Recorder:
  def apply(capacity: Int): Recorder = new Recorder(capacity)

/** A claim on a ring slot, held between `begin` and `complete`. Carries no state of its own. */
final case class Span(slot: Int, sequence: Long, id: Long, traceId: Long)

/** A span read back out of the ring, with its identifiers still unresolved to names. */
final case class RecordedSpan(
    traceId: Long,
    spanId: Long,
    parentSpanId: Long,
    componentRef: Int,
    handlerRef: Int,
    startedNanos: Long,
    durationNanos: Long,
    outcome: SpanOutcome
)
