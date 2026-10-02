package com.thinkmorestupidless.ankka.grpc

import io.grpc.{Grpc, InsecureChannelCredentials, ManagedChannel}

/**
 * Channels for calling a service on this machine, which is what a test does: start the service with
 * `GrpcServer.at("127.0.0.1", 0)`, read its `boundPort`, and call it through the stub generated
 * from the `.proto` file.
 *
 * {{{
 * val channel = GrpcChannels.plaintext(server.boundPort.get)
 * val cart    = CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("c1"))
 * }}}
 *
 * Plaintext because a service on a developer's machine serves no TLS. Shut the channel down when
 * the test is done with it.
 */
object GrpcChannels:

  def plaintext(port: Int): ManagedChannel =
    Grpc.newChannelBuilderForAddress("127.0.0.1", port, InsecureChannelCredentials.create()).build()
