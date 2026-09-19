import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.Ankka
import shoppingcart.api.ShoppingCartEndpoint
import shoppingcart.application.ShoppingCartEntity

/**
 * The whole service definition.
 *
 * Registration is explicit, so this is also the complete inventory of what the service hosts —
 * there is nothing discovered by scanning at startup.
 *
 * Needs Postgres: `docker compose up -d`.
 */
@main def runShoppingCart(): Unit =
  val service = Ankka.service
    .register(ShoppingCartEntity.descriptor)
    .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
