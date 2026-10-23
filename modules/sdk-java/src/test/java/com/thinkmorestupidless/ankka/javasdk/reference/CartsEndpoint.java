package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;

/** The cart's routes. */
public class CartsEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private EntityRef cart(String id) {
    return componentClient().forEventSourcedEntity(id);
  }

  @Post("/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    return cart(cartId).call(ShoppingCartEntity::addItem).invoke(item);
  }

  @Delete("/carts/{cartId}/items/{productId}")
  public Done removeItem(String cartId, String productId) {
    return cart(cartId).call(ShoppingCartEntity::removeItem).invoke(productId);
  }

  @Post("/carts/{cartId}/checkout")
  public ShoppingCart checkout(String cartId) {
    return cart(cartId).call(ShoppingCartEntity::checkout).invoke();
  }

  @Delete("/carts/{cartId}")
  public Done discard(String cartId) {
    return cart(cartId).call(ShoppingCartEntity::discard).invoke();
  }

  @Get("/carts/awkward")
  public String awkward() {
    return "literal";
  }

  @Get("/carts/{cartId}")
  public ShoppingCart get(String cartId) {
    return cart(cartId).call(ShoppingCartEntity::getCart).invoke();
  }

  @Get("/carts/{cartId}/rows")
  public CartRows.CartRow rows(String cartId) {
    return viewClient()
        .forView(CartRows.class)
        .get(cartId)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no row for '" + cartId + "'")));
  }
}
