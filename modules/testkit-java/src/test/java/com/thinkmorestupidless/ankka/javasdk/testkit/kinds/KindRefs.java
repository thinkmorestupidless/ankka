package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;

/** The method references the Scala suites call through: a Scala function is not one. */
public final class KindRefs {
  private KindRefs() {}

  public static final CommandRef<TreeNode, TreeNode.Placed, Done> place = TreeNode::place;
  public static final NoArgRef<TreeNode, Done> remove = TreeNode::remove;
  public static final CommandRef<ShoppingCart, LineItem, Done> addItem = ShoppingCart::addItem;
  public static final NoArgRef<ShoppingCart, Done> checkout = ShoppingCart::checkout;
}
