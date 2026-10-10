package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{
  CallTransport,
  CommandHandle,
  ComponentClient,
  NoArgHandle,
  Workflow,
  WorkflowLifecycle
}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A `CallTransport` for unit tests, with no cluster behind it.
 *
 * Calls to components you have not stubbed fail with an explanation rather than hanging or
 * returning a fabricated value — a unit test that silently gets a default back from a collaborator
 * it forgot to stub is worse than one that fails.
 *
 * {{{
 * val transport = TestTransport()
 *   .stub(WalletEntity.withdraw)(amount => Done)
 *   .stub(WalletEntity.balance)(() => 100)
 *
 * val kit = EventSourcedTestKit.of(TransferEntity, "t-1", transport.client)
 * }}}
 */
final class TestTransport private (
    private val stubs: Map[(String, String), Array[Byte] => Array[Byte]],
    val askTimeout: FiniteDuration
) extends CallTransport:

  /** Stubs a one-argument handler. */
  def stub[C, I, O](handle: CommandHandle[C, I, O])(respond: I => O): TestTransport =
    new TestTransport(
      stubs.updated(
        (handle.componentId, handle.name),
        bytes => handle.outputSerializer.toBytes(respond(handle.inputSerializer.fromBytes(bytes)))
      ),
      askTimeout
    )

  /** Stubs a no-argument handler. */
  def stub[C, O](handle: NoArgHandle[C, O])(respond: () => O): TestTransport =
    new TestTransport(
      stubs.updated(
        (handle.componentId, handle.name),
        _ => handle.outputSerializer.toBytes(respond())
      ),
      askTimeout
    )

  /** Makes a one-argument handler reject, so failure paths can be exercised. */
  def stubFailure[C, I, O](handle: CommandHandle[C, I, O])(error: CommandError): TestTransport =
    new TestTransport(
      stubs.updated((handle.componentId, handle.name), _ => throw error),
      askTimeout
    )

  /**
   * Makes a wait for any of `companion`'s workflows answer `state`, as one that has ended would.
   */
  def stubEnd[W <: Workflow[S], S](companion: Workflow.Companion[W, S])(state: S): TestTransport =
    new TestTransport(
      stubs.updated(
        (companion.componentId, WorkflowLifecycle.AwaitEnd),
        _ => companion.stateSerializer.toBytes(state)
      ),
      askTimeout
    )

  /**
   * Makes a wait for any of `companion`'s workflows fail as `failure` says: a failed workflow, or a
   * deleted one.
   */
  def stubEndFailure[W <: Workflow[S], S](companion: Workflow.Companion[W, S])(
      failure: WorkflowEnd.Failure
  ): TestTransport =
    new TestTransport(
      stubs.updated(
        (companion.componentId, WorkflowLifecycle.AwaitEnd),
        _ =>
          throw CommandError(
            s"workflow ${companion.componentId} failed: ${failure.reason}",
            ErrorCode.WorkflowFailed,
            failure.details
          )
      ),
      askTimeout
    )

  def withAskTimeout(timeout: FiniteDuration): TestTransport =
    new TestTransport(stubs, timeout)

  /** A `ComponentClient` backed by these stubs. */
  def client: ComponentClient = ComponentClient(this)

  /** Streaming is not modelled by the stub transport; use AnkkaTestKit for that. */
  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit =
    throw CommandError(
      s"$componentId does not accept fire-and-forget messages in the unit test kit; " +
        "use AnkkaTestKit for a test that routes them for real",
      ErrorCode.NotFound
    )

  def ask(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    stubs.get((componentId, method)) match
      case Some(respond) =>
        try Future.successful(respond(payload))
        catch case error: CommandError => Future.failed(error)
      case None =>
        Future.failed(
          CommandError(
            s"$componentId#$method was called on '$entityId' but is not stubbed. " +
              "Add TestTransport().stub(Component.handler)(…), or use AnkkaTestKit for a " +
              "test that routes calls for real.",
            ErrorCode.NotFound
          )
        )

  /** A wait answered from `stubEnd` or `stubEndFailure`; a workflow not stubbed is not found. */
  override def awaitEnd(
      componentId: ComponentId,
      entityId: EntityId,
      timeout: FiniteDuration,
      metadata: Metadata
  ): Future[(Array[Byte], Metadata)] =
    if timeout <= scala.concurrent.duration.Duration.Zero then
      Future.failed(CommandError(s"a wait needs a timeout of more than zero, not $timeout"))
    else
      ask(componentId, entityId, WorkflowLifecycle.AwaitEnd, Array.emptyByteArray, metadata)
        .map(bytes => (bytes, Metadata.empty))(using scala.concurrent.ExecutionContext.parasitic)

object TestTransport:
  def apply(): TestTransport = new TestTransport(Map.empty, 5.seconds)

  /** A client whose every call fails with an explanation. */
  def unroutedClient: ComponentClient = TestTransport().client
