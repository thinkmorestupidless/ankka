package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.client.CommandException;
import com.thinkmorestupidless.ankka.javasdk.grpc.GrpcEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;

/**
 * A gRPC service whose descriptors are built by hand with a text marshaller: the same {@code
 * MethodDescriptor}s protoc generates, without the generator.
 */
public class Greeter extends GrpcEndpoint {

  static final MethodDescriptor.Marshaller<String> TEXT =
      new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
          return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
          try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      };

  private static MethodDescriptor<String, String> method(
      String name, MethodDescriptor.MethodType type) {
    return MethodDescriptor.<String, String>newBuilder()
        .setType(type)
        .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Greeter", name))
        .setRequestMarshaller(TEXT)
        .setResponseMarshaller(TEXT)
        .build();
  }

  public static final MethodDescriptor<String, String> HELLO =
      method("Hello", MethodDescriptor.MethodType.UNARY);
  public static final MethodDescriptor<String, String> COUNT =
      method("Count", MethodDescriptor.MethodType.SERVER_STREAMING);
  public static final MethodDescriptor<String, String> JOIN =
      method("Join", MethodDescriptor.MethodType.CLIENT_STREAMING);
  public static final MethodDescriptor<String, String> SHOUT =
      method("Shout", MethodDescriptor.MethodType.BIDI_STREAMING);
  public static final MethodDescriptor<String, String> FIND =
      method("Find", MethodDescriptor.MethodType.UNARY);

  public static final ServiceDescriptor SERVICE =
      ServiceDescriptor.newBuilder("test.Greeter")
          .addMethod(HELLO)
          .addMethod(COUNT)
          .addMethod(JOIN)
          .addMethod(SHOUT)
          .addMethod(FIND)
          .build();

  public Greeter() {
    super(SERVICE);
    unary(HELLO, name -> "hello " + name + ", from " + metadata("x-greeting").orElse("nobody"));
    serverStream(
        COUNT,
        n -> {
          var publisher = new SubmissionPublisher<String>();
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (publisher.getNumberOfSubscribers() == 0) Thread.onSpinWait();
                    for (int i = 1; i <= Integer.parseInt(n); i++) publisher.submit("part " + i);
                    publisher.close();
                  });
          return publisher;
        });
    clientStream(
        JOIN,
        requests -> {
          List<String> all = new ArrayList<>();
          requests.forEachRemaining(all::add);
          return String.join("+", all);
        });
    bidiStream(
        SHOUT,
        requests -> {
          var publisher = new SubmissionPublisher<String>();
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (publisher.getNumberOfSubscribers() == 0) Thread.onSpinWait();
                    requests.forEachRemaining(r -> publisher.submit(r.toUpperCase()));
                    publisher.close();
                  });
          return publisher;
        });
    unary(
        FIND,
        name -> {
          throw new CommandException(Problem.notFound("no greeting for " + name));
        });
  }

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }
}
