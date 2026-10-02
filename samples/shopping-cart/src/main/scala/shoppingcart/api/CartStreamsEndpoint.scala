package shoppingcart.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.grpc.GrpcEndpoint
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients}
import org.apache.pekko.stream.scaladsl.Source
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain
import shoppingcart.v1.cart.{Cart, CartStreamsGrpc, ImportSummary, Line, LineItem}

import scala.concurrent.duration.DurationInt

/** The cart's streaming methods: one of each kind a stream can go. */
final class CartStreamsEndpoint(clients: EndpointClients)
    extends GrpcEndpoint(CartStreamsGrpc.SERVICE):

  val acl: Acl = Acl.allowCallers(Callers.anyInProject, Callers.internet)

  // docs:start server-stream
  // The cart after each change, for as long as the caller watches. An entity does not stream its
  // state, so this reads it every half second and sends it when it differs; the caller's going
  // away cancels the stream.
  serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
    Source
      .tick(0.seconds, 500.millis, ())
      .map(_ => cart(request.cartId).call(ShoppingCartEntity.getCart).invoke())
      .statefulMap(() => Option.empty[domain.ShoppingCart])(
        (last, now) => (Some(now), Option.when(!last.contains(now))(toProto(now))),
        _ => None
      )
      .collect { case Some(changed) => changed }
  }
  // docs:end server-stream

  // docs:start client-stream
  // Each item is added as it arrives, and the caller is answered once the stream ends. A refusal —
  // a checked-out cart — ends the call at once, with the items before it added.
  clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS) { items =>
    var added = 0
    items.foreach { request =>
      val item = request.item.getOrElse(LineItem())
      val _ = cart(request.cartId)
        .call(ShoppingCartEntity.addItem)
        .invoke(domain.LineItem(item.productId, item.name, item.quantity))
      added += 1
    }
    ImportSummary(added)
  }
  // docs:end client-stream

  // docs:start bidi-stream
  bidiStream(CartStreamsGrpc.METHOD_CONVERSE) { lines =>
    lines.asSource.map(line => Line(s"heard: ${line.text}"))
  }
  // docs:end bidi-stream

  private def cart(cartId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(cartId))

  private def toProto(cart: domain.ShoppingCart): Cart =
    Cart(
      cartId = cart.cartId,
      items = cart.items.map(i => LineItem(i.productId, i.name, i.quantity)),
      checkedOut = cart.checkedOut
    )
