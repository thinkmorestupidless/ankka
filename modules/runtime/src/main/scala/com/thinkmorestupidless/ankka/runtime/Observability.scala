package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Metadata
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Extension
import org.apache.pekko.actor.typed.ExtensionId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One recorder per service, reachable from anywhere in it.
 *
 * A Pekko extension rather than something threaded through every constructor: the hosts that need
 * to record are built in several places and a recorder is per-service state, which is exactly what
 * an extension is for. It holds no actor reference and touches no `ActorContext`, so it is safe to
 * call from a handler thread — the recorded trap about `Future` callbacks does not apply.
 *
 * `capacity` comes from `ankka.observability.ring-capacity`, so an application that wants a longer
 * window can have one without a code change.
 */
final class Observability(
    val recorder: Recorder,
    val names: Names,
    val calls: CallCounts = CallCounts(600_000L, 60, System.currentTimeMillis()),
    val externalServices: ExternalServices = ExternalServices(32)
) extends Extension:

  @volatile private var declaredNames: DeclaredNames = DeclaredNames.none

  /**
   * The names a call's metadata is checked against. Nothing until the service has said what it is
   * made of, so a call that arrives before then is from an unknown origin, and never from whatever
   * it claimed.
   */
  def declared: DeclaredNames = declaredNames

  /**
   * Refuses a service that declares more names than a call's key has room for, at startup and by
   * name. Once that has passed, nothing a call carries can fail on the table: only a declared name
   * is ever interned.
   */
  private[runtime] def declare(declaring: DeclaredNames): Unit =
    if declaring.names > Observability.MaxDeclaredNames then
      throw IllegalStateException(
        s"this service declares ${declaring.names} component and handler names, and the " +
          s"topology can count calls between at most ${Observability.MaxDeclaredNames}"
      )
    declaredNames = declaring

  private val unknown    = names.intern(CallCounts.UnknownOrigin)
  private val undeclared = names.intern(CallCounts.Undeclared)

  /**
   * A call a host ran a handler for. The one place a handled call is counted, so that no host can
   * count differently: who made it is what the call's metadata names, when that is a name this
   * service declared, and otherwise nobody in particular.
   *
   * @param callee
   *   the component that ran the handler, and `handler` the handler: the host's own, so declared.
   */
  private[ankka] def handled(
      incoming: Metadata,
      callee: String,
      handler: String,
      outcome: SpanOutcome,
      durationNanos: Long,
      streaming: Boolean = false
  ): Unit =
    val named = CallOrigin.from(incoming)
    // The console reading an entity is not the service doing anything.
    if !named.contains(CallOrigin.Console) then
      keyOf(named.filter(o => declaredNames.validate(o.component, o.handler)), callee, handler)
        .foreach(calls.handled(_, outcome, durationNanos, streaming, System.currentTimeMillis()))

  /**
   * A call a host answered without running a handler: the method is not one the component declares,
   * or the host was stopping. Counted as undelivered, and under no name that was sent: a method
   * nobody declared is `(undeclared)`.
   */
  private[ankka] def undelivered(
      incoming: Metadata,
      callee: String,
      handler: Option[String]
  ): Unit =
    val named = CallOrigin.from(incoming)
    if !named.contains(CallOrigin.Console) then
      val origin = named.filter(o => declaredNames.validate(o.component, o.handler))
      val key = handler match
        case Some(declared) => keyOf(origin, callee, declared)
        case None           => keyOf(origin, names.intern(callee), undeclared)
      key.foreach(calls.unanswered(_, Unanswered.Undelivered, System.currentTimeMillis()))

  /**
   * A call its caller got no answer to, counted where it was made: the only place that can see it.
   *
   * @param origin
   *   the calling thread's own origin, taken before the call was sent.
   * @param callee
   *   what the call named, which from a process in another language is whatever it sent. Counted
   *   under those names only when the service declared them; a declared component with another
   *   method is `(undeclared)`, and a component nobody registered is not counted at all.
   */
  private[ankka] def unanswered(
      origin: Option[CallOrigin],
      callee: String,
      method: String,
      kind: Unanswered
  ): Unit =
    if !origin.contains(CallOrigin.Console) then
      val caller = believed(origin)
      val key =
        if declaredNames.validate(callee, method) then keyOf(caller, callee, method)
        else if declaredNames.registered(callee) then
          keyOf(caller, names.intern(callee), undeclared)
        else None
      key.foreach(calls.unanswered(_, kind, System.currentTimeMillis()))

  /**
   * A call counted where it was made because nothing hosts what it calls: a view's rows are read
   * straight from the database, and another service is another process. The callee's names are the
   * caller's to give and are bounded by its own code, or by admission for a service.
   */
  private[ankka] def made(
      origin: Option[CallOrigin],
      callee: String,
      handler: String,
      outcome: SpanOutcome,
      durationNanos: Long
  ): Unit =
    if !origin.contains(CallOrigin.Console) then
      keyOf(believed(origin), callee, handler)
        .foreach(calls.handled(_, outcome, durationNanos, false, System.currentTimeMillis()))

  /** As `made`, for a call that got no answer: another service that timed out or was not there. */
  private[ankka] def madeUnanswered(
      origin: Option[CallOrigin],
      callee: String,
      handler: String,
      kind: Unanswered
  ): Unit =
    if !origin.contains(CallOrigin.Console) then
      keyOf(believed(origin), callee, handler)
        .foreach(calls.unanswered(_, kind, System.currentTimeMillis()))

  /**
   * An origin, when it is one this service declared. A thread's own origin was set by a host and
   * always is; it is checked all the same, so that no way of counting a call can put a name in the
   * table that the service did not declare, whatever set the origin.
   */
  private def believed(origin: Option[CallOrigin]): Option[CallOrigin] =
    origin.filter(o => declaredNames.validate(o.component, o.handler))

  private def keyOf(origin: Option[CallOrigin], callee: String, handler: String): Option[Long] =
    keyOf(origin, names.intern(callee), names.intern(handler))

  private def keyOf(origin: Option[CallOrigin], callee: Int, handler: Int): Option[Long] =
    origin match
      case Some(o) =>
        CallCounts.key(names.intern(o.component), names.intern(o.handler), callee, handler)
      case None => CallCounts.key(unknown, unknown, callee, handler)

  /**
   * Begins the span of a call to another service: a `Client` span under the calling thread's span,
   * or, when the thread is in no trace, the root of a fresh trace whose caller is unknown. The call
   * carries this span's context, so the callee's span names it as its parent: that pair is how a
   * collector draws one service calling another.
   *
   * `service` and `method` are names already admitted — a service by `externalServices.nameFor`, a
   * gRPC method by `externalServices.methodFor`, an HTTP call by its method and never its path —
   * because they are interned.
   */
  private[ankka] def beginCall(service: String, method: String): Span =
    val component = names.intern(service)
    val handler   = names.intern(method)
    Trace.currentContext match
      case Some(c) =>
        recorder.begin(c.traceIdHigh, c.traceId, c.spanId, component, handler, SpanKind.Client)
      case None =>
        recorder.begin(
          Trace.mintHigh(),
          Trace.mint(),
          Recorder.UnknownCaller,
          component,
          handler,
          SpanKind.Client
        )

  /**
   * Runs `body` as a call to another service, given the `traceparent` it is to send: a span under
   * the calling handler's, ended with how the call ended as its caller saw it, and the call counted
   * from the thread's own handler.
   *
   * Nothing hosts the other service in this process, so this is the only place the call is seen.
   * Both names are bounded: `service` is what `ExternalServices` admitted, up to a limit and then
   * the one name the rest share, and `method` is the request's method. Nothing a call carries — a
   * path, an id — is interned. A response is handled as `outcomeOf` says; a call that got none is
   * unanswered: timed out, or never delivered.
   */
  private[ankka] def calling[A](service: String, method: String)(
      outcomeOf: scala.util.Try[A] => SpanOutcome
  )(body: String => A): A =
    val origin  = Trace.currentOrigin
    val span    = beginCall(service, method)
    val started = System.nanoTime()
    var outcome = SpanOutcome.Failed
    try
      val result = body(Traceparent.render(span.context))
      outcome = outcomeOf(scala.util.Success(result))
      made(origin, service, method, outcome, System.nanoTime() - started)
      result
    catch
      case e: java.net.http.HttpTimeoutException =>
        outcome = outcomeOf(scala.util.Failure(e))
        madeUnanswered(origin, service, method, Unanswered.TimedOut)
        throw e
      case scala.util.control.NonFatal(e) =>
        outcome = outcomeOf(scala.util.Failure(e))
        madeUnanswered(origin, service, method, Unanswered.Undelivered)
        throw e
    finally recorder.complete(span, outcome)

  /**
   * Runs `body` as one invocation of a declared handler: a span in the caller's trace, the origin
   * of whatever `body` calls, and one handled call from whoever the metadata names.
   *
   * For a host whose handler runs on a thread of its own, which is every host but an entity's: the
   * span and the origin are set on the thread this is called on, so it is called inside the
   * `Future`, never around the creation of one.
   */
  private[ankka] def invocation[A](
      component: String,
      handler: String,
      incoming: Metadata,
      streaming: Boolean = false
  )(outcomeOf: A => SpanOutcome)(body: => A): A =
    val inbound = Trace.inbound(incoming)
    val span = recorder.begin(
      traceIdHigh = inbound.traceIdHigh,
      traceId = inbound.traceId,
      parentSpanId = inbound.parentSpanId,
      componentRef = names.intern(component),
      handlerRef = names.intern(handler),
      kind = SpanKind.Internal
    )
    val started = System.nanoTime()
    // Failed until proven otherwise: if the handler throws, that is what is recorded.
    var outcome = SpanOutcome.Failed
    try
      val result = Trace.within(span, CallOrigin(component, handler))(body)
      outcome = outcomeOf(result)
      result
    finally
      recorder.complete(span, outcome)
      handled(incoming, component, handler, outcome, System.nanoTime() - started, streaming)

object Observability extends ExtensionId[Observability]:

  def createExtension(system: ActorSystem[?]): Observability =
    val capacity =
      if system.settings.config.hasPath("ankka.observability.ring-capacity") then
        system.settings.config.getInt("ankka.observability.ring-capacity")
      else 4096
    val config = system.settings.config
    def millis(path: String, otherwise: Long): Long =
      if config.hasPath(path) then config.getDuration(path).toMillis else otherwise
    def int(path: String, otherwise: Int): Int =
      if config.hasPath(path) then config.getInt(path) else otherwise
    new Observability(
      Recorder(
        capacity,
        int("ankka.observability.max-counted-handlers", Recorder.DefaultCountedHandlers)
      ),
      new Names,
      CallCounts(
        millis("ankka.observability.call-window", 600_000L),
        int("ankka.observability.call-buckets", 60),
        System.currentTimeMillis()
      ),
      ExternalServices(
        int("ankka.observability.max-external-services", 32),
        int("ankka.observability.max-external-methods", ExternalServices.DefaultMethodLimit)
      )
    )

  /**
   * How a call ended, from the reply its host made. A refusal is a handler saying no; a reply that
   * says the handler could not answer, because something it needed failed or was too slow, is not.
   */
  private[ankka] def outcomeOf(reply: EntityProtocol.Reply): SpanOutcome = reply match
    case _: EntityProtocol.Succeeded       => SpanOutcome.Ok
    case rejected: EntityProtocol.Rejected => outcomeOf(rejected.toCommandError)

  private[ankka] def outcomeOf(
      error: com.thinkmorestupidless.ankka.core.CommandError
  ): SpanOutcome =
    import com.thinkmorestupidless.ankka.core.ErrorCode
    error.code match
      case ErrorCode.Timeout                          => SpanOutcome.TimedOut
      case ErrorCode.Internal | ErrorCode.Unavailable => SpanOutcome.Failed
      case _                                          => SpanOutcome.Refused

  /**
   * A call's key holds each name in sixteen bits. Short of that, to leave room for the names the
   * recorder interns that are not a service's own: the fixed ones, and the services it calls.
   */
  val MaxDeclaredNames: Int = 60_000

/**
 * Names on the way in, integers on the way out.
 *
 * A span records a component and a handler as `Int`s so that writing one allocates nothing and
 * copies no characters. This is where a name becomes an integer, once, and where a reader turns it
 * back into a name when somebody is actually looking at a trace.
 *
 * **What this costs on the hot path, stated plainly rather than claimed away**: interning a name
 * that has been seen before is one `ConcurrentHashMap` lookup. Not free — of the order of ten
 * nanoseconds beside the recorder's own twenty — but bounded, allocation-free after the first
 * sighting, and small against any real invocation. The alternative that *is* free is carrying the
 * index on the handler binding itself, which means changing a type in `sdk` that every sharded host
 * depends on; that trade is available later if measurement ever asks for it, and the measurement
 * has not asked.
 *
 * The table cannot grow without bound, because components reach the runtime only by explicit
 * registration and handler names are declared on typed companions. Both sets are fixed before the
 * first request.
 */
final class Names:
  private val ids  = new ConcurrentHashMap[String, Integer]()
  private val back = new ConcurrentHashMap[Integer, String]()
  private val next = new AtomicInteger(0)

  /** How many names are held. Bounded by what a service declares, so worth being able to assert. */
  def size: Int = ids.size

  /** The integer for this name, assigning one the first time it is seen. */
  def intern(name: String): Int =
    val existing = ids.get(name)
    if existing ne null then existing.intValue
    else
      val assigned = Integer.valueOf(next.getAndIncrement())
      val raced    = ids.putIfAbsent(name, assigned)
      if raced ne null then raced.intValue
      else
        back.put(assigned, name)
        assigned.intValue

  /** The name behind an integer, for a reader. Unknown reads as unknown, never as an empty name. */
  def nameOf(id: Int): Option[String] = Option(back.get(Integer.valueOf(id)))
