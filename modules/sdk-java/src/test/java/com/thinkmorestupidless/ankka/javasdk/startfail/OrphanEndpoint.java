package com.thinkmorestupidless.ankka.javasdk.startfail;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.util.concurrent.atomic.AtomicInteger;

/** Calls the cart, which the service under test does not register. Counts what it is asked. */
public class OrphanEndpoint extends HttpEndpoint {

  public static final AtomicInteger requests = new AtomicInteger();

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/carts/{id}/items")
  public Done addItem(String id, @Body LineItem item) {
    requests.incrementAndGet();
    return componentClient().forEventSourcedEntity(id).call(ShoppingCart::addItem).invoke(item);
  }
}
