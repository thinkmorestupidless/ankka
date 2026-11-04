package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.core.KeyAnswer
import com.thinkmorestupidless.ankka.core.Keyring
import com.thinkmorestupidless.ankka.core.PersonalScope
import com.thinkmorestupidless.ankka.javasdk.Personal
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.kotlin.personal
import com.thinkmorestupidless.ankka.kotlin.subject
import com.thinkmorestupidless.ankka.kotlin.valueOrNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

data class PersonalAddress(val line1: String, val city: String)

data class PersonalString(val field: Personal<String>)

data class PersonalDate(val field: Personal<LocalDate>)

data class PersonalRecord(val field: Personal<PersonalAddress>)

// The fixtures, read with the SDK's own codec. `$personal` is skipped as unknown.
data class FixtureEnvelope(val project: String, val subject: String, val c: String?)

data class FixtureCase(
    val name: String,
    val key: String,
    val nonce: String,
    val project: String,
    val subject: String,
    val manifest: String,
    val plaintext: String,
    val envelope: FixtureEnvelope,
)

data class FixtureEnvelopes(val cases: List<FixtureCase>)

data class FixtureSubjects(val message: String, val valid: List<String>, val invalid: List<String>)

/**
 * A Kotlin data class's personal field (feature 042) against `fixtures/personal`, the copy of
 * `protocol/fixtures/personal`: each envelope byte for byte under its key and nonce and read back,
 * the edge form outside a scope, and the data subject's rule.
 */
class PersonalTest {

    private val dir = File(System.getProperty("ankka.fixtures") ?: fail("ankka.fixtures is not set"), "personal")

    private fun <A : Any> read(type: Class<A>, name: String): A = Serializer.of(type).fromBytes(File(dir, name).readBytes())

    private fun keyring(own: String, key: ByteArray): Keyring =
        object : Keyring {
            override fun project(): String = own

            override fun key(project: String, subject: String, create: Boolean): KeyAnswer = KeyAnswer.key(key)
        }

    private fun compact(e: FixtureEnvelope): String =
        "{\"\$personal\":1,\"project\":\"${e.project}\",\"subject\":\"${e.subject}\"" +
            (e.c?.let { ",\"c\":\"$it\"" } ?: "") + "}"

    /** The data class holding the case's value, read from its plaintext, and its serializer. */
    @Suppress("UNCHECKED_CAST")
    private fun holder(case: FixtureCase): Pair<Any, Serializer<Any>> {
        val s = subject(case.subject)
        val p = case.plaintext
        return when {
            p.startsWith("{") -> {
                val address = Serializer.of(PersonalAddress::class.java).fromBytes(p.toByteArray())
                PersonalRecord(personal(s, address)) to Serializer.of(PersonalRecord::class.java) as Serializer<Any>
            }
            Regex("\"\\d{4}-\\d{2}-\\d{2}\"").matches(p) ->
                PersonalDate(personal(s, LocalDate.parse(p.trim('"')))) to Serializer.of(PersonalDate::class.java) as Serializer<Any>
            else -> {
                val text = Serializer.of(PersonalString::class.java).fromBytes("{\"field\":{\"subject\":\"a\",\"value\":$p}}".toByteArray())
                PersonalString(personal(s, text.field.orElse(""))) to Serializer.of(PersonalString::class.java) as Serializer<Any>
            }
        }
    }

    @TestFactory
    fun `each envelope is written byte for byte and read back`(): List<DynamicTest> =
        read(FixtureEnvelopes::class.java, "envelopes.json").cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val (value, serializer) = holder(case)
                val k = keyring(case.project, Base64.getDecoder().decode(case.key))
                val nonce = scala.Option.apply(Base64.getDecoder().decode(case.nonce))
                val written = Keyring.binding(k) { PersonalScope.within(case.manifest, nonce) { serializer.toBytes(value) } }
                assertEquals("{\"field\":${compact(case.envelope)}}", String(written))
                val back = Keyring.binding(k) { PersonalScope.within(case.manifest, scala.Option.empty()) { serializer.fromBytes(written) } }
                assertEquals(value, back)
            }
        }

    @Test
    fun `the edge form is written and read outside a scope, present and erased`() {
        val s = subject("player/8c1f")
        val serializer = Serializer.of(PersonalString::class.java)
        val present = PersonalString(personal(s, "ada@example.com"))
        val written = serializer.toBytes(present)
        assertEquals("{\"field\":{\"subject\":\"player/8c1f\",\"value\":\"ada@example.com\"}}", String(written))
        assertEquals(present, serializer.fromBytes(written))
        assertEquals("ada@example.com", present.field.valueOrNull)

        val erased = serializer.fromBytes("{\"field\":{\"subject\":\"player/8c1f\"}}".toByteArray())
        assertTrue(erased.field.isErased)
        assertNull(erased.field.valueOrNull)
        assertEquals("Personal(player/8c1f)", erased.field.toString())
    }

    @Test
    fun `a data subject outside the rule is refused with the shared message`() {
        val subjects = read(FixtureSubjects::class.java, "subjects.json")
        subjects.invalid.forEach { invalid ->
            val e = runCatching { subject(invalid) }.exceptionOrNull() ?: fail("'$invalid' was accepted")
            assertEquals("'$invalid' is not a data subject: ${subjects.message}", e.message)
        }
        subjects.valid.forEach { assertEquals(it, subject(it).value()) }
    }

    @Test
    fun `a personal field written with no keyring is refused as unavailable`() {
        val e =
            runCatching {
                Keyring.binding(Keyring.unavailable()) {
                    PersonalScope.within("player-registered", scala.Option.empty()) {
                        Serializer.of(PersonalString::class.java).toBytes(PersonalString(personal(subject("a"), "x")))
                    }
                }
            }.exceptionOrNull() ?: fail("the write was not refused")
        val found = CommandError.from(e)
        if (found.isEmpty) fail("no CommandError behind $e")
        val error = found.get()
        assertTrue(error.message!!.contains("keyring"), error.message)
    }
}
