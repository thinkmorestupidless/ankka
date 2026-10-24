package com.thinkmorestupidless.ankka.kotlin.testkit

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenReply
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

data class Tally(val count: Int)

data class Counted(val by: Int)

@ComponentId("tally")
class TallyEntity : EventSourcedEntity<Tally, Counted>() {
    override fun emptyState() = Tally(0)

    override fun applyEvent(state: Tally, event: Counted) = Tally(state.count + event.by)

    @Command("count")
    fun count(by: Int): Effect<Int> = effects { persist(Counted(by)) thenReply { it.count } }

    @Query("get")
    fun get(): ReadOnlyEffect<Tally> = effects { reply(currentState()) }
}

class KitsTest {

    @Test
    fun `an entity's kit is taken by its KClass`() {
        val kit = eventSourcedTestKit(TallyEntity::class)
        assertEquals(2, kit.call(TallyEntity::count, 2).reply())
        assertEquals(listOf(Counted(2)), kit.events())
        assertEquals(Tally(2), kit.state())
    }

    @Test
    fun `a whole service runs in a block that closes it, and eventually suspends between tries`() {
        val answered =
            ankkaTestKit(Ankka.service("tallies").register(TallyEntity::class.java)) {
                runBlocking {
                    componentClient().forEventSourcedEntity("t1").call(TallyEntity::count).invokeSuspending(3)
                    var tries = 0
                    eventually {
                        tries++
                        val tally = componentClient().forEventSourcedEntity("t1").call(TallyEntity::get).invoke()
                        assertEquals(Tally(3), tally)
                        assertTrue(tries >= 2, "passes on the second try")
                    }
                    tries
                }
            }
        assertEquals(2, answered)
    }

    @Test
    fun `eventually throws the last failure once its time is gone`() {
        ankkaTestKit(Ankka.service("never").register(TallyEntity::class.java)) {
            assertTrue(platformThreads > 0)
            val failed =
                assertFailsWith<AssertionError> {
                    runBlocking { eventually(within = 300.milliseconds) { assertEquals(1, 2, "never true") } }
                }
            assertTrue(failed.message!!.contains("never true"), failed.message)
        }
    }
}
