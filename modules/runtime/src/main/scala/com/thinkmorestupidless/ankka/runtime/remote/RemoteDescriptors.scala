package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  DeclaredHandler,
  HandlerKind,
  MethodName
}
import com.thinkmorestupidless.ankka.sdk.{
  ConsumerDescriptor,
  DeclaredQuery,
  StartFrom,
  ViewDescriptor,
  WorkflowSettings
}

/**
 * A handler the developer's process declared in discovery.
 *
 * Only what the sidecar needs to route and to enforce: the wire name, whether the handler may
 * persist (a `read_only` handler that replies with events is a protocol violation), and whether its
 * reply streams.
 */
final case class RemoteHandler(name: MethodName, readOnly: Boolean, streaming: Boolean)

/** What a remote view or consumer subscribes to. */
enum RemoteSource:
  case Component(kind: ComponentKind, id: ComponentId)

  /** `startFrom` as the process declared it; `None` when it declared nowhere. */
  case Topic(name: String, startFrom: Option[StartFrom] = None)

/**
 * Descriptors for components whose handlers live in another process.
 *
 * Plain data from discovery: no functions, no serializers. They extend `ComponentDescriptor` so
 * `ComponentRegistry.from` validates them exactly as it validates Scala ones, and `Ankka.host`
 * starts a remote host for each kind as it starts an in-process host for a Scala descriptor. The
 * conversation a remote host speaks through is supplied by whoever built the service — the sidecar
 * — so this module gains no transport.
 */
sealed trait RemoteDescriptor extends ComponentDescriptor:
  def handlers: Map[MethodName, RemoteHandler]
  def handler(name: MethodName): Option[RemoteHandler] = handlers.get(name)

  /** What discovery declared, said as a Scala component of the same kind says it. */
  override def declaredHandlers: Vector[DeclaredHandler] =
    DeclaredHandler.sorted(handlers.values.map(RemoteDescriptor.declared))

object RemoteDescriptor:
  private[remote] def declared(handler: RemoteHandler): DeclaredHandler =
    val kind =
      if handler.streaming then HandlerKind.Stream
      else if handler.readOnly then HandlerKind.Query
      else HandlerKind.Command
    DeclaredHandler(handler.name.toString, kind)

final case class RemoteEventSourcedDescriptor(
    componentId: ComponentId,
    handlers: Map[MethodName, RemoteHandler],
    snapshotEvery: Option[Int]
) extends RemoteDescriptor:
  val kind: ComponentKind = ComponentKind.EventSourcedEntity

final case class RemoteKeyValueDescriptor(
    componentId: ComponentId,
    handlers: Map[MethodName, RemoteHandler]
) extends RemoteDescriptor:
  val kind: ComponentKind = ComponentKind.KeyValueEntity

/**
 * `settings` are the engine's — timeouts and recovery are enforced by the sidecar, so the process
 * declares them in discovery rather than applying them itself.
 */
final case class RemoteWorkflowDescriptor(
    componentId: ComponentId,
    handlers: Map[MethodName, RemoteHandler],
    steps: Set[String],
    settings: WorkflowSettings = WorkflowSettings.default
) extends RemoteDescriptor:
  val kind: ComponentKind = ComponentKind.Workflow

  override def declaredHandlers: Vector[DeclaredHandler] =
    DeclaredHandler.sorted(
      handlers.values.map(RemoteDescriptor.declared) ++
        steps.map(DeclaredHandler(_, HandlerKind.Step))
    )

final case class RemoteViewDescriptor(
    componentId: ComponentId,
    source: RemoteSource,
    rowManifest: String,
    queries: Set[MethodName],
    version: Option[Int] = None,
    declaredQueries: Vector[DeclaredQuery] = Vector.empty
) extends RemoteDescriptor:
  val kind: ComponentKind = ComponentKind.View
  val handlers: Map[MethodName, RemoteHandler] =
    queries.map(q => q -> RemoteHandler(q, readOnly = true, streaming = false)).toMap

  /** As a Scala view declares: what it does with a change. Its queries are read, not called. */
  override def declaredHandlers: Vector[DeclaredHandler] = Vector(ViewDescriptor.OnChange)

/**
 * A keyed view in another process or a module: several entity sources, each change sent with the
 * component it came from, and answered with row changes by key (protocol 1.13).
 */
final case class RemoteKeyedViewDescriptor(
    componentId: ComponentId,
    sources: Vector[RemoteSource],
    rowManifest: String,
    version: Option[Int] = None,
    declaredQueries: Vector[DeclaredQuery] = Vector.empty
) extends RemoteDescriptor:
  val kind: ComponentKind                      = ComponentKind.View
  val handlers: Map[MethodName, RemoteHandler] = Map.empty

  /** One handler per source, named for what it reads, as a Scala keyed view declares. */
  override def declaredHandlers: Vector[DeclaredHandler] =
    sources
      .map {
        case RemoteSource.Component(_, id) => DeclaredHandler(id.toString, HandlerKind.Update)
        case RemoteSource.Topic(name, _)   => DeclaredHandler(name, HandlerKind.Update)
      }
      .sortBy(_.name)

/**
 * `startDeclarable` is false when the process's SDK predates start positions and so could not have
 * declared one: such a consumer over a topic is not refused for declaring none, and starts at the
 * earliest message as it always did. Refusing it would leave a running service unable to restart
 * after the platform beneath it was upgraded.
 */
final case class RemoteConsumerDescriptor(
    componentId: ComponentId,
    source: RemoteSource,
    producesTo: Option[String],
    startDeclarable: Boolean = true,
    version: Option[Int] = None
) extends RemoteDescriptor:
  val kind: ComponentKind                      = ComponentKind.Consumer
  val handlers: Map[MethodName, RemoteHandler] = Map.empty

  override def declaredHandlers: Vector[DeclaredHandler] = Vector(ConsumerDescriptor.OnMessage)

final case class RemoteTimedActionDescriptor(
    componentId: ComponentId,
    handlers: Map[MethodName, RemoteHandler]
) extends RemoteDescriptor:
  val kind: ComponentKind = ComponentKind.TimedAction

  override def declaredHandlers: Vector[DeclaredHandler] =
    DeclaredHandler.sorted(handlers.keys.map(m => DeclaredHandler(m.toString, HandlerKind.Action)))
