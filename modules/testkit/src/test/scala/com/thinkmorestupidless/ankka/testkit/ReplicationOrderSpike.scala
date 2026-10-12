package com.thinkmorestupidless.ankka.testkit

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.persistence.typed.{ReplicaId, ReplicationId}
import org.apache.pekko.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  ReplicatedEventSourcing
}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}

import com.thinkmorestupidless.ankka.runtime.{AnkkaSerializable, Database, SqlFragment}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Throwaway: what order does a Pekko replica apply events in when they come from two writers?
 *
 * The home-replica design has one writer at a time: A writes, home moves to B, B writes. A third
 * replica reads A's journal and B's journal as two independent streams. The question is whether
 * Pekko's replicated event sourcing applies B's events before A's earlier ones, and whether the
 * `concurrent` flag says anything when it does.
 *
 * Three replicas, three databases in the shared Postgres, one actor system each, no cluster: a
 * replica reads its siblings through a query plugin of its own pointing at the sibling's database,
 * exactly as an installation would read another installation's journal. A's journal is made to lag
 * for C by hiding its rows (`deleted = true`, which the by-persistence-id query filters out) until
 * C has consumed B's events, then unhiding them.
 *
 * Two entity flavours run the same scenario: `naive` applies every event as it arrives and records
 * the order; `buffered` holds an event whose canonical position is not the next one in its state
 * and applies it when the gap fills. Both record arrival order, so the journal order at C can be
 * compared with the order the entity applied.
 *
 * Gated on `-Dankka.spikes=on`: sbt -Dankka.spikes=on 'testkit/testOnly *ReplicationOrderSpike'
 */
class ReplicationOrderSpike extends munit.FunSuite with LogCapturing:
  import ReplicationOrderSpike.*

  override val munitTimeout = 5.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private val TypeName = "spike-car"
  private val Replicas = Vector("A", "B", "C")

  private final class Replica(
      val id: String,
      val database: TestDatabase,
      val system: ActorSystem[Nothing]
  ):
    given ActorSystem[?] = system
    def pid(entityId: String): String =
      ReplicationId(TypeName, entityId, ReplicaId(id)).persistenceId.id

    /** Spawns the entity in this replica; the actor name must differ per flavour. */
    def spawn(entityId: String, buffered: Boolean): ActorRef[Cmd] =
      system.systemActorOf(behavior(entityId, buffered), s"$entityId-${System.nanoTime()}")

    private def behavior(entityId: String, buffered: Boolean): Behavior[Cmd] =
      val plugins = Replicas.map { r =>
        ReplicaId(r) -> (if r == id then "pekko.persistence.r2dbc.query"
                         else s"ankka.replica.$r.query")
      }.toMap
      ReplicatedEventSourcing.perReplicaJournalConfig(
        ReplicationId(TypeName, entityId, ReplicaId(id)),
        plugins
      ) { ctx =>
        EventSourcedBehavior[Cmd, Appended, Log](
          ctx.persistenceId,
          Log.empty,
          commandHandler = (state, cmd) =>
            cmd match
              case Append(text, replyTo) =>
                val position = state.nextPosition
                Effect.persist(Appended(position, id, text)).thenReply(replyTo)(_ => position)
              case Get(replyTo) => Effect.reply(replyTo)(state),
          eventHandler = (state, event) =>
            val mark    = s"${ctx.origin.id}${event.position}${if ctx.concurrent then "!" else ""}"
            val arrived = state.copy(arrived = state.arrived :+ event.position)
            if !buffered then arrived.copy(applied = arrived.applied :+ mark)
            else drain(arrived.copy(pending = arrived.pending :+ event.copy(text = mark)))
        )
      }

    /** Applies pending events whose position is the next one, until there is a gap. */
    private def drain(state: Log): Log =
      state.pending.find(_.position == state.nextPosition) match
        case Some(next) =>
          drain(
            state.copy(
              applied = state.applied :+ next.text,
              pending = state.pending.filterNot(_ eq next)
            )
          )
        case None => state

    def journalOrder(entityId: String): Vector[Int] =
      val journal =
        PersistenceQuery(system).readJournalFor[R2dbcReadJournal]("pekko.persistence.r2dbc.query")
      Await
        .result(
          journal
            .currentEventsByPersistenceId(pid(entityId), 0L, Long.MaxValue)
            .map(_.event.asInstanceOf[Appended].position)
            .runWith(Sink.seq),
          20.seconds
        )
        .toVector

    def hide(entityId: String, hidden: Boolean): Unit =
      Await.result(
        Database().execute(
          SqlFragment.raw(
            s"UPDATE event_journal SET deleted = $hidden WHERE persistence_id = '${pid(entityId)}'"
          )
        ),
        20.seconds
      ): Unit

    def stop(): Unit =
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds): Unit
      SharedPostgres.release(database)

  private def start(id: String, databases: Map[String, TestDatabase]): Replica =
    val own = databases(id)
    val siblings = databases.toSeq
      .filterNot(_._1 == id)
      .map { (sibling, db) =>
        s"""ankka.replica.$sibling {
           |  connection-factory = $${pekko.persistence.r2dbc.connection-factory} { database = "${db.name}" }
           |  query = $${pekko.persistence.r2dbc.query} {
           |    use-connection-factory = "ankka.replica.$sibling.connection-factory"
           |    refresh-interval = 250ms
           |  }
           |}""".stripMargin
      }
      .mkString("\n")
    val config: Config = ConfigFactory
      .parseString(
        s"""pekko.actor.provider = local
           |pekko.persistence.r2dbc.connection-factory {
           |  host = "${own.host}"
           |  port = ${own.port}
           |  database = "${own.name}"
           |  user = "ankka"
           |  password = "ankka"
           |}
           |pekko.persistence.r2dbc.refresh-interval = 250ms
           |$siblings
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()
    Replica(id, own, ActorSystem(Behaviors.empty, s"replica-$id", config))

  private given Timeout = Timeout(10.seconds)

  private def append(ref: ActorRef[Cmd], text: String)(using ActorSystem[?]): Int =
    Await.result(ref.ask[Int](Append(text, _)), 10.seconds)

  private def get(ref: ActorRef[Cmd])(using ActorSystem[?]): Log =
    Await.result(ref.ask[Log](Get(_)), 10.seconds)

  private def eventually(what: String, within: FiniteDuration)(check: => Boolean): Unit =
    val deadline = within.fromNow
    while !check do
      if deadline.isOverdue() then fail(s"timed out waiting for: $what")
      Thread.sleep(200)

  private def scenario(flavour: String, buffered: Boolean, replicas: Map[String, Replica]): Unit =
    val entityId = s"car-$flavour"
    val a        = replicas("A")
    val b        = replicas("B")
    val c        = replicas("C")

    // Act 1: A is home and writes positions 1 to 5.
    val atA = a.spawn(entityId, buffered)
    (1 to 5).foreach(i => append(atA, s"a$i")(using a.system))

    // Act 2: B catches up from A, becomes home, writes 6 to 8.
    val atB = b.spawn(entityId, buffered)
    eventually("B has A's five events", 30.seconds)(get(atB)(using b.system).applied.size == 5)
    (6 to 8).foreach(i => append(atB, s"b$i")(using b.system))

    // Act 3: A's journal lags for C. C joins late, reads B first, then A once unhidden.
    a.hide(entityId, hidden = true)
    val atC = c.spawn(entityId, buffered)
    eventually("C has B's three events", 30.seconds)(get(atC)(using c.system).arrived.size == 3)
    a.hide(entityId, hidden = false)
    eventually("C has all eight events", 30.seconds)(get(atC)(using c.system).arrived.size == 8)

    val logC    = get(atC)(using c.system)
    val logB    = get(atB)(using b.system)
    val inOrder = logC.applied == logB.applied
    println(s"SPIKE[$flavour] B applied   : ${logB.applied.mkString(" ")}")
    println(s"SPIKE[$flavour] C arrived   : ${logC.arrived.mkString(" ")}")
    println(s"SPIKE[$flavour] C applied   : ${logC.applied.mkString(" ")}")
    println(s"SPIKE[$flavour] C journal   : ${c.journalOrder(entityId).mkString(" ")}")
    println(s"SPIKE[$flavour] C pending   : ${logC.pending.size}")
    println(s"SPIKE[$flavour] C's state matches the home's: $inOrder")

  test("a late joiner applies two writers' events in arrival order") {
    val databases = Replicas.map(id => id -> SharedPostgres.acquire()).toMap
    val replicas  = Replicas.map(id => id -> start(id, databases)).toMap
    try
      scenario("naive", buffered = false, replicas)
      scenario("buffered", buffered = true, replicas)
    finally replicas.values.foreach(_.stop())
  }

object ReplicationOrderSpike:
  // The event: `position` is the canonical position the writer assigns, dense from 1.
  final case class Appended(position: Int, origin: String, text: String) extends AnkkaSerializable

  // Applied entries read `<origin><position>` with a `!` when Pekko flagged the event concurrent.
  final case class Log(arrived: Vector[Int], applied: Vector[String], pending: Vector[Appended]):
    def nextPosition: Int = applied.size + 1

  object Log:
    val empty: Log = Log(Vector.empty, Vector.empty, Vector.empty)

  sealed trait Cmd
  final case class Append(text: String, replyTo: ActorRef[Int]) extends Cmd
  final case class Get(replyTo: ActorRef[Log])                  extends Cmd
