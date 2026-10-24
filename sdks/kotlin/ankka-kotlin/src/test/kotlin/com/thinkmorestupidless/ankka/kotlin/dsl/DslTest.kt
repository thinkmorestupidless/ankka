package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.KeyValueEntityTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.Result
import com.thinkmorestupidless.ankka.javasdk.testkit.WorkflowTestKit
import com.thinkmorestupidless.ankka.kotlin.commandRef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * features/java-kotlin/kotlin.feature: "a component declared in Kotlin's own form behaves as one
 * declared through the Java SDK". Each DSL form is driven beside the chained form it stands for, and
 * must leave the same events, state and replies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DslTest {

    /** One script of calls, its results in order. */
    private fun script(add: (Item) -> Result<Int>, close: () -> Result<*>, state: () -> Basket): List<Any?> {
        val first = add(Item("apple", 2))
        val second = add(Item("pear", 1))
        val closed = close()
        val refused = add(Item("plum", 1))
        return listOf(
            first.reply(), first.events(), second.reply(), closed.events(),
            refused.isError, refused.error(), refused.events(), state(),
        )
    }

    @Test
    fun `an event sourced entity's DSL effects are its chained effects`() {
        val chained = EventSourcedTestKit.of(ChainedBasket::class.java)
        val dsl = EventSourcedTestKit.of(DslBasket::class.java)
        val fromChained =
            script({ chained.call(ChainedBasket::add, it) }, { chained.call(ChainedBasket::close) }, { chained.state() })
        val fromDsl = script({ dsl.call(DslBasket::add, it) }, { dsl.call(DslBasket::close) }, { dsl.state() })
        assertEquals(fromChained, fromDsl)
        assertEquals(2, fromDsl[2])
        assertEquals(true, fromDsl[4])
    }

    @Test
    fun `a suspend fun handler built in the DSL answers as the plain one does`() {
        val plain = EventSourcedTestKit.of(DslBasket::class.java)
        val suspending = EventSourcedTestKit.of(SuspendingBasket::class.java)
        val a = plain.call(DslBasket::add, Item("apple", 2))
        val b = suspending.call(commandRef(SuspendingBasket::add), Item("apple", 2))
        assertEquals(a.reply(), b.reply())
        assertEquals(a.events(), b.events())
        assertEquals(plain.state(), suspending.state())
    }

    @Test
    fun `a key value entity's DSL update replies with the new state`() {
        val kit = KeyValueEntityTestKit.of(DslCounter::class.java)
        kit.call(DslCounter::add, 2)
        assertEquals(Counter(5), kit.call(DslCounter::add, 3).reply())
        assertEquals(Counter(5), kit.state())
    }

    @Test
    fun `a workflow's DSL transitions go to the steps its Kotlin references name, suspending or not`() {
        val kit = WorkflowTestKit.of(DslSteps::class.java)
        val started = kit.call(DslSteps::start)
        assertEquals(listOf("first", "second"), started.steps())
        assertEquals(Steps(listOf("started", "first", "second")), kit.state())
    }

    // The whole service: routes in code, Kotlin references in calls and tools.

    private lateinit var service: AnkkaTestKit
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        service =
            AnkkaTestKit.start(
                Ankka.service("baskets")
                    .register(DslBasket::class.java)
                    .register(SuspendingBasket::class.java)
                    .withEndpoint(BasketEndpoint::class.java)
                    .withMcpEndpoint(BasketTools::class.java)
            )
    }

    @AfterAll
    fun stop() {
        service.close()
    }

    private fun send(method: String, path: String, json: String? = null): HttpResponse<String> {
        val body = json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest.newBuilder(URI.create(service.http().toString().trimEnd('/') + path))
                .method(method, body)
                .header("Content-Type", "application/json")
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `routes declared in code are served beside the annotated ones`() {
        val added = send("POST", "/baskets/b1/items", """{"sku":"apple","quantity":2}""")
        assertEquals(200, added.statusCode(), added.body())
        assertEquals("1", added.body())
        val read = send("GET", "/baskets/b1")
        assertEquals(200, read.statusCode(), read.body())
        assertTrue(read.body().contains("\"sku\":\"apple\""), read.body())
        assertEquals(204, send("DELETE", "/baskets/b1").statusCode())
        val annotated = send("GET", "/annotated/b2")
        assertEquals(200, annotated.statusCode(), annotated.body())
    }

    @Test
    fun `a route declared in code that an annotation already serves stops the start`() {
        val e =
            runCatching { AnkkaTestKit.start(Ankka.service("twice").withEndpoint(TwiceRoutedEndpoint::class.java)) }
                .exceptionOrNull()
        assertTrue(e is ServiceProblems, "expected ServiceProblems, got $e")
        assertTrue(e.problems().joinToString().contains("GET /twice: is also annotated's route"), e.problems().toString())
    }
}
