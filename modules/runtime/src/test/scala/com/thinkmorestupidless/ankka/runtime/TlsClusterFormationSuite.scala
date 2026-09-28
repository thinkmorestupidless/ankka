package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.Cluster

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Cluster formation the way the Kubernetes overlay does it, on loopback: Cluster Bootstrap over a
 * management port that requires this service's certificate, remoting over Pekko's rotating-keys
 * TLS, and readiness on a plain port of its own. Discovery is `config` instead of the Kubernetes
 * API — the one thing that differs, and the one thing that has nothing to do with TLS.
 *
 * This is research R2's spike made permanent: it proves the contact-point probes go over mutual TLS
 * through pekko-http's default client context, which is inferred from pekko-management's bytecode
 * rather than documented, so it is asserted here rather than trusted.
 */
class TlsClusterFormationSuite extends munit.FunSuite:

  override val munitTimeout: Duration = 3.minutes

  private val authority = TestPki.root("tls-cluster-formation")
  private val identity  = "ankka://checkout/orders"

  private def freePort(): Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private def certificateDir(leaf: TestPki.Leaf): Path =
    leaf.writeTo(Files.createTempDirectory("tls-cluster"))

  private def node(
      tls: Path,
      remoting: Int,
      management: Int,
      probe: Int,
      contactPoints: Seq[(String, Int)],
      host: String
  ): ActorSystem[Nothing] =
    val endpoints = contactPoints.map((h, p) => s"{ host = \"$h\", port = $p }").mkString(", ")
    val config = ConfigFactory
      .parseString(s"""
        |pekko.actor.provider = cluster
        |pekko.remote.artery {
        |  canonical.hostname = "127.0.0.1"
        |  canonical.port = $remoting
        |  transport = tls-tcp
        |  ssl.ssl-engine-provider = "org.apache.pekko.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider"
        |  ssl.rotating-keys-engine {
        |    key-file = "$tls/tls.key"
        |    cert-file = "$tls/tls.crt"
        |    ca-cert-file = "$tls/ca.crt"
        |  }
        |}
        |pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
        |pekko.coordinated-shutdown.exit-jvm = off
        |ankka.cluster.formation = bootstrap
        |ankka.join-self-if-no-seed-nodes = off
        |ankka.tls.cluster-directory = "$tls"
        |ankka.probe { enabled = on, port = $probe }
        |pekko.management.http { hostname = "$host", bind-hostname = "127.0.0.1", port = $management }
        |pekko.management.cluster.bootstrap {
        |  contact-point-discovery {
        |    service-name = orders
        |    discovery-method = config
        |    required-contact-point-nr = 2
        |    stable-margin = 1s
        |    interval = 500ms
        |  }
        |  contact-point.probe-interval = 500ms
        |  contact-point.http-client.ca-path = ""
        |}
        |pekko.discovery.config.services.orders.endpoints = [ $endpoints ]
        |""".stripMargin)
      .withFallback(ConfigFactory.load())
      .resolve()
    val system = ActorSystem[Nothing](Behaviors.empty[Nothing], "ankka", config)
    ClusterFormation.form(system)
    system

  private def eventually(what: String, within: FiniteDuration)(check: => Boolean): Unit =
    val deadline = within.fromNow
    while !check do
      if deadline.isOverdue() then fail(s"timed out waiting for: $what")
      Thread.sleep(250)

  private def up(system: ActorSystem[?]): Int =
    Cluster(system).state.members.count(_.status == MemberStatus.Up)

  private def plainGet(port: Int, path: String): Either[Throwable, Int] =
    Try {
      HttpClient
        .newHttpClient()
        .send(
          HttpRequest
            .newBuilder(URI(s"http://127.0.0.1:$port$path"))
            .timeout(java.time.Duration.ofSeconds(3))
            .build(),
          HttpResponse.BodyHandlers.discarding()
        )
        .statusCode()
    }.toEither

  /** A TLS client that trusts the authority but presents no certificate of its own. */
  private def anonymousHttpsGet(port: Int, path: String): Either[Throwable, Int] =
    Try {
      val trust = java.security.KeyStore.getInstance("PKCS12")
      trust.load(null, null)
      trust.setCertificateEntry("ca", authority.certificate)
      val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm
      )
      tmf.init(trust)
      val context = javax.net.ssl.SSLContext.getInstance("TLS")
      context.init(null, tmf.getTrustManagers, null)
      val params = new javax.net.ssl.SSLParameters()
      params.setEndpointIdentificationAlgorithm(null)
      HttpClient
        .newBuilder()
        .sslContext(context)
        .sslParameters(params)
        .build()
        .send(
          HttpRequest
            .newBuilder(URI(s"https://127.0.0.1:$port$path"))
            .timeout(java.time.Duration.ofSeconds(3))
            .build(),
          HttpResponse.BodyHandlers.discarding()
        )
        .statusCode()
    }.toEither

  test("two nodes form over mutual TLS; a node with a foreign certificate never joins") {
    val shared          = certificateDir(authority.issue(uris = Seq(identity)))
    val Seq(r1, r2, r3) = Seq.fill(3)(freePort())
    val Seq(m1, m2, m3) = Seq.fill(3)(freePort())
    val Seq(p1, p2, p3) = Seq.fill(3)(freePort())
    // Bootstrap counts contact points per host, and in a cluster every pod has its own IP. On one
    // machine the two nodes are told apart by name instead: both names are loopback.
    val points = Seq("localhost" -> m1, "127.0.0.1" -> m2)
    val a      = node(shared, r1, m1, p1, points, "localhost")
    val b      = node(shared, r2, m2, p2, points, "127.0.0.1")
    try
      eventually("two members up", 90.seconds)(up(a) == 2 && up(b) == 2)

      // Readiness answers on its own plain port, with no certificate at all.
      eventually("ready on the probe port", 30.seconds)(plainGet(p1, "/ready") == Right(200))
      assertEquals(
        plainGet(p1, "/cluster/members"),
        Right(404),
        "the probe port serves /ready only"
      )

      // Management itself refuses anyone who cannot present the service's certificate.
      assert(plainGet(m1, "/cluster/members").isLeft, "plain HTTP must not reach management")
      assert(
        anonymousHttpsGet(m1, "/cluster/members").isLeft,
        "TLS with no client certificate must fail"
      )

      // Same authority, same labels a pod could carry, different service: not a peer.
      val foreignService = certificateDir(authority.issue(uris = Seq("ankka://checkout/carts")))
      val c              = node(foreignService, r3, m3, p3, points, "127.0.0.1")
      try
        Thread.sleep(15000)
        assertEquals(up(a), 2, "a node of another service joined")
        assertNotEquals(
          Cluster(c).selfMember.status,
          MemberStatus.Up,
          "the foreign node formed a cluster"
        )
        assert(
          Cluster(c).state.members.forall(_.address == Cluster(c).selfMember.address),
          "the foreign node must know of no other member"
        )
      finally c.terminate()
    finally
      a.terminate()
      b.terminate()
      Await.ready(a.whenTerminated, 30.seconds)
      Await.ready(b.whenTerminated, 30.seconds): Unit
  }
