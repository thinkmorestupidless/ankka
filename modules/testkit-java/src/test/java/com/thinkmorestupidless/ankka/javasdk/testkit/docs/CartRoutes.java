package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Checkout;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CheckoutRows;
import java.util.List;

/** Routes that start a checkout and list the checkouts a view holds. */
public class CartRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start view-client
  @Get("/checkouts")
  public List<CheckoutRows.Row> checkouts() {
    var rows = viewClient().forView(CheckoutRows.class);
    return rows.all();
  }
  // docs:end view-client

  // docs:start start-workflow
  /** The body is how long the checkout waits for a confirmation, in milliseconds. */
  @Post("/{cartId}/checkouts")
  public Done startCheckout(String cartId, @Body String confirmWithin) {
    // The request's context is already on this thread, so the call joins its trace.
    return componentClient()
        .forWorkflow(cartId)
        .call(Checkout::start)
        .invoke(new Checkout.Start(cartId, Long.parseLong(confirmWithin)));
  }
  // docs:end start-workflow
}
