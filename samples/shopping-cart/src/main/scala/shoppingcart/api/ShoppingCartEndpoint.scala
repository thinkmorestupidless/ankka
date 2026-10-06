package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart}

/**
 * The API layer: HTTP in, component calls out.
 *
 * Note what is absent — no try/catch, no status codes, no error mapping. A rejection from the
 * entity carries its own `ErrorCode`, which the runtime turns into the right status, so this layer
 * only has to describe the happy path.
 */
final class ShoppingCartEndpoint(client: ComponentClient) extends HttpEndpoint("/carts"):

  // Response and request bodies need JSON codecs; derived at compile time.
  private given JsonValueCodec[ShoppingCart] = Codecs.make[ShoppingCart]
  private given JsonValueCodec[LineItem]     = Codecs.make[LineItem]

  /** A public read/write API, stated deliberately rather than defaulted. */
  val acl: Acl = Acl.AllowAll

  get("/{cartId}") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.getCart).invoke()
  }

  get("/{cartId}/total") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.totalQuantity).invoke()
  }

  postBody("/{cartId}/items") { (cartId: String, item: LineItem) =>
    cart(cartId).call(ShoppingCartEntity.addItem).invoke(item)
  }

  delete("/{cartId}/items/{productId}") { (cartId: String, productId: String) =>
    cart(cartId).call(ShoppingCartEntity.removeItem).invoke(productId)
  }

  post("/{cartId}/checkout") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.checkout).invoke()
  }

  delete("/{cartId}") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.discard).invoke()
  }

  // docs:start socket
  // A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
  // open. The handler is ordinary blocking code on a virtual thread; `receive()` answers `None`
  // once the socket is closed, which ends the loop and the handler.
  socket("/{cartId}/watch") { (cartId: String, socket: Socket) =>
    Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach {
      case "refresh" =>
        socket.send(writeToString(cart(cartId).call(ShoppingCartEntity.getCart).invoke()))
      case other => socket.send(s"""{"error":"unknown request '$other'; send refresh"}""")
    }
  }
  // docs:end socket

  private def cart(cartId: String) =
    client.forEventSourcedEntity(EntityId(cartId))
