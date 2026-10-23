package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeyRequest
import com.thinkmorestupidless.ankka.javasdk.mcp.Expiry
import com.thinkmorestupidless.ankka.javasdk.mcp.IssuedClientKey
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.time.Duration
import java.util.Optional

// docs:start endpoint
/** The cart over HTTP, route for route as the Scala cart serves it, declared in code. */
class CartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
        get("/carts/{cartId}/total") { cart(path("cartId")).call(ShoppingCart::totalQuantity).invokeSuspending() }
        post<LineItem>("/carts/{cartId}/items") { item ->
            cart(path("cartId")).call(ShoppingCart::addItem).invokeSuspending(item)
        }
        delete("/carts/{cartId}/items/{productId}") {
            cart(path("cartId")).call(ShoppingCart::removeItem).invokeSuspending(path("productId"))
        }
        post("/carts/{cartId}/checkout") { cart(path("cartId")).call(ShoppingCart::checkout).invokeSuspending() }
        delete("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::discard).invokeSuspending() }
    }

    private fun cart(cartId: String): EntityRef = componentClient().forEventSourcedEntity(cartId)
}
// docs:end endpoint

/** The checkouts this service has read back from the topic, by cart id. */
class CheckoutsSeenEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/checkouts-seen/{cartId}")
    fun get(cartId: String): CheckoutsSeen.CheckoutSeen =
        viewClient().forView(CheckoutsSeen::class.java).getOrNull(cartId)
            ?: throw ProblemException(Problem.notFound("no checkout of '$cartId' has been read"))
}

/**
 * Issues and revokes the keys a customer's assistant carries to the cart's tools. Open to anyone,
 * as the Scala sample's is: a real product puts this behind its own sign-in.
 */
class CartKeysEndpoint : HttpEndpoint() {
    data class KeyRequest(val subject: String, val scope: Set<String>?, val label: String?)

    override fun acl(): Acl = Acl.allowAll()

    @Post("/mcp-keys")
    fun issue(@Body asked: KeyRequest): IssuedClientKey =
        clientKeys().issue(
            ClientKeyRequest(
                asked.subject,
                Expiry.after(Duration.ofDays(365)),
                Optional.empty(),
                asked.scope ?: emptySet(),
                emptyMap(),
                Optional.ofNullable(asked.label),
            )
        )

    @Delete("/mcp-keys/{id}")
    fun revoke(id: String): String {
        clientKeys().revoke(id)
        return "revoked"
    }
}
