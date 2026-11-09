package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.grpc.GrpcEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import java.util.concurrent.SubmissionPublisher;

// docs:start grpc-endpoint
/**
 * The cart's gRPC API: the service definition in {@link CartApi}, implemented over the same entity
 * the HTTP endpoint calls.
 *
 * <p>Like the HTTP endpoint it holds no state and maps no errors: a refusal from the entity
 * reaches the caller as the matching gRPC status. What it does do is translate, between the
 * messages of the API and the cart's own types: the API is a contract with callers, and the
 * domain is free to change behind it.
 */
public class CartGrpc extends GrpcEndpoint {

  public CartGrpc() {
    super(CartApi.SERVICE);

    unary(CartApi.GET_CART, request -> cart(request.cartId()).call(ShoppingCart::getCart).invoke());

    unary(
        CartApi.ADD_ITEM,
        request -> {
          cart(request.cartId()).call(ShoppingCart::addItem).invoke(request.item());
          return cart(request.cartId()).call(ShoppingCart::getCart).invoke();
        });

    // docs:start server-stream
    // The cart's lines, one part each. The publisher is fed once the caller has subscribed, and
    // closing it ends the call; a publisher that fails ends the call with the failure's status.
    serverStream(
        CartApi.LIST_ITEMS,
        request -> {
          var items = cart(request.cartId()).call(ShoppingCart::getCart).invoke().items();
          var parts = new SubmissionPublisher<LineItem>();
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (parts.getNumberOfSubscribers() == 0) Thread.onSpinWait();
                    items.forEach(parts::submit);
                    parts.close();
                  });
          return parts;
        });
    // docs:end server-stream

    // docs:start client-stream
    // Each item is added as it arrives, and the caller is answered once the stream ends. A
    // refusal, a checked-out cart, ends the call at once, with the items before it added.
    clientStream(
        CartApi.IMPORT_ITEMS,
        requests -> {
          int added = 0;
          while (requests.hasNext()) {
            var request = requests.next();
            cart(request.cartId()).call(ShoppingCart::addItem).invoke(request.item());
            added++;
          }
          return new CartApi.ImportSummary(added);
        });
    // docs:end client-stream

    // docs:start bidi-stream
    // A conversation: each line is answered as it arrives, and the answers end when the lines do.
    bidiStream(
        CartApi.CONVERSE,
        lines -> {
          var answers = new SubmissionPublisher<CartApi.Line>();
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (answers.getNumberOfSubscribers() == 0) Thread.onSpinWait();
                    lines.forEachRemaining(
                        line -> answers.submit(new CartApi.Line("heard: " + line.text())));
                    answers.close();
                  });
          return answers;
        });
    // docs:end bidi-stream
  }

  /** Any service of this project, and the internet through the gateway when the cart is exposed. */
  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.anyInProject(), Callers.internet());
  }

  private EntityRef cart(String cartId) {
    return componentClient().forEventSourcedEntity(cartId);
  }
}
// docs:end grpc-endpoint
