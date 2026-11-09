package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Replicated
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.attributionOrNull
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenReply
import com.thinkmorestupidless.ankka.kotlin.forwardedFromOrNull
import com.thinkmorestupidless.ankka.kotlin.installationOrNull
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue

// The service the replication features describe: a car maker's vehicles, built at one factory and
// sold at another. Each installation of "fleet" is one factory or dealership.

/**
 * A vehicle, with where each of its events was written and whether that was here: what the event
 * handler was told, kept in the state so a test can read it.
 */
@Manifest("vehicle")
data class Vehicle(
    val id: String = "",
    val steps: List<String> = emptyList(),
    val inspections: List<String> = emptyList(),
    val sold: Boolean = false,
    val writtenAt: List<String> = emptyList(),
    val local: List<Boolean> = emptyList(),
    val actedBy: List<String> = emptyList(),
)

@Manifest("vehicle-event")
sealed interface VehicleEvent {
    data class Created(val id: String) : VehicleEvent

    data class StepAdded(val step: String) : VehicleEvent

    data class InspectionRecorded(val note: String) : VehicleEvent

    data object Sold : VehicleEvent
}

/**
 * What a vehicle's handler answers a command with: which command, where, and whether at home; who
 * acted and where the command was forwarded from.
 */
data class Receipt(
    val command: String,
    val installation: String?,
    val home: Boolean,
    val records: Int,
    val attribution: String? = null,
    val forwardedFrom: String? = null,
)

/** Where a query ran. */
data class Placement(val installation: String?, val home: Boolean)

/** The installations a new vehicle is replicated to besides its home. */
data class CreateVehicle(val replicateTo: List<String> = emptyList())

// docs:start replicated-entity
/** A replicated entity: `@Replicated` says it is held at several installations. */
@ComponentId("vehicle")
@Replicated
class VehicleEntity : EventSourcedEntity<Vehicle, VehicleEvent>() {
    override fun emptyState() = Vehicle()

    // docs:start replicated-apply
    override fun applyEvent(state: Vehicle, event: VehicleEvent): Vehicle {
        val vehicle =
            when (event) {
                is VehicleEvent.Created -> state.copy(id = event.id)
                is VehicleEvent.StepAdded -> state.copy(steps = state.steps + event.step)
                is VehicleEvent.InspectionRecorded ->
                    state.copy(inspections = state.inspections + event.note)
                VehicleEvent.Sold -> state.copy(sold = true)
            }
        // Where this event was written, on replay as well as when it is persisted here.
        val facts = eventContext()
        return vehicle.copy(
            writtenAt = vehicle.writtenAt + (facts.installationOrNull ?: "nowhere"),
            local = vehicle.local + facts.isLocal,
            // Who acted on the command that wrote it, as its record says.
            actedBy = vehicle.actedBy + (facts.attributionOrNull ?: "nobody"),
        )
    }

    /** Taken while the handler runs: the reply is computed after the context is gone. */
    private fun receipt(command: String): (Vehicle) -> Receipt {
        val here = context().installationOrNull
        val atHome = context().isHome
        val actor = context().attributionOrNull
        val forwarder = context().forwardedFromOrNull
        return { v -> Receipt(command, here, atHome, v.writtenAt.size, actor, forwarder) }
    }
    // docs:end replicated-apply

    // docs:start replicate-to
    @Command("create")
    fun create(input: CreateVehicle): Effect<Receipt> = effects {
        var persisting = persist(VehicleEvent.Created(context().entityId()))
        for (installation in input.replicateTo) persisting = persisting.replicateTo(installation)
        persisting thenReply receipt("create")
    }
    // docs:end replicate-to

    @Command("add-step")
    fun addStep(step: String): Effect<Receipt> = effects {
        persist(VehicleEvent.StepAdded(step)) thenReply receipt("add-step")
    }

    @Command("record-inspection")
    fun recordInspection(note: String): Effect<Receipt> = effects {
        persist(VehicleEvent.InspectionRecorded(note)) thenReply receipt("record-inspection")
    }

    @Command("record-sale")
    fun recordSale(): Effect<Receipt> = effects {
        persist(VehicleEvent.Sold) thenReply receipt("record-sale")
    }

    // docs:start replicated-effects
    /** Ships the vehicle: its home is the dealership's installation from now on. */
    @Command("hand-over")
    fun handOver(installation: String): Effect<Receipt> = effects {
        handOverTo(installation) thenReply receipt("hand-over")
    }

    /** Allots the vehicle to a dealership, whose installation holds it from now on. */
    @Command("replicate-to")
    fun replicateTo(installation: String): Effect<Receipt> = effects {
        replicateTo(installation) thenReply receipt("replicate-to")
    }

    /** The factory no longer needs it: its installation keeps what it holds and mirrors no more. */
    @Command("stop-replicating-to")
    fun stopReplicatingTo(installation: String): Effect<Receipt> = effects {
        stopReplicatingTo(installation) thenReply receipt("stop-replicating-to")
    }
    // docs:end replicated-effects

    @Query("get")
    fun get(): ReadOnlyEffect<Vehicle> = effects { reply(currentState()) }

    @Query("placement")
    fun placement(): ReadOnlyEffect<Placement> = effects {
        reply(Placement(context().installationOrNull, context().isHome))
    }
}
// docs:end replicated-entity

/** A message the consumer "builds" publishes: a vehicle's step, and where it was written. */
data class Build(val vehicle: String, val step: String, val installation: String?, val local: Boolean)

/** A change a consumer was given, as its context described it. */
data class Handed(val vehicle: String, val event: String, val installation: String?, val local: Boolean)

/** The consumer "builds": publishes each step to "vehicle-builds", and records what it is given. */
@ComponentId("builds")
class BuildsConsumer : Consumer<VehicleEvent, Build>() {
    override fun source(): ChangeSource<VehicleEvent> = ChangeSource.of(VehicleEntity::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("vehicle-builds"))

    // docs:start mirrored-change
    override fun onChange(message: VehicleEvent): ConsumerEffect<Build> {
        val change = messageContext()
        GIVEN.add(Handed(change.subject(), message.toString(), change.installationOrNull, change.localOrigin()))
        return effects {
            when (message) {
                is VehicleEvent.StepAdded ->
                    publish(Build(change.subject(), message.step, change.installationOrNull, change.localOrigin()))
                else -> ignore()
            }
        }
    }
    // docs:end mirrored-change

    companion object {
        /** What the consumer was given, in order. */
        val GIVEN = ConcurrentLinkedQueue<Handed>()
    }
}
