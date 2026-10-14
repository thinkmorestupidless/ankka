package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*

/** Two installations, one vehicle: the shortest path through replication, end to end. */
class ReplicationSmokeSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  test("a follower mirrors its home and answers a read; a write there is forwarded") {
    // docs:start several-installations
    val fleet = AnkkaTestKit.installations("smoke-a", "smoke-b")
    try
      val a       = fleet.start("smoke-a", Seq(Fleet.vehicles.descriptor))
      val b       = fleet.start("smoke-b", Seq(Fleet.vehicles.descriptor))
      val atA     = a.componentClient.forEventSourcedEntity(EntityId("v1"))
      val atB     = b.componentClient.forEventSourcedEntity(EntityId("v1"))
      val created = atA.call(Fleet.vehicles.createVehicle).invoke(CreateVehicle(Vector("smoke-b")))
      assertEquals(created.installation, Some("smoke-a"))
      (1 to 3).foreach(n => atA.call(Fleet.vehicles.addStep).invoke(s"step-$n"))
      val mirrored = AnkkaTestKit.eventually("smoke-b holds the three steps", 20.seconds) {
        val v = atB.call(Fleet.vehicles.get).invoke()
        Option.when(v.steps.size == 3)(v)
      }
      assertEquals(mirrored.writtenAt.distinct, Vector(Some("smoke-a")))
      assertEquals(mirrored.local.distinct, Vector(false))
      val forwarded = atB.call(Fleet.vehicles.recordInspection).invoke("brakes")
      assertEquals(forwarded.installation, Some("smoke-a"))
      val status = atB.replication(Fleet.vehicles)
      assertEquals(status.home, Some("smoke-a"))
      assertEquals(status.replicationSet, Set("smoke-a", "smoke-b"))
    finally fleet.stopAll()
    // docs:end several-installations
  }

  test("the topology says which entity is replicated, and says nothing of any other") {
    val kit =
      AnkkaTestKit.start(Seq(Fleet.vehicles.descriptor, VehicleKind("order", false).descriptor))
    try
      val topology = com.thinkmorestupidless.ankka.runtime.TopologyJson
        .of(kit.service, "fleet", "fleet-1", "2026-10-12T00:00:00Z")
      assert(
        topology.contains("\"id\":\"vehicle\",\"kind\":\"EventSourcedEntity\"") &&
          "\"replicated\":true".r.findAllIn(topology).size == 1,
        topology
      )
      val order = topology.indexOf("\"id\":\"order\"")
      assert(order >= 0 && !topology.substring(order).takeWhile(_ != '}').contains("replicated"))
    finally kit.stop()
  }
