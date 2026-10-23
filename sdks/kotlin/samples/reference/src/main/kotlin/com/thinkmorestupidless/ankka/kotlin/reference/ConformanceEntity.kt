package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import java.time.Duration

/** An entity whose handlers are the protocol's edge cases. */
@ComponentId("conformance")
@SnapshotEvery(3)
class ConformanceEntity : EventSourcedEntity<ConformanceEntity.Recordings, ConformanceEntity.Recorded>() {

    /** What was recorded, in order. */
    @Manifest("conformance-state")
    data class Recordings(val inputs: List<String>)

    @Manifest("conformance-event")
    data class Recorded(val input: String)

    override fun emptyState(): Recordings = Recordings(emptyList())

    override fun applyEvent(state: Recordings, event: Recorded): Recordings = Recordings(state.inputs + event.input)

    @Command("record")
    fun record(input: String): Effect<String> = effects { persist(Recorded(input)).thenReply("done") }

    @Command("record-many")
    fun recordMany(n: Int): Effect<String> = effects {
        persistAll((0 until n).map { Recorded("many-$it") }).thenReply("done")
    }

    @Command("refuse")
    fun refuse(): Effect<String> = effects { error(Problem.conflict("refused on purpose")) }

    @Command("no-reply")
    fun noReply(): Effect<String> = effects { persist(Recorded("silent")).thenNoReply() }

    @Command("delete")
    fun delete(): Effect<String> = effects { deleteEntity().thenReply("done") }

    @Command("expire")
    fun expire(millis: Long): Effect<String> = effects {
        persist(Recorded("expiring")).expireAfter(Duration.ofMillis(millis)).thenReply("done")
    }

    @Query("count")
    fun count(): ReadOnlyEffect<Int> = effects { reply(currentState().inputs.size) }

    @Command("misbehave")
    fun misbehave(): Effect<String> = throw RuntimeException("boom")
}
