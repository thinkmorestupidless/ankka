package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.Serializer

import java.util.Base64

/**
 * Writes the wire fixtures the Python and TypeScript SDKs decode, in
 * `protocol/fixtures/autonomous/`.
 *
 * A directory of their own because `core`'s `EncodingFixturesSuite` owns the files directly in
 * `protocol/fixtures/` and refuses any it did not generate.
 *
 * The format is `protocol/ENCODING.md`'s: manifest, content type, the bytes, and the value as
 * language-neutral JSON — here the same JSON, since every one of these is a JSON record.
 */
class AutonomousFixturesSuite extends munit.FunSuite:

  private val usage = TokenUsage(120, 30)

  private def fixture[A](name: String, value: A, s: Serializer[A]): (java.nio.file.Path, String) =
    val bytes = s.toBytes(value)
    val text  = String(bytes, "UTF-8")
    Fixtures.repositoryRoot.resolve(s"protocol/fixtures/autonomous/$name.json") ->
      s"""{
  "manifest": "${s.manifest}",
  "content_type": "application/json",
  "bytes_base64": "${Base64.getEncoder.encodeToString(bytes)}",
  "value": $text
}
"""

  test("protocol/fixtures/autonomous holds the autonomous agent's wire forms") {
    val files = Map(
      fixture(
        "task-created",
        TaskEvent.Created("t-1", "answer", "How many red items?", Vector.empty, Vector("t-0"), 1L),
        TaskEntity.eventSerializer
      ),
      fixture(
        "task-completed",
        TaskEvent.Completed("""{"answer":"3","sources":["count_items"]}""", 2, usage, 2L),
        TaskEntity.eventSerializer
      ),
      fixture(
        "agent-instance-iteration-completed",
        InstanceEvent.IterationCompleted(2, usage, 3L),
        InstanceEntity.eventSerializer
      ),
      fixture(
        "agent-notification-task-result-rejected",
        Notification
          .TaskResultRejected("answerer", "i-1", "t-1", "sources must not be empty", 2, 4L),
        Notification.serializer
      ),
      fixture(
        "agent-state",
        AgentState(
          "answerer",
          "i-1",
          Phase.Working,
          suspended = false,
          terminated = false,
          Some(AgentState.Current("t-1", 2, Some(5))),
          Vector("t-2"),
          usage,
          usage
        ),
        AgentState.serializer
      )
    )
    val problems = Fixtures.check(files)
    assert(problems.isEmpty, problems.mkString("\n"))
  }
