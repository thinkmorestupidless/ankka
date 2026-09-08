package nakka.testkit

import nakka.core.*
import nakka.sdk.*

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
