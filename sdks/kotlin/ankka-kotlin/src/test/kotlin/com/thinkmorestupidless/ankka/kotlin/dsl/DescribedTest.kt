package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Response
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

data class Card(val name: String, val nick: String? = null, val badges: List<String> = emptyList())

/** Routes declared in code and described in Kotlin's own form (feature 067). */
class DescribedEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        described({ tag("cards"); refuses(404, "no such card") }) {
            described({ summary("A card"); answers<Card>(); query<Int>("size") }) {
                get("/cards/{id}") { Card(path("id")) }
            }
            described({ answers(204) }) {
                delete("/cards/{id}") { Done.DONE }
            }
            described({ answers(204) }) {
                post("/cards/{id}/teapot") { Response.status(418) }
            }
        }
        get("/bare/{id}") { Card(path("id")) }
    }
}

class DescribedTest {

    private fun service() = Ankka.service("cards").withEndpoint(DescribedEndpoint::class.java)

    private fun send(kit: AnkkaTestKit, method: String, path: String): HttpResponse<String> =
        kit.httpClient().send(
            HttpRequest.newBuilder(kit.http().resolve(path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a described route carries its scopes into the document, and a bare one is incomplete`() {
        AnkkaTestKit.start(service().withOpenApi(Acl.allowAll())).use { kit ->
            val document = send(kit, "GET", "/_ankka/openapi.json").body()
            val card = document.substringAfter("\"/cards/{id}\"").substringBefore("\"/cards/{id}/teapot\"")
            assertTrue(card.contains("\"summary\": \"A card\""), card)
            assertTrue(card.contains("#/components/schemas/Card"), card)
            assertTrue(card.contains("\"no such card\""), card)
            assertTrue(card.contains("\"size\""), card)
            assertTrue(card.contains("\"cards\""), card)
            assertTrue(card.contains("\"204\""), card)
            val bare = document.substringAfter("\"/bare/{id}\"").substringBefore("\"/cards/")
            assertTrue(bare.contains("x-ankka-incomplete"), bare)
            // A data class's defaulted and nullable fields are not required.
            val schema = document.substringAfter("\"Card\": {").substringBefore("}\n    }")
            assertTrue(schema.contains("\"required\": [\n          \"name\"\n        ]"), schema)
        }
    }

    @Test
    fun `a kit held to its document reports an answer the document does not name`() {
        val kit = AnkkaTestKit.start(service(), Settings.defaults().heldToDocument())
        assertEquals(200, send(kit, "GET", "/cards/c1").statusCode())
        assertTrue(kit.documentViolations().isEmpty(), kit.documentViolations().toString())
        assertEquals(418, send(kit, "POST", "/cards/c1/teapot").statusCode())
        val failure = assertFailsWith<AssertionError> { kit.close() }
        assertTrue(failure.message!!.contains("POST /cards/{id}/teapot: answered 418"), failure.message)
    }

    @Test
    fun `without heldToDocument nothing is checked`() {
        AnkkaTestKit.start(service()).use { kit ->
            send(kit, "POST", "/cards/c1/teapot")
            assertFalse(kit.documentViolations().isNotEmpty())
        }
    }
}
