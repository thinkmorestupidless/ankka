package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Contract, ContextMap, Serializer}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerContext,
  Publication,
  StartFrom,
  TopicOptions
}

import scala.concurrent.duration.DurationInt

/**
 * `ContextMapCheck` against two running services on one in-memory broker (feature 062): "orders"
 * publishes its ledger's events to "orders-placed" stating "order.v1", and "billing" reads
 * "orders-placed" stating the same. Each case holds the same two services to a different map, so
 * what changes between them is only what the map records.
 */
class ContextMapCheckSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val orderV1 = Contract("order.v1", "sha256:" + "a" * 64)

  private val lines = Codecs.serializer[Line]("line")

  /** orders: reads its own ledger's events, publishes to "orders-placed" as order.v1. */
  private object OrdersRelay
      extends Consumer.Companion[LedgerFanout, LedgerEvent, Line](
        ComponentId("order-relay"),
        ChangeSource.eventsOf(LedgerEntity)
      ):
    def create(ctx: ConsumerContext)                        = new LedgerFanout
    override val outputSerializer: Option[Serializer[Line]] = Some(lines)
    override def produces: Option[Publication] = Some(Publication("orders-placed", Some(orderV1)))

  /** billing: reads "orders-placed" as order.v1. */
  private object Invoicer
      extends Consumer.Companion[InvoiceOnOrder, Line, Nothing](
        ComponentId("invoicer"),
        ChangeSource.fromTopic(
          "orders-placed",
          lines,
          StartFrom.Earliest,
          TopicOptions(contract = Some(orderV1))
        )
      ):
    def create(ctx: ConsumerContext) = new InvoiceOnOrder

  private val broker                = InMemoryBroker()
  private var orders: AnkkaTestKit  = null
  private var billing: AnkkaTestKit = null

  override def beforeAll(): Unit =
    orders = AnkkaTestKit.start(
      Seq(LedgerEntity.descriptor, OrdersRelay.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )
    billing = AnkkaTestKit.start(
      Seq(Invoicer.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit =
    Option(orders).foreach(_.stop())
    Option(billing).foreach(_.stop())

  private def service(name: String) =
    ContextMap.Service(name, "", ContextMap.Boundary("ownership"))

  private val topic = ContextMap.Crossing(
    "orders",
    "billing",
    topic = Some("orders-placed"),
    contract = Some("order.v1"),
    schema = Some("contracts/order.v1.json")
  )

  private def map(crossings: ContextMap.Crossing*) =
    ContextMap("shop", Vector(service("orders"), service("billing")), crossings.toVector)

  test("a map that records what both services state has no problem") {
    assertEquals(ContextMapCheck.problems(map(topic), "orders", orders), Vector.empty)
    assertEquals(ContextMapCheck.problems(map(topic), "billing", billing), Vector.empty)
  }

  test("the descriptors alone, with no service started, give the same answers") {
    val components = Seq(LedgerEntity.descriptor, OrdersRelay.descriptor)
    assertEquals(ContextMapCheck.problems(map(topic), "orders", components), Vector.empty)
    assertEquals(
      ContextMapCheck.problems(map(), "orders", components),
      ContextMapCheck.problems(map(), "orders", orders)
    )
    assert(ContextMapCheck.problems(map(), "orders", components).nonEmpty)
  }

  test("a publication the map does not record") {
    assertEquals(
      ContextMapCheck.problems(map(), "orders", orders),
      Vector(
        "\"orders\" publishes to \"orders-placed\" as \"order.v1\", and the context map records no " +
          "crossing from \"orders\" by \"orders-placed\""
      )
    )
  }

  test("a read the map does not record") {
    assertEquals(
      ContextMapCheck.problems(map(), "billing", billing),
      Vector(
        "\"billing\" reads \"orders-placed\", and the context map records no crossing to " +
          "\"billing\" by \"orders-placed\""
      )
    )
  }

  test("a contract that differs from the map's") {
    val v2 = topic.copy(contract = Some("order.v2"))
    assertEquals(
      ContextMapCheck.problems(map(v2), "orders", orders),
      Vector(
        "\"orders\" publishes to \"orders-placed\" as \"order.v1\"; the context map says \"order.v2\""
      )
    )
    assertEquals(
      ContextMapCheck.problems(map(v2), "billing", billing),
      Vector(
        "\"billing\" reads \"orders-placed\" as \"order.v1\"; the context map says \"order.v2\""
      )
    )
  }

  test("a crossing no component states") {
    val shipped = ContextMap.Crossing(
      "orders",
      "billing",
      topic = Some("orders-shipped"),
      contract = Some("shipment.v1"),
      schema = Some("contracts/shipment.v1.json")
    )
    assertEquals(
      ContextMapCheck.problems(map(topic, shipped), "orders", orders),
      Vector(
        "the context map records a crossing from \"orders\" by \"orders-shipped\", and no component " +
          "of \"orders\" publishes to it"
      )
    )
    assertEquals(
      ContextMapCheck.problems(map(topic, shipped), "billing", billing),
      Vector(
        "the context map records a crossing to \"billing\" by \"orders-shipped\", and no component " +
          "of \"billing\" reads it"
      )
    )
  }

  test("a route crossing is not a topic and is not held to the components") {
    val call = ContextMap.Crossing("orders", "billing", route = Some("GET /billing/{id}"))
    assertEquals(ContextMapCheck.problems(map(topic, call), "orders", orders), Vector.empty)
  }

/** Reads a line and does nothing with it: the reading side of a crossing. */
final class InvoiceOnOrder extends Consumer[Line, Nothing]:
  def onMessage(line: Line): Effect = effects.done()
