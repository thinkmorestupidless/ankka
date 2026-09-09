package nakka.sdk

import nakka.core.*
import nakka.core.effect.*

import scala.collection.mutable

/**
 * An entity that persists only its current value, with no history.
 *
 * Same sharding and single-writer guarantees as an event sourced entity — the difference is purely
 * what reaches storage. Choose this when the audit trail has no value and the latest value is all
 * anyone ever reads.
 */
abstract class KeyValueEntity[S]:

  /** Handler return types. See the note on `EventSourcedEntity`. */
  final type Effect[R]         = KeyValueEffect[S, R]
  final type ReadOnlyEffect[R] = KeyValueReadOnlyEffect[S, R]

  private var stateOpt: Option[S]                = None
  private var contextOpt: Option[CommandContext] = None

  /** State before anything has been written. Must never be null. */
  def emptyState: S

  protected final def currentState: S =
    stateOpt.getOrElse(
      throw IllegalStateException("currentState is only available inside a command handler")
    )

  protected final def commandContext: CommandContext =
    contextOpt.getOrElse(
      throw IllegalStateException("commandContext is only available inside a command handler")
    )

  protected final val effects: KeyValueEffects[S] = new KeyValueEffects[S]()

  private[nakka] def _setState(state: S): Unit                      = stateOpt = Some(state)
  private[nakka] def _setContext(ctx: Option[CommandContext]): Unit = contextOpt = ctx

object KeyValueEntity:

  /**
   * Declares a key value entity to the runtime.
   *
   * {{{
   * object ProfileEntity
   *     extends KeyValueEntity.Companion[ProfileEntity, Profile](
   *       ComponentId("profile"),
   *       Codecs.serializer[Profile]("profile")
   *     ):
   *   def create(ctx: KeyValueEntityContext) = new ProfileEntity(ctx)
   *
   *   val rename = command("rename")(_.rename)
   *   val get    = query("get")(_.get)
   * }}}
   */
  abstract class Companion[C <: KeyValueEntity[S], S](
      val componentId: ComponentId,
      val stateSerializer: Serializer[S]
  ):

    private val bindings = mutable.ListBuffer.empty[HandlerBinding[C]]

    protected final given serializerForState: Serializer[S] = stateSerializer

    /** Instantiates the entity. Called once per activation, not per command. */
    def create(ctx: KeyValueEntityContext): C

    protected final def command[I, O](name: String)(
        f: C => I => KeyValueEffect[S, O]
    )(using in: Serializer[I], out: Serializer[O]): CommandHandle[C, I, O] =
      add(
        new CommandHandle[C, I, O](
          componentId,
          MethodName(name),
          readOnly = false,
          in,
          out,
          (c, i) => f(c)(i)
        )
      )

    protected final def command[O](name: String)(
        f: C => KeyValueEffect[S, O]
    )(using out: Serializer[O]): NoArgHandle[C, O] =
      add(new NoArgHandle[C, O](componentId, MethodName(name), readOnly = false, out, f))

    /** The `KeyValueReadOnlyEffect` bound is what makes "read-only" a type guarantee. */
    protected final def query[I, O](name: String)(
        f: C => I => KeyValueReadOnlyEffect[S, O]
    )(using in: Serializer[I], out: Serializer[O]): CommandHandle[C, I, O] =
      add(
        new CommandHandle[C, I, O](
          componentId,
          MethodName(name),
          readOnly = true,
          in,
          out,
          (c, i) => f(c)(i)
        )
      )

    protected final def query[O](name: String)(
        f: C => KeyValueReadOnlyEffect[S, O]
    )(using out: Serializer[O]): NoArgHandle[C, O] =
      add(new NoArgHandle[C, O](componentId, MethodName(name), readOnly = true, out, f))

    private def add[H <: HandlerBinding[C]](handle: H): H =
      bindings += handle
      handle

    /** A `def`, not a `val` — see the note on `EventSourcedEntity.Companion`. */
    final def descriptor: KeyValueEntityDescriptor[C, S] =
      val clashes = bindings.groupBy(_.name).collect {
        case (name, bs) if bs.sizeIs > 1 => s"handler '$name' registered ${bs.size} times"
      }
      if clashes.nonEmpty then
        throw IllegalArgumentException(
          clashes.mkString(s"invalid entity '$componentId':\n  - ", "\n  - ", "")
        )

      KeyValueEntityDescriptor(
        componentId,
        stateSerializer,
        create,
        bindings.map(b => b.name -> b).toMap
      )

/** The registered form of a key value entity. */
final case class KeyValueEntityDescriptor[C <: KeyValueEntity[S], S](
    componentId: ComponentId,
    stateSerializer: Serializer[S],
    create: KeyValueEntityContext => C,
    handlers: Map[MethodName, HandlerBinding[C]]
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.KeyValueEntity

  private[nakka] def handler(name: MethodName): Option[HandlerBinding[C]] =
    handlers.get(name)
