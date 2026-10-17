package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.discovery.AutonomousAgentDetail
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * An autonomous agent defined in a process names a model provider the sidecar holds, as a plan
 * does.
 */
class RemoteAutonomousModelsSuite extends munit.FunSuite with LogCapturing:
  import ProcessDouble.*

  override val munitTimeout = 3.minutes

  given ExecutionContext = ExecutionContext.global

  private val answerType =
    AutonomousAgentDetail.TaskType(name = "answer", description = "Answer a question")

  private val operator = AutonomousOf(
    "operator",
    "Answers questions",
    Vector(answerType),
    Vector("answer" -> 4),
    model = Some("openai")
  )

  private val anthropic = TestModelProvider("claude-opus-5", name = "anthropic")
  private val openai    = TestModelProvider("gpt-6.1-sol", name = "openai")

  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized

  private val answer: TaskType[Json] =
    TaskType.json("answer", "Answer a question", Json.obj(), Vector.empty)

  override def beforeAll(): Unit =
    double = new ProcessDouble(DoubleSpec(autonomous = Vector(operator)))
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered = Discovery
      .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
      .fold(p => fail(p.mkString("; ")), identity)
    val models = Models(Map("anthropic" -> anthropic, "openai" -> openai), Some(anthropic))
    val agents = discovered.autonomousAgents.map(c =>
      RemoteAutonomousAgent.descriptor(c, conversation, models, 5.seconds)
    )
    kit = AnkkaTestKit.start(
      agents ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(anthropic), ProjectionRuntime()),
      60.seconds,
      _.withConversation(conversation)
    )

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  test(
    "an autonomous agent of a service written in another language names the model provider openai"
  ) {
    openai.expectToolCall("lookup", Json.obj()).expectCompleteTaskText("three")
    val id = kit.componentClient.tasks.create(answer, "How many?").create()
    kit.componentClient
      .forAutonomousAgent(ComponentId("operator"), java.util.UUID.randomUUID().toString)
      .assign(id): Unit
    val done = kit.awaitTask(id, answer)
    assertEquals(done.status, TaskStatus.Completed, done.toString)
    assert(openai.callCount >= 1, "every iteration is a call to openai")
    assertEquals(anthropic.callCount, 0, "the default was never asked")
  }
