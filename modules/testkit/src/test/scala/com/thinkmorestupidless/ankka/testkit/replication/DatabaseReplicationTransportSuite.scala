package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.JournalRecord
import com.thinkmorestupidless.ankka.runtime.replication.{
  DatabaseReplicationTransport,
  ForwardedCall,
  InstallationDatabase,
  Located,
  Replicated,
  ReplicationSettings
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The runtime's own replication transport against real journals (T017): an installation's journal
 * read from another, in order from a position; where an entity lives, from a replicated journal and
 * from one written before replication; a read past the end that waits for the next record; and an
 * installation it cannot reach.
 *
 * It lives in the test kit's tests, not the runtime's: the runtime's tests have no database, and a
 * journal written by a real service is the one worth reading. Each journal is written by a service
 * of its own; the transport under test reads it as another installation would, from its database.
 */
class DatabaseReplicationTransportSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val vehicles = Fleet.vehicles
  private val poll     = 300.millis

  /** A transport at installation `reader` that reads `writer`'s journal from `kit`'s database. */
  private def transportOver(writer: String, kit: AnkkaTestKit)(using
      ActorSystem[?]
  ): DatabaseReplicationTransport =
    val db = kit.database
    DatabaseReplicationTransport(
      ReplicationSettings(
        installation = Some("reader"),
        installations = Set(writer),
        poll = poll,
        holdWrites = false,
        handoverTimeout = 30.seconds,
        databases = Map(writer -> InstallationDatabase(db.host, db.port, db.name, "ankka", "ankka"))
      ),
      Set(vehicles.componentId)
    )

  private def await[A](f: scala.concurrent.Future[A], within: FiniteDuration = 10.seconds): A =
    Await.result(f, within)

  // A service that names no installations writes a journal as every service did before replication.
  private val plain = FunFixture[AnkkaTestKit](
    _ => AnkkaTestKit.start(Seq(vehicles.descriptor)),
    _.stop()
  )

  plain.test("records are read from another installation's database in order, from a position") {
    kit =>
      given ActorSystem[?] = kit.service.system
      val at               = kit.componentClient.forEventSourcedEntity(EntityId("r1"))
      at.call(vehicles.createVehicle).invoke(CreateVehicle())
      (1 to 4).foreach(n => at.call(vehicles.addStep).invoke(s"step-$n"))
      val transport = transportOver("writer", kit)
      try
        val read = await(
          transport
            .records("writer", vehicles.componentId, EntityId("r1"), from = 3)
            .take(3)
            .runWith(Sink.seq)
        )
        assertEquals(read.map(_.sequenceNr), Vector(3L, 4L, 5L))
        assert(read.forall(_.record.kind == JournalRecord.KindDomain), read.toString)
        // The domain events, untouched: the second step is the third record.
        assert(String(read.head.record.payload, "UTF-8").contains("step-2"), read.head.toString)
      finally transport.stop()
  }

  plain.test("a journal from before replication is located at the installation that holds it") {
    kit =>
      given ActorSystem[?] = kit.service.system
      kit.componentClient
        .forEventSourcedEntity(EntityId("old"))
        .call(vehicles.createVehicle)
        .invoke(CreateVehicle())
      val transport = transportOver("writer", kit)
      try
        assertEquals(
          await(transport.locate(vehicles.componentId, EntityId("old"))),
          Some(Located("writer", Set("writer")))
        )
        assertEquals(await(transport.locate(vehicles.componentId, EntityId("nobody"))), None)
      finally transport.stop()
  }

  plain.test("a read past the end stays open and gives the next record within two polls") { kit =>
    given ActorSystem[?] = kit.service.system
    val at               = kit.componentClient.forEventSourcedEntity(EntityId("r2"))
    at.call(vehicles.createVehicle).invoke(CreateVehicle())
    val transport = transportOver("writer", kit)
    try
      val next = transport
        .records("writer", vehicles.componentId, EntityId("r2"), from = 2)
        .runWith(Sink.head[Replicated])
      Thread.sleep((poll * 2).toMillis)
      assert(!next.isCompleted, s"a read past the end answered before any write: ${next.value}")
      val written = System.nanoTime()
      at.call(vehicles.addStep).invoke("late")
      val record = await(next)
      val took   = (System.nanoTime() - written).nanos
      assertEquals(record.sequenceNr, 2L)
      assert(took < poll * 2 + 500.millis, s"took $took for a poll of $poll")
    finally transport.stop()
  }

  plain.test("an installation it cannot reach fails a forward and is then not reachable") { kit =>
    given ActorSystem[?] = kit.service.system
    val transport        = transportOver("writer", kit)
    try
      assert(transport.reachable("nowhere"))
      val forwarded = transport.forward(
        "nowhere",
        vehicles.componentId,
        EntityId("r3"),
        ForwardedCall(MethodName("get"), Array.emptyByteArray, Metadata.empty)
      )
      intercept[IllegalStateException](await(forwarded))
      assert(!transport.reachable("nowhere"))
      // A database the transport was not given cannot be read either.
      intercept[IllegalStateException](
        await(
          transport.records("nowhere", vehicles.componentId, EntityId("r3"), 1).runWith(Sink.head)
        )
      )
    finally transport.stop()
  }

  test("a replicated journal is located at the home its first records name") {
    val fleet = AnkkaTestKit.installations("dbt-home", "dbt-other")
    try
      val home             = fleet.start("dbt-home", Seq(vehicles.descriptor))
      given ActorSystem[?] = home.service.system
      home.componentClient
        .forEventSourcedEntity(EntityId("v1"))
        .call(vehicles.createVehicle)
        .invoke(CreateVehicle(Vector("dbt-other")))
      val transport = transportOver("dbt-home", home)
      try
        val first = await(
          transport
            .records("dbt-home", vehicles.componentId, EntityId("v1"), 1)
            .take(1)
            .runWith(Sink.head)
        )
        assertEquals(first.record.kind, JournalRecord.KindHome)
        assertEquals(
          await(transport.locate(vehicles.componentId, EntityId("v1"))),
          Some(Located("dbt-home", Set("dbt-home", "dbt-other")))
        )
      finally transport.stop()
    finally fleet.stopAll()
  }
