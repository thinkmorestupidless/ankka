package shoppingcart.openapi

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.dsl.routes

/** One route described in Kotlin's own form, for features/openapi-documents/languages.feature. */
class DescribedCartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        described({ refuses(404, "not found"); query<String>("currency") }) {
            get("/carts/{cartId}") { path("cartId") }
        }
    }
}

/** A service of that one route, started as a service's `main` starts it. */
fun main() {
    Ankka.service("cart").withEndpoint(DescribedCartEndpoint::class.java).start()
        .whenTerminated().toCompletableFuture().join()
}
