package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * What a handler can put on a response beside a JSON body: an HTML page, raw bytes under their own
 * content type, a status of its choosing, and headers — the two a website cannot do without being
 * `Location` and `Set-Cookie`. Drives `Router` directly, as `AclSuite` does.
 */
class ResponseSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "response-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private final class Site extends HttpEndpoint("/site"):
    val acl: Acl = Acl.AllowAll
    get("/page")(() => Html("<h1>hi</h1>"))
    get("/style.css")(() => Bytes("text/css", "body{}".getBytes("UTF-8")))
    get("/login")(() => Respond.redirect("/elsewhere"))
    get("/session")(() =>
      Respond(
        Html("<p>in</p>"),
        headers = Vector("Set-Cookie" -> "s=1; HttpOnly", "X-Trace" -> "t")
      )
    )
    get("/created")(() => Respond("made", 201))
    get("/asset")(() => Respond(Bytes("image/svg+xml", "<svg/>".getBytes("UTF-8")), 200))
    // What a client throws when the service it called refused: its own exception, the refusal as
    // the cause. Letting it pass answers this caller with the same refusal.
    get("/relayed")(() =>
      (throw RuntimeException("NOT_FOUND: gone", CommandError("gone", ErrorCode.NotFound))): String
    )

  private val page       = "<h1>Your account</h1>"
  private val cookie     = "session=s-1; HttpOnly; Secure"
  private val stylesheet = "h1{color:teal}".getBytes("UTF-8")

  /** A website's routes: a page that keeps a session, a redirect, and a stylesheet. */
  private final class Website extends HttpEndpoint("/web"):
    val acl: Acl = Acl.AllowAll
    // docs:start pages
    get("/account")(() => Respond(Html(page), headers = Vector("Set-Cookie" -> cookie)))
    post("/logout")(() => Respond.redirect("/")) // 303 See Other, Location: /
    get("/style.css")(() => Bytes("text/css", stylesheet))
    // docs:end pages

  private val router = Router(Vector(new Site, new Website), 5.seconds)

  private def call(
      path: String,
      method: HttpMethod = HttpMethods.GET
  ): (Int, String, Map[String, String], String) =
    val response =
      Await.result(router.handle(HttpRequest(method = method, uri = path)), 10.seconds)
    val body = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (
      response.status.intValue,
      body,
      response.headers.map(h => h.lowercaseName -> h.value).toMap,
      response.entity.contentType.value
    )

  test("Html is text/html with a charset") {
    val (status, body, _, contentType) = call("/site/page")
    assertEquals(status, 200)
    assertEquals(body, "<h1>hi</h1>")
    assertEquals(contentType, "text/html; charset=UTF-8")
  }

  test("Bytes carry their own content type") {
    val (_, body, _, contentType) = call("/site/style.css")
    assertEquals(body, "body{}")
    assertEquals(contentType, "text/css")
    assertEquals(call("/site/asset")._4, "image/svg+xml", "and inside a Respond")
  }

  test("a redirect is a 303 with a Location and no body") {
    val (status, body, headers, _) = call("/site/login")
    assertEquals(status, 303)
    assertEquals(body, "")
    assertEquals(headers.get("location"), Some("/elsewhere"))
  }

  test("headers reach the response beside the body, and the status is the handler's") {
    val (status, body, headers, contentType) = call("/site/session")
    assertEquals(status, 200)
    assertEquals(body, "<p>in</p>")
    assertEquals(contentType, "text/html; charset=UTF-8")
    assertEquals(headers.get("set-cookie"), Some("s=1; HttpOnly"))
    assertEquals(headers.get("x-trace"), Some("t"))
    assertEquals(call("/site/created")._1, 201)
  }

  test("a refusal carried as a cause answers as the refusal, not as a fault") {
    val (status, body, _, _) = call("/site/relayed")
    assertEquals(status, 404)
    assert(body.contains("\"gone\""), body)
  }

  test("a website's page keeps a session, logging out redirects, and a stylesheet is its bytes") {
    val (status, body, headers, contentType) = call("/web/account")
    assertEquals(status, 200)
    assertEquals(body, page)
    assertEquals(contentType, "text/html; charset=UTF-8")
    assertEquals(headers.get("set-cookie"), Some(cookie))
    val (redirected, _, to, _) = call("/web/logout", HttpMethods.POST)
    assertEquals(redirected, 303)
    assertEquals(to.get("location"), Some("/"))
    val (_, css, _, cssType) = call("/web/style.css")
    assertEquals(css, "h1{color:teal}")
    assertEquals(cssType, "text/css")
  }
