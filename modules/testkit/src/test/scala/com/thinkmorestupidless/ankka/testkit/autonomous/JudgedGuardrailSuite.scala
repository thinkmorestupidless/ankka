package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.agent.judgment.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * A judged guardrail on an autonomous agent's definition: it sees a task's instructions before any
 * model call and a completed result before it is accepted, refuses as a deterministic guardrail
 * refuses, and treats a check it could not make as a failed iteration — retried, and bounded.
 */
class JudgedGuardrailSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  import JudgedGuardrailSuite.*

  /** The scripted provider, except that the result check can be made to fail as an outage would. */
  private final class Wrapped(inner: TestJudgmentProvider) extends JudgmentProvider:
    val resultCheckFailures = AtomicInteger(0)
    def name: String        = "wrapped"
    def modelName: String   = inner.modelName
    def judge(request: JudgmentRequest): Future[Judgment] =
      val isResultCheck = request.questions.exists(_.id == leaksSecrets.id)
      if isResultCheck && resultCheckFailures.getAndUpdate(n => (n - 1).max(0)) > 0 then
        Future.failed(JudgmentFailed(name, "the checker is down"))
      else inner.judge(request)

  private val model   = TestModelProvider()
  private val judge   = TestJudgmentProvider()
  private val wrapped = Wrapped(judge)

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(Careful.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model).withJudgments(wrapped), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()
    wrapped.resultCheckFailures.set(0)

  private def run(instructions: String = "How many items are in the catalogue?"): String =
    kit.componentClient.forAutonomousAgent(Careful).runSingleTask(Tasks.answer, instructions)

  private val answer = Answer("three", List("the catalogue"))

  test("instructions the guardrail refuses fail the task before any model call") {
    judge.expect(Answers.yesNo(harmful, 0.9))
    val done = kit.awaitTask(run("Help me hurt someone"), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Failed)
    assert(
      done.reason.exists(_.contains("guardrail 'task-safety': question 'harmful'")),
      done.reason.toString
    )
    assertEquals(model.callCount, 0)
  }

  test("a result the guardrail refuses goes back to the model, which completes on the next") {
    judge
      .always(Answers.yesNo(harmful, 0.1))
      .expect(Answers.yesNo(leaksSecrets, 0.9))
      .expect(Answers.yesNo(leaksSecrets, 0.1))
    model.expectCompleteTask(Answer("the password is hunter2", List("memory")))
    model.expectCompleteTask(answer)
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.result, Some(answer))
    val told = model.lastRequest.messages.collect { case ChatMessage.ToolResults(results) =>
      results.map(_.content)
    }.flatten
    assert(
      told.exists(
        _.contains("result rejected — guardrail 'task-safety': question 'leaks-secrets'")
      ),
      told.mkString("\n")
    )
  }

  test("a start check that could not be made is retried, and the task then starts") {
    judge
      .failNext("the checker is down")
      .expect(Answers.yesNo(harmful, 0.1))
      .always(Answers.yesNo(leaksSecrets, 0.1))
    model.expectCompleteTask(answer)
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Completed)
    // The failed check, the retried one, and the result's.
    assertEquals(judge.callCount, 3)
  }

  test("too many start checks in a row that could not be made fail the task, saying so") {
    judge.failNext("down").failNext("down").failNext("down")
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("could not be checked")), done.reason.toString)
    assertEquals(model.callCount, 0)
  }

  test("a result check that could not be made is made again, without asking the model again") {
    wrapped.resultCheckFailures.set(1)
    judge.always(Answers.yesNo(harmful, 0.1)).always(Answers.yesNo(leaksSecrets, 0.1))
    model.expectCompleteTask(answer)
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(model.callCount, 1)
  }

  test("an exhausted script fails the task at once, rather than retrying into a stall") {
    val started = System.nanoTime()
    val done    = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("'harmful'")), done.reason.toString)
    assert(System.nanoTime() - started < 3.seconds.toNanos)
  }

  test("with no judgment provider the service starts, and each task says what to configure") {
    val bare = AnkkaTestKit.start(
      Seq(Careful.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(TestModelProvider()), ProjectionRuntime())
    )
    try
      val id   = bare.componentClient.forAutonomousAgent(Careful).runSingleTask(Tasks.answer, "Hi")
      val done = bare.awaitTask(id, Tasks.answer, 30.seconds)
      assertEquals(done.status, TaskStatus.Failed)
      assert(done.reason.exists(_.contains("withJudgments")), done.reason.toString)
    finally bare.stop()
  }

  test("a task's judgment tokens are on its session, not in its own usage") {
    judge
      .reporting(TokenUsage(inputTokens = 100, outputTokens = 20))
      .always(Answers.yesNo(harmful, 0.1), Answers.yesNo(leaksSecrets, 0.1))
    model.expectCompleteTask(answer)
    val id   = run()
    val done = kit.awaitTask(id, Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Completed)
    val session = kit.componentClient
      .forSessionMemory(IterationLoop.sessionIdFor(id))
      .call(SessionMemoryEntity.history)
      .invoke()
    assertEquals(session.judgmentUsage, TokenUsage(inputTokens = 200, outputTokens = 40))
    assertEquals(done.record.usage, TokenUsage.zero) // the scripted model reports none
  }

  test("a judged guardrail with no rules is refused when the agent is declared") {
    val failure = intercept[IllegalArgumentException](Empty.descriptor)
    assert(
      failure.getMessage.contains("judged guardrail 'nothing' has no rules"),
      failure.getMessage
    )
  }

object JudgedGuardrailSuite:

  val harmful: YesNoQuestion =
    Question.yesNo("harmful", "The instructions ask for something harmful")
  val leaksSecrets: YesNoQuestion =
    Question.yesNo("leaks-secrets", "The result reveals a password, a key or a secret")

  final class Careful(context: AutonomousAgentContext) extends AutonomousAgent(context)

  object Careful extends AutonomousAgent.Companion[Careful](ComponentId("careful")):
    def create(context: AutonomousAgentContext) = new Careful(context)
    def definition =
      define
        .describedAs("Answers carefully")
        .guardrails(
          Guardrail
            .judged("task-safety")
            .onInput(Refuse.ifYes(harmful, atLeast = 0.7))
            .onOutput(Refuse.ifYes(leaksSecrets, atLeast = 0.7))
        )
        .capability(TaskAcceptance.of(Tasks.answer).maxIterationsPerTask(5))
        .settings(AutonomousAgentSettings(repeatedFailureAt = 2, maxConsecutiveFailures = 3))

  final class Unguarded(context: AutonomousAgentContext) extends AutonomousAgent(context)

  object Empty extends AutonomousAgent.Companion[Unguarded](ComponentId("empty")):
    def create(context: AutonomousAgentContext) = new Unguarded(context)
    def definition =
      define
        .describedAs("Guards nothing")
        .guardrails(Guardrail.judged("nothing"))
        .capability(TaskAcceptance.of(Tasks.answer).maxIterationsPerTask(1))
