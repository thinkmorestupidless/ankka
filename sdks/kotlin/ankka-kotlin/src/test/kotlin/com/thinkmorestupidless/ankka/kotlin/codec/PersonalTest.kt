package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.core.KeyAnswer
import com.thinkmorestupidless.ankka.core.Keyring
import com.thinkmorestupidless.ankka.core.PersonalScope
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Personal
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.kotlin.personal
import com.thinkmorestupidless.ankka.kotlin.subject
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
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

    /**
     * features/erasure/languages.feature's JVM outline, its Kotlin row: the envelope is the Java
     * codec's, held to the fixtures above; here the service keeps no plain value, and the entity
     * recovered in Kotlin reads the field, and reads it as erased once its data subject is.
     */
    @Test
    fun `a service written in Kotlin keeps the field encrypted and recovers it`() {
        ankkaTestKit(Ankka.service("players").register(KotlinPlayers::class.java)) {
            fun email() = componentClient().forEventSourcedEntity("8c1f").call(KotlinPlayers::email).invoke()
            componentClient().forEventSourcedEntity("8c1f").call(KotlinPlayers::register).invoke("ada@example.com")
            assertNoPersonalValue("ada@example.com")
            restartService()
            assertEquals("ada@example.com", email())
            erase("player/8c1f")
            restartService()
            assertEquals("erased", email())
        }
    }

    // A field read from another project's envelope stays under that project's key when a Kotlin
    // service writes it again, as a Scala service's does; one it makes itself is its own.

    private val playersKey = ByteArray(32) { 3 }
    private val ownKey = ByteArray(32) { 7 }

    private fun twoProjects(own: String, playersErased: Boolean = false): Keyring =
        object : Keyring {
            override fun project(): String = own

            override fun key(project: String, subject: String, create: Boolean): KeyAnswer =
                when {
                    project != "players" -> KeyAnswer.key(ownKey)
                    // `Destroyed`, which Kotlin reaches only by its ordinal.
                    playersErased -> KeyAnswer.fromOrdinal(1)
                    else -> KeyAnswer.key(playersKey)
                }
        }

    private val strings = Serializer.of(PersonalString::class.java)

    private fun <T> scoped(keyring: Keyring, manifest: String, body: () -> T): T =
        Keyring.binding(keyring) { PersonalScope.within(manifest, scala.Option.empty()) { body() } }

    private fun fromPlayers(): ByteArray =
        scoped(twoProjects("players"), "player-registered") {
            strings.toBytes(PersonalString(personal(subject("player/8c1f"), "ada@example.com")))
        }

    private val envelope = Regex("\\{\"\\\$personal\":1,\"project\":\"([^\"]*)\",\"subject\":\"[^\"]*\"(?:,\"c\":\"([^\"]*)\")?}")

    @Test
    fun `a field read from another project is written again under that project's key`() {
        val bytes = fromPlayers()
        val engagement = twoProjects("engagement")
        val read = scoped(engagement, "player-registered") { strings.fromBytes(bytes) }
        assertEquals("ada@example.com", read.field.valueOrNull)
        val again = String(scoped(engagement, "engaged") { strings.toBytes(read.copy()) })
        val m = envelope.find(again) ?: fail("no envelope in $again")
        assertEquals("players", m.groupValues[1])
        val opened =
            com.thinkmorestupidless.ankka.core.PersonalCipher.open(
                playersKey,
                Base64.getDecoder().decode(m.groupValues[2]),
                com.thinkmorestupidless.ankka.core.PersonalCipher.associatedData("player/8c1f", "players", "engaged"),
            )
        assertEquals("\"ada@example.com\"", String(opened.get() as ByteArray))

        val made = String(scoped(engagement, "engaged") { strings.toBytes(PersonalString(personal(subject("player/8c1f"), "ada@example.com"))) })
        assertEquals("engagement", envelope.find(made)?.groupValues?.get(1))
    }

    @Test
    fun `a field of another project whose key is gone is written erased under that project`() {
        val bytes = fromPlayers()
        val engagement = twoProjects("engagement", playersErased = true)
        val read = scoped(engagement, "player-registered") { strings.fromBytes(bytes) }
        assertTrue(read.field.isErased)
        val again = String(scoped(engagement, "engaged") { strings.toBytes(read) })
        val m = envelope.find(again) ?: fail("no envelope in $again")
        assertEquals("players" to "", m.groupValues[1] to m.groupValues[2])
    }
}
