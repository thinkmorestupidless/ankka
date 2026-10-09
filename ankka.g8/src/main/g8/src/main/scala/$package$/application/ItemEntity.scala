package $package$.application

import $package$.domain.*
import $package$.domain.ItemEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*

/**
 * An event sourced entity: one instance per item id, holding the item's state as the fold of its
 * events. A handler returns an *effect* — a description of what should happen — and never performs
 * I/O itself; that is what lets `ItemEntitySuite` drive it with no database.
 */
final class ItemEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Item, ItemEvent]:

  private val itemId: String = context.entityId

  def emptyState: Item = Item.empty(itemId)

  def applyEvent(event: ItemEvent): Item = event match
    case ItemAdded(name, count) => currentState.onAdded(name, count)
    case OwnerSet(owner)        => currentState.onOwnerSet(owner)

  /** A command: validated, then persisted, then answered. */
  def addItem(request: AddItem): Effect[Done] =
    if request.count <= 0 then
      effects.error(s"count must be greater than zero, was \${request.count}")
    else effects.persist(ItemAdded(request.name, request.count)).thenReply(_ => Done)

  /**
   * The owner, by an opaque id and an email. The id names the data subject — never put anything
   * personal in it, since it stays readable — and the email is the personal field.
   */
  def setOwner(request: SetOwner): Effect[Done] =
    effects
      .persist(OwnerSet(Personal.present(s"user/\${request.user}", request.email)))
      .thenReply(_ => Done)

  /** A query: a `ReadOnlyEffect` cannot persist, and the compiler holds you to it. */
  def getItem: ReadOnlyEffect[Item] = effects.reply(currentState)

/** The request body of `add-item`. */
final case class AddItem(name: String, count: Int)

/** The request body of `set-owner`. */
final case class SetOwner(user: String, email: String)

object ItemEntity
    extends EventSourcedEntity.Companion[ItemEntity, Item, ItemEvent](
      componentId = ComponentId("item"),
      stateSerializer = Codecs.serializer[Item]("item"),
      eventSerializer = Codecs.serializer[ItemEvent]("item-event")
    ):

  given Serializer[AddItem]  = Codecs.serializer[AddItem]("add-item")
  given Serializer[SetOwner] = Codecs.serializer[SetOwner]("set-owner")

  def create(context: EventSourcedEntityContext) = new ItemEntity(context)

  // The string is the wire name — the versioning boundary. Rename the Scala method freely; change
  // the wire name and in-flight callers break.
  val addItem  = command("add-item")(_.addItem)
  val setOwner = command("set-owner")(_.setOwner)
  val getItem  = query("get-item")(_.getItem)
