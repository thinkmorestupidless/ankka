package shoppingcart.openapi

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin cart's OpenAPI document (feature 067), written by its own `main`, kept beside it. */
class OpenApiDocumentTest {
    private val written by lazy { WrittenDocument.of("shoppingcart.kotlin.MainKt") }

    @Test
    fun `the sample's kept openapi json is the document its code gives`() {
        val kept = Path.of("openapi.json")
        if (System.getenv("ANKKA_DOCS_UPDATE") == "true") Files.write(kept, written)
        assertTrue(Files.exists(kept), "sdks/kotlin/samples/shopping-cart/openapi.json is not kept")
        assertTrue(
            Files.readAllBytes(kept).contentEquals(written),
            "sdks/kotlin/samples/shopping-cart/openapi.json is stale: run " +
                "ANKKA_DOCS_UPDATE=true just kotlin :samples:shopping-cart:test and commit it",
        )
    }

    @Test
    fun `every route of the cart is complete, the receipt described as the Scala cart's is`() {
        val carts = WrittenDocument.operations(WrittenDocument.parse(written), "/carts")
        assertEquals(emptyList(), carts.filterValues { it.has("x-ankka-incomplete") }.keys.toList(), "incomplete")
        val receipt = carts.getValue("POST /carts/{cartId}/receipt")
        assertEquals("A cart's receipt", receipt.path("summary").asText())
        assertEquals("the cart is empty", receipt.path("responses").path("404").path("description").asText())
    }
}
