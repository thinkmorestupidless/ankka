package com.thinkmorestupidless.ankka.kotlin.codec

import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import java.time.Instant

// The shapes `protocol/fixtures` were generated from (`EncodingShapes` in `modules/core`), declared
// as a Kotlin developer would declare them: data classes, a sealed interface with a data object
// case, nullable fields where the Scala shape has an Option.

data class FixtureLineItem(val productId: String, val name: String, val quantity: Int)

@Manifest("shopping-cart")
data class FixtureShoppingCart(val items: List<FixtureLineItem>, val checkedOut: Boolean)

@Manifest("shopping-cart-event")
sealed interface FixtureShoppingCartEvent {
    data class ItemAdded(val item: FixtureLineItem) : FixtureShoppingCartEvent

    data class ItemRemoved(val productId: String) : FixtureShoppingCartEvent

    data object CheckedOut : FixtureShoppingCartEvent
}

data class FixtureStep(val name: String, val order: Int)

@Manifest("plan")
data class FixturePlan(val title: String, val steps: List<FixtureStep>, val labels: Map<String, String>)

@Manifest("status")
enum class FixtureStatus { Ready, Failed }

@Manifest("service")
data class FixtureService(
    val name: String,
    val createdAt: Instant,
    val owner: String?,
    val note: String?,
    val status: FixtureStatus,
)

@Manifest("service-event")
sealed interface FixtureServiceEvent {
    data class Applied(val generation: Long, val at: Instant?) : FixtureServiceEvent
}

@Manifest("numbers")
data class FixtureNumbers(val big: Long, val half: Double, val tenBillion: Double, val tenth: Double, val negative: Int)

@Manifest("tree")
data class FixtureTree(val label: String, val children: List<FixtureTree>)
