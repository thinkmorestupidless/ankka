package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.codec.EncodingException
import com.thinkmorestupidless.ankka.javasdk.codec.RecordCodec
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

data class Item(val productId: String, val quantity: Int)

data class Cart(
    val owner: String,
    val items: List<Item> = emptyList(),
    val discount: Int = 0,
    val note: String? = null,
)

sealed interface Event {
    data class ItemAdded(val item: Item) : Event

    data object CheckedOut : Event
}

/** A data class is a state or an event as a Java record is, under the same encoding. */
class DataClassCodecTest {

    private val kotlinCart = RecordCodec.forType(Cart::class.java)
    private val javaCart = RecordCodec.forType(JavaShapes.Cart::class.java)
    private val kotlinEvent = RecordCodec.forType(Event::class.java)
    private val javaEvent = RecordCodec.forType(JavaShapes.Event::class.java)

    private val cart = Cart("ada", listOf(Item("socks", 2), Item("hat", 1)), 5, "gift")
    private val sameCart =
        JavaShapes.Cart(
            "ada",
            listOf(JavaShapes.Item("socks", 2), JavaShapes.Item("hat", 1)),
            5,
            Optional.of("gift"),
        )

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun `a data class is written as the Java record of the same shape is`() {
        assertEquals(text(javaCart.toBytes(sameCart)), text(kotlinCart.toBytes(cart)))
        val noNote = cart.copy(note = null)
        assertEquals(
            text(javaCart.toBytes(JavaShapes.Cart(sameCart.owner, sameCart.items, 5, Optional.empty()))),
            text(kotlinCart.toBytes(noNote)),
        )
    }

    @Test
    fun `each reads what the other wrote`() {
        assertEquals(cart, kotlinCart.fromBytes(javaCart.toBytes(sameCart)))
        assertEquals(sameCart, javaCart.fromBytes(kotlinCart.toBytes(cart)))
    }

    @Test
    fun `a sealed interface of data classes and a data object is the Java sum type`() {
        val events = listOf(Event.ItemAdded(Item("socks", 2)), Event.CheckedOut)
        val javaEvents = listOf(JavaShapes.Event.ItemAdded(JavaShapes.Item("socks", 2)), JavaShapes.Event.CheckedOut())
        events.zip(javaEvents).forEach { (k, j) ->
            assertEquals(text(javaEvent.toBytes(j)), text(kotlinEvent.toBytes(k)))
            assertEquals(k, kotlinEvent.fromBytes(javaEvent.toBytes(j)))
        }
        // A data object reads back as the one instance.
        assert(kotlinEvent.fromBytes(kotlinEvent.toBytes(Event.CheckedOut)) === Event.CheckedOut)
    }

    @Test
    fun `a field with a default that is absent takes the default`() {
        val written = """{"owner":"ada"}""".toByteArray()
        assertEquals(Cart("ada"), kotlinCart.fromBytes(written))
    }

    @Test
    fun `a field with no default that is absent fails, naming the field`() {
        val failure = assertFailsWith<EncodingException> { kotlinCart.fromBytes("""{"discount":0}""".toByteArray()) }
        assertContains(failure.message ?: "", "owner")
    }
}
