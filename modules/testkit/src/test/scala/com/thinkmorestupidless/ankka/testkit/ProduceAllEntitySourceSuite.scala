package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, Done, EntityId}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A consumer over an entity publishes several messages for one change: over an event sourced
 * entity's events and over a key value entity's states, on a real journal with the in-memory broker
 * standing in for the wire.
 */
class ProduceAllEntitySourceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null
  private val broker                = InMemoryBroker()
  private val lineSerializer        = Codecs.serializer[Line]("line")

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(
        LedgerEntity.descriptor,
        LedgerFanout.descriptor,
        ProfileEntity.descriptor,
        ProfileFanout.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def ledger(id: String)  = testKit.componentClient.forEventSourcedEntity(EntityId(id))
  private def profile(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))

  private def add(id: String, amount: Int): Unit =
    assertEquals(ledger(id).call(LedgerEntity.add).invoke(amount), Done)

  /**
   * What was published about one subject, as (record key, line), in the order the broker took it.
   */
  private def published(topic: String, subject: String): Vector[(Option[String], Line)] =
    broker
      .publishedTo(topic)
      .toVector
      .filter(_.message.metadata.subject.contains(subject))
      .map(d => d.message.key -> lineSerializer.fromBytes(d.message.payload))

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("an event becomes several records, in order, each under its key, else the entity's id") {
    add("l-order", 7)
    val records =
      eventually("three lines")(Some(published("ledger-lines", "l-order")).filter(_.size >= 3))
    assertEquals(records.map(_._2.n), Vector(1, 2, 3))
    assertEquals(
      records.map(_._1),
      Vector(Some("l-order"), Some("second:l-order"), Some("l-order"))
    )
    assertEquals(
      records.map(_._2.sequence).distinct,
      Vector(1L),
      "all at the event's sequence number"
    )
  }

  test("an event that produces an empty list publishes nothing, and the next event is published") {
    add("l-empty", 0)
    add("l-empty", 4)
    val records =
      eventually("three lines")(Some(published("ledger-lines", "l-empty")).filter(_.size >= 3))
    // Only the second event's lines: the first was handled with nothing to publish.
    assertEquals(records.map(_._2.sequence).distinct, Vector(2L))
    assertEquals(records.size, 3)
  }

  test("the deletion handler publishes several records too, above the events before it") {
    add("l-gone", 1)
    assertEquals(ledger("l-gone").call(LedgerEntity.close).invoke(), Done)
    val deleted = eventually("the deletion's lines") {
      Some(published("ledger-lines", "l-gone").filter(_._2.note == "deleted")).filter(_.size >= 2)
    }
    assertEquals(deleted.map(_._1), Vector(Some("l-gone"), Some("gone:l-gone")))
    // The event, the close event, then the deletion: sequence 3.
    assertEquals(deleted.map(_._2.sequence).distinct, Vector(3L))
  }

  test("a key value entity's state becomes several records, at its revision; its deletion too") {
    assertEquals(
      profile("p-lines").call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 0)),
      Done
    )
    val records =
      eventually("two lines")(Some(published("profile-lines", "p-lines")).filter(_.size >= 2))
    assertEquals(records.map(_._1), Vector(Some("p-lines"), Some("name:p-lines")))
    assertEquals(records.map(_._2.sequence).distinct, Vector(1L))

    assertEquals(profile("p-lines").call(ProfileEntity.close).invoke(), Done)
    val deletion = eventually("the deletion's line") {
      published("profile-lines", "p-lines").find(_._2.note == "deleted")
    }
    assertEquals(deletion._2.sequence, 2L)
  }

  // The two cases below fail a publication on purpose, which restarts the consumer's projection
  // after a backoff. They wait for that; nothing after them shares their topics' counters.

  test(
    "when the broker refuses one of an event's records, the event comes again and none is missing"
  ) {
    add("l-warm", 1)
    eventually("the consumer is running")(
      Some(published("ledger-lines", "l-warm")).filter(_.size >= 3)
    )

    broker.failNext("ledger-lines", after = 1)
    add("l-refused", 9)
    val records = eventually("every line of the event, after redelivery", 90.seconds) {
      Some(published("ledger-lines", "l-refused")).filter(_.exists(_._2.n == 2))
    }
    assertEquals(records.map(_._2.n).toSet, Set(1, 2, 3))
    assert(
      records.count(_._2.n == 1) >= 2,
      s"the first line was published again: ${records.map(_._2.n)}"
    )
    // Whatever the repeats, the last three are the event's lines in order.
    assertEquals(records.takeRight(3).map(_._2.n), Vector(1, 2, 3))
  }

  test(
    "when the broker refuses one of a state's records, the state comes again and none is missing"
  ) {
    broker.failNext("profile-lines", after = 0)
    assertEquals(
      profile("p-refused")
        .call(ProfileEntity.register)
        .invoke(Profile("Grace", "g@example.com", 0)),
      Done
    )
    val records = eventually("both lines of the state, after redelivery", 90.seconds) {
      Some(published("profile-lines", "p-refused")).filter(_.exists(_._2.n == 1))
    }
    assertEquals(records.map(_._2.n).toSet, Set(1, 2))
    assert(
      records.count(_._2.n == 2) >= 2,
      s"the second line was published again: ${records.map(_._2.n)}"
    )
  }
