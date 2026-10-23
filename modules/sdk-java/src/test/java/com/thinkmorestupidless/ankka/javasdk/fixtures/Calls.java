package com.thinkmorestupidless.ankka.javasdk.fixtures;

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.Done;

/** Call sites as a developer writes them, for the suites to resolve. */
public final class Calls {

  private Calls() {}

  public static CommandRef<ShoppingCart, LineItem, Done> addItem() {
    return ShoppingCart::putInCart;
  }

  public static NoArgRef<ShoppingCart, Cart> getCart() {
    return ShoppingCart::getCart;
  }

  public static NoArgRef<ShoppingCart, Cart> checkout() {
    return ShoppingCart::checkout;
  }

  public static CommandRef<ShoppingCart, LineItem, Done> lambda() {
    return (cart, item) -> cart.putInCart(item);
  }

  public static NoArgRef<Unregistered, String> unregistered() {
    return Unregistered::name;
  }
}
