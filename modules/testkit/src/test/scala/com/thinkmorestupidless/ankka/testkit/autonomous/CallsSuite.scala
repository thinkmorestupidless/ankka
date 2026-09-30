package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.*

import scala.concurrent.duration.DurationInt

/** The client's orderings, against real task entities and no runtime. */
class CallsSuite extends munit.FunSuite with LogCapturing:

  import CallsSuite.*

  private val answer = Task.named("answer").describedAs("Answer").resultConformsTo[Answer]
  private val usage  = TokenUsage(1, 1)

  private def failed(router: EntityRouter, id: String): Unit =
    router.client.tasks.create(answer, "x").withId(id).create(): Unit
    val k = router.task(id)
    k.call(TaskEntity.assign)(Assignee("a", "i"))
    k.call(TaskEntity.fail)(TaskEntity.Fail("gave up", 1, usage)): Unit

  test("a task is created pending, with an id when none is given") {
    val router = EntityRouter()
    val id = router.client.tasks
      .create(answer, "How many?")
      .attach("brief", "text/plain", "Be brief.")
      .create()
    val record = router.client.forTask(id).get()
    assertEquals(record.status, TaskStatus.Pending)
    assertEquals(record.attachments.map(_.name), Vector("brief"))
    assert(id.nonEmpty)
  }

  test("a missing dependency is refused before anything is written") {
    val router = EntityRouter()
    val error = intercept[CommandError](
      router.client.tasks.create(answer, "x").withId("b").dependsOn("ghost").create()
    )
    assertEquals(error.code, ErrorCode.NotFound)
    assert(!router.callLog.exists(_.contains("/b#create")), router.callLog.toString)
  }

  test("a dependency records its dependent after the dependent exists") {
    val router = EntityRouter()
    router.client.tasks.create(answer, "first").withId("a").create()
    router.client.tasks.create(answer, "second").withId("b").dependsOn("a").create()
    assertEquals(router.task("a").currentState.dependents, Vector("b"))
    val log = router.callLog
    assert(
      log.indexOf("ankka-task/b#create") < log.indexOf("ankka-task/a#add-dependent"),
      log.toString
    )
  }

  test("a task depending on one that has already failed is cancelled at once, naming it") {
    val router = EntityRouter()
    failed(router, "a")
    router.client.tasks.create(answer, "second").withId("b").dependsOn("a").create()
    val b = router.client.forTask("b").get()
    assertEquals(b.status, TaskStatus.Cancelled)
    assertEquals(b.reason, Some("dependency 'a' failed"))
  }

  test("get decodes the result as the task's type, and refuses another type") {
    val router = EntityRouter()
    router.client.tasks.create(answer, "x").withId("a").create()
    val k = router.task("a")
    k.call(TaskEntity.assign)(Assignee("a", "i"))
    k.call(TaskEntity.start)
    k.call(TaskEntity.complete)(TaskEntity.Complete(answer.encode(Answer("3")), 1, usage))
    assertEquals(router.client.forTask("a").get(answer).result, Some(Answer("3")))
    val other = Task.named("summary").describedAs("Summarise")
    assertEquals(
      intercept[CommandError](router.client.forTask("a").get(other)).code,
      ErrorCode.BadRequest
    )
  }

  test("await returns at once on a task that has ended, and names the status when it times out") {
    val router = EntityRouter()
    failed(router, "a")
    assertEquals(router.client.forTask("a").await(answer, 1.second).status, TaskStatus.Failed)
    router.client.tasks.create(answer, "x").withId("b").create()
    val error = intercept[CommandError](router.client.forTask("b").await(answer, 300.millis))
    assertEquals(error.code, ErrorCode.Timeout)
    assert(error.message.contains("pending"), error.message)
  }

  test("cancelling writes the record first, then tells the assignee") {
    val router = EntityRouter()
    router.client.tasks.create(answer, "x").withId("a").create()
    router.task("a").call(TaskEntity.assign)(Assignee("answerer", "i-1"))
    router.client.forTask("a").cancel("no longer needed")
    assertEquals(router.client.forTask("a").get().status, TaskStatus.Cancelled)
    val log = router.callLog
    assert(log.indexOf("ankka-task/a#cancel") < log.indexOf("answerer/i-1#dequeue"), log.toString)
  }

  test("a single task runs on a generated instance, and the task id comes back") {
    var hosted = Option.empty[(ComponentId, EntityId, MethodName)]
    val router = EntityRouter { (c, e, m, _) =>
      hosted = Some((c, e, m)); Array.emptyByteArray
    }
    val companion = new AutonomousAgent.Companion[Nothing](ComponentId("answerer")):
      def create(context: AutonomousAgentContext) = ???
      def definition =
        define.describedAs("x").capability(TaskAcceptance.of(Task.named("answer").describedAs("x")))
    val id = router.client.forAutonomousAgent(companion).runSingleTask(answer, "How many?")
    assertEquals(router.client.forTask(id).get().status, TaskStatus.Pending)
    val (component, instance, method) = hosted.getOrElse(fail("the host was never asked"))
    assertEquals((component, method), (ComponentId("answerer"), MethodName("run-single-task")))
    assert(instance.nonEmpty)
  }

  test("an instance needs an id") {
    assertEquals(
      intercept[CommandError](EntityRouter().client.forAutonomousAgent(ComponentId("a"), "")).code,
      ErrorCode.BadRequest
    )
  }

object CallsSuite:
  final case class Answer(answer: String)
  object Answer:
    given JsonValueCodec[Answer] = Codecs.make
    given JsonSchema[Answer]     = JsonSchema.derived
