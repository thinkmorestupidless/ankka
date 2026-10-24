package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import io.cucumber.java.AfterAll
import io.cucumber.java.Before
import io.cucumber.java.BeforeAll
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectDirectories
import org.junit.platform.suite.api.Suite

/**
 * The Scala cart's own living features, run by cucumber-jvm against the cart written in Kotlin:
 * each step is the request a customer's client makes over HTTP, as `CartFeatures` sends it to the
 * Scala cart. Every scenario runs; none is left out.
 */
@Suite
@IncludeEngines("cucumber")
@SelectDirectories("../../../../samples/shopping-cart/features")
@ConfigurationParameter(key = "cucumber.glue", value = "shoppingcart.kotlin")
@ConfigurationParameter(key = "cucumber.publish.quiet", value = "true")
class CartFeaturesTest

/** The steps: one instance per scenario, one service for them all. */
class CartSteps {
    private lateinit var cartId: String

    /** The answer to the last request a `When` step made. */
    private var last = 0 to ""

    private var told = emptyList<String>()

    @Before
    fun newCart() {
        cartId = "cart-" + UUID.randomUUID()
    }

    private val cart
        get() = "/carts/$cartId"

    private fun add(quantity: Int, product: String) =
        send("POST", "$cart/items", """{"productId":"${id(product)}","name":"$product","quantity":$quantity}""")

    private fun setUp(result: Pair<Int, String>) = assertEquals(204, result.first, "setting up the cart failed: ${result.second}")

    private fun quantityOf(product: String): Int {
        val (status, body) = send("GET", cart)
        assertEquals(200, status, body)
        return quantityIn(body, product)
    }

    private fun refused(status: Int, words: String) {
        assertEquals(status, last.first, "expected a refusal with $status: ${last.second}")
        assertTrue(last.second.contains(words), "refused, but not because $words: ${last.second}")
    }

    @Given("an empty cart")
    fun anEmptyCart() = Unit

    @Given("a cart holding {int} of {string}")
    fun aCartHolding(quantity: Int, product: String) = setUp(add(quantity, product))

    @Given("a cart holding {int} of {string} and {int} of {string}")
    fun aCartHoldingTwo(q1: Int, p1: String, q2: Int, p2: String) {
        setUp(add(q1, p1))
        setUp(add(q2, p2))
    }

    @Given("a checked-out cart holding {int} of {string}")
    fun aCheckedOutCart(quantity: Int, product: String) {
        setUp(add(quantity, product))
        assertEquals(200, send("POST", "$cart/checkout").first)
    }

    @When("the customer adds {int} of {string}")
    fun adds(quantity: Int, product: String) {
        last = add(quantity, product)
    }

    @When("the customer removes {string}")
    fun removes(product: String) {
        last = send("DELETE", "$cart/items/${id(product)}")
    }

    @When("the customer checks out")
    fun checksOut() {
        last = send("POST", "$cart/checkout")
    }

    @When("the customer discards the cart")
    fun discards() {
        last = send("DELETE", cart)
    }

    @Then("the cart holds {int} of {string}")
    fun holds(quantity: Int, product: String) = assertEquals(quantity, quantityOf(product))

    @Then("the cart holds {int} item(s) in total")
    fun holdsInTotal(total: Int) {
        val (status, body) = send("GET", "$cart/total")
        assertEquals(200 to total.toString(), status to body.trim())
    }

    @Then("the cart is checked out")
    fun isCheckedOut() {
        val (status, body) = send("GET", cart)
        assertEquals(200, status, body)
        assertTrue(body.contains("\"checkedOut\":true"), body)
    }

    @Then("the cart is empty")
    fun isEmpty() {
        val (status, body) = send("GET", cart)
        assertEquals(200, status, body)
        assertTrue(body.contains("\"items\":[]"), body)
    }

    @Then("the checkout holds {int} of {string}")
    fun checkoutHolds(quantity: Int, product: String) {
        assertEquals(200, last.first, last.second)
        assertTrue(last.second.contains("\"checkedOut\":true"), last.second)
        assertEquals(quantity, quantityIn(last.second, product))
    }

    @Then("the checkout notice of the cart is read from the topic")
    fun noticeRead() = runBlocking {
        // Read through a topic and a view, so it arrives a moment after the checkout answers.
        kit.eventually(30.seconds) {
            val (status, body) = send("GET", "/checkouts-seen/$cartId")
            assertEquals(200, status, "no checkout notice of the cart was read: $body")
            assertTrue(body.contains("\"cartId\":\"$cartId\""), body)
            assertTrue(body.contains("\"notices\":1"), body)
        }
    }

    @Then("the addition is refused because the quantity is not positive")
    fun notPositive() = refused(400, "quantity must be greater than zero")

    @Then("the removal is refused because the cart does not hold the product")
    fun notHeld() = refused(404, "cart does not contain")

    @Then("the checkout is refused because the cart is empty")
    fun emptyCheckout() = refused(400, "cannot check out an empty cart")

    @Then("the {word} is refused because the cart is checked out")
    fun refusedCheckedOut(change: String) {
        assertTrue(change in setOf("addition", "removal", "checkout", "discard"), change)
        refused(409, "cart is already checked out")
    }

    // ── The cart's tools, as an assistant calls them ─────────────────────────

    /** One JSON-RPC request to the cart's MCP endpoint, as any MCP client sends it. */
    private fun mcp(method: String, params: String): String {
        val (status, body) =
            send("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""", "Authorization" to "Bearer $assistantKey")
        assertEquals(200, status, body)
        return body
    }

    private fun callTool(name: String, arguments: String) {
        last = 0 to mcp("tools/call", """{"name":"$name","arguments":$arguments}""")
    }

    @When("the customer's assistant asks for the cart's tools")
    fun asksForTools() {
        told = Regex(""""name":"([a-z_]+)"""").findAll(mcp("tools/list", "{}")).map { it.groupValues[1] }.toList()
    }

    @Then("the assistant is told the tools {string}, {string} and {string}")
    fun toldTools(a: String, b: String, c: String) = assertEquals(listOf(a, b, c), told)

    @When("the customer's assistant adds {int} of {string} with the tool {string}")
    fun assistantAdds(quantity: Int, product: String, tool: String) =
        callTool(tool, """{"cartId":"$cartId","productId":"${id(product)}","quantity":$quantity}""")

    @When("the customer's assistant reads the cart with the tool {string}")
    fun assistantReads(tool: String) = callTool(tool, """{"cartId":"$cartId"}""")

    @When("the customer's assistant checks the cart out with the tool {string}")
    fun assistantChecksOut(tool: String) {
        callTool(tool, """{"cartId":"$cartId"}""")
        assertTrue(last.second.contains("\"isError\":false"), last.second)
    }

    @Then("the assistant is told the cart holds {int} of {string}")
    fun toldHolds(quantity: Int, product: String) {
        assertTrue(last.second.contains("\"isError\":false"), last.second)
        // The structured content is the cart itself, as the cart's own read answers it.
        assertEquals(quantity, quantityIn(last.second.substring(last.second.indexOf("\"structuredContent\"")), product))
    }

    @Then("the assistant is told {string}")
    fun toldWords(words: String) {
        assertTrue(last.second.contains("\"isError\":true"), last.second)
        assertTrue(last.second.contains(words), last.second)
    }
}

// One service for every scenario.

private lateinit var kit: AnkkaTestKit

/** The key the customer's assistant carries to the cart's tools. */
private lateinit var assistantKey: String

// Top-level, so each is a plain static method: cucumber refuses a companion object's.
@BeforeAll
fun startService() {
    kit = AnkkaTestKit.start(service())
    // The assistant holds the customer's key, issued as the sample issues one; until the keys
    // have loaded, the issue is answered 503.
    val asked = """{"subject":"customer","label":"the customer's assistant"}"""
    val issued =
        runBlocking {
            var answer = 0 to ""
            kit.eventually(30.seconds) {
                answer = send("POST", "/mcp-keys", asked)
                assertTrue(answer.first != 503, answer.second)
            }
            answer
        }
    assertEquals(200, issued.first, issued.second)
    assistantKey = Regex(""""clientKey":"([^"]+)"""").find(issued.second)!!.groupValues[1]
}

@AfterAll
fun stopService() = kit.close()

private fun send(method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): Pair<Int, String> {
    val builder = HttpRequest.newBuilder(kit.http().resolve(path)).timeout(Duration.ofSeconds(30))
    headers.forEach { (name, value) -> builder.header(name, value) }
    if (body != null) builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
    else builder.method(method, HttpRequest.BodyPublishers.noBody())
    val response = kit.httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    return response.statusCode() to response.body()
}

private fun id(product: String) = product.lowercase().replace(Regex("[^a-z0-9]+"), "-")

private fun quantityIn(body: String, product: String): Int =
    Regex(""""productId":"${id(product)}","name":"[^"]*","quantity":(-?\d+)""").find(body)?.groupValues?.get(1)?.toInt() ?: 0

private val Int.seconds
    get() = kotlin.time.Duration.parse("${this}s")
