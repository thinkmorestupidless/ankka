package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  DeclaredHandler,
  ErrorCode,
  HandlerKind,
  Metadata
}
import munit.FunSuite

/**
 * The window of counted calls, and the one place every host counts into it.
 *
 * Time is passed in, so a case decides when "now" is. The cases about names are the ones that keep
 * the recorder's table bounded: a name that was sent and never declared must not reach it.
 */
final class CallCountsSuite extends FunSuite:

  private val Start  = 1_000_000_000L
  private val Window = 600_000L

  private def counts(buckets: Int = 60) = CallCounts(Window, buckets, Start)
  private val key                       = CallCounts.key(1, 2, 3, 4).get

  // ── the window ───────────────────────────────────────────────────────────────

  test("a handled call counts once, under how its handler ended") {
    val c = counts()
    c.handled(key, SpanOutcome.Ok, 1_000_000L, streaming = false, Start)
    c.handled(key, SpanOutcome.Ok, 1_000_000L, streaming = false, Start)
    c.handled(key, SpanOutcome.Refused, 1_000_000L, streaming = false, Start)
    c.handled(key, SpanOutcome.Failed, 1_000_000L, streaming = false, Start)
    c.handled(key, SpanOutcome.TimedOut, 1_000_000L, streaming = false, Start)

    val pair = c.snapshot(Start).pairs.head
    assertEquals((pair.ok, pair.refused, pair.failed), (2L, 1L, 2L))
    assertEquals(pair.handled, 5L)
    assertEquals(pair.unanswered, 0L)
    assertEquals(pair.histogram.sum, 5L, "every handled call has a duration")
  }

  test("an unanswered call counts by why, and has no duration") {
    val c = counts()
    c.unanswered(key, Unanswered.TimedOut, Start)
    c.unanswered(key, Unanswered.Undelivered, Start)
    c.unanswered(key, Unanswered.Undelivered, Start)

    val pair = c.snapshot(Start).pairs.head
    assertEquals((pair.timedOut, pair.undelivered), (1L, 2L))
    assertEquals(pair.handled, 0L)
    assertEquals(pair.histogram.sum, 0L)
  }

  test("a key is four names, and comes back as the same four") {
    val c = counts()
    c.handled(CallCounts.key(11, 22, 33, 44).get, SpanOutcome.Ok, 1L, streaming = false, Start)
    val pair = c.snapshot(Start).pairs.head
    assertEquals(
      (pair.callerComponent, pair.callerHandler, pair.calleeComponent, pair.calleeHandler),
      (11, 22, 33, 44)
    )
    assertEquals(CallCounts.key(CallCounts.MaxNameId, 0, 0, CallCounts.MaxNameId).isDefined, true)
    assertEquals(CallCounts.key(CallCounts.MaxNameId + 1, 0, 0, 0), None, "it does not fit")
    assertEquals(CallCounts.key(0, 0, -1, 0), None)
  }

  test("a call is counted for as long as the window, and then forgotten") {
    val c = counts()
    c.handled(key, SpanOutcome.Ok, 1L, streaming = false, Start)

    assertEquals(c.snapshot(Start + Window - 10_000L).pairs.map(_.ok), Vector(1L))
    assertEquals(c.snapshot(Start + Window + 10_000L).pairs, Vector.empty)
    assertEquals(c.size, 1, "the pair is still held; it is the picture that leaves it out")
  }

  test("calls in different parts of the window are added up, and leave it one part at a time") {
    val c = counts(buckets = 6) // each bucket is a hundred seconds
    c.handled(key, SpanOutcome.Ok, 1L, streaming = false, Start)
    c.handled(key, SpanOutcome.Ok, 1L, streaming = false, Start + 300_000L)

    assertEquals(c.snapshot(Start + 300_000L).pairs.head.ok, 2L)
    assertEquals(c.snapshot(Start + 650_000L).pairs.head.ok, 1L, "the first has left")
    assertEquals(c.snapshot(Start + 950_000L).pairs, Vector.empty)
  }

  test("a bucket that comes round again is emptied before it is written to") {
    val c = counts(buckets = 6)
    c.handled(key, SpanOutcome.Ok, 1L, streaming = false, Start)
    c.handled(key, SpanOutcome.Ok, 1L, streaming = false, Start)
    // One whole window later the same bucket is the present one.
    c.handled(key, SpanOutcome.Refused, 1L, streaming = false, Start + Window)

    val pair = c.snapshot(Start + Window).pairs.head
    assertEquals((pair.ok, pair.refused), (0L, 1L), "the two from a window ago are gone")
  }

  test("the window reaches back to the start, until the service is older than the window") {
    val c = counts()
    assertEquals(c.snapshot(Start + 5_000L).sinceMillis, Start)
    assertEquals(c.snapshot(Start + Window + 5_000L).sinceMillis, Start + 5_000L)
    assertEquals(c.snapshot(Start).windowSeconds, 600L)
  }

  test("a stream is marked on its pair") {
    val c = counts()
    c.handled(key, SpanOutcome.Ok, 1L, streaming = true, Start)
    assert(c.snapshot(Start).pairs.head.streaming)
  }

  // ── durations ────────────────────────────────────────────────────────────────

  test("a duration falls in the bucket whose edges are powers of two of a millisecond") {
    def bucket(millis: Double) = CallCounts.bucketOf((millis * 1_000_000).toLong)
    assertEquals(bucket(0.0), 0)
    assertEquals(bucket(0.001), 0, "everything shorter is in the first")
    assertEquals(bucket(1.0), 10, "[1, 2) ms")
    assertEquals(bucket(1.999), 10)
    assertEquals(bucket(2.0), 11)
    assertEquals(bucket(1000.0), 19, "[512, 1024) ms")
    assertEquals(CallCounts.bucketOf(Long.MaxValue), CallCounts.HistogramBuckets - 1)
    assertEquals(CallCounts.upperMillis(10), 2.0)
  }

  test("percentiles are read from the buckets: the upper edge of the one the share falls in") {
    val c = counts()
    (1 to 90).foreach(_ => c.handled(key, SpanOutcome.Ok, 1_500_000L, streaming = false, Start))
    (1 to 9).foreach(_ => c.handled(key, SpanOutcome.Ok, 6_000_000L, streaming = false, Start))
    c.handled(key, SpanOutcome.Ok, 100_000_000L, streaming = false, Start)

    val pair = c.snapshot(Start).pairs.head
    assertEquals(pair.percentileMillis(0.50), 2.0)
    assertEquals(pair.percentileMillis(0.90), 2.0)
    assertEquals(pair.percentileMillis(0.99), 8.0)
    assertEquals(pair.percentileMillis(1.0), 128.0)
    assertEquals(pair.maxMillis, 128.0)
  }

  // ── the one place a call is counted ──────────────────────────────────────────

  private def descriptor(id: String, of: ComponentKind, handlers: String*): ComponentDescriptor =
    new ComponentDescriptor:
      val componentId: ComponentId = ComponentId(id)
      val kind: ComponentKind      = of
      override def declaredHandlers: Vector[DeclaredHandler] =
        handlers.toVector.map(DeclaredHandler(_, HandlerKind.Command))

  private def observability(externalServices: Int = 2): Observability =
    val o = new Observability(
      Recorder(64),
      new Names,
      CallCounts(Window, 60, System.currentTimeMillis()),
      ExternalServices(externalServices)
    )
    o.declare(
      DeclaredNames.of(
        ComponentRegistry.fromOrThrow(
          Seq(
            descriptor("shopping-cart", ComponentKind.EventSourcedEntity, "add-item", "get-cart"),
            descriptor("checkout", ComponentKind.Workflow, "start", "reserve")
          )
        ),
        Vector(ServedRoute("POST", "/carts/{cartId}/items", streaming = false, "endpoint:/carts"))
      )
    )
    o

  private def from(component: String, handler: String): Metadata =
    CallOrigin.into(Metadata.empty, CallOrigin(component, handler))

  /** Every pair, by name, with its handled and unanswered counts. */
  private def pairs(o: Observability): Map[(String, String, String, String), (Long, Long)] =
    o.calls
      .snapshot(System.currentTimeMillis())
      .pairs
      .map { p =>
        def name(id: Int) = o.names.nameOf(id).getOrElse(fail(s"no name for $id"))
        (
          name(p.callerComponent),
          name(p.callerHandler),
          name(p.calleeComponent),
          name(p.calleeHandler)
        ) -> (p.handled, p.unanswered)
      }
      .toMap

  test("a handled call is from the handler its metadata names, when that is a declared one") {
    val o = observability()
    o.handled(from("checkout", "reserve"), "shopping-cart", "add-item", SpanOutcome.Ok, 1L)
    assertEquals(pairs(o), Map(("checkout", "reserve", "shopping-cart", "add-item") -> (1L, 0L)))
  }

  test("a call that names nobody, or somebody nobody declared, is from the unknown caller") {
    val o = observability()
    o.handled(Metadata.empty, "shopping-cart", "add-item", SpanOutcome.Ok, 1L)
    o.handled(from("intruder", "anything"), "shopping-cart", "add-item", SpanOutcome.Ok, 1L)
    o.handled(from("checkout", "cart-7"), "shopping-cart", "add-item", SpanOutcome.Ok, 1L)

    val unknown = CallCounts.UnknownOrigin
    assertEquals(pairs(o), Map((unknown, unknown, "shopping-cart", "add-item") -> (3L, 0L)))
  }

  test("names that were sent and never declared do not reach the recorder's table") {
    val o      = observability()
    val before = o.names.size
    (1 to 1000).foreach { i =>
      o.handled(from(s"sent-$i", s"handler-$i"), "shopping-cart", "add-item", SpanOutcome.Ok, 1L)
      o.unanswered(
        Some(CallOrigin("checkout", "reserve")),
        s"component-$i",
        s"m-$i",
        Unanswered.TimedOut
      )
      o.unanswered(
        Some(CallOrigin("checkout", "reserve")),
        "shopping-cart",
        s"m-$i",
        Unanswered.TimedOut
      )
      o.undelivered(from(s"sent-$i", s"handler-$i"), "shopping-cart", None)
      // And an origin on the calling thread that nobody declared, however it got there.
      val made = Some(CallOrigin(s"thread-$i", s"handler-$i"))
      o.unanswered(made, "shopping-cart", "add-item", Unanswered.TimedOut)
      o.made(made, "shopping-cart", "get", SpanOutcome.Ok, 1L)
      o.madeUnanswered(made, "shopping-cart", "get", Unanswered.Undelivered)
    }
    // The callee's own names, the caller's and the fixed ones were interned by the first round.
    val after = o.names.size
    assert(after - before <= 7, s"$before names before, $after after")
    assert(o.calls.size <= 6, s"${o.calls.size} pairs")
  }

  test("the console's own reads are not calls") {
    val o = observability()
    o.handled(
      CallOrigin.into(Metadata.empty, CallOrigin.Console),
      "shopping-cart",
      "get-cart",
      SpanOutcome.Ok,
      1L
    )
    o.undelivered(CallOrigin.into(Metadata.empty, CallOrigin.Console), "shopping-cart", None)
    o.unanswered(Some(CallOrigin.Console), "shopping-cart", "get-cart", Unanswered.TimedOut)
    o.made(Some(CallOrigin.Console), "shopping-cart", "get", SpanOutcome.Ok, 1L)
    assertEquals(pairs(o), Map.empty)
  }

  test(
    "a call the host answered itself is undelivered, and to an undeclared handler unless declared"
  ) {
    val o = observability()
    o.undelivered(from("checkout", "reserve"), "shopping-cart", None)
    o.undelivered(from("checkout", "reserve"), "shopping-cart", Some("add-item"))
    assertEquals(
      pairs(o),
      Map(
        ("checkout", "reserve", "shopping-cart", CallCounts.Undeclared) -> (0L, 1L),
        ("checkout", "reserve", "shopping-cart", "add-item")            -> (0L, 1L)
      )
    )
  }

  test("an unanswered call names its callee only as far as the service declared it") {
    val o      = observability()
    val caller = Some(CallOrigin("checkout", "reserve"))
    o.unanswered(caller, "shopping-cart", "add-item", Unanswered.TimedOut)
    o.unanswered(caller, "shopping-cart", "no-such-handler", Unanswered.Undelivered)
    o.unanswered(caller, "no-such-component", "add-item", Unanswered.Undelivered)
    o.unanswered(None, "shopping-cart", "add-item", Unanswered.TimedOut)

    val unknown = CallCounts.UnknownOrigin
    assertEquals(
      pairs(o),
      Map(
        ("checkout", "reserve", "shopping-cart", "add-item")            -> (0L, 1L),
        ("checkout", "reserve", "shopping-cart", CallCounts.Undeclared) -> (0L, 1L),
        (unknown, unknown, "shopping-cart", "add-item")                 -> (0L, 1L)
      )
    )
  }

  test("an invocation is a span, the origin of what its body calls, and one handled call") {
    val o = observability()
    val seen =
      o.invocation[String]("shopping-cart", "add-item", from("checkout", "reserve"))(_ =>
        SpanOutcome.Refused
      )(Trace.currentOrigin.map(CallOrigin.encode).getOrElse("nobody"))

    assertEquals(seen, "shopping-cart#add-item")
    assertEquals(Trace.currentOrigin, None, "and nothing is left on the thread")
    assertEquals(pairs(o), Map(("checkout", "reserve", "shopping-cart", "add-item") -> (1L, 0L)))
    val span = o.recorder.snapshot().head
    assertEquals(span.outcome, SpanOutcome.Refused)
    assertEquals(o.calls.snapshot(System.currentTimeMillis()).pairs.head.refused, 1L)
  }

  test("an invocation that throws is a failed span and a failed call, and still throws") {
    val o = observability()
    intercept[IllegalStateException] {
      o.invocation[String]("shopping-cart", "add-item", Metadata.empty)(_ => SpanOutcome.Ok)(
        throw IllegalStateException("the handler threw")
      )
    }
    assertEquals(o.recorder.snapshot().head.outcome, SpanOutcome.Failed)
    assertEquals(o.calls.snapshot(System.currentTimeMillis()).pairs.head.failed, 1L)
  }

  test("a reply says how its call ended: a refusal is the handler's, a fault is not") {
    def ended(code: ErrorCode) = Observability.outcomeOf(CommandError("no", code))
    assertEquals(ended(ErrorCode.BadRequest), SpanOutcome.Refused)
    assertEquals(ended(ErrorCode.NotFound), SpanOutcome.Refused)
    assertEquals(ended(ErrorCode.Forbidden), SpanOutcome.Refused)
    assertEquals(ended(ErrorCode.Internal), SpanOutcome.Failed)
    assertEquals(ended(ErrorCode.Unavailable), SpanOutcome.Failed)
    assertEquals(ended(ErrorCode.Timeout), SpanOutcome.TimedOut)
  }

  test("a service that declares more names than a key has room for is refused, by the number") {
    val o = new Observability(Recorder(64), new Names)
    val routes = (1 to Observability.MaxDeclaredNames + 1).toVector.map { i =>
      ServedRoute("GET", s"/things/$i", streaming = false, "endpoint:/things")
    }
    val refused = intercept[IllegalStateException] {
      o.declare(DeclaredNames.of(ComponentRegistry.fromOrThrow(Nil), routes))
    }
    assert(refused.getMessage.contains((Observability.MaxDeclaredNames + 2).toString), refused)
    assertEquals(o.declared.names, 0, "and nothing was declared")
  }

  // ── other services ───────────────────────────────────────────────────────────

  test("the first services called are named, and every one after that shares a name") {
    val services = ExternalServices(2)
    assertEquals(services.nameFor("checkout", "pricing"), "service:checkout/pricing")
    assertEquals(services.nameFor("checkout", "stock"), "service:checkout/stock")
    assertEquals(services.nameFor("checkout", "tax"), ExternalServices.Other)
    assertEquals(services.nameFor("checkout", "fraud"), "service:(other)")
    // One that was named stays named.
    assertEquals(services.nameFor("checkout", "pricing"), "service:checkout/pricing")
  }
