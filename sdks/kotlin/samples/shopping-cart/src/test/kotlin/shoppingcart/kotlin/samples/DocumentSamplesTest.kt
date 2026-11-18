package shoppingcart.kotlin.samples

import kotlin.test.Test
import kotlin.test.assertTrue

/** The OpenAPI page's samples: what they serve, and that a kit held to it passes. */
class DocumentSamplesTest {
    @Test
    fun `the served document holds the greeting route, its summary and its refusal`() {
        val document = servedDocument()
        assertTrue("\"/greetings/{name}\"" in document, document)
        assertTrue("A greeting for one name" in document, document)
        assertTrue("nobody has that name" in document, document)
    }

    @Test
    fun `a kit held to the document closes quietly when every answer is one it names`() {
        heldToItsDocument()
    }
}
