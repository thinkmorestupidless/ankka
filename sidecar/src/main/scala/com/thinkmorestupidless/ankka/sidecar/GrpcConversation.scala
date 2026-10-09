package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.agent.{
  AgentGrpc,
  GuardrailResult,
  PlanReply,
  TaskResultRequest,
  ToolRequest,
  ToolResult
}
import ankka.protocol.v1.discovery.{DiscoveryGrpc, SidecarInfo}
import ankka.protocol.v1.erasure.{ErasureGrpc, ErasureHandleReply, ErasureHandleRequest}
import ankka.protocol.v1.consumer.{ConsumerEffect, ConsumerGrpc}
import ankka.protocol.v1.endpoint.{
  HttpGrpc,
  HttpReply,
  SocketClosed as PbSocketClosed,
  SocketFrame,
  SocketIn,
  SocketOut,
  StreamFrame
}
import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.key_value.{KeyValueGrpc, KeyValueIn, KeyValueOut}
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.timed_action.{TimedActionEffect, TimedActionGrpc}
import ankka.protocol.v1.view.{ViewEffect, ViewGrpc}
import ankka.protocol.v1.workflow.{WorkflowGrpc, WorkflowIn, WorkflowOut}
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
  private val erasure      = ErasureGrpc.stub(channel)

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
          def runStep(id: Long, step: String, input: Option[Array[Byte]], metadata: Metadata) =
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
          def runStep(id: Long, step: String, input: Option[Array[Byte]], metadata: Metadata) =
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
          def runStep(id: Long, step: String, input: Option[Array[Byte]], metadata: Metadata) =
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
                      WorkflowIn.RunStep(
                        id,
                        step,
                        input.map(pb.Payload.parseFrom),
                        Some(Translate.toMetadata(metadata))
                      )
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
    view.handle(toViewRequest(request)).map(fromViewEffect)

  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome] =
    consumer.handle(toConsumerRequest(request)).map(fromConsumerEffect)

  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]] =
    timedAction.invoke(toTimedActionRequest(request)).map(fromTimedActionEffect)

  // ── Erasure (protocol 1.15) ─────────────────────────────────────────────────

  override def erase(
      subject: String,
      erasureId: String,
      reapply: Boolean,
      metadata: Metadata
  ): Future[com.thinkmorestupidless.ankka.sdk.ErasureOutcome] =
    erasure
      .handle(ErasureHandleRequest(subject, erasureId, reapply, metadata.toSeq.toMap))
      .map(GrpcConversation.erasureOutcome)

  // ── Agents ──────────────────────────────────────────────────────────────────

  def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]] =
    agent.plan(toPlanRequest(request)).map(fromPlanReply)

  def invokeTool(
      componentId: ComponentId,
      sessionId: String,
      tool: String,
      argumentsJson: String,
      metadata: Metadata
  ): Future[Either[String, String]] =
    agent
      .invokeTool(
        ToolRequest(
          componentId,
          sessionId,
          tool,
          argumentsJson,
          Some(Translate.toMetadata(metadata))
        )
      )
      .map(fromToolResult)

  def checkGuardrail(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String,
      metadata: Metadata
  ): Future[Either[String, Unit]] =
    agent
      .checkGuardrail(toGuardrailRequest(componentId, sessionId, guardrail, stage, text, metadata))
      .map(fromGuardrailResult)

  def checkTaskResult(
      componentId: ComponentId,
      taskId: String,
      taskType: String,
      resultJson: String,
      metadata: Metadata
  ): Future[TaskResultVerdict] =
    agent
      .checkTaskResult(
        TaskResultRequest(componentId, taskId, taskType, resultJson, Some(toMetadata(metadata)))
      )
      .map(fromTaskResultVerdict)

  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]] =
    http.handle(toHttpRequest(request)).map(fromHttpReply)

  def handleHttpStream(request: HttpForward): Source[String, NotUsed] =
    handleHttpEvents(request).collect { case StreamPart.Text(text) => text }

  override def handleHttpEvents(request: HttpForward): Source[StreamPart, NotUsed] =
    // The gRPC call starts when the stream is materialized, so no materializer is needed here and
    // nothing is sent to the process for a response nobody consumes.
    Source
      .queue[StreamPart](256)
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
              case StreamFrame.Frame.Text(text) => offer(StreamPart.Text(text))
              case StreamFrame.Frame.Event(event) =>
                offer(StreamPart.Event(event.name, event.data))
              case StreamFrame.Frame.Completed(_) => finish()
              case StreamFrame.Frame.Failed(e)    => fail(fromError(e))
              case StreamFrame.Frame.Empty        => ()
            private def offer(part: StreamPart): Unit =
              queue.offer(part) match
                case QueueOfferResult.Enqueued => ()
                case other                     => log.warn("SSE frame dropped: {}", other)
            def onError(t: Throwable): Unit = fail(t)
            def onCompleted(): Unit         = finish()
        )
        NotUsed
      }

  /**
   * A socket's call: `open` first, then the client's frames as the call can take them, then
   * `closed`. Frames are sent only when grpc-java says the call is ready, so a process that does
   * not read is not buffered for here; the client's frames wait in the socket's own bounded queue,
   * where its limit closes it.
   */
  override def openSocket(request: HttpForward): SocketLink =
    val outputs = java.util.concurrent.LinkedBlockingQueue[SocketOutput]()
    val lock    = Object()
    @volatile var ended: Option[SocketOutput]                               = None
    @volatile var closed                                                    = false
    @volatile var outbound: io.grpc.stub.ClientCallStreamObserver[SocketIn] = null

    def end(output: SocketOutput): Unit =
      val first = lock.synchronized {
        val was = ended.isEmpty
        if was then ended = Some(output)
        lock.notifyAll()
        was
      }
      if first then outputs.put(output)

    val observer = new io.grpc.stub.ClientResponseObserver[SocketIn, SocketOut]:
      def beforeStart(call: io.grpc.stub.ClientCallStreamObserver[SocketIn]): Unit =
        outbound = call
        call.setOnReadyHandler(() => lock.synchronized(lock.notifyAll()))
      def onNext(message: SocketOut): Unit = message.message match
        case SocketOut.Message.Frame(SocketFrame(SocketFrame.Kind.Text(text), _)) =>
          outputs.put(SocketOutput.Frame(text))
        case SocketOut.Message.Frame(_) =>
          end(SocketOutput.Failed("the process sent a frame of a kind this sidecar does not know"))
        case SocketOut.Message.Completed(_) => end(SocketOutput.Completed)
        case SocketOut.Message.Failed(e)    => end(SocketOutput.Failed(fromError(e).message))
        // A runtime reads a case it does not know as no case at all: a violation, never skipped.
        case SocketOut.Message.Empty =>
          end(SocketOutput.Failed("the process sent a message with no case set"))
      def onError(t: Throwable): Unit =
        end(SocketOutput.Failed(s"the process could not be reached: ${t.getMessage}"))
      def onCompleted(): Unit = end(SocketOutput.Completed)

    val requests = http.handleSocket(observer)
    lock.synchronized(requests.onNext(SocketIn(SocketIn.Message.Open(toSocketOpen(request)))))

    new SocketLink:
      def send(text: String): Boolean =
        lock.synchronized {
          while ended.isEmpty && !closed && outbound != null && !outbound.isReady do lock.wait(100)
          if ended.isDefined || closed then false
          else
            requests.onNext(
              SocketIn(SocketIn.Message.Frame(SocketFrame(SocketFrame.Kind.Text(text))))
            )
            true
        }

      def close(reason: String): Unit =
        lock.synchronized {
          if !closed then
            closed = true
            try
              if ended.isEmpty then
                requests.onNext(SocketIn(SocketIn.Message.Closed(PbSocketClosed(reason))))
              requests.onCompleted()
            catch case NonFatal(_) => () // the call has already ended
        }

      def next(within: FiniteDuration): Option[SocketOutput] =
        Option(outputs.poll(within.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) match
          case Some(output @ (SocketOutput.Completed | SocketOutput.Failed(_))) =>
            outputs.put(output) // every later read answers the same
            Some(output)
          case other => other

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

end GrpcConversation

object GrpcConversation:
  /** A process's or a module's answer from its erasure handler, as the runtime records it. */
  def erasureOutcome(reply: ErasureHandleReply): com.thinkmorestupidless.ankka.sdk.ErasureOutcome =
    import com.thinkmorestupidless.ankka.sdk.{ErasedObjects, ErasureOutcome}
    reply.outcome match
      case ErasureHandleReply.Outcome.Done(done) =>
        ErasureOutcome.Done(
          done.detail,
          done.objects.map(o =>
            ErasedObjects(o.count, java.time.Instant.ofEpochMilli(o.finalAtMillis))
          )
        )
      case ErasureHandleReply.Outcome.Failed(failed) => ErasureOutcome.Failed(failed.reason)
      case ErasureHandleReply.Outcome.Empty =>
        ErasureOutcome.Failed("the erasure handler answered nothing")
