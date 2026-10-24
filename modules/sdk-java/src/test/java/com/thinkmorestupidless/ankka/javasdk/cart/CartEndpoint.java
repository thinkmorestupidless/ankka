package com.thinkmorestupidless.ankka.javasdk.cart;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

public class CartEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/carts/{id}/items")
  public Done addItem(String id, @Body LineItem item) {
    return cart(id).call(ShoppingCart::addItem).invoke(item);
  }

  @Delete("/carts/{id}/items/{productId}")
  public Done removeItem(String id, String productId) {
    return cart(id).call(ShoppingCart::removeItem).invoke(productId);
  }

  @Post("/carts/{id}/checkout")
  public Done checkout(String id) {
    return cart(id).call(ShoppingCart::checkout).invoke();
  }

  @Get("/carts/{id}")
  public CartState get(String id) {
    return cart(id).call(ShoppingCart::getCart).invoke();
  }

  @Post("/carts/{id}/fail/{kind}")
  public Done fail(String id, String kind) {
    return cart(id).call(ShoppingCart::fail).invoke(kind);
  }

  private EntityRef cart(String id) {
    return componentClient().forEventSourcedEntity(id);
  }
}
