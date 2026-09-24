package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.{ClientGrpc, InvokeReply, InvokeRequest}
import ankka.protocol.v1.discovery.{Kind, WorkflowDetail}
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.workflow.WorkflowIn
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.{Payload, PayloadKeys}
import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Server}

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * The in-process engine driving a workflow whose handlers and steps live in the process: steps run
 * in order on the engine's schedule, a throwing step is retried and failed over as the process
 * declared, a step calls back through the sidecar's client service, a pause survives a restart of
 * the service, and a step that never answers times out per the declared settings.
 */
class RemoteWorkflowSuite extends munit.FunSuite:
  import ProcessDouble.*

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  // The state is `<mode>|<status>`: the mode chosen at start decides the path the steps take.
  private def modeOf(state: Option[String]): String = state.map(_.split('|').head).getOrElse("")
  private def withStatus(state: Option[String], status: String): String =
    s"${modeOf(state)}|$status"

  @volatile private var callbackPort: Int = 0
  private val neverAnswer                 = new CountDownLatch(1)

  private val order = Flow(
    "order",
    handlers = Map(
      "start" -> WfHandler(
        readOnly = false,
        (state, input) =>
          if state.isDefined then WfEffect.Refuse("already started", pb.ErrorCode.CONFLICT)
          else WfEffect.Update(Some(s"$input|started"), Some(("reserve", None)), Some("started"))
      ),
      "status" -> WfHandler(readOnly = true, (state, _) => WfEffect.Reply(state.getOrElse("none")))
    ),
    steps = Map(
      "reserve" -> ((state, _) =>
        StepResult(
          Some(withStatus(state, "reserved")),
          modeOf(state) match
            case "slow"  => Next.TransitionTo("slow")
            case "call"  => Next.TransitionTo("call")
            case "pause" => Next.TransitionTo("wait")
            case _       => Next.TransitionTo("charge", Some("42"))
        )
      ),
      "charge" -> ((state, input) =>
        if modeOf(state) == "fail" then throw RuntimeException("no funds")
        else StepResult(Some(withStatus(state, s"charged:${input.getOrElse("")}")), Next.End)
      ),
      "compensate" -> ((state, _) => StepResult(Some(withStatus(state, "compensated")), Next.End)),
      "slow" -> ((_, _) =>
        neverAnswer.await()
        StepResult(None, Next.End)
      ),
      "call" -> ((state, _) =>
        val channel =
          ManagedChannelBuilder.forAddress("127.0.0.1", callbackPort).usePlaintext().build()
        try
          val reply = ClientGrpc
            .blockingStub(channel)
            .invoke(
              InvokeRequest(
                Kind.EVENT_SOURCED_ENTITY,
                "conformance",
                "wf-target",
                "record",
                Some(pb.Payload("text/plain", "string", ByteString.copyFromUtf8("from-step"))),
                Some(pb.Metadata())
              )
            )
          reply.result match
            case InvokeReply.Result.Reply(_) =>
              StepResult(Some(withStatus(state, "called")), Next.End)
            case other => throw RuntimeException(s"the call failed: $other")
        finally channel.shutdownNow(): Unit
      ),
      "wait" -> ((state, _) =>
        StepResult(Some(withStatus(state, "waiting")), Next.Pause(Some(1500L), Some("resume")))
      ),
      "resume" -> ((state, _) => StepResult(Some(withStatus(state, "resumed")), Next.End))
    ),
    settings = Some(
      WorkflowDetail.Settings(
        defaultStepTimeoutMillis = Some(2000L),
        steps = Seq(
          WorkflowDetail.StepSettings(
            "charge",
            None,
            Some(WorkflowDetail.Recovery(1, Some("compensate")))
          )
        )
      )
    )
  )

  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized
  private var callback: Server        = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(
      DoubleSpec(entities = Vector(recorder("conformance")), flows = Vector(order))
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val descriptors =
      Discovery.validate(double.toSpec).fold(p => fail(p.mkString("; ")), _.descriptors)
    kit = AnkkaTestKit.start(descriptors, Nil, 60.seconds, _.withConversation(conversation))
    callback = CallbackServer.start(
      ClientService(kit.service, settings, () => None)(using kit.service.system),
      "127.0.0.1",
      0
    )
    callbackPort = callback.getPort

  override def afterAll(): Unit =
    neverAnswer.countDown()
    Try(callback.shutdownNow())
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  private def ask(component: String, id: String, name: String, input: String): Future[Array[Byte]] =
    kit.componentClient.transportRef.ask(
      ComponentId(component),
      EntityId(id),
      MethodName(name),
      input.getBytes,
      Metadata.empty.set(PayloadKeys.Manifest, "string").set(PayloadKeys.ContentType, Payload.Text)
    )

  private def invoke(
      component: String,
      id: String,
      name: String,
      input: String = ""
  ): Either[CommandError, String] =
    Try(Await.result(ask(component, id, name, input), 10.seconds).pipe(String(_))).toEither.left
      .map {
        case e: CommandError => e
        case other           => CommandError(other.getMessage, ErrorCode.Internal)
      }

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  private def lifecycle(id: String): WorkflowLifecycle =
    WorkflowLifecycle.serializer.fromBytes(
      Await.result(ask("order", id, WorkflowLifecycle.Method.toString, ""), 10.seconds)
    )

  private def status(id: String): String = invoke("order", id, "status").toOption.get

  private def eventually[A](timeout: FiniteDuration = 20.seconds)(check: => Option[A]): A =
    val deadline        = System.nanoTime() + timeout.toNanos
    var last: Option[A] = check
    while last.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(100)
      last = check
    last.getOrElse(fail(s"not observed within $timeout"))

  private def stepsRun: Vector[String] =
    double.messagesOf { case in: WorkflowIn if in.message.isRunStep => in.message.runStep.get.step }

  test(
    "W1 a command starts the workflow; steps run in order with their inputs; the engine ends it"
  ) {
    assertEquals(invoke("order", "w1", "start", "ok"), Right("started"))
    eventually()(Some(lifecycle("w1")).filter(_.isCompleted))
    assertEquals(status("w1"), "ok|charged:42")
    assertEquals(stepsRun, Vector("reserve", "charge"))
    assertEquals(invoke("order", "w1", "start", "again").left.map(_.code), Left(ErrorCode.Conflict))
  }

  test("W2 a step that throws is retried as declared, then fails over to the compensation step") {
    val before = stepsRun.size
    assertEquals(invoke("order", "w2", "start", "fail"), Right("started"))
    eventually()(Some(lifecycle("w2")).filter(_.isCompleted))
    assertEquals(status("w2"), "fail|compensated")
    assertEquals(stepsRun.drop(before), Vector("reserve", "charge", "charge", "compensate"))
    assertEquals(lifecycle("w2").retries.get("charge"), Some(1))
  }

  test("W3 a step calls another component back through the sidecar's client service") {
    assertEquals(invoke("order", "w3", "start", "call"), Right("started"))
    eventually()(Some(lifecycle("w3")).filter(_.isCompleted))
    assertEquals(status("w3"), "call|called")
    assertEquals(invoke("conformance", "wf-target", "count"), Right("1"))
  }

  test("W4 a pause survives a restart of the service and its timeout step runs afterwards") {
    assertEquals(invoke("order", "w4", "start", "pause"), Right("started"))
    eventually()(Some(lifecycle("w4")).filter(_.isPaused))
    assertEquals(status("w4"), "pause|waiting")
    kit.restartService()
    eventually(30.seconds)(Some(lifecycle("w4")).filter(_.isCompleted))
    assertEquals(status("w4"), "pause|resumed")
  }

  test("W5 a step that never answers times out per the declared settings and fails the workflow") {
    assertEquals(invoke("order", "w5", "start", "slow"), Right("started"))
    // A query while the step is in flight is answered — from the state before the step.
    eventually(5.seconds)(Some(stepsRun).filter(_.lastOption.contains("slow")))
    assertEquals(status("w5"), "slow|reserved")
    val failed = eventually(15.seconds)(Some(lifecycle("w5")).filter(_.isFailed))
    assert(failed.failure.exists(_.contains("timed out")), s"failure: ${failed.failure}")
    assert(
      failed.failure.exists(f => f.contains("2000 milliseconds") || f.contains("2 seconds")),
      s"failure: ${failed.failure}"
    )
  }
