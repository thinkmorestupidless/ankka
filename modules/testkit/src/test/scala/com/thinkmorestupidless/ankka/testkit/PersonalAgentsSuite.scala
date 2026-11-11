package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.{DataSubject, SessionId}
import com.thinkmorestupidless.ankka.testkit.autonomous.Operator

import scala.concurrent.duration.DurationInt

/**
 * The personal data page's agents sample: a session and an instance started with a data subject.
 */
class PersonalAgentsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private val model             = TestModelProvider()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(WeatherAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  test("a session started with a data subject keeps it, and answers as any session does") {
    model.expectText("Sorry to hear that.")
    val componentClient = kit.componentClient
    // docs:start agent-subject
    val calls =
      componentClient.forAgent(SessionId("support-91"), subject = Some(DataSubject("player/8c1f")))
    calls.call(WeatherAgent.ask).invoke("my card was declined")

    componentClient.forAutonomousAgent(
      Operator,
      "case-91",
      subject = Some(DataSubject("player/8c1f"))
    )
    // docs:end agent-subject
    val history =
      componentClient
        .forSessionMemory(SessionId("support-91"))
        .call(SessionMemoryEntity.history)
        .invoke()
    assertEquals(history.subject, Some("player/8c1f"))
  }
