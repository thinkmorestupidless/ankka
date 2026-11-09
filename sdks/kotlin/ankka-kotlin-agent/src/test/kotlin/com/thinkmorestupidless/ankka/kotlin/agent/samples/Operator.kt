package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition
import com.thinkmorestupidless.ankka.agent.javasdk.FunctionTool
import com.thinkmorestupidless.ankka.agent.javasdk.Task
import com.thinkmorestupidless.ankka.agent.javasdk.TaskType
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A summary of what was done, as text. */
val SUMMARY: TaskType<String> = Task.named("summary").describedAs("Summarise what was done")

/** An operations agent whose disruptive tools wait for a person. */
@ComponentId("operator")
class Operator : AutonomousAgent() {

    data class Service(@Describe("the service to restart") val service: String)

    data class Node(@Describe("the node to drain") val node: String)

    data class Metrics(@Describe("the service to read") val service: String)

    override fun definition(): AutonomousDefinition =
        AutonomousDefinition.describedAs("Keeps services running").accepts(SUMMARY, 4)

    // docs:start autonomous-requires-approval
    override fun tools(): List<Any> =
        listOf(
            FunctionTool.of("restart_service", "Restarts a service. An operator approves every restart.", Service::class.java) {
                RUNS.add("restart_service(${it.service})")
                "restarted ${it.service}"
            }
                .requiresApproval(),
            FunctionTool.of("drain_node", "Drains a node of its work.", Node::class.java) {
                RUNS.add("drain_node(${it.node})")
                "drained ${it.node}"
            }
                .requiresApproval(Duration.ofSeconds(2)),
            FunctionTool.of("read_metrics", "Reads a service's metrics.", Metrics::class.java) {
                RUNS.add("read_metrics(${it.service})")
                "${it.service} is healthy"
            },
        )
    // docs:end autonomous-requires-approval

    companion object {
        /** Tool runs, in order, as `tool(arguments)`. */
        val RUNS: MutableList<String> = CopyOnWriteArrayList()

        fun runsOf(tool: String): List<String> = RUNS.filter { it.startsWith("$tool(") }
    }
}
