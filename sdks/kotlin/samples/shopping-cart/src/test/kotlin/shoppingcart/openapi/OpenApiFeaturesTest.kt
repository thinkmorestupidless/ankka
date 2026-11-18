package shoppingcart.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.cucumber.java.AfterAll
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectFile
import org.junit.platform.suite.api.Suite

/**
 * `features/openapi-documents/languages.feature` (feature 067): its Kotlin rows, against the cart
 * written in Kotlin. Its document is written by its own `main`, as `ankka openapi` writes it, and
 * compared with the Scala cart's kept document. The Scala and Java rows are skipped here and run by
 * `OpenApiJavaFeatures`; the rows of languages with no document yet are `OpenApiCommandSuite`'s.
 */
@Suite
@IncludeEngines("cucumber")
@SelectFile("../../../../features/openapi-documents/languages.feature")
@ConfigurationParameter(key = "cucumber.glue", value = "shoppingcart.openapi")
@ConfigurationParameter(key = "cucumber.filter.name", value = OpenApiFeaturesTest.SCENARIOS)
@ConfigurationParameter(key = "cucumber.publish.quiet", value = "true")
class OpenApiFeaturesTest {
    companion object {
        /** The scenarios that have a Kotlin row, by name. */
        const val SCENARIOS =
            "^(the same service has the same document in every language that has one" +
                "|a route description says the same in every language that has one)$"
    }
}

class OpenApiSteps {
    private lateinit var scenario: String
    private var language = ""
    private var described = false
    private lateinit var document: JsonNode

    @Before
    fun named(scenario: io.cucumber.java.Scenario) {
        this.scenario = scenario.name
    }

    @Given("the service {string} is written in {string}")
    fun writtenIn(service: String, written: String) {
        assertEquals("cart", service)
        assumeTrue(written == "kotlin", "the $written row is run by OpenApiJavaFeatures")
        language = written
        kotlinRows += scenario
    }

    @Given("the route {string} has a route description naming the refusal {string} and the query parameter {string}")
    fun describedRoute(route: String, refusal: String, query: String) {
        assertEquals("GET /carts/{cartId}", route)
        assertEquals("not found" to "currency", refusal to query)
        described = true
    }

    @When("the developer writes the service's OpenAPI document")
    fun writes() {
        assertEquals("kotlin", language)
        val main = if (described) "shoppingcart.openapi.DescribedCartKt" else "shoppingcart.kotlin.MainKt"
        document = WrittenDocument.parse(WrittenDocument.of(main))
    }

    @Then("the document holds the same routes, path parameters, answers and shapes as the service written in {string}")
    fun sameAs(other: String) {
        assertEquals("scala", other)
        val scala = WrittenDocument.parse(Files.readAllBytes(Path.of("../../../../samples/shopping-cart/openapi.json")))
        val expected = WrittenDocument.operations(scala, "/carts")
        val found = WrittenDocument.operations(document, "/carts")
        assertTrue(expected.isNotEmpty(), "the Scala cart's document holds no route under /carts")
        assertEquals(expected.keys, found.keys, "the routes")
        val differences = expected.toSortedMap().flatMap { (route, op) ->
            WrittenDocument.differences(route, op, scala, found.getValue(route), document)
        }
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
    }

    @Then("the route has the refusal {string} and the query parameter {string}")
    fun refusalAndQuery(refusal: String, query: String) {
        val op = document.path("paths").path("/carts/{cartId}").path("get")
        val reasons = op.path("responses").properties().map { it.value.path("description").asText() }
        assertTrue(refusal in reasons, "$reasons")
        val queries = op.path("parameters").filter { it.path("in").asText() == "query" }.map { it.path("name").asText() }
        assertTrue(query in queries, "$queries")
    }
}

/** The scenario of every Kotlin row that ran: a row skipped or filtered out would look like a pass. */
private val kotlinRows = ConcurrentLinkedQueue<String>()

// Top-level, so it is a plain static method: cucumber refuses a companion object's.
@AfterAll
fun everyKotlinRowRan() {
    assertEquals(2, kotlinRows.size, "Kotlin rows run: $kotlinRows")
}
