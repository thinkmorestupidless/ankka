package {{package}}.{{service_pkg}}.application

import {{package}}.contracts.{{Service}}V1
import {{package}}.{{service_pkg}}.domain.ItemEvent
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A crossing of the project's context map: each change to an item is published to the declared topic
 * `{{service}}-events` as the item now is, stating the contract `{{service}}.v1` the topic carries. The
 * message type and the contract are the shared module's, so the reading side builds against the same.
 *
 * A message carries the whole item rather than the change, so a reader can apply it twice, or late,
 * and still hold what {{service}} holds.
 */
final class {{Service}}Publisher(context: ConsumerContext) extends Consumer[ItemEvent, {{Service}}V1.Item]:

  def onMessage(event: ItemEvent): Effect =
    val id   = messageContext.subject
    val item = context.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(ItemEntity.getItem)
      .invoke()
    effects.produce({{Service}}V1.Item(id, item.name, item.count))

object {{Service}}Publisher
    extends Consumer.Companion[{{Service}}Publisher, ItemEvent, {{Service}}V1.Item](
      componentId = ComponentId("{{service}}-publisher"),
      source = ChangeSource.eventsOf(ItemEntity)
    ):
  def create(ctx: ConsumerContext) = new {{Service}}Publisher(ctx)

  override val outputSerializer: Option[Serializer[{{Service}}V1.Item]] = Some({{Service}}V1.serializer)

  override def produces: Option[Publication] =
    Some(Publication("{{service}}-events", Some({{Service}}V1.contract)))
