package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import nakka.core.{Codecs, EntityId}
import nakka.http.*
import nakka.sdk.ComponentClient
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart}

/**
 * The API layer: HTTP in, component calls out.
 *
 * Note what is absent — no try/catch, no status codes, no error mapping. A rejection
 * from the entity carries its own `ErrorCode`, which the runtime turns into the right
 * status, so this layer only has to describe the happy path.
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

  private def cart(cartId: String) =
    client.forEventSourcedEntity(EntityId(cartId))
