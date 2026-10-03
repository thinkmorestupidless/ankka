package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The observe listener on an ephemeral port, read as the control plane reads it: presenting a
 * certificate, by address, expecting exactly the service asked for.
 */
class ObserveServerSuite extends munit.FunSuite:

  private val root = TestPki.root("observe-server-suite")

  private def dir() = Files.createTempDirectory("observe")

  private val documents = new ObserveServer.Documents:
    def service(): String  = """{"name":"cart","instances":[]}"""
    def topology(): String = """{"service":{"name":"cart"},"nodes":[]}"""

  private var server: ObserveServer = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    val cart = root.issue(uris = Seq("ankka://checkout/cart")).writeTo(dir())
    server = ObserveServer.start(
      RotatingTls(cart, 1.minute, RotatingTls.Peers.Exactly(ObserveServer.ControlPlane)),
      0,
      documents
    )

  override def afterAll(): Unit = server.stop()

  /** A reader presenting `uri`, expecting the cart, reached by address as a pod is. */
  private def reading(uri: String, expecting: String = "ankka://checkout/cart"): HttpClient =
    val tls = RotatingTls(
      root.issue(uris = Seq(uri)).writeTo(dir()),
      1.minute,
      RotatingTls.Peers.Exactly(expecting)
    )
    HttpClient.newBuilder().sslContext(tls.sslContext).build()

  private def get(client: HttpClient, path: String): Try[HttpResponse[String]] =
    Try(
      client.send(
        HttpRequest.newBuilder(URI.create(s"https://127.0.0.1:${server.port}$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
    )

  test("the control plane's identity reads the service and its topology") {
    val controlplane = reading(ObserveServer.ControlPlane)
    val topology     = get(controlplane, "/observability/topology").get
    assertEquals(topology.statusCode(), 200)
    assertEquals(topology.body(), documents.topology())
    val service = get(controlplane, "/observability/service").get
    assertEquals(service.statusCode(), 200)
    assertEquals(service.body(), documents.service())
  }

  test("any other identity fails the handshake: the service's own, the gateway's, another's") {
    for uri <- Seq("ankka://checkout/cart", "ankka://gateway", "ankka://checkout/orders") do
      val attempt = get(reading(uri), "/observability/topology")
      assert(attempt.isFailure, s"$uri was admitted: $attempt")

    // Nothing presented at all: the authority is trusted, no certificate is offered.
    val bare = SSLContext.getInstance("TLS")
    val ks   = java.security.KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setCertificateEntry("ca", root.certificate)
    val tmf = javax.net.ssl.TrustManagerFactory
      .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    bare.init(null, tmf.getTrustManagers, null)
    val anonymous = HttpClient.newBuilder().sslContext(bare).build()
    assert(get(anonymous, "/observability/topology").isFailure, "no certificate was admitted")
  }

  test("a reader expecting another service refuses this one before reading anything") {
    val wrong = reading(ObserveServer.ControlPlane, expecting = "ankka://checkout/orders")
    assert(get(wrong, "/observability/topology").isFailure)
  }

  test("nothing but the two documents is served: no traces, no sessions, no query") {
    val controlplane = reading(ObserveServer.ControlPlane)
    for path <- Seq(
        "/observability/traces",
        "/observability/sessions/s1",
        "/observability/query/a/b/c",
        "/"
      )
    do assertEquals(get(controlplane, path).get.statusCode(), 404, path)
    val post = Try(
      controlplane.send(
        HttpRequest
          .newBuilder(URI.create(s"https://127.0.0.1:${server.port}/observability/topology"))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    ).get
    assertEquals(post.statusCode(), 405)
  }
