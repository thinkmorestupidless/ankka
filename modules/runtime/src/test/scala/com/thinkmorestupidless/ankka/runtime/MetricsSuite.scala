package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

/** The Prometheus exposition, and the two ways it could mislead a scraper. */
final class MetricsSuite extends FunSuite:

  private def observability(capacity: Int = 64) =
    val names = Names()
    new Observability(Recorder(capacity), names)

  private def record(
      o: Observability,
      component: String,
      handler: String,
      outcome: SpanOutcome = SpanOutcome.Ok
  ): Unit =
    val span = o.recorder.begin(
      Trace.mint(),
      0L,
      o.names.intern(component),
      o.names.intern(handler)
    )
    o.recorder.complete(span, outcome)

  test("a service that has served nothing reports zero, not nothing") {
    val rendered = Metrics.render(observability())

    assert(rendered.contains("ankka_invocations_total 0"), rendered)
    assert(rendered.contains("ankka_invocation_duration_seconds_sum 0"), rendered)
    // An empty body would leave a scraper unable to tell "no traffic" from "target broken", and
    // those want different reactions from whoever is holding the pager.
    assert(rendered.nonEmpty)
  }

  test("invocations are counted by component, handler and outcome") {
    val o = observability()
    record(o, "cart", "add-item")
    record(o, "cart", "add-item")
    record(o, "cart", "get-cart")

    val rendered = Metrics.render(o)
    assert(
      rendered.contains(
        """ankka_invocations_total{component="cart",handler="add-item",outcome="Ok"} 2"""
      ),
      rendered
    )
    assert(
      rendered.contains(
        """ankka_invocations_total{component="cart",handler="get-cart",outcome="Ok"} 1"""
      ),
      rendered
    )
  }

  test("a refusal is its own series, not folded into failures") {
    val o = observability()
    record(o, "cart", "add-item", SpanOutcome.Refused)
    record(o, "cart", "add-item", SpanOutcome.Failed)

    val rendered = Metrics.render(o)
    assert(rendered.contains("""outcome="Refused"} 1"""), rendered)
    assert(rendered.contains("""outcome="Failed"} 1"""), rendered)
  }

  test("the reader is told this is a window, and how big") {
    val o = observability(capacity = 16)
    (1 to 40).foreach(_ => record(o, "cart", "add-item"))

    val rendered = Metrics.render(o)
    assert(rendered.contains("ankka_recorder_capacity 16"), rendered)
    assert(
      rendered.contains("ankka_recorder_spans_recorded_total 40"),
      "spans begun is a real counter even though the window holds 16"
    )
  }

  test("a label value cannot break the exposition format") {
    val o     = observability()
    val nasty = "we\"ird\\name"
    record(o, nasty, "handler")

    val rendered = Metrics.render(o)
    assert(rendered.contains("""component="we\"ird\\name""""), rendered)
  }

  test("an unresolvable name reads as unknown, not as empty") {
    val o    = observability()
    val span = o.recorder.begin(Trace.mint(), 0L, componentRef = 999, handlerRef = 998)
    o.recorder.complete(span, SpanOutcome.Ok)

    val rendered = Metrics.render(o)
    // `component=""` would be read as "no component" rather than "a component nobody can name".
    assert(rendered.contains("""component="unknown""""), rendered)
    assert(!rendered.contains("""component="""""), rendered)
  }
