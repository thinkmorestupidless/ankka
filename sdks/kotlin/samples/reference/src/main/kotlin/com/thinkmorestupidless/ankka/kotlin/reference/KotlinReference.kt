package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint

/**
 * The reference service in Kotlin, as the conformance suite hosts it: every component and route the
 * Java reference has, translated, so the suite holds the Kotlin SDK to the same protocol.
 */
class KotlinReference : ConformanceEntry {

    override fun language(): String = "Kotlin"

    override fun components(): List<Class<*>> = COMPONENTS

    override fun componentIds(): Set<String> = COMPONENTS.map { it.getAnnotation(ComponentId::class.java).value }.toSet()

    override fun readOnlyHandlers(): Map<String, Set<String>> =
        mapOf(
            "shopping-cart" to setOf("get-cart", "total-quantity"),
            "conformance" to setOf("count"),
            "profile" to setOf("get"),
            "checkout" to setOf("status"),
            "replicated-vehicle" to setOf("get"),
        )

    override fun endpoints(): List<Class<out HttpEndpoint>> =
        listOf(
            CartsEndpoint::class.java,
            TreeEndpoint::class.java,
            VehiclesEndpoint::class.java,
            JoinedEndpoint::class.java,
            ConformanceEndpoint::class.java,
            ClosedEndpoint::class.java,
            PrivateEndpoint::class.java,
            CallersEndpoint::class.java,
            SelfCallersEndpoint::class.java,
            GrantedCallersEndpoint::class.java,
            McpKeysEndpoint::class.java,
        )

    override fun mcpEndpoints(): List<Class<out McpEndpoint>> = listOf(CartMcp::class.java)

    override fun authenticateWith(authenticated: Acl) {
        Companion.authenticated = authenticated
    }

    companion object {
        /** What the suite's verified tokens are admitted by; nobody until the suite says. */
        @Volatile
        var authenticated: Acl = Acl.denyAll()
            private set

        private val COMPONENTS: List<Class<*>> =
            listOf(
                ShoppingCartEntity::class.java,
                ConformanceEntity::class.java,
                Profile::class.java,
                Checkout::class.java,
                CheckoutRows::class.java,
                CheckoutEnds::class.java,
                CartRows::class.java,
                CheckoutRecorder::class.java,
                CheckoutFanout::class.java,
                TopicRows::class.java,
                TopicRelay::class.java,
                ContractRelay::class.java,
                PartnerRelay::class.java,
                TreeNode::class.java,
                TreeRows::class.java,
                ReplicatedVehicle::class.java,
                VehicleFeed::class.java,
                JoinedLeft::class.java,
                JoinedRight::class.java,
                JoinedRows::class.java,
                CartGraph::class.java,
                ProfileGraph::class.java,
                Reminder::class.java,
            )
    }
}
