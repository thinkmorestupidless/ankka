package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{
  Ankka,
  AnkkaService,
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  SqlFragment,
  WorkflowRecord
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.apache.pekko.util.Timeout

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.util.Try

/**
 * `features/workflow-sources/views.feature`, run as it is written, against a real service.
 *
 * Each scenario's workflows are its own, their ids prefixed with its number, and each one's script
 * says what its steps do (`WorkflowKit`). What a view did is read from its rows — every row counts
 * its own writes — and from the log of what each reader was handed.
 */
final class WorkflowSourcesFeatures
    extends GherkinSuite("../../features/workflow-sources/views.feature")
    with LogCapturing:

  override val munitTimeout: Duration = 10.minutes

  private var kit: AnkkaTestKit = null
  private val broker            = InMemoryBroker()
  private val scenarios         = AtomicInteger()

  private var scope                              = ""
  private var before: Option[CheckoutRow]        = None
  private var answered: Vector[CheckoutRow]      = Vector.empty
  private var started: Option[Try[AnkkaService]] = None

  override def beforeAll(): Unit =
    super.beforeAll()
    FlowVersions.checkouts = 1
    // Registered at every start, so each start declares the version `FlowVersions` says then.
    kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      configure = _.registerAll(workflowSourceComponents)
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    scope = s"w${scenarios.incrementAndGet()}"
    before = None
    answered = Vector.empty
    started.foreach(_.foreach(_.terminate()))
    started = None

  private def scoped(id: String) = s"$scope-$id"

  private def checkouts       = kit.service.viewClient.forView(Checkouts)
  private def row(id: String) = checkouts.get(scoped(id))
  private def rowNow(id: String) =
    kit.eventually(s"the row $id")(row(id))

  private def start(id: String, script: String): Unit =
    kit.componentClient
      .forWorkflow(EntityId(scoped(id)))
      .call(CheckoutFlow.start)
      .invoke(script): Unit

  /** Waits until the row of `id` says `standing`, and answers it. */
  private def standing(id: String, word: String): CheckoutRow =
    kit.eventually(s"the row $id is $word")(row(id).filter(_.standing == word))

  /** The workflow's journal, every record as the runtime stored it. */
  private def journal(id: String): Vector[WorkflowRecord] =
    given system: ActorSystem[?] = kit.service.system
    val serialization = org.apache.pekko.serialization.SerializationExtension(system.classicSystem)
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT event_payload FROM event_journal WHERE persistence_id = 'checkout|${scoped(id)}' ORDER BY seq_nr"
        )
      )(r =>
        serialization
          .deserialize(r.get("event_payload", classOf[Array[Byte]]), classOf[WorkflowRecord])
          .get
      ),
      10.seconds
    )

  /** Long enough for a projection to have handed a change on, had there been one. */
  private def settle(): Unit = Thread.sleep(3000)

  // ── The service ───────────────────────────────────────────────────────────

  Given("a service {string} with a workflow {string} of the steps {string} and {string}")(
    (_: String, _: String, _: String, _: String) => ()
  )
  Given("a view {string} that reads the workflow {string} and keeps a row for each workflow of it")(
    (_: String, _: String) => ()
  )

  // ── What the workflows do ─────────────────────────────────────────────────

  // The scripts say this, and a step reads its script from the state: these steps are what the
  // next one's script will be.
  private var script = "end"

  Given(
    "the step {string} of {string} fails after its retries and fails over to {string}, which records the failure in the state and fails the workflow"
  )((_: String, _: String, _: String) => script = "compensate")
  Given("the step {string} of {string} fails after its retries and fails over to nothing")(
    (_: String, _: String) => script = "bare"
  )
  Given("the step {string} of {string} records its state and pauses")((_: String, _: String) =>
    script = "pause"
  )
  Given("the step {string} of {string} records its state and moves to {string}")(
    (_: String, _: String, _: String) => script = "hold"
  )

  When("the workflow {string} of {string} runs from its start to its end")(
    (id: String, _: String) =>
      start(id, "end")
      standing(id, "completed"): Unit
  )
  When("the workflow {string} of {string} runs from its start")((id: String, _: String) =>
    start(id, script)
    script = "end"
  )
  Given("the workflow {string} of {string} has run its step {string}")(
    (id: String, _: String, _: String) =>
      start(id, script)
      script = "end"
      kit.eventually(s"$id has run reserve")(row(id).filter(_.note == "reserve")): Unit
  )
  Given("{string} holds the row {string} from the state {string} recorded before {string}")(
    (_: String, id: String, _: String, _: String) =>
      start(id, script)
      script = "end"
      before = Some(kit.eventually(s"$id has run reserve")(row(id).filter(_.note == "reserve")))
  )
  When("the workflow {string} of {string} fails at {string}")((id: String, _: String, _: String) =>
    kit.eventually(s"$id has failed")(
      Some(journal(id)).filter(_.exists(_.kind == WorkflowRecord.KindFailed))
    ): Unit
  )

  // ── What the view holds ───────────────────────────────────────────────────

  Then("{string} holds a row {string}")((_: String, id: String) => rowNow(id): Unit)
  Then("the row {string} holds the state {string} ended with")((id: String, _: String) =>
    assertEquals(rowNow(id).note, "charge")
  )
  Then("the row {string} has the standing completed")((id: String) =>
    standing(id, "completed"): Unit
  )
  Then("the row {string} has the standing failed")((id: String) => standing(id, "failed"): Unit)
  Then("the row {string} has the standing paused")((id: String) => standing(id, "paused"): Unit)
  Then("the row {string} has the standing unknown")((id: String) => standing(id, "unknown"): Unit)
  Then("the row {string} holds the reason")((id: String) =>
    val failed = standing(id, "failed")
    assertEquals(failed.failure, Some("declined"))
    assertEquals(failed.reason, Some("declined"))
  )
  Then("the row {string} names the step {string}")((id: String, step: String) =>
    assertEquals(rowNow(id).step, Some(step))
  )
  Then("{string} is handed no change for the failure")((_: String) =>
    settle()
    val id = "c4"
    val seen =
      FlowLog.of("checkouts", scoped(id)).filter(_.standing.exists(_.status == "Failed"))
    assert(seen.isEmpty, s"handed a change for the failure: $seen")
  )
  Then("the row {string} is as it was")((id: String) =>
    assertEquals(row(id), before, "the row changed")
  )

  When("a handler reads the row {string}")((id: String) => before = Some(rowNow(id)))
  // What recorded the state: a step, or — for a state journalled by the release before — the
  // workflow itself, whose one state says so.
  Then("the row {string} holds the state {string} recorded")((id: String, by: String) =>
    val note = if by == id then "recorded-before" else by
    assertEquals(kit.eventually(s"the row $id")(row(id).filter(_.note == note)).note, note)
  )
  Then("the row {string} has the standing running and names the step {string}")(
    (id: String, step: String) =>
      val running = rowNow(id)
      assertEquals(running.standing, "running")
      assertEquals(running.step, Some(step))
  )

  // ── The declared query ────────────────────────────────────────────────────

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows holding the value {string}"
  )((_: String, query: String, _: String) => assertEquals(query, Checkouts.byStanding.name))
  Given(
    "the workflows {string} and {string} of {string} have ended as completed and the workflow {string} as failed"
  )((a: String, b: String, _: String, c: String) =>
    start(a, "end")
    start(b, "end")
    start(c, "compensate")
    standing(a, "completed")
    standing(b, "completed")
    standing(c, "failed"): Unit
  )
  When("a handler of {string} asks {string} the query {string} with {string} as {string}")(
    (_: String, _: String, _: String, value: String, name: String) =>
      answered =
        checkouts.ask(Checkouts.byStanding, name -> value).filter(_.id.startsWith(s"$scope-"))
  )
  Then("the handler is answered with the row {string} and no other")((id: String) =>
    assertEquals(answered.map(_.id), Vector(scoped(id)))
  )

  // ── Rebuild and restart ───────────────────────────────────────────────────

  Given("{string} at version {int} has read every state recorded by {string}")(
    (_: String, _: Int, _: String) => ()
  )
  Given("the workflows {string} and {string} of {string} have ended")(
    (a: String, b: String, _: String) =>
      start(a, "end")
      start(b, "end")
      standing(a, "completed")
      standing(b, "completed"): Unit
  )
  When("{string} restarts with {string} at version {int}")((_: String, _: String, _: Int) =>
    // Raised from whatever this run has reached, so each restart that asks for one rebuilds.
    FlowVersions.checkouts += 1
    kit.restartService()
  )
  Then("{string} holds no row written at version {int}")((_: String, _: Int) =>
    val version = FlowVersions.checkouts
    kit.eventually("every row is the new version's")(
      Option.when(checkouts.all(100000).forall(_.version == version))(())
    ): Unit
  )
  Then("{string} holds a row written at version {int} for {string} and for {string}")(
    (_: String, _: Int, a: String, b: String) =>
      val version = FlowVersions.checkouts
      Seq(a, b).foreach(id =>
        kit.eventually(s"$id at version $version")(row(id).filter(_.version == version)): Unit
      )
  )

  Given("{string} has read every state recorded by the workflow {string}")(
    (_: String, id: String) =>
      start(id, "end")
      before = Some(standing(id, "completed"))
  )
  When("{string} restarts")((_: String) => kit.restartService())
  Then("{string} reads no state of {string} again")((_: String, id: String) =>
    settle()
    assertEquals(row(id).map(_.writes), before.map(_.writes))
  )

  // ── A state recorded before standings were stamped ────────────────────────

  Given("the workflow {string} of {string} recorded a state on a release before workflow sources")(
    (id: String, _: String) => recordedBefore(scoped(id))
  )

  /**
   * Journals one state for `checkout|<id>` as the release before wrote it: a `WorkflowRecord` of
   * the `state` kind with no standing, persisted directly, with no adapter and no engine.
   */
  private def recordedBefore(id: String): Unit =
    given system: ActorSystem[?] = kit.service.system
    given Timeout                = 10.seconds
    val bytes = FlowState.serializer.toBytes(FlowState(id, "", "recorded-before", None))
    val writer: Behavior[org.apache.pekko.actor.typed.ActorRef[Boolean]] =
      EventSourcedBehavior[
        org.apache.pekko.actor.typed.ActorRef[Boolean],
        WorkflowRecord,
        Int
      ](
        PersistenceId("checkout", id),
        0,
        (_, replyTo) =>
          Effect.persist(WorkflowRecord.stateUpdated(bytes)).thenRun(_ => replyTo ! true),
        (n, _) => n + 1
      )
    val ref = system.systemActorOf(writer, s"recorded-before-$id")
    assert(Await.result(ref.ask[Boolean](identity), 10.seconds))

  // ── What may not be declared ──────────────────────────────────────────────

  Given("{string} has a view {string} that reads the topic {string} and the workflow {string}")(
    (_: String, _: String, _: String, _: String) => ()
  )
  When("{string} is started")((_: String) =>
    val service =
      Ankka.service.registerAll(Seq(CheckoutFlow.descriptor, ordersOfTopicAndCheckout))
    started = Some(Try(service.start(s"refused-$scope", kit.serviceConfig)))
  )
  Then("{string} does not start")((_: String) =>
    assert(started.exists(_.isFailure), s"it started: $started")
  )
  Then("the developer is told that a topic and a workflow may not be sources of one view")(() =>
    val problem =
      started.flatMap(_.failed.toOption).map(_.getMessage).getOrElse(fail("nothing failed"))
    assert(problem.contains("a topic and a workflow may not be sources of one view"), problem)
  )

  // ── A keyed view over an entity and the workflow ──────────────────────────

  Given("{string} has an event sourced entity {string}")((_: String, _: String) => ())
  Given("a view {string} that reads the events of {string} and the workflow {string}")(
    (_: String, _: String, _: String) => ()
  )
  When(
    "the workflow {string} of {string} ends and the entity {string} of {string} records an event at the same time"
  )((workflow: String, _: String, entity: String, _: String) =>
    val order = kit.componentClient
      .forEventSourcedEntity(EntityId(scoped(entity)))
      .call(OrderEntity.record)
      .invokeAsync(1)
    start(workflow, "end")
    Await.result(order, 10.seconds): Unit
  )
  Then(
    "{string} handles one of the two changes, with everything it reads and writes for it, before it handles the other"
  )((_: String) =>
    val lines = kit.eventually("both handled")(
      Some(FlowLog.spansOf(s"$scope-")).filter(l => l.count(_.startsWith("end")) >= 4)
    )
    // Every begin is followed by its own end before the next begin.
    lines.grouped(2).foreach { pair =>
      assert(pair.size == 2 && pair(0).startsWith("begin") && pair(1).startsWith("end"), lines)
      assertEquals(pair(0).stripPrefix("begin "), pair(1).stripPrefix("end "), lines)
    }
  )
  Then("{string} holds the rows each change names")((_: String) =>
    val fulfilment = kit.service.viewClient.forView(Fulfilment)
    Seq("c1", "o1").foreach(id => kit.eventually(s"the row $id")(fulfilment.get(scoped(id))): Unit)
  )

  // ── Deletion ──────────────────────────────────────────────────────────────

  Given("{string} holds the row {string}")((_: String, id: String) =>
    start(id, "end")
    standing(id, "completed"): Unit
  )
  When("the workflow {string} of {string} is deleted")((id: String, _: String) =>
    kit.componentClient
      .forWorkflow(EntityId(scoped(id)))
      .call(CheckoutFlow.remove)
      .invoke(): Unit
  )
  Then("the deletion handler of {string} runs for {string}")((_: String, id: String) =>
    kit.eventually("the deletion handler ran")(
      FlowLog.of("checkouts", scoped(id)).find(_.note.isEmpty)
    ): Unit
  )
  Then("{string} holds no row {string}")((_: String, id: String) =>
    kit.eventually(s"no row $id")(Option.when(row(id).isEmpty)(())): Unit
  )
