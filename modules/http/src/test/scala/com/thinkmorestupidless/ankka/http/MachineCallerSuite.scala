package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * A machine's token at a route, over the platform's mutual TLS (feature 040): a request with the
 * gateway's certificate and a valid token is the machine; with no token, an expired one or another
 * issuer's, it is the gateway; and over a connection between services the certificate is the caller
 * whatever token the request carries.
 */
class MachineCallerSuite extends munit.FunSuite:

  private val authority = TestPki.root("machine-caller-suite")
  private val serverDir =
    authority
      .issue(uris = Seq("ankka://spinvibe/affiliates"), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("machine-caller"))

  private given system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "machine-caller-suite",
    ConfigFactory
      .parseString(s"""
        |pekko.actor.provider = local
        |ankka.http.tls.enabled = on
        |ankka.tls.service-directory = "$serverDir"
        |""".stripMargin)
      .withFallback(ConfigFactory.load())
  )

  private val issuer = "https://api.example.test"
  private val signer = MachineTokenSigner()

  private final class Affiliates extends HttpEndpoint("/v1/affiliates"):
    val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("payments", "merchant"))
    get("/whoami")(() => Caller.encode(caller))

  private val server = HttpServer
    .at("127.0.0.1", 0)()
    .withMachines(MachineTokens.withKeys(issuer, Map(signer.kid -> signer.publicKey)))
  server.serve(Vector(new Affiliates), "127.0.0.1", 0, 5.seconds)
  private val port = server.boundPort.get

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def whoami(certificate: String, bearer: Option[String]): (Int, String) =
    val context = RotatingTls(
      authority.issue(uris = Seq(certificate)).writeTo(Files.createTempDirectory("client")),
      1.minute
    ).sslContext
    val request = HttpRequest
      .newBuilder(URI(s"https://localhost:$port/v1/affiliates/whoami"))
      .timeout(java.time.Duration.ofSeconds(5))
    bearer.foreach(t => request.header("Authorization", s"Bearer $t"))
    val response = HttpClient
      .newBuilder()
      .sslContext(context)
      .build()
      .send(request.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  test("through the gateway with a valid token, the caller is the machine") {
    assertEquals(
      whoami(RotatingTls.GatewayUri, Some(signer.token(issuer))),
      (200, "machine:eitheror/affiliate-network")
    )
  }

  test(
    "through the gateway with no token, an expired one, or another issuer's, it is the gateway"
  ) {
    val expired = signer.sign(signer.claims(issuer, now = Instant.now().minusSeconds(3600)))
    for bearer <- Vector(None, Some(expired), Some(signer.token("https://elsewhere.test"))) do
      assertEquals(whoami(RotatingTls.GatewayUri, bearer), (200, "gateway"), bearer.toString)
  }

  test(
    "over a connection between services, a token changes nothing: the certificate is the caller"
  ) {
    assertEquals(
      whoami("ankka://payments/merchant", Some(signer.token(issuer))),
      (200, "service:payments/merchant")
    )
  }
