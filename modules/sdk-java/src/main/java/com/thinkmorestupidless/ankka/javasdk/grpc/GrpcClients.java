package com.thinkmorestupidless.ankka.javasdk.grpc;

import io.grpc.Channel;

/**
 * Channels to other services' gRPC, as this service: mutual TLS on the platform, and on a
 * developer's machine the address {@code ankka.local-grpc-services.<name>} names. Hand a channel to
 * a protoc-generated stub: {@code CartServiceGrpc.newBlockingStub(grpc.channel("carts"))}.
 */
public interface GrpcClients {

  /** The named service of this project. */
  Channel channel(String service);

  /** The named service of another project, which must grant this one the call. */
  Channel channel(String project, String service);
}
