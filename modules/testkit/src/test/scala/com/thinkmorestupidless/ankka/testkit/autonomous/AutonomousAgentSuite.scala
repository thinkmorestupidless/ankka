package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Autonomous agents end to end: real sharding, real journals, a scripted model. */
class AutonomousAgentSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private val model             = TestModelProvider()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(Answerer.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    Answerer.calls.clear()
    Answerer.gate.set(java.util.concurrent.CountDownLatch(0))
    Answerer.ruleChecks.set(0)
    Answerer.ruleFaults.set(0)

  private def client = kit.componentClient

  private def run(instructions: String): String =
    client.forAutonomousAgent(Answerer).runSingleTask(Tasks.answer, instructions)

  private def toolCalls = Answerer.calls.asScala.toVector

  // ── US1: a task is run and its typed result read later ─────────────────────

  test("US1 a task runs to a typed result through its tools") {
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("red")))
      .expectToolCall("count", Json.obj())
      .expectCompleteTask(Answer("3", List("lookup", "count")))
    val id   = run("How many red things are there?")
    val done = kit.awaitTask(id, Tasks.answer)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.result, Some(Answer("3", List("lookup", "count"))))
    assertEquals(done.record.iterations, 3)
    assertEquals(toolCalls, Vector("lookup(red)", "count"))
    assertEquals(model.callCount, 3)
  }

  test("US1 running a task answers before the model is called") {
    Answerer.gate.set(java.util.concurrent.CountDownLatch(1))
    model.expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-slow")))
    val id = run("Take your time")
    assert(client.forTask(id).get().status != TaskStatus.Completed)
    Answerer.gate.get().countDown()
    model.expectCompleteTask(Answer("done", List("lookup")))
    assertEquals(kit.awaitTask(id, Tasks.answer).status, TaskStatus.Completed)
  }

  test("US1 the model can give up, and nothing more is asked of it") {
    model.expectFailTask("there is no way to know")
    val id   = run("What will the weather be next year?")
    val done = kit.awaitTask(id, Tasks.answer)
    assertEquals(done.status, TaskStatus.Failed)
    assertEquals(done.reason, Some("there is no way to know"))
    assertEquals(done.result, None)
    assertEquals(model.callCount, 1)
  }

  test("US1 a result that does not match the shape goes back to the model as a tool error") {
    model
      .expectCompleteTaskJson("""{"answer":1}""")
      .expectCompleteTask(Answer("one", List("memory")))
    val id = run("Say one")
    assertEquals(kit.awaitTask(id, Tasks.answer).result, Some(Answer("one", List("memory"))))
    val second = model.requests(1)
    val errors = second.messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(
      errors.exists(r => r.isError && r.content.contains("does not match the task's shape")),
      errors.toString
    )
  }

  test("US1 a tool that throws is an error result, and the task carries on") {
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("boom")))
      .expectCompleteTask(Answer("unknown", List("lookup")))
    val id = run("Look up boom")
    assertEquals(kit.awaitTask(id, Tasks.answer).status, TaskStatus.Completed)
    val results =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(results.exists(r => r.isError && r.content.contains("down")), results.toString)
  }

  test("US1 a completed task's record says who did it and when") {
    model.expectCompleteTask(Answer("yes", List("memory")))
    val id     = run("Yes or no?")
    val record = kit.awaitTask(id, Tasks.answer).record
    assertEquals(record.assignee.map(_.componentId), Some("answerer"))
    assert(
      record.createdAt > 0 && record.assignedAt.isDefined && record.startedAt.isDefined && record.endedAt.isDefined
    )
    assert(
      System.currentTimeMillis() - record.endedAt.get < 1000,
      "await returned more than a second after the end"
    )
  }

  test("US1 the model is shown the task, its type and its budget") {
    model.expectCompleteTask(Answer("x", List("y")))
    kit.awaitTask(run("Find x"), Tasks.answer): Unit
    val request = model.requests.head
    val system  = request.systemMessage.getOrElse("")
    assert(system.contains("Answers questions") && system.contains("Be brief."), system)
    assert(system.contains("'answer'") && system.contains("iteration 1 of 5"), system)
    assertEquals(
      request.tools.map(_.name).toSet,
      Set("lookup", "count", "complete_task", "fail_task")
    )
    assertEquals(
      request.tools.find(_.name == "complete_task").map(_.inputSchema),
      Some(JsonSchema[Answer].schema)
    )
  }

  test("US1 an unknown task is not found, and a type the agent does not take is refused") {
    assertEquals(
      intercept[CommandError](client.forTask("no-such-task").get()).code,
      ErrorCode.NotFound
    )
    val error = intercept[CommandError](
      client.forAutonomousAgent(Answerer).runSingleTask(Tasks.review, "Review this")
    )
    assertEquals(error.code, ErrorCode.BadRequest)
  }

  test("US1 an unused instance reads as idle") {
    val state = client.forAutonomousAgent(Answerer)("never-used").state()
    assertEquals(state.phase, Phase.Idle)
    assertEquals(state.queued, Vector.empty)
  }

  // ── US2: work survives the process ─────────────────────────────────────────

  private def hold(): java.util.concurrent.CountDownLatch =
    val latch = java.util.concurrent.CountDownLatch(1)
    Answerer.gate.set(latch)
    latch

  private def waitForCall(call: String): Unit =
    kit.eventually(s"the tool call $call")(Option.when(toolCalls.contains(call))(()))

  test(
    "US2 a service stopped mid-task finishes it after restart, with nothing sent to the instance"
  ) {
    val latch = hold()
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("a")))
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-1")))
      .expectCompleteTask(Answer("done", List("lookup")))
    val id = run("Look two things up")
    waitForCall("lookup(hold-1)")

    // The second iteration's response is recorded and its tool is running: restart under it.
    kit.restartService()
    latch.countDown()

    val done = kit.awaitTask(id, Tasks.answer)
    assertEquals(done.status, TaskStatus.Completed)
    // Every model call was made once: the recorded responses were not asked for again.
    assertEquals(model.callCount, 3)
    // The tool whose result had not been recorded ran again: tools are run at least once.
    assertEquals(toolCalls.count(_ == "lookup(hold-1)"), 2)
    assertEquals(toolCalls.count(_ == "lookup(a)"), 1)
  }

  test("US2 queued tasks survive a restart and run in order") {
    val latch    = hold()
    val instance = "survivor"
    val ids      = (1 to 3).map(i => client.tasks.create(Tasks.answer, s"task $i").create())
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-q")))
      .expectCompleteTask(Answer("one", List("lookup")))
      .expectCompleteTask(Answer("two", List("memory")))
      .expectCompleteTask(Answer("three", List("memory")))
    val assigned = client.forAutonomousAgent(Answerer)(instance).assign(ids*)
    assertEquals(assigned.accepted, ids.toVector)
    waitForCall("lookup(hold-q)")
    assertEquals(client.forAutonomousAgent(Answerer)(instance).state().queued, ids.drop(1).toVector)

    kit.restartService()
    latch.countDown()

    val done = ids.map(id => kit.awaitTask(id, Tasks.answer))
    assertEquals(done.map(_.result.map(_.answer)), Vector(Some("one"), Some("two"), Some("three")))
    val ends = done.map(_.record.endedAt.get)
    assertEquals(ends, ends.sorted)
  }

  // ── US3: the budget stops a task that will not finish ──────────────────────

  private def lookups(n: Int): Unit =
    (1 to n).foreach(i => model.expectToolCall("lookup", Json.obj("topic" -> Json.str(s"t$i"))))

  test("US3 a task that never finishes fails after exactly its budget of model calls") {
    lookups(10)
    val done = kit.awaitTask(run("Never finish"), Tasks.answer)
    assertEquals(done.status, TaskStatus.Failed)
    assertEquals(done.reason, Some("iteration budget of 5 exhausted"))
    assertEquals(done.result, None)
    assertEquals(model.callCount, 5)
  }

  test("US3 the model is told how many iterations remain as it nears the budget") {
    lookups(10)
    kit.awaitTask(run("Never finish"), Tasks.answer): Unit
    val systems = model.requests.map(_.systemMessage.getOrElse(""))
    assert(!systems(2).contains("left after this one"), systems(2))
    assert(systems(3).contains("You have 1 iteration left after this one"), systems(3))
    assert(
      systems(4).contains("iteration 5 of 5") && systems(4).contains("last iteration"),
      systems(4)
    )
  }

  test("US3 the next queued task starts within a second of one failing") {
    lookups(5)
    model.expectCompleteTask(Answer("second", List("memory")))
    val first  = client.tasks.create(Tasks.answer, "Never finish").create()
    val second = client.tasks.create(Tasks.answer, "Finish").create()
    client.forAutonomousAgent(Answerer)("budget-queue").assign(first, second): Unit
    val a = kit.awaitTask(first, Tasks.answer)
    val b = kit.awaitTask(second, Tasks.answer)
    assertEquals((a.status, b.status), (TaskStatus.Failed, TaskStatus.Completed))
    assert(
      b.record.startedAt.get - a.record.endedAt.get < 1000,
      (a.record.endedAt, b.record.startedAt).toString
    )
  }

  test("US3 a task whose instructions a guardrail refuses fails before any model call") {
    val done = kit.awaitTask(run("the key is sk-123"), Tasks.answer)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("no-secrets")), done.reason.toString)
    assertEquals(model.callCount, 0)
  }

  // ── US4: a result is held to the task's rules ──────────────────────────────

  test(
    "US4 a result a rule rejects goes back to the model with the reason, and the next one stands"
  ) {
    model
      .expectCompleteTask(Answer("three", Nil))
      .expectCompleteTask(Answer("three", List("count")))
    val id   = run("How many?")
    val done = kit.awaitTask(id, Tasks.answer)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.result.map(_.sources), Some(List("count")))
    assertEquals(done.record.iterations, 2)
    val second =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(
      second.exists(r =>
        r.isError && r.content.contains("rule 'cites-sources': sources must not be empty")
      ),
      second.toString
    )
  }

  test("US4 a rejected result is recorded on the task until the next attempt") {
    Answerer.gate.set(java.util.concurrent.CountDownLatch(1))
    val latch = Answerer.gate.get()
    model
      .expectCompleteTask(Answer("three", Nil))
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-after-rejection")))
      .expectCompleteTask(Answer("three", List("lookup")))
    val id = run("How many?")
    // Held in the iteration after the rejection: the record says why the last result was refused.
    waitForCall("lookup(hold-after-rejection)")
    val mid = client.forTask(id).get()
    assertEquals(mid.reason, Some("rule 'cites-sources': sources must not be empty"))
    latch.countDown()
    assertEquals(kit.awaitTask(id, Tasks.answer).status, TaskStatus.Completed)
  }

  test("US4 an output guardrail refuses a result as a rule would") {
    model
      .expectCompleteTask(Answer("the key is sk-123", List("memory")))
      .expectCompleteTask(Answer("no key here", List("memory")))
    val done = kit.awaitTask(run("Tell me the key"), Tasks.answer)
    assertEquals(done.result.map(_.answer), Some("no key here"))
    val second =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(second.exists(r => r.isError && r.content.contains("no-secrets")), second.toString)
  }

  test("US4 a rejection counts against the budget, and resets the warning that it is near") {
    (1 to 5).foreach(_ => model.expectCompleteTask(Answer("x", Nil)))
    val done = kit.awaitTask(run("Never cite anything"), Tasks.answer)
    assertEquals(done.status, TaskStatus.Failed)
    assertEquals(done.reason, Some("iteration budget of 5 exhausted"))
    assertEquals(model.callCount, 5)
  }

  // ── US5: an instance is driven from outside ────────────────────────────────

  private def instance(id: String) = client.forAutonomousAgent(Answerer)(id)

  private def create(instructions: String, dependsOn: String*): String =
    client.tasks.create(Tasks.answer, instructions).dependsOn(dependsOn*).create()

  /** The instance ids sharding holds in memory for the answerer. */
  private def inMemory(): Set[String] =
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
    import org.apache.pekko.cluster.sharding.ShardRegion.CurrentShardRegionState
    import org.apache.pekko.cluster.sharding.typed.GetShardRegionState
    import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    given org.apache.pekko.util.Timeout                       = 5.seconds
    val key =
      EntityTypeKey[com.thinkmorestupidless.ankka.runtime.EntityProtocol.Command]("answerer")
    val state = scala.concurrent.Await.result(
      ClusterSharding(system).shardState.ask[CurrentShardRegionState](GetShardRegionState(key, _)),
      5.seconds
    )
    state.shards.flatMap(_.entityIds)

  test("US5 assigned tasks are worked one at a time, in order, and the state says so") {
    val latch = hold()
    val ids   = Vector(create("first"), create("second"), create("third"))
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-order")))
      .expectCompleteTask(Answer("1", List("lookup")))
      .expectCompleteTask(Answer("2", List("memory")))
      .expectCompleteTask(Answer("3", List("memory")))
    instance("reviewer-1").assign(ids*): Unit
    waitForCall("lookup(hold-order)")
    val state = instance("reviewer-1").state()
    assertEquals(state.phase, Phase.Working)
    assertEquals(state.currentTask.map(_.taskId), Some(ids(0)))
    assertEquals(state.queued, ids.drop(1))
    latch.countDown()
    val ends = ids.map(id => kit.awaitTask(id, Tasks.answer).record.endedAt.get)
    assertEquals(ends, ends.sorted)
  }

  test("US5 a suspended instance makes no model call until resumed, and keeps its task") {
    val latch = hold()
    val id    = create("pause me")
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-suspend")))
      .expectCompleteTask(Answer("resumed", List("lookup")))
    instance("pauser").assign(id): Unit
    waitForCall("lookup(hold-suspend)")
    instance("pauser").suspend(): Unit
    latch.countDown()
    Thread.sleep(2000)
    assertEquals(model.callCount, 1)
    assertEquals(client.forTask(id).get().status, TaskStatus.InProgress)
    assertEquals(instance("pauser").state().phase, Phase.Suspended)
    instance("pauser").resume(): Unit
    assertEquals(kit.awaitTask(id, Tasks.answer).result.map(_.answer), Some("resumed"))
  }

  test("US5 a suspended idle instance holds what it is given until resumed") {
    instance("idle-pauser").assign(create("warm up")): Unit // creates the instance
    model.expectCompleteTask(Answer("warm", List("memory")))
    kit.eventually("warm-up done")(
      Option.when(instance("idle-pauser").state().phase == Phase.Idle)(())
    )
    instance("idle-pauser").suspend(): Unit
    val id = create("wait for me")
    model.expectCompleteTask(Answer("waited", List("memory")))
    instance("idle-pauser").assign(id): Unit
    Thread.sleep(2000)
    assertEquals(client.forTask(id).get().status, TaskStatus.Assigned)
    instance("idle-pauser").resume(): Unit
    assertEquals(kit.awaitTask(id, Tasks.answer).status, TaskStatus.Completed)
  }

  test("US5 terminating hands the tasks back, and the id is never used again") {
    val latch = hold()
    val ids   = Vector(create("working"), create("queued 1"), create("queued 2"))
    model.expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-terminate")))
    instance("doomed").assign(ids*): Unit
    waitForCall("lookup(hold-terminate)")
    instance("doomed").terminate(): Unit
    latch.countDown()
    ids.foreach { id =>
      val t = client.forTask(id).get()
      assertEquals((t.status, t.assignee), (TaskStatus.Pending, None), id)
      assertEquals(t.reason, Some("assignee terminated"))
    }
    assertEquals(instance("doomed").state().phase, Phase.Terminated)
    instance("doomed").terminate(): Unit // a second is harmless
    val refused = intercept[CommandError](instance("doomed").assign(create("too late")))
    assertEquals(refused.code, ErrorCode.Conflict)
  }

  test(
    "US5 cancelling the task being worked stops it at the end of the iteration; the next one starts"
  ) {
    val latch  = hold()
    val first  = create("cancel me")
    val second = create("then me")
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-cancel")))
      .expectCompleteTask(Answer("second", List("memory")))
    instance("canceller").assign(first, second): Unit
    waitForCall("lookup(hold-cancel)")
    client.forTask(first).cancel("no longer needed"): Unit
    latch.countDown()
    val cancelled = kit.awaitTask(first, Tasks.answer)
    assertEquals(
      (cancelled.status, cancelled.reason),
      (TaskStatus.Cancelled, Some("no longer needed"))
    )
    assertEquals(kit.awaitTask(second, Tasks.answer).result.map(_.answer), Some("second"))
    // The cancelled task asked for nothing more after its first response.
    assertEquals(model.callCount, 2)
  }

  test("US5 cancelling a queued task takes it out of the queue, and the rest keep their order") {
    val latch = hold()
    val ids   = Vector(create("a"), create("b"), create("c"))
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-dequeue")))
      .expectCompleteTask(Answer("a", List("lookup")))
      .expectCompleteTask(Answer("c", List("memory")))
    instance("dequeuer").assign(ids*): Unit
    waitForCall("lookup(hold-dequeue)")
    client.forTask(ids(1)).cancel(): Unit
    assertEquals(instance("dequeuer").state().queued, Vector(ids(2)))
    latch.countDown()
    assertEquals(kit.awaitTask(ids(2), Tasks.answer).result.map(_.answer), Some("c"))
    assertEquals(client.forTask(ids(1)).get().status, TaskStatus.Cancelled)
  }

  test(
    "US5 a task waiting on a dependency lets a later one run, then starts with the dependency's result"
  ) {
    val a = create("the dependency")
    val b = create("the dependent", a)
    model
      .expectCompleteTask(Answer("forty-two", List("memory")))
      .expectCompleteTask(Answer("used it", List("dependency")))
    // b is queued first, and waits; a runs.
    instance("deps").assign(b, a): Unit
    assertEquals(kit.awaitTask(b, Tasks.answer).status, TaskStatus.Completed)
    val bFirst = model.requests(1).messages.head.toString
    assert(bFirst.contains(s"Result of task '$a'") && bFirst.contains("forty-two"), bFirst)
  }

  test("US5 a dependency that fails cancels its dependents") {
    val a = create("will fail")
    val b = create("depends on it", a)
    model.expectFailTask("could not")
    instance("cascade").assign(a): Unit
    kit.eventually("the dependent is cancelled") {
      Option(client.forTask(b).get()).filter(_.status == TaskStatus.Cancelled)
    }
    assertEquals(client.forTask(b).get().reason, Some(s"dependency '$a' failed"))
  }

  test("US5 a dependency must exist, and one already failed cancels the dependent at once") {
    assertEquals(intercept[CommandError](create("orphan", "no-such-task")).code, ErrorCode.NotFound)
    val a = create("fails first")
    model.expectFailTask("could not")
    instance("already").assign(a): Unit
    kit.awaitTask(a, Tasks.answer): Unit
    val b = create("too late", a)
    assertEquals(client.forTask(b).get().status, TaskStatus.Cancelled)
  }

  test("US5 an idle instance leaves memory, and asking its state does not bring it back") {
    model.expectCompleteTask(Answer("done", List("memory")))
    val id = create("then rest")
    instance("sleeper").assign(id): Unit
    kit.awaitTask(id, Tasks.answer): Unit
    kit.eventually("the instance leaves memory", 20.seconds)(
      Option.when(!inMemory().contains("sleeper"))(())
    )
    assertEquals(instance("sleeper").state().phase, Phase.Idle)
    Thread.sleep(500)
    assert(!inMemory().contains("sleeper"))
  }

  test("US4 of two rules, only the first to refuse is reported") {
    model
      .expectCompleteTask(Answer("", Nil))
      .expectCompleteTask(Answer("three", List("count")))
    val id = client.forAutonomousAgent(Answerer).runSingleTask(Tasks.strict, "How many?")
    assertEquals(kit.awaitTask(id, Tasks.strict).status, TaskStatus.Completed)
    val errors = model
      .requests(1)
      .messages
      .collect { case ChatMessage.ToolResults(rs) => rs }
      .flatten
      .filter(_.isError)
    assertEquals(errors.size, 1, errors.toString)
    assert(errors.head.content.contains("rule 'says-something'"), errors.head.content)
    assert(!errors.head.content.contains("cites-sources"), errors.head.content)
  }

  test(
    "US4 a rule that throws decides nothing: the check is made again, and the model is not asked again"
  ) {
    Answerer.ruleFaults.set(1)
    model.expectCompleteTask(Answer("three", List("count")))
    val id   = client.forAutonomousAgent(Answerer).runSingleTask(Tasks.flaky, "How many?")
    val done = kit.awaitTask(id, Tasks.flaky)
    assertEquals(done.status, TaskStatus.Completed)
    assertEquals(done.record.iterations, 1)
    assertEquals(model.callCount, 1)
    assertEquals(Answerer.ruleChecks.get, 2)
  }

  test("US4 a rule that keeps throwing fails the task once too many checks have failed in a row") {
    Answerer.ruleFaults.set(100)
    model.expectCompleteTask(Answer("three", List("count")))
    val id   = client.forAutonomousAgent(Answerer).runSingleTask(Tasks.flaky, "How many?")
    val done = kit.awaitTask(id, Tasks.flaky, 2.minutes)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("the checker is down")), done.reason.toString)
    assertEquals(model.callCount, 1)
  }

  // ── US6: progress is watched live ──────────────────────────────────────────

  /** Collects what an instance says from now on, until `count` have arrived. */
  private def watch(instanceId: String): java.util.concurrent.LinkedBlockingQueue[Notification] =
    val seen = java.util.concurrent.LinkedBlockingQueue[Notification]()
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    client
      .forAutonomousAgent(Answerer)(instanceId)
      .notifications()
      .runForeach(seen.add(_): Unit): Unit
    seen

  private def until(
      seen: java.util.concurrent.LinkedBlockingQueue[Notification],
      last: String
  ): Vector[Notification] =
    val out  = Vector.newBuilder[Notification]
    var name = ""
    while name != last do
      val next = Option(seen.poll(20, java.util.concurrent.TimeUnit.SECONDS))
        .getOrElse(fail(s"no $last after ${out.result().map(_.productPrefix)}"))
      name = next.productPrefix
      out += next
    out.result()

  private def types(
      seen: java.util.concurrent.LinkedBlockingQueue[Notification],
      last: String
  ): Vector[String] =
    until(seen, last).map(_.productPrefix)

  test("US6 a subscriber sees a task's run, in order, as it happens") {
    // Subscribing starts the instance; the task is assigned once the subscriber is in place.
    val seen = watch("watched")
    kit.eventually("the subscriber is in place")(
      Option(seen.peek()).filter(_.productPrefix == "Activated")
    )
    model
      .expectCompleteTask(Answer("x", Nil))
      .expectCompleteTask(Answer("x", List("memory")))
    val id = create("Watch me")
    instance("watched").assign(id): Unit
    val sequence = types(seen, "TaskCompleted")
    assertEquals(
      sequence,
      Vector(
        "Activated",
        "TaskAssigned",
        "TaskStarted",
        "IterationStarted",
        "IterationCompleted",
        "TaskResultRejected",
        "IterationStarted",
        "IterationCompleted",
        "TaskCompleted"
      )
    )
  }

  test("US6 notifications name their instance and task, and carry what happened") {
    val seen = watch("detailed")
    kit.eventually("the subscriber is in place")(Option(seen.peek()))
    model.expectCompleteTask(Answer("x", List("memory")))
    val id = create("Detail")
    instance("detailed").assign(id): Unit
    val all = until(seen, "TaskCompleted")
    all.collect { case n: Notification.TaskAssigned => n.taskId }.foreach(t => assertEquals(t, id))
    val started = all.collectFirst { case n: Notification.IterationStarted => n }.get
    assertEquals(
      (started.instanceId, started.taskId, started.iteration, started.remaining),
      ("detailed", id, 1, 4)
    )
    val completed = all.collectFirst { case n: Notification.TaskCompleted => n }.get
    assertEquals((completed.taskId, completed.iterations), (id, 1))
  }

  test("US6 a subscriber that joins late sees nothing from before") {
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("early")))
      .expectCompleteTask(Answer("done", List("lookup")))
    val id = create("Early")
    instance("late-watch").assign(id): Unit
    kit.awaitTask(id, Tasks.answer): Unit
    val seen = watch("late-watch")
    Thread.sleep(1000)
    assert(!seen.asScala.exists(_.productPrefix.startsWith("Task")), seen.toString)
  }

  test("US6 approaching the budget is warned about once, and again after a rejection") {
    val seen = watch("near-budget")
    kit.eventually("the subscriber is in place")(Option(seen.peek()))
    model
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("a")))
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("b")))
      .expectToolCall("lookup", Json.obj("topic" -> Json.str("c")))
      .expectCompleteTask(Answer("x", Nil)) // iteration 4 of 5: warned, then rejected
      .expectCompleteTask(Answer("x", List("lookup")))
    val id = create("Take your time")
    instance("near-budget").assign(id): Unit
    val sequence = types(seen, "TaskCompleted")
    assertEquals(sequence.count(_ == "TaskApproachingMaxIterations"), 2, sequence.toString)
  }

  test("US6 a watched instance stays in memory, and leaves once nobody is watching") {
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    val (switch, _) = client
      .forAutonomousAgent(Answerer)("watched-idle")
      .notifications()
      .viaMat(org.apache.pekko.stream.KillSwitches.single)(
        org.apache.pekko.stream.scaladsl.Keep.right
      )
      .toMat(org.apache.pekko.stream.scaladsl.Sink.ignore)(
        org.apache.pekko.stream.scaladsl.Keep.both
      )
      .run()
    kit.eventually("the instance is up")(Option.when(inMemory().contains("watched-idle"))(()))
    Thread.sleep(5000) // longer than the fixture's 3s idle passivation
    assert(inMemory().contains("watched-idle"), "a watched instance left memory")
    switch.shutdown()
    kit.eventually("the instance leaves", 20.seconds)(
      Option.when(!inMemory().contains("watched-idle"))(())
    )
  }

  // ── US7: it can be tested with a script ────────────────────────────────────

  test("US7 a script that runs out fails the task, saying so, rather than stalling") {
    val done = kit.awaitTask(run("Nothing is scripted"), Tasks.answer)
    assertEquals(done.status, TaskStatus.Failed)
    assert(done.reason.exists(_.contains("no scripted response left")), done.reason.toString)
  }

  test("US7 waiting for a task that does not end names where it got to") {
    val latch = hold()
    model.expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-forever")))
    val id = run("Hold")
    waitForCall("lookup(hold-forever)")
    val error = intercept[CommandError](kit.awaitTask(id, Tasks.answer, 1.second))
    assertEquals(error.code, ErrorCode.Timeout)
    assert(error.message.contains("in-progress"), error.message)
    model.expectCompleteTask(Answer("released", List("lookup")))
    latch.countDown()
    kit.awaitTask(id, Tasks.answer): Unit
  }

  test("US7 standing rules answer by what the request holds, without using up the queue") {
    model
      // The tool-result rule is declared first: rules are tried in order, and the instructions
      // still say "red" on the second request.
      .whenToolResult("3 items")(
        ModelResponse(
          "",
          Vector(
            ToolCall(
              "done",
              "complete_task",
              Json.parse("""{"answer":"3","sources":["count"]}""").toOption.get
            )
          ),
          StopReason.ToolUse
        )
      )
      .whenUserAsks("red")(
        ModelResponse("", Vector(ToolCall("c", "count", Json.obj())), StopReason.ToolUse)
      )
    val done = kit.awaitTask(run("How many red things?"), Tasks.answer)
    assertEquals(done.result, Some(Answer("3", List("count"))))
    assertEquals(toolCalls, Vector("count"))
  }
