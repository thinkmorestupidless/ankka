package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.CommandException;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

/** A cart's tools: three that name its handlers, and three built here. */
public class CartTools extends McpEndpoint {

  public record Greeting(String name, int times) {}

  public CartTools() {
    tool("add_item").describedAs("Adds an item to a cart").command(ShoppingCart::addItem, "cartId");
    tool("remove_item")
        .describedAs("Removes a product from a cart")
        .command(ShoppingCart::removeItem, "cartId", "productId");
    tool("get_cart").describedAs("Reads a cart").query(ShoppingCart::getCart, "cartId");
    tool("greet")
        .describedAs("Greets someone, as many times as asked")
        .arguments(Greeting.class)
        .handle(g -> ("hello " + g.name() + " ").repeat(g.times()).trim());
    tool("who")
        .describedAs("Says who is calling")
        .handle(() -> principal().map(Principal::subject).orElse("nobody"));
    tool("refuse")
        .describedAs("Refuses, always")
        .handle(
            () -> {
              throw new CommandException(Problem.badRequest("not today"));
            });
  }

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }
}
