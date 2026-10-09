package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Metadata}
import com.thinkmorestupidless.ankka.runtime.*
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * What a topic source reports of what its topic no longer holds: `features/topics/gap.feature`'s
 * first three scenarios, over the in-memory broker, whose `drop` stands for retention and `compact`
 * for a compacted topic (feature 043). Cases run in order: each raises the view's version, so each
 * is a rebuild. A real dropped segment is `KafkaSuite`'s.
 */
class RetentionGapSuite extends munit.FunSuite with LogCapturing:

  import RetentionGapSuite.*
  import ViewVersionSuite.serializer

  override val munitTimeout = 3.minutes

  private val broker            = InMemoryBroker()
  private var kit: AnkkaTestKit = null
  private val times = (1 to 10).map(n => Instant.parse("2026-09-01T00:00:00Z").plusSeconds(n * 60L))

  override def beforeAll(): Unit =
    times.zipWithIndex.foreach { (at, i) =>
      broker.setClock(() => at)
      broker.publish(
        GapTopic,
        serializer.toBytes(sample(i)),
        Metadata.empty.withSubject(s"m$i")
      ): Unit
    }
    broker.setClock(() => Instant.now())
    kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      configure = _.registerAll(Seq(Entries.companion().descriptor)),
      serviceIdentity = ServiceIdentity.local("ledger")
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def eventually[A](what: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline = System.nanoTime() + within.toNanos
    var last     = check
    while last.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(100)
      last = check
    last.getOrElse(fail(s"$what did not happen within $within"))

  private def source = TopicSources(kit.service.system).all.find(_.componentId == "entries")

  private def rows = kit.service.viewClient.forView(Entries.companion()).all()

  /** Restarts at `version` and waits for the rebuild's gap, read after this restart began. */
  private def rebuildAt(version: Int): RetentionGap =
    Entries.version = version
    val restarted = Instant.now()
    kit.restartService()
    eventually(s"entries reports its gap at version $version") {
      source
        .flatMap(_.gap)
        .filter(g => !g.readAt.isBefore(restarted) && source.exists(_.version == version))
    }

  test("the gap is reported when a topic source subscribes") {
    val gap = eventually("entries reports its gap")(source.flatMap(_.gap))
    assertEquals(gap.partitions.map(p => (p.partition, p.beginning)), Vector((0, 0L)))
    assertEquals(gap.partitions.head.earliestAt, Some(times.head))
    assert(!gap.gone && !gap.compacted, gap.toString)
  }

  // features/topics/gap.feature
  test("a view rebuilt from a topic that still holds every message reports no retention gap") {
    val gap = rebuildAt(2)
    eventually("the rebuild read every message")(Option.when(rows.size == 10)(()))
    assertEquals(gap.partitions.map(_.beginning), Vector(0L))
    assert(!gap.gone, gap.toString)
  }

  test(
    "a view rebuilt from a topic that no longer holds its earliest messages reports the retention gap for each partition"
  ) {
    broker.drop(GapTopic, 3)
    val gap = rebuildAt(3)
    // The rebuild runs: what the broker still holds is read.
    eventually("the rebuild read what is held")(Option.when(rows.size == 7)(()))
    assertEquals(
      gap.partitions.map(p => (p.partition, p.beginning, p.earliestAt)),
      Vector((0, 3L, Some(times(3))))
    )
    assert(gap.gone, "messages are gone, because the beginning position is above 0")
    assert(!gap.compacted)
  }

  test(
    "a view rebuilt from a compacted topic reports the topic as compacted and not as having a retention gap"
  ) {
    broker.compact(GapTopic)
    val gap = rebuildAt(4)
    assert(gap.compacted, gap.toString)
    assert(!gap.gone, "a compacted topic is reported as compacted, not as having a gap")
  }

  // features/broker/cleanup-policy.feature, in memory: the runtime's rule, the broker's word
  test("a message published under no key to a compacted topic fails before it reaches the broker") {
    val before  = broker.publishedTo(GapTopic).size
    val refused = broker.publish(GapTopic, serializer.toBytes(sample(99)), Metadata.empty)
    val failure = intercept[KeylessPublication](scala.concurrent.Await.result(refused, 5.seconds))
    assertEquals(
      failure.getMessage,
      s"topic '$GapTopic' is compacted and a message published to it must carry a key or a subject"
    )
    assertEquals(broker.publishedTo(GapTopic).size, before, "nothing reached the broker")
    // A key or a subject is enough.
    scala.concurrent.Await.result(
      broker.publish(GapTopic, Some("m99"), serializer.toBytes(sample(99)), Metadata.empty),
      5.seconds
    ): Unit
    assertEquals(broker.publishedTo(GapTopic).size, before + 1)
  }

object RetentionGapSuite:

  val GapTopic: String = "transactions"

  def sample(i: Int): StockEvent = StockEvent(s"m$i", 1, "w1")

  final case class Entry(subject: String, version: Int)

  final class EntriesView(version: Int) extends View[StockEvent, Entry]:
    def onChange(event: StockEvent): Effect =
      effects.updateRow(Entry(updateContext.subject, version))

  object Entries:
    @volatile var version: Int = 1

    def companion(): View.Companion[EntriesView, StockEvent, Entry] =
      val declared = version
      new View.Companion[EntriesView, StockEvent, Entry](
        ComponentId("entries"),
        ChangeSource.Topic(GapTopic, ViewVersionSuite.serializer, Some(StartFrom.Earliest)),
        Codecs.serializer[Entry]("entry")
      ):
        override def version                  = Some(declared)
        def create(ctx: ViewComponentContext) = new EntriesView(declared)
