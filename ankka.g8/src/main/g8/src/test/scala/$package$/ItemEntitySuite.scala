package $package$

import $package$.application.{AddItem, ItemEntity}
import $package$.domain.*
import $package$.domain.ItemEvent.*
import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/**
 * The entity with no runtime, cluster or database: effects are values, so this is milliseconds.
 * Inputs and replies still round-trip through the entity's own serializers, so a missing codec
 * fails here rather than on first deployment.
 */
class ItemEntitySuite extends munit.FunSuite:

  private def newKit = EventSourcedTestKit.of(ItemEntity, "item-1")

  test("adding persists exactly one event and acknowledges") {
    val kit    = newKit
    val result = kit.call(ItemEntity.addItem)(AddItem("Widget", 2))
    assertEquals(result.replyValue, Done)
    assertEquals(result.events, Vector(ItemAdded("Widget", 2)))
    assertEquals(kit.currentState, Item("item-1", "Widget", 2))
  }

  test("state is the fold of the events, and nothing else") {
    val kit = newKit
    val _   = kit.call(ItemEntity.addItem)(AddItem("Widget", 2))
    val _   = kit.call(ItemEntity.addItem)(AddItem("Widget", 3))
    val folded = kit.allEvents.foldLeft(Item.empty("item-1")) {
      case (item, ItemAdded(name, count)) => item.onAdded(name, count)
    }
    assertEquals(folded, kit.currentState, "replaying the journal must reproduce the state")
    assertEquals(kit.currentState.count, 5)
  }

  test("a rejected command persists nothing") {
    val kit    = newKit
    val result = kit.call(ItemEntity.addItem)(AddItem("Widget", 0))
    assert(result.isError)
    assertEquals(result.events, Vector.empty)
  }

  test("a query answers without changing anything") {
    val kit = newKit
    val _   = kit.call(ItemEntity.addItem)(AddItem("Widget", 1))
    val got = kit.call(ItemEntity.getItem)
    assertEquals(got.replyValue, Item("item-1", "Widget", 1))
    assertEquals(got.events, Vector.empty)
  }
