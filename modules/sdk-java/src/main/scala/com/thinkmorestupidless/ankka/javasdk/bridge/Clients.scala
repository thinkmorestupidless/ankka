package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName,
  WorkflowEnd
}
import com.thinkmorestupidless.ankka.http
import com.thinkmorestupidless.ankka.javasdk.client.*
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.component.{EventSourcedEntity, Workflow}
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient as ScalaClient}

import java.time.Duration
import java.util.concurrent.{
  CompletionException,
  CompletionStage,
  Flow,
  SubmissionPublisher,
  TimeoutException
}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.jdk.OptionConverters.*

/** The Java component client over the Scala one's transport. */
private[ankka] object Clients:

  def of(scala: ScalaClient, refs: Refs): ComponentClient =
    JavaComponentClient(scala.transportRef, refs)

  /** The client of an instance built outside a host, which answers nothing. */
  val unavailable: ComponentClient = new ComponentClient:
    def forEventSourcedEntity(entityId: String): EntityRef = refuse
    def forKeyValueEntity(entityId: String): EntityRef     = refuse
    def forWorkflow(workflowId: String): WorkflowRef       = refuse
    private def refuse =
      throw IllegalStateException("this component was not built by a service or a test kit")

  /** A rejection or a failure as a Java caller sees it. */
  private[ankka] def javaFailure(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => javaFailure(e.getCause)
    case e: CommandError =>
      WorkflowEnd.failure(e) match
        case Some(f) =>
          CommandException(
            Effects.toProblem(e),
            WorkflowFailure(f.step.toJava, f.reason, f.deleted)
          )
        case None => CommandException(Effects.toProblem(e))
    case other => other

  /** A workflow's lifecycle as the Java record, read from the engine's answer. */
  private[bridge] val lifecycle: Serializer[Any] = new Serializer[Any]:
    def manifest(): String = "workflow-lifecycle"
    def toBytes(value: Any): Array[Byte] =
      throw UnsupportedOperationException("a workflow's lifecycle is only ever read")
    def fromBytes(bytes: Array[Byte]): Any =
      javaLifecycle(sdk.WorkflowLifecycle.serializer.fromBytes(bytes))

  /** A workflow's lifecycle as the Java record. */
  private[ankka] def javaLifecycle(l: sdk.WorkflowLifecycle): WorkflowLifecycle =
    WorkflowLifecycle(
      l.status,
      l.pendingStep.toJava,
      l.retries.view.mapValues(Int.box).toMap.asJava,
      l.failure.toJava
    )

  /** A copy's replication status as the Java record. */
  private[bridge] val replication: Serializer[Any] = new Serializer[Any]:
    def manifest(): String = "replication-status"
    def toBytes(value: Any): Array[Byte] =
      throw UnsupportedOperationException("a replication status is only ever read")
    def fromBytes(bytes: Array[Byte]): Any =
      val s = sdk.ReplicationStatus.serializer.fromBytes(bytes)
      ReplicationStatus(
        s.home.toJava,
        s.replicationSet.asJava,
        s.pendingHandover.toJava,
        s.conflict.toJava,
        s.position
      )

private[ankka] final class JavaComponentClient(
    private[ankka] val transport: CallTransport,
    private[ankka] val refs: Refs
) extends ComponentClient:
  def forEventSourcedEntity(entityId: String): EntityRef = JavaEntityRef(transport, refs, entityId)
  def forKeyValueEntity(entityId: String): EntityRef     = JavaEntityRef(transport, refs, entityId)
  def forWorkflow(workflowId: String): WorkflowRef =
    JavaWorkflowRef(JavaEntityRef(transport, refs, workflowId), transport, refs, workflowId)

private final class JavaEntityRef(transport: CallTransport, refs: Refs, entityId: String)
    extends EntityRef:

  def call[C, I, O](handler: CommandRef[C, I, O]): Invocation[I, O] =
    val (component, plan) = refs.resolve(handler)
    JavaInvocation(
      transport,
      Target(component.componentId, plan.wireName, plan.readOnly, plan.input, plan.output),
      entityId
    )

  def call[C, O](handler: NoArgRef[C, O]): Invocation[Void, O] =
    val (component, plan) = refs.resolve(handler)
    JavaInvocation(
      transport,
      Target(component.componentId, plan.wireName, plan.readOnly, None, plan.output),
      entityId
    )

  def replication(entity: Class[? <: EventSourcedEntity[?, ?]]): ReplicationStatus =
    val plan = eventSourced(entity)
    JavaInvocation[Void, ReplicationStatus](
      transport,
      Target(plan.componentId, sdk.ReplicationStatus.Method, true, None, Clients.replication),
      entityId
    ).invoke(null)

  def awaitHandover(
      entity: Class[? <: EventSourcedEntity[?, ?]],
      installation: String,
      timeout: Duration
  ): Unit =
    val plan = eventSourced(entity)
    try
      ScalaClient(transport)
        .forEventSourcedEntity(EntityId(entityId))
        .awaitHandover(ComponentId(plan.componentId), installation, Effects.finite(timeout))
    catch case t: Throwable => throw Clients.javaFailure(t)

  private def eventSourced(entity: Class[?]): ComponentPlan =
    refs
      .plan(entity)
      .filter(_.kind == JavaKind.EventSourced)
      .getOrElse(
        throw CallerError(
          s"${entity.getSimpleName} is not an event sourced entity this service registers"
        )
      )

  def call[I, O](
      componentId: String,
      wireName: String,
      input: Class[I],
      output: Class[O]
  ): Invocation[I, O] =
    val in =
      if input == classOf[Void] then None
      else Some(RecordCodec.forType(input).asInstanceOf[Serializer[Any]])
    JavaInvocation(
      transport,
      Target(
        componentId,
        wireName,
        readOnly = false,
        in,
        RecordCodec.forType(output).asInstanceOf[Serializer[Any]]
      ),
      entityId
    )

private final class JavaWorkflowRef(
    calls: JavaEntityRef,
    transport: CallTransport,
    refs: Refs,
    workflowId: String
) extends WorkflowRef:
  export calls.{awaitHandover, call, replication}

  def awaitEnd[S](workflow: Class[? <: Workflow[S]], timeout: Duration): S =
    try
      ScalaClient
        .await(future(workflow, timeout), Effects.finite(timeout) + transport.askTimeout)
        .asInstanceOf[S]
    catch case t: Throwable => throw Clients.javaFailure(t)

  def awaitEndAsync[S](workflow: Class[? <: Workflow[S]], timeout: Duration): CompletionStage[S] =
    future(workflow, timeout)
      .transform(identity, Clients.javaFailure)(using ExecutionContext.parasitic)
      .asInstanceOf[Future[S]]
      .asJava

  def awaitEndEvents(
      workflow: Class[? <: Workflow[?]],
      timeout: Duration
  ): Flow.Publisher[SseEvent] =
    val plan               = workflowPlan(workflow)
    val wait               = Effects.finite(timeout)
    given ExecutionContext = ExecutionContext.parasitic
    val end: Future[http.SseEvent] =
      ended(plan, timeout)
        .map((bytes, metadata) => http.SseEvent.ended(http.EndpointClients.asJson(bytes, metadata)))
        .recover {
          case timedOut: CommandError if timedOut.code == ErrorCode.Timeout =>
            http.SseEvent.timedOut(wait)
          case refused: CommandError => http.SseEvent.failed(refused)
        }
    HeldEvents(end, http.Heartbeat.Default)

  def lifecycle(workflow: Class[? <: Workflow[?]]): Invocation[Void, WorkflowLifecycle] =
    val plan = workflowPlan(workflow)
    JavaInvocation(
      transport,
      Target(plan.componentId, sdk.WorkflowLifecycle.Method, true, None, Clients.lifecycle),
      workflowId
    )

  def callThenAwaitEnd[W <: Workflow[S], S, I, O](
      handler: CommandRef[W, I, O],
      timeout: Duration
  ): Invocation[I, S] =
    awaiting(refs.resolve(handler)._1, calls.call(handler), timeout)

  def callThenAwaitEnd[W <: Workflow[S], S, O](
      handler: NoArgRef[W, O],
      timeout: Duration
  ): Invocation[Void, S] =
    awaiting(refs.resolve(handler)._1, calls.call(handler), timeout)

  private def awaiting[I, S](
      plan: ComponentPlan,
      command: Invocation[I, ?],
      timeout: Duration
  ): Invocation[I, S] =
    if plan.kind != JavaKind.Workflow then
      throw CallerError(
        s"${plan.cls.getSimpleName} is not a workflow, so it has no end to wait for"
      )
    JavaAwaitingInvocation(
      command.asInstanceOf[JavaInvocation[I, Any]],
      workflowId,
      plan.state,
      Effects.finite(timeout)
    )

  private def workflowPlan(workflow: Class[?]): ComponentPlan =
    refs
      .plan(workflow)
      .filter(_.kind == JavaKind.Workflow)
      .getOrElse(
        throw CallerError(s"${workflow.getSimpleName} is not a workflow this service registers")
      )

  private def future(workflow: Class[?], timeout: Duration): Future[Any] =
    val plan = workflowPlan(workflow)
    ended(plan, timeout)
      .map((bytes, _) => plan.state.fromBytes(bytes))(using ExecutionContext.parasitic)

  private def ended(plan: ComponentPlan, timeout: Duration): Future[(Array[Byte], Metadata)] =
    if timeout.isNegative || timeout.isZero then
      Future.failed(
        CommandError(
          s"a wait for a workflow's end needs a timeout of more than zero, not $timeout",
          ErrorCode.BadRequest
        )
      )
    else
      transport
        .awaitEnd(
          ComponentId(plan.componentId),
          EntityId(workflowId),
          Effects.finite(timeout),
          Metadata.empty
        )

/** Where a call goes and how its input and reply cross. */
private final case class Target(
    componentId: String,
    wireName: String,
    readOnly: Boolean,
    input: Option[Serializer[Any]],
    output: Serializer[Any]
)

private final case class JavaInvocation[I, O](
    transport: CallTransport,
    target: Target,
    entityId: String,
    timeout: Option[FiniteDuration] = None,
    metadata: Metadata = Metadata.empty
) extends Invocation[I, O]:

  def invoke(input: I): O =
    try ComponentClientAwait(future(input), timeout.getOrElse(transport.askTimeout)).asInstanceOf[O]
    catch case t: Throwable => throw Clients.javaFailure(t)

  def invokeAsync(input: I): CompletionStage[O] =
    future(input)
      .transform(identity, Clients.javaFailure)(using ExecutionContext.parasitic)
      .asInstanceOf[Future[O]]
      .asJava

  def withTimeout(timeout: Duration): Invocation[I, O] =
    copy(timeout = Some(Effects.finite(timeout)))

  def withMetadata(key: String, value: String): Invocation[I, O] =
    copy(metadata = metadata.add(key, value))

  private[bridge] def future(input: I): Future[Any] =
    val bytes = target.input match
      case Some(serializer) => serializer.toBytes(input)
      case None             => Array.emptyByteArray
    transport
      .askHandler(
        target.readOnly,
        ComponentId(target.componentId),
        EntityId(entityId),
        MethodName(target.wireName),
        bytes,
        metadata
      )
      .map(target.output.fromBytes)(using ExecutionContext.parasitic)

/** A command to a workflow followed by a wait for its end, with one deadline from the send. */
private final case class JavaAwaitingInvocation[I, S](
    command: JavaInvocation[I, Any],
    workflowId: String,
    state: Serializer[Any],
    timeout: FiniteDuration
) extends Invocation[I, S]:

  private def scala: sdk.AwaitingInvocation[I, S] =
    sdk.AwaitingInvocation[I, S](
      command.transport,
      timeout,
      input => command.future(input),
      left =>
        command.transport
          .awaitEnd(
            ComponentId(command.target.componentId),
            EntityId(workflowId),
            left,
            command.metadata
          )
          .map((bytes, _) => state.fromBytes(bytes).asInstanceOf[S])(using
            ExecutionContext.parasitic
          )
    )

  def invoke(input: I): S =
    try scala.invoke(input)
    catch case t: Throwable => throw Clients.javaFailure(t)

  def invokeAsync(input: I): CompletionStage[S] =
    scala
      .invokeAsync(input)
      .transform(identity, Clients.javaFailure)(using ExecutionContext.parasitic)
      .asJava

  def withTimeout(timeout: Duration): Invocation[I, S] = copy(timeout = Effects.finite(timeout))

  def withMetadata(key: String, value: String): Invocation[I, S] =
    copy(command = command.copy(metadata = command.metadata.add(key, value)))

private object ComponentClientAwait:
  def apply(future: Future[Any], timeout: FiniteDuration): Any =
    ScalaClient.await(future, timeout)

/**
 * A wait for a workflow's end as events: a heartbeat while it goes on, then the one event that ends
 * it. A route that returns it serves `scala` as it is; anything else subscribing is served from a
 * virtual thread that waits on the end a heartbeat at a time.
 */
private[bridge] final class HeldEvents(end: Future[http.SseEvent], heartbeat: FiniteDuration)
    extends Flow.Publisher[SseEvent]:

  val scala: org.apache.pekko.stream.scaladsl.Source[http.SseEvent, org.apache.pekko.NotUsed] =
    org.apache.pekko.stream.scaladsl.Source
      .tick(heartbeat, heartbeat, http.SseEvent.heartbeat)
      .mapMaterializedValue(_ => org.apache.pekko.NotUsed)
      .merge(org.apache.pekko.stream.scaladsl.Source.future(end), eagerComplete = true)
      .takeWhile(event => !http.SseEvent.endsAWait(event), inclusive = true)

  def subscribe(subscriber: Flow.Subscriber[? >: SseEvent]): Unit =
    val publisher = SubmissionPublisher[SseEvent]()
    publisher.subscribe(subscriber)
    Thread
      .ofVirtual()
      .start { () =>
        try
          var last = Option.empty[http.SseEvent]
          while last.isEmpty do
            try last = Some(Await.result(end, heartbeat))
            catch
              case _: TimeoutException =>
                publisher.submit(HeldEvents.java(http.SseEvent.heartbeat)): Unit
          publisher.submit(HeldEvents.java(last.get)): Unit
          publisher.close()
        catch case t: Throwable => publisher.closeExceptionally(t)
      }: Unit

private[bridge] object HeldEvents:
  def java(event: http.SseEvent): SseEvent = SseEvent.named(event.name.get, event.data)
