package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * A model call that fails is an iteration that failed, not a task that did: it is tried again, for
 * the same iteration, after a pause, and the task fails only after too many in a row.
 */
class IterationFailureSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  /**
   * Fails the next `failures` calls as a provider that is down would, then answers from `script`.
   */
  private final class Flaky(script: TestModelProvider) extends ModelProvider:
    val failures          = AtomicInteger(0)
    def name: String      = "flaky"
    def modelName: String = "flaky-model"
    def complete(request: ModelRequest): Future[ModelResponse] =
      if failures.getAndUpdate(n => (n - 1).max(0)) > 0 then
        Future.failed(ModelCallFailed(name, "the provider is down"))
      else script.complete(request)

  private val script = TestModelProvider()
  private val flaky  = Flaky(script)

  private final class Patient(context: AutonomousAgentContext) extends AutonomousAgent(context)

  private object Patient extends AutonomousAgent.Companion[Patient](ComponentId("patient")):
    def create(context: AutonomousAgentContext) = new Patient(context)
    def definition =
      define
        .describedAs("Tries again")
        .capability(TaskAcceptance.of(Tasks.answer).maxIterationsPerTask(2))
        .settings(AutonomousAgentSettings(repeatedFailureAt = 2, maxConsecutiveFailures = 3))

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(Patient.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(flaky), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    script.reset()
    flaky.failures.set(0)

  private def run(): String =
    kit.componentClient.forAutonomousAgent(Patient).runSingleTask(Tasks.answer, "Answer")

  test("failed calls are tried again for the same iteration, spending none of the budget") {
    flaky.failures.set(2)
    script.expectCompleteTask(Answer("got there", List("memory")))
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Completed)
    // A budget of two, and two failures before the call that answered: none of them counted.
    assertEquals(done.record.iterations, 1)
  }

  test("too many failures in a row fail the task with the last error") {
    flaky.failures.set(10)
    val done = kit.awaitTask(run(), Tasks.answer, 30.seconds)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("the provider is down")), done.reason.toString)
    assertEquals(script.callCount, 0)
  }
