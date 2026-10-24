package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.DurationInt

/** A workflow that waits for a decision, and gives up when none comes. */
final class Approval extends Workflow[String]:
  def emptyState: String = ""

  def request(what: String): Effect[Done] =
    effects.updateState(what).transitionTo(Approval.ask).thenReply(Done)

  def approve: Effect[String] =
    if !lifecycleIsPaused then effects.error("nothing is waiting", ErrorCode.Conflict)
    else effects.transitionTo(Approval.finish).thenReplyState

  def cancel: Effect[Done] = effects.delete().thenReply(Done)

  def spin: Effect[Done] = effects.transitionTo(Approval.loop).thenReply(Done)

  def explode: Effect[Done] = effects.transitionTo(Approval.boom).thenReply(Done)

  def askStep: StepEffect =
    stepEffects.updateState(s"$currentState?").thenPause(1.hour, Approval.expire)
  def finishStep: StepEffect = stepEffects.updateState(s"$currentState!").thenEnd
  def expireStep: StepEffect = stepEffects.thenFail("nobody decided")
  def loopStep: StepEffect   = stepEffects.thenTransitionTo(Approval.loop)
  def boomStep: StepEffect   = throw IllegalStateException("the step broke")

  // A paused workflow's state ends in '?', which is all a handler here can see of where it is.
  private def lifecycleIsPaused = currentState.endsWith("?")

object Approval extends Workflow.Companion[Approval, String](ComponentId("approval"), summon):
  def create(context: WorkflowContext) = new Approval

  val ask    = step("ask")(_.askStep)
  val finish = step("finish")(_.finishStep)
  val expire = step("expire")(_.expireStep)
  val loop   = step("loop")(_.loopStep)
  val boom   = step("boom")(_.boomStep)

  val request = command("request")(_.request)
  val approve = command("approve")(_.approve)
  val cancel  = command("cancel")(_.cancel)
  val spin    = command("spin")(_.spin)
  val explode = command("explode")(_.explode)

class WorkflowTestKitSuite extends munit.FunSuite with LogCapturing:

  private val wallets = TestTransport()
    .stub(WalletEntity.withdraw)(_ => Done)
    .stub(WalletEntity.deposit)(_ => Done)

  test("a command starts the workflow, and its steps run until it ends") {
    val kit    = WorkflowTestKit.of(TransferWorkflow, "t-1", wallets.client)
    val result = kit.call(TransferWorkflow.start)(Transfer("a", "b", 10))
    assertEquals(result.replyValue, Done)
    assertEquals(result.steps, Vector("withdraw", "deposit"))
    assertEquals(result.state.status, "completed")
    assert(result.lifecycle.isCompleted, result.lifecycle.toString)
    assertEquals(kit.state, result.state)
  }

  test("a refused command changes nothing and runs no step") {
    val kit    = WorkflowTestKit.of(TransferWorkflow, "t-2", wallets.client)
    val result = kit.call(TransferWorkflow.start)(Transfer("a", "b", 0))
    assertEquals(result.error.message, "transfer amount must be greater than zero")
    assertEquals(result.steps, Vector.empty)
    assertEquals(kit.lifecycle.status, "NotStarted")
  }

  test("a step that fails is retried, then failed over, as the workflow's settings say") {
    val refusing = TestTransport()
      .stub(WalletEntity.withdraw)(_ => Done)
      .stubFailure(WalletEntity.deposit)(CommandError("wallet frozen"))
    val flaky  = FlakyDeposit(failures = 2)
    val kit    = WorkflowTestKit.of(TransferWorkflow, "t-3", flaky.client)
    val result = kit.call(TransferWorkflow.start)(Transfer("a", "b", 10))
    assertEquals(result.steps, Vector("withdraw", "deposit", "deposit", "compensate"))
    assertEquals(result.state.status, "compensated")
    assertEquals(result.lifecycle.retries, Map("deposit" -> 1))
    assert(result.lifecycle.isCompleted)
    // The refusing transport is the plain case: every deposit refused, compensation too.
    val failing = WorkflowTestKit.of(TransferWorkflow, "t-4", refusing.client)
    val failed  = failing.call(TransferWorkflow.start)(Transfer("a", "b", 10))
    assert(failed.lifecycle.isFailed, failed.lifecycle.toString)
    assertEquals(failing.failedStep, Some("compensate"))
  }

  test("a workflow stops where it pauses, reports the pause's timeout, and goes on when told") {
    val kit    = WorkflowTestKit.of(Approval)
    val paused = kit.call(Approval.request)("a holiday")
    assertEquals(paused.steps, Vector("ask"))
    assert(paused.lifecycle.isPaused, paused.lifecycle.toString)
    assertEquals(paused.pauseTimeout, Some(1.hour -> "expire"))
    assertEquals(paused.state, "a holiday?")
    val approved = kit.call(Approval.approve)
    assertEquals(approved.replyValue, "a holiday?")
    assertEquals(approved.steps, Vector("finish"))
    assertEquals(approved.state, "a holiday?!")
    assert(approved.lifecycle.isCompleted)
  }

  test("a pause's timeout runs the step it named, only when the test says so") {
    val kit = WorkflowTestKit.of(Approval)
    intercept[AssertionError](kit.timeOut())
    kit.call(Approval.request)("a raise"): Unit
    val expired = kit.timeOut()
    assertEquals(expired.steps, Vector("expire"))
    assertEquals(expired.lifecycle.failure, Some("nobody decided"))
    assertEquals(kit.failedStep, Some("expire"))
  }

  test("a step that throws, with no recovery, fails the workflow naming the step") {
    val kit    = WorkflowTestKit.of(Approval)
    val result = kit.call(Approval.explode)
    assertEquals(result.replyValue, Done)
    assertEquals(result.lifecycle.failure, Some("step 'boom' failed: the step broke"))
  }

  test("a workflow that never stops fails the test rather than hanging it") {
    val kit     = WorkflowTestKit.of(Approval)
    val refused = intercept[AssertionError](kit.call(Approval.spin))
    assert(refused.getMessage.contains("1000 steps"), refused.getMessage)
  }

  test("a deleted workflow is the empty one, marked deleted") {
    val kit = WorkflowTestKit.of(Approval)
    kit.call(Approval.request)("a desk"): Unit
    kit.call(Approval.cancel): Unit
    assert(kit.isDeleted)
    assertEquals(kit.state, "")
    assertEquals(kit.lifecycle.status, "NotStarted")
  }

/** Routes the wallet calls, refusing the first `failures` deposits. */
private final class FlakyDeposit(failures: Int):
  private var refused         = 0
  val client: ComponentClient = TestTransport()
    .stub(WalletEntity.withdraw)(_ => Done)
    .stub(WalletEntity.deposit) { _ =>
      if refused < failures then
        refused += 1
        throw CommandError("wallet frozen")
      Done
    }
    .client
