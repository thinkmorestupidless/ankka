package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Root routes (feature 054): a route whose template is the whole path, under no prefix, so an MCP
 * endpoint at `/mcp` can answer `/.well-known/oauth-protected-resource/mcp`. Served ahead of every
 * prefix and refused at start when two endpoints would answer one path.
 */
class RootRoutesSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "root-routes-suite",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )
  private given ExecutionContext = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def answer(text: String) = Route(
    "GET",
    PathTemplate.parse(""),
    needsBody = false,
    (_, _) => EncodedResponse(200, "text/plain", text.getBytes("UTF-8"))
  )

  private def root(method: String, path: String, text: String) =
    answer(text).copy(method = method, template = PathTemplate.parse(path))

  /** An endpoint at `/`, which would claim every path without root routes. */
  private final class Everything extends HttpEndpoint("/"):
    val acl: Acl = Acl.AllowAll
    get("/{anything}")((anything: String) => s"everything:$anything")

  private final class Discovery(paths: String*) extends HttpEndpoint("/mcp"):
    val acl: Acl = Acl.DenyAll
    get("/inner")(() => "inner")
    override private[ankka] def rootRoutes: Vector[Route] =
      paths.toVector.map(p => root("GET", p, s"root:$p").copy(acl = Some(Acl.AllowAll)))

  private def fetch(router: Router, path: String): (Int, String) =
    val response = Await.result(router.handle(HttpRequest(uri = path)), 10.seconds)
    val body     = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body)

  test("a root route is served ahead of another endpoint's prefix") {
    val router = Router(Vector(new Everything, new Discovery("/.well-known/x")), 5.seconds)
    assertEquals(fetch(router, "/.well-known/x"), (200, "root:/.well-known/x"))
    assertEquals(fetch(router, "/other"), (200, "everything:other"))
  }

  test("a root route answers to its own acl, not its endpoint's") {
    val router = Router(Vector(new Discovery("/.well-known/x")), 5.seconds)
    assertEquals(fetch(router, "/.well-known/x")._1, 200)
    assertEquals(fetch(router, "/mcp/inner")._1, 403)
  }

  test("the health probe still answers") {
    val router = Router(Vector(new Everything, new Discovery("/_ankka/health")), 5.seconds)
    assertEquals(fetch(router, "/_ankka/health"), (200, "ok"))
  }

  private def refusal(endpoints: HttpEndpoint*): String =
    intercept[IllegalArgumentException](
      HttpServer.at("127.0.0.1", 0)().serve(endpoints.toVector, "127.0.0.1", 0, 5.seconds)
    ).getMessage

  test("two endpoints claiming one root route fail the start, naming both") {
    final class Office extends HttpEndpoint("/office"):
      val acl: Acl = Acl.AllowAll
      override private[ankka] def rootRoutes: Vector[Route] =
        Vector(root("GET", "/.well-known/x", "office"))
    val message = refusal(new Discovery("/.well-known/x"), new Office)
    assert(message.contains("invalid ankka http configuration"), message)
    assert(message.contains("root route GET /.well-known/x"), message)
    assert(message.contains("'/mcp'") && message.contains("'/office'"), message)
  }

  test("a root route and a prefixed route on one path fail the start") {
    final class Known extends HttpEndpoint("/.well-known"):
      val acl: Acl = Acl.AllowAll
      get("/x")(() => "prefixed")
    val message = refusal(new Discovery("/.well-known/x"), new Known)
    assert(message.contains("root route GET /.well-known/x of '/mcp'"), message)
    assert(message.contains("also served by '/.well-known'"), message)
  }
