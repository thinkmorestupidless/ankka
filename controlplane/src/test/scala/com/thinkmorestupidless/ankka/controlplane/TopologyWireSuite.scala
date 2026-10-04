package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  CallCounts,
  Names,
  ServedRoute,
  SpanOutcome,
  TopologyJson,
  Unanswered
}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The runtime writes a topology document by hand and the control plane reads it with a codec. This
 * is the one place both are in scope, so it is the only test that can see them disagree: every
 * field the runtime writes, with every kind of node, edge and outcome, must arrive on the other
 * side. Rename a field on either side and this goes red.
 */
class TopologyWireSuite extends munit.FunSuite with LogCapturing:

  final class Cart extends EventSourcedEntity[Int, String]:
    def emptyState: Int                = 0
    def applyEvent(event: String): Int = currentState + 1
    def add(n: Int): Effect[Int]       = effects.persist(n.toString).thenReply(identity)
    def get: ReadOnlyEffect[Int]       = effects.reply(currentState)
  object Cart
      extends EventSourcedEntity.Companion[Cart, Int, String](
        ComponentId("cart"),
        Serializers.int,
        Serializers.string
      ):
    def create(context: EventSourcedEntityContext) = new Cart
    val add                                        = command("add")(_.add)
    val get                                        = query("get")(_.get)

  final class Rows extends View[String, Int]:
    def onChange(change: String): Effect = effects.updateRow(1)
  object Rows
      extends View.Companion[Rows, String, Int](
        ComponentId("rows"),
        ChangeSource.EventSourced(ComponentId("cart"), Serializers.string),
        Serializers.int
      ):
    def create(context: ViewComponentContext) = new Rows

  final class Audit extends Consumer[String, String]:
    def onMessage(message: String): Effect = effects.produce(message)
  object Audit
      extends Consumer.Companion[Audit, String, String](
        ComponentId("audit"),
        ChangeSource.Topic(
          "events",
          Serializers.string,
          Some(com.thinkmorestupidless.ankka.sdk.StartFrom.Earliest)
        )
      ):
    def create(context: ConsumerContext)                      = new Audit
    override val outputSerializer: Option[Serializer[String]] = Some(Serializers.string)
    override val produceTo: Option[String]                    = Some("audited")

  test("every field the runtime writes is read by the control plane's codec") {
    val registry =
      ComponentRegistry.fromOrThrow(Vector(Cart.descriptor, Rows.descriptor, Audit.descriptor))
    val routes = Vector(
      ServedRoute("POST", "/carts/{id}/items", streaming = false, "endpoint:/carts"),
      ServedRoute("GET", "/carts/{id}/events", streaming = true, "endpoint:/carts")
    )
    val names            = new Names
    def id(name: String) = names.intern(name)
    val counts           = CallCounts(600_000L, 60, 0L)
    val now              = 1_000L
    val key = CallCounts
      .key(id("endpoint:/carts"), id("POST /carts/{id}/items"), id("cart"), id("add"))
      .get
    counts.handled(key, SpanOutcome.Ok, 1_500_000L, false, now)
    counts.handled(key, SpanOutcome.Refused, 2_000_000L, false, now)
    counts.handled(key, SpanOutcome.Failed, 40_000_000L, false, now)
    counts.unanswered(key, Unanswered.TimedOut, now)
    counts.unanswered(key, Unanswered.Undelivered, now)
    val stream = CallCounts
      .key(id("endpoint:/carts"), id("GET /carts/{id}/events"), id("cart"), id("get"))
      .get
    counts.handled(stream, SpanOutcome.Ok, 3_000_000L, true, now)
    val other = CallCounts
      .key(
        id(CallCounts.UnknownOrigin),
        id(CallCounts.UnknownOrigin),
        id("service:shop/orders"),
        id("GET")
      )
      .get
    counts.handled(other, SpanOutcome.Ok, 1_000_000L, false, now)

    val json = TopologyJson.render(
      "cart",
      "4242",
      "2026-10-01T10:00:00Z",
      registry,
      routes,
      counts.snapshot(now + 1),
      names.nameOf
    )
    val document = readFromString[InstanceTopologyDocument](json)

    assertEquals(
      document.service,
      TopologyService(
        "cart",
        com.thinkmorestupidless.ankka.core.BuildInfo.version,
        "4242",
        "2026-10-01T10:00:00Z"
      )
    )
    assertEquals(document.window.seconds, 600L)
    assertEquals(document.window.calls, 5L)
    assertEquals(document.window.unanswered, 2L)
    assert(document.window.since.nonEmpty)

    val kinds = document.nodes.map(n => n.id -> n.kind).toMap
    assertEquals(kinds("cart"), "EventSourcedEntity")
    assertEquals(kinds("rows"), "View")
    assertEquals(kinds("audit"), "Consumer")
    assertEquals(kinds("endpoint:/carts"), "Endpoint")
    assertEquals(kinds("topic:events"), "Topic")
    assertEquals(kinds("topic:audited"), "Topic")
    assertEquals(kinds("service:shop/orders"), "ExternalService")
    assertEquals(kinds("unknown"), "UnknownCaller")
    val cart     = document.nodes.find(_.id == "cart").get
    val endpoint = document.nodes.find(_.id == "endpoint:/carts").get
    assert(endpoint.layer < cart.layer, "an endpoint is drawn before what it calls")
    assert(cart.layer < document.nodes.find(_.id == "rows").get.layer, "a view after its source")
    assertEquals(cart.platform, false)
    assertEquals(
      cart.handlers.map(h => (h.name, h.`type`)).toSet,
      Set("add" -> "command", "get" -> "query")
    )
    assertEquals(
      endpoint.handlers.map(h => (h.name, h.`type`, h.streaming)).toSet,
      Set[(String, String, Option[Boolean])](
        ("POST /carts/{id}/items", "route", Some(false)),
        ("GET /carts/{id}/events", "route", Some(true))
      )
    )

    assertEquals(
      document.declared.toSet,
      Set(
        DeclaredEdge("cart", "rows", "events"),
        DeclaredEdge("topic:events", "audit", "topic-subscription"),
        DeclaredEdge("audit", "topic:audited", "topic-publication")
      )
    )

    val call = document.calls.find(c => c.from == "endpoint:/carts" && c.to == "cart").get
    val add  = call.pairs.find(_.callee == "add").get
    assertEquals(add.caller, "POST /carts/{id}/items")
    assertEquals(add.handled, HandledCounts(1L, 1L, 1L))
    assertEquals(add.unanswered, UnansweredCounts(1L, 1L))
    assert(add.durationMillis.bucketed)
    assert(add.durationMillis.p50 > 0.0 && add.durationMillis.p99 >= add.durationMillis.p50)
    assert(add.durationMillis.max >= add.durationMillis.p99)
    assertEquals(add.histogram.size, CallCounts.HistogramBuckets)
    assertEquals(add.histogram.sum, 3L)
    assertEquals(add.streaming, false)
    val get = call.pairs.find(_.callee == "get").get
    assertEquals(get.streaming, true)
    val unknown = document.calls.find(c => c.from == "unknown" && c.to == "service:shop/orders").get
    assertEquals(unknown.pairs.head.caller, "unknown")
    assertEquals(unknown.pairs.head.callee, "GET")

    // The merge reads the same percentiles from the histogram the runtime wrote.
    assertEquals(TopologyMerge.percentile(add.histogram, 0.5), add.durationMillis.p50)
    assertEquals(TopologyMerge.percentile(add.histogram, 0.99), add.durationMillis.p99)
    assertEquals(TopologyMerge.max(add.histogram), add.durationMillis.max)
  }
