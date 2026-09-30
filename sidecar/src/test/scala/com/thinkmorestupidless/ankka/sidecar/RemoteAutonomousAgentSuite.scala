package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.agent.{TaskResultRequest, ToolRequest}
import ankka.protocol.v1.discovery.AutonomousAgentDetail
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The sidecar running an autonomous agent whose tools, guardrails and task rules live in the
 * process: the loop, the records and the model are the sidecar's; the double is asked to run a
 * tool, check a guardrail or check a rule, each naming the task's session.
 */
class RemoteAutonomousAgentSuite extends munit.FunSuite with LogCapturing:
  import ProcessDouble.*

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: Duration = 5.minutes

  private val answerType = AutonomousAgentDetail.TaskType(
    name = "answer",
    description = "Answer a question",
    resultSchemaJson = Some(
      """{"type":"object","properties":{"answer":{"type":"string"},"sources":{"type":"array","items":{"type":"string"}}},"required":["answer","sources"]}"""
    ),
    rules = Vector("cites-sources")
  )

  private val answerer = AutonomousOf(
    "answerer",
    "Answers questions",
    Vector(answerType),
    Vector("answer" -> 4),
    tools = Map("lookup" -> ((_, arguments) => Right(s"looked up $arguments"))),
    rules = Map(
      "cites-sources" -> (json =>
        if json.contains("\"sources\":[]") then Left("sources must not be empty") else Right(())
      )
    ),
    malformed = json => Option.when(!json.contains("\"answer\":\""))("answer must be a string")
  )

  private val model                   = TestModelProvider()
  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized

  /** The task type as the sidecar knows it, for reading results back. */
  private val answer: TaskType[Json] =
    TaskType.json("answer", "Answer a question", Json.obj(), Vector.empty)

  override def beforeAll(): Unit =
    double = new ProcessDouble(DoubleSpec(autonomous = Vector(answerer)))
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered   = Discovery.validate(double.toSpec).fold(p => fail(p.mkString("; ")), identity)
    val models       = Models.only(Models.Scripted, model)
    val agents = discovered.autonomousAgents.map(c =>
      RemoteAutonomousAgent.descriptor(c, conversation, models, 5.seconds)
    )
    kit = AnkkaTestKit.start(
      agents ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime()),
      60.seconds,
      _.withConversation(conversation)
    )

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    double.received.clear()

  private def run(instructions: String): String =
    val id       = kit.componentClient.tasks.create(answer, instructions).create()
    val instance = java.util.UUID.randomUUID().toString
    kit.componentClient.forAutonomousAgent(ComponentId("answerer"), instance).assign(id): Unit
    id

  private def received[A](cls: Class[A]): Vector[A] =
    double.received.asScala
      .map(_.message)
      .collect { case m if cls.isInstance(m) => cls.cast(m) }
      .toVector

  test("a task runs to its result with the tool run in the process, naming the task's session") {
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("red")))
      .expectCompleteTaskJson("""{"answer":"3","sources":["lookup"]}""")
    val id   = run("How many red things?")
    val done = kit.awaitTask(id, answer)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.record.result, Some("""{"answer":"3","sources":["lookup"]}"""))
    val tools = received(classOf[ToolRequest])
    assertEquals(tools.map(t => (t.tool, t.sessionId)), Vector("lookup" -> s"task:$id"))
    val checks = received(classOf[TaskResultRequest])
    assertEquals(checks.map(r => (r.taskId, r.taskType)), Vector((id, "answer")))
  }

  test("the process's rule rejects a result, the model sees why, and the second attempt stands") {
    model
      .expectCompleteTaskJson("""{"answer":"3","sources":[]}""")
      .expectCompleteTaskJson("""{"answer":"3","sources":["memory"]}""")
    val id   = run("How many?")
    val done = kit.awaitTask(id, answer)
    assertEquals(done.record.result, Some("""{"answer":"3","sources":["memory"]}"""))
    assertEquals(done.record.iterations, 2)
    val second = model.requests(1).messages.toString
    assert(second.contains("sources must not be empty"), second)
  }

  test("a rule the process fails to check is tried again, not taken as a rejection") {
    double.knobs.failRules.set(1)
    model.expectCompleteTaskJson("""{"answer":"3","sources":["memory"]}""")
    val done = kit.awaitTask(run("How many?"), answer)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(model.callCount, 1)
    assertEquals(received(classOf[TaskResultRequest]).size, 2)
  }

  test(
    "a result the process cannot decode goes back to the model as a tool error, not a rejection"
  ) {
    model
      .expectCompleteTaskJson("""{"answer":1,"sources":["x"]}""")
      .expectCompleteTaskJson("""{"answer":"one","sources":["x"]}""")
    val done = kit.awaitTask(run("Say one"), answer)
    assertEquals(done.status, TaskStatus.Completed)
    val second = model.requests(1).messages.toString
    assert(second.contains("does not match the task's shape: answer must be a string"), second)
  }

  test(
    "discovery refuses a tool named like a built-in, an unknown accepted type and a zero budget"
  ) {
    val bad = answerer.copy(
      tools = Map("complete_task" -> ((_, _) => Right("x"))),
      accepts = Vector("answer" -> 0, "missing" -> 3)
    )
    val problems = Discovery
      .validate(new ProcessDouble(DoubleSpec(autonomous = Vector(bad))).toSpec)
      .left
      .getOrElse(fail("a bad definition was admitted"))
      .mkString("\n")
    assert(problems.contains("tool name 'complete_task' is reserved"), problems)
    assert(problems.contains("'missing', which it does not declare"), problems)
    assert(problems.contains("at least one iteration"), problems)
  }
