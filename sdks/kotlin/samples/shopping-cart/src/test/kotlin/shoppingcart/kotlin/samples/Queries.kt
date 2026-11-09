package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.AuthDecision
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Principal
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.http.Response
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.headerOrNull
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.queryOrNull
import com.thinkmorestupidless.ankka.kotlin.receiveOrNull
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.util.stream.Stream
import shoppingcart.kotlin.Cart
import shoppingcart.kotlin.CartRows
import shoppingcart.kotlin.CheckoutWorkflow
import shoppingcart.kotlin.ShoppingCart

data class SearchResult(val term: String, val limit: Int, val tags: List<String>, val verbose: Boolean)

/** Exercises query parameters and headers from inside handlers. */
class QueryEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    // docs:start query
    /** Required, optional-with-default, repeated, and flag parameters. */
    @Get("/search")
    fun search(): SearchResult =
        SearchResult(
            term = request().requiredQuery("q"),
            limit = request().queryOrNull("limit")?.toInt() ?: 20,
            tags = request().queryAll("tag"),
            verbose = request().flag("verbose"),
        )
    // docs:end query

    // docs:start header
    /** Headers. */
    @Get("/search/trace")
    fun trace(): String = request().headerOrNull("X-Trace-Id") ?: "none"
    // docs:end header

    // docs:start sse-query
    /** A streaming route reads its parameters while the stream is built, on the request's thread. */
    @Sse("/search/stream")
    fun stream(): Stream<SseEvent> {
        val term = request().requiredQuery("q")
        val count = request().queryOrNull("count")?.toInt() ?: 2
        return (1..count).map { SseEvent.text("$term-$it") }.stream()
    }
    // docs:end sse-query
}

// docs:start gated
/** An endpoint whose ACL inspects the request: the same request the handler sees. */
class GatedEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowIf { it.header("X-Api-Key").orNull() == "let-me-in" || it.flag("public") }

    @Get("/gated")
    fun root(): String = "allowed"
}
// docs:end gated

// docs:start route-acl
/**
 * One path, two audiences: reading a cart is public, purging one is not.
 *
 * An ACL belongs to an endpoint class, so the routes under one path that admit different callers
 * are two classes, each with its own ACL.
 */
class CartReadEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/mixed/{cartId}")
    fun read(cartId: String): String = "cart:$cartId"
}

class CartPurgeEndpoint : HttpEndpoint() {
    override fun acl(): Acl =
        Acl.authenticate { request ->
            request.headerOrNull("X-Support-Id")?.let { AuthDecision.allow(Principal.of(it)) }
                ?: AuthDecision.unauthenticated("realm=\"support\"")
        }

    @Delete("/mixed/{cartId}")
    fun purge(cartId: String): String = "purged:$cartId by ${principal().orElseThrow().subject()}"
}
// docs:end route-acl

// docs:start oidc-endpoint
/** An endpoint whose users sign in with an identity provider the service lists. */
class AccountEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.oidc()

    @Get("/account/me")
    fun me(): String {
        val principal = principal().orElseThrow()
        return "${principal.subject()} from ${principal.issuer().orNull() ?: "?"} " +
            "roles=${principal.roles().sorted().joinToString(",")} " +
            "tier=${principal.claims()["tier"] ?: ""}"
    }
}
// docs:end oidc-endpoint

/** An endpoint that serves a website rather than an API. */
class SiteEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    private val page = "<html><body>account</body></html>"
    private val cookie = "session=abc; Path=/; HttpOnly"
    private val stylesheet = "body { margin: 0 }".toByteArray()

    override fun routes() = routes {
        // docs:start pages
        get("/account") { Response.html(page).header("Set-Cookie", cookie) }
        post("/logout") { Response.redirect("/") } // 303 See Other, Location: /
        get("/style.css") { Response.bytes(stylesheet, "text/css") }
        // docs:end pages
    }
}

/** The request, a view and a problem. */
class CartRowsEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        // docs:start problem
        get("/carts/{cartId}/rows") {
            viewClient().forView(CartRows::class.java).getOrNull(path("cartId"))
                ?: throw ProblemException(Problem.notFound("no row for cart '${path("cartId")}'"))
        }
        // docs:end problem

        // docs:start pass-metadata
        post("/carts/{cartId}/checkouts") {
            // The request's context is already on this thread, so the call joins its trace.
            componentClient().forWorkflow(path("cartId")).call(CheckoutWorkflow::start).invokeSuspending(path("cartId"))
        }
        // docs:end pass-metadata
    }
}

/** A socket over a cart. */
class CartWatchEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    private val carts = Serializer.of(Cart::class.java)

    private fun cartJson(cartId: String): String =
        String(carts.toBytes(componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke()))

    // docs:start socket
    // A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
    // open. The handler is ordinary blocking code on a virtual thread; `receiveOrNull()` answers null
    // once the socket is closed, which ends the loop and the handler.
    @Socket("/carts/{cartId}/watch")
    fun watch(cartId: String, socket: WebSocket) {
        generateSequence { socket.receiveOrNull() }.forEach { message ->
            when (message) {
                "refresh" -> socket.send(cartJson(cartId))
                else -> socket.send("""{"error":"unknown request '$message'; send refresh"}""")
            }
        }
    }
    // docs:end socket
}
