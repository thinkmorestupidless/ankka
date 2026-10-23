package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.fixtures.Counter;

/** Method references to the fixtures' handlers, as a Java test writes them. */
final class CartRefs {

  static final CommandRef<ShoppingCart, LineItem, Done> addItem = ShoppingCart::addItem;
  static final CommandRef<ShoppingCart, String, Done> removeItem = ShoppingCart::removeItem;
  static final NoArgRef<ShoppingCart, Done> checkout = ShoppingCart::checkout;
  static final NoArgRef<ShoppingCart, CartState> getCart = ShoppingCart::getCart;
  static final CommandRef<Counter, Integer, Long> increase = Counter::increase;
  static final NoArgRef<Counter, Long> counterGet = Counter::get;

  private CartRefs() {}
}
