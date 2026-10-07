package com.thinkmorestupidless.ankka.agent.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{Json, ModelProvider}
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * What a task carries when its agent's definition is the task's own rather than the companion's:
 * the instructions, the model, the tools and guardrails by name, the iteration budget, the shape of
 * the result, and whatever context names where it came from. Resolving the names into a definition
 * is the service's, through a `TaskDefinitionResolver`; the platform only carries them, so a task's
 * record names nothing a service did not register.
 */
final case class TaskDefinition(
    instructions: String,
    model: String,
    tools: Vector[String],
    guardrails: Vector[String],
    budget: Int,
    resultSchema: Json,
    context: Map[String, String] = Map.empty
)

object TaskDefinition:
  given JsonValueCodec[TaskDefinition] = Codecs.make

/** A task definition resolved against what the service registered: ready to be iterated. */
final case class ResolvedTask(
    definition: AutonomousAgentDefinition,
    agent: AutonomousAgent,
    model: ModelProvider,
    acceptance: TaskAcceptance
)

/**
 * Turns the names a task definition carries into a definition, tools and a model, or says why not.
 */
trait TaskDefinitionResolver:
  def resolve(
      definition: TaskDefinition,
      context: AutonomousAgentContext
  ): Either[String, ResolvedTask]

object TaskDefinitionResolver:
  /** A service that resolves none: a task that carries a definition fails, saying so. */
  val none: TaskDefinitionResolver = (_, _) =>
    Left(
      "the task carries a definition of its own, and this service resolves none: it runs no blueprints"
    )
