package com.thinkmorestupidless.ankka.kotlin.suspend

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.kotlin.stepRef
import kotlinx.coroutines.delay

// A service whose handlers wait: an endpoint route and a workflow step written as suspend funs, each
// of which waits and then calls an entity.

data class Tally(val count: Int)

@ComponentId("tally")
class TallyEntity : KeyValueEntity<Tally>() {
    override fun emptyState() = Tally(0)

    @Command("add")
    fun add(by: Int): Effect<Tally> {
        val next = Tally(currentState().count + by)
        return effects().updateState(next).thenReply(next)
    }
}

class WaitingEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/wait-then-add/{id}")
    suspend fun waitThenAdd(id: String): Tally {
        delay(WAIT_MILLIS)
        return componentClient()
            .forKeyValueEntity(id)
            .call("tally", "add", Int::class.javaObjectType, Tally::class.java)
            .invokeSuspending(1)
    }

    companion object {
        const val WAIT_MILLIS = 1_000L
    }
}

data class Counting(val tallyId: String, val status: String, val count: Int)

@ComponentId("counting")
class CountingWorkflow : Workflow<Counting>() {

    @Command("start")
    fun start(tallyId: String): Effect<Done> =
        effects().updateState(Counting(tallyId, "started", 0)).transitionTo(stepRef(CountingWorkflow::count)).thenDone()

    @Query("get")
    fun get(): ReadOnlyEffect<Counting> = effects().reply(currentState())

    @Step("count")
    suspend fun count(): StepEffect {
        delay(200)
        val tally =
            context()
                .componentClient()
                .forKeyValueEntity(currentState().tallyId)
                .call("tally", "add", Int::class.javaObjectType, Tally::class.java)
                .invokeSuspending(5)
        return stepEffects().updateState(currentState().copy(status = "counted", count = tally.count)).thenEnd()
    }
}

/** Declared wrongly in the two ways only a suspend fun can be. */
@ComponentId("misdeclared")
class Misdeclared : KeyValueEntity<Tally>() {
    @Command("answer")
    suspend fun answer(): String = "not an effect"

    @Query("peek")
    suspend fun peek(): Effect<Tally> = effects().updateState(currentState()).thenReplyState()
}
