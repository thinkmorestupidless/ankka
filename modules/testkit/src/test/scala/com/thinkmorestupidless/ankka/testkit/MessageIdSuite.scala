package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  ProjectionSupport,
  SqlFragment
}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A message published from a journal event carries an id made from the event (feature 041): the
 * line of history the event was written on, its persistence id and its sequence, so the same event
 * published again carries the same id, and an event written after a restore never shares one with
 * an event the restore lost.
 */
class MessageIdSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null
  private val broker                = InMemoryBroker()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(
        LedgerEntity.descriptor,
        LedgerFanout.descriptor,
        ProfileEntity.descriptor,
        ProfileFanout.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      settings = ConfigFactory.parseString("ankka.history-line = \"A\"")
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def ids(topic: String, subject: String): Vector[String] =
    broker
      .publishedTo(topic)
      .toVector
      .filter(_.message.metadata.subject.contains(subject))
      .flatMap(_.message.metadata.eventId)

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def add(id: String, amount: Int): Unit =
    assertEquals(
      testKit.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(LedgerEntity.add)
        .invoke(amount),
      Done
    )

  test(
    "each message of an event carries its line, the persistence id, the sequence and its place"
  ) {
    add("m-one", 3)
    val carried = eventually("three ids")(Some(ids("ledger-lines", "m-one")).filter(_.size >= 3))
    assertEquals(carried.size, 3)
    for (id, place) <- carried.zipWithIndex do
      assert(id.startsWith("A/") && id.contains("m-one"), id)
      assert(id.endsWith(s"/1/$place"), id)
  }

  test("a key value state's messages carry its revision") {
    testKit.componentClient
      .forKeyValueEntity(EntityId("m-profile"))
      .call(ProfileEntity.register)
      .invoke(Profile("Ada", "ada@example.test", 0)): Unit
    val carried = eventually("two ids")(Some(ids("profile-lines", "m-profile")).filter(_.size >= 2))
    assert(carried.forall(id => id.startsWith("A/") && id.contains("/1/")), carried.toString)
  }

  test("an event written after a new line began carries the new line, and an older one the old") {
    add("m-two", 1)
    eventually("the first id")(Some(ids("ledger-lines", "m-two")).filter(_.size >= 3))
    // As a restore's first start does: a row beginning a line after the first event.
    Thread.sleep(50)
    val db = Database()(using testKit.service.system)
    Await.result(
      db.execute(SqlFragment.raw("INSERT INTO ankka_history_lines (line_id) VALUES ('B')")),
      30.seconds
    ): Unit
    testKit.restartService()
    add("m-two", 2)
    val carried = eventually("six ids")(Some(ids("ledger-lines", "m-two")).filter(_.size >= 6))
    assert(carried.take(3).forall(_.startsWith("A/")), carried.toString)
    assert(
      carried
        .drop(3)
        .zipWithIndex
        .forall((id, place) => id.startsWith("B/") && id.endsWith(s"/2/$place")),
      carried.toString
    )
  }

  test("an id the handler declared is kept, and a message with no event keeps a random one") {
    val declared = Metadata.empty.add(Metadata.CeId, "mine")
    assertEquals(ProjectionSupport.withEventId(declared, Some("A/p/1"), None).eventId, Some("mine"))
    assertEquals(ProjectionSupport.withEventId(Metadata.empty, None, None).eventId, None)
    assertEquals(
      ProjectionSupport.withEventId(Metadata.empty, Some("A/p/1"), None).eventId,
      Some("A/p/1")
    )
  }
