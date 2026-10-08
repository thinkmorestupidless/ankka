package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures

/**
 * Pins the journal form of every blueprint event and of the record a reader gets.
 *
 * `blueprint-events.json` holds one line per case. A field renamed, reordered or given no default
 * changes a line, and the change is then a decision rather than an accident: a journal written by
 * one release has to replay under the next.
 */
class BlueprintCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val blueprint =
    Blueprint("digest")
      .worker(Worker("reader").instructions("Say what this paper finds.").budget(3))
      .step(Step("findings").ask("reader").reads("input"))

  private val events: Vector[BlueprintEvent] =
    import BlueprintEvent.*
    Vector(
      VersionRegistered(1, blueprint.canonical, blueprint.digest, 1L),
      ScheduleAdvanced(2L, Some(3L)),
      ScheduleStopped(4L)
    )

  private def lines[A](label: String, values: Vector[A], s: Serializer[A]): Vector[String] =
    values.map(v => s"$label ${String(s.toBytes(v), "UTF-8")}")

  private val path =
    Fixtures.repositoryRoot.resolve(
      "modules/testkit/src/test/resources/journal/blueprint-events.json"
    )

  test("every blueprint event decodes from its pinned form and back") {
    val generated =
      lines("blueprint-event", events, BlueprintEntity.eventSerializer).mkString("", "\n", "\n")
    events.foreach(e =>
      assertEquals(
        BlueprintEntity.eventSerializer.fromBytes(BlueprintEntity.eventSerializer.toBytes(e)),
        e
      )
    )
    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("a task created with a definition of its own is pinned, and one without reads with none") {
    import com.thinkmorestupidless.ankka.agent.autonomous.*
    val definition = TaskDefinition(
      "Say what this paper finds.",
      "default",
      Vector("search"),
      Vector.empty,
      3,
      Shape.obj("text" -> Shape.string).schema,
      Map("run" -> "r-1", "step" -> "findings", "blueprint" -> "digest", "version" -> "1")
    )
    val created = TaskEvent.Created(
      "t-1",
      "blueprint-step",
      "Step 'findings'.",
      Vector.empty,
      Vector.empty,
      1L,
      Some(definition)
    )
    val generated = s"task-event ${String(TaskEntity.eventSerializer.toBytes(created), "UTF-8")}\n"
    assertEquals(
      TaskEntity.eventSerializer.fromBytes(TaskEntity.eventSerializer.toBytes(created)),
      created
    )
    val problems = Fixtures.check(
      Map(
        Fixtures.repositoryRoot.resolve(
          "modules/testkit/src/test/resources/journal/task-created-with-definition.json"
        ) -> generated
      )
    )
    assert(problems.isEmpty, problems.mkString("\n"))
    // A task journal written before definitions reads with none.
    val before =
      """{"type":"Created","id":"t-0","typeName":"answer","instructions":"How many?","attachments":[],"dependencies":[],"at":1}"""
    assertEquals(
      TaskEntity.eventSerializer.fromBytes(before.getBytes("UTF-8")),
      TaskEvent.Created("t-0", "answer", "How many?", Vector.empty, Vector.empty, 1L)
    )
  }

  test("every event case is pinned") {
    assertEquals(events.map(_.ordinal).distinct.sorted, events.indices.toVector)
    assertEquals(BlueprintEvent.ScheduleStopped(0L).ordinal, events.map(_.ordinal).max)
  }

  test("a record round-trips with every version it holds") {
    val record = BlueprintRecord(
      "digest",
      Vector(VersionRecord(1, blueprint.canonical, blueprint.digest, 1L)),
      Some(2L),
      Some(3L)
    )
    assertEquals(
      BlueprintEntity.stateSerializer.fromBytes(BlueprintEntity.stateSerializer.toBytes(record)),
      record
    )
  }
