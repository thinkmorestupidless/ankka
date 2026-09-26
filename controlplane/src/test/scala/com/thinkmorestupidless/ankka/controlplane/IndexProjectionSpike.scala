package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.application.OrganizationEntity
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.JournalRecord
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{NoOffset, Offset, PersistenceQuery}
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Keep, Sink}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * Feature 013, research V1 and V2: can a control-plane node keep an index of every deploy token
 * warm in its own memory, and how long after a write does another node see it?
 *
 * The question exists because `Acl.Authenticate` is synchronous on the server's dispatcher, so
 * verifying a token may not read a row — and because an ankka `Consumer` is a
 * `ShardedDaemonProcess` that runs on *one* node, which is no use to an ACL running on whichever
 * node took the request.
 *
 * Measures; asserts almost nothing. Off unless asked for:
 *
 * {{{
 * sbt -Dankka.spikes=on 'controlPlane/testOnly *IndexProjectionSpike'
 * }}}
 */
final class IndexProjectionSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  override val munitTimeout = 10.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    if !munitIgnore then testKit = AnkkaTestKit.start(Seq(OrganizationEntity.descriptor))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private given system: ActorSystem[?] = testKit.service.system

  private def journal: R2dbcReadJournal =
    PersistenceQuery(system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  /** The whole slice range, as one node must watch it — not a sharded sub-range. */
  private def slices: (Int, Int) = (0, Persistence(system).numberOfSlices - 1)

  private def create(id: String): Unit =
    testKit.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(OrganizationEntity.createOrganization)
      .invoke(id): Unit

  private def rename(id: String, name: String): Unit =
    testKit.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(OrganizationEntity.rename)
      .invoke(name): Unit

  /** The bounded query, run to completion: every event the journal holds right now. */
  private def replay(from: Offset): Vector[EventEnvelope[JournalRecord]] =
    val (min, max) = slices
    Await
      .result(
        journal
          .currentEventsBySlices[JournalRecord]("organization", min, max, from)
          .runWith(Sink.seq),
        1.minute
      )
      .toVector

  test("V1: the bounded query completes, which is the 'caught up' signal readiness needs") {
    create("spike-a")
    rename("spike-a", "one")
    rename("spike-a", "two")

    val first = replay(NoOffset)
    println(s"\n  V1: a cold replay delivered ${first.size} event(s) and the stream completed")
    assert(first.sizeIs >= 3, s"expected the three events just written, got ${first.size}")
    val lastOffset = first.lastOption.map(_.offset).getOrElse(NoOffset)

    create("spike-b")
    rename("spike-b", "one")

    val again = replay(NoOffset)
    println(s"  V1: a second node starting cold replays everything: ${again.size} event(s)")
    assert(again.sizeIs >= 5, s"a cold replay should see everything, got ${again.size}")

    val tail = replay(lastOffset)
    println(s"  V1: resuming from the first replay's last offset delivered ${tail.size} event(s)")
    println(
      "  V1 ANSWER: `currentEventsBySlices` completes; the index is caught up when it does, and\n" +
        "             the live `eventsBySlices` continues from that query's last offset.\n"
    )
  }

  test("V2: how long after a write does a live stream see it") {
    val (min, max) = slices
    val seen       = new AtomicReference[Map[String, Long]](Map.empty)

    // The live query, exactly what a node runs after its cold replay finishes.
    val (killSwitch, done) = journal
      .eventsBySlices[JournalRecord]("organization", min, max, NoOffset)
      .map { envelope =>
        val at = System.nanoTime()
        seen.updateAndGet(m => m.updated(s"${envelope.persistenceId}#${envelope.sequenceNr}", at))
        envelope
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.both)
      .run()

    Thread.sleep(2000) // let the stream establish before measuring

    val samples = Vector.newBuilder[Long]
    create("spike-latency")
    // 30, not 200: each sample costs a poll interval, and 200 of them outran the munit timeout
    // the first time this ran — which was itself the finding.
    var round = 0
    while round < 30 do
      val written = System.nanoTime()
      rename("spike-latency", s"n$round")
      // The create is sequence 1, so this rename is sequence round + 2.
      val key      = s"organization|spike-latency#${round + 2}"
      val deadline = System.nanoTime() + 30.seconds.toNanos
      var at       = seen.get().get(key)
      while at.isEmpty && System.nanoTime() < deadline do
        Thread.sleep(2)
        at = seen.get().get(key)
      at.foreach(t => samples += (t - written))
      round += 1

    killSwitch.shutdown()
    Await.ready(done, 30.seconds): Unit

    val sorted = samples.result().sorted
    if sorted.isEmpty then println("\n  V2: no sample arrived — the live query saw nothing\n")
    else
      def millis(n: Long): Double = n.toDouble / 1000000.0
      val p50                     = sorted(sorted.size / 2)
      val p99                     = sorted(math.min(sorted.size - 1, (sorted.size * 99) / 100))
      println(f"""
           |  V2: ${sorted.size} samples, persist -> seen by a live eventsBySlices stream
           |      p50 ${millis(p50)}%,.0f ms
           |      p99 ${millis(p99)}%,.0f ms
           |      max ${millis(sorted.last)}%,.0f ms
           |""".stripMargin)
  }
