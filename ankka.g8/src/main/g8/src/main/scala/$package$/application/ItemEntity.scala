package $package$.application

import $package$.domain.*
import $package$.domain.ItemEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * An event sourced entity: one instance per item id, holding the item's state as the fold of its
 * events. A handler returns an *effect* — a description of what should happen — and never performs
 * I/O itself; that is what lets `ItemEntitySuite` drive it with no database.
 */
final class ItemEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Item, ItemEvent]:

  private val itemId: String = context.entityId

  def emptyState: Item = Item.empty(itemId)

  def applyEvent(event: ItemEvent): Item = event match
    case ItemAdded(name, count) => currentState.onAdded(name, count)

  /** A command: validated, then persisted, then answered. */
  def addItem(request: AddItem): Effect[Done] =
    if request.count <= 0 then
      effects.error(s"count must be greater than zero, was \${request.count}")
    else effects.persist(ItemAdded(request.name, request.count)).thenReply(_ => Done)

  /** A query: a `ReadOnlyEffect` cannot persist, and the compiler holds you to it. */
  def getItem: ReadOnlyEffect[Item] = effects.reply(currentState)

/** The request body of `add-item`. */
final case class AddItem(name: String, count: Int)

object ItemEntity
    extends EventSourcedEntity.Companion[ItemEntity, Item, ItemEvent](
      componentId = ComponentId("item"),
      stateSerializer = Codecs.serializer[Item]("item"),
      eventSerializer = Codecs.serializer[ItemEvent]("item-event")
    ):

  given Serializer[AddItem] = Codecs.serializer[AddItem]("add-item")

  def create(context: EventSourcedEntityContext) = new ItemEntity(context)

  // The string is the wire name — the versioning boundary. Rename the Scala method freely; change
  // the wire name and in-flight callers break.
  val addItem = command("add-item")(_.addItem)
  val getItem = query("get-item")(_.getItem)
