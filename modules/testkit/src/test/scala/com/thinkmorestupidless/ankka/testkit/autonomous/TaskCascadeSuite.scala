package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.{InMemorySecretStore, LogCapturing}
import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.{
  ComponentClient,
  ConsumerContext,
  SecretStore,
  SimpleChangeContext
}

/** The cascade against real task entities and no runtime: what it cancels, and what it leaves. */
class TaskCascadeSuite extends munit.FunSuite with LogCapturing:

  private val answer = Task.named("answer").describedAs("Answer").resultConformsTo[Answer]
  private val usage  = TokenUsage(1, 1)

  private def cascade(router: EntityRouter): TaskCascade =
    new TaskCascade(new ConsumerContext:
      def componentId: ComponentId         = TaskCascade.ComponentId
      def componentClient: ComponentClient = router.client
      def secrets: SecretStore             = InMemorySecretStore())

  /** Delivers `event` as the runtime would: with the task it happened to as the subject. */
  private def deliver(router: EntityRouter, taskId: String, event: TaskEvent): Unit =
    val consumer = cascade(router)
    consumer._setContext(Some(SimpleChangeContext(taskId, 1L, localOrigin = true)))
    try consumer.onMessage(event): Unit
    finally consumer._setContext(None)

  /** `a`, with `b` and `c` depending on it. */
  private def family(router: EntityRouter): Unit =
    router.client.tasks.create(answer, "first").withId("a").create(): Unit
    router.client.tasks.create(answer, "second").withId("b").dependsOn("a").create(): Unit
    router.client.tasks.create(answer, "third").withId("c").dependsOn("a").create(): Unit

  private def fail(router: EntityRouter, id: String): TaskEvent =
    val k = router.task(id)
    k.call(TaskEntity.assign)(Assignee("a", "i"))
    k.call(TaskEntity.fail)(TaskEntity.Fail("gave up", 1, usage)): Unit
    TaskEvent.Failed("gave up", 1, usage, 0L)

  test("a failed task's dependents are each cancelled, naming it") {
    val router = EntityRouter()
    family(router)
    deliver(router, "a", fail(router, "a"))
    for id <- Seq("b", "c") do
      val record = router.task(id).currentState
      assertEquals(record.status, TaskStatus.Cancelled, id)
      assert(
        record.reason.exists(r => r.contains("'a'") && r.contains("failed")),
        record.reason.toString
      )
  }

  test("a cancelled task's dependents are cancelled too") {
    val router = EntityRouter()
    family(router)
    router.client.forTask("a").cancel("not needed"): Unit
    deliver(router, "a", TaskEvent.Cancelled("not needed", 0L))
    assertEquals(
      Seq("b", "c").map(router.task(_).currentState.status),
      Seq(TaskStatus.Cancelled, TaskStatus.Cancelled)
    )
  }

  test("a completed task cancels nothing") {
    val router = EntityRouter()
    family(router)
    deliver(router, "a", TaskEvent.Completed("{}", 1, usage, 0L))
    assertEquals(
      Seq("b", "c").map(router.task(_).currentState.status),
      Seq(TaskStatus.Pending, TaskStatus.Pending)
    )
    assert(!router.callLog.exists(_.contains("#cancel")), router.callLog.toString)
  }

  test("a redelivery is harmless: a dependent that has already ended is left as it is") {
    val router = EntityRouter()
    family(router)
    val failed = fail(router, "a")
    deliver(router, "a", failed)
    deliver(router, "a", failed)
    assertEquals(router.task("b").currentState.status, TaskStatus.Cancelled)
  }
