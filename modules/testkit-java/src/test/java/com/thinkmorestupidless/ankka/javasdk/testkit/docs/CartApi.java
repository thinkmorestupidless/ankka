package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The cart's gRPC API as protoc would generate it: the messages, and the descriptors the endpoint
 * is declared against and a caller's stub is built on. Built by hand here, with the SDK's record
 * codec as the marshaller, because this build runs no protoc for its Java tests; a service keeps
 * its {@code .proto} file and lets protoc generate this class.
 */
public final class CartApi {

  private CartApi() {}

  @Manifest("get-cart-request")
  public record GetCartRequest(String cartId) {}

  @Manifest("add-item-request")
  public record AddItemRequest(String cartId, LineItem item) {}

  @Manifest("import-summary")
  public record ImportSummary(int added) {}

  @Manifest("line")
  public record Line(String text) {}

  private static <A> MethodDescriptor.Marshaller<A> json(Class<A> type) {
    var codec = Serializer.of(type);
    return new MethodDescriptor.Marshaller<>() {
      @Override
      public InputStream stream(A value) {
        return new ByteArrayInputStream(codec.toBytes(value));
      }

      @Override
      public A parse(InputStream stream) {
        try {
          return codec.fromBytes(stream.readAllBytes());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    };
  }

  private static <Q, R> MethodDescriptor<Q, R> method(
      String name, MethodDescriptor.MethodType type, Class<Q> request, Class<R> response) {
    return MethodDescriptor.<Q, R>newBuilder()
        .setType(type)
        .setFullMethodName(MethodDescriptor.generateFullMethodName("shoppingcart.v1.Cart", name))
        .setRequestMarshaller(json(request))
        .setResponseMarshaller(json(response))
        .build();
  }

  public static final MethodDescriptor<GetCartRequest, CartState> GET_CART =
      method("GetCart", MethodDescriptor.MethodType.UNARY, GetCartRequest.class, CartState.class);

  public static final MethodDescriptor<AddItemRequest, CartState> ADD_ITEM =
      method("AddItem", MethodDescriptor.MethodType.UNARY, AddItemRequest.class, CartState.class);

  public static final MethodDescriptor<GetCartRequest, LineItem> LIST_ITEMS =
      method(
          "ListItems",
          MethodDescriptor.MethodType.SERVER_STREAMING,
          GetCartRequest.class,
          LineItem.class);

  public static final MethodDescriptor<AddItemRequest, ImportSummary> IMPORT_ITEMS =
      method(
          "ImportItems",
          MethodDescriptor.MethodType.CLIENT_STREAMING,
          AddItemRequest.class,
          ImportSummary.class);

  public static final MethodDescriptor<Line, Line> CONVERSE =
      method("Converse", MethodDescriptor.MethodType.BIDI_STREAMING, Line.class, Line.class);

  public static final ServiceDescriptor SERVICE =
      ServiceDescriptor.newBuilder("shoppingcart.v1.Cart")
          .addMethod(GET_CART)
          .addMethod(ADD_ITEM)
          .addMethod(LIST_ITEMS)
          .addMethod(IMPORT_ITEMS)
          .addMethod(CONVERSE)
          .build();
}
