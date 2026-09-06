package nakka.testkit

import nakka.core.{CommandError, Done, EntityId, ErrorCode}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The workflow engine against real sharding, persistence and recovery. */
class WorkflowSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private var testKit: NakkaTestKit = null

  override def beforeAll(): Unit =
    testKit = NakkaTestKit.start(
      Seq(WalletEntity.descriptor, TransferWorkflow.descriptor)
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def wallet(id: String) = testKit.componentClient.forKeyValueEntity(EntityId(id))
  private def transfer(id: String) = testKit.componentClient.forWorkflow(EntityId(id))

  private def eventually[A](description: String, within: FiniteDuration = 40.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def statusOf(id: String): String =
    transfer(id).call(TransferWorkflow.status).invoke().status

  /** What the *engine* thinks, as opposed to what the domain state says. */
  private def lifecycleOf(id: String) =
    transfer(id).lifecycle(TransferWorkflow).invoke()

  private def fund(id: String, amount: Int): Unit =
    val _ = wallet(id).call(WalletEntity.deposit).invoke(amount)

  test("a transfer runs both steps and completes") {
    fund("w-src-1", 500)
    val _ = wallet("w-dst-1").call(WalletEntity.deposit).invoke(10)

    assertEquals(
      transfer("t-1").call(TransferWorkflow.start).invoke(Transfer("w-src-1", "w-dst-1", 100)),
      Done
    )

    val _ = eventually("the transfer completes")(Option(statusOf("t-1")).filter(_ == "completed"))
    assertEquals(wallet("w-src-1").call(WalletEntity.balance).invoke(), 400)
    assertEquals(wallet("w-dst-1").call(WalletEntity.balance).invoke(), 110)
  }

  test("steps run in order, each one recorded before the next begins") {
    fund("w-src-2", 300)
    val _ = transfer("t-2").call(TransferWorkflow.start).invoke(Transfer("w-src-2", "w-dst-2", 50))

    val _ = eventually("the transfer completes")(Option(statusOf("t-2")).filter(_ == "completed"))
    // Both sides moved by exactly the transfer amount — no double-application.
    assertEquals(wallet("w-src-2").call(WalletEntity.balance).invoke(), 250)
    assertEquals(wallet("w-dst-2").call(WalletEntity.balance).invoke(), 50)
  }

  test("a failing deposit is retried, then compensated") {
    fund("w-src-3", 200)
    // Freezing the destination makes every deposit reject, so the retry is used up and
    // the failover step runs.
    val _ = wallet("w-dst-3").call(WalletEntity.freeze).invoke()

    val _ = transfer("t-3").call(TransferWorkflow.start).invoke(Transfer("w-src-3", "w-dst-3", 75))

    val _ = eventually("the transfer compensates")(
      Option(statusOf("t-3")).filter(_ == "compensated")
    )

    // The money is back where it started and never reached the frozen wallet.
    assertEquals(wallet("w-src-3").call(WalletEntity.balance).invoke(), 200)
    assertEquals(wallet("w-dst-3").call(WalletEntity.balance).invoke(), 0)
  }

  test("a workflow resumes its pending step after the service restarts") {
    fund("w-src-4", 400)
    // Frozen, so the workflow parks on the deposit step retrying and cannot finish
    // before the restart.
    val _ = wallet("w-dst-4").call(WalletEntity.freeze).invoke()
    val _ = transfer("t-4").call(TransferWorkflow.start).invoke(Transfer("w-src-4", "w-dst-4", 60))

    // Wait until the withdrawal is journalled, so there is real in-flight progress.
    val _ = eventually("the withdrawal is recorded") {
      Option(statusOf("t-4")).filter(s => s == "withdrawn" || s == "compensated")
    }

    testKit.restartService()

    // The engine re-arms from the journal and the workflow reaches a terminal state.
    val finalStatus = eventually("the workflow terminates after recovery", 60.seconds) {
      Option(statusOf("t-4")).filter(s => s == "completed" || s == "compensated")
    }
    assertEquals(finalStatus, "compensated")
    assertEquals(wallet("w-src-4").call(WalletEntity.balance).invoke(), 400)
  }

  test("a rejected start persists nothing") {
    val failure = intercept[CommandError] {
      transfer("t-5").call(TransferWorkflow.start).invoke(Transfer("w-src-5", "w-dst-5", 0))
    }
    assertEquals(failure.code, ErrorCode.BadRequest)
    assertEquals(statusOf("t-5"), "not-started")
  }

  test("starting the same transfer twice conflicts") {
    fund("w-src-6", 100)
    val _ = transfer("t-6").call(TransferWorkflow.start).invoke(Transfer("w-src-6", "w-dst-6", 10))

    val failure = intercept[CommandError] {
      transfer("t-6").call(TransferWorkflow.start).invoke(Transfer("w-src-6", "w-dst-6", 10))
    }
    assertEquals(failure.code, ErrorCode.Conflict)
  }

  test("a step failure with no failover fails the workflow, visibly") {
    // No funds, so the *withdrawal* fails. It has no recovery strategy, so the workflow
    // must end Failed rather than stall.
    val _ = transfer("t-7").call(TransferWorkflow.start).invoke(Transfer("w-empty", "w-dst-7", 999))

    val lifecycle = eventually("the engine reports failure")(
      Option(lifecycleOf("t-7")).filter(_.isFailed)
    )

    // Asserting on the engine, not on the domain state. The domain state stays
    // "started" whether the step failed or the engine did, so an assertion on it proves
    // nothing — which is exactly how an engine bug hid here once already.
    assert(lifecycle.failure.exists(_.contains("withdraw")), lifecycle.failure.toString)
    assertEquals(lifecycle.pendingStep, None)
    assertEquals(statusOf("t-7"), "started", "no step succeeded, so domain state is untouched")
    assertEquals(wallet("w-dst-7").call(WalletEntity.balance).invoke(), 0)
  }

  test("the lifecycle tracks a workflow from start to completion") {
    fund("w-src-8", 250)

    assertEquals(lifecycleOf("t-8").status, "NotStarted")
    assertEquals(lifecycleOf("t-8").pendingStep, None)

    val _ = transfer("t-8").call(TransferWorkflow.start).invoke(Transfer("w-src-8", "w-dst-8", 25))

    val done = eventually("the engine reports completion")(
      Option(lifecycleOf("t-8")).filter(_.isCompleted)
    )
    assertEquals(done.pendingStep, None, "a completed workflow has nothing pending")
    assertEquals(done.failure, None)
    assertEquals(statusOf("t-8"), "completed")
  }

  test("the lifecycle records retries before failing over") {
    fund("w-src-9", 150)
    val _ = wallet("w-dst-9").call(WalletEntity.freeze).invoke()
    val _ = transfer("t-9").call(TransferWorkflow.start).invoke(Transfer("w-src-9", "w-dst-9", 40))

    val _ = eventually("the transfer compensates")(
      Option(statusOf("t-9")).filter(_ == "compensated")
    )

    // The deposit was attempted twice: once, then the single configured retry.
    assertEquals(lifecycleOf("t-9").retries.get("deposit"), Some(1))
    assert(lifecycleOf("t-9").isCompleted, "compensation ends the workflow normally")
  }

  test("the reserved lifecycle name cannot be used by a handler") {
    // Guards the one way a developer could shadow the engine's own query.
    val failure = intercept[IllegalArgumentException] {
      object Bad
          extends nakka.sdk.Workflow.Companion[TransferWorkflow, TransferState](
            nakka.core.ComponentId("bad-workflow"),
            nakka.core.Codecs.serializer[TransferState]("transfer-state")
          ):
        def create(context: nakka.sdk.WorkflowContext) = new TransferWorkflow(context)
        val clash = step("nakka:lifecycle")(_.compensateStep)
      Bad.descriptor
    }
    assert(failure.getMessage.contains("reserved"), failure.getMessage)
  }
