package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, GraphElementRefused}
import com.thinkmorestupidless.ankka.core.{Codecs, Done, EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

/**
 * Publishing a graph is safe to repeat, and the versions of one entity's elements never go
 * backwards: across updates, deletion, and the entity being created again under the same id — for
 * an event sourced entity, a key value entity, and a topic, which has no sequence number at all.
 */
class GraphVersionsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null
  private val broker                = InMemoryBroker()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(
        LedgerEntity.descriptor,
        LedgerGraph.descriptor,
        ProfileEntity.descriptor,
        ProfileGraph.descriptor,
        StockGraph.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def ledger(id: String)  = testKit.componentClient.forEventSourcedEntity(EntityId(id))
  private def profile(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))

  private def add(id: String, amount: Int): Unit =
    assertEquals(ledger(id).call(LedgerEntity.add).invoke(amount), Done)
  private def register(id: String, name: String): Unit =
    assertEquals(
      profile(id).call(ProfileEntity.register).invoke(Profile(name, s"$name@example.com", 0)),
      Done
    )

  /** The deltas published about one subject, read as a consumer of the topic reads them. */
  private def published(topic: String, subject: String): Vector[GraphDelta] =
    broker
      .publishedTo(topic)
      .toVector
      .filter(_.message.metadata.subject.contains(subject))
      .map(d => GraphDelta.read(d.message.key, d.message.payload).fold(p => fail(p), identity))

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("an event sourced entity's elements are versioned by the events' sequence numbers") {
    add("g-seq", 5)
    add("g-seq", 6)
    val deltas =
      eventually("four deltas")(Some(published("ledger-graph", "g-seq")).filter(_.sizeIs >= 4))
    assertEquals(
      deltas.map(d => d.key -> d.version),
      Vector(
        "node:ledger:g-seq" -> 1L,
        "node:latest:g-seq" -> 1L,
        "node:ledger:g-seq" -> 2L,
        "node:latest:g-seq" -> 2L
      )
    )
  }

  test("a stated version is used in place of the sequence number") {
    add("g-stated", 5000)
    val deltas =
      eventually("two deltas")(Some(published("ledger-graph", "g-stated")).filter(_.sizeIs >= 2))
    assertEquals(deltas.map(_.version).distinct, Vector(5000L))
  }

  test("a key value entity's elements are versioned by its revisions, never zero") {
    register("g-kv", "Ada")
    assertEquals(profile("g-kv").call(ProfileEntity.recordLogin).invoke(), 1)
    // A key value source delivers the latest state and may skip one before it: wait for the last.
    val last = eventually("the state after the login") {
      published("profile-graph", "g-kv").find(_.properties.get("logins").contains(1L))
    }
    assertEquals(last.version, 2L)
    val versions = published("profile-graph", "g-kv").map(_.version)
    assert(versions.forall(_ >= 1), versions.toString)
    assertEquals(versions, versions.sorted)
  }

  test(
    "a deleted event sourced entity is tombstoned above every version before, and returns above the tombstone"
  ) {
    add("g-gone", 1)
    assertEquals(ledger("g-gone").call(LedgerEntity.close).invoke(), Done)
    val tombstones = eventually("both tombstones") {
      Some(published("ledger-graph", "g-gone").filter(_.isTombstone)).filter(_.sizeIs >= 2)
    }
    // The event, the close, then the deletion.
    assertEquals(tombstones.map(_.version).distinct, Vector(3L))
    assertEquals(tombstones.map(_.key), Vector("node:ledger:g-gone", "node:latest:g-gone"))

    add("g-gone", 2)
    val again = eventually("the node after the tombstone") {
      published("ledger-graph", "g-gone").find(d => !d.isTombstone && d.version > 3)
    }
    assertEquals(again.version, 4L)
    val all = published("ledger-graph", "g-gone").map(_.version)
    assertEquals(all, all.sorted, "one entity's versions only rise")
  }

  test(
    "a deleted key value entity is tombstoned at the next revision, and returns above the tombstone"
  ) {
    register("g-kv-gone", "Ada")
    eventually("the node")(published("profile-graph", "g-kv-gone").headOption)
    assertEquals(profile("g-kv-gone").call(ProfileEntity.close).invoke(), Done)
    val tombstone =
      eventually("the tombstone")(published("profile-graph", "g-kv-gone").find(_.isTombstone))
    assertEquals(tombstone.key, "node:profile:g-kv-gone")
    assertEquals(tombstone.version, 2L)

    register("g-kv-gone", "Grace")
    val again = eventually("the node after the tombstone") {
      published("profile-graph", "g-kv-gone").find(d =>
        !d.isTombstone && d.version > tombstone.version
      )
    }
    assertEquals(again.version, 3L)
    assertEquals(again.properties("name"), "Grace")
  }

  test("a change handled twice publishes equal deltas") {
    add("g-warm", 1)
    eventually("the consumer is running")(
      Some(published("ledger-graph", "g-warm")).filter(_.sizeIs >= 2)
    )

    // Refuse the second of the change's two records: the first is in the topic, the change is not
    // handled, and it comes again whole.
    broker.failNext("ledger-graph", after = 1)
    add("g-twice", 7)
    val deltas = eventually("both elements, after redelivery", 90.seconds) {
      Some(published("ledger-graph", "g-twice")).filter(_.exists(_.key == "node:latest:g-twice"))
    }
    val ledgers = deltas.filter(_.key == "node:ledger:g-twice")
    assert(ledgers.sizeIs >= 2, s"the first record was published again: ${deltas.map(_.key)}")
    assertEquals(
      ledgers.distinct.size,
      1,
      "and is the same delta each time: key, version and value"
    )
    assertEquals(ledgers.head.version, 1L)
  }

  // ── A topic has no sequence number ───────────────────────────────────────

  private val stockEvent = Codecs.serializer[StockEvent]("stock-event")

  private def deliver(event: StockEvent): Try[org.apache.pekko.Done] =
    Try(
      Await.result(
        broker.publish(
          "stock-graph-events",
          stockEvent.toBytes(event),
          Metadata.empty.withSubject(event.sku)
        ),
        10.seconds
      )
    )

  test("over a topic, an element that states no version fails its message, saying what to do") {
    val failure = deliver(StockEvent("s-none", 9, "w1")).failed.get
    assert(failure.isInstanceOf[GraphElementRefused], failure.toString)
    assertEquals(failure.asInstanceOf[GraphElementRefused].why, "no-sequence")
    assert(failure.getMessage.contains("state a version"), failure.getMessage)
    assertEquals(published("stock-graph", "s-none"), Vector.empty)
    // It would fail every time it came again, and a broker hands it again before anything after it.
    broker.skipFailed("stock-graph-events")
  }

  test("over a topic, an element that states its version is published at it") {
    assert(deliver(StockEvent("s-stated", 42, "stated")).isSuccess)
    assertEquals(
      published("stock-graph", "s-stated").map(d => d.key -> d.version),
      Vector("node:sku:s-stated" -> 42L)
    )
  }
