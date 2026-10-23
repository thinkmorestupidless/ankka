package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.core.effect as se
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.client.{CallerError, NoInputStepRef, StepRef}
import com.thinkmorestupidless.ankka.javasdk.component.{
  EntityContext,
  RecoverStrategy,
  Workflow,
  WorkflowSettings
}
import com.thinkmorestupidless.ankka.javasdk.effect.*
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.CommandContext

import java.lang.reflect.{InvocationTargetException, Method}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * A workflow's steps, named by method reference: `Checkout::charge` resolved to the step's `@Step`
 * name and input serializer, through the `SerializedLambda` a call's method reference is resolved
 * through too.
 */
private[ankka] final class StepIndex(workflow: Class[?], steps: Vector[StepPlan]):
  private val byKey = steps.map(s => s.javaKey -> s).toMap

  def plan(ref: AnyRef): StepPlan =
    val lambda = Refs.serialized(ref)
    val key = (
      lambda.getImplClass.replace('/', '.'),
      lambda.getImplMethodName,
      lambda.getImplMethodSignature
    )
    byKey.getOrElse(
      key,
      throw CallerError(
        if key._2.startsWith("lambda$") then
          "a step is named by a method reference, such as Checkout::charge, not a lambda"
        else s"${key._1}::${key._2} is not a @Step of ${workflow.getSimpleName}"
      )
    )

  def to(ref: StepRef[?, ?], input: Any): se.StepRef =
    val step = plan(ref)
    step.input match
      case Some(serializer) => se.StepRef(step.name, Some(serializer.toBytes(input)))
      case None =>
        throw CallerError(s"step '${step.name}' takes no input; name it as a NoInputStepRef")

  def to(ref: NoInputStepRef[?]): se.StepRef =
    val step = plan(ref)
    if step.input.isDefined then
      throw CallerError(s"step '${step.name}' takes an input; transition to it with one")
    se.StepRef(step.name, None)

private[ankka] object StepIndex:
  val empty: StepIndex = StepIndex(classOf[Object], Vector.empty)

/** A Java workflow's settings, its steps named by reference resolved to their names. */
private[bridge] object WorkflowSettingsReader:

  private val settingsHook: Method =
    val m = classOf[Workflow[?]].getDeclaredMethod("settings")
    m.setAccessible(true)
    m

  def read(
      cls: Class[?],
      componentId: String,
      construct: EntityContext => AnyRef,
      steps: Vector[StepPlan]
  ): Either[Vector[(String, String)], sdk.WorkflowSettings] =
    val index = StepIndex(cls, steps)
    val cell  = EntityCell("", componentId, Clients.unavailable)
    val java =
      try Right(EntityCell.constructing(cell)(construct(cell.entityContext())))
      catch case e: Exception => Left(Vector("" -> s"could not be constructed: $e"))
    java.flatMap { instance =>
      val settings =
        try Right(settingsHook.invoke(instance).asInstanceOf[WorkflowSettings])
        catch
          case e: InvocationTargetException =>
            Left(Vector("settings" -> s"threw ${e.getCause}"))
      settings.flatMap(convert(index, _))
    }

  private def convert(
      index: StepIndex,
      java: WorkflowSettings
  ): Either[Vector[(String, String)], sdk.WorkflowSettings] =
    val problems = Vector.newBuilder[(String, String)]
    def name(ref: AnyRef): Option[String] =
      try Some(index.plan(ref).name)
      catch
        case e: CallerError =>
          problems += ("settings" -> e.getMessage)
          None
    def recovery(r: RecoverStrategy): sdk.RecoverStrategy =
      val failover = r.failover.toScala.flatMap { ref =>
        val step = name(ref)
        step.foreach { s =>
          if index.plan(ref).input.isDefined then
            problems += ("settings" -> s"fails over to '$s', which takes an input; a failover takes none")
        }
        step
      }
      sdk.RecoverStrategy(r.maxRetries, failover)
    val stepTimeouts = java.stepTimeouts.asScala.toVector.flatMap { (ref, d) =>
      name(ref.asInstanceOf[AnyRef]).map(_ -> Effects.finite(d))
    }.toMap
    val stepRecovery = java.stepRecovery.asScala.toVector.flatMap { (ref, r) =>
      name(ref.asInstanceOf[AnyRef]).map(_ -> recovery(r))
    }.toMap
    val settings = sdk.WorkflowSettings(
      java.timeout.toScala.map(Effects.finite),
      Effects.finite(java.defaultStepTimeout),
      stepTimeouts,
      recovery(java.defaultRecovery),
      stepRecovery
    )
    val found = problems.result()
    if found.nonEmpty then Left(found) else Right(settings)

/** The effects a Java workflow's commands and steps return, resolving steps through its index. */
private[bridge] final class JavaWorkflowEffects(steps: StepIndex) extends WorkflowEffects[Any]:
  private val effects = new se.WorkflowEffects[Any]()

  def updateState(state: Any): WorkflowBuilder[Any] = Builder(effects.updateState(state))
  def transitionTo[W, I](step: StepRef[W, I], input: I): WorkflowBuilder[Any] =
    Builder(effects.transitionTo(steps.to(step, input)))
  def transitionTo[W](step: NoInputStepRef[W]): WorkflowBuilder[Any] =
    Builder(effects.transitionTo(steps.to(step)))
  def delete(): WorkflowBuilder[Any]               = Builder(effects.delete())
  def reply[R](reply: R): ReadOnlyEffect[R]        = Effects.Held(effects.reply(reply))
  def error[R](message: String): ReadOnlyEffect[R] = Effects.Held(effects.error(message))
  def error[R](problem: Problem): ReadOnlyEffect[R] =
    val e = Effects.toCommandError(problem)
    Effects.Held(effects.error(e.message, e.code))

  private final case class Builder(scala: se.WorkflowCommandBuilder[Any])
      extends WorkflowBuilder[Any]:
    def transitionTo[W, I](step: StepRef[W, I], input: I): WorkflowBuilder[Any] =
      Builder(scala.transitionTo(steps.to(step, input)))
    def transitionTo[W](step: NoInputStepRef[W]): WorkflowBuilder[Any] =
      Builder(scala.transitionTo(steps.to(step)))
    def thenReply[R](reply: R): Effect[R] = Effects.Held(scala.thenReply(reply))
    def thenReplyState(): Effect[Any]     = Effects.Held(scala.thenReplyState)
    def thenDone(): Effect[Done]          = Effects.Held(scala.thenReply(Done.DONE))

private[bridge] final class JavaStepEffects(steps: StepIndex) extends StepEffects[Any]:
  private val effects = new se.WorkflowStepEffects[Any]()

  def updateState(state: Any): StepBuilder[Any] = Builder(effects.updateState(state))
  def thenTransitionTo[W, I](step: StepRef[W, I], input: I): StepEffect =
    held(effects.thenTransitionTo(steps.to(step, input)))
  def thenTransitionTo[W](step: NoInputStepRef[W]): StepEffect =
    held(effects.thenTransitionTo(steps.to(step)))
  def thenPause(): StepEffect = held(effects.thenPause())
  def thenPause[W](after: Duration, onTimeout: NoInputStepRef[W]): StepEffect =
    held(effects.thenPause(Effects.finite(after), steps.to(onTimeout)))
  def thenEnd(): StepEffect                 = held(effects.thenEnd)
  def thenFail(message: String): StepEffect = held(effects.thenFail(message))

  private def held(scala: se.WorkflowStepEffect[Any]): StepEffect = JavaStepEffects.Held(scala)

  private final case class Builder(scala: se.WorkflowStepBuilder[Any]) extends StepBuilder[Any]:
    def thenTransitionTo[W, I](step: StepRef[W, I], input: I): StepEffect =
      held(scala.thenTransitionTo(steps.to(step, input)))
    def thenTransitionTo[W](step: NoInputStepRef[W]): StepEffect =
      held(scala.thenTransitionTo(steps.to(step)))
    def thenPause(): StepEffect = held(scala.thenPause())
    def thenPause[W](after: Duration, onTimeout: NoInputStepRef[W]): StepEffect =
      held(scala.thenPause(Effects.finite(after), steps.to(onTimeout)))
    def thenEnd(): StepEffect                 = held(scala.thenEnd)
    def thenFail(message: String): StepEffect = held(scala.thenFail(message))

private[bridge] object JavaStepEffects:
  final class Held(val scala: se.WorkflowStepEffect[Any]) extends StepEffect with Effects.Holding

/** A Java workflow as the Scala SDK's own companion, so the workflow runtime hosts it unchanged. */
private[ankka] object Workflows:

  def companion(plan: ComponentPlan, refs: Refs): sdk.Workflow.Companion[JavaWorkflowHost, Any] =
    val index = StepIndex(plan.cls, plan.steps)
    new sdk.Workflow.Companion[JavaWorkflowHost, Any](
      ComponentId(plan.componentId),
      CodecBridge.state(plan.state)
    ):
      def create(ctx: sdk.WorkflowContext): JavaWorkflowHost =
        val cell =
          EntityCell(ctx.workflowId, plan.componentId, Clients.of(ctx.componentClient, refs))
        cell.steps = index
        JavaWorkflowHost(Adapters.construct(plan, cell), cell, plan.settings)

      plan.handlers.foreach { h =>
        val where = s"${plan.cls.getSimpleName}.${h.method.getName}"
        def run(host: JavaWorkflowHost, input: Any) =
          Effects
            .unwrap(h.invoke(host.java, input), where)
            .asInstanceOf[se.WorkflowEffect[Any, Any]]
        val out = CodecBridge.toScala(h.output)
        (h.input, h.readOnly) match
          case (Some(in), false) =>
            command[Any, Any](h.wireName)(host => i => run(host, i))(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (Some(in), true) =>
            query[Any, Any](h.wireName)(host =>
              i => run(host, i).asInstanceOf[se.WorkflowReadOnlyEffect[Any, Any]]
            )(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (None, false) =>
            command[Any](h.wireName)(host => run(host, null))(using out): Unit
          case (None, true) =>
            query[Any](h.wireName)(host =>
              run(host, null).asInstanceOf[se.WorkflowReadOnlyEffect[Any, Any]]
            )(using
              out
            ): Unit
      }

      plan.steps.foreach { s =>
        val where = s"${plan.cls.getSimpleName}.${s.method.getName}"
        def run(host: JavaWorkflowHost, input: Any) =
          Effects.unwrap(s.invoke(host.java, input), where).asInstanceOf[se.WorkflowStepEffect[Any]]
        s.input match
          case Some(in) =>
            step[Any](s.name)(host => i => run(host, i))(using CodecBridge.toScala(in)): Unit
          case None => step(s.name)(host => run(host, null)): Unit
      }

  private[bridge] object Hooks:
    private val empty =
      val m = classOf[Workflow[?]].getDeclaredMethod("emptyState")
      m.setAccessible(true)
      m
    def emptyState(java: AnyRef): Any =
      try empty.invoke(java)
      catch case e: InvocationTargetException => throw e.getCause

/** A Java workflow, hosted. */
private[ankka] final class JavaWorkflowHost(
    val java: AnyRef,
    cell: EntityCell,
    override val settings: sdk.WorkflowSettings
) extends sdk.Workflow[Any]:
  def emptyState: Any = Workflows.Hooks.emptyState(java)

  override private[ankka] def _setState(state: Any): Unit =
    super._setState(state)
    cell.state = state
    cell.stateSet = true

  override private[ankka] def _setContext(ctx: Option[CommandContext]): Unit =
    super._setContext(ctx)
    cell.command = ctx
