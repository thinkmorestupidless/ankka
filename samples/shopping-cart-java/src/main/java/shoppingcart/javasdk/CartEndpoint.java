package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Answers;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.BodyShape;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.ReadsQuery;
import com.thinkmorestupidless.ankka.javasdk.annotations.Refuses;
import com.thinkmorestupidless.ankka.javasdk.annotations.Summary;
import com.thinkmorestupidless.ankka.javasdk.annotations.Tag;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.Response;

// docs:start endpoint
/**
 * The cart over HTTP, route for route as the Scala cart serves it. Every route carries the route
 * tag {@code cart} into the service's OpenAPI document.
 */
@Tag("cart")
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

  // Every write the entity refuses once the cart is checked out, for the document.
  @Post("/carts/{cartId}/items")
  @Refuses(status = 409, reason = "the cart is already checked out")
  public Done addItem(String cartId, @Body LineItem item) {
    return cart(cartId).call(ShoppingCart::addItem).invoke(item);
  }

  @Delete("/carts/{cartId}/items/{productId}")
  @Refuses(status = 409, reason = "the cart is already checked out")
  @Refuses(status = 404, reason = "the cart does not hold the product")
  public Done removeItem(String cartId, String productId) {
    return cart(cartId).call(ShoppingCart::removeItem).invoke(productId);
  }

  @Post("/carts/{cartId}/checkout")
  @Refuses(status = 409, reason = "the cart is already checked out")
  public Cart checkout(String cartId) {
    return cart(cartId).call(ShoppingCart::checkout).invoke();
  }

  @Delete("/carts/{cartId}")
  @Refuses(status = 409, reason = "the cart is already checked out")
  public Done discard(String cartId) {
    return cart(cartId).call(ShoppingCart::discard).invoke();
  }

  // A route whose types say little: a text note in, a Response out, two query parameters it reads
  // itself and a refusal of its own. Its annotations say the rest for the OpenAPI document.
  // docs:start described
  @Post("/carts/{cartId}/receipt")
  @Summary("A cart's receipt")
  @BodyShape(String.class)
  @Answers(shape = Receipt.class)
  @ReadsQuery(name = "currency")
  @ReadsQuery(name = "copies", type = Integer.class)
  @Refuses(status = 404, reason = "the cart is empty")
  public Response receipt(String cartId, @Body String note) {
    return receiptOf(cartId, note);
  }
  // docs:end described

  private Response receiptOf(String cartId, String note) {
    int items =
        cart(cartId).call(ShoppingCart::getCart).invoke().items().stream()
            .mapToInt(LineItem::quantity)
            .sum();
    if (items == 0) throw new ProblemException(Problem.notFound("the cart is empty"));
    int copies = request().query("copies").map(Integer::valueOf).orElse(1);
    String currency = request().query("currency").orElse("GBP");
    return Response.ok(new Receipt(cartId, note, items, currency, copies));
  }

  private EntityRef cart(String cartId) {
    return componentClient().forEventSourcedEntity(cartId);
  }
}
// docs:end endpoint
