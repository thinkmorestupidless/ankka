package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{
  Announcement,
  Database,
  ProjectionRuntime,
  SqlFragment,
  ViewListener,
  ViewStore
}
import com.thinkmorestupidless.ankka.sdk.ViewDescriptor
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Every write of a view's row is announced when it commits, and only then, and a rebuild likewise;
 * and the instance's listener hears it.
 *
 * What is heard is read from the listener's own connection, apart from the connection that wrote,
 * so a notification reaching it is one Postgres delivered on commit.
 */
class ViewAnnounceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit      = null
  private var listener: ViewListener = null
  private val heard                  = LinkedBlockingQueue[Announcement]()
  private val table                  = ViewDescriptor.tableFor(Orders.componentId)

  override def beforeAll(): Unit =
    kit =
      AnkkaTestKit.start(Seq(OrderEntity.descriptor, Orders.descriptor), Seq(ProjectionRuntime()))
    listener = ViewListener(using kit.service.system)
    Await.result(listener.subscribe(table)(heard.put, _ => ()), 30.seconds): Unit

  override def afterAll(): Unit =
    if listener != null then Await.result(listener.stop(), 10.seconds)
    if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit = heard.clear()

  private def database = Database()(using kit.service.system)

  private def next(within: FiniteDuration = 10.seconds): Option[Announcement] =
    Option(heard.poll(within.toMillis, MILLISECONDS))

  private def run(fragments: SqlFragment*): Unit =
    Await.result(database.executeAllInTransaction(fragments), 10.seconds)

  test("a row written through a projection is announced by its key") {
    kit.componentClient
      .forEventSourcedEntity(EntityId("a1"))
      .call(OrderEntity.place)
      .invoke(Order("alice", 1)): Unit
    assertEquals(next(30.seconds), Some(Announcement.Written("a1")))
  }

  test("a written row and a deleted row are each announced; a row never there is not") {
    run(ViewStore.upsert(table, "w1", """{"orderId":"w1","customer":"x","n":1}"""))
    assertEquals(next(), Some(Announcement.Written("w1")))
    run(ViewStore.delete(table, "w1"))
    assertEquals(next(), Some(Announcement.Written("w1")))
    run(ViewStore.delete(table, "never-there"))
    assertEquals(next(1.second), None)
  }

  test("a write that rolls back is not announced") {
    val failed = scala.util.Try(
      run(
        ViewStore.upsert(table, "r1", """{"orderId":"r1","customer":"x","n":1}"""),
        SqlFragment.raw("SELECT 1 / 0")
      )
    )
    assert(failed.isFailure)
    assertEquals(next(1.second), None)
  }

  test("a row written twice in one transaction is announced once") {
    run(
      ViewStore.upsert(table, "t1", """{"orderId":"t1","customer":"x","n":1}"""),
      ViewStore.upsert(table, "t1", """{"orderId":"t1","customer":"x","n":2}""")
    )
    assertEquals(next(), Some(Announcement.Written("t1")))
    assertEquals(next(1.second), None)
  }

  test("a key too long to announce is written and not announced") {
    val key = "k" * ViewStore.AnnouncedKeyBytes
    run(ViewStore.upsert(table, key, """{"orderId":"long","customer":"x","n":1}"""))
    assertEquals(next(1.second), None)
    val held = Await.result(
      database.query(ViewStore.selectByKey(table, key))(_.get("payload", classOf[String])),
      10.seconds
    )
    assertEquals(held.size, 1)
  }

  test("another view's writes are not heard as this one's") {
    val other = "ankka_view_other_orders"
    run(ViewStore.createTable(other))
    run(ViewStore.upsert(other, "n1", "{}"))
    assertEquals(next(1.second), None)
  }

  test("a rebuild is announced") {
    run(ViewStore.announceRebuilt(table))
    assertEquals(next(), Some(Announcement.Rebuilt))
  }
