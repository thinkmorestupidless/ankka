package shoppingcart.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.grpc.GrpcEndpoint
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain
import shoppingcart.v1.cart.{Cart, CartServiceGrpc, LineItem, WhoCalledReply}

// docs:start grpc-endpoint
/**
 * The cart's gRPC API: the service definition in `shopping-cart-api/…/cart.proto`, implemented over
 * the same entity the HTTP endpoint calls.
 *
 * Like the HTTP endpoint it holds no state and maps no errors: a rejection from the entity carries
 * its `ErrorCode`, which reaches the caller as the matching gRPC status. What it does do is
 * translate, between the messages of the API and the cart's own types — the API is a contract with
 * callers, and the domain is free to change behind it.
 */
final class CartGrpcEndpoint(clients: EndpointClients)
    extends GrpcEndpoint(CartServiceGrpc.SERVICE):

  val acl: Acl = Acl.AllowAll

  unary(CartServiceGrpc.METHOD_GET_CART) { request =>
    toProto(cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
  }

  unary(CartServiceGrpc.METHOD_ADD_ITEM) { request =>
    val item = request.item.getOrElse(LineItem())
    val _ = cart(request.cartId)
      .call(ShoppingCartEntity.addItem)
      .invoke(domain.LineItem(item.productId, item.name, item.quantity))
    toProto(cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
  }
  // docs:end grpc-endpoint

  unary(CartServiceGrpc.METHOD_WHO_CALLED) { _ =>
    WhoCalledReply(caller = "local", instance = sys.env.getOrElse("HOSTNAME", "local"))
  }

  private def cart(cartId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(cartId))

  private def toProto(cart: domain.ShoppingCart): Cart =
    Cart(
      cartId = cart.cartId,
      items = cart.items.map(i => LineItem(i.productId, i.name, i.quantity)),
      checkedOut = cart.checkedOut
    )
