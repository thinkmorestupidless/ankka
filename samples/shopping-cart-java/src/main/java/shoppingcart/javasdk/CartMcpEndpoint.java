package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

// docs:start endpoint
/**
 * The cart as an MCP server at {@code /mcp}: a customer's assistant reads a cart, adds to it and
 * checks it out through these tools rather than the cart's routes. Two tools are the entity's own
 * handlers, declared by reference; one has a handler of its own, because its arguments are not the
 * command's input.
 */
public class CartMcpEndpoint extends McpEndpoint {

  public record AddItemArgs(
      @Describe("The cart's id") String cartId,
      @Describe("The product's id") String productId,
      @Describe("How many; greater than zero") int quantity) {}

  public CartMcpEndpoint() {
    tool("get_cart")
        .describedAs("Reads one cart: its lines, and whether it is checked out")
        .query(ShoppingCart::getCart, "cartId");

    tool("add_item")
        .describedAs("Adds a quantity of a product to a cart")
        .arguments(AddItemArgs.class)
        .handle(
            args ->
                componentClient()
                    .forEventSourcedEntity(args.cartId())
                    .call(ShoppingCart::addItem)
                    .invoke(new LineItem(args.productId(), args.productId(), args.quantity())));

    tool("checkout")
        .describedAs("Checks a cart out, answering what it held")
        .command(ShoppingCart::checkout, "cartId");
  }

  // A request carrying one of the cart's client keys, as the key's subject (CartKeysEndpoint).
  @Override
  protected Acl acl() {
    return Acl.clientKeys();
  }
}
// docs:end endpoint
