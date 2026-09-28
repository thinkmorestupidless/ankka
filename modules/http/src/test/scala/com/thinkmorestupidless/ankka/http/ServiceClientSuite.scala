package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.runtime.HttpServiceClients
import com.thinkmorestupidless.ankka.sdk.{
  ServiceCallFailed,
  ServiceIdentityMismatch,
  ServiceUnresolvable
}
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * One service calling another as itself (feature 014): the caller presents its certificate, the
 * callee reads it as the caller, and a callee that is not who was asked for is refused before
 * anything is sent.
 */
class ServiceClientSuite extends munit.FunSuite:

  final case class Echo(caller: String)
  given JsonValueCodec[Echo] = JsonCodecMaker.make

  private val authority = TestPki.root("service-client-suite")
  private val served    = AtomicInteger()

  private final class Callee extends HttpEndpoint("/echo"):
    val acl: Acl = Acl.allowCallers(Callers.service("checkout", "orders"))
    get("/whoami")(() =>
      served.incrementAndGet()
      Echo(Caller.encode(caller))
    )

  /** A TLS server presenting `uri`, on loopback. */
  private def serving(uri: String): (ActorSystem[Nothing], HttpServer, Int) =
    val dir = authority
      .issue(uris = Seq(uri), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("callee"))
    given system: ActorSystem[Nothing] = ActorSystem(
      Behaviors.empty,
      "callee",
      ConfigFactory
        .parseString(s"""
          |ankka.http.tls.enabled = on
          |ankka.tls.service-directory = "$dir"
          |""".stripMargin)
        .withFallback(ConfigFactory.load())
    )
    val server = HttpServer.at("127.0.0.1", 0)()
    server.serve(Vector(new Callee), "127.0.0.1", 0, 5.seconds)
    (system, server, server.boundPort.get)

  private def stop(system: ActorSystem[?], server: HttpServer): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  /** The caller's side: its own certificate, and the callee found at `port` on loopback. */
  private def clientsAs(uri: String, port: Int): HttpServiceClients =
    val dir = authority.issue(uris = Seq(uri)).writeTo(Files.createTempDirectory("caller"))
    HttpServiceClients(
      ConfigFactory
        .parseString(s"""ankka.tls.service-directory = "$dir"""")
        .withFallback(ConfigFactory.load()),
      None,
      (_, _) => Some("localhost" -> port)
    )

  test("a call presents the caller's certificate, and the callee reads it as the caller") {
    val (system, server, port) = serving("ankka://checkout/carts")
    try
      val echo = clientsAs("ankka://checkout/orders", port)("carts").get[Echo]("/echo/whoami")
      assertEquals(echo, Echo("service:checkout/orders"))
    finally stop(system, server)
  }

  test("a caller the callee's ACL does not admit is answered 403, as a ServiceCallFailed") {
    val (system, server, port) = serving("ankka://checkout/carts")
    try
      val failed = intercept[ServiceCallFailed](
        clientsAs("ankka://checkout/payments", port)("carts").get[Echo]("/echo/whoami")
      )
      assertEquals(failed.status, 403)
    finally stop(system, server)
  }

  test("a callee holding another service's certificate is refused before anything is sent") {
    val (system, server, port) = serving("ankka://checkout/impostor")
    try
      val before = served.get
      intercept[ServiceIdentityMismatch](
        clientsAs("ankka://checkout/orders", port)("carts").get[Echo]("/echo/whoami")
      )
      assertEquals(served.get, before, "the impostor received the request")
    finally stop(system, server)
  }

  test("a name that resolves to nothing says what was tried") {
    val dir = authority
      .issue(uris = Seq("ankka://checkout/orders"))
      .writeTo(Files.createTempDirectory("caller"))
    val clients = HttpServiceClients(
      ConfigFactory
        .parseString(s"""ankka.tls.service-directory = "$dir"""")
        .withFallback(ConfigFactory.load()),
      None,
      (_, _) => None
    )
    val e = intercept[ServiceUnresolvable](clients("nowhere").getText("/"))
    assert(e.reason.contains("_http._tcp.nowhere."), e.reason)
  }

  test("outside a cluster a service is reached by its configured local address, over plain HTTP") {
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "local-callee")
    val server                         = HttpServer.at("127.0.0.1", 0)()
    server.serve(Vector(new Callee), "127.0.0.1", 0, 5.seconds)
    try
      val clients = HttpServiceClients(
        ConfigFactory
          .parseString(
            s"""ankka.local-services.carts = "http://127.0.0.1:${server.boundPort.get}""""
          )
          .withFallback(ConfigFactory.load()),
        None
      )
      // Locally every caller is Local, which a caller-naming ACL admits.
      assertEquals(clients("carts").get[Echo]("/echo/whoami"), Echo("local"))
    finally stop(system, server)
  }
