package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.handling
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

/**
 * The cart's MCP endpoint at `/mcp`. `get_cart` answers the principal beside the cart, so a case
 * can see who a client key or a token made the caller.
 */
// docs:start mcp-endpoint
class CartMcp : McpEndpoint() {

    @Manifest("tool-cart")
    data class ToolCart(val cart: ShoppingCart, val subject: String?, val claims: Map<String, String>)

    data class CartArgs(@Describe("The cart's id") val cartId: String)

    data class AddItemArgs(
        @Describe("The cart's id") val cartId: String,
        @Describe("The product's id") val productId: String,
        @Describe("How many; greater than zero") val quantity: Int,
    )

    init {
        tool("get_cart").describedAs("Reads one cart").handling<CartArgs> { args ->
            val p = principal().orNull()
            ToolCart(
                componentClient().forEventSourcedEntity(args.cartId).call(ShoppingCartEntity::getCart).invokeSuspending(),
                p?.subject,
                p?.claims ?: emptyMap(),
            )
        }

        tool("add_item").describedAs("Adds an item to a cart").handling<AddItemArgs> { args ->
            componentClient()
                .forEventSourcedEntity(args.cartId)
                .call(ShoppingCartEntity::addItem)
                .invokeSuspending(LineItem(args.productId, args.productId, args.quantity))
        }

        tool("checkout").describedAs("Checks a cart out").command(ShoppingCartEntity::checkout, "cartId")
    }

    override fun acl(): Acl = Acl.anyOf(Acl.clientKeys(), KotlinReference.authenticated)
}
// docs:end mcp-endpoint
