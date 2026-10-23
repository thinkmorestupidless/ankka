package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;
import java.util.Map;
import java.util.Optional;

/**
 * The cart's MCP endpoint at {@code /mcp}. {@code get_cart} answers the principal beside the cart,
 * so a case can see who a client key or a token made the caller.
 */
public class CartMcp extends McpEndpoint {

  @Manifest("tool-cart")
  public record ToolCart(ShoppingCart cart, Optional<String> subject, Map<String, String> claims) {}

  public record CartArgs(@Describe("The cart's id") String cartId) {}

  public record AddItemArgs(
      @Describe("The cart's id") String cartId,
      @Describe("The product's id") String productId,
      @Describe("How many; greater than zero") int quantity) {}

  public CartMcp() {
    tool("get_cart")
        .describedAs("Reads one cart")
        .arguments(CartArgs.class)
        .handle(
            args -> {
              var p = principal();
              return new ToolCart(
                  componentClient()
                      .forEventSourcedEntity(args.cartId())
                      .call(ShoppingCartEntity::getCart)
                      .invoke(),
                  p.map(Principal::subject),
                  p.map(Principal::claims).orElse(Map.of()));
            });

    tool("add_item")
        .describedAs("Adds an item to a cart")
        .arguments(AddItemArgs.class)
        .handle(
            args ->
                componentClient()
                    .forEventSourcedEntity(args.cartId())
                    .call(ShoppingCartEntity::addItem)
                    .invoke(new LineItem(args.productId(), args.productId(), args.quantity())));

    tool("checkout").describedAs("Checks a cart out").command(ShoppingCartEntity::checkout, "cartId");
  }

  @Override
  protected Acl acl() {
    return Acl.anyOf(Acl.clientKeys(), JavaReference.authenticated());
  }
}
