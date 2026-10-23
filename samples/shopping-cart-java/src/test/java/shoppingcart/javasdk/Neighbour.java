package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClient;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** The Java cart's calls to the Scala cart, running beside it as the service "scala-cart". */
public class Neighbour extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private ServiceClient scalaCart() {
    return services().of("scala-cart");
  }

  /** The Scala cart's entity, read into the Java cart's record. */
  @Get("/neighbour/carts/{cartId}")
  public Cart cart(String cartId) {
    return scalaCart().get("/carts/" + cartId, Cart.class);
  }

  @Post("/neighbour/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    scalaCart().post("/carts/" + cartId + "/items", item, Void.class);
    return Done.done();
  }

  /** Who the Scala cart saw calling. */
  @Get("/neighbour/whoami")
  public String whoami() {
    return scalaCart().getText("/callers/whoami");
  }
}
