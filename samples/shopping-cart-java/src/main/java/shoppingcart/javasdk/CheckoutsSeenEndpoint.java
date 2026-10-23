package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;

/** The checkouts this service has read back from the topic, by cart id. */
public class CheckoutsSeenEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/checkouts-seen/{cartId}")
  public CheckoutsSeen.CheckoutSeen get(String cartId) {
    return viewClient()
        .forView(CheckoutsSeen.class)
        .get(cartId)
        .orElseThrow(
            () ->
                new ProblemException(
                    Problem.notFound("no checkout of '" + cartId + "' has been read")));
  }
}
