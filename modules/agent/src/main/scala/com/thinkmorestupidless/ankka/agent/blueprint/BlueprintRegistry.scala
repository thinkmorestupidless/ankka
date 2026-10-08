package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail, Json, ModelProvider}
import com.thinkmorestupidless.ankka.agent.judgment.Question
import com.thinkmorestupidless.ankka.agent.mcp.McpServer
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore, ServiceClients}

/**
 * What a service hands the registry's builder when the service starts. A tool that writes an entity
 * needs the component client when it is built, which is why the registry is built from a context
 * and not before the service exists.
 */
trait BlueprintContext:
  def componentClient: ComponentClient

  /** The service's views, for a tool that reads one. */
  def viewClient: ViewClient
  def services: ServiceClients
  def secrets: SecretStore

  /** Whether the service registered a `TimerRuntime`; a schedule needs one. */
  def hasTimers: Boolean

/**
 * A handler a call step runs: given which run it serves and what the step reads, it answers the
 * step's result. It may be run again after a restart, as a tool may, so it tolerates a repeat.
 */
final class BlueprintHandler private (val name: String, val run: (RunRef, Json) => Json):
  override def toString: String = s"BlueprintHandler($name)"

object BlueprintHandler:
  def apply(name: String)(run: (RunRef, Json) => Json): BlueprintHandler =
    if name.isEmpty then throw IllegalArgumentException("a handler needs a name")
    else new BlueprintHandler(name, run)

/**
 * What a blueprint may name: the tools, MCP servers, models, guardrails, judgment questions and
 * handlers a service registers for its blueprints, and the blueprints it carries in its code.
 * Nothing lists a service's agents' tools — an agent chooses them inside its handler — so this is
 * declared.
 *
 * A plain class with fields named apart from its builders, as `AgentEffect` is: a field called
 * `tools` would shadow `tools(...)`.
 */
final class BlueprintRegistry private (
    val toolsByName: Map[String, FunctionTool],
    val servers: Vector[McpServer],
    val modelsByName: Map[String, ModelProvider],
    val guardrailsByName: Map[String, Guardrail],
    val questionsByName: Map[String, Question[?]],
    val handlersByName: Map[String, BlueprintHandler],
    val carried: Vector[Blueprint],
    val hasTimers: Boolean
):

  private def copy(
      toolsByName: Map[String, FunctionTool] = toolsByName,
      servers: Vector[McpServer] = servers,
      modelsByName: Map[String, ModelProvider] = modelsByName,
      guardrailsByName: Map[String, Guardrail] = guardrailsByName,
      questionsByName: Map[String, Question[?]] = questionsByName,
      handlersByName: Map[String, BlueprintHandler] = handlersByName,
      carried: Vector[Blueprint] = carried,
      hasTimers: Boolean = hasTimers
  ): BlueprintRegistry =
    new BlueprintRegistry(
      toolsByName,
      servers,
      modelsByName,
      guardrailsByName,
      questionsByName,
      handlersByName,
      carried,
      hasTimers
    )

  private def adding[A](
      kind: String,
      existing: Map[String, A],
      more: Seq[(String, A)]
  ): Map[String, A] =
    more.foldLeft(existing) { case (acc, (name, value)) =>
      if acc.contains(name) then
        throw IllegalArgumentException(s"$kind '$name' is registered twice for blueprints")
      else acc + (name -> value)
    }

  def tools(more: FunctionTool*): BlueprintRegistry =
    copy(toolsByName = adding("tool", toolsByName, more.map(t => t.name -> t)))

  /** Connected when the service starts, as an agent's are; their tools join `toolsByName`. */
  def mcpServers(more: McpServer*): BlueprintRegistry =
    val names = (servers ++ more).map(_.name)
    names.diff(names.distinct).headOption.foreach { n =>
      throw IllegalArgumentException(s"MCP server '$n' is registered twice for blueprints")
    }
    copy(servers = servers ++ more)

  /** `default` is the runtime's default model and needs no registering. */
  def models(more: (String, ModelProvider)*): BlueprintRegistry =
    if more.exists(_._1 == Worker.DefaultModel) then
      throw IllegalArgumentException(
        s"'${Worker.DefaultModel}' is the runtime's default model; register others"
      )
    else copy(modelsByName = adding("model", modelsByName, more))

  def guardrails(more: (String, Guardrail)*): BlueprintRegistry =
    copy(guardrailsByName = adding("guardrail", guardrailsByName, more))

  def questions(more: Question[?]*): BlueprintRegistry =
    copy(questionsByName = adding("judgment question", questionsByName, more.map(q => q.id -> q)))

  /** Handlers a call step may run, given what the step reads. */
  def handlers(more: BlueprintHandler*): BlueprintRegistry =
    copy(handlersByName = adding("handler", handlersByName, more.map(h => h.name -> h)))

  /** Blueprints carried in the service's code, registered when it starts. */
  def carrying(more: Blueprint*): BlueprintRegistry = copy(carried = carried ++ more)

  private[ankka] def withMcpTools(tools: Vector[FunctionTool]): BlueprintRegistry =
    copy(toolsByName = adding("tool", toolsByName, tools.map(t => t.name -> t)))

  private[ankka] def withTimers(present: Boolean): BlueprintRegistry = copy(hasTimers = present)

  def tool(name: String): Option[FunctionTool]        = toolsByName.get(name)
  def guardrail(name: String): Option[Guardrail]      = guardrailsByName.get(name)
  def question(name: String): Option[Question[?]]     = questionsByName.get(name)
  def handler(name: String): Option[BlueprintHandler] = handlersByName.get(name)

  /** `default` is answered by the runtime, which holds that model. */
  def hasModel(name: String): Boolean = name == Worker.DefaultModel || modelsByName.contains(name)

object BlueprintRegistry:
  val empty: BlueprintRegistry =
    new BlueprintRegistry(
      Map.empty,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      Vector.empty,
      hasTimers = false
    )
