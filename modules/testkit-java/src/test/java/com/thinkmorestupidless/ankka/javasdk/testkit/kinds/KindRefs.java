package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ActionRef;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgActionRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;

/** The method references the Scala suites call through: a Scala function is not one. */
public final class KindRefs {
  private KindRefs() {}

  public static final CommandRef<TreeNode, TreeNode.Placed, Done> place = TreeNode::place;
  public static final NoArgRef<TreeNode, Done> remove = TreeNode::remove;
  public static final CommandRef<ShoppingCart, LineItem, Done> addItem = ShoppingCart::addItem;
  public static final CommandRef<ShoppingCart, String, Done> removeItem = ShoppingCart::removeItem;
  public static final NoArgRef<ShoppingCart, CartState> getCart = ShoppingCart::getCart;
  public static final NoArgRef<ShoppingCart, Done> checkout = ShoppingCart::checkout;
  public static final CommandRef<Checkout, Checkout.Start, Done> startCheckout = Checkout::start;
  public static final NoArgRef<Checkout, Done> confirmCheckout = Checkout::confirm;
  public static final NoArgRef<Checkout, Checkout.State> getCheckout = Checkout::get;
  public static final ActionRef<CartTimers, String> expireTimer = CartTimers::expire;
  public static final NoArgActionRef<CartTimers> tick = CartTimers::tick;
  public static final NoArgActionRef<CartTimers> flaky = CartTimers::flaky;
  public static final ActionRef<CartTimers, String> chain = CartTimers::chain;
  public static final NoArgActionRef<CartTimers> tickByLambda = action -> action.tick();
}
