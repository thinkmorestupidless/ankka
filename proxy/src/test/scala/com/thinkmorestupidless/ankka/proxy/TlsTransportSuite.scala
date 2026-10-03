package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{
  Admitted,
  Answers,
  ProxyEngine,
  ProxySettings,
  StandInProcess
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, URI}
import java.nio.file.{Files, Path}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/**
 * The proxy over mutual TLS on loopback, with certificates a test authority issued: who gets a
 * connection at all, and who the process is told sent it.
 */
class TlsTransportSuite extends munit.FunSuite with LogCapturing:

  override def munitTimeout: Duration = 60.seconds

  private val loopback  = InetAddress.getByName("127.0.0.1")
  private val authority = TestPki.root("proxy-transport")
  private val foreign   = TestPki.root("proxy-transport-foreign")

  private val serverDir: Path = Files.createTempDirectory("proxy-transport-server")
  authority.issue(uris = Seq("ankka://shop/web"), dnsNames = Seq("localhost")).writeTo(serverDir)
  private val serverTls = RotatingTls(serverDir, 1.minute)

  private val process = StandInProcess().start()
  private val settings = ProxySettings(
    project = "shop",
    service = "web",
    port = 0,
    processPort = process.port,
    probePort = 0,
    callers = Vector(Admitted.Service("shop", "orders")),
    publicAuthority = Some("web-shop.example.test"),
    drainTimeout = 1.second
  )
  private val engine =
    ProxyEngine(settings, TlsTransport(serverTls, loopback), probeAddress = loopback)
  engine.start()
  private val port = engine.ports.public

  override def afterAll(): Unit =
    engine.stop()
    process.stop()

  /** A client presenting a certificate `issuer` made for `uri`, trusting the proxy's authority. */
  private def presenting(
      issuer: TestPki,
      uris: Seq[String],
      dnsNames: Seq[String] = Nil
  ): SSLContext =
    val dir = issuer.issue(uris = uris, dnsNames = dnsNames).writeTo(Files.createTempDirectory("c"))
    Files.writeString(dir.resolve("ca.crt"), authority.pem)
    RotatingTls(dir, 1.minute).sslContext

  /** A client that trusts the authority and presents nothing. */
  private def bare: SSLContext =
    val trust = java.security.KeyStore.getInstance("PKCS12")
    trust.load(null, null)
    trust.setCertificateEntry("ca", authority.certificate)
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
      javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm
    )
    tmf.init(trust)
    val context = SSLContext.getInstance("TLS")
    context.init(null, tmf.getTrustManagers, null)
    context

  private def get(context: SSLContext, path: String): HttpResponse[String] =
    HttpClient
      .newBuilder()
      .sslContext(context)
      .version(HttpClient.Version.HTTP_1_1)
      .build()
      .send(
        HttpRequest.newBuilder(URI.create(s"https://localhost:$port$path")).build(),
        BodyHandlers.ofString()
      )

  private def lastCaller: Option[String] = process.requests.last.header("x-ankka-caller")

  private def restore(property: String, previous: Option[String]): Unit =
    previous match
      case Some(value) => sys.props(property) = value
      case None        => sys.props -= property: Unit

  test("a connection without a certificate fails the handshake, and the process is given nothing") {
    val before = process.requests.size
    assert(Try(get(bare, "/no-certificate")).isFailure, "a client with no certificate was let in")
    assertEquals(process.requests.size, before)
  }

  test("a certificate from another authority fails the handshake") {
    val before = process.requests.size
    assert(
      Try(get(presenting(foreign, Seq("ankka://gateway")), "/foreign")).isFailure,
      "another authority's certificate was let in"
    )
    assertEquals(process.requests.size, before)
  }

  test("a gateway certificate is the internet") {
    val response = get(presenting(authority, Seq("ankka://gateway")), "/from-the-gateway")
    assertEquals(response.statusCode, 200)
    assertEquals(lastCaller, Some("internet"))
    assertEquals(process.requests.last.header("host"), Some("web-shop.example.test"))
  }

  test("a service's certificate is that service") {
    val response = get(presenting(authority, Seq("ankka://shop/orders")), "/from-orders")
    assertEquals(response.statusCode, 200)
    assertEquals(lastCaller, Some("service shop/orders"))
  }

  test("a service the descriptor does not name is refused by the proxy") {
    val before   = process.requests.size
    val response = get(presenting(authority, Seq("ankka://shop/ledger")), "/from-ledger")
    assertEquals(response.statusCode, 403)
    assertEquals(response.headers.firstValue(Answers.MarkerName).toScala, Some("proxy"))
    assertEquals(response.body, """{"error":"the caller is not admitted: service shop/ledger"}""")
    assertEquals(process.requests.size, before)
  }

  test("a certificate the authority issued that names nobody is refused") {
    val before   = process.requests.size
    val response = get(presenting(authority, Nil, dnsNames = Seq("something.example")), "/nobody")
    assertEquals(response.statusCode, 403)
    assertEquals(response.body, """{"error":"unrecognised caller certificate"}""")
    assertEquals(process.requests.size, before)
  }

  test("a mount identity is refused until the proxy learns to read one") {
    // Today's reading of a certificate: the shape a runtime from before this feature refuses.
    val response = get(presenting(authority, Seq("ankka://shop/web/mount")), "/under-a-mount")
    assertEquals(response.statusCode, 403)
    assertEquals(response.body, """{"error":"unrecognised caller certificate"}""")
  }

  test("a certificate whose identity differs from the settings makes run return 1 naming both") {
    val reported = Vector.newBuilder[String]
    val env = Map(
      ProxySettings.Variables.Project     -> "billing",
      ProxySettings.Variables.Service     -> "portal",
      ProxySettings.Variables.Port        -> "9000",
      ProxySettings.Variables.ProcessPort -> "8080"
    )
    val property = Main.ServiceDirectoryProperty
    val previous = sys.props.get(property)
    sys.props(property) = serverDir.toString
    try
      assertEquals(Main.run(Array.empty, env.get, reported += _), 1)
    finally restore(property, previous)
    val message = reported.result().mkString("\n")
    assert(message.contains("shop/web"), message)
    assert(message.contains("billing/portal"), message)
    assert(message.contains(serverDir.toString), message)
  }

  test("an incomplete environment makes run return 1 naming every problem") {
    val reported = Vector.newBuilder[String]
    assertEquals(Main.run(Array.empty, _ => None, reported += _), 1)
    val problems = reported.result()
    for variable <- Vector(
        ProxySettings.Variables.Project,
        ProxySettings.Variables.Service,
        ProxySettings.Variables.Port,
        ProxySettings.Variables.ProcessPort
      )
    do assert(problems.exists(_.contains(variable)), s"$variable not named in $problems")
  }

  test("a missing certificate directory makes run return 1 naming it") {
    val reported = Vector.newBuilder[String]
    val env = Map(
      ProxySettings.Variables.Project     -> "shop",
      ProxySettings.Variables.Service     -> "web",
      ProxySettings.Variables.Port        -> "9000",
      ProxySettings.Variables.ProcessPort -> "8080"
    )
    val property = Main.ServiceDirectoryProperty
    val previous = sys.props.get(property)
    val missing  = Files.createTempDirectory("no-certificate").resolve("absent")
    sys.props(property) = missing.toString
    try assertEquals(Main.run(Array.empty, env.get, reported += _), 1)
    finally restore(property, previous)
    assert(reported.result().exists(_.contains("absent")), reported.result())
  }
