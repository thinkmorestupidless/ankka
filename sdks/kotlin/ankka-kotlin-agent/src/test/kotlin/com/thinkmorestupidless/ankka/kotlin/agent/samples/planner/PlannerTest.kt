package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The multi-agent planner end to end.
 *
 * A scripted model, so the assertions are about the orchestration (which specialists were chosen,
 * that they ran in one shared session, that the summariser saw their contributions and not the
 * selector's routing) rather than about a model's wording.
 */
class PlannerTest {
    // docs:start start
    private val model = TestModelProvider()
    private lateinit var kit: AnkkaTestKit

    @BeforeTest
    fun start() {
        kit = AnkkaTestKit.start(plannerService(model))
    }

    @AfterTest
    fun stop() = kit.close()
    // docs:end start

    private fun planner(id: String) = kit.componentClient().forWorkflow(id)

    private fun preferences(userId: String) = kit.componentClient().forKeyValueEntity(userId)

    private fun completedPlan(planId: String): PlanState {
        val plan = planner(planId).awaitEnd(PlannerWorkflow::class.java, Duration.ofSeconds(60))
        assertEquals(PlanState.COMPLETED, plan.status)
        return plan
    }

    // docs:start script
    /** Scripts the selector's structured reply plus one answer per specialist. */
    private fun scriptPlan(specialists: List<String>, summary: String) {
        val json = specialists.joinToString(",", "[", "]") { "\"$it\"" }
        model.expectText("""{"specialists":$json,"reason":"chosen for the test"}""")
        specialists.forEach { specialist ->
            when (specialist) {
                // The weather specialist has a tool, so it takes two model turns.
                Specialist.WEATHER ->
                    model
                        .expectToolCall("get_forecast", """{"destination":"Lisbon"}""")
                        .expectText("Lisbon is mild with occasional rain.")
                Specialist.ACTIVITY -> model.expectText("Try the tram and a pastel de nata.")
                Specialist.BUDGET -> model.expectText("Budget about 90 euros a day.")
                else -> fail("unscripted specialist '$specialist'")
            }
        }
        model.expectText(summary)
    }
    // docs:end script

    // docs:start assert
    @Test
    fun `the selector decides which specialists run, and only those run`() {
        scriptPlan(listOf(Specialist.WEATHER, Specialist.BUDGET), "A mild, affordable trip.")

        assertEquals(Done.DONE, planner("p-select").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon")))

        val plan = completedPlan("p-select")
        assertEquals(listOf(Specialist.WEATHER, Specialist.BUDGET), plan.selection?.specialists)
        assertEquals(listOf(Specialist.WEATHER, Specialist.BUDGET), plan.contributions.map { it.specialist })
        // The activity specialist was never consulted, so it contributed nothing.
        assertNull(plan.contributionFrom(Specialist.ACTIVITY))
        assertEquals("A mild, affordable trip.", plan.summary)
    }
    // docs:end assert

    @Test
    fun `a different selection changes which agents run, with no orchestration change`() {
        scriptPlan(listOf(Specialist.ACTIVITY), "Plenty to do.")
        planner("p-one").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))

        val plan = completedPlan("p-one")
        assertEquals(listOf(Specialist.ACTIVITY), plan.contributions.map { it.specialist })
        assertEquals("Try the tram and a pastel de nata.", plan.contributionFrom(Specialist.ACTIVITY))
    }

    @Test
    fun `the summariser reads the specialists' contributions back from the session`() {
        scriptPlan(listOf(Specialist.WEATHER, Specialist.ACTIVITY), "Mild weather, good trams.")
        planner("p-summary").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
        completedPlan("p-summary")

        // The last model call is the summariser's; it must have seen the specialists' answers.
        val summaryRequest = model.lastRequest()
        assertContains(summaryRequest, "mild with occasional rain")
        assertContains(summaryRequest, "pastel de nata")
        // Routing chatter stayed out of it.
        assertFalse(summaryRequest.contains("chosen for the test"), summaryRequest)
    }

    @Test
    fun `the activity specialist is given the traveller's stored preferences`() {
        preferences("u-picky").call(PreferencesEntity::set).invoke(Preferences("u-picky", listOf("museums"), listOf("hiking"), 120))

        scriptPlan(listOf(Specialist.ACTIVITY), "Museums it is.")
        planner("p-prefs").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-picky", "Lisbon"))
        completedPlan("p-prefs")

        // The preferences reached the model as context, read from the entity by the agent.
        val activityRequest = model.requests()[1]
        assertContains(activityRequest, "museums")
        assertContains(activityRequest, "budget 120")
    }

    @Test
    fun `a specialist with a tool runs it before answering`() {
        scriptPlan(listOf(Specialist.WEATHER), "Bring a light coat.")
        planner("p-tool").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
        val plan = completedPlan("p-tool")

        assertEquals("Lisbon is mild with occasional rain.", plan.contributionFrom(Specialist.WEATHER))
        // The tool's answer went back to the model before it answered.
        assertContains(model.requests()[2], "Lisbon: mild with occasional rain")
    }

    @Test
    fun `a plan cannot be started twice`() {
        scriptPlan(listOf(Specialist.BUDGET), "Cheap.")
        planner("p-twice").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
        val second = runCatching { planner("p-twice").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon")) }
        assertContains(second.exceptionOrNull()?.message ?: fail("the second start was accepted"), "already started")

        // docs:start drain
        // Let the first plan finish before the test ends. A workflow left mid-flight keeps
        // consuming the shared scripted model, which would starve the next test.
        completedPlan("p-twice")
        // docs:end drain
    }

    // docs:start observe
    @Test
    fun `a plan's own state and the engine's lifecycle are read apart`() {
        scriptPlan(listOf(Specialist.BUDGET), "Cheap.")
        planner("p-observe").call(PlannerWorkflow::start).invoke(PlannerWorkflow.Start("u-1", "Lisbon"))
        completedPlan("p-observe")

        val state = planner("p-observe").call(PlannerWorkflow::plan).invoke() // the domain state
        val lifecycle = planner("p-observe").lifecycle(PlannerWorkflow::class.java).invoke() // the engine's state
        assertEquals(PlanState.COMPLETED, state.status)
        assertTrue(lifecycle.isCompleted, lifecycle.status())
    }
    // docs:end observe
}
