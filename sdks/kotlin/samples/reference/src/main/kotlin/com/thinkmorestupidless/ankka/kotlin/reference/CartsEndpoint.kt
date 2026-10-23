package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.call
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.suspend.getSuspending
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

/** The cart's routes, declared in code. */
class CartsEndpoint : HttpEndpoint(), RouteRegistrar {

    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        post<LineItem>("/carts/{cartId}/items") { item ->
            cart(path("cartId")).call(ShoppingCartEntity::addItem).invokeSuspending(item)
        }
        delete("/carts/{cartId}/items/{productId}") {
            cart(path("cartId")).call(ShoppingCartEntity::removeItem).invokeSuspending(path("productId"))
        }
        post("/carts/{cartId}/checkout") { cart(path("cartId")).call(ShoppingCartEntity::checkout).invokeSuspending() }
        delete("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCartEntity::discard).invokeSuspending() }
        get("/carts/awkward") { "literal" }
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCartEntity::getCart).invokeSuspending() }
        get("/carts/{cartId}/rows") {
            val cartId = path("cartId")
            viewClient().forView(CartRows::class.java).getSuspending(cartId)
                ?: throw ProblemException(Problem.notFound("no row for '$cartId'"))
        }
    }

    private fun cart(id: String): EntityRef = componentClient().forEventSourcedEntity(id)
}
