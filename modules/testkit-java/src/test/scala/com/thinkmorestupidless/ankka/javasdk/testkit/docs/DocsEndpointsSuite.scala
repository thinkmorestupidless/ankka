package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, TestIssuer}
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.bridge.TestAcls
import com.thinkmorestupidless.ankka.javasdk.http.Caller
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

/** The Java samples of the HTTP endpoints page, served by one service in the Java kit. */
class DocsEndpointsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit  = null
  private var issuer: TestIssuer = null

  override def beforeAll(): Unit =
    issuer = TestIssuer.serving("customers", "shop")
    TestAcls.hold(Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer()))))
    AccountRoutes.rule = () => TestAcls.tokens()
    kit = AnkkaTestKit.start(
      Ankka
        .service("site")
        .withEndpoint(classOf[SiteRoutes])
        .withEndpoint(classOf[SearchRoutes])
        .withEndpoint(classOf[GatedRoutes])
        .withEndpoint(classOf[AccountRoutes])
        .withEndpoint(classOf[CallerRoutes])
        .withEndpoint(classOf[SelfRoutes])
        .withEndpoint(classOf[WalletRoutes])
    )

  override def afterAll(): Unit =
    try if kit != null then kit.close()
    finally if issuer != null then issuer.stop()

  private def send(
      method: String,
      path: String,
      body: String = "",
      headers: (String, String)*
  ): HttpResponse[String] =
    val request = HttpRequest
      .newBuilder(kit.http.resolve(path))
      .method(method, HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => request.header(k, v))
    kit.httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())

  private def header(response: HttpResponse[String], name: String) =
    Option(response.headers.firstValue(name).orElse(null))

  test("a page carries its cookie, a logout redirects, and bytes name their own content type") {
    val page = send("GET", "/site/account")
    assertEquals(page.statusCode, 200)
    assert(header(page, "content-type").exists(_.startsWith("text/html")), page.headers.toString)
    assertEquals(header(page, "set-cookie"), Some(SiteRoutes.COOKIE))
    val logout = send("POST", "/site/logout")
    assertEquals(logout.statusCode, 303)
    assertEquals(header(logout, "location"), Some("/"))
    val css = send("GET", "/site/style.css")
    assert(header(css, "content-type").exists(_.startsWith("text/css")), css.headers.toString)
    assertEquals(css.body, "h1 { color: teal }")
  }

  test("query parameters are required, defaulted, repeated and flags; a missing one is a 400") {
    val found = send("GET", "/search?q=pens&tag=a&tag=b&verbose")
    assertEquals(found.statusCode, 200, found.body)
    assertEquals(
      found.body,
      """{"term":"pens","limit":20,"tags":["a","b"],"verbose":true}"""
    )
    val missing = send("GET", "/search")
    assertEquals(missing.statusCode, 400)
    assert(missing.body.contains("'q'"), missing.body)
  }

  test("a header is read by name, or defaulted") {
    assertEquals(send("GET", "/search/trace", "", "X-Trace-Id" -> "t-1").body, "t-1")
    assertEquals(send("GET", "/search/trace").body, "none")
  }

  test("a streaming route reads its parameters before the stream is pulled") {
    val stream = send("GET", "/search/stream?q=x&count=3")
    assertEquals(stream.statusCode, 200)
    assert(stream.body.contains("data:\"x-3\""), stream.body)
  }

  test("an ACL that inspects the request admits the key or the flag, and refuses the rest") {
    assertEquals(send("GET", "/gated").statusCode, 403)
    assertEquals(send("GET", "/gated", "", "X-Api-Key" -> "let-me-in").statusCode, 200)
    assertEquals(send("GET", "/gated?public").body, "allowed")
  }

  test("a verified token reaches the handler as its principal; no token is a 401") {
    val token = issuer.token("ada", roles = Set("buyer"), claims = Map("tier" -> "gold"))
    val me    = send("GET", "/account/me", "", "Authorization" -> s"Bearer $token")
    assertEquals(me.statusCode, 200, me.body)
    assertEquals(me.body, "ada from customers roles=buyer tier=gold")
    assertEquals(send("GET", "/account/me").statusCode, 401)
  }

  test("the orders service is admitted and the payments service is not") {
    assertEquals(
      CallerChecks.statusAs(kit, "/callers/only-orders", Caller.Service("local", "orders")),
      200
    )
    assertEquals(
      CallerChecks.statusAs(kit, "/callers/only-orders", Caller.Service("local", "payments")),
      403
    )
    assertEquals(CallerChecks.statusAs(kit, "/callers/only-self", Caller.Gateway()), 403)
    assertEquals(send("GET", "/callers/only-self").body, "admitted: myself")
  }

  test("the caller is read by the handler") {
    val asCaller = kit.asCaller(Caller.Gateway())
    assertEquals(
      send("GET", "/callers/whoami", "", asCaller.getKey -> asCaller.getValue).body,
      "the internet"
    )
    assertEquals(send("GET", "/callers/whoami").body, "this machine")
  }

  test("a granted route reads the idempotency key, and refuses a deposit without one") {
    val made = send(
      "POST",
      "/v1/wallets/ada/EUR/deposits",
      """{"amount":5}""",
      "Content-Type"    -> "application/json",
      "Idempotency-Key" -> "k-1"
    )
    assertEquals(made.statusCode, 200, made.body)
    assertEquals(made.body, """{"key":"k-1","currency":"EUR","amount":5,"by":"this machine"}""")
    val refused = send(
      "POST",
      "/v1/wallets/ada/EUR/deposits",
      """{"amount":5}""",
      "Content-Type" -> "application/json"
    )
    assertEquals(refused.statusCode, 400)
  }
