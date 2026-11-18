package com.thinkmorestupidless.ankka.http.openapi

import com.thinkmorestupidless.ankka.http.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * A service serves its OpenAPI document only to the callers it names (feature 067), at the level
 * below `features/openapi-documents/serving.feature`, which `OpenApiFeatures` runs: the media type,
 * the exact challenge and refusal, a query string, the reserved path. SC-009's table holds what a
 * server without it answers to what it answered before this feature.
 */
class OpenApiServedSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "cart",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  /** Tokens from the issuer "shop": a bearer of `shop` is a verified person. */
  private object ShopTokens extends TokenAuthenticator:
    val authorizationServers: Vector[String]         = Vector("https://auth.shop.example")
    def apply(context: RequestContext): AuthDecision =
      context.header("Authorization") match
        case Some("Bearer shop") => AuthDecision.Allow(Principal("ann"))
        case _                   => AuthDecision.Unauthenticated("realm=\"shop\"")

  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll
    get("/{cartId}")((id: String) => id)
    withAcl(Acl.allowCallers(CallerMatcher.NamedService(None, "billing"))) {
      delete("/{cartId}")((_: String) => ())
    }

  private val http = HttpClient.newHttpClient()

  /** A server for the cart, serving its document to `acl` when given one. */
  private def serving[A](acl: Option[Acl])(use: (HttpServer, String) => A): A =
    val plain  = HttpServer.at("127.0.0.1", 0)(_ => Carts())
    val server = acl.fold(plain)(plain.withOpenApi)
    server.serve(Vector(Carts()), "127.0.0.1", 0, 5.seconds)
    try use(server, s"http://127.0.0.1:${server.boundPort.get}")
    finally server.stop()

  private def send(
      base: String,
      method: String,
      path: String,
      token: Option[String] = None
  ): (Int, String, String) =
    val request = HttpRequest
      .newBuilder(URI.create(base + path))
      .method(method, HttpRequest.BodyPublishers.noBody())
    token.foreach(t => request.header("Authorization", s"Bearer $t"))
    val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    (answer.statusCode, answer.headers.firstValue("content-type").orElse(""), answer.body)

  private val ToShop = Some(Acl.Authenticate(ShopTokens))

  test("an admitted caller is answered the written document as application/json") {
    serving(ToShop) { (server, base) =>
      val (status, contentType, body) = send(base, "GET", "/_ankka/openapi.json", Some("shop"))
      assertEquals((status, contentType), (200, "application/json"))
      val written = server.document("openapi", Map("name" -> "cart"), registry = null)
      assertEquals(written, Some(Right(body)))
    }
  }

  test("a caller the ACL does not admit is answered 401 with the authenticator's challenge") {
    serving(ToShop) { (_, base) =>
      val (status, _, _) = send(base, "GET", "/_ankka/openapi.json")
      assertEquals(status, 401)
      val challenge = http
        .send(
          HttpRequest.newBuilder(URI.create(base + "/_ankka/openapi.json")).GET().build(),
          HttpResponse.BodyHandlers.discarding()
        )
        .headers
        .firstValue("WWW-Authenticate")
        .orElse("")
      assertEquals(challenge, "Bearer realm=\"shop\"")
    }
  }

  test("with no ACL for the document, the path answers the router's own 404 body") {
    serving(None) { (_, base) =>
      assertEquals(
        send(base, "GET", "/_ankka/openapi.json"),
        (
          404,
          "application/json",
          """{"status":404,"error":"no endpoint for /_ankka/openapi.json"}"""
        )
      )
    }
  }

  test("a route the reader may not call is still in the document it is answered") {
    serving(ToShop) { (_, base) =>
      val (_, _, body) = send(base, "GET", "/_ankka/openapi.json", Some("shop"))
      assert(body.contains("\"/carts/{cartId}\""), body)
      assert(body.contains("\"delete\""), body)
    }
  }

  test("a request for the document with a query string is answered the whole document") {
    serving(ToShop) { (_, base) =>
      val whole    = send(base, "GET", "/_ankka/openapi.json", Some("shop"))
      val selected = send(base, "GET", "/_ankka/openapi.json?tag=cart&prefix=/carts", Some("shop"))
      assertEquals(selected, whole)
    }
  }

  test("the path is the platform's: a root route on it is refused at start") {
    final class Squatter extends HttpEndpoint("/squat"):
      val acl: Acl                                          = Acl.AllowAll
      override private[ankka] def rootRoutes: Vector[Route] = Vector(
        Route(
          "GET",
          PathTemplate.parse("/_ankka/openapi.json"),
          needsBody = false,
          (_, _) => EncodedResponse(200, "text/plain", "mine".getBytes)
        )
      )
    val refused = intercept[IllegalArgumentException](
      HttpServer.at("127.0.0.1", 0)().serve(Vector(Squatter()), "127.0.0.1", 0, 5.seconds)
    )
    assert(
      refused.getMessage.contains("reserves for the service's OpenAPI document"),
      refused.getMessage
    )
  }

  // SC-009: what a server that does not serve its document answered before feature 067, recorded
  // against the parent of this feature's serving commit and kept here, so a change to these
  // answers is a change the feature made to services that do not use it.
  private val before = Vector(
    ("GET", "/_ankka/openapi.json") ->
      (
        404,
        "application/json",
        """{"status":404,"error":"no endpoint for /_ankka/openapi.json"}"""
      ),
    ("GET", "/_ankka/openapi.json?tag=cart") ->
      (
        404,
        "application/json",
        """{"status":404,"error":"no endpoint for /_ankka/openapi.json"}"""
      ),
    ("POST", "/_ankka/openapi.json") ->
      (
        404,
        "application/json",
        """{"status":404,"error":"no endpoint for /_ankka/openapi.json"}"""
      ),
    ("HEAD", "/_ankka/openapi.json") -> (404, "application/json", ""),
    ("GET", "/_ankka/openapi")       ->
      (404, "application/json", """{"status":404,"error":"no endpoint for /_ankka/openapi"}"""),
    ("GET", "/_ankka/health") -> (200, "text/plain; charset=UTF-8", "ok"),
    ("GET", "/carts/c1")      -> (200, "text/plain; charset=UTF-8", "c1"),
    ("GET", "/openapi.json")  ->
      (404, "application/json", """{"status":404,"error":"no endpoint for /openapi.json"}""")
  )

  test("a service that does not use the feature answers every request as it did before (SC-009)") {
    serving(None) { (_, base) =>
      before.foreach { case ((method, path), answer) =>
        assertEquals(send(base, method, path), answer, s"$method $path")
      }
    }
  }
