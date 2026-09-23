package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.agent.{
  AgentGrpc,
  AgentPlan,
  GuardrailRequest,
  GuardrailResult,
  PlanReply,
  PlanRequest as PbPlanRequest,
  ToolRequest,
  ToolResult
}
import ankka.protocol.v1.discovery.{DiscoveryGrpc, SidecarInfo}
import ankka.protocol.v1.consumer.{
  ConsumerEffect,
  ConsumerGrpc,
  ConsumerRequest as PbConsumerRequest
}
import ankka.protocol.v1.endpoint.{HttpGrpc, HttpReply, HttpRequest as PbHttpRequest, StreamFrame}
import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.key_value.{KeyValueGrpc, KeyValueIn, KeyValueOut}
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.timed_action.{
  TimedActionEffect,
  TimedActionGrpc,
  TimedActionRequest as PbTimedActionRequest
}
import ankka.protocol.v1.view.{ViewEffect, ViewGrpc, ViewRequest as PbViewRequest}
import ankka.protocol.v1.workflow.{
  StepOutcome as PbStepOutcome,
  StepRef as PbStepRef,
  WorkflowGrpc,
  WorkflowIn,
  WorkflowOut
}
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.effect.{Retention, StepOutcome, StepRef}
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  ComponentKind,
  ErrorCode,
  Metadata
}
import com.thinkmorestupidless.ankka.runtime.remote.*
import io.grpc.stub.StreamObserver
import io.grpc.ManagedChannel
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.QueueOfferResult
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/**
 * The `Conversation` a remote host speaks, over grpc-java to the developer's process.
 *
 * Every stateful session is one bidirectional stream. Exactly one command is in flight per session
 * — the host promises that and this class relies on it: a reply completes the one pending promise,
 * whatever its `command_id` says, and the host's `RemoteEffect.materialise` decides whether the id
 * was right. A reply with nothing pending is logged and dropped: that is a late reply after a
 * timeout, which the protocol says to ignore.
 */
final class GrpcConversation(
    channel: ManagedChannel,
    settings: Settings
)(using ec: ExecutionContext)
    extends Conversation:

  private val log = LoggerFactory.getLogger(getClass)
  // Timeouts on a scheduler of its own: the conversation exists before any ActorSystem does.
  private val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "ankka-conversation-timeouts")
    t.setDaemon(true)
    t
  }
  private val eventSourced = EventSourcedGrpc.stub(channel)
  private val keyValue     = KeyValueGrpc.stub(channel)
  private val workflow     = WorkflowGrpc.stub(channel)
  private val view         = ViewGrpc.stub(channel)
  private val consumer     = ConsumerGrpc.stub(channel)
  private val timedAction  = TimedActionGrpc.stub(channel)
  private val http         = HttpGrpc.stub(channel)
  private val agent        = AgentGrpc.stub(channel)
  private val discovery    = DiscoveryGrpc.stub(channel)

  import Translate.*

  // ── Sessions ───────────────────────────────────────────────────────────────

  /** What a session's inbound observer completes: one pending command or step at a time. */
  private final class Pending(
      val id: Long,
      val reply: Promise[Either[ProcessFailure, Reply]],
      val step: Promise[Either[ProcessFailure, StepReply]]
  )

  private def complete[A](p: Promise[A], value: A): Unit =
    val _ = p.trySuccess(value)

  private def timeout(
      pending: AtomicReference[Option[Pending]],
      id: Long,
      what: String,
      onTimeout: () => Unit
  ): Unit =
    val _ = scheduler.schedule(
      (
          () =>
            pending.get() match
              case Some(p) if p.id == id =>
                val failure = ProcessFailure(
                  id,
                  CommandError(
                    s"$what $id: no reply from the process within ${settings.commandTimeout}",
                    ErrorCode.Timeout
                  )
                )
                if p.reply.trySuccess(Left(failure)) || p.step.trySuccess(Left(failure)) then
                  pending.set(None)
                  onTimeout()
              case _ => ()
      ): Runnable,
      settings.commandTimeout.toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )

  def open(init: Init): InstanceSession =
    val pending = new AtomicReference[Option[Pending]](None)
    // A workflow's stream carries commands and steps, and the engine keeps answering commands
    // while a step runs — a query during a long step is the point of steps being asynchronous. So
    // one command *and* one step may be in flight at once, each tracked on its own.
    val pendingStep      = new AtomicReference[Option[Pending]](None)
    @volatile var closed = false

    def failPending(error: CommandError): Unit =
      (pending.getAndSet(None) ++ pendingStep.getAndSet(None)).foreach { p =>
        val f = ProcessFailure(p.id, error)
        complete(p.reply, Left(f))
        complete(p.step, Left(f))
      }

    def dropped(what: String, id: Long): Unit =
      log.warn(
        "{}/{}: {} for command {} arrived with nothing in flight; dropped (a late reply)",
        init.componentId,
        init.entityId,
        what,
        id
      )

    init.kind match
      case ComponentKind.EventSourcedEntity =>
        val out = eventSourced.handle(new StreamObserver[EventSourcedOut]:
          def onNext(o: EventSourcedOut): Unit = o.message match
            case EventSourcedOut.Message.Reply(r) =>
              pending.getAndSet(None) match
                case Some(p) => complete(p.reply, Right(fromEventSourcedReply(r)))
                case None    => dropped("reply", r.commandId)
            case EventSourcedOut.Message.Failure(f) =>
              pending.getAndSet(None) match
                case Some(p) => complete(p.reply, Left(fromFailure(f)))
                case None    => dropped("failure", f.commandId)
            case EventSourcedOut.Message.Empty => ()
          def onError(t: Throwable): Unit =
            closed = true
            failPending(
              CommandError(
                s"the process closed the conversation: ${t.getMessage}",
                ErrorCode.Unavailable
              )
            )
          def onCompleted(): Unit =
            closed = true
            failPending(CommandError("the process ended the conversation", ErrorCode.Unavailable)))
        out.onNext(
          EventSourcedIn(
            EventSourcedIn.Message.Init(
              EventSourcedIn.Init(
                init.componentId,
                init.entityId,
                init.snapshot.map(s =>
                  EventSourcedIn.Snapshot(s.sequence, Some(toPayload(s.payload)))
                )
              )
            )
          )
        )
        new InstanceSession:
          def event(sequence: Long, payload: Payload): Unit =
            out.onNext(
              EventSourcedIn(
                EventSourcedIn.Message.Event(
                  EventSourcedIn.Event(sequence, Some(toPayload(payload)))
                )
              )
            )
          def command(cmd: Command): Future[Either[ProcessFailure, Reply]] =
            if closed then
              Future.successful(
                Left(
                  ProcessFailure(cmd.id, CommandError("conversation closed", ErrorCode.Unavailable))
                )
              )
            else
              val p = new Pending(cmd.id, Promise(), Promise())
              if !pending.compareAndSet(None, Some(p)) then
                Future.failed(
                  ProtocolViolation(s"command ${cmd.id} sent while another was in flight")
                )
              else
                timeout(pending, cmd.id, "command", () => close())
                out.onNext(
                  EventSourcedIn(
                    EventSourcedIn.Message.Command(
                      EventSourcedIn.Command(
                        cmd.id,
                        cmd.name,
                        Some(toPayload(cmd.payload)),
                        Some(toMetadata(cmd.metadata)),
                        cmd.snapshotRequested
                      )
                    )
                  )
                )
                p.reply.future
          def runStep(id: Long, step: String, input: Option[Array[Byte]]) =
            Future.failed(ProtocolViolation("an event sourced entity has no steps"))
          def close(): Unit =
            if !closed then
              closed = true
              try out.onCompleted()
              catch case NonFatal(_) => ()
              failPending(CommandError("conversation closed", ErrorCode.Unavailable))

      case ComponentKind.KeyValueEntity =>
        val out = keyValue.handle(new StreamObserver[KeyValueOut]:
          def onNext(o: KeyValueOut): Unit = o.message match
            case KeyValueOut.Message.Reply(r) =>
              pending.getAndSet(None) match
                case Some(p) => complete(p.reply, Right(fromKeyValueReply(r)))
                case None    => dropped("reply", r.commandId)
            case KeyValueOut.Message.Failure(f) =>
              pending.getAndSet(None) match
                case Some(p) => complete(p.reply, Left(fromFailure(f)))
                case None    => dropped("failure", f.commandId)
            case KeyValueOut.Message.Empty => ()
          def onError(t: Throwable): Unit =
            closed = true
            failPending(
              CommandError(
                s"the process closed the conversation: ${t.getMessage}",
                ErrorCode.Unavailable
              )
            )
          def onCompleted(): Unit =
            closed = true
            failPending(CommandError("the process ended the conversation", ErrorCode.Unavailable)))
        out.onNext(
          KeyValueIn(
            KeyValueIn.Message.Init(
              KeyValueIn.Init(
                init.componentId,
                init.entityId,
                init.snapshot.map(s => toPayload(s.payload))
              )
            )
          )
        )
        new InstanceSession:
          def event(sequence: Long, payload: Payload): Unit =
            throw ProtocolViolation("a key value entity has no events to replay")
          def command(cmd: Command): Future[Either[ProcessFailure, Reply]] =
            if closed then
              Future.successful(
                Left(
                  ProcessFailure(cmd.id, CommandError("conversation closed", ErrorCode.Unavailable))
                )
              )
            else
              val p = new Pending(cmd.id, Promise(), Promise())
              if !pending.compareAndSet(None, Some(p)) then
                Future.failed(
                  ProtocolViolation(s"command ${cmd.id} sent while another was in flight")
                )
              else
                timeout(pending, cmd.id, "command", () => close())
                out.onNext(
                  KeyValueIn(
                    KeyValueIn.Message.Command(
                      KeyValueIn.Command(
                        cmd.id,
                        cmd.name,
                        Some(toPayload(cmd.payload)),
                        Some(toMetadata(cmd.metadata))
                      )
                    )
                  )
                )
                p.reply.future
          def runStep(id: Long, step: String, input: Option[Array[Byte]]) =
            Future.failed(ProtocolViolation("a key value entity has no steps"))
          def close(): Unit =
            if !closed then
              closed = true
              try out.onCompleted()
              catch case NonFatal(_) => ()
              failPending(CommandError("conversation closed", ErrorCode.Unavailable))

      case ComponentKind.Workflow =>
        val out = workflow.handle(new StreamObserver[WorkflowOut]:
          def onNext(o: WorkflowOut): Unit = o.message match
            case WorkflowOut.Message.Reply(r) =>
              pending.getAndSet(None) match
                case Some(p) => complete(p.reply, Right(fromWorkflowReply(r)))
                case None    => dropped("reply", r.commandId)
            case WorkflowOut.Message.StepReply(r) =>
              pendingStep.getAndSet(None) match
                case Some(p) => complete(p.step, Right(fromStepReply(r)))
                case None    => dropped("step reply", r.commandId)
            case WorkflowOut.Message.Failure(f) =>
              // A failure names its command id, which says whether a command or a step failed.
              (pending.get(), pendingStep.get()) match
                case (Some(p), _) if p.id == f.commandId =>
                  pending.set(None)
                  complete(p.reply, Left(fromFailure(f)))
                case (_, Some(p)) if p.id == f.commandId =>
                  pendingStep.set(None)
                  complete(p.step, Left(fromFailure(f)))
                case _ => dropped("failure", f.commandId)
            case WorkflowOut.Message.Empty => ()
          def onError(t: Throwable): Unit =
            closed = true
            failPending(
              CommandError(
                s"the process closed the conversation: ${t.getMessage}",
                ErrorCode.Unavailable
              )
            )
          def onCompleted(): Unit =
            closed = true
            failPending(CommandError("the process ended the conversation", ErrorCode.Unavailable)))
        out.onNext(
          WorkflowIn(
            WorkflowIn.Message.Init(
              WorkflowIn.Init(
                init.componentId,
                init.entityId,
                init.snapshot.map(s => toPayload(s.payload))
              )
            )
          )
        )
        new InstanceSession:
          def event(sequence: Long, payload: Payload): Unit =
            throw ProtocolViolation("a workflow has no events to replay")
          def command(cmd: Command): Future[Either[ProcessFailure, Reply]] =
            if closed then
              Future.successful(
                Left(
                  ProcessFailure(cmd.id, CommandError("conversation closed", ErrorCode.Unavailable))
                )
              )
            else
              val p = new Pending(cmd.id, Promise(), Promise())
              if !pending.compareAndSet(None, Some(p)) then
                Future.failed(
                  ProtocolViolation(s"command ${cmd.id} sent while another was in flight")
                )
              else
                timeout(pending, cmd.id, "command", () => close())
                out.onNext(
                  WorkflowIn(
                    WorkflowIn.Message.Command(
                      WorkflowIn.Command(
                        cmd.id,
                        cmd.name,
                        Some(toPayload(cmd.payload)),
                        Some(toMetadata(cmd.metadata))
                      )
                    )
                  )
                )
                p.reply.future
          def runStep(id: Long, step: String, input: Option[Array[Byte]]) =
            if closed then
              Future.successful(
                Left(ProcessFailure(id, CommandError("conversation closed", ErrorCode.Unavailable)))
              )
            else
              val p = new Pending(id, Promise(), Promise())
              if !pendingStep.compareAndSet(None, Some(p)) then
                Future.failed(
                  ProtocolViolation(s"step $step sent while another step was in flight")
                )
              else
                // A step's own timeout is the engine's; this one only guards a process that vanished.
                out.onNext(
                  WorkflowIn(
                    WorkflowIn.Message.RunStep(
                      WorkflowIn.RunStep(id, step, input.map(pb.Payload.parseFrom))
                    )
                  )
                )
                p.step.future
          def close(): Unit =
            if !closed then
              closed = true
              try out.onCompleted()
              catch case NonFatal(_) => ()
              failPending(CommandError("conversation closed", ErrorCode.Unavailable))

      case other =>
        throw ProtocolViolation(s"$other is not a stateful kind and has no conversation")
  end open

  // ── Stateless conversations ─────────────────────────────────────────────────

  def handleView(request: ViewRequest): Future[ViewOutcome] =
    view
      .handle(
        PbViewRequest(
          componentId = request.componentId,
          event = request.event.map(toPayload),
          metadata = Some(toMetadata(request.metadata)),
          row = request.row.map(toPayload),
          deleted = request.event.isEmpty
        )
      )
      .map { effect =>
        effect.effect match
          case ViewEffect.Effect.UpdateRow(row) => ViewOutcome.UpdateRow(fromPayload(row))
          case ViewEffect.Effect.DeleteRow(_)   => ViewOutcome.DeleteRow
          case ViewEffect.Effect.Ignore(_)      => ViewOutcome.Ignore
          case ViewEffect.Effect.Empty          => ViewOutcome.Ignore
      }

  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome] =
    consumer
      .handle(
        PbConsumerRequest(
          componentId = request.componentId,
          message = request.message.map(toPayload),
          metadata = Some(toMetadata(request.metadata)),
          deleted = request.message.isEmpty
        )
      )
      .map { effect =>
        effect.effect match
          case ConsumerEffect.Effect.Produce(p) =>
            ConsumerOutcome.Produce(fromPayload(p.getPayload), fromMetadata(p.metadata))
          case ConsumerEffect.Effect.Done(_)   => ConsumerOutcome.Done
          case ConsumerEffect.Effect.Ignore(_) => ConsumerOutcome.Ignore
          case ConsumerEffect.Effect.Empty     => ConsumerOutcome.Ignore
      }

  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]] =
    timedAction
      .invoke(
        PbTimedActionRequest(
          request.componentId,
          request.name,
          Some(
            if request.payload.isEmpty then pb.Payload() else pb.Payload.parseFrom(request.payload)
          ),
          Some(toMetadata(request.metadata))
        )
      )
      .map { effect =>
        effect.effect match
          case TimedActionEffect.Effect.Fail(e) => Left(fromError(e))
          case _                                => Right(())
      }

  // ── Agents ──────────────────────────────────────────────────────────────────

  def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]] =
    agent
      .plan(
        PbPlanRequest(
          request.componentId,
          request.sessionId,
          request.name,
          Some(toPayload(request.payload)),
          Some(toMetadata(request.metadata))
        )
      )
      .map { reply =>
        reply.message match
          case PlanReply.Message.Plan(p)    => Right(fromPlan(p))
          case PlanReply.Message.Failure(f) => Left(fromFailure(f))
          case PlanReply.Message.Empty =>
            Left(
              ProcessFailure(0L, CommandError("the process answered no plan", ErrorCode.Internal))
            )
      }

  private def fromPlan(p: AgentPlan): RemotePlan =
    RemotePlan(
      model = p.model,
      system = p.system,
      user = p.user,
      context = p.context.toVector,
      sessionMemory = p.memory == AgentPlan.Memory.SESSION,
      tools = p.tools.toVector,
      jsonShape = p.responseShape.flatMap(_.shape.json).map(_.schemaHint),
      guardrails = p.guardrails.toVector,
      failure = p.failure.map(fromError)
    )

  def invokeTool(
      componentId: ComponentId,
      sessionId: String,
      tool: String,
      argumentsJson: String
  ): Future[Either[String, String]] =
    agent.invokeTool(ToolRequest(componentId, sessionId, tool, argumentsJson)).map { result =>
      result.result match
        case ToolResult.Result.Ok(text)    => Right(text)
        case ToolResult.Result.Error(text) => Left(text)
        case ToolResult.Result.Empty       => Right("")
    }

  def checkGuardrail(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String
  ): Future[Either[String, Unit]] =
    agent
      .checkGuardrail(
        GuardrailRequest(
          componentId,
          sessionId,
          guardrail,
          stage match
            case GuardrailStage.Input  => GuardrailRequest.Stage.INPUT
            case GuardrailStage.Output => GuardrailRequest.Stage.OUTPUT
          ,
          text
        )
      )
      .map { result =>
        result.result match
          case GuardrailResult.Result.Block(reason) => Left(reason)
          case _                                    => Right(())
      }

  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]] =
    http.handle(toHttpRequest(request)).map { reply =>
      reply.message match
        case HttpReply.Message.Response(r) =>
          Right(
            HttpResult(
              r.status,
              r.contentType,
              r.body.toByteArray,
              r.headers.map(p => p.name -> p.value).toVector
            )
          )
        case HttpReply.Message.Failure(f) => Left(fromFailure(f))
        case HttpReply.Message.Empty =>
          Left(
            ProcessFailure(
              0L,
              CommandError("empty HTTP reply from the process", ErrorCode.Internal)
            )
          )
    }

  def handleHttpStream(request: HttpForward): Source[String, NotUsed] =
    // The gRPC call starts when the stream is materialized, so no materializer is needed here and
    // nothing is sent to the process for a response nobody consumes.
    Source
      .queue[String](256)
      .mapMaterializedValue { queue =>
        http.handleStream(
          toHttpRequest(request),
          new StreamObserver[StreamFrame]:
            // The process says `completed` in its last frame and then ends the call, so the
            // queue is completed twice; the second time is not an error.
            @volatile private var done = false
            private def finish(): Unit =
              if !done then
                done = true
                queue.complete()
            private def fail(t: Throwable): Unit =
              if !done then
                done = true
                queue.fail(t)
            def onNext(frame: StreamFrame): Unit = frame.frame match
              case StreamFrame.Frame.Text(text) =>
                queue.offer(text) match
                  case QueueOfferResult.Enqueued => ()
                  case other                     => log.warn("SSE frame dropped: {}", other)
              case StreamFrame.Frame.Completed(_) => finish()
              case StreamFrame.Frame.Failed(e)    => fail(fromError(e))
              case StreamFrame.Frame.Empty        => ()
            def onError(t: Throwable): Unit = fail(t)
            def onCompleted(): Unit         = finish()
        )
        NotUsed
      }

  /**
   * A real round trip with a short deadline, not the channel's state: a process that is frozen or
   * wedged keeps its TCP connection and would read as READY forever. `Discover` is the cheapest
   * thing every process answers.
   */
  def reachable(): Boolean =
    try
      Await.result(
        discovery
          .withDeadlineAfter(1, java.util.concurrent.TimeUnit.SECONDS)
          .discover(SidecarInfo(Discovery.ProtocolVersion, "")),
        1500.millis
      )
      true
    catch case NonFatal(_) => false

  // ── Translation ─────────────────────────────────────────────────────────────

  private object Translate:
    def toPayload(p: Payload): pb.Payload =
      pb.Payload(p.contentType, p.manifest, ByteString.copyFrom(p.data))
    def fromPayload(p: pb.Payload): Payload = Payload(p.contentType, p.manifest, p.data.toByteArray)
    def toMetadata(m: Metadata): pb.Metadata =
      pb.Metadata(m.toSeq.map((k, v) => pb.Metadata.Entry(k, v)))
    def fromMetadata(m: Option[pb.Metadata]): Metadata =
      Metadata(m.toSeq.flatMap(_.entries).map(e => e.key -> e.value).toVector)
    def fromCode(code: pb.ErrorCode): ErrorCode = code match
      case pb.ErrorCode.BAD_REQUEST  => ErrorCode.BadRequest
      case pb.ErrorCode.UNAUTHORIZED => ErrorCode.Unauthorized
      case pb.ErrorCode.FORBIDDEN    => ErrorCode.Forbidden
      case pb.ErrorCode.NOT_FOUND    => ErrorCode.NotFound
      case pb.ErrorCode.CONFLICT     => ErrorCode.Conflict
      case pb.ErrorCode.TIMEOUT      => ErrorCode.Timeout
      case pb.ErrorCode.UNAVAILABLE  => ErrorCode.Unavailable
      case _                         => ErrorCode.Internal
    def fromError(e: pb.Error): CommandError = CommandError(e.message, fromCode(e.code))
    def fromFailure(f: pb.Failure): ProcessFailure =
      ProcessFailure(
        f.commandId,
        f.error.map(fromError).getOrElse(CommandError("unspecified failure", ErrorCode.Internal))
      )
    def fromRetention(r: Option[pb.Retention]): Option[Retention] = r.flatMap { r =>
      r.retention match
        case pb.Retention.Retention.DeleteNow(_)   => Some(Retention.DeleteNow)
        case pb.Retention.Retention.ExpireAfter(e) => Some(Retention.ExpireAfter(e.millis.millis))
        case pb.Retention.Retention.Empty          => None
    }
    def fromOutcome(o: Option[pb.Outcome]): RemoteOutcome = o.map(_.outcome) match
      case Some(pb.Outcome.Outcome.Reply(r)) =>
        RemoteOutcome.Reply(
          r.payload
            .map(fromPayload)
            .getOrElse(Payload(Payload.Binary, "unit", Array.emptyByteArray)),
          fromMetadata(r.metadata)
        )
      case Some(pb.Outcome.Outcome.Error(e)) => RemoteOutcome.Error(fromError(e))
      case _                                 => RemoteOutcome.NoReply
    def fromStepRef(s: PbStepRef): StepOutcome.TransitionTo =
      StepOutcome.TransitionTo(StepRef(s.step, s.input.map(_.toByteArray)))
    def fromStepOutcome(o: Option[PbStepOutcome]): StepOutcome = o.map(_.outcome) match
      case Some(PbStepOutcome.Outcome.TransitionTo(s)) => fromStepRef(s)
      case Some(PbStepOutcome.Outcome.Pause(p)) =>
        StepOutcome.Pause(
          p.afterMillis.map(_.millis),
          p.onTimeout.map(s => StepRef(s.step, s.input.map(_.toByteArray)))
        )
      case Some(PbStepOutcome.Outcome.Fail(e)) => StepOutcome.Fail(fromError(e))
      case _                                   => StepOutcome.End
    def fromEventSourcedReply(r: EventSourcedOut.Reply): Reply =
      Reply(
        r.commandId,
        r.events.map(fromPayload).toVector,
        None,
        None,
        fromRetention(r.retention),
        fromOutcome(r.outcome),
        r.snapshot.map(fromPayload)
      )
    def fromKeyValueReply(r: KeyValueOut.Reply): Reply =
      Reply(
        r.commandId,
        Vector.empty,
        r.newState.map(fromPayload),
        None,
        fromRetention(r.retention),
        fromOutcome(r.outcome),
        None
      )
    def fromWorkflowReply(r: WorkflowOut.Reply): Reply =
      Reply(
        r.commandId,
        Vector.empty,
        r.newState.map(fromPayload),
        r.transition.map(fromStepRef),
        None,
        fromOutcome(r.outcome),
        None
      )
    def fromStepReply(r: WorkflowOut.StepReply): StepReply =
      StepReply(r.commandId, r.newState.map(fromPayload), fromStepOutcome(r.next))
    def toHttpRequest(r: HttpForward): PbHttpRequest =
      PbHttpRequest(
        r.endpointId,
        r.routeId,
        r.pathArgs,
        r.query.map((n, v) => PbHttpRequest.Pair(n, v)),
        r.headers.map((n, v) => PbHttpRequest.Pair(n, v)),
        r.contentType,
        ByteString.copyFrom(r.body),
        r.principal.map(p =>
          ankka.protocol.v1.endpoint
            .Principal(p.subject, p.name, p.email, p.emailVerified, p.roles.toSeq)
        ),
        Some(toMetadata(r.metadata))
      )
  end Translate

  /**
   * The step input a workflow journals is the whole `Payload`, so manifest and content type
   * survive.
   */
  private def toPayload(p: Payload): pb.Payload = Translate.toPayload(p)
end GrpcConversation
