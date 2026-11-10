package com.thinkmorestupidless.ankka.kotlin.agent.samples.blueprint

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients
import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider
import com.thinkmorestupidless.ankka.kotlin.agent.samples.planner.PreferencesEntity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guide's blueprint, registered and checked whole when the service starts. Its judge and
 * critique steps need a judgment, which the Java SDK cannot script, so the run itself is proved
 * by the Scala suite; here the platform's check of the blueprint is.
 */
class GuideSamplesTest {
    private val model = TestModelProvider()

    // docs:start runtime
    private val agents = Agents.module().withModelProvider(model).withBlueprints { GuideSamples.registry }
    // docs:end runtime

    @Test
    fun `the guide's blueprint is registered when the service starts, and again is the same version`() {
        AnkkaTestKit.start(Ankka.service("brief").withModule(agents)).use { kit ->
            // docs:start register
            val blueprints = AgentClients.blueprints(kit.componentClient())
            val registered = blueprints.register(GuideSamples.brief) // Registered(name, version, isNew, notes)
            blueprints.register(GuideSamples.brief.json()) // the same, from a blueprint written as JSON
            val versions = blueprints.versions("brief") // every version, oldest first
            val current = blueprints.current("brief") // the version a run started now would use
            val first = blueprints.version("brief", 1) // one, as it was registered
            // docs:end register
            assertEquals(GuideSamples.brief.digest(), first.digest())
            assertEquals("brief", registered.name())
            assertEquals(1, registered.version())
            assertFalse(registered.isNew(), "the blueprint carried at start was registered again as new")
            assertEquals(1, versions.size)
            assertEquals(GuideSamples.brief.digest(), current.orElseThrow().digest())
        }
    }

    @Test
    fun `a registry built from the context names a tool that reads an entity`() {
        val service =
            Ankka.service("brief")
                .register(PreferencesEntity::class.java)
                .withModule(Agents.module().withModelProvider(model).withBlueprints(GuideSamples::registry))
        AnkkaTestKit.start(service).use { kit ->
            assertTrue(AgentClients.blueprints(kit.componentClient()).current("brief").isPresent)
        }
    }

    @Test
    fun `the combinations and the schedule are blueprints' data`() {
        assertEquals(listOf("dig", "views"), listOf(GuideSamples.dig.name(), GuideSamples.views.name()))
        assertContains(GuideSamples.digest.json(), "Weekly")
        assertContains(GuideSamples.digest.json(), "Europe/London")
    }
}
