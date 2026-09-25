package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * `withAcl`: a route answers to its own ACL rather than the endpoint's, and that ACL *replaces* the
 * endpoint's rather than adding to it — so an open endpoint can hold one protected route and a
 * closed one can open a single route, without either being split in two at a second prefix.
 *
 * Drives `Router` directly, like `AclSuite`, because the contract is the router's.
 */
class RouteAclSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "route-acl-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def decide(context: RequestContext): AuthDecision =
    context.header("x-who") match
      case None          => AuthDecision.Unauthenticated("""realm="test"""")
      case Some(subject) => AuthDecision.Allow(Principal(subject))

  /** Public, with one route that support staff alone may call. */
  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll

    get("/{cartId}")((cartId: String) => s"cart:$cartId")
    sse("/{cartId}/events")((cartId: String) => Source.single(cartId))

    withAcl(Acl.Authenticate(decide)) {
      delete("/{cartId}")((cartId: String) => s"purged:$cartId by ${principal.subject}")
      sse("/{cartId}/audit")((cartId: String) => Source.single(s"$cartId:${principal.subject}"))
    }

  /** The mirror: closed, with one route deliberately opened. */
  private final class Admin extends HttpEndpoint("/admin"):
    val acl: Acl = Acl.DenyAll

    get("/secret")(() => "never")

    withAcl(Acl.AllowAll) {
      get("/ping")(() => "pong")
    }

  /** Scopes nest; the innermost is the one that applies. */
  private final class Nested extends HttpEndpoint("/nested"):
    val acl: Acl = Acl.AllowAll

    withAcl(Acl.DenyAll) {
      get("/outer")(() => "outer")
      withAcl(Acl.AllowIf(_.header("x-ok").contains("yes"))) {
        get("/inner")(() => "inner")
      }
      get("/after")(() => "after")
    }

    get("/free")(() => "free")

  private val router = Router(Vector(new Carts, new Admin, new Nested), 5.seconds)

  private def call(
      method: HttpMethod,
      path: String,
      headers: (String, String)*
  ): (Int, String, Map[String, String]) =
    val raw: List[HttpHeader] = headers.map((k, v) => RawHeader(k, v)).toList
    val request               = HttpRequest(method = method, uri = path).withHeaders(raw)
    val response              = Await.result(router.handle(request), 10.seconds)
    val body = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body, response.headers.map(h => h.lowercaseName -> h.value).toMap)

  private def fetch(path: String, headers: (String, String)*) =
    call(HttpMethods.GET, path, headers*)

  test("a route's acl protects that route and leaves its siblings open") {
    assertEquals(fetch("/carts/abc")._2, "cart:abc")

    val (status, body, headers) = call(HttpMethods.DELETE, "/carts/abc")
    assertEquals(status, 401)
    assertEquals(headers.get("www-authenticate"), Some("""Bearer realm="test""""))
    assert(body.contains("authentication required"), body)
  }

  test("a route's acl replaces the endpoint's rather than adding to it") {
    // Open endpoint, authenticated route: the route decides, so a credential gets through.
    assertEquals(call(HttpMethods.DELETE, "/carts/abc", "x-who" -> "sam")._2, "purged:abc by sam")
    // Closed endpoint, open route: the route decides there too, in the other direction.
    val (status, body, _) = fetch("/admin/ping")
    assertEquals((status, body), (200, "pong"))
    assertEquals(fetch("/admin/secret")._1, 403)
  }

  test("a principal established by a route's acl reaches that route's handler") {
    assertEquals(call(HttpMethods.DELETE, "/carts/xyz", "x-who" -> "dana")._2, "purged:xyz by dana")
    // And asking on a route that does not authenticate is still the loud failure it was.
    assertEquals(fetch("/carts/xyz")._1, 200)
  }

  test(
    "an unmatched path is judged by the endpoint's acl, so a closed endpoint discloses nothing"
  ) {
    // `/admin` denies: a path that exists and one that does not must be indistinguishable.
    assertEquals(fetch("/admin/secret")._1, 403)
    assertEquals(fetch("/admin/no-such-thing")._1, 403)
    // The wrong verb on a real path is the endpoint's decision too, not the route's.
    assertEquals(call(HttpMethods.POST, "/admin/ping")._1, 403)

    // An open endpoint still answers honestly.
    assertEquals(fetch("/carts/abc/no-such-thing")._1, 404)
    assertEquals(call(HttpMethods.POST, "/carts/abc")._1, 405)
  }

  test("scopes nest, and the innermost wins") {
    assertEquals(fetch("/nested/outer")._1, 403)
    assertEquals(fetch("/nested/inner")._1, 403)
    assertEquals(fetch("/nested/inner", "x-ok" -> "yes")._2, "inner")
    // The scope closes: a route declared after the nested block is back under the outer one,
    // and one declared outside both is back under the endpoint's.
    assertEquals(fetch("/nested/after")._1, 403)
    assertEquals(fetch("/nested/free")._2, "free")
  }

  test("a streaming route takes a route acl, and is refused before the stream opens") {
    val (status, _, headers) = fetch("/carts/abc/audit")
    assertEquals(status, 401)
    assertEquals(headers.get("www-authenticate"), Some("""Bearer realm="test""""))

    assertEquals(fetch("/carts/abc/audit", "x-who" -> "sam")._1, 200)
    assertEquals(fetch("/carts/abc/events")._1, 200)
  }
