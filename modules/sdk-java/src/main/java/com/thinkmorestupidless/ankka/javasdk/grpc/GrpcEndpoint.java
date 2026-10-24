package com.thinkmorestupidless.ankka.javasdk.grpc;

import com.thinkmorestupidless.ankka.javasdk.bridge.GrpcCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.client.ViewClient;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * A gRPC service, served on the service's gRPC port. Its methods are declared in the constructor,
 * each against the method descriptor protoc generated for it ({@code
 * CartServiceGrpc.getGetCartMethod()}), so a method the {@code .proto} does not have cannot be
 * served:
 *
 * <pre>{@code
 * public CartGrpc() {
 *   super(CartServiceGrpc.getServiceDescriptor());
 *   unary(CartServiceGrpc.getGetCartMethod(), request -> ...);
 * }
 * }</pre>
 *
 * A handler that throws a {@code CommandException} or a {@code ProblemException} answers the
 * status its code maps to, as a Scala endpoint's refusal does; a {@code StatusRuntimeException}
 * answers its own status; anything else is {@code INTERNAL}, and the caller is told nothing more.
 */
public abstract class GrpcEndpoint {

  private final GrpcCell cell = GrpcCell.take();

  protected GrpcEndpoint(ServiceDescriptor service) {
    cell.bind(service);
  }

  /** Who may call the service's methods, unless {@link #withAcl} says otherwise for some. */
  protected abstract Acl acl();

  protected final <Q, R> void unary(MethodDescriptor<Q, R> method, Function<Q, R> handler) {
    cell.unary(method, handler);
  }

  /** One request, answered with a stream of responses as they are published. */
  protected final <Q, R> void serverStream(
      MethodDescriptor<Q, R> method, Function<Q, Flow.Publisher<R>> handler) {
    cell.serverStream(method, handler);
  }

  /** A stream of requests, read as they arrive, answered once. */
  protected final <Q, R> void clientStream(
      MethodDescriptor<Q, R> method, Function<Iterator<Q>, R> handler) {
    cell.clientStream(method, handler);
  }

  protected final <Q, R> void bidiStream(
      MethodDescriptor<Q, R> method, Function<Iterator<Q>, Flow.Publisher<R>> handler) {
    cell.bidiStream(method, handler);
  }

  /** Declares the methods {@code declare} declares under {@code acl}. */
  protected final void withAcl(Acl acl, Runnable declare) {
    cell.withAcl(acl, declare);
  }

  /** Who made the call in hand; only inside a handler, on its own thread. */
  protected final Caller caller() {
    return cell.caller();
  }

  /** A metadata value of the call in hand. */
  protected final Optional<String> metadata(String key) {
    return cell.metadata(key);
  }

  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  protected final ViewClient viewClient() {
    return cell.viewClient();
  }

  protected final ServiceClients services() {
    return cell.services();
  }

  protected final SecretStore secrets() {
    return cell.secrets();
  }

  protected final ObjectStorage storage() {
    return cell.storage();
  }
}
