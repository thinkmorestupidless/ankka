package com.thinkmorestupidless.ankka.kotlin.suspend

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.platformThreads
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * features/java-kotlin/kotlin.feature: "a handler written as a suspending function calls a component
 * and is answered". The test JVM runs virtual threads on two carriers (the build sets it), so a
 * handler that held its carrier while it waited would make fifty waiting handlers queue behind two.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspendingHandlerTest {

    private lateinit var kit: AnkkaTestKit

    // Its own client, whose work runs on virtual threads, so the count below is the service's.
    private val http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build()

    @BeforeAll
    fun start() {
        kit =
            AnkkaTestKit.start(
                Ankka.service("waiting")
                    .register(TallyEntity::class.java)
                    .register(CountingWorkflow::class.java)
                    .withEndpoint(WaitingEndpoint::class.java)
            )
    }

    @AfterAll
    fun stop() {
        kit.close()
    }

    private fun waitThenAdd(id: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(
            HttpRequest.newBuilder(kit.http().resolve("/wait-then-add/$id")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a suspending route waits holding no platform thread and is answered by the entity it calls`() {
        assertEquals(200, waitThenAdd("warm-up").join().statusCode())
        val before = kit.platformThreads
        val started = System.nanoTime()

        val waiting = (1..50).map { waitThenAdd("tally-$it") }
        Thread.sleep(WaitingEndpoint.WAIT_MILLIS / 2)
        val during = kit.platformThreads
        val answers = waiting.map { it.join() }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        answers.forEach {
            assertEquals(200, it.statusCode(), it.body())
            assertTrue(it.body().contains("\"count\":1"), it.body())
        }
        // The service's own pools may grow by a few threads under fifty connections; a handler that
        // held a thread while it waited would add one per handler.
        assertTrue(during - before < 10, "platform threads went from $before to $during while 50 handlers waited")
        assertTrue(
            elapsed < Duration.ofSeconds(5),
            "50 handlers each waiting ${WaitingEndpoint.WAIT_MILLIS} ms on two carriers took $elapsed",
        )
    }

    @Test
    fun `a suspending step calls a component and the workflow carries its answer`() {
        kit.componentClient()
            .forWorkflow("counting-1")
            .call("counting", "start", String::class.java, Done::class.java)
            .invoke("tally-of-counting")
        kit.eventually(Duration.ofSeconds(10)) {
            val state =
                kit.componentClient()
                    .forWorkflow("counting-1")
                    .call("counting", "get", Void::class.java, Counting::class.java)
                    .invoke()
            assertEquals(Counting("tally-of-counting", "counted", 5), state)
        }
    }

    @Test
    fun `a suspend fun handler must return an effect, and a query a read-only one`() {
        val e =
            kotlin.runCatching { Ankka.service("misdeclared").register(Misdeclared::class.java).start() }
                .exceptionOrNull()
        assertTrue(e is ServiceProblems, "expected ServiceProblems, got $e")
        val messages = e.problems().joinToString("\n")
        assertTrue(messages.contains("answer: must return an Effect built by effects()"), messages)
        assertTrue(messages.contains("peek: is a @Query, so it must return a ReadOnlyEffect"), messages)
    }
}
