package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

/** A key value entity holding a name. */
@ComponentId("profile")
class Profile : KeyValueEntity<Profile.ProfileState>() {

    @Manifest("profile")
    data class ProfileState(val name: String)

    override fun emptyState(): ProfileState = ProfileState("")

    @Command("set")
    fun set(name: String): Effect<String> = effects {
        if (name.isEmpty()) error("a name is needed") else updateState(ProfileState(name)).thenReply("done")
    }

    @Query("get")
    fun get(): ReadOnlyEffect<String> = effects { reply(currentState().name.ifEmpty { "none" }) }

    @Command("delete")
    fun delete(): Effect<String> = effects { deleteEntity().thenReply("done") }
}
