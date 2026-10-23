package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

// docs:start endpoint
/** The cart over HTTP, route for route as the Scala cart serves it. */
public class CartEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/carts/{cartId}")
  public Cart get(String cartId) {
    return cart(cartId).call(ShoppingCart::getCart).invoke();
  }

  @Get("/carts/{cartId}/total")
  public Integer total(String cartId) {
    return cart(cartId).call(ShoppingCart::totalQuantity).invoke();
  }

  @Post("/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    return cart(cartId).call(ShoppingCart::addItem).invoke(item);
  }

  @Delete("/carts/{cartId}/items/{productId}")
  public Done removeItem(String cartId, String productId) {
    return cart(cartId).call(ShoppingCart::removeItem).invoke(productId);
  }

  @Post("/carts/{cartId}/checkout")
  public Cart checkout(String cartId) {
    return cart(cartId).call(ShoppingCart::checkout).invoke();
  }

  @Delete("/carts/{cartId}")
  public Done discard(String cartId) {
    return cart(cartId).call(ShoppingCart::discard).invoke();
  }

  private EntityRef cart(String cartId) {
    return componentClient().forEventSourcedEntity(cartId);
  }
}
// docs:end endpoint
