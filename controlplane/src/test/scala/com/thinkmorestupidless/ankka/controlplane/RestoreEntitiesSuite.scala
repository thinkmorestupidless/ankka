package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.{ProjectEntity, ServiceEntity}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.time.Instant

/**
 * A restore on the project and a switch on the service (feature 041): what each records, what each
 * refuses, and that a journal from before them still replays.
 */
class RestoreEntitiesSuite extends munit.FunSuite with LogCapturing:

  private val moment = Instant.parse("2026-10-08T09:20:00Z")

  private def project =
    val kit = EventSourcedTestKit.of(ProjectEntity, "shop")
    kit.call(ProjectEntity.createProject)(CreateProject("Shop", "acme")): Unit
    kit

  private def restore(name: String) = RequestRestore(name, "ankka-db", moment)

  test("a restore is recorded with its line and moment, and in the project's history") {
    val kit    = project
    val result = kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1"))
    assertEquals(
      result.events,
      Vector(ProjectRestoreRequested("ankka-db-r1", "ankka-db", moment, None, None))
    )
    assertEquals(kit.currentState.restoreInProgress, Some("ankka-db-r1"))
    assertEquals(kit.currentState.history.map(_.kind), Vector("restore-requested"))
  }

  test("a second restore is refused while one has not ended, naming it") {
    val kit = project
    kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1")): Unit
    val second = kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r2"))
    assert(second.isError)
    assertEquals(second.error.code, ErrorCode.Conflict)
    assert(second.errorMessage.contains("in progress: ankka-db-r1"), second.errorMessage)
  }

  test("a restore ends once: verified with the moment it reached, and a repeat records nothing") {
    val kit = project
    kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1")): Unit
    val reached = Instant.parse("2026-10-08T09:19:58Z")
    val ended = kit.call(ProjectEntity.observeRestore)(
      ObserveRestore("ankka-db-r1", "Verified", Some(reached))
    )
    assertEquals(ended.events.size, 1)
    assertEquals(kit.currentState.restoreInProgress, None)
    assertEquals(
      kit.currentState.restores("ankka-db-r1").outcome.flatMap(_.reachedAt),
      Some(reached)
    )
    val again = kit.call(ProjectEntity.observeRestore)(ObserveRestore("ankka-db-r1", "InUse"))
    assertEquals(again.events, Vector.empty)
    assertEquals(
      kit.currentState.history.map(_.kind),
      Vector("restore-requested", "restore-completed")
    )
    // With it ended, another may be asked for.
    assert(!kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r2")).isError)
  }

  test("a restore that failed ends failed, with why; one still restoring records nothing") {
    val kit = project
    kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1")): Unit
    assertEquals(
      kit.call(ProjectEntity.observeRestore)(ObserveRestore("ankka-db-r1", "Restoring")).events,
      Vector.empty
    )
    kit.call(ProjectEntity.observeRestore)(
      ObserveRestore("ankka-db-r1", "Failed", detail = Some("no base backup"))
    ): Unit
    val outcome = kit.currentState.restores("ankka-db-r1").outcome.getOrElse(fail("no outcome"))
    assert(!outcome.succeeded)
    assertEquals(outcome.detail, Some("no base backup"))
  }

  test("a restore's name is never used twice") {
    val kit = project
    kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1")): Unit
    kit.call(ProjectEntity.observeRestore)(ObserveRestore("ankka-db-r1", "Failed")): Unit
    val again = kit.call(ProjectEntity.requestRestore)(restore("ankka-db-r1"))
    assertEquals(again.error.code, ErrorCode.Conflict)
  }

  // ── The switch ────────────────────────────────────────────────────────────

  private def service =
    val kit = EventSourcedTestKit.of(ServiceEntity, "shop/rewards")
    kit.call(ServiceEntity.applyDescriptor)(
      ApplyService("shop", ServiceDescriptor("rewards", ServiceSpec("rewards:1")))
    ): Unit
    kit

  test(
    "a switch is a new generation, so the service rolls onto the cluster, and is in its history"
  ) {
    val kit    = service
    val result = kit.call(ServiceEntity.switchDatabase)(SwitchDatabase(Some("ankka-db-r1")))
    assertEquals(result.events, Vector(ServiceSwitched(Some("ankka-db-r1"), 2L, None, None)))
    assertEquals(result.replyValue.databaseCluster, Some("ankka-db-r1"))
    assertEquals(result.replyValue.generation, 2L)
    val entry = kit.currentState.history.head
    assertEquals(entry.kind, "switched")
    assertEquals(entry.detail, Some("from ankka-db to ankka-db-r1"))
  }

  test("switching back is the same action, to the project database") {
    val kit = service
    kit.call(ServiceEntity.switchDatabase)(SwitchDatabase(Some("ankka-db-r1"))): Unit
    val back = kit.call(ServiceEntity.switchDatabase)(SwitchDatabase(Some("ankka-db")))
    assertEquals(back.replyValue.databaseCluster, None)
    assertEquals(kit.currentState.history.head.detail, Some("from ankka-db-r1 to ankka-db"))
  }

  test("switching to the cluster the service is on records nothing") {
    val kit = service
    assertEquals(kit.call(ServiceEntity.switchDatabase)(SwitchDatabase(None)).events, Vector.empty)
  }

  test("the resource names the cluster a switched service is on, and nothing for one that is not") {
    val kit = service
    def projected = com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjection
      .project(
        kit.currentState,
        com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig.default
      )
      .getOrElse(fail("not projected"))
      .databaseCluster
    assertEquals(projected, None)
    kit.call(ServiceEntity.switchDatabase)(SwitchDatabase(Some("ankka-db-r1"))): Unit
    assertEquals(projected, Some("ankka-db-r1"))
  }

  // ── A journal from before ─────────────────────────────────────────────────

  test("a project's state from before restores decodes with none, and the events round-trip") {
    val old =
      """{"id":"shop","name":"Shop","organizationId":"acme","deleted":false}""".getBytes("UTF-8")
    val state = ProjectEntity.stateSerializer.fromBytes(old)
    assertEquals(state.restores, Map.empty)
    assertEquals(state.history, Vector.empty)
    for event <- Vector[ProjectEvent](
        ProjectRestoreRequested("ankka-db-r1", "ankka-db", moment),
        ProjectRestoreEnded("ankka-db-r1", succeeded = false, detail = Some("why")),
        ProjectDatabaseSet(DatabaseSetting(2, synchronous = true, Some(45), Some("daily"))),
        ProjectRehearsalRequested("ankka-db-x1", "ankka-db", moment),
        ProjectRehearsalEnded("ankka-db-x1", "ankka-db", moment, "Completed", Some(118L))
      )
    do
      val serializer = ProjectEntity.eventSerializer
      assertEquals(serializer.fromBytes(serializer.toBytes(event)), event)
  }

  test("a service from before the switch is on the project database") {
    val kit   = service
    val bytes = ServiceEntity.stateSerializer.toBytes(kit.currentState)
    val json  = new String(bytes, "UTF-8")
    assert(!json.contains("databaseCluster"), json)
    assertEquals(ServiceEntity.stateSerializer.fromBytes(bytes).databaseCluster, None)
  }

  test("a project's database setting is recorded whole, once, and in its history") {
    val kit     = project
    val setting = DatabaseSetting(replicas = 2, synchronous = true)
    val set     = kit.call(ProjectEntity.setDatabase)(SetDatabase(setting))
    assertEquals(set.events.size, 1)
    assertEquals(kit.call(ProjectEntity.database).replyValue, setting)
    assertEquals(kit.call(ProjectEntity.setDatabase)(SetDatabase(setting)).events, Vector.empty)
    assertEquals(kit.currentState.history.last.detail, Some("2 replicas, synchronous"))
  }

  // ── Rehearsals ────────────────────────────────────────────────────────────

  private def rehearse(name: String) = RequestRehearsal(name, "ankka-db", moment)

  test("a rehearsal is recorded, a second refused while it runs, and its end recorded once") {
    val kit = project
    assertEquals(kit.call(ProjectEntity.requestRehearsal)(rehearse("ankka-db-x1")).events.size, 1)
    val second = kit.call(ProjectEntity.requestRehearsal)(rehearse("ankka-db-x2"))
    assertEquals(second.error.code, ErrorCode.Conflict)
    val ended = ObserveRehearsal("ankka-db-x1", "ankka-db", moment, "Completed", Some(118L))
    assertEquals(kit.call(ProjectEntity.observeRehearsal)(ended).events.size, 1)
    assertEquals(kit.call(ProjectEntity.observeRehearsal)(ended).events, Vector.empty)
    val kept = kit.currentState.rehearsals("ankka-db-x1")
    assertEquals(kept.outcome.flatMap(_.elapsedSeconds), Some(118L))
    assertEquals(
      kit.currentState.history.map(_.kind).takeRight(2),
      Vector("rehearsal-requested", "rehearsal-completed")
    )
  }

  test("a rehearsal the schedule started is recorded whole by its end, with no one asking") {
    val kit     = project
    val running = ObserveRehearsal("ankka-db-x9", "ankka-db", moment, "Running")
    assertEquals(kit.call(ProjectEntity.observeRehearsal)(running).events, Vector.empty)
    kit.call(ProjectEntity.observeRehearsal)(
      running.copy(outcome = "Failed", detail = Some("why"))
    ): Unit
    val kept = kit.currentState.rehearsals("ankka-db-x9")
    assertEquals(kept.requestedBy, None)
    assertEquals(kept.outcome.map(_.outcome), Some("Failed"))
  }
