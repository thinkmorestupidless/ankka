package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.mcp.features.Fixtures.JobEntity
import com.thinkmorestupidless.ankka.runtime.JournalRecord
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.Persistence
import org.apache.pekko.persistence.query.{NoOffset, PersistenceQuery}
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.stream.scaladsl.Sink

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

/**
 * Spike S1 (research R8): how soon a node that did not write a key's event sees it, following the
 * journal through `ankka.mcp.client-keys.query` (the runtime's query plugin, polling every 500ms).
 * Two nodes of one service, a throwaway entity written on the first, a follower on the second.
 *
 * Skipped unless `-Dankka.spikes=on`. It prints what it measured; `.claude/rules/mcp.md` records
 * it.
 */
class ClientKeyIndexSpike extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  test("a follower on another node sees a write within a second") {
    val kit = AnkkaTestKit.start(Seq(JobEntity.descriptor))
    try
      val peer             = kit.startPeer(Nil)
      given ActorSystem[?] = peer.service.system
      val journal          = PersistenceQuery(peer.service.system)
        .readJournalFor[R2dbcReadJournal]("ankka.mcp.client-keys.query")
      val maxSlice = Persistence(peer.service.system).numberOfSlices - 1
      val seen     = ConcurrentHashMap[String, Long]()
      journal
        .eventsBySlices[JournalRecord]("job", 0, maxSlice, NoOffset)
        .runWith(Sink.foreach(e => seen.putIfAbsent(e.persistenceId, System.nanoTime()): Unit))
      Thread.sleep(2000) // the follower's first poll
      val delays = (1 to 10).map { i =>
        val written = System.nanoTime()
        kit.componentClient
          .forEventSourcedEntity(EntityId(s"spike-$i"))
          .call(JobEntity.book)
          .invoke("slot")
        val deadline = 10.seconds.fromNow
        while !seen.containsKey(s"job|spike-$i") && deadline.hasTimeLeft() do Thread.sleep(10)
        assert(seen.containsKey(s"job|spike-$i"), s"spike-$i never seen")
        (seen.get(s"job|spike-$i") - written).nanos.toMillis
      }
      println(s"S1: follower delays (ms) ${delays.mkString(", ")}; max ${delays.max}")
      assert(delays.max < 1500, delays)
    finally kit.stop()
  }
