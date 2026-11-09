package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import shoppingcart.kotlin.CheckoutWorkflow
import shoppingcart.kotlin.LineItem
import shoppingcart.kotlin.ShoppingCart
import shoppingcart.kotlin.service

/** The HTTP page's endpoints, driven over HTTP. */
class HttpSamplesTest {
    private lateinit var kit: AnkkaTestKit

    private fun endpoints(): ServiceBuilder =
        service()
            .withEndpoint(QueryEndpoint::class.java)
            .withEndpoint(GatedEndpoint::class.java)
            .withEndpoint(CartReadEndpoint::class.java)
            .withEndpoint(CartPurgeEndpoint::class.java)
            .withEndpoint(SiteEndpoint::class.java)
            .withEndpoint(CartRowsEndpoint::class.java)
            .withEndpoint(CartWatchEndpoint::class.java)

    @BeforeTest
    fun start() {
        kit = AnkkaTestKit.start(endpoints())
    }

    @AfterTest
    fun stop() = kit.close()

    private fun addItem(cartId: String, item: LineItem) = runBlocking {
        kit.componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::addItem).invokeSuspending(item)
    }

    @Test
    fun `query parameters are read as required, optional, repeated and flag`() {
        val answer = kit.get("/search?q=pens&tag=a&tag=b&verbose")
        assertEquals(200, answer.status, answer.body)
        assertEquals("""{"term":"pens","limit":20,"tags":["a","b"],"verbose":true}""", answer.body)
        val limited = kit.get("/search?q=pens&limit=5")
        assertEquals("""{"term":"pens","limit":5,"tags":[],"verbose":false}""", limited.body)
        assertEquals(400, kit.get("/search").status)
    }

    @Test
    fun `a header is read, and its absence is a default`() {
        assertEquals("t-1", kit.get("/search/trace", mapOf("X-Trace-Id" to "t-1")).body)
        assertEquals("none", kit.get("/search/trace").body)
    }

    @Test
    fun `a streaming route reads its parameters while the stream is built`() {
        val answer = kit.get("/search/stream?q=x&count=3")
        assertEquals(200, answer.status, answer.body)
        val events = Regex("data:\"([^\"]+)\"").findAll(answer.body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("x-1", "x-2", "x-3"), events, answer.body)
    }

    @Test
    fun `an ACL that inspects the request admits the key or the flag`() {
        assertEquals(403, kit.get("/gated").status)
        assertEquals("allowed", kit.get("/gated", mapOf("X-Api-Key" to "let-me-in")).body)
        assertEquals(200, kit.get("/gated?public").status)
    }

    @Test
    fun `one path, two audiences, two endpoint classes`() {
        assertEquals("cart:c1", kit.get("/mixed/c1").body)
        val anonymous = kit.send("DELETE", "/mixed/c1")
        assertEquals(401, anonymous.status, anonymous.body)
        assertContains(anonymous.header("WWW-Authenticate") ?: "", "support")
        assertEquals("purged:c1 by dana", kit.send("DELETE", "/mixed/c1", headers = mapOf("X-Support-Id" to "dana")).body)
    }

    @Test
    fun `pages, redirects and cookies`() {
        val page = kit.get("/account")
        assertEquals(200, page.status)
        assertContains(page.header("Content-Type") ?: "", "text/html")
        assertEquals("session=abc; Path=/; HttpOnly", page.header("Set-Cookie"))
        val redirect = kit.send("POST", "/logout")
        assertEquals(303, redirect.status)
        assertEquals("/", redirect.header("Location"))
        assertContains(kit.get("/style.css").header("Content-Type") ?: "", "text/css")
    }

    @Test
    fun `a missing row is a problem with its status, and a present one is the row`() {
        val missing = kit.get("/carts/nope/rows")
        assertEquals(404, missing.status, missing.body)
        assertContains(missing.body, "no row for cart 'nope'")
        addItem("c2", LineItem("p1", "Pen", 2))
        kit.eventually(Duration.ofSeconds(10)) {
            val row = kit.get("/carts/c2/rows")
            assertEquals(200, row.status, row.body)
            assertContains(row.body, "\"p1\":2")
        }
    }

    @Test
    fun `a call from a route joins the request, and the workflow runs`() {
        addItem("c3", LineItem("p1", "Pen", 1))
        assertEquals(204, kit.send("POST", "/carts/c3/checkouts").status)
        kit.eventually(Duration.ofSeconds(10)) {
            val checkout = kit.componentClient().forWorkflow("c3").call(CheckoutWorkflow::get).invoke()
            assertNotNull(checkout.ordered)
            assertTrue(checkout.ordered.checkedOut)
        }
    }

    @Test
    fun `a socket answers refresh with the cart, and anything else with an error`() {
        addItem("c4", LineItem("p1", "Pen", 1))
        kit.socket("/carts/c4/watch").use { socket ->
            socket.send("refresh")
            val cart = socket.receive(Duration.ofSeconds(10)).orElseThrow()
            assertContains(cart, "\"cartId\":\"c4\"")
            socket.send("nope")
            assertContains(socket.receive(Duration.ofSeconds(10)).orElseThrow(), "unknown request 'nope'")
        }
    }

    @Test
    fun `an OIDC endpoint does not start without an issuer, and says which variable names one`() {
        val failed = assertFailsWith<Exception> { AnkkaTestKit.start(Ankka.service("accounts").withEndpoint(AccountEndpoint::class.java)).close() }
        val messages = generateSequence<Throwable>(failed) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertContains(messages, "ANKKA_AUTH_ISSUERS")
    }
}
