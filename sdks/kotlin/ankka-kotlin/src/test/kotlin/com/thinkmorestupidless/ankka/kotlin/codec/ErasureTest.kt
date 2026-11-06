package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureAsk
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureReport
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.kotlin.subject
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import kotlinx.coroutines.runBlocking
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Asks for an erasure as the service, from a Kotlin endpoint. */
class ForgetEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Post("/forget/{id}")
    fun forget(id: String): String {
        val asked = erasures().ask(subject("player/$id"), ErasureAsk.defaults().withReason("account closed"))
        return "${asked.id()} ${asked.state()}"
    }
}

/**
 * A Kotlin service's part of an erasure (feature 042): its handler, a Kotlin lambda, runs for each
 * application and erases what the service keeps of the data subject in its bucket; an endpoint asks
 * for an erasure as the service, refused where no control plane is reachable.
 */
class ErasureTest {

    @Test
    fun `a Kotlin erasure handler runs for each application and erases the subject's objects`() {
        val runs = ConcurrentLinkedQueue<Pair<String, Boolean>>()
        val service =
            Ankka.service("players")
                .register(KotlinPlayers::class.java)
                .withErasureHandler { ctx, subject ->
                    runs.add(subject.value() to ctx.rerun())
                    ErasureReport.of(ctx.storage().defaultBucket().erase(subject))
                }
        ankkaTestKit(service) {
            val bucket = storage().defaultBucket()
            runBlocking {
                // A notice heard before the instance holds the handler runs nothing; warm up.
                var n = 0
                eventually(within = 30.seconds) {
                    erase("warm/${n++}")
                    eventually(within = 2.seconds) { assertTrue(runs.any { it.first.startsWith("warm/") }) }
                }
                runs.clear()
                bucket.putBytes("subjects/player%2F8c1f/passport.png", byteArrayOf(1), "image/png")
                bucket.putBytes("subjects/player%2F77a0/passport.png", byteArrayOf(2), "image/png")
                erase("player/8c1f")
                eventually { assertEquals(listOf("player/8c1f" to false), runs.toList()) }
            }
            assertEquals(listOf("subjects/player%2F77a0/passport.png"), bucket.list("subjects/").map { it.name() })
        }
    }

    @Test
    fun `a Kotlin endpoint's ask reaches the platform, which refuses it without a control plane`() {
        ankkaTestKit(Ankka.service("players").withEndpoint(ForgetEndpoint::class.java)) {
            val response =
                httpClient().send(
                    HttpRequest.newBuilder(http().resolve("/forget/8c1f")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(503, response.statusCode(), response.body())
            assertTrue(response.body().contains("control plane"), response.body())
        }
    }
}
