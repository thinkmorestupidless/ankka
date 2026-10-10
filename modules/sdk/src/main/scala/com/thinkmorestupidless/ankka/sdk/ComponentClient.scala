package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * How a call actually reaches another component.
 *
 * The typed call machinery belongs in the SDK — developers write these calls inside handlers — but
 * routing needs cluster sharding, which the SDK must not depend on. This interface is the join:
 * `ankka-runtime` supplies the sharding-backed implementation.
 */
trait CallTransport:
  def ask(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]]

  /**
   * `ask`, for a handler that only reads (a query).
   *
   * Cluster sharding delivers at most once while an instance moves between nodes: a message that
   * reaches the old node's region after the hand-off began is dropped, and nobody answers. A query
   * changes nothing, so a transport may send one that went unanswered again, within the same
   * `askTimeout`; a command it may not, since an unanswered command may yet have run. Here, `ask`.
   */
  def askQuery(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] = ask(componentId, entityId, method, payload, metadata)

  /** `askQuery` for a read-only handler, `ask` for any other. */
  private[ankka] final def askHandler(
      readOnly: Boolean,
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    if readOnly then askQuery(componentId, entityId, method, payload, metadata)
    else ask(componentId, entityId, method, payload, metadata)

  /**
   * As `ask`, with the metadata the reply carried.
   *
   * A reply's metadata is how a handler says what kind of answer it gave without changing the
   * envelope every node reads — an agent turn that waits for approval answers this way. A transport
   * whose replies carry no metadata need not override it.
   */
  def askWithMetadata(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[(Array[Byte], Metadata)] =
    ask(componentId, entityId, method, payload, metadata)
      .map(bytes => (bytes, Metadata.empty))(using ExecutionContext.parasitic)

  /**
   * Sends a message without awaiting a reply.
   *
   * `Any` because the message types live in `ankka-runtime`, which the SDK must not depend on. Used
   * for streaming, where the reply arrives over time through a channel carried inside the message
   * rather than as a return value.
   */
  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit

  /** How long a blocking `invoke` waits before failing with `ErrorCode.Timeout`. */
  def askTimeout: FiniteDuration

  /**
   * Waits for the end of the workflow `entityId` of `componentId`, for at most `timeout`: its final
   * state's bytes, with the state's manifest and content type in the metadata. Fails with
   * `WorkflowFailed` when the workflow failed or was deleted, and with `Timeout` when `timeout`
   * passes first, the workflow running on. A transport that cannot wait refuses.
   */
  def awaitEnd(
      @scala.annotation.unused componentId: ComponentId,
      @scala.annotation.unused entityId: EntityId,
      @scala.annotation.unused timeout: FiniteDuration,
      @scala.annotation.unused metadata: Metadata
  ): Future[(Array[Byte], Metadata)] =
    Future.failed(
      CommandError("this transport cannot wait for a workflow's end", ErrorCode.Unavailable)
    )

/**
 * How components call each other.
 *
 * Calls go through a client rather than direct method calls because the target instance is very
 * likely on another node. What the client hides is the routing; what it deliberately does not hide
 * is that a call can fail, which is why a rejection arrives as a `CommandError` rather than as a
 * default value.
 */
final class ComponentClient(transport: CallTransport):

  /** Lets other ankka modules add their own component kinds — see `forAgent`. */
  private[ankka] def transportRef: CallTransport = transport

  def forEventSourcedEntity(entityId: EntityId): EventSourcedEntityCalls =
    EventSourcedEntityCalls(transport, entityId)

  def forKeyValueEntity(entityId: EntityId): KeyValueEntityCalls =
    KeyValueEntityCalls(transport, entityId)

  def forWorkflow(workflowId: EntityId): WorkflowCalls =
    WorkflowCalls(transport, workflowId)

object ComponentClient:

  /**
   * Waits for a call to complete.
   *
   * `scala.concurrent.blocking` is the load-bearing part. On a virtual thread — which is where
   * ankka runs endpoints, workflow steps and consumers — the await parks the virtual thread and
   * releases its carrier, so waiting costs nothing and "write straightforward sequential code" is
   * real rather than aspirational. On a fork-join pool it signals the pool to compensate instead of
   * silently starving it.
   */
  def await[A](future: Future[A], timeout: FiniteDuration): A =
    scala.concurrent.blocking(Await.result(future, timeout))

/** Entry point for calls to event sourced entities. */
final class EventSourcedEntityCalls private[ankka] (
    transport: CallTransport,
    entityId: EntityId
):
  def call[C <: EventSourcedEntity[?, ?], I, O](handle: CommandHandle[C, I, O]): Invocation[I, O] =
    Invocation(transport, entityId, handle)

  def call[C <: EventSourcedEntity[?, ?], O](handle: NoArgHandle[C, O]): NoArgInvocation[O] =
    NoArgInvocation(transport, entityId, handle)

/** Entry point for calls to key value entities. */
final class KeyValueEntityCalls private[ankka] (transport: CallTransport, entityId: EntityId):
  def call[C <: KeyValueEntity[?], I, O](handle: CommandHandle[C, I, O]): Invocation[I, O] =
    Invocation(transport, entityId, handle)

  def call[C <: KeyValueEntity[?], O](handle: NoArgHandle[C, O]): NoArgInvocation[O] =
    NoArgInvocation(transport, entityId, handle)

/** Entry point for calls to workflows. */
final class WorkflowCalls private[ankka] (
    transport: CallTransport,
    workflowId: EntityId,
    metadata: Metadata = Metadata.empty
):
  /** The same calls, carrying `metadata`: a wait carries it as a command does. */
  def withMetadata(metadata: Metadata): WorkflowCalls =
    WorkflowCalls(transport, workflowId, metadata)

  def call[W <: Workflow[?], I, O](handle: CommandHandle[W, I, O]): WorkflowInvocation[W, I, O] =
    WorkflowInvocation(transport, workflowId, handle, metadata)

  def call[W <: Workflow[?], O](handle: NoArgHandle[W, O]): WorkflowNoArgInvocation[W, O] =
    WorkflowNoArgInvocation(transport, workflowId, handle, metadata)

  /**
   * Waits for this workflow's end, for at most `timeout`, and answers the state it ended with — at
   * once when it has already ended. Throws `CommandError` with `WorkflowFailed` when it failed or
   * was deleted (`WorkflowEnd.failure` reads the step and the reason), and with `Timeout` when
   * `timeout` passes first; the workflow runs on, and a wait made again is answered when it ends. A
   * paused workflow has not ended. There is no default: the caller says how long it waits.
   */
  def awaitEnd[W <: Workflow[S], S](
      companion: Workflow.Companion[W, S],
      timeout: FiniteDuration
  ): S =
    ComponentClient.await(
      awaitEndAsync(companion, timeout),
      WorkflowCalls.blockFor(transport, timeout)
    )

  /** `awaitEnd` without blocking. */
  def awaitEndAsync[W <: Workflow[S], S](
      companion: Workflow.Companion[W, S],
      timeout: FiniteDuration
  ): Future[S] =
    WorkflowCalls.awaitEnd(
      transport,
      companion.componentId,
      workflowId,
      timeout,
      metadata,
      companion.stateSerializer
    )

  /**
   * Asks the engine where this workflow has got to.
   *
   * Answered by the runtime, not by a handler the developer wrote, so every workflow has it whether
   * or not its author thought to expose one.
   */
  def lifecycle[W <: Workflow[S], S](
      companion: Workflow.Companion[W, S]
  ): NoArgInvocation[WorkflowLifecycle] =
    NoArgInvocation(
      transport,
      workflowId,
      new NoArgHandle[W, WorkflowLifecycle](
        companion.componentId,
        WorkflowLifecycle.Method,
        readOnly = true,
        WorkflowLifecycle.serializer,
        _ => throw IllegalStateException("the lifecycle query is answered by the runtime")
      )
    )

private[ankka] object WorkflowCalls:

  /** How long a blocking wait blocks: the wait's own time, and an ask's for the last answer. */
  def blockFor(transport: CallTransport, timeout: FiniteDuration): FiniteDuration =
    timeout + transport.askTimeout

  def awaitEnd[S](
      transport: CallTransport,
      componentId: ComponentId,
      workflowId: EntityId,
      timeout: FiniteDuration,
      metadata: Metadata,
      state: Serializer[S]
  ): Future[S] =
    if timeout <= Duration.Zero then Future.failed(nonPositive(timeout))
    else
      transport
        .awaitEnd(componentId, workflowId, timeout, metadata)
        .map((bytes, _) => state.fromBytes(bytes))(using ExecutionContext.parasitic)

  def nonPositive(timeout: FiniteDuration): CommandError =
    CommandError(
      s"a wait for a workflow's end needs a timeout of more than zero, not $timeout",
      ErrorCode.BadRequest
    )

  /** The state a handle's workflow ends with, as its companion declared it. */
  def stateOf[S](handle: Any, declared: Option[Serializer[?]]): Serializer[S] =
    declared match
      case Some(serializer) => serializer.asInstanceOf[Serializer[S]]
      case None =>
        throw IllegalArgumentException(
          s"$handle was not declared by a workflow's companion, which names the state a wait answers"
        )

/**
 * A call to a workflow's one-argument handler: an `Invocation`, and the means to wait for the
 * workflow's end after it.
 */
final class WorkflowInvocation[W <: Workflow[?], I, O] private[ankka] (
    transport: CallTransport,
    workflowId: EntityId,
    handle: CommandHandle[W, I, O],
    metadata: Metadata = Metadata.empty
):
  private val plain = Invocation(transport, workflowId, handle, metadata)

  def withMetadata(metadata: Metadata): WorkflowInvocation[W, I, O] =
    WorkflowInvocation(transport, workflowId, handle, metadata)

  /** Issues the call and waits for its reply. Throws `CommandError` if the handler rejected it. */
  def invoke(input: I): O = plain.invoke(input)

  def invokeAsync(input: I): Future[O] = plain.invokeAsync(input)

  /**
   * Sends the command, then waits for the workflow's end, as one call: answered with the state the
   * workflow ended with, within `timeout` of the command being sent. A refusal of the command is
   * thrown at once and no wait begins; the command's own reply is not kept — a caller that wants
   * both sends the command and then waits.
   */
  def thenAwaitEnd[S](timeout: FiniteDuration)(using W <:< Workflow[S]): AwaitingInvocation[I, S] =
    AwaitingInvocation(
      transport,
      timeout,
      input => plain.invokeAsync(input),
      left =>
        WorkflowCalls.awaitEnd(
          transport,
          handle.componentId,
          workflowId,
          left,
          metadata,
          WorkflowCalls.stateOf[S](handle, handle.stateSerializer)
        )
    )

/** A call to a workflow's no-argument handler, and the means to wait for its end after it. */
final class WorkflowNoArgInvocation[W <: Workflow[?], O] private[ankka] (
    transport: CallTransport,
    workflowId: EntityId,
    handle: NoArgHandle[W, O],
    metadata: Metadata = Metadata.empty
):
  private val plain = NoArgInvocation(transport, workflowId, handle, metadata)

  def withMetadata(metadata: Metadata): WorkflowNoArgInvocation[W, O] =
    WorkflowNoArgInvocation(transport, workflowId, handle, metadata)

  def invoke(): O = plain.invoke()

  def invokeAsync(): Future[O] = plain.invokeAsync()

  /** As `WorkflowInvocation.thenAwaitEnd`, for a handler that takes nothing. */
  def thenAwaitEnd[S](
      timeout: FiniteDuration
  )(using W <:< Workflow[S]): AwaitingNoArgInvocation[S] =
    AwaitingNoArgInvocation(
      AwaitingInvocation[Unit, S](
        transport,
        timeout,
        _ => plain.invokeAsync(),
        left =>
          WorkflowCalls.awaitEnd(
            transport,
            handle.componentId,
            workflowId,
            left,
            metadata,
            WorkflowCalls.stateOf[S](handle, handle.stateSerializer)
          )
      )
    )

/** A command to a workflow followed by a wait for its end, with one deadline from the send. */
final class AwaitingInvocation[I, S] private[ankka] (
    transport: CallTransport,
    timeout: FiniteDuration,
    send: I => Future[?],
    await: FiniteDuration => Future[S]
):
  /** Sends the command and waits for the end. Throws as `WorkflowCalls.awaitEnd` does. */
  def invoke(input: I): S =
    ComponentClient.await(
      invokeAsync(input),
      WorkflowCalls.blockFor(transport, timeout) + transport.askTimeout
    )

  def invokeAsync(input: I): Future[S] =
    if timeout <= Duration.Zero then Future.failed(WorkflowCalls.nonPositive(timeout))
    else
      val deadline = timeout.fromNow
      send(input)
        .flatMap(_ => await(deadline.timeLeft.max(1.milli)))(using ExecutionContext.parasitic)

/** As `AwaitingInvocation`, for a handler that takes nothing. */
final class AwaitingNoArgInvocation[S] private[ankka] (awaiting: AwaitingInvocation[Unit, S]):
  def invoke(): S              = awaiting.invoke(())
  def invokeAsync(): Future[S] = awaiting.invokeAsync(())

/** A resolved, not-yet-issued call to a one-argument handler. */
final class Invocation[I, O] private[ankka] (
    transport: CallTransport,
    entityId: EntityId,
    handle: CommandHandle[?, I, O],
    metadata: Metadata = Metadata.empty
):

  def withMetadata(metadata: Metadata): Invocation[I, O] =
    Invocation(transport, entityId, handle, metadata)

  /** Issues the call and waits. Throws `CommandError` if the handler rejected it. */
  def invoke(input: I): O = ComponentClient.await(invokeAsync(input), transport.askTimeout)

  /** Issues the call without waiting — use this to fan out across many instances. */
  def invokeAsync(input: I): Future[O] =
    transport
      .askHandler(
        handle.readOnly,
        handle.componentId,
        entityId,
        handle.name,
        handle.inputSerializer.toBytes(input),
        metadata
      )
      .map(handle.outputSerializer.fromBytes)(using ExecutionContext.parasitic)

/** A resolved, not-yet-issued call to a no-argument handler. */
final class NoArgInvocation[O] private[ankka] (
    transport: CallTransport,
    entityId: EntityId,
    handle: NoArgHandle[?, O],
    metadata: Metadata = Metadata.empty
):

  def withMetadata(metadata: Metadata): NoArgInvocation[O] =
    NoArgInvocation(transport, entityId, handle, metadata)

  def invoke(): O = ComponentClient.await(invokeAsync(), transport.askTimeout)

  def invokeAsync(): Future[O] =
    transport
      .askHandler(
        handle.readOnly,
        handle.componentId,
        entityId,
        handle.name,
        Array.emptyByteArray,
        metadata
      )
      .map(handle.outputSerializer.fromBytes)(using ExecutionContext.parasitic)
