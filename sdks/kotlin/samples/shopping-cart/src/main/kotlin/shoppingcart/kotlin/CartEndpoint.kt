package shoppingcart.kotlin

import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Refuses
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.http.Request
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

/** A cart's receipt, as the receipt route answers it. */
data class Receipt(val cartId: String, val note: String, val items: Int, val currency: String, val copies: Int)

// docs:start endpoint
/** The cart over HTTP, route for route as the Scala cart serves it, declared in code. */
class CartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    // A route declared in code has no answer type of its own: each one's scope says what it
    // answers, for the service's OpenAPI document. Every route carries the route tag `cart`.
    override fun routes() = routes {
        described({ tag("cart") }) {
            described({ answers<Cart>() }) {
                get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
            }
            described({ answers<Int>() }) {
                get("/carts/{cartId}/total") {
                    cart(path("cartId")).call(ShoppingCart::totalQuantity).invokeSuspending()
                }
            }
            // Every write the entity refuses once the cart is checked out.
            described({ refuses(409, "the cart is already checked out") }) {
                described({ answers(204) }) {
                    post<LineItem>("/carts/{cartId}/items") { item ->
                        cart(path("cartId")).call(ShoppingCart::addItem).invokeSuspending(item)
                    }
                }
                described({ answers(204); refuses(404, "the cart does not hold the product") }) {
                    delete("/carts/{cartId}/items/{productId}") {
                        cart(path("cartId")).call(ShoppingCart::removeItem).invokeSuspending(path("productId"))
                    }
                }
                described({ answers<Cart>() }) {
                    post("/carts/{cartId}/checkout") {
                        cart(path("cartId")).call(ShoppingCart::checkout).invokeSuspending()
                    }
                }
                described({ answers(204) }) {
                    delete("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::discard).invokeSuspending() }
                }
            }
            // A text note in, two query parameters it reads itself and a refusal of its own.
            // docs:start described
            described({
                summary("A cart's receipt")
                body<String>()
                answers<Receipt>()
                query<String>("currency")
                query<Int>("copies")
                refuses(404, "the cart is empty")
            }) {
                post<String>("/carts/{cartId}/receipt") { note -> receipt(path("cartId"), note, request) }
            }
            // docs:end described
        }
    }

    private suspend fun receipt(cartId: String, note: String, request: Request): Receipt {
        val items = cart(cartId).call(ShoppingCart::getCart).invokeSuspending().items.sumOf { it.quantity }
        if (items == 0) throw ProblemException(Problem.notFound("the cart is empty"))
        val copies = request.query("copies").map { it.toInt() }.orElse(1)
        return Receipt(cartId, note, items, request.query("currency").orElse("GBP"), copies)
    }

    private fun cart(cartId: String): EntityRef = componentClient().forEventSourcedEntity(cartId)
}
// docs:end endpoint

/** The checkouts this service has read back from the topic, by cart id. */
// docs:start annotated-endpoint
class CheckoutsSeenEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/checkouts-seen/{cartId}")
    @Refuses(status = 404, reason = "no checkout of the cart has been read")
    fun get(cartId: String): CheckoutsSeen.CheckoutSeen =
        viewClient().forView(CheckoutsSeen::class.java).getOrNull(cartId)
            ?: throw ProblemException(Problem.notFound("no checkout of '$cartId' has been read"))
}
// docs:end annotated-endpoint

// docs:start keys
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
// docs:end keys
