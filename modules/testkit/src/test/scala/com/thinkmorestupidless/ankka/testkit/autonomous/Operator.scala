package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.FunctionTool
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.ComponentId

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.DurationInt

/**
 * An operations agent whose disruptive tools wait for a person.
 *
 * Its own fixture, apart from the answerer, so a suite about approvals cannot change the tools
 * every other autonomous suite offers its model.
 */
final class Operator(context: AutonomousAgentContext) extends AutonomousAgent(context):
  // docs:start autonomous-requires-approval
  override def tools: Seq[FunctionTool] = Seq(
    FunctionTool
      .named("restart_service")
      .describedAs("Restarts a service. An operator approves every restart.")
      .param[String]("service", "the service to restart")
      .handle { (service: String) =>
        Operator.runs.add(s"restart_service($service)"): Unit
        s"restarted $service"
      }
      .requiresApproval,
    FunctionTool
      .named("drain_node")
      .describedAs("Drains a node of its work.")
      .param[String]("node", "the node to drain")
      .handle { (node: String) =>
        Operator.runs.add(s"drain_node($node)"): Unit
        s"drained $node"
      }
      .requiresApproval(2.seconds),
    FunctionTool
      .named("read_metrics")
      .describedAs("Reads a service's metrics.")
      .param[String]("service", "the service to read")
      .handle { (service: String) =>
        Operator.runs.add(s"read_metrics($service)"): Unit
        s"$service is healthy"
      }
  )
  // docs:end autonomous-requires-approval

object Operator extends AutonomousAgent.Companion[Operator](ComponentId("operator")):
  def create(context: AutonomousAgentContext) = new Operator(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Keeps services running")
      .capability(TaskAcceptance.of(Tasks.summary).maxIterationsPerTask(4))
      .settings(AutonomousAgentSettings(idlePassivationAfter = 1.second))

  /** Tool runs, in order, as `tool(arguments)`. */
  val runs: CopyOnWriteArrayList[String] = CopyOnWriteArrayList()

  def runsOf(tool: String): Vector[String] =
    runs.toArray.toVector.map(_.toString).filter(_.startsWith(s"$tool("))
