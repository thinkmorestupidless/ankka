package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.{AddItemRequest, Cart, CartServiceGrpc, GetCartRequest}
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import io.grpc.*

import java.net.Socket
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SSLEngine, X509ExtendedTrustManager}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Whether grpc-java, as `ankka-grpc` uses it, serves mutual TLS from `RotatingTls`'s managers on
 * the provider grpc-netty-shaded picks for itself — research R4's one unverified assumption.
 *
 * The question is whether a custom `X509ExtendedKeyManager` and `X509ExtendedTrustManager`, handed
 * to `TlsServerCredentials`, survive the trip into shaded Netty's SSL engine: that a handshake
 * requires the client's certificate, that the peer's certificate is readable from the session, and
 * that a certificate rotated on disk is presented on the next connection with no restart.
 *
 * {{{
 * sbt -Dankka.spikes=on 'grpc/testOnly *GrpcTlsSpike'
 * }}}
 */
class GrpcTlsSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private val root = TestPki.root("grpc-tls-spike")

  private def dir(): Path = Files.createTempDirectory("grpc-tls-spike")

  private val PeerCaller: Context.Key[String] = Context.key("peer-caller")

  /** Reads the caller from the session's peer certificate, as `Admission` will. */
  private object ReadCaller extends ServerInterceptor:
    def interceptCall[Q, R](
        call: ServerCall[Q, R],
        headers: Metadata,
        next: ServerCallHandler[Q, R]
    ): ServerCall.Listener[Q] =
      val caller = Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
        .flatMap(session => Try(session.getPeerCertificates.head).toOption)
        .collect { case c: X509Certificate => c }
        .map(c => Caller.fromCertificate(c, None).fold(identity, Caller.encode))
        .getOrElse("none")
      Contexts.interceptCall(Context.current.withValue(PeerCaller, caller), call, headers, next)

  private object Impl extends CartServiceGrpc.CartService:
    def getCart(request: GetCartRequest): Future[Cart] =
      Future.successful(Cart(cartId = request.cartId, caller = PeerCaller.get()))
    def addItem(request: AddItemRequest): Future[Cart] =
      Future.successful(Cart(cartId = request.cartId))

  private def serve(tls: RotatingTls): Server =
    val credentials = TlsServerCredentials
      .newBuilder()
      .keyManager(tls.keyManager)
      .trustManager(tls.trustManager)
      .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
      .build()
    Grpc
      .newServerBuilderForPort(0, credentials)
      .addService(
        ServerInterceptors.intercept(
          CartServiceGrpc.bindService(Impl, ExecutionContext.global),
          ReadCaller
        )
      )
      .build()
      .start()

  /** Remembers the certificate each handshake was shown, so a test can see which one was served. */
  private final class Recording(delegate: X509ExtendedTrustManager)
      extends X509ExtendedTrustManager:
    val seen                                         = AtomicReference[X509Certificate]()
    private def saw(c: Array[X509Certificate]): Unit = seen.set(c.head)
    override def checkClientTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkClientTrusted(c, a)
    override def checkServerTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkServerTrusted(c, a); saw(c)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkClientTrusted(c, a, s)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkServerTrusted(c, a, s); saw(c)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkClientTrusted(c, a, e)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkServerTrusted(c, a, e); saw(c)
    override def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

  private def channel(port: Int, client: Option[RotatingTls], trust: X509ExtendedTrustManager) =
    val builder = TlsChannelCredentials.newBuilder().trustManager(trust)
    client.foreach(c => builder.keyManager(c.keyManager): Unit)
    Grpc.newChannelBuilderForAddress("localhost", port, builder.build()).build()

  private def call(channel: ManagedChannel): Either[Throwable, Cart] =
    try
      Try(
        CartServiceGrpc
          .blockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCart(GetCartRequest("c1"))
      ).toEither
    finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit

  private def serverTls(interval: FiniteDuration = 1.minute, directory: Path = dir()) =
    RotatingTls(
      root.issue(uris = Seq("ankka://p/cart"), dnsNames = Seq("localhost")).writeTo(directory),
      interval
    )
  private def clientTls() =
    RotatingTls(root.issue(uris = Seq("ankka://p/checkout")).writeTo(dir()), 1.minute)

  test("a client with the service's authority and a certificate is answered") {
    val server = serve(serverTls())
    try
      val client = clientTls()
      assertEquals(
        call(channel(server.getPort, Some(client), client.trustManager)).map(_.cartId),
        Right("c1")
      )
    finally server.shutdownNow(): Unit
  }

  test("a client that presents no certificate is refused") {
    val server = serve(serverTls())
    try
      val client = clientTls()
      val result = call(channel(server.getPort, None, client.trustManager))
      assert(result.isLeft, result)
      assertEquals(
        result.left.toOption.collect { case e: StatusRuntimeException => e.getStatus.getCode },
        Some(Status.Code.UNAVAILABLE)
      )
    finally server.shutdownNow(): Unit
  }

  test("the caller is read from the session's peer certificate") {
    val server = serve(serverTls())
    try
      val client = clientTls()
      assertEquals(
        call(channel(server.getPort, Some(client), client.trustManager)).map(_.caller),
        Right("service:p/checkout")
      )
    finally server.shutdownNow(): Unit
  }

  test("a certificate rotated on disk is presented on the next connection, with no restart") {
    val directory = dir()
    val tls       = serverTls(50.millis, directory)
    val server    = serve(tls)
    try
      val client = clientTls()
      val first  = Recording(client.trustManager)
      assert(call(channel(server.getPort, Some(client), first)).isRight)
      val renewed = root.issue(uris = Seq("ankka://p/cart"), dnsNames = Seq("localhost"))
      renewed.writeTo(directory)
      val later = FileTime.from(Instant.now().plusSeconds(5))
      Seq("tls.key", "tls.crt", "ca.crt").foreach(n =>
        Files.setLastModifiedTime(directory.resolve(n), later): Unit
      )
      Thread.sleep(100)
      val second = Recording(client.trustManager)
      assert(call(channel(server.getPort, Some(client), second)).isRight)
      assertNotEquals(first.seen.get.getSerialNumber, renewed.serial)
      assertEquals(second.seen.get.getSerialNumber, renewed.serial)
    finally server.shutdownNow(): Unit
  }
