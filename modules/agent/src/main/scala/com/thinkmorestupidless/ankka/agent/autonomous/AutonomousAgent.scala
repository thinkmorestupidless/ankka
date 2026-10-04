package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail, ModelProvider}
import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  DeclaredHandler,
  HandlerKind
}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore, ServiceClients}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * An agent that works tasks to a typed result on its own.
 *
 * Where a request agent answers one request while its caller waits, an autonomous agent is handed a
 * task and iterates — calls the model, runs the tools it asks for, records what happened — until
 * the model completes the task with a result its type's rules accept, fails it, or spends the
 * budget. Every iteration is recorded, so an instance that stops mid-task resumes where it was.
 *
 * What the agent is for, how it behaves and which tasks it takes are declared once, on the
 * companion's `definition`. Its tools are declared here, on the instance, because a tool usually
 * needs the component client, and the instance is what has one.
 *
 * {{{
 * final class CatalogueAnswerer(context: AutonomousAgentContext) extends AutonomousAgent(context):
 *   override def tools = Seq(countItems)
 *
 * object CatalogueAnswerer extends AutonomousAgent.Companion[CatalogueAnswerer](ComponentId("catalogue-answerer")):
 *   def create(context: AutonomousAgentContext) = new CatalogueAnswerer(context)
 *   def definition =
 *     define
 *       .describedAs("Answers questions about the catalogue")
 *       .capability(TaskAcceptance.of(CatalogueTasks.answer).maxIterationsPerTask(5))
 * }}}
 */
abstract class AutonomousAgent(val context: AutonomousAgentContext):

  /**
   * The tools the model may call while working a task, besides `complete_task` and `fail_task`,
   * which every autonomous agent has.
   *
   * Built once per activation of an instance. A tool may run more than once for one request of the
   * model — after a crash, the tools of the last recorded response are run again — so a tool with a
   * side effect should tolerate being repeated.
   */
  def tools: Seq[FunctionTool] = Seq.empty

/** What an autonomous agent is given when an instance is activated. */
trait AutonomousAgentContext:
  def componentId: ComponentId

  /** The instance this is, as the caller named it, or as the platform generated it. */
  def instanceId: String

  def componentClient: ComponentClient

  /** The service-wide default model, if one is configured. */
  def defaultModel: Option[ModelProvider]

  /** The service's secret store, for a tool that needs a credential. */
  def secrets: SecretStore

  /** Other services, called as this one, for a tool that needs one. */
  def services: ServiceClients

private[ankka] final case class SimpleAutonomousAgentContext(
    componentId: ComponentId,
    instanceId: String,
    componentClient: ComponentClient,
    defaultModel: Option[ModelProvider],
    secrets: SecretStore,
    services: ServiceClients
) extends AutonomousAgentContext

object AutonomousAgent:

  /**
   * The task the calling thread is working on: guardrails, tools and rules run on the thread
   * working the task, and this is how one that needs to say which task it is for finds out. Empty
   * on any other thread.
   */
  def currentTask: Option[String] = Option(CurrentTask.get())

  private[autonomous] object CurrentTask extends ThreadLocal[String]:
    def within[A](taskId: String)(body: => A): A =
      set(taskId)
      try body
      finally remove()

  /** Names the platform reserves for the tools every autonomous agent is given. */
  val CompleteTask: String = "complete_task"
  val FailTask: String     = "fail_task"

  /**
   * Declares an autonomous agent to the runtime.
   *
   * `descriptor` is a `def`, as for every companion, and checks the definition when it is called —
   * at registration, so a mistake fails the service at startup rather than at its first task.
   */
  abstract class Companion[A <: AutonomousAgent](val componentId: ComponentId):

    def create(context: AutonomousAgentContext): A

    /** What this agent is for, how it behaves, and which tasks it takes. */
    def definition: AutonomousAgentDefinition

    /** Starts a definition. */
    protected final def define: AutonomousAgentDefinition = AutonomousAgentDefinition.empty

    final def descriptor: AutonomousAgentDescriptor[A] =
      val d        = definition
      val problems = AutonomousAgentDefinition.problems(d)
      if problems.nonEmpty then
        throw IllegalArgumentException(
          problems.mkString(s"invalid autonomous agent '$componentId':\n  - ", "\n  - ", "")
        )
      AutonomousAgentDescriptor(componentId, d, create)

/**
 * An autonomous agent's definition: everything about it that does not depend on an instance.
 *
 * Inert data, like every effect in ankka; the runtime reads it and runs the loop.
 */
final case class AutonomousAgentDefinition private[autonomous] (
    description: String,
    instructions: Option[String],
    guardrails: Vector[Guardrail],
    model: Option[ModelProvider],
    capabilities: Vector[Capability],
    settings: AutonomousAgentSettings
):

  /** What the agent is for. Required; shown to the model on every iteration. */
  def describedAs(text: String): AutonomousAgentDefinition = copy(description = text)

  /** How it should behave: tone, rules of the domain, what to prefer. */
  def instructions(text: String): AutonomousAgentDefinition = copy(instructions = Some(text))

  /**
   * Checks on what goes in and what comes out. Input guardrails see a task's instructions before
   * any model call; output guardrails see a completed result, as JSON, before it is accepted.
   */
  def guardrails(more: Guardrail*): AutonomousAgentDefinition =
    copy(guardrails = guardrails ++ more)

  /** The model to use. Without one, the agent runtime's default model is used. */
  def model(provider: ModelProvider): AutonomousAgentDefinition = copy(model = Some(provider))

  def capability(c: Capability): AutonomousAgentDefinition = copy(capabilities = capabilities :+ c)

  def settings(s: AutonomousAgentSettings): AutonomousAgentDefinition = copy(settings = s)

  /** The task types this agent accepts, each with its iteration budget. */
  def acceptances: Vector[TaskAcceptance] = capabilities.collect { case a: TaskAcceptance => a }

  /** The acceptance for a task type, by its wire name. */
  def accepted(typeName: String): Option[TaskAcceptance] =
    acceptances.find(_.taskType.name == typeName)

object AutonomousAgentDefinition:

  /** A definition assembled from what a process declared, rather than built in Scala. */
  private[ankka] def remote(
      description: String,
      instructions: Option[String],
      guardrails: Vector[Guardrail],
      model: Option[ModelProvider],
      acceptances: Vector[TaskAcceptance],
      settings: AutonomousAgentSettings
  ): AutonomousAgentDefinition =
    AutonomousAgentDefinition(description, instructions, guardrails, model, acceptances, settings)

  private[autonomous] val empty: AutonomousAgentDefinition =
    AutonomousAgentDefinition("", None, Vector.empty, None, Vector.empty, AutonomousAgentSettings())

  /** Everything wrong with a definition, all at once. */
  private[ankka] def problems(d: AutonomousAgentDefinition): Vector[String] =
    val builder = Vector.newBuilder[String]
    if d.description.trim.isEmpty then builder += "a description is required: describedAs(...)"
    if d.acceptances.isEmpty then
      builder += "it accepts no task type: add capability(TaskAcceptance.of(...))"
    d.acceptances.groupBy(_.taskType.name).foreach { (name, as) =>
      if as.sizeIs > 1 then builder += s"task type '$name' is accepted ${as.size} times"
    }
    d.acceptances.foreach { a =>
      if a.budget < 1 then
        builder += s"task type '${a.taskType.name}' needs a budget of at least one iteration"
    }
    d.guardrails.foreach {
      case g: com.thinkmorestupidless.ankka.agent.judgment.JudgedGuardrail if !g.hasRules =>
        builder += s"judged guardrail '${g.name}' has no rules: add onInput(...) or onOutput(...)"
      case _ => ()
    }
    d.guardrails.groupBy(_.name).foreach { (name, gs) =>
      if gs.sizeIs > 1 then builder += s"guardrail '$name' is declared ${gs.size} times"
    }
    builder ++= AutonomousAgentSettings.problems(d.settings)
    builder.result()

  /** What is wrong with an instance's tools. Checked at startup against one instance. */
  private[ankka] def toolProblems(tools: Seq[FunctionTool]): Vector[String] =
    val reserved = Set(AutonomousAgent.CompleteTask, AutonomousAgent.FailTask)
    tools.map(_.name).filter(reserved).distinct.map(n => s"tool name '$n' is reserved").toVector ++
      tools.groupBy(_.name).collect {
        case (n, ts) if ts.sizeIs > 1 => s"tool '$n' is declared ${ts.size} times"
      }

/**
 * Something an autonomous agent can do. In this version the only capability is accepting tasks of a
 * type; coordinating with other agents is a capability a later version adds here.
 */
sealed trait Capability

/** Accepts tasks of one type, spending at most `maxIterationsPerTask` model calls on each. */
final case class TaskAcceptance private (taskType: TaskType[?], budget: Int) extends Capability:
  def maxIterationsPerTask(n: Int): TaskAcceptance = copy(budget = n)

object TaskAcceptance:
  /** Accepts `taskType`, with a budget of ten iterations until one is set. */
  def of(taskType: TaskType[?]): TaskAcceptance = TaskAcceptance(taskType, 10)

/**
 * When an instance warns that it is struggling, when it gives up, and how it waits.
 *
 * @param approachingBudgetAt
 *   the share of a task's budget after which the model is told how many iterations remain and a
 *   notification says the task is approaching its limit
 * @param repeatedFailureAt
 *   consecutive failed iterations — a model call, or a result check, that failed — after which a
 *   notification says so
 * @param maxConsecutiveFailures
 *   consecutive failed iterations after which the task fails with the last error
 * @param dependencyStuckAfter
 *   how long a task may wait on a dependency before a notification says it is stuck
 * @param dependencyPoll
 *   how often a waiting task checks its dependencies
 * @param idlePassivationAfter
 *   how long an instance with nothing to do and nobody watching stays in memory
 */
final case class AutonomousAgentSettings(
    approachingBudgetAt: Double = 0.8,
    repeatedFailureAt: Int = 3,
    maxConsecutiveFailures: Int = 5,
    dependencyStuckAfter: FiniteDuration = 5.minutes,
    dependencyPoll: FiniteDuration = 2.seconds,
    idlePassivationAfter: FiniteDuration = 2.minutes
)

object AutonomousAgentSettings:
  private[autonomous] def problems(s: AutonomousAgentSettings): Vector[String] =
    Vector(
      Option.when(!(s.approachingBudgetAt > 0 && s.approachingBudgetAt <= 1))(
        "approachingBudgetAt must be in (0, 1]"
      ),
      Option.when(s.repeatedFailureAt < 1)("repeatedFailureAt must be at least 1"),
      Option.when(s.maxConsecutiveFailures < 1)("maxConsecutiveFailures must be at least 1"),
      Option.when(s.dependencyPoll <= FiniteDuration(0, "ms"))("dependencyPoll must be positive"),
      Option.when(s.idlePassivationAfter <= FiniteDuration(0, "ms"))(
        "idlePassivationAfter must be positive"
      )
    ).flatten

/** The registered form of an autonomous agent. */
final case class AutonomousAgentDescriptor[A <: AutonomousAgent](
    componentId: ComponentId,
    definition: AutonomousAgentDefinition,
    create: AutonomousAgentContext => A
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.AutonomousAgent

  /**
   * The same for every autonomous agent: what a caller can ask of an instance, and the one thing an
   * instance does of its own accord, which is where its calls come from.
   */
  override def declaredHandlers: Vector[DeclaredHandler] = AutonomousAgentDescriptor.Declared

object AutonomousAgentDescriptor:

  /** What an instance is doing when it works on a task: the handler its calls are attributed to. */
  val Iteration: String = "iteration"

  private val Declared: Vector[DeclaredHandler] =
    DeclaredHandler.sorted(
      Vector(
        HostProtocol.Assign,
        HostProtocol.RunSingleTask,
        HostProtocol.Dequeue,
        HostProtocol.Suspend,
        HostProtocol.Resume,
        HostProtocol.Terminate,
        HostProtocol.Decide
      ).map(method => DeclaredHandler(method.toString, HandlerKind.Command)) ++ Vector(
        DeclaredHandler(HostProtocol.Notifications.toString, HandlerKind.Stream),
        DeclaredHandler(Iteration, HandlerKind.Step)
      )
    )
