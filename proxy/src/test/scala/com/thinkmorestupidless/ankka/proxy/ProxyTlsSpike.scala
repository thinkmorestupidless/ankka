package com.thinkmorestupidless.ankka.proxy

import com.sun.net.httpserver.{HttpExchange, HttpsExchange, HttpsServer}
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.io.InputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Whether the JDK's own HTTPS server can be the proxy's listener (research R2): the one piece of
 * the proxy nothing in the platform used before. Five things must hold, or the proxy takes its
 * listeners from pekko-http instead. Gated on `-Dankka.spikes=on`; reported ignored, never passed,
 * without it.
 *
 * sbt -Dankka.spikes=on 'proxy/testOnly *ProxyTlsSpike'
 */
class ProxyTlsSpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private val authority = TestPki.root("proxy-tls-spike")
  private val foreign   = TestPki.root("proxy-tls-spike-foreign")

  private val serverDir: Path = Files.createTempDirectory("proxy-tls-server")
  authority.issue(uris = Seq("ankka://shop/web"), dnsNames = Seq("localhost")).writeTo(serverDir)

  private val serverTls                          = RotatingTls(serverDir, 200.millis)
  private val seen                               = new ConcurrentLinkedQueue[String]()
  @volatile private var lastPartWritten: Instant = Instant.MAX

  private val server: HttpsServer =
    val s = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.setHttpsConfigurator(RotatingServerTls.configurator(serverTls))
    s.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    s.createContext("/whoami", exchange => respond(exchange, 200, callerOf(exchange)))
    s.createContext(
      "/count",
      exchange =>
        val in    = exchange.getRequestBody
        val chunk = new Array[Byte](8192)
        var total = 0L
        var reads = 0
        var n     = in.read(chunk)
        while n >= 0 do
          total += n
          reads += 1
          n = in.read(chunk)
        respond(exchange, 200, s"$total in $reads reads")
    )
    s.createContext(
      "/stream",
      exchange =>
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.getResponseBody
        for part <- 1 to 3 do
          out.write(s"part $part\n".getBytes(UTF_8))
          out.flush()
          if part == 3 then lastPartWritten = Instant.now()
          else Thread.sleep(1000)
        out.close()
    )
    s.createContext(
      "/host",
      exchange =>
        val host = Option(exchange.getRequestHeaders.getFirst("Host")).getOrElse("")
        seen.add(host)
        respond(exchange, 200, host)
    )
    s.start()
    s

  private val port = server.getAddress.getPort

  override def afterAll(): Unit = server.stop(0)

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def callerOf(exchange: HttpExchange): String =
    val session = exchange.asInstanceOf[HttpsExchange].getSSLSession
    val leaf    = session.getPeerCertificates.head.asInstanceOf[java.security.cert.X509Certificate]
    Caller.fromCertificate(leaf, serverTls.identity).fold(identity, _.toString)

  private def clientContext(issuer: TestPki, uri: String): SSLContext =
    RotatingTls(
      issuer.issue(uris = Seq(uri)).writeTo(Files.createTempDirectory("client")),
      1.minute
    ).sslContext

  /** A context that trusts the authority and presents nothing. */
  private def bareContext: SSLContext =
    val trust = java.security.KeyStore.getInstance("PKCS12")
    trust.load(null, null)
    trust.setCertificateEntry("ca", authority.certificate)
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
      javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm
    )
    tmf.init(trust)
    val bare = SSLContext.getInstance("TLS")
    bare.init(null, tmf.getTrustManagers, null)
    bare

  private def client(context: SSLContext): HttpClient =
    HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1).build()

  private def get(context: SSLContext, path: String): HttpResponse[String] =
    client(context).send(
      HttpRequest.newBuilder(URI(s"https://localhost:$port$path")).build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private val gateway = clientContext(authority, "ankka://gateway")

  test("1. a renewed certificate is served to the next connection, with no restart") {
    def served(): java.math.BigInteger =
      val response = get(gateway, "/whoami")
      response
        .sslSession()
        .get
        .getPeerCertificates
        .head
        .asInstanceOf[java.security.cert.X509Certificate]
        .getSerialNumber
    val before  = served()
    val renewed = authority.issue(uris = Seq("ankka://shop/web"), dnsNames = Seq("localhost"))
    renewed.writeTo(serverDir)
    // A renewal is a new mtime on every file; make sure the clock moved for a fast filesystem.
    for name <- Seq("tls.key", "tls.crt", "ca.crt") do
      Files.setLastModifiedTime(
        serverDir.resolve(name),
        FileTime.from(Instant.now().plusSeconds(5))
      )
    Thread.sleep(400)
    val after = served()
    assertNotEquals(after, before)
    assertEquals(after, renewed.serial)
  }

  test("2. a client with no certificate, or one from another authority, fails the handshake") {
    assert(Try(get(bareContext, "/whoami")).isFailure, "no certificate was let in")
    // The foreign client trusts this server's authority, so the only thing that can fail its
    // handshake is the server refusing its certificate; and the same client with a certificate from
    // the right authority gets in, so the failure is the refusal and not a broken client.
    def trustingClient(issuer: TestPki): SSLContext =
      val dir = issuer.issue(uris = Seq("ankka://gateway")).writeTo(Files.createTempDirectory("c"))
      Files.writeString(dir.resolve("ca.crt"), authority.pem)
      RotatingTls(dir, 1.minute).sslContext
    assert(
      Try(get(trustingClient(foreign), "/whoami")).isFailure,
      "another authority's certificate was let in"
    )
    assertEquals(get(trustingClient(authority), "/whoami").body, "Gateway")
  }

  test("3. the handler reads the peer's certificate and names the caller from it") {
    assertEquals(get(gateway, "/whoami").body, "Gateway")
    assertEquals(
      get(clientContext(authority, "ankka://shop/orders"), "/whoami").body,
      "Service(shop,orders)"
    )
  }

  test("4. a body is read as it arrives, and a response reaches the client part by part") {
    val megabyte = new Array[Byte](1024 * 1024)
    val counted = client(gateway).send(
      HttpRequest
        .newBuilder(URI(s"https://localhost:$port/count"))
        .POST(
          HttpRequest.BodyPublishers.ofInputStream(() => java.io.ByteArrayInputStream(megabyte))
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assert(counted.body.startsWith(s"${megabyte.length} in "), counted.body)
    val reads = counted.body.split(" ")(2).toInt
    assert(reads > 1, s"the body arrived in $reads read")

    val streamed = client(gateway).send(
      HttpRequest.newBuilder(URI(s"https://localhost:$port/stream")).build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    val in: InputStream = streamed.body
    val first           = new Array[Byte](7)
    in.readNBytes(first, 0, first.length)
    val firstArrived = Instant.now()
    assertEquals(new String(first, UTF_8), "part 1\n")
    in.readAllBytes()
    assert(
      firstArrived.isBefore(lastPartWritten),
      s"the first part arrived at $firstArrived, after the last was written at $lastPartWritten"
    )
  }

  test("5. the JDK's client sends a Host of the proxy's choosing") {
    assertEquals(
      sys.props.get("jdk.httpclient.allowRestrictedHeaders"),
      Some("host"),
      "the forked test JVM was not started with the option"
    )
    val response = client(gateway).send(
      HttpRequest
        .newBuilder(URI(s"https://localhost:$port/host"))
        .header("Host", "web-shop.example.test")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.body, "web-shop.example.test")
    assertEquals(seen.asScala.toVector.last, "web-shop.example.test")
  }
