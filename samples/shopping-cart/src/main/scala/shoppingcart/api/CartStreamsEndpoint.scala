package shoppingcart.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.WatchEvent
import com.thinkmorestupidless.ankka.grpc.GrpcEndpoint
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients}
import shoppingcart.application.{CartRow, CartRows, ShoppingCartEntity}
import shoppingcart.domain
import shoppingcart.v1.cart.{Cart, CartStreamsGrpc, ImportSummary, Line, LineItem}

/** The cart's streaming methods: one of each kind a stream can go. */
final class CartStreamsEndpoint(clients: EndpointClients)
    extends GrpcEndpoint(CartStreamsGrpc.SERVICE):

  val acl: Acl = Acl.allowCallers(Callers.anyInProject, Callers.internet)

  // docs:start server-stream
  // The cart as the view writes it, for as long as the caller watches: a watch of the cart's row,
  // which the view announces on every write, so nothing polls. A row given twice is sent once; the
  // caller's going away ends the watch.
  serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
    clients.viewClient
      .forView(CartRows)
      .watchRow(request.cartId)
      .collect { case WatchEvent.Row(_, row) => toProto(row) }
      .statefulMap(() => Option.empty[Cart])(
        (last, now) => (Some(now), Option.when(!last.contains(now))(now)),
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

  private def toProto(row: CartRow): Cart =
    Cart(
      cartId = row.cartId,
      items = row.productIds.map(p => LineItem(p, row.names.getOrElse(p, ""), row.quantities(p))),
      checkedOut = row.checkedOut
    )
