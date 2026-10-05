package com.thinkmorestupidless.ankka.sidecar.wasm

import ankka.protocol.v1.agent.{
  GuardrailResult,
  PlanReply,
  TaskResultRequest,
  TaskResultVerdict as PbTaskResultVerdict,
  ToolRequest,
  ToolResult
}
import ankka.protocol.v1.consumer.ConsumerEffect
import ankka.protocol.v1.discovery.Kind
import ankka.protocol.v1.endpoint.HttpReply
import ankka.protocol.v1.event_sourced.EventSourcedIn
import ankka.protocol.v1.key_value.KeyValueIn
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.timed_action.TimedActionEffect
import ankka.protocol.v1.view.ViewEffect
import ankka.protocol.v1.wasm.{
  FoldReply,
  FoldRequest,
  HandleReply,
  HandleRequest,
  Passivate,
  StepReply as PbStepReply,
  StepRequest
}
import ankka.protocol.v1.workflow.WorkflowIn
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  ComponentKind,
  ErrorCode,
  Metadata
}
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sidecar.Discovery.Shape
import com.thinkmorestupidless.ankka.sidecar.{Settings, Translate}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import scalapb.GeneratedMessage

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * The `Conversation` a remote host speaks, with a WebAssembly module in this process instead of a
 * developer's process over gRPC. The remote hosts cannot tell the difference, which is the point:
 * nothing in `runtime` changes for a module.
 *
 * An entity or workflow instance's session keeps its `HeldState`. Its commands go to the command
 * pool — any instance for a stateless component, the pinned one for a stateful component, handed
 * the state only when that instance does not already hold it. Everything that may wait on the
 * runtime (a workflow step, a view, a consumer, a timed action, an agent's plan, tool or guardrail,
 * an HTTP route) runs on a fresh instance of its own, so a slow call never holds an instance a
 * command needs. Every call runs on a virtual thread, so a guest blocked in an import parks it.
 *
 * A fault — a trap, a reply that says `failure`, no reply within the deadline — is answered as a
 * process's failure is, the instance it happened on is discarded, and the held state is untouched:
 * the next call starts from it.
 */
final class WasmConversation(
    module: LoadedModule,
    settings: Settings,
    imports: HostImports,
    shapeOf: ComponentId => Shape
) extends Conversation:

  import Translate.*

  private given ExecutionContext = AnkkaExecutors.virtual

  private val build = () => GuestInstance.build(module, imports.values, settings.wasmMaxMemoryPages)
  val commands      = CommandPool(settings.wasmInstances, build, settings.commandTimeout)
  val blocking      = BlockingPool(build)
  private val stepTime = 10.minutes

  private def fn(name: String): String = Abi.Prefix + name

  /**
   * The runtime's clock on the call, for a module built before it could ask for the time through
   * the `now` import: it reads this entry, on every call that carries metadata. The same clock
   * answers both.
   */
  private def stamped(metadata: Metadata): Metadata =
    metadata.set(WasmConversation.Now, imports.clock().toString)

  private def failure(id: Long, fault: GuestFault): ProcessFailure =
    ProcessFailure(
      id,
      CommandError(s"the module failed in ${fault.function}: ${fault.message}", ErrorCode.Internal)
    )

  /**
   * Calls `function` with `request` on `instance`, and reads the reply as an `A`. `purpose` is what
   * the call is for, which the imports read to know what they were called from.
   */
  private def ask[A](
      instance: GuestInstance,
      function: String,
      request: GeneratedMessage,
      purpose: Purpose
  )(parse: Array[Byte] => A): Either[GuestFault, A] =
    instance.call(function, request.toByteArray, purpose).flatMap { bytes =>
      Try(parse(bytes)).toEither.left.map { e =>
        instance.markBroken(GuestFault(function, s"an unreadable reply: ${e.getMessage}"))
        GuestFault(function, s"an unreadable reply: ${e.getMessage}")
      }
    }

  // ── Stateful sessions ──────────────────────────────────────────────────────

  def open(init: Init): InstanceSession =
    val kind = init.kind match
      case ComponentKind.EventSourcedEntity => Kind.EVENT_SOURCED_ENTITY
      case ComponentKind.KeyValueEntity     => Kind.KEY_VALUE_ENTITY
      case ComponentKind.Workflow           => Kind.WORKFLOW
      case other =>
        throw ProtocolViolation(s"$other is not a stateful kind and has no conversation")
    val held = HeldState(
      s"${init.componentId}/${init.entityId}",
      shapeOf(init.componentId),
      init.snapshot.map(s => toPayload(s.payload)),
      init.snapshot.map(_.sequence).getOrElse(0L)
    )
    Session(init, kind, held)

  private final class Session(init: Init, kind: Kind, held: HeldState) extends InstanceSession:

    private def purposeOf(handler: Option[String], metadata: Metadata): Purpose =
      Purpose(init.componentId.toString, handler, metadata)

    private def onCommandPool[A](what: String)(
        f: (GuestInstance, Boolean) => Either[GuestFault, A]
    ): Either[GuestFault, A] =
      held.shape match
        case Shape.Stateful  => commands.withPinned(held.key, what)(f)
        case Shape.Stateless => commands.withAny(what)(f(_, false))

    /** Folds what replay delivered since the last call; on a fault, puts it back. */
    private def foldReplayed(
        instance: GuestInstance,
        resident: Boolean
    ): Either[GuestFault, Boolean] =
      val events = held.takeUnfolded()
      events.zipWithIndex.foldLeft[Either[GuestFault, Boolean]](Right(resident)) {
        case (Left(fault), _) => Left(fault)
        case (Right(holds), ((sequence, event), i)) =>
          val request = FoldRequest(
            init.componentId,
            init.entityId,
            held.toSend(holds),
            Some(event),
            sequence
          )
          ask(instance, fn("fold"), request, purposeOf(None, Metadata.empty))(
            FoldReply.parseFrom
          ) match
            case Right(reply) if reply.failure.isEmpty =>
              held.update(reply.state, sequence)
              Right(true)
            case other =>
              events.drop(i).foreach((s, e) => held.replayed(s, e))
              other match
                case Right(reply) =>
                  Left(GuestFault("ankka1_fold", reply.getFailure.getError.message))
                case Left(fault) => Left(fault)
      }

    /** Replay: folded, in order, before the next call, on the thread that makes it. */
    def event(sequence: Long, payload: Payload): Unit = held.replayed(sequence, toPayload(payload))

    def command(cmd: Command): Future[Either[ProcessFailure, Reply]] = Future {
      val sent     = stamped(cmd.metadata).set(WasmConversation.Sequence, held.sequence.toString)
      val metadata = Some(toMetadata(sent))
      val command = kind match
        case Kind.EVENT_SOURCED_ENTITY =>
          HandleRequest.Command.EventSourced(
            EventSourcedIn.Command(
              cmd.id,
              cmd.name,
              Some(toPayload(cmd.payload)),
              metadata,
              cmd.snapshotRequested
            )
          )
        case Kind.KEY_VALUE_ENTITY =>
          HandleRequest.Command.KeyValue(
            KeyValueIn.Command(cmd.id, cmd.name, Some(toPayload(cmd.payload)), metadata)
          )
        case _ =>
          HandleRequest.Command.Workflow(
            WorkflowIn.Command(cmd.id, cmd.name, Some(toPayload(cmd.payload)), metadata)
          )
      val answered = onCommandPool(fn("handle")) { (instance, resident) =>
        foldReplayed(instance, resident).flatMap { holds =>
          val request = HandleRequest(
            kind,
            init.componentId,
            init.entityId,
            held.toSend(holds),
            command
          )
          ask(instance, fn("handle"), request, purposeOf(Some(cmd.name.toString), sent))(
            HandleReply.parseFrom
          )
        }
      }
      answered match
        case Left(fault) => Left(failure(cmd.id, fault))
        case Right(reply) if reply.failure.isDefined =>
          Left(fromFailure(reply.getFailure.copy(commandId = cmd.id)))
        case Right(reply) =>
          val translated = reply.reply match
            case HandleReply.Reply.EventSourced(r) => Right(fromEventSourcedReply(r))
            case HandleReply.Reply.KeyValue(r)     => Right(fromKeyValueReply(r))
            case HandleReply.Reply.Workflow(r)     => Right(fromWorkflowReply(r))
            case HandleReply.Reply.Empty =>
              Left(
                ProcessFailure(
                  cmd.id,
                  CommandError("the module answered the command with no reply", ErrorCode.Internal)
                )
              )
          translated.foreach(r => held.update(reply.state, held.sequence + r.events.size))
          translated
    }

    def runStep(
        id: Long,
        step: String,
        input: Option[Array[Byte]],
        metadata: Metadata
    ): Future[Either[ProcessFailure, StepReply]] = Future {
      // A fresh instance holds nothing, so it is always handed the state, whatever the shape.
      val sent = stamped(metadata)
      val request = StepRequest(
        init.componentId,
        init.entityId,
        held.state,
        Some(
          WorkflowIn.RunStep(
            id,
            step,
            input.map(pb.Payload.parseFrom),
            Some(Translate.toMetadata(sent))
          )
        )
      )
      blocking.withFresh(fn("run_step"), stepTime)(
        ask(_, fn("run_step"), request, purposeOf(Some(step), sent))(PbStepReply.parseFrom)
      ) match
        case Left(fault) => Left(failure(id, fault))
        case Right(reply) if reply.failure.isDefined =>
          Left(fromFailure(reply.getFailure.copy(commandId = id)))
        case Right(reply) =>
          held.update(reply.state, held.sequence)
          // The pinned instance of a stateful workflow now holds the state before the step.
          if held.shape == Shape.Stateful then commands.evict(held.key)
          Right(fromStepReply(reply.getReply))
    }

    def close(): Unit =
      if held.shape == Shape.Stateful && module.exportsFunction(fn("close")) then
        Future {
          commands.release(held.key, fn("close")) { instance =>
            instance
              .call(
                fn("close"),
                Passivate(init.componentId, init.entityId).toByteArray,
                purposeOf(None, Metadata.empty)
              )
              .map(_ => ())
          }
        }: Unit

  // ── Stateless calls, each on a fresh instance ──────────────────────────────

  private def fresh[A](
      function: String,
      request: GeneratedMessage,
      timeout: FiniteDuration,
      purpose: Purpose
  )(parse: Array[Byte] => A): Future[Either[GuestFault, A]] =
    Future(
      blocking.withFresh(fn(function), timeout)(ask(_, fn(function), request, purpose)(parse))
    )

  private def orFail[A](answered: Future[Either[GuestFault, A]]): Future[A] =
    answered.flatMap {
      case Right(a) => Future.successful(a)
      case Left(fault) =>
        Future.failed(
          CommandError(
            s"the module failed in ${fault.function}: ${fault.message}",
            ErrorCode.Internal
          )
        )
    }

  def handleView(request: ViewRequest): Future[ViewOutcome] =
    val stampedRequest = request.copy(metadata = stamped(request.metadata))
    orFail(
      fresh(
        "view",
        toViewRequest(stampedRequest),
        settings.commandTimeout,
        Purpose(request.componentId.toString, None, stampedRequest.metadata)
      )(ViewEffect.parseFrom)
    ).map(fromViewEffect)

  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome] =
    val stampedRequest = request.copy(metadata = stamped(request.metadata))
    orFail(
      fresh(
        "consumer",
        toConsumerRequest(stampedRequest),
        settings.commandTimeout,
        Purpose(request.componentId.toString, None, stampedRequest.metadata)
      )(ConsumerEffect.parseFrom)
    ).map(fromConsumerEffect)

  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]] =
    val stampedRequest = request.copy(metadata = stamped(request.metadata))
    orFail(
      fresh(
        "timed_action",
        toTimedActionRequest(stampedRequest),
        settings.commandTimeout,
        Purpose(request.componentId.toString, Some(request.name.toString), stampedRequest.metadata)
      )(TimedActionEffect.parseFrom)
    ).map(fromTimedActionEffect)

  def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]] =
    val stampedRequest = request.copy(metadata = stamped(request.metadata))
    fresh(
      "plan",
      toPlanRequest(stampedRequest),
      settings.commandTimeout,
      Purpose(request.componentId.toString, Some(request.name.toString), stampedRequest.metadata)
    )(PlanReply.parseFrom).map {
      case Left(fault)  => Left(failure(0L, fault))
      case Right(reply) => fromPlanReply(reply)
    }

  def invokeTool(
      componentId: ComponentId,
      sessionId: String,
      tool: String,
      argumentsJson: String,
      metadata: Metadata
  ): Future[Either[String, String]] =
    val sent = stamped(metadata)
    val request =
      ToolRequest(componentId, sessionId, tool, argumentsJson, Some(Translate.toMetadata(sent)))
    orFail(
      fresh("invoke_tool", request, stepTime, Purpose(componentId.toString, Some(tool), sent))(
        ToolResult.parseFrom
      )
    ).map(fromToolResult)

  def checkGuardrail(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String,
      metadata: Metadata
  ): Future[Either[String, Unit]] =
    val sent = stamped(metadata)
    orFail(
      fresh(
        "check_guardrail",
        toGuardrailRequest(componentId, sessionId, guardrail, stage, text, sent),
        settings.commandTimeout,
        Purpose(componentId.toString, Some(guardrail), sent)
      )(GuardrailResult.parseFrom)
    ).map(fromGuardrailResult)

  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]] =
    val stampedRequest = request.copy(metadata = stamped(request.metadata))
    fresh(
      "http",
      toHttpRequest(stampedRequest),
      settings.requestTimeout,
      Purpose(request.endpointId, Some(request.routeId), stampedRequest.metadata)
    )(HttpReply.parseFrom).map {
      case Left(fault)  => Left(failure(0L, fault))
      case Right(reply) => fromHttpReply(reply)
    }

  /**
   * An autonomous agent's result, decoded as its task type's and held to the type's rules, by the
   * module on a fresh instance. A rule that traps fails the future: that decided nothing, and the
   * agent treats it as a failed iteration rather than a verdict.
   */
  def checkTaskResult(
      componentId: ComponentId,
      taskId: String,
      taskType: String,
      resultJson: String,
      metadata: Metadata
  ): Future[TaskResultVerdict] =
    val sent = stamped(metadata)
    orFail(
      fresh(
        "check_task_result",
        TaskResultRequest(
          componentId,
          taskId,
          taskType,
          resultJson,
          Some(Translate.toMetadata(sent))
        ),
        settings.commandTimeout,
        Purpose(componentId.toString, Some(taskType), sent)
      )(PbTaskResultVerdict.parseFrom)
    ).map(fromTaskResultVerdict)

  /** Refused at discovery: a module answers a request whole, so no route of one streams. */
  def handleHttpStream(request: HttpForward): Source[String, NotUsed] =
    Source.failed(ProtocolViolation("a module has no streaming routes"))

  /** Refused at discovery: a module answers a request whole, so it cannot hold a socket. */
  override def openSocket(request: HttpForward): SocketLink =
    throw ProtocolViolation("a module cannot hold a socket")

  /** The module is in this process: if the runtime is answering, so is it. */
  def reachable(): Boolean = true

object WasmConversation:
  /** The metadata entry carrying the runtime's clock, as epoch milliseconds. */
  val Now: String = "ankka.now"

  /** On a command: the journal sequence of the state the guest is handed. */
  val Sequence: String = "ankka.sequence"
