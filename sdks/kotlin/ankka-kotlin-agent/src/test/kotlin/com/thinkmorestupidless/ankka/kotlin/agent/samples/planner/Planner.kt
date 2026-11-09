package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProviders
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder

// docs:start registration
/** The planner's service: its entity, its workflow, its agents and the agents module that hosts them. */
fun plannerService(model: ModelProvider): ServiceBuilder =
    Ankka.service("planner")
        .register(PreferencesEntity::class.java)
        .register(PlannerWorkflow::class.java)
        .register(SelectorAgent::class.java)
        .register(WeatherAgent::class.java)
        .register(ActivityAgent::class.java)
        .register(BudgetAgent::class.java)
        .register(SummaryAgent::class.java)
        // The module hosts the agents and the session memory, with the service's default model.
        .withModule(Agents.module().withModelProvider(model))

fun main() {
    plannerService(ModelProviders.anthropic("claude-opus-5")).start()
}
// docs:end registration
