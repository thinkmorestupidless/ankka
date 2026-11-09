package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.grpc.GrpcClients;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import io.grpc.CallOptions;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCalls;

/** A gRPC service's registration, its test, and a call to it from another service. */
public final class GrpcChecks {

  private GrpcChecks() {}

  // docs:start grpc-registration
  /** The cart service serves gRPC: its endpoint is registered beside its components. */
  public static ServiceBuilder cartService() {
    return Ankka.service("cart").register(ShoppingCart.class).withGrpcEndpoint(CartGrpc.class);
  }
  // docs:end grpc-registration

  // docs:start grpc-test
  /** Starts the whole service, with gRPC on a port the system picks, and calls it as a client. */
  public static CartState addThenRead() {
    try (var kit = AnkkaTestKit.start(cartService())) {
      var channel = ManagedChannelBuilder.forTarget(kit.grpcAddress()).usePlaintext().build();
      try {
        var item = new LineItem("p1", "Widget", 2);
        ClientCalls.blockingUnaryCall(
            channel, CartApi.ADD_ITEM, CallOptions.DEFAULT, new CartApi.AddItemRequest("g-1", item));
        return ClientCalls.blockingUnaryCall(
            channel, CartApi.GET_CART, CallOptions.DEFAULT, new CartApi.GetCartRequest("g-1"));
      } finally {
        channel.shutdownNow();
      }
    }
  }
  // docs:end grpc-test

  // docs:start grpc-clients
  /** A service that calls other services' gRPC asks for the clients when it is built. */
  public static ServiceBuilder ordersService() {
    return Ankka.service("orders").withGrpcClients();
  }
  // docs:end grpc-clients

  // docs:start call-another-service-grpc
  /**
   * Asks the cart service of this project for a cart: {@code channel("cart")} is a channel to it,
   * and {@code channel("shop", "cart")} to the one in project {@code shop}. A stub generated from
   * the service's {@code .proto} file is built on the channel the same way.
   */
  public static CartState readCart(GrpcClients clients, String cartId) {
    return ClientCalls.blockingUnaryCall(
        clients.channel("cart"),
        CartApi.GET_CART,
        CallOptions.DEFAULT,
        new CartApi.GetCartRequest(cartId));
  }
  // docs:end call-another-service-grpc
}
