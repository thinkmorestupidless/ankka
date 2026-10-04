package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * How many times each handler ran, and for how long, since the instance started.
 *
 * The trace window cannot say this: it forgets, and what it loses it loses exactly when a service
 * is busiest. So the count is made where a span ends, by `Recorder.complete`, beside the span.
 *
 * Written on every invocation, so it is held to the recorder's own rules: no dependency, no
 * allocation, and bounded absolutely. Keys are a component's and a handler's interned numbers
 * packed into one long, in an open-addressed table claimed by compare-and-set; the counts are
 * atomic adds. A pair that arrives once the table holds `capacity` pairs is counted under one
 * overflow entry rather than refused, so the table can never fail and never grow. Pairs are bounded
 * anyway — components are registered and handlers declared — so the overflow is a guard, as
 * `(other services)` is for the services a service calls.
 */
final class InvocationTotals(val capacity: Int):
  require(capacity > 0, "capacity must be positive")

  /** Twice the pairs, as a power of two: probes stay short while the table fills. */
  private val slots = Integer.highestOneBit(math.max(2, capacity * 2 - 1)) << 1
  private val mask  = slots - 1

  // A key is the packed pair plus one, so that zero means an empty slot.
  private val keys    = AtomicLongArray(slots)
  private val claimed = AtomicInteger(0)

  // Per entry: one count per SpanOutcome, then the summed duration. The last entry is the overflow.
  private val Width    = SpanOutcome.values.length + 1
  private val counts   = AtomicLongArray((slots + 1) * Width)
  private val overflow = slots

  /** Counts one invocation of this handler. */
  def add(componentRef: Int, handlerRef: Int, outcome: SpanOutcome, durationNanos: Long): Unit =
    val entry = entryOf(componentRef, handlerRef)
    val base  = entry * Width
    counts.incrementAndGet(base + outcome.ordinal)
    counts.addAndGet(base + Width - 1, math.max(0L, durationNanos))
    ()

  private def entryOf(componentRef: Int, handlerRef: Int): Int =
    val key = ((componentRef.toLong & 0x7fffffffL) << 32 | (handlerRef.toLong & 0xffffffffL)) + 1L
    var at  = mix(key) & mask
    var probes = 0
    var found  = -1
    while found < 0 && probes < slots do
      val held = keys.get(at)
      if held == key then found = at
      else if held == 0L then
        if claimed.get() >= capacity then found = overflow
        else if keys.compareAndSet(at, 0L, key) then
          claimed.incrementAndGet()
          found = at
        else if keys.get(at) == key then found = at
        else
          at = (at + 1) & mask
          probes += 1
      else
        at = (at + 1) & mask
        probes += 1
    if found < 0 then overflow else found

  private def mix(key: Long): Int =
    val h = key * 0x9e3779b97f4a7c15L
    (h ^ (h >>> 32)).toInt

  /** Every pair counted so far, and the overflow entry if anything reached it. */
  def snapshot(): Vector[InvocationTotals.Entry] =
    val builder = Vector.newBuilder[InvocationTotals.Entry]
    var at      = 0
    while at < slots do
      val key = keys.get(at)
      if key != 0L then
        val packed = key - 1L
        builder += read(at, Some(((packed >>> 32).toInt, packed.toInt)))
      at += 1
    val spilled = read(overflow, None)
    if spilled.invocations > 0L then builder += spilled
    builder.result()

  private def read(entry: Int, pair: Option[(Int, Int)]): InvocationTotals.Entry =
    val base = entry * Width
    InvocationTotals.Entry(
      pair = pair,
      byOutcome = SpanOutcome.values.toVector.map(o => o -> counts.get(base + o.ordinal)).toMap,
      durationNanos = counts.get(base + Width - 1)
    )

object InvocationTotals:

  /**
   * One pair's counts. `pair` is the component's and the handler's interned numbers, or none for
   * the overflow entry, which a reader names `(other)`.
   */
  final case class Entry(
      pair: Option[(Int, Int)],
      byOutcome: Map[SpanOutcome, Long],
      durationNanos: Long
  ):
    def invocations: Long = byOutcome.values.sum
