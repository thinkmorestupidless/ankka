package {{package}}.{{service_pkg}}.application

import {{package}}.contracts.{{First}}V1
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A crossing of the project's context map: {{service}} reads the declared topic `{{first}}-events`,
 * stating the contract `{{first}}.v1` it expects, and records each item as {{first}} last said it is.
 * Delivery is at least once, and `record-item` changes nothing when it is told what it already holds.
 */
final class {{First}}Reader(context: ConsumerContext) extends Consumer[{{First}}V1.Item, Nothing]:

  def onMessage(item: {{First}}V1.Item): Effect =
    context.componentClient
      .forEventSourcedEntity(EntityId(item.id))
      .call(ItemEntity.recordItem)
      .invoke(RecordItem(item.name, item.count))
    effects.done()

object {{First}}Reader
    extends Consumer.Companion[{{First}}Reader, {{First}}V1.Item, Nothing](
      componentId = ComponentId("{{first}}-reader"),
      source = ChangeSource.fromTopic(
        "{{first}}-events",
        {{First}}V1.serializer,
        StartFrom.Earliest,
        TopicOptions(contract = Some({{First}}V1.contract))
      )
    ):
  def create(ctx: ConsumerContext) = new {{First}}Reader(ctx)
