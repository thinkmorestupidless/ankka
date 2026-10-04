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
 * Which trace a piece of work belongs to and which span it is, as a call or a message carries it: a
 * trace id in two halves, high and low, and a span id. The low half alone is what the consoles key
 * a trace by.
 */
final case class TraceContext(traceIdHigh: Long, traceId: Long, spanId: Long):
  /** The trace id as W3C and OTLP write it: 32 lower-case hex digits, the high half first. */
  def traceIdHex: String = TraceContext.hex16(traceIdHigh) + TraceContext.hex16(traceId)

  /** The span id as W3C and OTLP write it: 16 lower-case hex digits. */
  def spanIdHex: String = TraceContext.hex16(spanId)

object TraceContext:
  private[runtime] def hex16(value: Long): String =
    val digits = java.lang.Long.toHexString(value)
    if digits.length == 16 then digits else "0" * (16 - digits.length) + digits

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

  /**
   * The low half of a fresh trace: never zero, so a trace id is never mistaken for "no trace".
   * Minted at an entry point — an HTTP request, a projection, a timer — with `mintHigh`.
   */
  def mint(): Long =
    var id = 0L
    while id == 0L do id = ThreadLocalRandom.current().nextLong()
    id

  /**
   * The high half of a fresh trace. A trace id is 128 bits because a collector keeps traces from
   * every service of an installation for as long as it is asked to, and 64 random bits collide
   * after about four billion of them.
   */
  def mintHigh(): Long = ThreadLocalRandom.current().nextLong()

  /**
   * Writes this trace and the span that will parent the callee's work into outbound metadata. The
   * trace id is written as 32 hex digits, the high half first; a node from before 128-bit ids
   * cannot read that, takes the call as carrying no trace, and starts one — a trace that crosses
   * two versions of a service during its rolling update is split, and nothing fails.
   */
  def into(metadata: Metadata, context: TraceContext): Metadata =
    metadata
      .set(TraceIdKey, context.traceIdHex)
      .set(SpanIdKey, java.lang.Long.toHexString(context.spanId))

  /** As `into`, for a trace whose high half is zero. */
  def into(metadata: Metadata, traceId: Long, parentSpanId: Long): Metadata =
    into(metadata, TraceContext(0L, traceId, parentSpanId))

  /** Reads the low half of a trace from inbound metadata, if the caller was carrying one. */
  def traceIdOf(metadata: Metadata): Option[Long] = traceOf(metadata).map(_._2)

  /** Reads the caller's span, which becomes this invocation's parent. */
  def parentSpanIdOf(metadata: Metadata): Option[Long] = parse(metadata.get(SpanIdKey))

  /** Both halves of the trace an inbound call carries: 1 to 32 hex digits, the high half first. */
  private def traceOf(metadata: Metadata): Option[(Long, Long)] =
    metadata.get(TraceIdKey).flatMap { value =>
      if value.isEmpty || value.length > 32 then None
      else
        val split = math.max(0, value.length - 16)
        for
          high <- if split == 0 then Some(0L) else parse(Some(value.substring(0, split)))
          low  <- parse(Some(value.substring(split)))
          if low != 0L || high != 0L
        yield (high, low)
    }

  /** The trace and parent a span recorded for a call begins with. */
  final case class Inbound(traceIdHigh: Long, traceId: Long, parentSpanId: Long)

  /**
   * The trace a host's span for a *call* begins in: the one the call carries, under the caller's
   * span. A call that carries none was made outside any handler — every call a handler makes
   * carries its trace — so its span is the root of a fresh trace whose caller is unknown, and is
   * marked so; it is never given a parent by guessing. The local console's own calls are not the
   * service's, and are plain roots.
   *
   * Entry points (an endpoint, a projection, a timer) do not use this: they start traces, or
   * continue one a request or a message carries.
   */
  def inbound(metadata: Metadata): Inbound =
    traceOf(metadata) match
      case Some((high, low)) => Inbound(high, low, parentSpanIdOf(metadata).getOrElse(0L))
      case None =>
        val parent =
          if CallOrigin.from(metadata).contains(CallOrigin.Console) then 0L
          else Recorder.UnknownCaller
        Inbound(mintHigh(), mint(), parent)

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
  private val current = ThreadLocal[Working]()

  /** What a thread is working for: the trace, the span, and the handler that span is. */
  private final class Working(
      val traceIdHigh: Long,
      val traceId: Long,
      val spanId: Long,
      val origin: CallOrigin
  )

  /** The trace and span the current thread is working for, both halves of the trace id. */
  def currentContext: Option[TraceContext] =
    current.get() match
      case null                             => None
      case working if working.traceId == 0L => None
      case working => Some(TraceContext(working.traceIdHigh, working.traceId, working.spanId))

  /** The low half of the current trace, and the current span. */
  def currentTrace: Option[(Long, Long)] =
    current.get() match
      case null => None
      // No trace is minted with id zero, so zero is a thread that is a handler and in no trace.
      case working if working.traceId == 0L => None
      case working                          => Some((working.traceId, working.spanId))

  /**
   * The handler the current thread is running, if a host said so.
   *
   * What a call made from this thread is attributed to. It follows the thread exactly as the trace
   * does, and stops where the trace stops: work handed to another thread has no origin, and a call
   * it makes is from an unknown one. It is never filled in from the nearest handler that might have
   * been responsible.
   */
  def currentOrigin: Option[CallOrigin] =
    current.get() match
      case null    => None
      case working => Option(working.origin)

  /**
   * Runs `body` as the work of this span and of this handler, restoring whatever was current
   * before. The form a host uses: a call `body` makes is this span's child and this handler's call.
   */
  def within[A](span: Span, origin: CallOrigin)(body: => A): A =
    scoped(Working(span.traceIdHigh, span.traceId, span.id, origin))(body)

  /** As above, for a trace whose high half is zero: the form tests use. */
  def within[A](traceId: Long, spanId: Long, origin: CallOrigin)(body: => A): A =
    scoped(Working(0L, traceId, spanId, origin))(body)

  /**
   * Runs `body` as a handler's work that belongs to no trace: what a handler does between the
   * pieces of work it records, such as an agent reading its own record before it starts an
   * iteration. A call made here is the handler's call, and the root of a trace of its own.
   */
  def asOrigin[A](origin: CallOrigin)(body: => A): A =
    scoped(Working(0L, 0L, 0L, origin))(body)

  /**
   * What a thread was working for, taken so that the same work can go on somewhere else.
   *
   * A thread-local does not follow work to another thread, and that is the rule: a call made there
   * is from nobody. This is for the one case where the code itself knows better, because it built
   * the work here and only starts it there. A stream is that case: the handler asks for it, and it
   * is sent when something starts reading. It is the handler's call wherever it is sent from.
   *
   * Never a way to give a call to the nearest handler: only code that was run by the handler, on
   * the handler's thread, can take one of these.
   */
  final class Scope private[Trace] (private[Trace] val working: Working | Null)

  def capture(): Scope = Scope(current.get())

  /** Runs `body` as the work that was captured, restoring whatever was current before. */
  def resume[A](scope: Scope)(body: => A): A =
    if scope.working eq null then body else scoped(scope.working.nn)(body)

  /**
   * What a call carries about where it came from: the trace it belongs to and the handler making
   * it, both as the calling thread knows them.
   *
   * An origin already in the metadata is never passed on. A handler may forward the metadata it was
   * given, and that names whoever called *it*; left in place it would put this call on them. So the
   * thread's own origin replaces it, and where the thread has none it is taken out: the call is
   * then from an unknown origin, which is the truth.
   */
  def outbound(metadata: Metadata): Metadata =
    val traced = currentContext match
      case Some(context) => into(metadata, context)
      case None          => metadata
    currentOrigin match
      case Some(origin) => CallOrigin.into(traced, origin)
      case None         => CallOrigin.strip(traced)

  /** Runs `body` as the work of this span, for no handler in particular. */
  def within[A](span: Span)(body: => A): A =
    scoped(Working(span.traceIdHigh, span.traceId, span.id, null))(body)

  /** As above, for a trace whose high half is zero. */
  def within[A](traceId: Long, spanId: Long)(body: => A): A =
    scoped(Working(0L, traceId, spanId, null))(body)

  private def scoped[A](working: Working)(body: => A): A =
    val previous = current.get()
    current.set(working)
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
