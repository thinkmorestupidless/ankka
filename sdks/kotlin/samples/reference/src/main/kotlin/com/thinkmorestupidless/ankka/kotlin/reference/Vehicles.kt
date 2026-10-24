package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Replicated
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.attributionOrNull
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.forwardedFromOrNull
import com.thinkmorestupidless.ankka.kotlin.installationOrNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.Optional

/**
 * A replicated entity whose state says, for each step, where its event was written ("" when no
 * installation is named), whether that was here, who acted and where it was forwarded from.
 */
@ComponentId("replicated-vehicle")
@Replicated
class ReplicatedVehicle : EventSourcedEntity<ReplicatedVehicle.VehicleRecord, ReplicatedVehicle.StepAdded>() {

    @Manifest("vehicle-record")
    data class VehicleRecord(
        val steps: List<String>,
        val writtenAt: List<String>,
        val local: List<Boolean>,
        val actedBy: List<String>,
        val forwardedFrom: List<String>,
    )

    /** Where a command ran, who acted and where it was forwarded from ("" when none). */
    @Manifest("vehicle-placement")
    data class VehiclePlacement(val installation: String, val home: Boolean, val attribution: String, val forwardedFrom: String)

    @Manifest("step-added")
    data class StepAdded(val step: String)

    override fun emptyState(): VehicleRecord = VehicleRecord(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    override fun applyEvent(state: VehicleRecord, event: StepAdded): VehicleRecord {
        val facts = eventContext()
        return VehicleRecord(
            state.steps + event.step,
            state.writtenAt + (facts.installationOrNull ?: ""),
            state.local + facts.isLocal,
            state.actedBy + (facts.attributionOrNull ?: ""),
            state.forwardedFrom + (facts.forwardedFromOrNull ?: ""),
        )
    }

    @Command("add-step")
    fun addStep(step: String): Effect<VehiclePlacement> {
        val placement =
            VehiclePlacement(
                context().installationOrNull ?: "",
                context().isHome,
                context().attributionOrNull ?: "",
                context().forwardedFromOrNull ?: "",
            )
        return effects { persist(StepAdded(step)).thenReply(placement) }
    }

    @Command("hand-over")
    fun handOver(target: String): Effect<String> = effects { handOverTo(target).thenReply(target) }

    @Command("replicate-to")
    fun replicateTo(target: String): Effect<String> = effects { replicateTo(target).thenReply(target) }

    @Command("stop-replicating-to")
    fun stopReplicatingTo(target: String): Effect<String> = effects { stopReplicatingTo(target).thenReply(target) }

    @Query("get")
    fun get(): ReadOnlyEffect<VehicleRecord> = effects { reply(currentState()) }
}

/** Publishes nothing; declared to show a consumer given mirrored changes discovers so. */
@ComponentId("vehicle-feed")
class VehicleFeed : Consumer<ReplicatedVehicle.StepAdded, ReplicatedVehicle.StepAdded>() {

    override fun source(): ChangeSource<ReplicatedVehicle.StepAdded> = ChangeSource.of(ReplicatedVehicle::class.java)

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("conformance-vehicle-steps"))

    override fun mirroredChanges(): Boolean = true

    override fun onChange(message: ReplicatedVehicle.StepAdded): ConsumerEffect<ReplicatedVehicle.StepAdded> = effects { ignore() }
}

/** Drives the replicated vehicle: a step, a read, and the three replication changes, declared in code. */
class VehiclesEndpoint : HttpEndpoint(), RouteRegistrar {

    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        post<String>("/vehicles/{vehicleId}/steps") { step ->
            vehicle(path("vehicleId")).call(ReplicatedVehicle::addStep).invokeSuspending(step)
        }
        get("/vehicles/{vehicleId}") { vehicle(path("vehicleId")).call(ReplicatedVehicle::get).invokeSuspending() }
        post("/vehicles/{vehicleId}/hand-over/{target}") {
            vehicle(path("vehicleId")).call(ReplicatedVehicle::handOver).invokeSuspending(path("target"))
        }
        post("/vehicles/{vehicleId}/replicate-to/{target}") {
            vehicle(path("vehicleId")).call(ReplicatedVehicle::replicateTo).invokeSuspending(path("target"))
        }
        post("/vehicles/{vehicleId}/stop-replicating-to/{target}") {
            vehicle(path("vehicleId")).call(ReplicatedVehicle::stopReplicatingTo).invokeSuspending(path("target"))
        }
    }

    private fun vehicle(id: String): EntityRef = componentClient().forEventSourcedEntity(id)
}
