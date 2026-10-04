package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import io.grpc.stub.MetadataUtils
import io.grpc.{Grpc, InsecureChannelCredentials, ManagedChannel, Metadata, TlsChannelCredentials}

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

  /**
   * The same, with every call made as `caller`: the gRPC form of `LocalCallers.header`. It works
   * only outside a cluster, where the service reads the caller from this process's token; under TLS
   * the certificate decides and this is not read.
   */
  def plaintext(port: Int, caller: Caller): ManagedChannel =
    val (name, value) = LocalCallers.header(caller)
    val headers       = Metadata()
    headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
    Grpc
      .newChannelBuilderForAddress("127.0.0.1", port, InsecureChannelCredentials.create())
      .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
      .build()

  /**
   * A mutual-TLS channel presenting `tls`'s identity to a server on this machine that must be
   * `expecting` — for suites that read a caller from a real certificate without a cluster.
   */
  private[ankka] def tls(
      port: Int,
      authority: String,
      tls: RotatingTls,
      expecting: String
  ): ManagedChannel =
    val credentials = TlsChannelCredentials
      .newBuilder()
      .keyManager(tls.keyManager)
      .trustManager(tls.trustManagerRequiring(expecting))
      .build()
    Grpc
      .newChannelBuilderForAddress("127.0.0.1", port, credentials)
      .overrideAuthority(authority)
      .build()
