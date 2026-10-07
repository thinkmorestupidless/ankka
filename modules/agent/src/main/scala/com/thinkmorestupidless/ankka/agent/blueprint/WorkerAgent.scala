package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.{FunctionTool, Json, ModelProvider}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * The one autonomous agent every work step's task goes to. It has no tools and no instructions of
 * its own: each task carries its worker's definition, which the host resolves against the service's
 * registry for that task alone, so a worker's instructions, model, tools and budget are the
 * blueprint's and a new version takes effect at the next run.
 */
final class WorkerAgent(context: AutonomousAgentContext) extends AutonomousAgent(context)

object WorkerAgent
    extends AutonomousAgent.Companion[WorkerAgent](ComponentId("ankka-blueprint-worker")):

  /**
   * The one task type a work step's task has; its result shape is the step's, from the definition.
   */
  val StepTaskType: String = "blueprint-step"

  /** The type as the companion accepts it; the shape and the rule come with each task (R4). */
  val stepType: TaskType[Json] =
    TaskType.json(
      StepTaskType,
      "One step of a blueprint's run, as the task's definition says.",
      Json.obj("type" -> Json.str("object")),
      Vector.empty
    )

  def create(context: AutonomousAgentContext) = new WorkerAgent(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Carries out the work steps of blueprints, each as its task's definition says.")
      .capability(TaskAcceptance.of(stepType))

  /** The platform's own. */
  val platformDescriptor: AutonomousAgentDescriptor[WorkerAgent] = descriptor.copy(platform = true)

/**
 * From a worker and a step to a task's definition, and back from the task's to what the host
 * iterates.
 */
private[ankka] object BlueprintTasks:

  def definitionOf(worker: Worker, shape: Shape, ref: RunRef): TaskDefinition =
    TaskDefinition(
      worker.instructions,
      worker.model,
      worker.tools,
      worker.guardrails,
      worker.budget,
      shape.schema,
      Map(
        "run"       -> ref.runId,
        "step"      -> ref.step,
        "blueprint" -> ref.blueprint,
        "version"   -> ref.version.toString
      )
    )

  def refOf(definition: TaskDefinition): Option[RunRef] =
    for
      run       <- definition.context.get("run")
      step      <- definition.context.get("step")
      blueprint <- definition.context.get("blueprint")
      version   <- definition.context.get("version").flatMap(_.toIntOption)
    yield RunRef(run, step, blueprint, version)

  /** Resolves a task's definition against the registry: the service's part of R4. */
  def resolver(
      registry: BlueprintRegistry,
      defaultModel: Option[ModelProvider]
  ): TaskDefinitionResolver =
    (d, context) =>
      val model =
        if d.model == Worker.DefaultModel then
          defaultModel.toRight("the task names the default model, and the runtime has none")
        else
          registry.modelsByName
            .get(d.model)
            .toRight(
              s"the task names the model '${d.model}', which is not registered for blueprints"
            )
      val shape = Shape
        .fromJson(d.resultSchema)
        .left
        .map(ps => s"the task's result shape is not one the platform checks: ${ps.mkString("; ")}")
      for
        m <- model
        s <- shape
      yield
        val ref = refOf(d)
        val wrapped: Seq[FunctionTool] =
          d.tools.flatMap(registry.tool).map(t => ref.fold(t)(RunContext.wrap(t, _)))
        val guardrails = d.guardrails.flatMap(registry.guardrail)
        val rule = TaskRule[Json](
          "shape",
          json =>
            s.check(json) match
              case Vector() => TaskRule.Accepted
              case problems =>
                TaskRule.Rejected(
                  s"the result does not have the step's shape: ${problems.mkString("; ")}"
                )
        )
        val where = ref.fold("a blueprint's step")(r =>
          s"step '${r.step}' of a run of blueprint '${r.blueprint}'"
        )
        val taskType = TaskType.json(
          WorkerAgent.StepTaskType,
          s"One step of a blueprint's run: $where.",
          s.schema,
          Vector(rule)
        )
        val definition = AutonomousAgentDefinition.remote(
          s"Carries out $where.",
          Some(d.instructions),
          guardrails,
          Some(m),
          Vector(TaskAcceptance.of(taskType).maxIterationsPerTask(d.budget)),
          AutonomousAgentSettings()
        )
        val agent = new AutonomousAgent(context):
          override def tools: Seq[FunctionTool] = wrapped
        ResolvedTask(definition, agent, m, definition.acceptances.head)
