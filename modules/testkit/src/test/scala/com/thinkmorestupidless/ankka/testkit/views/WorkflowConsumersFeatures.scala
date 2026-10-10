package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  SqlFragment
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * `features/workflow-sources/consumers.feature`, run as it is written, against a real service with
 * the in-memory broker. What `settlement` was handed is read from the readers' log; what it did,
 * from the broker and the ledger.
 */
final class WorkflowConsumersFeatures
    extends GherkinSuite("../../features/workflow-sources/consumers.feature")
    with LogCapturing:

  override val munitTimeout: Duration = 10.minutes

  private var kit: AnkkaTestKit = null
  private val broker            = InMemoryBroker()
  private val scenarios         = AtomicInteger()

  private var scope  = ""
  private var script = "end"

  override def beforeAll(): Unit =
    super.beforeAll()
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
    scope = s"t${scenarios.incrementAndGet()}"
    script = "end"

  private def scoped(id: String) = s"$scope-$id"

  private def start(id: String, script: String): Unit =
    kit.componentClient
      .forWorkflow(EntityId(scoped(id)))
      .call(TransferFlow.start)
      .invoke(script): Unit

  private def seen(id: String) = FlowLog.of("settlement", scoped(id))

  private def settled(id: String) =
    broker.publishedTo("transfers-settled").filter(_.message.subject.contains(scoped(id)))

  private def reports(id: String): Int =
    kit.componentClient
      .forEventSourcedEntity(EntityId(scoped(id)))
      .call(LedgerEntity.reports)
      .invoke()

  /** The sequence numbers of every record the transfer journalled, in order. */
  private def sequences(id: String): Vector[Long] =
    given ActorSystem[?] = kit.service.system
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT seq_nr FROM event_journal WHERE persistence_id = 'transfer|${scoped(id)}' ORDER BY seq_nr"
        )
      )(r => r.get("seq_nr", classOf[java.lang.Long]).longValue),
      10.seconds
    )

  /** Long enough for a projection to have handed a change on, had there been one. */
  private def settle(): Unit = Thread.sleep(3000)

  // ── The service ───────────────────────────────────────────────────────────

  Given("a service {string} with a workflow {string} of the steps {string} and {string}")(
    (_: String, _: String, _: String, _: String) => ()
  )
  Given("an event sourced entity {string}")((_: String) => ())

  // `settlement` does each of these, by its standing; the step says which the scenario reads.
  Given(
    "a consumer {string} that reads the workflow {string} and publishes to the topic {string} for each change whose standing is completed"
  )((_: String, _: String, _: String) => ())
  Given(
    "a consumer {string} that reads the workflow {string} and calls {string} for each change whose standing is failed"
  )((_: String, _: String, _: String) => ())
  Given("a consumer {string} that reads the workflow {string}")((_: String, _: String) => ())

  Given(
    "the step {string} of {string} fails after its retries and fails over to {string}, which records the failure in the state and fails the workflow"
  )((_: String, _: String, _: String) => script = "compensate")
  Given("{string} declares a timeout of {string} for the whole workflow")((_: String, _: String) =>
    () // `transfer` does: two seconds in all
  )

  // ── What the transfers do ─────────────────────────────────────────────────

  When("the workflow {string} of {string} runs from its start to its end")(
    (id: String, _: String) =>
      start(id, "end")
      kit.eventually(s"$id settled")(settled(id).headOption): Unit
  )
  When("the workflow {string} of {string} runs from its start")((id: String, _: String) =>
    start(id, script)
  )
  When(
    "the step {string} of the workflow {string} of {string} records its state and moves to {string}"
  )((_: String, id: String, _: String, _: String) =>
    start(id, "hold")
    kit.eventually(s"$id handed withdraw")(seen(id).find(_.note.contains("withdraw"))): Unit
  )
  When("the workflow {string} of {string} runs a step that takes longer than {string}")(
    (id: String, _: String, _: String) => start(id, "slow")
  )

  // ── What settlement did ───────────────────────────────────────────────────

  Then("one message about {string} is published to {string}")((id: String, _: String) =>
    settle()
    assertEquals(settled(id).size, 1)
  )
  Then("no message is published for the changes of {string} before its end")((id: String) =>
    // Every change before the end was handed and ignored: one message, for the end alone.
    val handed = seen(id)
    assert(handed.size >= 3, s"handed $handed")
    assertEquals(handed.count(_.standing.exists(_.status == "Completed")), 1)
    assertEquals(settled(id).size, 1)
  )
  Then("{string} is called once about {string}")((_: String, id: String) =>
    kit.eventually(s"$id reported")(Option.when(reports(id) >= 1)(())): Unit
    settle()
    assertEquals(reports(id), 1)
  )
  Then("{string} is handed one change for it")((_: String) =>
    settle()
    assertEquals(seen("t1").count(_.note.contains("withdraw")), 1)
  )
  Then("the change carries the state and the standing running on {string}")((step: String) =>
    val change = seen("t1").find(_.note.contains("withdraw")).get
    assertEquals(change.standing.map(_.status), Some("Running"))
    assertEquals(change.standing.flatMap(_.pendingStep), Some(step))
  )
  Then("the change carries the sequence number of the state's record and {string} as its subject")(
    (id: String) =>
      val change = seen(id).find(_.note.contains("withdraw")).get
      assertEquals(change.subject, scoped(id))
      // start: state, transition; withdraw: state, transition — the withdraw state is the third.
      assertEquals(change.sequence, sequences(id)(2))
  )
  Then("{string} is handed no change for the timeout")((_: String) =>
    kit.eventually("the transfer timed out")(
      Option.when(sequences("t3").size >= 5)(())
    ): Unit
    settle()
    val handed = seen("t3")
    assert(!handed.exists(_.standing.exists(_.status == "Failed")), s"handed $handed")
  )

  // ── Deletion ──────────────────────────────────────────────────────────────

  Given(
    "a consumer {string} that reads the workflow {string} and has read every change of {string}"
  )((_: String, _: String, id: String) =>
    start(id, "end")
    kit.eventually(s"$id settled")(settled(id).headOption): Unit
  )
  When("the workflow {string} of {string} is deleted")((id: String, _: String) =>
    kit.componentClient.forWorkflow(EntityId(scoped(id))).call(TransferFlow.remove).invoke(): Unit
  )
  Then("the deletion handler of {string} runs for {string}")((_: String, id: String) =>
    kit.eventually("the deletion handler ran")(seen(id).find(_.note.isEmpty)): Unit
  )
