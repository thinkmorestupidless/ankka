package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import io.grpc.stub.MetadataUtils
import io.grpc.{ClientInterceptors, Metadata, Status, StatusRuntimeException}

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** How a call's caller is established, and every way an ACL answers it. */
class AdmissionSuite extends munit.FunSuite:

  private def context(headers: Vector[(String, String)] = Vector.empty) =
    SimpleRequestContext(
      method = "POST",
      path = "/ankka.fixtures.v1.CartService/GetCart",
      query = QueryParams.empty,
      headers = headers,
      remoteAddress = None,
      caller = Caller.Local
    )

  private val admission = Admission.local

  private def code(result: Either[(Status, Metadata), RequestContext]): Status.Code =
    result.fold(_._1.getCode, _ => Status.Code.OK)

  test("deny all refuses, allow all admits") {
    assertEquals(code(admission.decide(Acl.DenyAll, context())), Status.Code.PERMISSION_DENIED)
    assertEquals(code(admission.decide(Acl.AllowAll, context())), Status.Code.OK)
  }

  test("a predicate sees the method as a path and the metadata as headers") {
    val acl = Acl.AllowIf(c =>
      c.path == "/ankka.fixtures.v1.CartService/GetCart" && c.header("tenant").contains("acme")
    )
    assertEquals(code(admission.decide(acl, context(Vector("tenant" -> "acme")))), Status.Code.OK)
    assertEquals(
      code(admission.decide(acl, context(Vector("tenant" -> "other")))),
      Status.Code.PERMISSION_DENIED
    )
  }

  test(
    "an authenticator's four answers are four outcomes, with a challenge for the one that asks"
  ) {
    def decided(answer: AuthDecision) = admission.decide(Acl.Authenticate(_ => answer), context())
    val allowed                       = decided(AuthDecision.Allow(Principal("alice")))
    assertEquals(allowed.toOption.flatMap(_.principal).map(_.subject), Some("alice"))
    val unauthenticated = decided(AuthDecision.Unauthenticated("realm=\"cart\""))
    assertEquals(code(unauthenticated), Status.Code.UNAUTHENTICATED)
    assertEquals(
      unauthenticated.left.toOption.map(_._2.get(Admission.Challenge)),
      Some("Bearer realm=\"cart\"")
    )
    val forbidden = decided(AuthDecision.Forbidden("not your cart"))
    assertEquals(code(forbidden), Status.Code.PERMISSION_DENIED)
    assertEquals(forbidden.left.toOption.map(_._1.getDescription), Some("not your cart"))
    assertEquals(code(decided(AuthDecision.Unavailable("no keys"))), Status.Code.UNAVAILABLE)
  }

  test("binary keys and the local caller's key are not among the headers an ACL sees") {
    val headers = Metadata()
    headers.put(Metadata.Key.of("tenant", Metadata.ASCII_STRING_MARSHALLER), "acme")
    headers.put(Metadata.Key.of("trace-bin", Metadata.BINARY_BYTE_MARSHALLER), Array[Byte](1, 2))
    headers.put(
      Metadata.Key.of(LocalCallers.Header.toLowerCase, Metadata.ASCII_STRING_MARSHALLER),
      "secret service:p/x"
    )
    assertEquals(CallMetadata.of(headers).toSeq, Vector("tenant" -> "acme"))
  }

  // ── over a real server ────────────────────────────────────────────────────────

  private val checked   = AtomicInteger()
  private val sawCaller = AtomicReference[Caller]()

  /** The authenticator an HTTP endpoint would have: a bearer token in `Authorization`. */
  private val bearer = Acl.Authenticate { c =>
    checked.incrementAndGet()
    c.header("Authorization") match
      case Some("Bearer good") => AuthDecision.Allow(Principal("alice"))
      case _                   => AuthDecision.Unauthenticated("realm=\"cart\"")
  }

  private final class Guarded(guard: Acl) extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = guard
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      sawCaller.set(caller)
      Cart(request.cartId)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private def serving[A](server: GrpcServer, guard: Acl)(body: Int => A): A =
    server.serve(Vector(Guarded(guard)), "127.0.0.1", 0, ConfigFactory.load())
    try body(server.boundPort.getOrElse(fail("not bound")))
    finally server.stop()

  test(
    "an authenticator written for an HTTP endpoint admits a call carrying authorization metadata"
  ) {
    serving(GrpcServer.at("127.0.0.1", 0)(), bearer) { port =>
      val channel = GrpcChannels.plaintext(port)
      try
        val headers = Metadata()
        headers.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
          "Bearer good"
        )
        val authorised =
          ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
        assertEquals(
          CartServiceGrpc.blockingStub(authorised).getCart(GetCartRequest("c1")).cartId,
          "c1"
        )
        val refused = intercept[StatusRuntimeException](
          CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("c1"))
        )
        assertEquals(refused.getStatus.getCode, Status.Code.UNAUTHENTICATED)
        assertEquals(
          Option(refused.getTrailers).map(_.get(Admission.Challenge)),
          Some("Bearer realm=\"cart\"")
        )
      finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    }
  }

  private val root = TestPki.root("admission-suite")

  private def directory(uri: String, dnsNames: Seq[String] = Nil) =
    root.issue(uris = Seq(uri), dnsNames = dnsNames).writeTo(Files.createTempDirectory("admission"))

  test("under TLS a client with no certificate never reaches the acl") {
    checked.set(0)
    val server =
      GrpcServer.at("127.0.0.1", 0)().withTls(directory("ankka://shop/cart", Seq("localhost")))
    serving(server, bearer) { port =>
      val trustOnly = io.grpc.TlsChannelCredentials
        .newBuilder()
        .trustManager(RotatingTls(directory("ankka://shop/other"), 1.minute).trustManager)
        .build()
      val channel = io.grpc.Grpc
        .newChannelBuilderForAddress("127.0.0.1", port, trustOnly)
        .overrideAuthority("localhost")
        .build()
      try
        val failed = Try(
          CartServiceGrpc
            .blockingStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .getCart(GetCartRequest("c1"))
        )
        assert(failed.isFailure, failed)
        assertEquals(checked.get, 0)
      finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    }
  }

  test("under TLS the caller is the certificate's, whatever the local caller's key says") {
    val server =
      GrpcServer.at("127.0.0.1", 0)().withTls(directory("ankka://shop/cart", Seq("localhost")))
    serving(server, Acl.AllowAll) { port =>
      val channel = GrpcChannels.tls(
        port,
        "localhost",
        RotatingTls(directory("ankka://shop/checkout"), 1.minute),
        "ankka://shop/cart"
      )
      try
        val (name, value) = LocalCallers.header(Caller.Service("shop", "billing"))
        val headers       = Metadata()
        headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
        val claiming =
          ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
        val _ = CartServiceGrpc.blockingStub(claiming).getCart(GetCartRequest("c1"))
        assertEquals(sawCaller.get, Caller.Service("shop", "checkout"))
      finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    }
  }

  test("outside TLS the local caller's key, with this process's token, names the caller") {
    serving(GrpcServer.at("127.0.0.1", 0)(), Acl.AllowAll) { port =>
      val channel = GrpcChannels.plaintext(port, Caller.Service("demo", "checkout"))
      try
        val _ = CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("c1"))
        assertEquals(sawCaller.get, Caller.Service("demo", "checkout"))
      finally channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    }
  }
