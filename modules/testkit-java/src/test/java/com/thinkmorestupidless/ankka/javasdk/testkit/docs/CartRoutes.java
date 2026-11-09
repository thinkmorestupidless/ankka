package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Checkout;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CheckoutRows;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

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

  // docs:start problem
  @Get("/{cartId}/checkout-row")
  public CheckoutRows.Row checkoutRow(String cartId) {
    return viewClient()
        .forView(CheckoutRows.class)
        .get(cartId)
        .orElseThrow(
            () -> new ProblemException(Problem.notFound("no row for cart '" + cartId + "'")));
  }
  // docs:end problem

  // docs:start socket
  private static final Serializer<CartState> CART = Serializer.of(CartState.class);

  // A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
  // open. The handler is ordinary blocking code on a virtual thread; receive() answers empty once
  // the socket is closed, which ends the loop and the handler.
  @Socket("/{cartId}/watch")
  public void watch(String cartId, WebSocket socket) {
    Optional<String> frame;
    while ((frame = socket.receive()).isPresent()) {
      if (frame.get().equals("refresh")) {
        var cart =
            componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
        socket.send(new String(CART.toBytes(cart), StandardCharsets.UTF_8));
      } else {
        socket.send("{\"error\":\"unknown request '" + frame.get() + "'; send refresh\"}");
      }
    }
  }
  // docs:end socket
}
