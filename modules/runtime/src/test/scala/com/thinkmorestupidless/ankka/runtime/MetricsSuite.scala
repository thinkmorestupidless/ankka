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

  // ── Topic sources ──────────────────────────────────────────────────────────

  import com.thinkmorestupidless.ankka.core.ComponentKind
  import com.thinkmorestupidless.ankka.sdk.StartFrom

  private def source(
      kind: ComponentKind,
      id: String,
      version: Int,
      recorded: Option[Int],
      behind: Boolean
  ) =
    val word = if kind == ComponentKind.View then "view" else "consumer"
    TopicSourceStatus(
      kind,
      id,
      "order-changes",
      s"ankka.shop.orders.$word.$id",
      StartFrom.Earliest,
      version,
      recorded,
      behind
    )

  test("a service with no topic source lists none in its metrics") {
    val rendered = Metrics.render(observability())
    assert(rendered.contains("# TYPE ankka_topic_source_info gauge"), rendered)
    assert(rendered.contains("# TYPE ankka_topic_source_behind gauge"), rendered)
    assert(!rendered.contains("ankka_topic_source_info{"), rendered)
    assert(!rendered.contains("ankka_topic_source_behind{"), rendered)
  }

  test("a service's metrics list each topic source") {
    val rendered = Metrics.render(
      observability(),
      Vector(
        source(ComponentKind.View, "summary", 2, Some(2), behind = false),
        source(ComponentKind.Consumer, "notifier", 1, None, behind = false)
      )
    )
    assert(
      rendered.contains(
        """ankka_topic_source_info{kind="view",component="summary",topic="order-changes",""" +
          """group="ankka.shop.orders.view.summary",start="earliest",version="2"} 1"""
      ),
      rendered
    )
    assert(
      rendered.contains(
        """ankka_topic_source_info{kind="consumer",component="notifier",topic="order-changes",""" +
          """group="ankka.shop.orders.consumer.notifier",start="earliest",version="1"} 1"""
      ),
      rendered
    )
    assert(
      rendered.contains(
        """ankka_topic_source_behind{component="summary",declared="2",recorded="2"} 0"""
      ),
      rendered
    )
    // A consumer has no recorded version, and so no behind series.
    assert(!rendered.contains("""ankka_topic_source_behind{component="notifier""""), rendered)
  }

  test("a view behind its recorded version is shown as behind in the metrics") {
    val rendered = Metrics.render(
      observability(),
      Vector(source(ComponentKind.View, "summary", 1, Some(2), behind = true))
    )
    assert(
      rendered.contains(
        """ankka_topic_source_behind{component="summary",declared="1",recorded="2"} 1"""
      ),
      rendered
    )
  }
