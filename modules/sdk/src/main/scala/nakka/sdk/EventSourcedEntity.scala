package nakka.sdk

import nakka.core.*
import nakka.core.effect.*

import scala.collection.mutable

/**
 * An entity whose state is derived by replaying the events it has persisted.
 *
 * Cluster sharding guarantees a single instance per entity id across the whole cluster and delivers
 * one command at a time to it, which is what makes the mutable `currentState` below safe — and what
 * lets handlers be written as ordinary sequential code with no locks and no concurrency reasoning.
 *
 * State can only change by persisting an event. There is no `updateState`.
 */
abstract class EventSourcedEntity[S, E]:

  /**
   * Handler return types, so signatures read `def addItem(i: LineItem): Effect[Done]` rather than
   * repeating the state and event types on every method.
   */
  final type Effect[R]         = EventSourcedEffect[S, E, R]
  final type ReadOnlyEffect[R] = nakka.core.effect.ReadOnlyEffect[S, E, R]

  private var stateOpt: Option[S]                = None
  private var contextOpt: Option[CommandContext] = None

  /** State before any event has been persisted. Must never be null. */
  def emptyState: S

  /**
   * Folds one event into the current state. The single place state changes.
   *
   * Called on replay as well as on new events, so it must be pure: no I/O, no clock reads, no
   * random values — anything else makes a recovered entity diverge from the one that wrote the
   * journal.
   */
  def applyEvent(event: E): S

  /** State as of now: after replay, and after any events persisted earlier this command. */
  protected final def currentState: S =
    stateOpt.getOrElse(
      throw IllegalStateException(
        "currentState is only available inside a command handler or applyEvent"
      )
    )

  protected final def commandContext: CommandContext =
    contextOpt.getOrElse(
      throw IllegalStateException("commandContext is only available inside a command handler")
    )

  /** The declarative effect API for this entity. */
  protected final val effects: EventSourcedEffects[S, E] = new EventSourcedEffects[S, E]()

  // ── Runtime hooks ─────────────────────────────────────────────────────────
  private[nakka] def _setState(state: S): Unit                      = stateOpt = Some(state)
  private[nakka] def _setContext(ctx: Option[CommandContext]): Unit = contextOpt = ctx
  private[nakka] def _applyEvent(state: S, event: E): S =
    _setState(state)
    applyEvent(event)

object EventSourcedEntity:

  /**
   * Declares an event sourced entity to the runtime.
   *
   * Extend this in the entity's companion object and register handlers as `val`s:
   * {{{
   * object CounterEntity
   *     extends EventSourcedEntity.Companion[CounterEntity, Counter, CounterEvent](
   *       ComponentId("counter"),
   *       Codecs.serializer[Counter]("counter"),
   *       Codecs.serializer[CounterEvent]("counter-event")
   *     ):
   *   def create(ctx: EventSourcedEntityContext) = new CounterEntity(ctx)
   *
   *   val increase = command("increase")(_.increase)
   *   val get      = query("get")(_.get)
   * }}}
   *
   * `descriptor` is a `def`, not a `val`: a `val` in this base class would be initialised before
   * the subclass's handler `val`s had run, and would see no handlers.
   */
  abstract class Companion[C <: EventSourcedEntity[S, E], S, E](
      val componentId: ComponentId,
      val stateSerializer: Serializer[S],
      val eventSerializer: Serializer[E]
  ):

    private val bindings = mutable.ListBuffer.empty[HandlerBinding[C]]

    /**
     * The state and event serializers are exposed as givens because replying with state and
     * persisting events are the two most common things a handler does — requiring the developer to
     * redeclare instances that this companion already holds would be ceremony with no safety
     * benefit.
     */
    protected final given serializerForState: Serializer[S] = stateSerializer
    protected final given serializerForEvent: Serializer[E] = eventSerializer

    /** Instantiates the entity. Called once per activation, not per command. */
    def create(ctx: EventSourcedEntityContext): C

    /** How often to snapshot. `None` disables snapshotting for this entity. */
    def snapshotEvery: Option[Int] = Some(100)

    /** Registers a handler that takes one argument and may persist events. */
    protected final def command[I, O](name: String)(
        f: C => I => EventSourcedEffect[S, E, O]
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

    /** Registers a no-argument handler that may persist events. */
    protected final def command[O](name: String)(
        f: C => EventSourcedEffect[S, E, O]
    )(using out: Serializer[O]): NoArgHandle[C, O] =
      add(new NoArgHandle[C, O](componentId, MethodName(name), readOnly = false, out, f))

    /**
     * Registers a read-only handler taking one argument.
     *
     * The `ReadOnlyEffect` bound is the enforcement: a handler that persists cannot be registered
     * as a query, so the runtime's "serve reads from any replica" decision rests on the type system
     * rather than on a naming convention.
     */
    protected final def query[I, O](name: String)(
        f: C => I => ReadOnlyEffect[S, E, O]
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

    /** Registers a no-argument read-only handler. */
    protected final def query[O](name: String)(
        f: C => ReadOnlyEffect[S, E, O]
    )(using out: Serializer[O]): NoArgHandle[C, O] =
      add(new NoArgHandle[C, O](componentId, MethodName(name), readOnly = true, out, f))

    private def add[H <: HandlerBinding[C]](handle: H): H =
      bindings += handle
      handle

    /** Everything the runtime needs to host this entity. */
    final def descriptor: EventSourcedEntityDescriptor[C, S, E] =
      val seen = bindings.groupBy(_.name).collect {
        case (name, bs) if bs.sizeIs > 1 => s"handler '$name' registered ${bs.size} times"
      }
      if seen.nonEmpty then
        throw IllegalArgumentException(
          seen.mkString(s"invalid entity '$componentId':\n  - ", "\n  - ", "")
        )

      EventSourcedEntityDescriptor(
        componentId,
        stateSerializer,
        eventSerializer,
        create,
        snapshotEvery,
        bindings.map(b => b.name -> b).toMap
      )

/** The registered form of an event sourced entity. */
final case class EventSourcedEntityDescriptor[C <: EventSourcedEntity[S, E], S, E](
    componentId: ComponentId,
    stateSerializer: Serializer[S],
    eventSerializer: Serializer[E],
    create: EventSourcedEntityContext => C,
    snapshotEvery: Option[Int],
    handlers: Map[MethodName, HandlerBinding[C]]
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.EventSourcedEntity

  private[nakka] def handler(name: MethodName): Option[HandlerBinding[C]] =
    handlers.get(name)
