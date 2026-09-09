package nakka.sdk

import nakka.core.*
import nakka.core.effect.*

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A durable, multi-step business process.
 *
 * The difference between a workflow and a plain method that calls three services is what happens
 * when the process is halfway through and the node dies. A workflow's current step and its input
 * are persisted, so it resumes; a method call is simply gone.
 *
 * Steps are ordinary sequential code. They may block on `ComponentClient.invoke`, because they run
 * on virtual threads.
 */
abstract class Workflow[S]:

  private var stateOpt: Option[S]                = None
  private var contextOpt: Option[CommandContext] = None

  final type Effect[R]         = WorkflowEffect[S, R]
  final type ReadOnlyEffect[R] = WorkflowReadOnlyEffect[S, R]
  final type StepEffect        = WorkflowStepEffect[S]

  /** State before the workflow is started. */
  def emptyState: S

  /** Timeouts and recovery. */
  def settings: WorkflowSettings = WorkflowSettings.default

  protected final def currentState: S =
    stateOpt.getOrElse(
      throw IllegalStateException("currentState is only available inside a handler or step")
    )

  protected final def commandContext: CommandContext =
    contextOpt.getOrElse(
      throw IllegalStateException("commandContext is only available inside a command handler")
    )

  /** Effects for command handlers — decide what to reply. */
  protected final val effects: WorkflowEffects[S] = new WorkflowEffects[S]()

  /** Effects for steps — decide what runs next. */
  protected final val stepEffects: WorkflowStepEffects[S] = new WorkflowStepEffects[S]()

  private[nakka] def _setState(state: S): Unit                      = stateOpt = Some(state)
  private[nakka] def _setContext(ctx: Option[CommandContext]): Unit = contextOpt = ctx

/** How long a workflow and its steps may take, and what to do when they fail. */
final case class WorkflowSettings(
    timeout: Option[FiniteDuration],
    defaultStepTimeout: FiniteDuration,
    stepTimeouts: Map[String, FiniteDuration],
    defaultRecovery: RecoverStrategy,
    stepRecovery: Map[String, RecoverStrategy]
):
  def stepTimeout(step: String): FiniteDuration =
    stepTimeouts.getOrElse(step, defaultStepTimeout)

  def recoveryFor(step: String): RecoverStrategy =
    stepRecovery.getOrElse(step, defaultRecovery)

object WorkflowSettings:

  /**
   * No global timeout, 30s per step, no retries.
   *
   * Retries default to zero rather than to some number because a step that is not idempotent must
   * not be retried, and the runtime cannot know which yours are.
   */
  val default: WorkflowSettings =
    WorkflowSettings(None, 30.seconds, Map.empty, RecoverStrategy.fail, Map.empty)

  def builder: WorkflowSettingsBuilder = WorkflowSettingsBuilder(default)

final case class WorkflowSettingsBuilder(private val settings: WorkflowSettings):

  /** A ceiling on the whole workflow, so a stuck process cannot run forever. */
  def timeout(duration: FiniteDuration): WorkflowSettingsBuilder =
    copy(settings = settings.copy(timeout = Some(duration)))

  def defaultStepTimeout(duration: FiniteDuration): WorkflowSettingsBuilder =
    copy(settings = settings.copy(defaultStepTimeout = duration))

  def stepTimeout[C](step: StepHandleLike[C], duration: FiniteDuration): WorkflowSettingsBuilder =
    copy(settings = settings.copy(stepTimeouts = settings.stepTimeouts + (step.name -> duration)))

  def defaultRecovery(strategy: RecoverStrategy): WorkflowSettingsBuilder =
    copy(settings = settings.copy(defaultRecovery = strategy))

  def stepRecovery[C](step: StepHandleLike[C], strategy: RecoverStrategy): WorkflowSettingsBuilder =
    copy(settings = settings.copy(stepRecovery = settings.stepRecovery + (step.name -> strategy)))

  def build: WorkflowSettings = settings

/** What happens when a step throws or times out. */
final case class RecoverStrategy(maxRetries: Int, failoverTo: Option[String]):

  /**
   * Where to go when the retries are used up.
   *
   * The failover step takes no input on purpose: compensation needs the workflow's accumulated
   * state, which it can read from `currentState`, and an input captured when the settings were
   * built would be stale by the time it was needed.
   */
  def failoverTo[C](step: NoInputStepHandle[C]): RecoverStrategy =
    copy(failoverTo = Some(step.name))

object RecoverStrategy:
  /** Give up immediately. The workflow ends failed. */
  val fail: RecoverStrategy = RecoverStrategy(0, None)

  def maxRetries(count: Int): RecoverStrategy = RecoverStrategy(count, None)

/** Common surface of the two step handle kinds, for settings that only need a name. */
sealed trait StepHandleLike[C]:
  def name: String
  private[nakka] def invoke(workflow: C, input: Option[Array[Byte]]): Any

/** A step taking one argument. Transitions to it must supply that argument. */
final class StepHandle[C, I] private[nakka] (
    val name: String,
    private[nakka] val inputSerializer: Serializer[I],
    private[nakka] val run: (C, I) => Any
) extends StepHandleLike[C]:

  /** A transition to this step, carrying `input`. */
  def withInput(input: I): StepRef =
    StepRef(name, Some(inputSerializer.toBytes(input)))

  private[nakka] def invoke(workflow: C, input: Option[Array[Byte]]): Any =
    run(
      workflow,
      inputSerializer.fromBytes(
        input.getOrElse(
          throw IllegalStateException(s"step '$name' takes an input but none was persisted")
        )
      )
    )

  override def toString: String = s"step($name)"

/** A step taking no argument. Usable directly as a transition target. */
final class NoInputStepHandle[C] private[nakka] (
    val name: String,
    private[nakka] val run: C => Any
) extends StepHandleLike[C]:

  /** Implicitly a transition target, so `thenTransitionTo(myStep)` just works. */
  def ref: StepRef = StepRef(name, None)

  private[nakka] def invoke(workflow: C, input: Option[Array[Byte]]): Any = run(workflow)

  override def toString: String = s"step($name)"

object NoInputStepHandle:
  /** Lets a no-input step be written where a transition target is expected. */
  given [C]: Conversion[NoInputStepHandle[C], StepRef] = _.ref

object Workflow:

  /**
   * Declares a workflow to the runtime.
   *
   * Steps and command handlers are registered as `val`s, exactly as on an entity:
   * {{{
   * object TransferWorkflow
   *     extends Workflow.Companion[TransferWorkflow, TransferState](
   *       ComponentId("transfer"),
   *       Codecs.serializer[TransferState]("transfer-state")
   *     ):
   *   def create(ctx: WorkflowContext) = new TransferWorkflow(ctx)
   *
   *   val withdraw = step("withdraw")(_.withdrawStep)
   *   val deposit  = step("deposit")(_.depositStep)
   *   val start    = command("start")(_.startTransfer)
   * }}}
   */
  abstract class Companion[W <: Workflow[S], S](
      val componentId: ComponentId,
      val stateSerializer: Serializer[S]
  ):

    private val handlers = mutable.ListBuffer.empty[HandlerBinding[W]]
    private val steps    = mutable.ListBuffer.empty[StepHandleLike[W]]

    protected final given serializerForState: Serializer[S] = stateSerializer

    def create(ctx: WorkflowContext): W

    /** Registers a step taking one argument. */
    protected final def step[I](name: String)(
        f: W => I => WorkflowStepEffect[S]
    )(using in: Serializer[I]): StepHandle[W, I] =
      addStep(new StepHandle[W, I](name, in, (w, i) => f(w)(i)))

    /** Registers a step taking no argument. */
    protected final def step(name: String)(
        f: W => WorkflowStepEffect[S]
    ): NoInputStepHandle[W] =
      addStep(new NoInputStepHandle[W](name, f))

    protected final def command[I, O](name: String)(
        f: W => I => WorkflowEffect[S, O]
    )(using in: Serializer[I], out: Serializer[O]): CommandHandle[W, I, O] =
      addHandler(
        new CommandHandle[W, I, O](
          componentId,
          MethodName(name),
          readOnly = false,
          in,
          out,
          (w, i) => f(w)(i)
        )
      )

    protected final def command[O](name: String)(
        f: W => WorkflowEffect[S, O]
    )(using out: Serializer[O]): NoArgHandle[W, O] =
      addHandler(new NoArgHandle[W, O](componentId, MethodName(name), readOnly = false, out, f))

    protected final def query[I, O](name: String)(
        f: W => I => WorkflowReadOnlyEffect[S, O]
    )(using in: Serializer[I], out: Serializer[O]): CommandHandle[W, I, O] =
      addHandler(
        new CommandHandle[W, I, O](
          componentId,
          MethodName(name),
          readOnly = true,
          in,
          out,
          (w, i) => f(w)(i)
        )
      )

    protected final def query[O](name: String)(
        f: W => WorkflowReadOnlyEffect[S, O]
    )(using out: Serializer[O]): NoArgHandle[W, O] =
      addHandler(new NoArgHandle[W, O](componentId, MethodName(name), readOnly = true, out, f))

    private def addHandler[H <: HandlerBinding[W]](handle: H): H =
      handlers += handle
      handle

    private def addStep[H <: StepHandleLike[W]](handle: H): H =
      steps += handle
      handle

    /** A `def`, not a `val` — see the note on `EventSourcedEntity.Companion`. */
    final def descriptor: WorkflowDescriptor[W, S] =
      val problems = Vector.newBuilder[String]
      (handlers.map(_.name: String) ++ steps.map(_.name)).foreach { name =>
        if name.startsWith(WorkflowLifecycle.MethodPrefix) then
          problems += s"'$name' uses the reserved '${WorkflowLifecycle.MethodPrefix}' prefix"
      }
      handlers.groupBy(_.name).foreach { (name, bs) =>
        if bs.sizeIs > 1 then problems += s"handler '$name' registered ${bs.size} times"
      }
      steps.groupBy(_.name).foreach { (name, ss) =>
        if ss.sizeIs > 1 then problems += s"step '$name' registered ${ss.size} times"
      }
      val found = problems.result()
      if found.nonEmpty then
        throw IllegalArgumentException(
          found.mkString(s"invalid workflow '$componentId':\n  - ", "\n  - ", "")
        )

      WorkflowDescriptor(
        componentId,
        stateSerializer,
        create,
        handlers.map(h => h.name -> h).toMap,
        steps.map(s => s.name -> s).toMap
      )

/** The registered form of a workflow. */
final case class WorkflowDescriptor[W <: Workflow[S], S](
    componentId: ComponentId,
    stateSerializer: Serializer[S],
    create: WorkflowContext => W,
    handlers: Map[MethodName, HandlerBinding[W]],
    steps: Map[String, StepHandleLike[W]]
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.Workflow

  private[nakka] def handler(name: MethodName): Option[HandlerBinding[W]] = handlers.get(name)
  private[nakka] def stepNamed(name: String): Option[StepHandleLike[W]]   = steps.get(name)
