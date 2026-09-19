package $package$

import $package$.application.{AddItem, ItemEntity}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import scala.concurrent.duration.DurationInt

/**
 * The whole service against a throwaway Postgres. `restartService()` drops every entity from
 * memory, so the second read proves durability — the state came back from the journal, not from
 * a cache.
 */
class ItemIntegrationSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(ItemEntity.descriptor)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def item(id: String) = testKit.componentClient.forEventSourcedEntity(EntityId(id))

  test("state survives losing every entity from memory") {
    item("durable").call(ItemEntity.addItem).invoke(AddItem("Widget", 4))
    testKit.restartService()
    assertEquals(item("durable").call(ItemEntity.getItem).invoke().count, 4)
  }

  test("entities with different ids are isolated") {
    item("a").call(ItemEntity.addItem).invoke(AddItem("A", 1))
    item("b").call(ItemEntity.addItem).invoke(AddItem("B", 2))
    assertEquals(item("a").call(ItemEntity.getItem).invoke().count, 1)
    assertEquals(item("b").call(ItemEntity.getItem).invoke().count, 2)
  }
