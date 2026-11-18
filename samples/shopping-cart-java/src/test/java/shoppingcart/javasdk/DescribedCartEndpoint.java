package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.ReadsQuery;
import com.thinkmorestupidless.ankka.javasdk.annotations.Refuses;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** One route described by annotations, for features/openapi-documents/languages.feature. */
public class DescribedCartEndpoint extends HttpEndpoint {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/carts/{cartId}")
  @Refuses(status = 404, reason = "not found")
  @ReadsQuery(name = "currency")
  public String get(String cartId) {
    return cartId;
  }
}
