package nakka.core

/** The component kinds nakka's runtime knows how to host. */
enum ComponentKind:
  case EventSourcedEntity
  case KeyValueEntity
  case Workflow
  case View
  case Consumer
  case TimedAction
  case Endpoint
  case Agent

  /** Whether instances of this kind are hosted by cluster sharding. */
  def sharded: Boolean = this match
    case EventSourcedEntity | KeyValueEntity | Workflow | Agent => true
    case View | Consumer | TimedAction | Endpoint               => false

/**
 * Everything the runtime needs to host one component, produced by that component's
 * companion.
 *
 * This is the seam that replaces Akka's classpath scanning. Because a component only
 * reaches the runtime by being handed over explicitly, an unregistered component is a
 * compile-or-startup problem rather than a 404 discovered in production.
 */
trait ComponentDescriptor:
  def componentId: ComponentId
  def kind: ComponentKind

  override def toString: String = s"$kind($componentId)"

/**
 * The immutable, validated set of components making up a service.
 *
 * Built once at startup. Lookups are by `(kind, componentId)` because ids only need to
 * be unique within a kind — an entity and the view projecting it may reasonably share a
 * name.
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
    components.groupBy(_.kind).toVector.sortBy(_._1.ordinal).map { (kind, ds) =>
      s"$kind -> [${ds.map(_.componentId).sorted.mkString(", ")}]"
    }.mkString("ComponentRegistry(", "; ", ")")

object ComponentRegistry:

  val empty: ComponentRegistry = new ComponentRegistry(Vector.empty)

  /**
   * Validates and freezes a set of descriptors.
   *
   * Reports *every* problem at once rather than the first, because a service with four
   * duplicate registrations should take one restart to fix, not four.
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

    if duplicates.nonEmpty then Left(duplicates)
    else Right(new ComponentRegistry(components.toVector))

  /** As `from`, but throws — for the service bootstrap, where there is no recovery. */
  def fromOrThrow(components: Seq[ComponentDescriptor]): ComponentRegistry =
    from(components).fold(
      problems =>
        throw IllegalArgumentException(
          problems.mkString("invalid nakka service definition:\n  - ", "\n  - ", "")
        ),
      identity
    )
