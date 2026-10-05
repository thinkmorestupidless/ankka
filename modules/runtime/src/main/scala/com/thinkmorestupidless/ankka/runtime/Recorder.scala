package com.thinkmorestupidless.ankka.runtime

import java.lang.invoke.VarHandle
import java.util.concurrent.ThreadLocalRandom
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
 * What a span was, as a collector sorts spans: the serving of a request from outside, a call to
 * another service, the handling of a message from a topic, or work inside one service.
 *
 * A collector draws an edge between two services by pairing a `Client` span with the `Server` span
 * that names it as its parent; nothing else can tell it which spans those are.
 */
enum SpanKind:
  case Internal, Server, Client, Consumer

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
 *     there are longs in an array. Exporting them is another module's work, through `cursor`.
 *   - **Nothing built on the hot path.** A span is ints and longs; no name is copied and nothing is
 *     allocated beyond the handle `begin` returns. Components and handlers arrive already reduced
 *     to integers — see `Names`, which documents the one map lookup that reduction costs, rather
 *     than pretending it is free. A reader turns them back into names later, when somebody is
 *     actually looking. `complete` also counts the span into `totals`: a hash, a probe and two
 *     atomic adds, because a count since the instance started cannot be read from a ring that
 *     forgets.
 *   - **Bounded, absolutely.** Capacity is the only knob. The oldest span is overwritten, so memory
 *     is a function of the capacity and nothing else — not of uptime, not of traffic. This is what
 *     lets always-on be a defensible default (SC-003, SC-004). The totals are bounded by the number
 *     of pairs of component and handler they hold, with one overflow entry beyond it; a cursor
 *     holds at most a ring's worth of sequences it is waiting on.
 *
 * Spans are written by many threads and read by occasional readers, so a reader never stops a
 * writer and accepts that it may catch a slot mid-write. A reader therefore validates what it reads
 * rather than trusting it: a slot's sequence is checked before and after its fields are read, and a
 * span read while its slot changed is dropped, its trace reported partial. Writers hold a slot
 * while they write it — one uncontended compare-and-set — because a writer that pauses mid-`begin`
 * for a whole lap of the ring would otherwise write its fields into a slot a newer span holds, and
 * the mixture would pass a reader's checks. The older span gives way: it is the one the ring has
 * already overwritten.
 *
 * Span ids start at a random number per recorder, so that two instances' spans in one trace do not
 * share ids; the counter is otherwise exactly as cheap as one that started at one.
 */
final class Recorder(val capacity: Int, countedHandlers: Int = Recorder.DefaultCountedHandlers):
  require(capacity > 0 && (capacity & (capacity - 1)) == 0, "capacity must be a power of two")

  private val mask = capacity - 1

  // One array per field rather than an array of objects: a span costs no allocation at all, and
  // writing one touches a handful of primitive slots.
  private val traceIdsHigh = new Array[Long](capacity)
  private val traceIds     = new Array[Long](capacity)
  private val spanIds      = new Array[Long](capacity)
  private val parentIds    = new Array[Long](capacity)
  private val componentRef = new Array[Int](capacity)
  private val handlerRef   = new Array[Int](capacity)
  private val kinds        = new Array[Byte](capacity)
  private val startedNanos = new Array[Long](capacity)
  private val durations    = new Array[Long](capacity)
  private val outcomes     = new Array[Byte](capacity)

  /**
   * 0 means "never written", `Recorder.Writing` that a writer holds the slot, `-seq` that the span
   * `seq` is in flight in it, and `seq` that the span is complete and may be read.
   */
  private val sequences = new Array[Long](capacity)

  private val next   = new AtomicLong(0L)
  private val spanId = new AtomicLong(ThreadLocalRandom.current().nextLong())

  /** When this recorder was made, on both clocks: what turns a span's start into a time of day. */
  private val anchorEpochNanos = System.currentTimeMillis() * 1_000_000L
  private val anchorNanoTime   = System.nanoTime()

  /** How many times each handler ran, and for how long, since this recorder was made. */
  val totals: InvocationTotals = InvocationTotals(countedHandlers)

  /** Total spans ever begun — `> capacity` means the oldest have been overwritten. */
  def recorded: Long = next.get()

  def oldestOverwritten: Boolean = recorded > capacity

  /** A span's start, `System.nanoTime()` as recorded, as nanoseconds since 1970. */
  def epochNanos(startedNanos: Long): Long = anchorEpochNanos + (startedNanos - anchorNanoTime)

  /** A span in a trace whose high half is zero, of no particular kind: the form tests use. */
  def begin(traceId: Long, parentSpanId: Long, componentRef: Int, handlerRef: Int): Span =
    begin(0L, traceId, parentSpanId, componentRef, handlerRef, SpanKind.Internal)

  /**
   * Claims a slot and starts timing. The returned handle is the slot and its sequence packed
   * together, so `complete` can refuse to write into a slot that has since been reused; it also
   * carries what `complete` counts, so a span whose slot was reused is still counted.
   *
   * @param parentSpanId
   *   the span this one is nested under; `0` for a root, and `Recorder.UnknownCaller` for a call
   *   that carried no trace, which a reader sees as a root whose caller is unknown.
   */
  def begin(
      traceIdHigh: Long,
      traceId: Long,
      parentSpanId: Long,
      componentRef: Int,
      handlerRef: Int,
      kind: SpanKind
  ): Span =
    val seq     = next.incrementAndGet()
    val slot    = ((seq - 1) & mask).toInt
    val id      = nextSpanId()
    val started = System.nanoTime()
    if hold(slot, seq) then
      traceIdsHigh(slot) = traceIdHigh
      traceIds(slot) = traceId
      spanIds(slot) = id
      parentIds(slot) = parentSpanId
      this.componentRef(slot) = componentRef
      this.handlerRef(slot) = handlerRef
      kinds(slot) = kind.ordinal.toByte
      startedNanos(slot) = started
      durations(slot) = -1L
      outcomes(slot) = SpanOutcome.Ok.ordinal.toByte
      Recorder.Sequences.setRelease(sequences, slot, -seq) // in flight: not to be read yet
    Span(slot, seq, id, traceIdHigh, traceId, componentRef, handlerRef, started)

  /** A span that is the root of a fresh trace: an entry point's, which nothing called. */
  def beginRoot(componentRef: Int, handlerRef: Int, kind: SpanKind = SpanKind.Internal): Span =
    begin(Trace.mintHigh(), Trace.mint(), 0L, componentRef, handlerRef, kind)

  /**
   * Takes `slot` for the span `seq` to write, unless a newer span already has it: then this span is
   * one the ring has overwritten, and is counted lost by whoever reads. Waits only on a writer in
   * the middle of its handful of stores.
   */
  private def hold(slot: Int, seq: Long): Boolean =
    var held    = false
    var decided = false
    while !decided do
      val current = Recorder.Sequences.getVolatile(sequences, slot).asInstanceOf[Long]
      if current == Recorder.Writing then Thread.onSpinWait()
      else if math.abs(current) > seq then decided = true
      else if Recorder.Sequences.compareAndSet(sequences, slot, current, Recorder.Writing) then
        held = true
        decided = true
    if held then VarHandle.storeStoreFence() // the mark before any field: a reader sees it change
    held

  private def nextSpanId(): Long =
    var id = spanId.incrementAndGet()
    while id == 0L || id == Recorder.UnknownCaller do id = spanId.incrementAndGet()
    id

  /**
   * Stops timing, counts the span, and publishes it. A slot reused since `begin` is left alone, and
   * the span is counted all the same: it happened, whether or not the window still holds it.
   */
  def complete(span: Span, outcome: SpanOutcome): Unit =
    val duration = System.nanoTime() - span.startedNanos
    totals.add(span.componentRef, span.handlerRef, outcome, duration)
    val slot = span.slot
    // Only while the slot is still this span's: one a newer span has taken is left alone.
    if Recorder.Sequences.compareAndSet(sequences, slot, -span.sequence, Recorder.Writing) then
      durations(slot) = duration
      outcomes(slot) = outcome.ordinal.toByte
      Recorder.Sequences.setRelease(sequences, slot, span.sequence) // publish: now it may be read

  /**
   * The span in `slot` if it is complete and is the one `seq` claimed, read so that a slot
   * overwritten while it was being read is refused rather than returned half one span and half
   * another.
   */
  private def readSlot(slot: Int, seq: Long): RecordedSpan | Null =
    if sequences(slot) != seq then null
    else
      VarHandle.loadLoadFence()
      val parent = parentIds(slot)
      val read = RecordedSpan(
        traceId = traceIds(slot),
        spanId = spanIds(slot),
        parentSpanId = if parent == Recorder.UnknownCaller then 0L else parent,
        componentRef = componentRef(slot),
        handlerRef = handlerRef(slot),
        startedNanos = startedNanos(slot),
        durationNanos = durations(slot),
        outcome = SpanOutcome.fromOrdinal(outcomes(slot).toInt),
        traceIdHigh = traceIdsHigh(slot),
        kind = SpanKind.fromOrdinal(kinds(slot).toInt),
        callerUnknown = parent == Recorder.UnknownCaller
      )
      VarHandle.loadLoadFence()
      if sequences(slot) == seq && read.durationNanos >= 0L then read else null

  /**
   * A span id for a span that will be recorded later, whole, by `record`. Claims no slot.
   *
   * For an invocation that may outlive the ring: a socket is open for as long as its client wants,
   * and a slot claimed by `begin` at the open would be reused by newer spans long before the close,
   * so the span would never be published. Its children name this id as their parent meanwhile.
   */
  def reserve(): Long = spanId.incrementAndGet()

  /**
   * Claims a slot and publishes a span at once: one that started at `startedNanos`
   * (`System.nanoTime` then) and ends now. The id is one `reserve` returned.
   */
  def record(
      traceId: Long,
      spanId: Long,
      parentSpanId: Long,
      componentRef: Int,
      handlerRef: Int,
      startedNanos: Long,
      outcome: SpanOutcome
  ): Unit =
    val seq  = next.incrementAndGet()
    val slot = ((seq - 1) & mask).toInt
    sequences(slot) = 0L // mark in-flight: a reader must not trust this slot yet
    traceIds(slot) = traceId
    spanIds(slot) = spanId
    parentIds(slot) = parentSpanId
    this.componentRef(slot) = componentRef
    this.handlerRef(slot) = handlerRef
    this.startedNanos(slot) = startedNanos
    durations(slot) = System.nanoTime() - startedNanos
    outcomes(slot) = outcome.ordinal.toByte
    sequences(slot) = seq // publish last: now a reader may trust it

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
      readSlot(((seq - 1) & mask).toInt, seq) match
        case null               => ()
        case span: RecordedSpan => builder += span
      seq -= 1
    builder.result()

  /** Every complete span of one trace, oldest first — what trace assembly consumes. */
  def spansOf(traceId: Long): Vector[RecordedSpan] =
    snapshot().filter(_.traceId == traceId).sortBy(_.startedNanos)

  /**
   * A reader that is handed each span once, in the order spans began, for as long as it keeps up
   * with the ring: what an exporter reads with. Several cursors read independently.
   */
  def cursor(): Recorder.Cursor = Recorder.Cursor(this)

  /**
   * One read of the ring from where a cursor stands, changing nothing: the complete spans after
   * `position` and among `pending`, oldest first and at most `max`; what the cursor would then wait
   * on; and how many spans fell out of the ring before it could read them.
   */
  private[runtime] def readFrom(
      position: Long,
      pending: Vector[Long],
      max: Int
  ): Recorder.Batch =
    val end     = next.get()
    val floor   = math.max(1L, end - capacity + 1)
    val spans   = Vector.newBuilder[RecordedSpan]
    val waiting = Vector.newBuilder[Long]
    var taken   = 0
    var lost    = 0L

    // What was in flight when last passed: complete now, still in flight, or overwritten.
    pending.foreach { seq =>
      if seq < floor then lost += 1
      else if taken >= max then waiting += seq
      else
        readSlot(((seq - 1) & mask).toInt, seq) match
          case null =>
            // Read again with the ring as it is now: a slot claimed since `end` was read is a
            // span this cursor will never see.
            if seq < math.max(1L, next.get() - capacity + 1) then lost += 1 else waiting += seq
          case span: RecordedSpan =>
            spans += span
            taken += 1
    }

    // What has begun since. Anything the ring has already overwritten is counted, not read.
    var seq = position + 1
    if seq < floor then
      lost += floor - seq
      seq = floor
    while seq <= end && taken < max do
      readSlot(((seq - 1) & mask).toInt, seq) match
        case null => waiting += seq
        case span: RecordedSpan =>
          spans += span
          taken += 1
      seq += 1

    val held = waiting.result()
    // A cursor waits on at most a ring's worth: anything older is overwritten by now anyway.
    val kept = if held.size > capacity then held.takeRight(capacity) else held
    Recorder.Batch(
      spans.result(),
      position = seq - 1,
      pending = kept,
      lost = lost + (held.size - kept.size)
    )

object Recorder:
  def apply(capacity: Int): Recorder = new Recorder(capacity)

  /** Atomic access to a slot's sequence, for the publish. */
  private val Sequences: VarHandle =
    java.lang.invoke.MethodHandles.arrayElementVarHandle(classOf[Array[Long]])

  /** A slot's sequence while a writer holds it: no sequence is this negative. */
  private val Writing: Long = Long.MinValue

  def apply(capacity: Int, countedHandlers: Int): Recorder = new Recorder(capacity, countedHandlers)

  /** Pairs of component and handler counted by name before the overflow entry takes the rest. */
  val DefaultCountedHandlers: Int = 1024

  /**
   * The parent of a span recorded for a call that carried no trace: a call made outside any
   * handler, whose caller the service cannot tell. No span has this id. A reader sees such a span
   * as a root, with `callerUnknown` set, and it is never given a parent by guessing.
   */
  val UnknownCaller: Long = Long.MinValue

  /**
   * A reader of a recorder that is handed each span once.
   *
   * `read` changes nothing: a batch that is not committed is read again, less whatever the ring has
   * overwritten meanwhile, which is then counted lost. So a failed export loses nothing the ring
   * still holds. A span that was still in flight when the cursor passed it is looked at again on
   * every read until it completes or is overwritten — a request's root span begins before its
   * children and ends after them, and a cursor that stopped at it would stall behind every long
   * request, while one that skipped it would never send a root.
   */
  final class Cursor private[Recorder] (recorder: Recorder):
    @volatile private var position: Long        = 0L
    @volatile private var pending: Vector[Long] = Vector.empty
    @volatile private var lostSoFar: Long       = 0L

    /** The next spans, at most `max`, without moving the cursor. */
    def read(max: Int): Batch = recorder.readFrom(position, pending, max)

    /** Moves the cursor past a batch it read. */
    def commit(batch: Batch): Unit =
      position = batch.position
      pending = batch.pending
      lostSoFar += batch.lost

    /** Spans that left the ring before this cursor read them, since it was made. */
    def lost: Long = lostSoFar

    /** Spans begun that this cursor has not passed: one read, for deciding to read sooner. */
    def unread: Long = recorder.recorded - position

  /** What one read of a cursor found. */
  final class Batch private[runtime] (
      val spans: Vector[RecordedSpan],
      private[runtime] val position: Long,
      private[runtime] val pending: Vector[Long],
      val lost: Long
  )

/** A claim on a ring slot, held between `begin` and `complete`. */
final case class Span(
    slot: Int,
    sequence: Long,
    id: Long,
    traceIdHigh: Long,
    traceId: Long,
    componentRef: Int,
    handlerRef: Int,
    startedNanos: Long
):
  /** The context a call made inside this span carries: this span's trace, and this span. */
  def context: TraceContext = TraceContext(traceIdHigh, traceId, id)

/**
 * A span read back out of the ring, with its identifiers still unresolved to names.
 *
 * `parentSpanId` is `0` for a root and for a span whose caller is unknown; `callerUnknown` tells
 * the two apart.
 */
final case class RecordedSpan(
    traceId: Long,
    spanId: Long,
    parentSpanId: Long,
    componentRef: Int,
    handlerRef: Int,
    startedNanos: Long,
    durationNanos: Long,
    outcome: SpanOutcome,
    traceIdHigh: Long = 0L,
    kind: SpanKind = SpanKind.Internal,
    callerUnknown: Boolean = false
)
