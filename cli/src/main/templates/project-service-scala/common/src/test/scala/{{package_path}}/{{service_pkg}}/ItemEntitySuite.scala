package {{package}}.{{service_pkg}}

import {{package}}.{{service_pkg}}.application.{AddItem, ItemEntity, RecordItem}
import {{package}}.{{service_pkg}}.domain.ItemEvent
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** The entity alone: no actor system, no database, milliseconds. */
class ItemEntitySuite extends munit.FunSuite:

  test("adding an item persists ItemAdded and folds it into state") {
    val kit    = EventSourcedTestKit.of(ItemEntity, "item-1")
    val result = kit.call(ItemEntity.addItem)(AddItem("Widget", 3))
    assertEquals(result.events, Vector(ItemEvent.ItemAdded("Widget", 3)))
    assertEquals(kit.currentState.count, 3)
  }

  test("a count of zero is refused, and nothing is persisted") {
    val kit    = EventSourcedTestKit.of(ItemEntity, "item-2")
    val result = kit.call(ItemEntity.addItem)(AddItem("Widget", 0))
    assert(result.isError, result)
    assertEquals(result.events, Vector.empty)
  }

  test("recording what the item already is persists nothing") {
    val kit = EventSourcedTestKit.of(ItemEntity, "item-3")
    kit.call(ItemEntity.recordItem)(RecordItem("Widget", 2)): Unit
    assertEquals(kit.call(ItemEntity.recordItem)(RecordItem("Widget", 2)).events, Vector.empty)
    assertEquals(kit.currentState.count, 2)
  }
