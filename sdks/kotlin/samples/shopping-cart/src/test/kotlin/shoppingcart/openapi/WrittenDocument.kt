package shoppingcart.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** A service's OpenAPI document as `ankka openapi` writes it, and the comparisons the tests make. */
object WrittenDocument {
    private val mapper = ObjectMapper()

    /** `main` of [mainClass] with `ankka.document` set, in a fresh JVM with an empty environment. */
    fun of(mainClass: String): ByteArray {
        val dir = Files.createTempDirectory("openapi")
        val out = dir.resolve("openapi.json")
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val builder = ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                "-Dankka.document=openapi", "-Dankka.document.out=$out",
                "-Dankka.service.name=shopping-cart", mainClass,
            ).redirectErrorStream(true)
            builder.environment().clear()
            System.getenv("PATH")?.let { builder.environment()["PATH"] = it }
            val process = builder.start()
            val said = String(process.inputStream.readAllBytes())
            assertEquals(0, process.waitFor(), said)
            return Files.readAllBytes(out)
        } finally {
            Files.deleteIfExists(out)
            Files.deleteIfExists(dir)
        }
    }

    fun parse(bytes: ByteArray): JsonNode = mapper.readTree(bytes)

    /** Every operation of [document] under [prefix], as `METHOD path`. */
    fun operations(document: JsonNode, prefix: String): Map<String, JsonNode> =
        document.path("paths").properties()
            .filter { it.key.startsWith(prefix) }
            .flatMap { (path, ops) -> ops.properties().map { (m, op) -> "${m.uppercase()} $path" to op } }
            .toMap()

    /** [json] with every `$ref` replaced by the schema it names, so two names for one shape agree. */
    fun resolved(json: JsonNode, document: JsonNode): JsonNode = when {
        json.isObject && json.size() == 1 && json.has("\$ref") -> {
            val name = json["\$ref"].asText().removePrefix("#/components/schemas/")
            resolved(document.path("components").path("schemas").path(name), document)
        }
        json is ObjectNode -> JsonNodeFactory.instance.objectNode().also { o ->
            json.properties().forEach { (k, v) -> o.set<JsonNode>(k, resolved(v, document)) }
        }
        json is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { a -> json.forEach { a.add(resolved(it, document)) } }
        else -> json
    }

    /**
     * Where [other]'s operation differs from [expected]'s in what the language feature compares: the
     * path parameters, the body's shape and each answer's status and shape; the media type is not
     * compared, as the Java suite says why.
     */
    fun differences(route: String, expected: JsonNode, expectedDoc: JsonNode, other: JsonNode, otherDoc: JsonNode): List<String> {
        fun shapes(content: JsonNode, doc: JsonNode) =
            content.properties().mapNotNull { (_, m) -> m.get("schema")?.let { resolved(it, doc) } }
        fun answers(op: JsonNode, doc: JsonNode) =
            op.path("responses").properties().associate { (status, r) -> status to shapes(r.path("content"), doc) }
        val found = mutableListOf<String>()
        if (resolved(expected.path("parameters"), expectedDoc) != resolved(other.path("parameters"), otherDoc)) {
            found += "$route: the parameters differ"
        }
        if (shapes(expected.path("requestBody").path("content"), expectedDoc) !=
            shapes(other.path("requestBody").path("content"), otherDoc)
        ) found += "$route: the body's shape differs"
        val (a, b) = answers(expected, expectedDoc) to answers(other, otherDoc)
        if (a.keys != b.keys) found += "$route: answers ${a.keys.sorted()} against ${b.keys.sorted()}"
        a.keys.intersect(b.keys).sorted().forEach { if (a[it] != b[it]) found += "$route: the $it answer's shape differs" }
        return found
    }
}
