package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * `Acl.Authenticate` at the router: the three refusals answer as three different statuses, and an
 * allowed principal reaches the handler on the handler's own thread. Drives `Router` directly, with
 * no runtime, because the contract is the router's.
 */
class AclSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "acl-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  /** Decides from a header, the way a token verifier would from `Authorization`. */
  private def decide(context: RequestContext): AuthDecision =
    context.header("x-who") match
      case None           => AuthDecision.Unauthenticated("""realm="test"""")
      case Some("nobody") => AuthDecision.Forbidden("nobody may")
      case Some("later")  => AuthDecision.Unavailable("keys unavailable")
      case Some(subject)  => AuthDecision.Allow(Principal(subject, email = Some(s"$subject@x")))

  private final class Authenticated extends HttpEndpoint("/secure"):
    val acl: Acl = Acl.Authenticate(decide)
    get("/me")(() => s"${principal.subject}:${principal.email.getOrElse("")}")

  private final class Open extends HttpEndpoint("/open"):
    val acl: Acl = Acl.AllowAll
    get("/present")(() => request.principal.isDefined.toString)
    get("/asks")(() => principal.subject)

  private final class Predicate extends HttpEndpoint("/pred"):
    val acl: Acl = Acl.AllowIf(_.header("x-ok").contains("yes"))
    get("/")(() => "ok")

  /** A second endpoint under the first's prefix: the longer prefix must win, whatever the order. */
  private final class Nested extends HttpEndpoint("/open/nested"):
    val acl: Acl = Acl.Authenticate(decide)
    get("/")(() => principal.subject)

  private val router =
    Router(Vector(new Open, new Nested, new Authenticated, new Predicate), 5.seconds)

  private def call(path: String, headers: (String, String)*): (Int, String, Map[String, String]) =
    val raw: List[HttpHeader] = headers.map((k, v) => RawHeader(k, v)).toList
    val request               = HttpRequest(uri = path).withHeaders(raw)
    val response              = Await.result(router.handle(request), 10.seconds)
    val body = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body, response.headers.map(h => h.lowercaseName -> h.value).toMap)

  test("no credential is 401 with a Bearer challenge, never 403") {
    val (status, body, headers) = call("/secure/me")
    assertEquals(status, 401)
    assertEquals(headers.get("www-authenticate"), Some("""Bearer realm="test""""))
    assert(body.contains("authentication required"), body)
  }

  test("a forbidden principal is 403 with the reason") {
    val (status, body, headers) = call("/secure/me", "x-who" -> "nobody")
    assertEquals(status, 403)
    assert(body.contains("nobody may"), body)
    assert(!headers.contains("www-authenticate"))
  }

  test("an unavailable verifier is 503 with Retry-After") {
    val (status, _, headers) = call("/secure/me", "x-who" -> "later")
    assertEquals(status, 503)
    assertEquals(headers.get("retry-after"), Some("5"))
  }

  test("an allowed principal reaches the handler on its own thread") {
    val (status, body, _) = call("/secure/me", "x-who" -> "alice")
    assertEquals(status, 200)
    assertEquals(body, "alice:alice@x")
  }

  test("under AllowAll there is no principal, and asking for one fails loudly") {
    assertEquals(call("/open/present", "x-who" -> "alice")._2, "false")
    // The router does not leak the exception's text, so the loud failure is the status alone.
    val (status, _, _) = call("/open/asks", "x-who" -> "alice")
    assertEquals(status, 500)
  }

  test("AllowIf still answers 403 on false") {
    assertEquals(call("/pred/")._1, 403)
    assertEquals(call("/pred/", "x-ok" -> "yes")._1, 200)
  }

  test("the longest prefix wins, so an open endpoint can sit beside an authenticated one") {
    assertEquals(call("/open/nested/")._1, 401)
    assertEquals(call("/open/nested/", "x-who" -> "bob")._2, "bob")
    assertEquals(call("/open/present")._1, 200)
  }

  test("the health route stays exempt") {
    val (status, body, _) = call("/_ankka/health")
    assertEquals((status, body), (200, "ok"))
  }
