package shoppingcart

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.{Ankka, ServiceBuilder}
import shoppingcart.api.ShoppingCartEndpoint
import shoppingcart.application.{CartRow, CartRows, CheckoutWorkflow, ShoppingCartEntity}

/**
 * The samples of the HTTP endpoints and component client guides (docs/build/http-endpoints.md,
 * docs/build/component-client.md) that need the cart: registering an endpoint, a view's row or a
 * 404, and a workflow started from a route. `GuideSamplesSuite` runs them.
 */
object GuideSamples:

  /** The cart and its endpoint, served by the `HttpServer` extension. */
  def service: ServiceBuilder =
    // docs:start http-registration
    Ankka.service
      .register(ShoppingCartEntity.descriptor)
      .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
    // docs:end http-registration

/** A cart's row from the view, and a checkout started for it. */
final class CartRowsEndpoint(clients: EndpointClients) extends HttpEndpoint("/carts"):

  private given JsonValueCodec[CartRow] = Codecs.make[CartRow]

  private val client = clients.componentClient

  val acl: Acl = Acl.AllowAll

  // docs:start view-client
  val rows = clients.viewClient.forView(CartRows)
  // docs:end view-client

  get("/rows/count")(() => rows.all().size)

  // docs:start problem
  get("/{cartId}/rows") { (cartId: String) =>
    clients.viewClient
      .forView(CartRows)
      .get(cartId)
      .getOrElse(throw HttpProblem.notFound(s"no row for cart '$cartId'"))
  }
  // docs:end problem

  // docs:start start-workflow
  postBody("/{cartId}/checkouts") { (cartId: String, mode: String) =>
    // The request's context is already on this thread, so the call joins its trace.
    client.forWorkflow(EntityId(cartId)).call(CheckoutWorkflow.start).invoke(mode)
  }
  // docs:end start-workflow
