package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{AutonomousAgentDetail, Component}
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail, Json, ToolSpec}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  GuardrailStage,
  TaskResultVerdict
}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * An autonomous agent declared by a process in another language.
 *
 * Its whole definition arrives in discovery, so there is nothing to plan per task: the sidecar runs
 * the loop exactly as for a Scala agent and asks the process for three things only — run a tool,
 * check a guardrail, check a task rule — each naming the task's session, `task:<task id>`. The task
 * records, the instance and the loop are the sidecar's.
 */
object RemoteAutonomousAgent:

  /** Everything wrong with an autonomous agent's detail, all at once. */
  def problems(id: String, d: AutonomousAgentDetail): Vector[String] =
    val p = Vector.newBuilder[String]
    def named(what: String, names: Seq[String]): Unit =
      if names.exists(_.isEmpty) then p += s"autonomous agent '$id': a $what has no name"
      names.groupBy(identity).collect { case (n, ns) if n.nonEmpty && ns.sizeIs > 1 => n }.foreach {
        n =>
          p += s"autonomous agent '$id': $what '$n' is declared ${names.count(_ == n)} times"
      }
    if d.description.trim.isEmpty then p += s"autonomous agent '$id' has no description"
    named("tool", d.tools.map(_.name))
    named("guardrail", d.guardrails)
    named("task type", d.taskTypes.map(_.name))
    d.tools.foreach { t =>
      if Set(AutonomousAgent.CompleteTask, AutonomousAgent.FailTask).contains(t.name) then
        p += s"autonomous agent '$id': tool name '${t.name}' is reserved"
      if t.description.isEmpty then
        p += s"autonomous agent '$id': tool '${t.name}' has no description; the model decides by it"
      if t.inputSchemaJson.nonEmpty && Json.parse(t.inputSchemaJson).isLeft then
        p += s"autonomous agent '$id': tool '${t.name}' has an input schema that is not JSON"
    }
    d.taskTypes.foreach { t =>
      if t.description.isEmpty then
        p += s"autonomous agent '$id': task type '${t.name}' has no description"
      t.resultSchemaJson.filter(s => Json.parse(s).isLeft).foreach { _ =>
        p += s"autonomous agent '$id': task type '${t.name}' has a result schema that is not JSON"
      }
      named(s"rule of task type '${t.name}' — a rule", t.rules)
    }
    if d.accepts.isEmpty then p += s"autonomous agent '$id' accepts no task type"
    d.accepts.groupBy(_.taskType).collect { case (n, as) if as.sizeIs > 1 => n }.foreach { n =>
      p += s"autonomous agent '$id': task type '$n' is accepted ${d.accepts.count(_.taskType == n)} times"
    }
    d.accepts.foreach { a =>
      if !d.taskTypes.exists(_.name == a.taskType) then
        p += s"autonomous agent '$id' accepts task type '${a.taskType}', which it does not declare"
      if a.maxIterations < 1 then
        p += s"autonomous agent '$id': task type '${a.taskType}' needs a budget of at least one iteration"
    }
    p.result()

  /** The descriptor the `AgentRuntime` hosts, from what the process declared. */
  def descriptor(
      component: Component,
      conversation: Conversation,
      models: Models,
      callTimeout: FiniteDuration,
      toolTimeout: FiniteDuration = 2.minutes
  ): AutonomousAgentDescriptor[AutonomousAgent] =
    val id     = ComponentId(component.id)
    val detail = component.detail.autonomousAgent.getOrElse(AutonomousAgentDetail())

    def await[A](future: Future[A], timeout: FiniteDuration): A =
      scala.concurrent.blocking(Await.result(future, timeout))

    /** Tools and rules run on the iteration's thread, which knows the task. */
    def session: String =
      AutonomousAgent.currentTask
        .map(IterationLoop.sessionIdFor(_).toString)
        .getOrElse(throw IllegalStateException("a remote tool ran outside an iteration"))

    def taskId: String = AutonomousAgent.currentTask.getOrElse("")

    // The process decodes the result as its type and runs its rules, in one call. A process that
    // fails to answer has decided nothing: that throws, and the iteration is tried again, rather
    // than rejecting a result nobody looked at.
    // The check is the iteration's work, as a tool's run is: the process is told the trace and
    // the handler, so a call a rule makes is the iteration's call, not nobody's.
    def check(typeName: String)(resultJson: String): TaskType.Verdict =
      await(
        conversation
          .checkTaskResult(id, taskId, typeName, resultJson, Trace.outbound(Metadata.empty)),
        callTimeout
      ) match
        case TaskResultVerdict.Accept               => TaskType.Verdict.Accepted(resultJson)
        case TaskResultVerdict.Malformed(problem)   => TaskType.Verdict.Malformed(problem)
        case TaskResultVerdict.Reject(rule, reason) => TaskType.Verdict.Rejected(rule, reason)

    val taskTypes: Map[String, TaskType[?]] = detail.taskTypes.map { t =>
      t.name -> TaskType.remote(
        t.name,
        t.description,
        t.resultSchemaJson.flatMap(s => Json.parse(s).toOption),
        check(t.name)
      )
    }.toMap

    val guardrails = detail.guardrails.toVector.map { guardName =>
      new Guardrail:
        val name: String = guardName
        override def checkInput(text: String): Either[String, Unit] =
          await(
            conversation.checkGuardrail(
              id,
              session,
              guardName,
              GuardrailStage.Input,
              text,
              Trace.outbound(Metadata.empty)
            ),
            callTimeout
          )
        override def checkOutput(text: String): Either[String, Unit] =
          await(
            conversation.checkGuardrail(
              id,
              session,
              guardName,
              GuardrailStage.Output,
              text,
              Trace.outbound(Metadata.empty)
            ),
            callTimeout
          )
    }

    val tools = detail.tools.toVector.map { t =>
      val schema = Json.parse(t.inputSchemaJson).getOrElse(Json.obj("type" -> Json.str("object")))
      FunctionTool.raw(ToolSpec(t.name, t.description, schema)) { arguments =>
        await(
          conversation
            .invokeTool(id, session, t.name, arguments.render, Trace.outbound(Metadata.empty)),
          toolTimeout
        )
      }
    }

    val model = detail.model match
      case Some(name) =>
        models
          .named(name)
          .orElse(
            throw CommandError(
              s"autonomous agent '$id' names model '$name', which this sidecar is not configured with",
              ErrorCode.Unavailable
            )
          )
      case None => models.default

    val settings = detail.settings.fold(AutonomousAgentSettings()) { s =>
      val defaults = AutonomousAgentSettings()
      defaults.copy(
        approachingBudgetAt = s.approachingBudgetAt.getOrElse(defaults.approachingBudgetAt),
        repeatedFailureAt = s.repeatedFailureAt.getOrElse(defaults.repeatedFailureAt),
        maxConsecutiveFailures =
          s.maxConsecutiveFailures.getOrElse(defaults.maxConsecutiveFailures),
        dependencyStuckAfter = s.dependencyStuckAfterMillis
          .map(FiniteDuration(_, "ms"))
          .getOrElse(defaults.dependencyStuckAfter)
      )
    }

    val definition = AutonomousAgentDefinition.remote(
      description = detail.description,
      instructions = detail.instructions,
      guardrails = guardrails,
      model = model,
      acceptances = detail.accepts.toVector.flatMap(a =>
        taskTypes
          .get(a.taskType)
          .map(t => TaskAcceptance.of(t).maxIterationsPerTask(a.maxIterations))
      ),
      settings = settings
    )

    AutonomousAgentDescriptor[AutonomousAgent](id, definition, context => Remote(context, tools))

  /** An instance whose tools run in the process. */
  private final class Remote(context: AutonomousAgentContext, remoteTools: Vector[FunctionTool])
      extends AutonomousAgent(context):
    override def tools: Seq[FunctionTool] = remoteTools
