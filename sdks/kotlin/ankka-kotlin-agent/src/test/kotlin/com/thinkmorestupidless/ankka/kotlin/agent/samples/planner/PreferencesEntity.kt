package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenReply

/** A user's stated preferences, so an agent can be given context it did not ask for. */
@ComponentId("preferences")
class PreferencesEntity : KeyValueEntity<Preferences>() {

    override fun emptyState(): Preferences = Preferences(context().entityId())

    @Command("set")
    fun set(preferences: Preferences): Effect<Done> = effects {
        if (preferences.maxBudget < 0) {
            error("budget cannot be negative")
        } else {
            updateState(preferences.copy(userId = context().entityId())).thenDone()
        }
    }

    @Command("add-like")
    fun addLike(activity: String): Effect<Preferences> = effects {
        if (activity.isBlank()) {
            error("an activity needs a name")
        } else {
            updateState(currentState().copy(likes = (currentState().likes + activity).distinct())) thenReply { it }
        }
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Preferences> = effects { reply(currentState()) }
}
