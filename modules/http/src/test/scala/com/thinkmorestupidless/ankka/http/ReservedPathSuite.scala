package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** `/_ankka` is the runtime's (feature 055): no endpoint is given a path under it. */
class ReservedPathSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "reserved-path",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )

  private final class Mine(prefix: String) extends HttpEndpoint(prefix):
    val acl: Acl = Acl.AllowAll
    get("/x")(() => "mine")

  private val server = HttpServer.at("127.0.0.1", 0)()
  private val client = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    server.serve(Vector(Mine("/")), "127.0.0.1", 0, 10.seconds)

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def status(path: String): Int =
    client
      .send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}$path"))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .statusCode

  test("an endpoint under /_ankka is refused at start") {
    val refused = intercept[IllegalArgumentException] {
      HttpServer
        .at("127.0.0.1", 0)()
        .serve(Vector(Mine("/_ankka/mine")), "127.0.0.1", 0, 10.seconds)
    }
    assert(refused.getMessage.contains("the /_ankka prefix is the runtime's"), refused.getMessage)
  }

  test("a storage address is 404 when the service's store is not a directory") {
    assertEquals(status("/_ankka/storage/_default/x/y?access=read&expires=9&token=a"), 404)
    assertEquals(status("/_ankka/anything"), 404)
  }

  test("health is answered as before, and an endpoint at the root is unchanged") {
    assertEquals(status("/_ankka/health"), 200)
    assertEquals(status("/x"), 200)
  }
