package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.duration.*

/** A socket route's declaration: its arity, its ACL, its name, and where it may not be declared. */
class SocketRouteSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "socket-route-suite",
    com.typesafe.config.ConfigFactory
      .parseString("pekko.actor.provider = local")
      .withFallback(com.typesafe.config.ConfigFactory.load())
  )

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 30.seconds): Unit

  private final class Notices extends HttpEndpoint("/notices"):
    val acl: Acl = Acl.AllowAll
    socket("/stream")(socket => socket.send("hello"))
    socket("/rooms/{room}")((room: String, socket: Socket) => socket.send(room))
    socket("/rooms/{room}/{seat}")((room: String, seat: Int, socket: Socket) =>
      socket.send(s"$room:$seat")
    )
    post("/stream")(() => "posted")
    withAcl(Acl.DenyAll) {
      socket("/closed")(_ => ())
    }

  test("a handler whose arity does not match its template is refused at construction") {
    val refused = intercept[IllegalArgumentException] {
      new HttpEndpoint("/bad"):
        val acl: Acl = Acl.AllowAll
        socket("/{room}")(_ => ())
    }
    assert(
      refused.getMessage.contains("SOCKET /bad/{room} declares 1 path parameter"),
      refused.getMessage
    )
    assert(refused.getMessage.contains("its handler takes 0"), refused.getMessage)
  }

  test(
    "a socket route is named SOCKET by its template, and carries the ACL it was declared under"
  ) {
    val routes = (new Notices).socketRoutes
    assertEquals(
      routes.map(_.describe),
      Vector(
        "SOCKET /stream",
        "SOCKET /rooms/{room}",
        "SOCKET /rooms/{room}/{seat}",
        "SOCKET /closed"
      )
    )
    assertEquals(routes.map(_.method).distinct, Vector("GET"))
    assertEquals(routes.last.acl, Some(Acl.DenyAll))
    assertEquals(routes.head.acl, None)
  }

  test("a socket route is served as a SOCKET route, listed beside the endpoint's others") {
    val server = HttpServer.at("127.0.0.1", 0)()
    server.serve(Vector(new Notices), "127.0.0.1", 0, 5.seconds)
    try
      val sockets = server.routes.filter(_.method == "SOCKET")
      assertEquals(
        sockets.map(_.path).toSet,
        Set(
          "/notices/stream",
          "/notices/rooms/{room}",
          "/notices/rooms/{room}/{seat}",
          "/notices/closed"
        )
      )
      assert(sockets.forall(_.streaming), sockets.toString)
      assert(server.routes.exists(r => r.method == "POST" && r.path == "/notices/stream"))
    finally server.stop()
  }

  private final class OnAGet extends HttpEndpoint("/a"):
    val acl: Acl = Acl.AllowAll
    get("/x")(() => "x")
    socket("/x")(_ => ())

  private final class OnAnSse extends HttpEndpoint("/b"):
    val acl: Acl = Acl.AllowAll
    sse("/x")(() => Source.single("x"))
    socket("/x")(_ => ())

  test("a socket route on the template of a GET or an SSE route does not start") {
    for (endpoint, prefix) <- Seq(new OnAGet -> "/a", new OnAnSse -> "/b") do
      val refused = intercept[IllegalArgumentException] {
        HttpServer.at("127.0.0.1", 0)().serve(Vector(endpoint), "127.0.0.1", 0, 5.seconds)
      }
      assert(refused.getMessage.contains(s"'$prefix' declares GET /x 2 times"), refused.getMessage)
  }

  test("a keep-alive not shorter than the idle timeout does not start") {
    val config = com.typesafe.config.ConfigFactory
      .parseString("""pekko.actor.provider = local
        |pekko.http.server.idle-timeout = 5s
        |ankka.http.socket.keep-alive = 10s""".stripMargin)
      .withFallback(com.typesafe.config.ConfigFactory.load())
    val other = ActorSystem(Behaviors.empty, "socket-keep-alive", config)
    try
      val refused = intercept[IllegalArgumentException] {
        HttpServer
          .at("127.0.0.1", 0)()
          .serve(Vector(new Notices), "127.0.0.1", 0, 5.seconds)(using
            other
          )
      }
      assert(refused.getMessage.contains("keep-alive (10 seconds)"), refused.getMessage)
      assert(refused.getMessage.contains("idle-timeout (5 seconds)"), refused.getMessage)
    finally
      other.terminate()
      Await.ready(other.whenTerminated, 30.seconds): Unit
  }
