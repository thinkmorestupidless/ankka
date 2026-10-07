package outside.probe

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.autonomous.{
  forAutonomousAgent,
  TaskEntity,
  TaskEvent,
  TaskStatus
}
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.autonomous.{Answer, Answerer, Tasks}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Records every task event it is given, with the task it belongs to. It lives outside the `ankka`
 * package on purpose: a service's own consumer has to be able to subscribe to a platform entity.
 */
final class TaskWatcher extends Consumer[TaskEvent, Nothing]:
  def onMessage(event: TaskEvent): Effect =
    TaskWatcher.seen.add(messageContext.subject -> event.productPrefix)
    effects.ignore()

object TaskWatcher:
  val seen = CopyOnWriteArrayList[(String, String)]()

  val descriptor: ConsumerDescriptor[TaskWatcher, TaskEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId("outside-task-watcher"),
      source = ChangeSource.eventsOf(TaskEntity),
      outputSerializer = None,
      produceTo = None,
      create = _ => new TaskWatcher,
      parallelism = 1
    )

/**
 * V3 for blueprints (research R2, R11, FR-026): a consumer declared by a service, outside the
 * platform's own package, subscribes to a platform entity's events and receives them in order.
 * `TaskCascade` does this from inside the platform; nothing had done it from outside.
 */
class PlatformEntityConsumerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private val model             = TestModelProvider()

  override def beforeAll(): Unit =
    TaskWatcher.seen.clear()
    kit = AnkkaTestKit.start(
      Seq(Answerer.descriptor, TaskWatcher.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("V3 a consumer outside the platform receives a platform entity's events in order") {
    model
      .expectToolCall("count", Json.obj())
      .expectCompleteTask(Answer("0", List("count")))
    val id =
      kit.componentClient.forAutonomousAgent(Answerer).runSingleTask(Tasks.answer, "How many?")
    assertEquals(kit.awaitTask(id, Tasks.answer).status, TaskStatus.Completed)

    val events = eventually("the watcher sees the task's end") {
      val mine = TaskWatcher.seen.asScala.filter(_._1 == id).map(_._2).toVector
      Option.when(mine.contains("Completed"))(mine)
    }
    assertEquals(events.head, "Created")
    assertEquals(events.last, "Completed")
    assertEquals(events.indexOf("Started") > events.indexOf("Assigned"), true)
  }
