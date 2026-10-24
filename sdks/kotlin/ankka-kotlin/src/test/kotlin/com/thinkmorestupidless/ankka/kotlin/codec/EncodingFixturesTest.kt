package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.codec.RecordCodec
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.lang.reflect.Type
import java.time.Duration
import java.util.Base64
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every payload fixture in `sdks/kotlin/fixtures` (a copy of `protocol/fixtures`, held to it by
 * `./gradlew checkFixtures`) decodes into its Kotlin shape and re-encodes to the same bytes. A
 * fixture with no Kotlin shape fails, as it does for every other SDK; the subdirectories hold other
 * features' rules and are read by their suites.
 */
class EncodingFixturesTest {

    private val dir = File(System.getProperty("ankka.fixtures") ?: fail("ankka.fixtures is not set"))

    // `option[int]` is a top-level optional, which only a Java type can name: Kotlin's `Int?` is no
    // class. A field of type `Int?` is the Kotlin form, and record-instants-and-options covers it.
    @Suppress("unused")
    private val optionalInt: Optional<Int>? = null
    private val optionalIntType: Type = javaClass.getDeclaredField("optionalInt").genericType

    private val types: Map<String, Type> =
        mapOf(
            "shopping-cart" to FixtureShoppingCart::class.java,
            "shopping-cart-event" to FixtureShoppingCartEvent::class.java,
            "plan" to FixturePlan::class.java,
            "service" to FixtureService::class.java,
            "service-event" to FixtureServiceEvent::class.java,
            "status" to FixtureStatus::class.java,
            "numbers" to FixtureNumbers::class.java,
            "tree" to FixtureTree::class.java,
            "string" to String::class.java,
            "int" to Int::class.javaObjectType,
            "long" to Long::class.javaObjectType,
            "double" to Double::class.javaObjectType,
            "boolean" to Boolean::class.javaObjectType,
            "duration-millis" to Duration::class.java,
            "done" to Done::class.java,
            "unit" to Void::class.java,
            "bytes" to ByteArray::class.java,
            "option[int]" to optionalIntType,
        )

    private val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private fun field(json: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: fail("no $name in $json")

    @Test
    fun `the fixtures are there`() {
        assertTrue(files.isNotEmpty(), "no fixtures under $dir")
    }

    @TestFactory
    fun fixtures(): List<DynamicTest> =
        files.map { file ->
            DynamicTest.dynamicTest(file.name) {
                val json = file.readText()
                val manifest = field(json, "manifest")
                val bytes = Base64.getDecoder().decode(field(json, "bytes_base64"))
                val type = types[manifest] ?: fail("no Kotlin shape for manifest $manifest")
                @Suppress("UNCHECKED_CAST")
                val codec = RecordCodec.forType(type) as Serializer<Any?>
                assertEquals(manifest, codec.manifest())
                assertEquals(field(json, "content_type"), codec.contentType().mediaType())
                val decoded = codec.fromBytes(bytes)
                assertEquals(String(bytes, Charsets.UTF_8), String(codec.toBytes(decoded), Charsets.UTF_8))
                assertContentEquals(bytes, codec.toBytes(decoded))
            }
        }

    @Test
    fun `a fixture decodes into Kotlin values, not only back to its bytes`() {
        val file = File(dir, "sum-type-fieldless.json")
        val bytes = Base64.getDecoder().decode(field(file.readText(), "bytes_base64"))
        val decoded = RecordCodec.forType(FixtureShoppingCartEvent::class.java).fromBytes(bytes)
        assertEquals(FixtureShoppingCartEvent.CheckedOut, decoded)
    }
}
