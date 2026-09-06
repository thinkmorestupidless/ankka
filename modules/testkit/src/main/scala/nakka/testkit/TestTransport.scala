package nakka.testkit

import nakka.core.*
import nakka.sdk.{CallTransport, CommandHandle, ComponentClient, NoArgHandle}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A `CallTransport` for unit tests, with no cluster behind it.
 *
 * Calls to components you have not stubbed fail with an explanation rather than hanging
 * or returning a fabricated value — a unit test that silently gets a default back from a
 * collaborator it forgot to stub is worse than one that fails.
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
        bytes =>
          handle.outputSerializer.toBytes(respond(handle.inputSerializer.fromBytes(bytes)))
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

  def withAskTimeout(timeout: FiniteDuration): TestTransport =
    new TestTransport(stubs, timeout)

  /** A `ComponentClient` backed by these stubs. */
  def client: ComponentClient = ComponentClient(this)

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
              "Add TestTransport().stub(Component.handler)(…), or use NakkaTestKit for a " +
              "test that routes calls for real.",
            ErrorCode.NotFound
          )
        )

object TestTransport:
  def apply(): TestTransport = new TestTransport(Map.empty, 5.seconds)

  /** A client whose every call fails with an explanation. */
  def unroutedClient: ComponentClient = TestTransport().client
