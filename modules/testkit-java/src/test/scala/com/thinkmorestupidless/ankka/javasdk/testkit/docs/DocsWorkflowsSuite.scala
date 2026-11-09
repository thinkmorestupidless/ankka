package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration
import scala.concurrent.duration.DurationInt

/** The Java samples of the workflows page: the transfer, its recovery, and a step that waits. */
class DocsWorkflowsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(TransferCalls.service())
  override def afterAll(): Unit  = if kit != null then kit.close()

  private def balance(id: String): Long =
    client.forKeyValueEntity(id).call(DocsRefs.balance).invoke().amount

  private def transfer(id: String, t: TransferWorkflow.Transfer): TransferWorkflow.TransferState =
    client.forWorkflow(id).call(DocsRefs.startTransfer).invoke(t): Unit
    client.forWorkflow(id).awaitEnd(classOf[TransferWorkflow], Duration.ofSeconds(20))

  test("a transfer withdraws, deposits and completes") {
    client.forKeyValueEntity("a").call(DocsRefs.deposit).invoke(100L): Unit
    val ended = transfer("t-1", TransferWorkflow.Transfer("a", "b", 40))
    assertEquals(ended.status, "completed")
    assertEquals(balance("a"), 60L)
    assertEquals(balance("b"), 40L)
  }

  test("a deposit that keeps failing is retried once, then compensated") {
    client.forKeyValueEntity("c").call(DocsRefs.deposit).invoke(30L): Unit
    val ended = transfer("t-2", TransferWorkflow.Transfer("c", "frozen-d", 10))
    assertEquals(ended.status, "compensated")
    assertEquals(balance("c"), 30L)
    val lifecycle = client.forWorkflow("t-2").lifecycle(classOf[TransferWorkflow]).invoke()
    assert(lifecycle.isCompleted, lifecycle.toString)
    assertEquals(lifecycle.retries.get("deposit"), Integer.valueOf(1))
  }

  test("a transfer of nothing is refused before anything starts") {
    val e = intercept[CommandException](
      client
        .forWorkflow("t-3")
        .call(DocsRefs.startTransfer)
        .invoke(TransferWorkflow.Transfer("a", "b", 0))
    )
    assert(e.getMessage.contains("greater than zero"), e.getMessage)
  }

  test("a step starts another workflow and goes on with its decision") {
    client.forWorkflow("ada").call(DocsRefs.startOnboarding).invoke("ada"): Unit
    val ada =
      client.forWorkflow("ada").awaitEnd(classOf[OnboardingWorkflow], Duration.ofSeconds(30))
    assertEquals(ada.status, "welcomed")
    client.forWorkflow("bob").call(DocsRefs.startOnboarding).invoke("bad-bob"): Unit
    val bob =
      client.forWorkflow("bob").awaitEnd(classOf[OnboardingWorkflow], Duration.ofSeconds(30))
    assertEquals(bob.status, "declined")
  }
