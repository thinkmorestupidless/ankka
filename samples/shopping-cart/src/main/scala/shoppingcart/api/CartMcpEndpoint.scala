package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.mcp.{ClientKeys, McpEndpoint}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart}

// docs:start endpoint
/**
 * The cart as an MCP server: a customer's assistant — Claude, a coding agent, a product's own —
 * reads a cart, adds to it and checks it out through these tools, at `/mcp`, rather than through
 * the cart's routes.
 *
 * Two tools are the entity's own handlers, declared by reference; one has a handler of its own,
 * because its arguments are not the command's input.
 */
final class CartMcpEndpoint(client: ComponentClient) extends McpEndpoint("/mcp"):

  // docs:start acl
  // A request carrying one of the cart's client keys, as the key's subject (`CartKeysEndpoint`).
  val acl: Acl = ClientKeys.authenticate()
  // docs:end acl

  /** How a cart is told to the assistant: its JSON, which is also the tool's structured content. */
  private given JsonValueCodec[ShoppingCart] = Codecs.make

  // docs:start reference
  val getCart = tool("get_cart")
    .describedAs("Reads one cart: its lines, and whether it is checked out")
    .query(ShoppingCartEntity.getCart, id = "cartId")
  // docs:end reference

  // docs:start handler
  val addItem = tool("add_item")
    .describedAs("Adds a quantity of a product to a cart")
    .param[String]("cartId", "The cart's id")
    .param[String]("productId", "The product's id")
    .param[Int]("quantity", "How many; greater than zero")
    .handle { (cartId: String, productId: String, quantity: Int) =>
      client
        .forEventSourcedEntity(EntityId(cartId))
        .call(ShoppingCartEntity.addItem)
        .invoke(LineItem(productId, productId, quantity))
    }
  // docs:end handler

  val checkout = tool("checkout")
    .describedAs("Checks a cart out, answering what it held")
    .command(ShoppingCartEntity.checkout, id = "cartId")
// docs:end endpoint
