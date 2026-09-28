package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The HTTP server in the Kubernetes overlay's shape: mutual TLS with the service certificate, the
 * caller read from the client's certificate, and rotation picked up by new connections.
 */
class TlsServerSuite extends munit.FunSuite:

  private val authority = TestPki.root("tls-server-suite")
  private val serverDir: Path =
    authority
      .issue(uris = Seq("ankka://checkout/carts"), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("tls-server"))

  private given system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "tls-server-suite",
    ConfigFactory
      .parseString(s"""
        |ankka.http.tls.enabled = on
        |ankka.tls.service-directory = "$serverDir"
        |ankka.tls.reload-interval = 200ms
        |""".stripMargin)
      .withFallback(ConfigFactory.load())
  )

  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))
    get("/whoami")(() => Caller.encode(caller))

  private val server = HttpServer.at("127.0.0.1", 0)()
  server.serve(Vector(new Carts), "127.0.0.1", 0, 5.seconds)
  private val port = server.boundPort.get

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def client(leaf: Option[TestPki.Leaf]): HttpClient =
    val context = leaf match
      case Some(l) =>
        RotatingTls(l.writeTo(Files.createTempDirectory("tls-client")), 1.minute).sslContext
      case None =>
        val trust = java.security.KeyStore.getInstance("PKCS12")
        trust.load(null, null)
        trust.setCertificateEntry("ca", authority.certificate)
        val tmf = javax.net.ssl.TrustManagerFactory
          .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
        tmf.init(trust)
        val bare = SSLContext.getInstance("TLS")
        bare.init(null, tmf.getTrustManagers, null)
        bare
    HttpClient.newBuilder().sslContext(context).build()

  private def fetch(
      leaf: Option[TestPki.Leaf],
      path: String = "/carts/whoami"
  ): Either[Throwable, HttpResponse[String]] =
    Try(
      client(leaf).send(
        HttpRequest
          .newBuilder(URI(s"https://localhost:$port$path"))
          .timeout(java.time.Duration.ofSeconds(5))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    ).toEither

  test("a client presenting a service certificate is served, and named by it") {
    val response = fetch(Some(authority.issue(uris = Seq("ankka://checkout/orders")))).toTry.get
    assertEquals(response.statusCode, 200)
    assertEquals(response.body, "service:checkout/orders")
  }

  test("the gateway's certificate reads as the internet") {
    val response = fetch(Some(authority.issue(uris = Seq(RotatingTls.GatewayUri)))).toTry.get
    assertEquals((response.statusCode, response.body), (200, "gateway"))
  }

  test("a service the ACL does not name is refused with 403") {
    assertEquals(
      fetch(Some(authority.issue(uris = Seq("ankka://checkout/payments")))).map(_.statusCode),
      Right(403)
    )
  }

  test("no client certificate, or one from another authority, never reaches the router") {
    assert(fetch(None).isLeft, "a connection without a client certificate was served")
    val foreign = TestPki.root("elsewhere").issue(uris = Seq("ankka://checkout/orders"))
    assert(fetch(Some(foreign)).isLeft, "a certificate from another authority was served")
  }

  test("a certificate from the authority that names no ankka caller is refused, not guessed at") {
    val response = fetch(Some(authority.issue(dnsNames = Seq("orders.svc")))).toTry.get
    assertEquals(response.statusCode, 403)
    assert(response.body.contains("unrecognised caller certificate"), response.body)
  }

  test("a renewed server certificate is presented to new connections without a restart") {
    def served: java.math.BigInteger =
      fetch(Some(authority.issue(uris = Seq("ankka://checkout/orders")))).toTry.get
        .sslSession()
        .get()
        .getPeerCertificates
        .head
        .asInstanceOf[X509Certificate]
        .getSerialNumber
    val before  = served
    val renewed = authority.issue(uris = Seq("ankka://checkout/carts"), dnsNames = Seq("localhost"))
    renewed.writeTo(serverDir)
    val later = FileTime.from(Instant.now().plusSeconds(5))
    Seq("tls.key", "tls.crt", "ca.crt").foreach(n =>
      Files.setLastModifiedTime(serverDir.resolve(n), later): Unit
    )
    Thread.sleep(400)
    val after = served
    assertNotEquals(after, before)
    assertEquals(after, renewed.serial)
  }
