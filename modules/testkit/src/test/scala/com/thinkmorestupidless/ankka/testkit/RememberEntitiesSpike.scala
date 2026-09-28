package com.thinkmorestupidless.ankka.testkit

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}

import scala.concurrent.Await
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

/**
 * Throwaway: does a remembered entity come back after a full restart with nothing sent to it?
 *
 * An autonomous agent working a task has no caller to wake it after a crash, so the design depends
 * on sharding restarting it. Two configurations are measured, because the coordinator's own list of
 * shards is what a full restart must recover, and by default it lives in durable distributed data
 * on the local disk — which a pod does not keep.
 *
 * Gated on `-Dankka.spikes=on`.
 */
class RememberEntitiesSpike extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private val starts = ConcurrentHashMap[String, Integer]()

  private sealed trait Msg
  private case object Hello                                  extends Msg
  private case object Leave                                  extends Msg
  private final case class Asked(replyTo: ActorRef[Boolean]) extends Msg

  private def host(
      system: ActorSystem[?],
      key: EntityTypeKey[Msg],
      settings: ClusterShardingSettings
  ): ActorRef[ShardingEnvelope[Msg]] =
    ClusterSharding(system).init(
      Entity(key) { ctx =>
        Behaviors.setup[Msg] { self =>
          starts.merge(ctx.entityId, 1, (a, b) => a + b): Unit
          Behaviors.receiveMessage {
            case Hello          => Behaviors.same
            case Asked(replyTo) => replyTo ! true; Behaviors.same
            case Leave =>
              ctx.shard ! ClusterSharding.Passivate(self.self)
              Behaviors.same
          }
        }
      }.withSettings(settings)
    )

  private def journalIds(kit: AnkkaTestKit, prefix: String): Vector[String] =
    given ActorSystem[?] = kit.service.system
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT DISTINCT persistence_id FROM event_journal WHERE persistence_id LIKE '$prefix%'"
        )
      )(_.get("persistence_id", classOf[String])),
      10.seconds
    )

  private def eventually(what: String, within: FiniteDuration)(check: => Boolean): Unit =
    val deadline = within.fromNow
    while !check do
      if deadline.isOverdue() then fail(s"timed out waiting for: $what")
      Thread.sleep(200)

  private def measure(
      label: String,
      configure: ClusterShardingSettings => ClusterShardingSettings
  ): Unit =
    val kit = AnkkaTestKit.start(Seq.empty)
    try
      val key = EntityTypeKey[Msg](s"spike-$label")
      def settings(system: ActorSystem[?]) =
        configure(ClusterShardingSettings(system).withRememberEntities(true))

      val region = host(kit.service.system, key, settings(kit.service.system))
      region ! ShardingEnvelope("working", Hello)
      region ! ShardingEnvelope("idle", Hello)
      eventually("both started", 30.seconds)(
        starts.getOrDefault("working", 0) >= 1 && starts.getOrDefault("idle", 0) >= 1
      )
      region ! ShardingEnvelope("idle", Leave)
      Thread.sleep(3000) // let the passivation and the store's writes land

      val workingBefore = starts.get("working").intValue
      val idleBefore    = starts.get("idle").intValue
      kit.restartService()

      // Nothing is sent to either entity after the restart: only the entity type is initialised,
      // exactly as AgentRuntime.start would on a fresh node.
      host(kit.service.system, key, settings(kit.service.system)): Unit

      val deadline = 45.seconds.fromNow
      while starts.get("working").intValue == workingBefore && !deadline.isOverdue() do
        Thread.sleep(200)

      val cameBack  = starts.get("working").intValue > workingBefore
      val idleStays = starts.get("idle").intValue == idleBefore
      val rows      = journalIds(kit, "/sharding/")
      println(
        s"SPIKE[$label]: working entity restarted with no message = $cameBack; " +
          s"passivated entity stayed down = $idleStays; sharding journal ids = $rows"
      )
      assert(cameBack, s"[$label] the remembered entity did not come back after a full restart")
      assert(idleStays, s"[$label] a passivated entity came back")
    finally kit.stop()

  test("eventsourced remember-entities store, ddata coordinator") {
    measure(
      "es-ddata",
      _.withRememberEntitiesStoreMode(ClusterShardingSettings.RememberEntitiesStoreModeEventSourced)
    )
  }

  test("persistence coordinator (deprecated mode)") {
    measure(
      "persistence",
      _.withStateStoreMode(ClusterShardingSettings.StateStoreModePersistence)
    )
  }
