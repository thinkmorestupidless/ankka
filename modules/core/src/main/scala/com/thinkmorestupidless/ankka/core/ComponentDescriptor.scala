package com.thinkmorestupidless.ankka.core

/** The component kinds ankka's runtime knows how to host. */
enum ComponentKind:
  case EventSourcedEntity
  case KeyValueEntity
  case Workflow
  case View
  case Consumer
  case TimedAction
  case Endpoint
  case Agent

  /** An agent that works tasks to a typed result on its own, instance by instance. */
  case AutonomousAgent

  /** Whether instances of this kind are hosted by cluster sharding. */
  def sharded: Boolean = this match
    case EventSourcedEntity | KeyValueEntity | Workflow | Agent | AutonomousAgent => true
    case View | Consumer | TimedAction | Endpoint                                 => false

/**
 * Everything the runtime needs to host one component, produced by that component's companion.
 *
 * This is the seam that replaces Akka's classpath scanning. Because a component only reaches the
 * runtime by being handed over explicitly, an unregistered component is a compile-or-startup
 * problem rather than a 404 discovered in production.
 */
trait ComponentDescriptor:
  def componentId: ComponentId
  def kind: ComponentKind

  /**
   * The handlers this component declares, by wire name, in name order.
   *
   * What the platform can say about a component without knowing its type: the runtime reports them
   * in a service's topology, and a name a caller sends is only ever taken at its word when it is
   * one of these. Declared on a companion or in discovery, so the set is fixed when the service
   * starts and bounded by what was registered.
   */
  def declaredHandlers: Vector[DeclaredHandler] = Vector.empty

  /**
   * Whether the platform registered this component for its own purposes, such as the entity that
   * keeps an agent's sessions. Said by the component, never inferred from its id: nothing stops a
   * service naming one of its own components `ankka-something`.
   */
  def platform: Boolean = false

  override def toString: String = s"$kind($componentId)"

/** What a declared handler is for. It says how the handler is reported, never how it is run. */
enum HandlerKind:
  /** May change state. */
  case Command

  /** Only reads. */
  case Query

  /** A workflow's step. */
  case Step

  /** Answers a part at a time. */
  case Stream

  /** Run by a timer. */
  case Action

  /** Applies a change from a view's or a consumer's source. */
  case Update

/** A handler a component declares, by the name a caller uses for it. */
final case class DeclaredHandler(name: String, kind: HandlerKind)

object DeclaredHandler:
  /** One order everywhere a component's handlers are listed, so two listings can be compared. */
  def sorted(handlers: Iterable[DeclaredHandler]): Vector[DeclaredHandler] =
    handlers.toVector.sortBy(h => (h.name, h.kind.ordinal))

/**
 * The immutable, validated set of components making up a service.
 *
 * Built once at startup. Lookups are by `(kind, componentId)` because ids only need to be unique
 * within a kind — an entity and the view projecting it may reasonably share a name.
 */
final class ComponentRegistry private (val components: Vector[ComponentDescriptor]):

  private val index: Map[(ComponentKind, ComponentId), ComponentDescriptor] =
    components.map(d => (d.kind, d.componentId) -> d).toMap

  def get(kind: ComponentKind, id: ComponentId): Option[ComponentDescriptor] =
    index.get((kind, id))

  def ofKind(kind: ComponentKind): Vector[ComponentDescriptor] =
    components.filter(_.kind == kind)

  def size: Int = components.size

  def isEmpty: Boolean = components.isEmpty

  override def toString: String =
    components
      .groupBy(_.kind)
      .toVector
      .sortBy(_._1.ordinal)
      .map { (kind, ds) =>
        s"$kind -> [${ds.map(_.componentId).sorted.mkString(", ")}]"
      }
      .mkString("ComponentRegistry(", "; ", ")")

object ComponentRegistry:

  val empty: ComponentRegistry = new ComponentRegistry(Vector.empty)

  /**
   * Validates and freezes a set of descriptors.
   *
   * Reports *every* problem at once rather than the first, because a service with four duplicate
   * registrations should take one restart to fix, not four.
   */
  def from(components: Seq[ComponentDescriptor]): Either[Vector[String], ComponentRegistry] =
    val duplicates = components
      .groupBy(d => (d.kind, d.componentId))
      .collect {
        case ((kind, id), ds) if ds.sizeIs > 1 =>
          s"duplicate $kind component id '$id' registered ${ds.size} times"
      }
      .toVector
      .sorted

    // Every sharded kind is keyed by its component id alone, so two sharded components of
    // different kinds sharing an id would share one sharding region: the second to start would
    // receive the first's messages. An entity and its view may share a name; two sharded ones may
    // not.
    val shardedClashes = components
      .filter(_.kind.sharded)
      .distinctBy(d => (d.kind, d.componentId))
      .groupBy(_.componentId)
      .collect {
        case (id, ds) if ds.sizeIs > 1 =>
          s"component id '$id' is used by ${ds.map(_.kind).sortBy(_.ordinal).mkString(" and ")}; " +
            "sharded components need distinct ids"
      }
      .toVector
      .sorted

    val problems = duplicates ++ shardedClashes
    if problems.nonEmpty then Left(problems)
    else Right(new ComponentRegistry(components.toVector))

  /** As `from`, but throws — for the service bootstrap, where there is no recovery. */
  def fromOrThrow(components: Seq[ComponentDescriptor]): ComponentRegistry =
    from(components).fold(
      problems =>
        throw IllegalArgumentException(
          problems.mkString("invalid ankka service definition:\n  - ", "\n  - ", "")
        ),
      identity
    )
