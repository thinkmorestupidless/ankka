package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.Personal
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.personal
import com.thinkmorestupidless.ankka.kotlin.subject

data class KotlinPlayer(val email: Personal<String>?)

@Manifest("player-registered")
data class KotlinPlayerRegistered(val playerId: String, val email: Personal<String>, val currency: String)

/** `players` in Kotlin (feature 042): a registration whose email is a personal field. */
@ComponentId("player")
class KotlinPlayers : EventSourcedEntity<KotlinPlayer, KotlinPlayerRegistered>() {
    override fun emptyState() = KotlinPlayer(null)

    override fun applyEvent(state: KotlinPlayer, event: KotlinPlayerRegistered) = KotlinPlayer(event.email)

    @Command("register")
    fun register(email: String): Effect<Done> {
        val id = context().entityId()
        return effects().persist(KotlinPlayerRegistered(id, personal(subject("player/$id"), email), "EUR")).thenDone()
    }

    /** The email, `erased` once the player is, or `none`. */
    @Query("email")
    fun email(): ReadOnlyEffect<String> = effects().reply(currentState().email?.orElse("erased") ?: "none")
}
