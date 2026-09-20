package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata

import java.util.concurrent.ThreadLocalRandom

/**
 * One inbound request and the tree of invocations it caused, assembled on demand.
 *
 * Assembly happens at read time, never at write time: the hot path writes flat records and the
 * expensive shaping is paid for by whoever is actually looking.
 *
 * Two rules here are the difference between a console that helps and one that misleads, and both
 * exist because the alternative produces something that *reads correctly and is wrong*:
 *
 *   - **`unattributed` is reported, never redistributed.** It is the elapsed time no span accounts
 *     for — a model call, a database wait, work handed to another thread that the thread-local
 *     request context cannot follow. It is usually the answer: Akka's own console example is a
 *     request that turned out to be 99.9% waiting on a model. Spreading it across spans to make the
 *     percentages tidy would hide exactly the finding the panel exists to surface.
 *
 * It is measured **per span**, as the gap between a span's own duration and the sum of its
 * children's, and that is the whole of the rule: measuring it only at the trace level reports zero
 * for every single-root trace, because the root by definition covers the entire elapsed time. Every
 * ordinary HTTP request is exactly that shape, so the number was zero on every trace a developer
 * would ever look at while 99% of the time went unexplained — the panel's headline finding,
 * silently absent. A leaf span has no gap to report: its duration is attributed to it, and a second
 * row under every entity call would say nothing.
 *   - **An orphan stays an orphan.** A span whose parent is gone from the ring, or was never
 *     recorded, is returned at the root with its parent marked unknown. Attaching it to the most
 *     recent plausible parent would produce a tree that looks complete and describes something that
 *     never happened.
 */
final case class Trace(
    traceId: Long,
    startedNanos: Long,
    durationNanos: Long,
    roots: Vector[TraceSpan],
    unattributedNanos: Long,
    partial: Boolean
)

/** A span in an assembled trace, with its children and its identifiers still unresolved. */
final case class TraceSpan(
    spanId: Long,
    parentSpanId: Long,
    componentRef: Int,
    handlerRef: Int,
    startedNanos: Long,
    durationNanos: Long,
    outcome: SpanOutcome,
    children: Vector[TraceSpan],
    parentUnknown: Boolean,
    unattributedNanos: Long
)

/**
 * Trace identity, and how it travels.
 *
 * It travels as `Metadata`, and that is the whole mechanism — no protocol type changes, and
 * `EntityProtocol.Command` in particular is untouched. `ComponentClient` already carries `Metadata`
 * on every call, `ShardingTransport` already wraps it as `MetaEntry`, `MetaEntry` already extends
 * `AnkkaSerializable`, and that binding is already jackson-cbor. So a trace identifier crosses a
 * node boundary for free, because the thing carrying it already did.
 *
 * The keys are namespaced so an application cannot collide with them by accident, and so a reader
 * of a service's metadata can tell platform bookkeeping from the application's own.
 */
object Trace:

  val TraceIdKey: String = "ankka-trace-id"
  val SpanIdKey: String  = "ankka-span-id"

  /** A fresh trace. Minted at an entry point — an HTTP request, a projection, a timer. */
  def mint(): Long =
    var id = 0L
    while id == 0L do id = ThreadLocalRandom.current().nextLong()
    id

  /** Writes this trace and the span that will parent the callee's work into outbound metadata. */
  def into(metadata: Metadata, traceId: Long, parentSpanId: Long): Metadata =
    metadata
      .set(TraceIdKey, java.lang.Long.toHexString(traceId))
      .set(SpanIdKey, java.lang.Long.toHexString(parentSpanId))

  /** Reads a trace from inbound metadata, if the caller was carrying one. */
  def traceIdOf(metadata: Metadata): Option[Long] = parse(metadata.get(TraceIdKey))

  /** Reads the caller's span, which becomes this invocation's parent. */
  def parentSpanIdOf(metadata: Metadata): Option[Long] = parse(metadata.get(SpanIdKey))

  /**
   * The trace and span the current thread is working for, if any.
   *
   * A `ThreadLocal` is sound here for exactly the reason `RequestContext` gives for being one:
   * handlers run on their own virtual thread, one request to one thread, so there is no sharing to
   * get wrong. It carries the same consequence too — **work handed to another thread cannot see
   * it**, so a component that dispatches to its own executor produces invocations this cannot
   * attribute. That shows up as unattributed time and an orphan span, which is the honest answer;
   * guessing a parent would produce a trace that reads correctly and is wrong.
   */
  private val current = ThreadLocal[(Long, Long)]()

  def currentTrace: Option[(Long, Long)] = Option(current.get())

  /** Runs `body` as the work of this span, restoring whatever was current before. */
  def within[A](traceId: Long, spanId: Long)(body: => A): A =
    val previous = current.get()
    current.set((traceId, spanId))
    try body
    finally if previous eq null then current.remove() else current.set(previous)

  private def parse(value: Option[String]): Option[Long] =
    value.flatMap(v => scala.util.Try(java.lang.Long.parseUnsignedLong(v, 16)).toOption)

  /**
   * Builds a trace from the flat spans of one trace id.
   *
   * `partial` means **this window does not hold all of it**, stated by what it means rather than by
   * its cause. Here the only cause is eviction from the ring; a console over a deployed
   * installation would have a second (spans living on another instance's ring), and defining the
   * flag this way means that console needs no new flag and the UI no new case.
   */
  def assemble(traceId: Long, spans: Vector[RecordedSpan], oldestOverwritten: Boolean): Trace =
    if spans.isEmpty then Trace(traceId, 0L, 0L, Vector.empty, 0L, partial = oldestOverwritten)
    else
      val byId       = spans.map(s => s.spanId -> s).toMap
      val childrenOf = spans.groupBy(_.parentSpanId)

      def build(span: RecordedSpan): TraceSpan =
        val kids = childrenOf.getOrElse(span.spanId, Vector.empty).sortBy(_.startedNanos).map(build)
        TraceSpan(
          spanId = span.spanId,
          parentSpanId = span.parentSpanId,
          componentRef = span.componentRef,
          handlerRef = span.handlerRef,
          startedNanos = span.startedNanos,
          durationNanos = span.durationNanos,
          outcome = span.outcome,
          children = kids,
          // A parent id that names a span this window does not hold. Reported, never re-parented.
          parentUnknown = span.parentSpanId != 0L && !byId.contains(span.parentSpanId),
          // The span's own elapsed time that none of its children account for — a database wait, a
          // model call, work handed to a thread the trace cannot follow. Only meaningful where
          // there *are* children: a leaf's whole duration is attributed to the leaf, and reporting
          // that as unattributed would put a second row under every entity call saying nothing.
          unattributedNanos =
            if kids.isEmpty then 0L
            else math.max(0L, span.durationNanos - kids.map(_.durationNanos).sum)
        )

      // Roots are spans with no parent, plus orphans — whose parent is named but absent.
      val roots = spans
        .filter(s => s.parentSpanId == 0L || !byId.contains(s.parentSpanId))
        .sortBy(_.startedNanos)
        .map(build)

      val started = spans.map(_.startedNanos).min
      val ended   = spans.map(s => s.startedNanos + s.durationNanos).max
      val total   = ended - started

      // Only root-level spans count against the total; a child's time is already inside its
      // parent's, and counting both would make unattributed time negative on a nested trace.
      val accounted    = roots.map(_.durationNanos).sum
      val unattributed = math.max(0L, total - accounted)

      // An orphan is proof that something is missing from this window, whether or not the ring has
      // wrapped — for instance a parent evicted while its children survived.
      val orphaned = roots.exists(_.parentUnknown)

      Trace(traceId, started, total, roots, unattributed, partial = oldestOverwritten || orphaned)
