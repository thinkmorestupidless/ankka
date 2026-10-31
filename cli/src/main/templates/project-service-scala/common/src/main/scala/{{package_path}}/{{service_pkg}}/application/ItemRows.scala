package {{package}}.{{service_pkg}}.application

import {{package}}.{{service_pkg}}.domain.ItemEvent
import {{package}}.{{service_pkg}}.domain.ItemEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per item, for listing: the read side an entity cannot answer on its own. */
final case class ItemRow(id: String, name: String, count: Int)

/** A view: fed the entity's events in order, keeping one row per entity. */
final class ItemRowsView extends View[ItemEvent, ItemRow]:

  def onChange(event: ItemEvent): Effect =
    val current = rowState.getOrElse(ItemRow(updateContext.subject, "", 0))
    event match
      case ItemAdded(name, count) =>
        effects.updateRow(current.copy(name = name, count = current.count + count))
      case ItemRecorded(name, count) =>
        effects.updateRow(current.copy(name = name, count = count))

object ItemRows
    extends View.Companion[ItemRowsView, ItemEvent, ItemRow](
      componentId = ComponentId("item-rows"),
      source = ChangeSource.eventsOf(ItemEntity),
      rowSerializer = Codecs.serializer[ItemRow]("item-row")
    ):
  def create(ctx: ViewComponentContext) = new ItemRowsView
