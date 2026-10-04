package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*

/**
 * An autonomous agent's tool that waits for a person, end to end: real sharding, the instance's
 * record and the task's session in Postgres, a scripted model.
 *
 * One case per scenario of `features/agents/autonomous-approvals.feature` but the one about a time
 * limit, named for it, and the cases a waiting instance that has left memory needs.
 */
class AutonomousApprovalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private val model             = TestModelProvider()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(Operator.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    Operator.runs.clear()

  private def client               = kit.componentClient
  private def instance(id: String) = client.forAutonomousAgent(Operator)(id)

  private val restartCart = Json.obj("service" -> Json.str("cart"))

  /** Assigns a task whose first iteration asks to restart the cart, and waits until it waits. */
  private def waitingTask(
      instanceId: String,
      callId: String = "call-restart"
  ): (String, ApprovalRequest) =
    model.expectToolCall("restart_service", restartCart, callId): Unit
    val taskId = client.tasks.create(Tasks.summary, "restart the cart service").create()
    instance(instanceId).assign(taskId): Unit
    (taskId, awaitingOne(instanceId))

  private def awaitingOne(instanceId: String): ApprovalRequest =
    kit.eventually(s"$instanceId awaits a decision")(
      instance(instanceId).state().awaiting.headOption
    )

  private def resultsOf(taskId: String): Vector[SessionMessage.ToolResultMessage] =
    client
      .forEventSourcedEntity(EntityId(IterationLoop.sessionIdFor(taskId)))
      .call(SessionMemoryEntity.history)
      .invoke()
      .messages
      .collect { case r: SessionMessage.ToolResultMessage => r }

  private def watch(instanceId: String): LinkedBlockingQueue[Notification] =
    val seen                                          = LinkedBlockingQueue[Notification]()
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    instance(instanceId).notifications().runForeach(seen.add(_): Unit): Unit
    seen

  private def next[N <: Notification](seen: LinkedBlockingQueue[Notification])(using
      tag: scala.reflect.ClassTag[N]
  ): N =
    Iterator
      .continually(
        Option(seen.poll(20, TimeUnit.SECONDS)).getOrElse(fail(s"no ${tag.runtimeClass}"))
      )
      .collectFirst { case n: N => n }
      .get

  /** The instance ids sharding holds in memory for the operator. */
  private def inMemory(): Set[String] =
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    import org.apache.pekko.cluster.sharding.ShardRegion.CurrentShardRegionState
    import org.apache.pekko.cluster.sharding.typed.GetShardRegionState
    import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    given org.apache.pekko.util.Timeout                       = 5.seconds
    val key =
      EntityTypeKey[com.thinkmorestupidless.ankka.runtime.EntityProtocol.Command]("operator")
    val state = scala.concurrent.Await.result(
      ClusterSharding(system).shardState.ask[CurrentShardRegionState](GetShardRegionState(key, _)),
      5.seconds
    )
    state.shards.flatMap(_.entityIds)

  test("every subscriber is told of an autonomous agent's approval request") {
    val first  = watch("watched")
    val second = watch("watched")
    next[Notification.Activated](first): Unit

    val (taskId, request) = waitingTask("watched")

    Vector(first, second).foreach { seen =>
      val asked = next[Notification.ApprovalRequested](seen)
      assertEquals(
        (asked.taskId, asked.approvalId, asked.tool),
        (taskId, request.id, "restart_service")
      )
      assert(asked.arguments.contains("cart"), asked.arguments)
    }
    assertEquals(Operator.runsOf("restart_service"), Vector.empty, "the tool has not run")
  }

  test("waiting for a decision uses none of the task's budget") {
    val (taskId, _) = waitingTask("patient")
    // Something happened first: the model was asked once and the iteration was recorded.
    kit.eventually("the first model call")(Option.when(model.callCount == 1)(()))
    val before = instance("patient").state().currentTask.map(_.iteration)
    assertEquals(before, Some(1))

    // Waiting is not working: the instance goes idle and leaves memory, rather than going round
    // and round its task — which would also leave the budget untouched, and be wrong.
    kit.eventually("the waiting instance leaves memory", 20.seconds)(
      Option.when(!inMemory().contains("patient"))(())
    )

    assertEquals(model.callCount, 1, "the model has not been asked since")
    assertEquals(instance("patient").state().currentTask.map(_.iteration), before)
    assertEquals(
      client.forTask(taskId).get().status,
      TaskStatus.InProgress,
      "the task has not failed"
    )
  }

  test("an autonomous agent's approved tool call runs once and the task goes on") {
    val (taskId, request) = waitingTask("approver")
    model.expectCompleteTaskText("restarted the cart"): Unit

    instance("approver").decide(Decision.approved(request.id, "dana")): Unit

    val done = kit.awaitTask(taskId, Tasks.summary)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(Operator.runsOf("restart_service"), Vector("restart_service(cart)"))
    // Iteration 1 asked and was counted once; iteration 2 completed the task.
    assertEquals(done.record.iterations, 2)
    assertEquals(model.callCount, 2)
  }

  test("an autonomous agent's refused tool call never runs and the task goes on") {
    val (taskId, request) = waitingTask("refuser")
    model.expectCompleteTaskText("left the cart alone"): Unit

    instance("refuser").decide(Decision.refused(request.id, "dana", "not during trading")): Unit

    assertEquals(kit.awaitTask(taskId, Tasks.summary).status, TaskStatus.Completed)
    assertEquals(Operator.runsOf("restart_service"), Vector.empty)
    val told = model.requests(1).messages.collect { case ChatMessage.ToolResults(r) => r }.flatten
    assert(told.exists(r => r.isError && r.content.contains("not during trading")), told.toString)
  }

  test(
    "an autonomous agent's approval request is still awaiting a decision after the service restarts"
  ) {
    val (taskId, request) = waitingTask("restarted")

    kit.restartService()

    assertEquals(awaitingOne("restarted").id, request.id)
    model.expectCompleteTaskText("restarted the cart"): Unit
    instance("restarted").decide(Decision.approved(request.id, "dana")): Unit
    assertEquals(kit.awaitTask(taskId, Tasks.summary).status, TaskStatus.Completed)
    assertEquals(Operator.runsOf("restart_service"), Vector("restart_service(cart)"))
  }

  test("cancelling a task discards its approval requests") {
    val (taskId, request) = waitingTask("canceller")

    client.forTask(taskId).cancel(): Unit

    kit.eventually("the task is over")(
      Option.when(instance("canceller").state().currentTask.isEmpty)(())
    )
    assertEquals(client.forTask(taskId).get().status, TaskStatus.Cancelled)
    val refused = intercept[CommandError](
      instance("canceller").decide(Decision.approved(request.id, "dana"))
    )
    assertEquals(refused.code, ErrorCode.NotFound)
    assertEquals(Operator.runsOf("restart_service"), Vector.empty)
  }

  test("suspending and resuming an autonomous agent leaves its approval request as it was") {
    val (taskId, request) = waitingTask("pauser")

    instance("pauser").suspend(): Unit
    assertEquals(instance("pauser").state().awaiting.map(_.id), Vector(request.id))
    instance("pauser").resume(): Unit

    assertEquals(instance("pauser").state().awaiting.map(_.id), Vector(request.id))
    model.expectCompleteTaskText("restarted the cart"): Unit
    instance("pauser").decide(Decision.approved(request.id, "dana")): Unit // not refused
    assertEquals(kit.awaitTask(taskId, Tasks.summary).status, TaskStatus.Completed)
  }

  test("an autonomous agent shows who decided an approval request") {
    val seen = watch("shower")
    next[Notification.Activated](seen): Unit
    val (taskId, request) = waitingTask("shower")
    model.expectCompleteTaskText("left the cart alone"): Unit

    instance("shower").decide(Decision.refused(request.id, "dana", "not during trading")): Unit

    val decided = next[Notification.ApprovalDecided](seen)
    assertEquals(
      (decided.by, decided.approved, decided.note),
      ("dana", false, Some("not during trading"))
    )
    kit.awaitTask(taskId, Tasks.summary): Unit
    val result = resultsOf(taskId).find(_.decision.isDefined).getOrElse(fail("no decided result"))
    assertEquals(
      result.decision.map(d => (d.by, d.note)),
      Some(("dana", Some("not during trading")))
    )
  }

  test("a waiting instance that has left memory comes back on a decision") {
    val (taskId, request) = waitingTask("sleeper")
    kit.eventually("the waiting instance leaves memory", 20.seconds)(
      Option.when(!inMemory().contains("sleeper"))(())
    )

    model.expectCompleteTaskText("restarted the cart"): Unit
    instance("sleeper").decide(Decision.approved(request.id, "dana")): Unit

    assertEquals(kit.awaitTask(taskId, Tasks.summary).status, TaskStatus.Completed)
    assertEquals(Operator.runsOf("restart_service"), Vector("restart_service(cart)"))
  }

  test("a waiting instance that has left memory ends its task when the task is cancelled") {
    val (taskId, request) = waitingTask("dozer")
    kit.eventually("the waiting instance leaves memory", 20.seconds)(
      Option.when(!inMemory().contains("dozer"))(())
    )

    client.forTask(taskId).cancel(): Unit

    kit.eventually("the instance notices")(
      Option.when(instance("dozer").state().currentTask.isEmpty)(())
    )
    val refused = intercept[CommandError](
      instance("dozer").decide(Decision.approved(request.id, "dana"))
    )
    assertEquals(refused.code, ErrorCode.NotFound)
  }

  test("a second decision after the task has gone on to its next iteration is a conflict") {
    val (taskId, first) = waitingTask("twice", callId = "call-1")
    model.expectToolCall("restart_service", restartCart, "call-2"): Unit
    instance("twice").decide(Decision.approved(first.id, "dana")): Unit
    val second = kit.eventually("the next iteration waits")(
      instance("twice").state().awaiting.find(_.id != first.id)
    )

    val refused =
      intercept[CommandError](instance("twice").decide(Decision.approved(first.id, "sam")))

    assertEquals(refused.code, ErrorCode.Conflict)
    model.expectCompleteTaskText("done"): Unit
    instance("twice").decide(Decision.refused(second.id, "dana")): Unit
    assertEquals(kit.awaitTask(taskId, Tasks.summary).status, TaskStatus.Completed)
    assertEquals(Operator.runsOf("restart_service").size, 1)
  }
