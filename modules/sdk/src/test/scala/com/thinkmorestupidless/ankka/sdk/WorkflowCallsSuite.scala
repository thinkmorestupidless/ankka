package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

final case class Tally(count: Int, closed: Boolean)

final class TallyWorkflow extends Workflow[Tally]:
  def emptyState: Tally            = Tally(0, closed = false)
  def start(by: Int): Effect[Done] = effects.updateState(Tally(by, closed = false)).thenReply(Done)
  def close: Effect[String]        = effects.reply("closing")
  def closeStep: StepEffect        = stepEffects.thenEnd

object TallyWorkflow
    extends Workflow.Companion[TallyWorkflow, Tally](
      ComponentId("tally"),
      Codecs.serializer[Tally]("tally")
    ):
  def create(context: WorkflowContext) = new TallyWorkflow
  val closing                          = step("closing")(_.closeStep)
  val start                            = command("start")(_.start)
  val close                            = command("close")(_.close)

/**
 * A transport that records what it was asked, in order, and answers a wait and a command as it is
 * told: what the client does with a wait, before any runtime is involved.
 */
final class RecordingTransport(
    answerCommand: MethodName => Future[Array[Byte]],
    answerWait: FiniteDuration => Future[(Array[Byte], Metadata)]
) extends CallTransport:
  val asked = CopyOnWriteArrayList[(String, Metadata, Option[FiniteDuration])]()

  def askTimeout: FiniteDuration = 1.second

  def ask(
      c: ComponentId,
      e: EntityId,
      m: MethodName,
      p: Array[Byte],
      md: Metadata
  ): Future[Array[Byte]] =
    asked.add((m, md, None))
    answerCommand(m)

  def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()

  override def awaitEnd(c: ComponentId, e: EntityId, timeout: FiniteDuration, md: Metadata) =
    asked.add(("await-end", md, Some(timeout)))
    answerWait(timeout)

  def calls: Vector[(String, Metadata, Option[FiniteDuration])] = asked.asScala.toVector

class WorkflowCallsSuite extends munit.FunSuite:

  private val ended = Tally(7, closed = true)
  private val endedOk = (_: FiniteDuration) =>
    Future.successful((TallyWorkflow.stateSerializer.toBytes(ended), Metadata.empty))
  private val done = (_: MethodName) => Future.successful(Serializers.done.toBytes(Done))

  private def client(t: RecordingTransport) = ComponentClient(t).forWorkflow(EntityId("t1"))

  test("a wait answers the state the workflow ended with, decoded as its companion says") {
    val t = RecordingTransport(done, endedOk)
    assertEquals(client(t).awaitEnd(TallyWorkflow, 5.seconds), ended)
    assertEquals(t.calls.map(_._1), Vector("await-end"))
    assertEquals(t.calls.head._3, Some(5.seconds))
  }

  test("a failed workflow surfaces as WorkflowFailed, with its step and reason") {
    val failed = CommandError(
      "workflow tally 't1' failed: boom",
      ErrorCode.WorkflowFailed,
      WorkflowEnd.Failure(Some("closing"), "boom", deleted = false).details
    )
    val t     = RecordingTransport(done, _ => Future.failed(failed))
    val error = intercept[CommandError](client(t).awaitEnd(TallyWorkflow, 5.seconds))
    assertEquals(
      WorkflowEnd.failure(error),
      Some(WorkflowEnd.Failure(Some("closing"), "boom", deleted = false))
    )
  }

  test("a start and a wait are one call: the command first, then the wait") {
    val t     = RecordingTransport(done, endedOk)
    val state = client(t).call(TallyWorkflow.start).thenAwaitEnd(5.seconds).invoke(3)
    assertEquals(state, ended)
    assertEquals(t.calls.map(_._1), Vector("start", "await-end"))
    val left = t.calls(1)._3.get
    assert(left <= 5.seconds && left > 4.seconds, s"one deadline, from the send: $left")
  }

  test("the command's own reply is not kept; a no-argument command waits the same way") {
    val t =
      RecordingTransport(_ => Future.successful(Serializers.string.toBytes("closing")), endedOk)
    val state: Tally = client(t).call(TallyWorkflow.close).thenAwaitEnd(2.seconds).invoke()
    assertEquals(state, ended)
    assertEquals(t.calls.map(_._1), Vector("close", "await-end"))
  }

  test("a refused command is thrown at once, and no wait begins") {
    val refused = CommandError("no", ErrorCode.Conflict)
    val t       = RecordingTransport(_ => Future.failed(refused), endedOk)
    val error =
      intercept[CommandError](client(t).call(TallyWorkflow.start).thenAwaitEnd(5.seconds).invoke(3))
    assertEquals(error, refused)
    assertEquals(t.calls.map(_._1), Vector("start"))
  }

  test("there is no default: a timeout of zero is refused, and nothing is sent") {
    val t = RecordingTransport(done, endedOk)
    assertEquals(
      intercept[CommandError](client(t).awaitEnd(TallyWorkflow, Duration.Zero)).code,
      ErrorCode.BadRequest
    )
    assertEquals(
      intercept[CommandError](
        client(t).call(TallyWorkflow.start).thenAwaitEnd(0.seconds).invoke(1)
      ).code,
      ErrorCode.BadRequest
    )
    assertEquals(t.calls, Vector.empty)
  }

  test("a wait carries the metadata it was given, as a command does") {
    val t        = RecordingTransport(done, endedOk)
    val metadata = Metadata.empty.set("x-request", "r1")
    client(t)
      .withMetadata(metadata)
      .call(TallyWorkflow.start)
      .thenAwaitEnd(1.second)
      .invoke(1): Unit
    assertEquals(t.calls.map(_._2.get("x-request")), Vector(Some("r1"), Some("r1")))
  }

  test("only a workflow's call can wait for an end") {
    val errors = compileErrors(
      "ComponentClient(RecordingTransport(_ => ???, _ => ???)).forEventSourcedEntity(EntityId(\"c\")).call(CounterEntity.increase).thenAwaitEnd(1.second)"
    )
    assert(errors.contains("thenAwaitEnd"), errors)
  }
