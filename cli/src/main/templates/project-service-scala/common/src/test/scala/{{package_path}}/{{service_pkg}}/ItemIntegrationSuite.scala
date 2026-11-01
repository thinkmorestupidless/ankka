package {{package}}.{{service_pkg}}

import {{package}}.{{service_pkg}}.application.{AddItem, ItemEntity}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import scala.concurrent.duration.DurationInt

/**
 * The entity in a running service against a throwaway Postgres. `restartService()` drops every entity
 * from memory, so the second read proves durability.
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
