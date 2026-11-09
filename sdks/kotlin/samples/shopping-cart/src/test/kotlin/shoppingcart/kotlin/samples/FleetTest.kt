package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit
import com.thinkmorestupidless.ankka.kotlin.orNull
import java.time.Duration
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A replicated entity: in the entity test kit as its home and as a follower, and across two installations. */
class FleetTest {
    @Test
    fun `at its home, a handover and a change to the replication set show on the result`() {
        val kit = EventSourcedTestKit.home(VehicleEntity::class.java, "v1", "leipzig")
        val created = kit.call(VehicleEntity::create, CreateVehicle(listOf("vienna")))
        assertEquals(setOf("vienna"), created.replicatedTo())
        assertEquals(Receipt("create", "leipzig", true, 1), created.reply())

        val handed = kit.call(VehicleEntity::handOver, "vienna")
        assertEquals(Optional.of("vienna"), handed.handover())
        assertEquals(setOf("vienna"), kit.call(VehicleEntity::stopReplicatingTo, "vienna").stoppedReplicatingTo())
        // Every event was written here, at the home.
        assertEquals(listOf("leipzig"), kit.state().writtenAt.distinct())
        assertEquals(listOf(true), kit.state().local.distinct())
    }

    @Test
    fun `a follower is refused a command that may persist, and answers from what it was given`() {
        // docs:start follower-kit
        val kit = EventSourcedTestKit.follower(VehicleEntity::class.java, "v1", "leipzig", "bratislava")
        kit.replay("leipzig", listOf(VehicleEvent.Created("v1"), VehicleEvent.StepAdded("frame")))
        // docs:end follower-kit
        val refused = kit.call(VehicleEntity::addStep, "wheels")
        assertTrue(refused.isError())
        assertEquals(421, refused.problem().status())
        assertContains(refused.error(), "leipzig")

        val vehicle = kit.call(VehicleEntity::get).reply()
        assertEquals(listOf("frame"), vehicle.steps)
        // Replayed events were written at the home, not here.
        assertEquals(listOf("leipzig", "leipzig"), vehicle.writtenAt)
        assertEquals(listOf(false, false), vehicle.local)
        assertEquals(Placement("bratislava", false), kit.call(VehicleEntity::placement).reply())
    }

    @Test
    fun `a copy answers how it stands, and a consumer is told where each change was written`() {
        BuildsConsumer.GIVEN.clear()
        val fleet = Ankka.service("fleet").register(VehicleEntity::class.java).register(BuildsConsumer::class.java)
        AnkkaTestKit.installations("leipzig", "vienna").use { group ->
            val leipzig = group.start("leipzig", fleet)
            val vienna = group.start("vienna", fleet)
            val atHome = leipzig.componentClient().forEventSourcedEntity("v1")
            atHome.call(VehicleEntity::create).invoke(CreateVehicle(listOf("vienna")))
            atHome.call(VehicleEntity::addStep).invoke("frame")

            vienna.eventually(Duration.ofSeconds(30)) {
                // docs:start replication-status
                val status = vienna.componentClient().forEventSourcedEntity("v1").replication(VehicleEntity::class.java)
                assertEquals("leipzig", status.home().orNull())
                assertEquals(setOf("leipzig", "vienna"), status.replicationSet())
                assertTrue(status.position() > 0) // how many records this copy holds
                // docs:end replication-status
            }
            val mirrored = vienna.componentClient().forEventSourcedEntity("v1").call(VehicleEntity::get).invoke()
            assertEquals(listOf("frame"), mirrored.steps)
            assertEquals(listOf(false, false), mirrored.local)

            // The consumer publishes, so it is given the local change only, at the home.
            leipzig.eventually(Duration.ofSeconds(30)) {
                assertTrue(BuildsConsumer.GIVEN.any { it.event == "StepAdded(step=frame)" }, BuildsConsumer.GIVEN.toString())
            }
            val handed = BuildsConsumer.GIVEN.filter { it.event == "StepAdded(step=frame)" }
            assertEquals(listOf(Handed("v1", "StepAdded(step=frame)", "leipzig", true)), handed.distinct())
            assertEquals(listOf("v1"), leipzig.published("vehicle-builds").map { it.key().orNull() })
        }
    }
}
