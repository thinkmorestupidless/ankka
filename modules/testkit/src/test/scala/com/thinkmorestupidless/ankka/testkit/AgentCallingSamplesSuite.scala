package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*

import scala.concurrent.duration.*

/** The agents guide's samples of calling an agent, run against a whole service. */
class AgentCallingSamplesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model             = TestModelProvider()
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    val agents = AgentRuntime.withDefaultModel(model)
    kit = AnkkaTestKit.start(Seq(WeatherAgent.descriptor) ++ agents.descriptors, Seq(agents))

  override def afterAll(): Unit = if kit != null then kit.stop()

  test("an agent is called through the component client, addressed by session") {
    model.expectText("Lisbon is 18C and sunny.")
    val componentClient = kit.componentClient
    // docs:start call-agent
    val answer: String =
      componentClient.forAgent(SessionId("session-42")).call(WeatherAgent.ask).invoke("Lisbon")
    // docs:end call-agent
    assertEquals(answer, "Lisbon is 18C and sunny.")
  }

  test("a session about a person is started with the person's data subject") {
    model.expectText("Sorry to hear that.")
    val componentClient = kit.componentClient
    // docs:start agent-subject
    val reply = componentClient
      .forAgent(SessionId("support-91"), subject = Some(DataSubject("player/8c1f")))
      .call(WeatherAgent.ask)
      .invoke("my card was declined")
    // docs:end agent-subject
    assertEquals(reply, "Sorry to hear that.")
  }
