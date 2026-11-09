package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.Personal
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.personal
import com.thinkmorestupidless.ankka.kotlin.subject

// docs:start personal-entity
/** A player, whose email is a personal field: sealed under the player's key wherever it is kept. */
@Manifest("player")
data class Player(val email: Personal<String>? = null)

@Manifest("player-registered")
data class PlayerRegistered(val playerId: String, val email: Personal<String>, val currency: String)

@ComponentId("player")
class PlayerEntity : EventSourcedEntity<Player, PlayerRegistered>() {
    override fun emptyState() = Player()

    override fun applyEvent(state: Player, event: PlayerRegistered) = Player(event.email)

    @Command("register")
    fun register(email: String): Effect<Done> = effects {
        val id = context().entityId()
        persist(PlayerRegistered(id, personal(subject("player/$id"), email), "EUR")).thenDone()
    }

    /** The email as it reads now: its value, `erased` once the player is erased, or `none`. */
    @Query("email")
    fun email(): ReadOnlyEffect<String> = effects {
        reply(
            when (val email = currentState().email) {
                null -> "none"
                is Personal.Present -> email.value()
                is Personal.Erased -> "erased"
            }
        )
    }
}
// docs:end personal-entity
