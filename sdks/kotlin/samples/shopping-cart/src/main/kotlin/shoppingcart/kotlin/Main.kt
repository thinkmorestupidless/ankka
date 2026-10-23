package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.handling
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

// docs:start tools
/**
 * The cart as an MCP server at `/mcp`: a customer's assistant reads a cart, adds to it and checks
 * it out through these tools rather than the cart's routes.
 */
class CartMcpEndpoint : McpEndpoint() {
    data class AddItemArgs(
        @Describe("The cart's id") val cartId: String,
        @Describe("The product's id") val productId: String,
        @Describe("How many; greater than zero") val quantity: Int,
    )

    init {
        tool("get_cart").describedAs("Reads one cart: its lines, and whether it is checked out").query(ShoppingCart::getCart, "cartId")
        tool("add_item").describedAs("Adds a quantity of a product to a cart").handling<AddItemArgs> { args ->
            componentClient()
                .forEventSourcedEntity(args.cartId)
                .call(ShoppingCart::addItem)
                .invokeSuspending(LineItem(args.productId, args.productId, args.quantity))
        }
        tool("checkout").describedAs("Checks a cart out, answering what it held").command(ShoppingCart::checkout, "cartId")
    }

    // A request carrying one of the cart's client keys, as the key's subject (CartKeysEndpoint).
    override fun acl(): Acl = Acl.clientKeys()
}
// docs:end tools

// docs:start service
fun service(): ServiceBuilder =
    Ankka.service("shopping-cart")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .register(CheckoutNotifier::class.java)
        .register(CheckoutsSeen::class.java)
        .register(CheckoutWorkflow::class.java)
        .withEndpoint(CartEndpoint::class.java)
        .withEndpoint(CheckoutsSeenEndpoint::class.java)
        .withEndpoint(CartKeysEndpoint::class.java)
        .withMcpEndpoint(CartMcpEndpoint::class.java)

fun main() {
    service().start().whenTerminated().toCompletableFuture().join()
}
// docs:end service
