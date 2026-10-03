package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/** What arrives on the topic. */
final case class StockEvent(sku: String, delta: Int, warehouse: String)

/** A view built from a topic rather than an entity. */
final case class StockRow(sku: String, onHand: Int, updates: Int)

final class StockLevelsView extends View[StockEvent, StockRow]:

  def onChange(event: StockEvent): Effect =
    val current = rowState.getOrElse(StockRow(updateContext.subject, 0, 0))
    effects.updateRow(
      current.copy(onHand = current.onHand + event.delta, updates = current.updates + 1)
    )

object StockLevels
    extends View.Companion[StockLevelsView, StockEvent, StockRow](
      componentId = ComponentId("stock-levels"),
      source = ChangeSource.fromTopic("stock-events", Codecs.serializer[StockEvent]("stock-event")),
      rowSerializer = Codecs.serializer[StockRow]("stock-row")
    ):
  def create(ctx: ViewComponentContext) = new StockLevelsView

/** A consumer that reacts to the same topic and republishes low-stock alerts. */
final case class LowStockAlert(sku: String, onHand: Int)

final class LowStockNotifier extends Consumer[StockEvent, LowStockAlert]:

  def onMessage(event: StockEvent): Effect =
    LowStockNotifier.seen.add(s"${event.sku}:${event.delta}"): Unit
    if event.delta < 0 && event.delta <= -10 then
      effects.produce(LowStockAlert(event.sku, event.delta))
    else effects.ignore()

object LowStockNotifier
    extends Consumer.Companion[LowStockNotifier, StockEvent, LowStockAlert](
      componentId = ComponentId("low-stock-notifier"),
      source = ChangeSource.fromTopic("stock-events", Codecs.serializer[StockEvent]("stock-event"))
    ):

  /** What the consumer saw, for assertions. */
  val seen: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  def create(ctx: ConsumerContext) = new LowStockNotifier

  override val outputSerializer: Option[Serializer[LowStockAlert]] =
    Some(Codecs.serializer[LowStockAlert]("low-stock-alert"))

  override val produceTo: Option[String] = Some("stock-alerts")

/** One line of what a fan-out consumer publishes: the n-th message about `sku`. */
final case class FanLine(sku: String, n: Int)

/**
 * A consumer that publishes several messages for one message it reads: three for a movement of
 * stock — the second under a key of its own, the third with a header — and none at all for a
 * movement of nothing.
 */
final class StockFanout extends Consumer[StockEvent, FanLine]:

  def onMessage(event: StockEvent): Effect =
    if event.delta == 0 then effects.produceAll(Nil)
    else
      effects.produceAll(
        Seq(
          effects.message(FanLine(event.sku, 1)),
          effects.message(FanLine(event.sku, 2)).withKey(s"second:${event.sku}"),
          effects.message(FanLine(event.sku, 3)).withMetadata(Metadata.empty.set("x-n", "3"))
        )
      )

object StockFanout
    extends Consumer.Companion[StockFanout, StockEvent, FanLine](
      componentId = ComponentId("stock-fanout"),
      source = ChangeSource.fromTopic("fanout-events", Codecs.serializer[StockEvent]("stock-event"))
    ):
  def create(ctx: ConsumerContext) = new StockFanout

  override val outputSerializer: Option[Serializer[FanLine]] =
    Some(Codecs.serializer[FanLine]("fan-line"))

  override val produceTo: Option[String] = Some("fanout-lines")

/** Returns several messages and declares nowhere to publish them. */
final class TopiclessFanout extends Consumer[StockEvent, FanLine]:
  def onMessage(event: StockEvent): Effect =
    effects.produceAll(Seq(effects.message(FanLine(event.sku, 1))))

object TopiclessFanout
    extends Consumer.Companion[TopiclessFanout, StockEvent, FanLine](
      componentId = ComponentId("topicless-fanout"),
      source =
        ChangeSource.fromTopic("topicless-events", Codecs.serializer[StockEvent]("stock-event"))
    ):
  def create(ctx: ConsumerContext) = new TopiclessFanout
