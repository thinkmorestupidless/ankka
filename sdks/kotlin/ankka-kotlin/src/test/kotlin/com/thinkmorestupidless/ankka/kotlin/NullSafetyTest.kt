package com.thinkmorestupidless.ankka.kotlin

import com.thinkmorestupidless.ankka.kotlin.steps.compile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A null given where the Java SDK takes a value is refused before build. The Java SDK's packages are
 * `@NullMarked`, so with `-Xjspecify-annotations=strict` (the Kotlin SDK's build and the template's)
 * every parameter is a non-null Kotlin type; a package that lost the annotation would make these
 * platform types again, and a null would compile and fail only when called.
 */
class NullSafetyTest {
    /** One call per kind of thing a service hands the Java SDK, with `$it` where the value goes. */
    private val calls =
        listOf(
            "fun cart(client: ComponentClient) = client.forEventSourcedEntity(\$it)",
            "fun missing() = Problem.notFound(\$it)",
            "fun keep(secrets: SecretStore) = secrets.put(\"key\", \$it)",
            "fun refuse() = ProblemException(Problem.conflict(\$it))",
        )

    private fun source(value: String): String =
        (
            listOf(
                "import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient",
                "import com.thinkmorestupidless.ankka.javasdk.http.Problem",
                "import com.thinkmorestupidless.ankka.javasdk.http.ProblemException",
                "import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore",
            ) + calls.map { it.replace("\$it", value) }
        ).joinToString("\n")

    private val firstCallLine = 5

    @Test
    fun `every call given a value builds`() {
        val given = compile(source("\"c1\""))
        assertTrue(given.succeeded, given.messages)
    }

    @Test
    fun `every call given null is refused at the line that gives it`() {
        val nulls = compile(source("null"))
        assertFalse(nulls.succeeded, "it built")
        val refused =
            Regex("""Service\.kt:(\d+):\d+: error: (.*)""")
                .findAll(nulls.messages)
                .map { it.groupValues[1].toInt() to it.groupValues[2] }
                .toList()
        assertEquals(calls.indices.map { it + firstCallLine }.toSet(), refused.map { it.first }.toSet(), nulls.messages)
        refused.forEach { (line, message) -> assertTrue(message.contains("null", ignoreCase = true), "line $line: $message") }
    }
}
