package $package$.application

import $package$.domain.ItemEvent
import $package$.domain.ItemEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per item, for listing — the read side an entity cannot answer on its own. */
final case class ItemRow(id: String, name: String, count: Int)

/**
 * A view: fed the entity's events in order, keeping one row per entity. Rows land in a Postgres
 * table and are queried with SQL over the row's JSON (see `ItemEndpoint`).
 */
final class ItemRowsView extends View[ItemEvent, ItemRow]:

  def onChange(event: ItemEvent): Effect =
    val current = rowState.getOrElse(ItemRow(updateContext.subject, "", 0))
    event match
      case ItemAdded(name, count) =>
        effects.updateRow(current.copy(name = name, count = current.count + count))

object ItemRows
    extends View.Companion[ItemRowsView, ItemEvent, ItemRow](
      componentId = ComponentId("item-rows"),
      source = ChangeSource.eventsOf(ItemEntity),
      rowSerializer = Codecs.serializer[ItemRow]("item-row")
    ):
  def create(ctx: ViewComponentContext) = new ItemRowsView
