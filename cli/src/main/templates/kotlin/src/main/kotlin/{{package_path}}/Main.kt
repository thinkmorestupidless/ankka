package {{package}}

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder

// docs:start service
/** The service: every component and endpoint registered by name, nothing found by scanning. */
fun service(): ServiceBuilder =
    Ankka.service("{{name}}")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .withEndpoint(CartEndpoint::class.java)
// docs:end service

fun main() {
    service().start().whenTerminated().toCompletableFuture().join()
}
