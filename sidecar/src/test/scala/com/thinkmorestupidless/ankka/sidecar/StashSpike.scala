package com.thinkmorestupidless.ankka.sidecar

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration.*

/**
 * Research verify item 3: how a remote host waits for a reply that arrives later, under
 * `withEnforcedReplies`, and what happens to commands that arrived meanwhile when the actor stops.
 *
 * Two shapes are compared. Pekko's own `Effect.stash()` drops the stash when the actor stops, so a
 * caller waiting on a stashed command would time out. An explicit queue held in the actor's state
 * can answer every queued caller `Unavailable` from `PostStop`, which is the shape the host uses.
 */
class StashSpike extends munit.FunSuite:

  sealed trait Cmd
  final case class Invoke(id: Int, replyTo: ActorRef[String]) extends Cmd
  final case class RemoteReplied(id: Int)                     extends Cmd
  final case class Stop()                                     extends Cmd

  final case class Replied(id: Int)

  /** In-flight command plus what arrived while it was in flight. */
  final case class Busy(current: Option[Invoke], queued: Vector[Invoke])

  private def queued(pid: String, remote: ActorRef[RemoteReplied]): Behavior[Cmd] =
    Behaviors.setup { _ =>
      var busy = Busy(None, Vector.empty)
      EventSourcedBehavior
        .withEnforcedReplies[Cmd, Replied, Int](
          PersistenceId.ofUniqueId(pid),
          0,
          (_, cmd) =>
            cmd match
              case inv: Invoke if busy.current.isEmpty =>
                busy = Busy(Some(inv), Vector.empty)
                remote ! RemoteReplied(inv.id) // the "remote" answers later, out of band
                Effect.none.thenNoReply()
              case inv: Invoke =>
                busy = busy.copy(queued = busy.queued :+ inv)
                Effect.none.thenNoReply()
              case RemoteReplied(id) =>
                busy.current match
                  case Some(inv) if inv.id == id =>
                    val next = busy.queued.headOption
                    busy = Busy(next, busy.queued.drop(1))
                    next.foreach(n => remote ! RemoteReplied(n.id))
                    Effect.persist(Replied(id)).thenReply(inv.replyTo)(c => s"ok:$id@$c")
                  case _ => Effect.none.thenNoReply()
              case Stop() => Effect.stop().thenNoReply(),
          (count, _) => count + 1
        )
        .receiveSignal { case (_, PostStop) =>
          (busy.current.toVector ++ busy.queued)
            .foreach(inv => inv.replyTo ! s"unavailable:${inv.id}")
        }
    }

  test("an explicit queue serves commands in order and answers the rest on stop") {
    val config = ConfigFactory
      .parseString(s"""
        pekko.actor.provider = local
        pekko.persistence.journal.plugin = "${PersistenceTestKitPlugin.PluginId}"
        pekko.persistence.snapshot-store.plugin = "${PersistenceTestKitSnapshotPlugin.PluginId}"
        # The in-memory journal serializes events; the spike's event is a plain case class.
        pekko.persistence.testkit.events.serialize = off
      """)
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())
    val kit = ActorTestKit(config)
    try
      // The remote answers only when told; the test is the remote.
      val remoteProbe = kit.createTestProbe[RemoteReplied]()
      val entity      = kit.spawn(queued("spike-1", remoteProbe.ref))
      val a           = kit.createTestProbe[String]()
      val b           = kit.createTestProbe[String]()
      val c           = kit.createTestProbe[String]()
      entity ! Invoke(1, a.ref)
      entity ! Invoke(2, b.ref)
      entity ! Invoke(3, c.ref)
      // Only the first is in flight.
      val first = remoteProbe.receiveMessage()
      assertEquals(first.id, 1)
      remoteProbe.expectNoMessage(200.millis)
      entity ! RemoteReplied(1)
      assertEquals(a.receiveMessage(), "ok:1@1")
      // The next queued command goes out only after the reply.
      assertEquals(remoteProbe.receiveMessage().id, 2)
      // Stop while 2 is in flight and 3 is queued: both callers are told, nobody hangs.
      entity ! Stop()
      assertEquals(b.receiveMessage(), "unavailable:2")
      assertEquals(c.receiveMessage(), "unavailable:3")
    finally kit.shutdownTestKit()
  }
