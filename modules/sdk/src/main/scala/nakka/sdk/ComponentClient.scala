package nakka.sdk

import nakka.core.*

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * How a call actually reaches another component.
 *
 * The typed call machinery belongs in the SDK — developers write these calls inside handlers — but
 * routing needs cluster sharding, which the SDK must not depend on. This interface is the join:
 * `nakka-runtime` supplies the sharding-backed implementation.
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
   * Sends a message without awaiting a reply.
   *
   * `Any` because the message types live in `nakka-runtime`, which the SDK must not depend on. Used
   * for streaming, where the reply arrives over time through a channel carried inside the message
   * rather than as a return value.
   */
  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit

  /** How long a blocking `invoke` waits before failing with `ErrorCode.Timeout`. */
  def askTimeout: FiniteDuration

/**
 * How components call each other.
 *
 * Calls go through a client rather than direct method calls because the target instance is very
 * likely on another node. What the client hides is the routing; what it deliberately does not hide
 * is that a call can fail, which is why a rejection arrives as a `CommandError` rather than as a
 * default value.
 */
final class ComponentClient(transport: CallTransport):

  /** Lets other nakka modules add their own component kinds — see `forAgent`. */
  private[nakka] def transportRef: CallTransport = transport

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
   * nakka runs endpoints, workflow steps and consumers — the await parks the virtual thread and
   * releases its carrier, so waiting costs nothing and "write straightforward sequential code" is
   * real rather than aspirational. On a fork-join pool it signals the pool to compensate instead of
   * silently starving it.
   */
  def await[A](future: Future[A], timeout: FiniteDuration): A =
    scala.concurrent.blocking(Await.result(future, timeout))

/** Entry point for calls to event sourced entities. */
final class EventSourcedEntityCalls private[nakka] (
    transport: CallTransport,
    entityId: EntityId
):
  def call[C <: EventSourcedEntity[?, ?], I, O](handle: CommandHandle[C, I, O]): Invocation[I, O] =
    Invocation(transport, entityId, handle)

  def call[C <: EventSourcedEntity[?, ?], O](handle: NoArgHandle[C, O]): NoArgInvocation[O] =
    NoArgInvocation(transport, entityId, handle)

/** Entry point for calls to key value entities. */
final class KeyValueEntityCalls private[nakka] (transport: CallTransport, entityId: EntityId):
  def call[C <: KeyValueEntity[?], I, O](handle: CommandHandle[C, I, O]): Invocation[I, O] =
    Invocation(transport, entityId, handle)

  def call[C <: KeyValueEntity[?], O](handle: NoArgHandle[C, O]): NoArgInvocation[O] =
    NoArgInvocation(transport, entityId, handle)

/** Entry point for calls to workflows. */
final class WorkflowCalls private[nakka] (transport: CallTransport, workflowId: EntityId):
  def call[W <: Workflow[?], I, O](handle: CommandHandle[W, I, O]): Invocation[I, O] =
    Invocation(transport, workflowId, handle)

  def call[W <: Workflow[?], O](handle: NoArgHandle[W, O]): NoArgInvocation[O] =
    NoArgInvocation(transport, workflowId, handle)

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

/** A resolved, not-yet-issued call to a one-argument handler. */
final class Invocation[I, O] private[nakka] (
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
      .ask(
        handle.componentId,
        entityId,
        handle.name,
        handle.inputSerializer.toBytes(input),
        metadata
      )
      .map(handle.outputSerializer.fromBytes)(using ExecutionContext.parasitic)

/** A resolved, not-yet-issued call to a no-argument handler. */
final class NoArgInvocation[O] private[nakka] (
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
      .ask(handle.componentId, entityId, handle.name, Array.emptyByteArray, metadata)
      .map(handle.outputSerializer.fromBytes)(using ExecutionContext.parasitic)
