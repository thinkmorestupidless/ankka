package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.testpki.TestPki

import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.Instant
import javax.net.ssl.{SSLServerSocket, SSLSocket}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class RotatingTlsSuite extends munit.FunSuite:

  private val root = TestPki.root("rotating-tls-suite")

  private def dir(): Path = Files.createTempDirectory("rotating-tls")

  /**
   * Pushes every file's mtime forward, so a rewrite within the same millisecond still reads as new.
   */
  private def touch(directory: Path): Unit =
    val later = FileTime.from(Instant.now().plusSeconds(5))
    Seq("tls.key", "tls.crt", "ca.crt").foreach(n =>
      Files.setLastModifiedTime(directory.resolve(n), later): Unit
    )

  /**
   * One real handshake over loopback: a server socket from `server`'s context requiring a client
   * certificate, a client socket from `client`'s. Answers the certificate the client saw, or the
   * failure.
   */
  private def handshake(
      server: RotatingTls,
      client: Option[RotatingTls]
  ): Either[Throwable, java.math.BigInteger] =
    val listener =
      server.sslContext.getServerSocketFactory.createServerSocket(0).asInstanceOf[SSLServerSocket]
    listener.setNeedClientAuth(true)
    listener.setEnabledProtocols(Array("TLSv1.3"))
    val accepted = Future {
      val socket = listener.accept().asInstanceOf[SSLSocket]
      try Try(socket.startHandshake()).toEither
      finally socket.close()
    }
    try
      val factory = client match
        case Some(c) => c.sslContext.getSocketFactory
        case None    =>
          // Trusts the server's authority and presents nothing.
          val bare = javax.net.ssl.SSLContext.getInstance("TLS")
          val ks   = java.security.KeyStore.getInstance("PKCS12")
          ks.load(null, null)
          ks.setCertificateEntry("ca", root.certificate)
          val tmf = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
          tmf.init(ks)
          bare.init(null, tmf.getTrustManagers, null)
          bare.getSocketFactory
      val socket = factory.createSocket("127.0.0.1", listener.getLocalPort).asInstanceOf[SSLSocket]
      socket.setEnabledProtocols(Array("TLSv1.3"))
      val result = Try {
        socket.startHandshake()
        // TLS 1.3 reports a refused client certificate on the first read, not the handshake.
        socket.getOutputStream.write(1)
        socket.getOutputStream.flush()
        socket.setSoTimeout(2000)
        val _ = Try(socket.getInputStream.read())
        socket.getSession.getPeerCertificates.head
          .asInstanceOf[java.security.cert.X509Certificate]
          .getSerialNumber
      }.toEither
      socket.close()
      val serverSide = Await.result(accepted, 5.seconds)
      serverSide.flatMap(_ => result)
    finally listener.close()

  test("a server and a client from the same authority complete a mutual handshake") {
    val server = RotatingTls(
      root.issue(uris = Seq("ankka://p/server"), dnsNames = Seq("localhost")).writeTo(dir()),
      1.minute
    )
    val client = RotatingTls(root.issue(uris = Seq("ankka://p/client")).writeTo(dir()), 1.minute)
    assert(handshake(server, Some(client)).isRight)
  }

  test("a client presenting no certificate is refused") {
    val server = RotatingTls(root.issue(uris = Seq("ankka://p/server")).writeTo(dir()), 1.minute)
    assert(handshake(server, None).isLeft)
  }

  test("a client from another authority is refused") {
    val server  = RotatingTls(root.issue(uris = Seq("ankka://p/server")).writeTo(dir()), 1.minute)
    val foreign = TestPki.root("foreign").issue(uris = Seq("ankka://p/client"))
    val client  = RotatingTls(foreign.writeTo(dir()), 1.minute)
    assert(handshake(server, Some(client)).isLeft)
  }

  test("rewritten files are picked up after the interval, and not before it") {
    val directory = dir()
    val first     = root.issue(uris = Seq("ankka://p/s")).writeTo(directory)
    val tls       = RotatingTls(first, 200.millis)
    val original  = tls.current.getSerialNumber
    val next      = root.issue(uris = Seq("ankka://p/s"))
    next.writeTo(directory)
    touch(directory)
    assertEquals(tls.current.getSerialNumber, original, "reloaded before the interval")
    Thread.sleep(250)
    assertEquals(tls.current.getSerialNumber, next.serial)
  }

  test("a reload that fails keeps the previous identity") {
    val directory = root.issue(uris = Seq("ankka://p/s")).writeTo(dir())
    val tls       = RotatingTls(directory, 10.millis)
    val original  = tls.current.getSerialNumber
    Files.writeString(directory.resolve("tls.key"), "not a key")
    touch(directory)
    Thread.sleep(20)
    assertEquals(tls.current.getSerialNumber, original)
  }

  test("identity reads the ankka URI, and a certificate without one has none") {
    val named =
      RotatingTls(root.issue(uris = Seq("ankka://checkout/orders")).writeTo(dir()), 1.minute)
    assertEquals(named.identity, Some(RotatingTls.Identity("checkout", "orders")))
    val gateway = RotatingTls(root.issue(uris = Seq("ankka://gateway")).writeTo(dir()), 1.minute)
    assertEquals(gateway.identity, None)
    val plain = RotatingTls(root.issue(dnsNames = Seq("x.svc")).writeTo(dir()), 1.minute)
    assertEquals(plain.identity, None)
  }

  test("a missing file fails construction naming the file") {
    val directory = dir()
    val e         = intercept[IllegalStateException](RotatingTls(directory, 1.minute))
    assert(e.getMessage.contains("tls.key"), e.getMessage)
  }

  test("the server engine requires a client certificate and the client engine checks the host") {
    val tls = RotatingTls(root.issue(uris = Seq("ankka://p/s")).writeTo(dir()), 1.minute)
    assert(tls.serverEngine().getNeedClientAuth)
    assertEquals(
      tls.clientEngine("orders.ns.svc", 443).getSSLParameters.getEndpointIdentificationAlgorithm,
      "HTTPS"
    )
  }

  test("under SameIdentity a peer from the same authority but another service is refused") {
    val mine    = root.issue(uris = Seq("ankka://p/orders"))
    val server  = RotatingTls(mine.writeTo(dir()), 1.minute, RotatingTls.Peers.SameIdentity)
    val sibling = RotatingTls(mine.writeTo(dir()), 1.minute, RotatingTls.Peers.SameIdentity)
    assert(handshake(server, Some(sibling)).isRight, "the same identity must be accepted")
    val other = RotatingTls(
      root.issue(uris = Seq("ankka://p/carts")).writeTo(dir()),
      1.minute,
      RotatingTls.Peers.SameIdentity
    )
    assert(handshake(server, Some(other)).isLeft, "another service's identity must be refused")
  }

  test("SameIdentity refuses to start from a certificate with no ankka identity") {
    val directory = root.issue(dnsNames = Seq("x.svc")).writeTo(dir())
    intercept[IllegalStateException](
      RotatingTls(directory, 1.minute, RotatingTls.Peers.SameIdentity)
    )
  }
