package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Tenancy as a boundary, over real HTTP (spec US2 and US3): two users, two organizations, a
 * platform administrator, and every route in contracts/http-api.md. What a non-member gets is the
 * *same* answer as for an id that never existed; removal bites on the very next request; an
 * invitation is claimed by a verified email on a refused write and on a listing, never by an
 * unverified one; the last owner stays; and a disabled organization refuses every change.
 *
 * Listings are views and are polled; enforcement reads entities and is asserted without a retry.
 */
class AuthorizationMatrixSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private lazy val identity         = TestIdentity()
  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)
  private lazy val bob   = identity.token("bob", Some("bob@example.test"), expiresIn = 2.hours)
  private lazy val dave =
    identity.token("dave", Some("dave@example.test"), emailVerified = false, expiresIn = 2.hours)
  private lazy val carol = identity.token(
    "carol",
    Some("carol@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 2.hours
  )

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default.copy(baseDomain = Some("example.test")),
        auth = Some(identity.config())
      )*
    )
    // The whole assembly, projector included: disabling an organization fans out to its services
    // through the projector, and the audit scenario reads that fan-out back.
    val projector = com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
      .withClient(DeployConfig.default.copy(sweepInterval = 1.second), new FakeAnkkaServiceClient)
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      token: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    builder.header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def eventually(description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[String]
  ): String =
    val deadline             = System.nanoTime() + within.toNanos
    var last: Option[String] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(200)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def descriptor(name: String) = s"""{"name":"$name","service":{"image":"cart:1.0"}}"""

  test(
    "1. creators own; listings are scoped; a non-member's read is indistinguishable from a missing id"
  ) {
    assertEquals(send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(send("POST", "/organizations/globex", bob, Some("""{"name":"Globex"}"""))._1, 204)

    val aliceList = eventually("alice's listing shows acme") {
      val (_, body) = send("GET", "/organizations", alice)
      Option.when(body.contains("\"id\":\"acme\""))(body)
    }
    assert(aliceList.contains("\"role\":\"owner\""), aliceList)
    assert(!aliceList.contains("globex"), "another organization's id leaked into a listing")
    val bobList = eventually("bob's listing shows globex") {
      val (_, body) = send("GET", "/organizations", bob)
      Option.when(body.contains("globex"))(body)
    }
    assert(!bobList.contains("acme"), bobList)

    val (status, missing) = send("GET", "/organizations/acme", bob)
    val (status2, never)  = send("GET", "/organizations/never-was", bob)
    assertEquals(status, 404)
    assertEquals(status2, 404)
    assertEquals(
      missing.replace("acme", "X"),
      never.replace("never-was", "X"),
      "existence must not leak"
    )
    assertEquals(send("GET", "/organizations/acme", alice)._1, 200)
  }

  test("2. projects and services are visible and writable to members only") {
    assertEquals(
      send(
        "POST",
        "/projects/checkout",
        alice,
        Some("""{"name":"Checkout","organizationId":"acme"}""")
      )._1,
      204
    )
    assertEquals(
      send("POST", "/projects/intruder", bob, Some("""{"name":"X","organizationId":"acme"}"""))._1,
      404,
      "creating into an organization you cannot see"
    )
    assertEquals(send("GET", "/projects/checkout", bob)._1, 404)
    assertEquals(send("GET", "/projects/checkout", alice)._1, 200)
    assertEquals(send("PUT", "/services/checkout/cart", bob, Some(descriptor("cart")))._1, 404)
    assertEquals(send("PUT", "/services/checkout/cart", alice, Some(descriptor("cart")))._1, 200)
    assertEquals(send("GET", "/services/checkout", bob)._1, 404)
    assertEquals(send("GET", "/services/checkout/cart", bob)._1, 404)
    val bobProjects = send("GET", "/projects", bob)._2
    assert(!bobProjects.contains("checkout"), bobProjects)
    assertEquals(
      send("GET", "/projects?organization=acme", bob),
      (200, "[]"),
      "a filter outside your organizations is empty, not refused"
    )
  }

  test(
    "3. an invitation is claimed by a verified email on a listing and on a refused write, never unverified"
  ) {
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"Bob@Example.test"}""")
      )._1,
      204
    )
    val members = send("GET", "/organizations/acme/members", alice)._2
    assert(members.contains("\"invitations\":[{\"email\":\"bob@example.test\""), members)

    // Bob's next listing claims it — once the invitation has reached the view.
    val claimed = eventually("bob's listing shows acme as member") {
      val (_, body) = send("GET", "/organizations", bob)
      Option.when(body.contains("\"id\":\"acme\"") && body.contains("\"role\":\"member\""))(body)
    }
    assert(claimed.contains("globex"), "and still his own")
    assertEquals(send("GET", "/projects/checkout", bob)._1, 200, "member now")
    assertEquals(send("GET", "/services/checkout/cart", bob)._1, 200)
    val after = send("GET", "/organizations/acme/members", alice)._2
    assert(after.contains("\"subject\":\"bob\""), after)
    assert(!after.contains("\"invitations\":[{"), "the invitation is spent")

    // Dave's email is not verified: the invitation stays pending and dave sees nothing.
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"dave@example.test"}""")
      )._1,
      204
    )
    assertEquals(send("GET", "/organizations/acme", dave)._1, 404)
    val daveList = send("GET", "/organizations", dave)._2
    assert(!daveList.contains("acme"), daveList)
    assertEquals(send("DELETE", "/organizations/acme/invitations/dave@example.test", alice)._1, 204)

    // Claim on a refused write: invite erin, whose first request is an apply.
    val erin = identity.token("erin", Some("erin@example.test"), expiresIn = 2.hours)
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"erin@example.test"}""")
      )._1,
      204
    )
    assertEquals(
      send("PUT", "/services/checkout/cart", erin, Some(descriptor("cart")))._1,
      200,
      "claimed on the way to a write"
    )
    val (_, whoami) = send("GET", "/auth/whoami", erin)
    assert(whoami.contains("\"id\":\"acme\"") || eventually("erin's whoami shows acme") {
      val (_, body) = send("GET", "/auth/whoami", erin)
      Option.when(body.contains("\"id\":\"acme\""))(body)
    }.nonEmpty)
  }

  test("4. a member may deploy but not manage members; removal takes effect on the next request") {
    assertEquals(
      send("POST", "/organizations/acme/members", bob, Some("""{"email":"x@example.test"}"""))._1,
      403
    )
    assertEquals(send("PUT", "/organizations/acme/name", bob, Some("""{"name":"Bobco"}"""))._1, 403)
    assertEquals(send("POST", "/services/checkout/cart/pause", bob)._1, 200)
    assertEquals(send("DELETE", "/organizations/acme/members/bob", alice)._1, 204)
    assertEquals(send("GET", "/projects/checkout", bob)._1, 404, "the very next request")
    assertEquals(send("POST", "/services/checkout/cart/resume", bob)._1, 404)
    assertEquals(send("POST", "/services/checkout/cart/resume", alice)._1, 200)
  }

  test("5. the last owner cannot leave; a second owner can take over") {
    assertEquals(send("DELETE", "/organizations/acme/members/alice", alice)._1, 409)
    assertEquals(
      send(
        "PUT",
        "/organizations/acme/members/alice/role",
        alice,
        Some("""{"role":"member"}""")
      )._1,
      409
    )
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"bob@example.test","role":"owner"}""")
      )._1,
      204
    )
    eventually("bob claims the owner invitation") {
      val (_, body) = send("GET", "/organizations", bob)
      Option.when(body.contains("\"id\":\"acme\""))(body)
    }
    assertEquals(
      send("PUT", "/organizations/acme/members/alice/role", bob, Some("""{"role":"member"}"""))._1,
      204
    )
    assertEquals(
      send("POST", "/organizations/acme/members", alice, Some("""{"email":"y@example.test"}"""))._1,
      403,
      "demoted"
    )
    assertEquals(
      send("DELETE", "/organizations/acme/members/bob", bob)._1,
      409,
      "bob is the last owner now"
    )
    assertEquals(
      send("PUT", "/organizations/acme/members/alice/role", bob, Some("""{"role":"owner"}"""))._1,
      204
    )
  }

  test(
    "6. a platform administrator sees everything, repairs, and disables; owners cannot disable"
  ) {
    val all = eventually("carol lists every organization") {
      val (_, body) = send("GET", "/organizations", carol)
      Option.when(body.contains("\"id\":\"acme\"") && body.contains("\"id\":\"globex\""))(body)
    }
    assert(!all.contains("\"role\":\"owner\"") || all.contains("globex"), all)
    assertEquals(send("POST", "/organizations/acme/disable", alice)._1, 403, "an owner may not")
    assertEquals(
      send(
        "POST",
        "/organizations/globex/members/erin/repair",
        carol,
        Some("""{"role":"owner"}""")
      )._1,
      204
    )
    assertEquals(
      send(
        "POST",
        "/organizations/globex/members/erin/repair",
        alice,
        Some("""{"role":"owner"}""")
      )._1,
      403
    )

    assertEquals(send("POST", "/organizations/acme/disable", carol)._1, 204)
    assertEquals(send("POST", "/organizations/acme/disable", carol)._1, 409, "already disabled")
    val (status, detail) = send("GET", "/organizations/acme", alice)
    assertEquals(status, 200)
    assert(detail.contains("\"disabled\":true"), detail)
    val (refused, why) =
      send("PUT", "/organizations/acme/name", alice, Some("""{"name":"Acme Ltd"}"""))
    assertEquals(refused, 409)
    assert(why.contains("organization 'acme' is disabled"), why)
    assertEquals(
      send("POST", "/projects/another", alice, Some("""{"name":"A","organizationId":"acme"}"""))._1,
      409
    )
    assertEquals(send("PUT", "/services/checkout/cart", alice, Some(descriptor("cart")))._1, 409)
    assertEquals(send("POST", "/services/checkout/cart/resume", alice)._1, 409)
    assertEquals(send("GET", "/services/checkout/cart", alice)._1, 200, "reads still work")
    assertEquals(send("GET", "/organizations/acme/members", alice)._1, 200)
    assertEquals(
      send("POST", "/organizations/acme/members", alice, Some("""{"email":"z@example.test"}"""))._1,
      409
    )

    assertEquals(send("POST", "/organizations/acme/enable", carol)._1, 204)
    assertEquals(
      send("PUT", "/organizations/acme/name", alice, Some("""{"name":"Acme Ltd"}"""))._1,
      204
    )
    assertEquals(send("POST", "/organizations/acme/enable", carol)._1, 409)
  }

  test("7. the audit trail names who did what, and whether it took an administrator (US5)") {
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        bob,
        Some("""{"email":"frank@example.test"}""")
      )._1,
      204
    )
    val history = eventually("the disable reached the service's history") {
      val (status, body) = send("GET", "/services/checkout/cart/history", alice)
      Option.when(status == 200 && body.contains("\"kind\":\"suspended\""))(body)
    }
    assert(
      history.contains("\"kind\":\"applied\"") && history.contains("\"subject\":\"alice\""),
      history
    )
    assert(
      history.contains("\"kind\":\"paused\"") && history.contains("\"subject\":\"bob\""),
      history
    )
    assert(
      history.contains("\"kind\":\"suspended\""),
      "the administrator's disable reached the service"
    )
    assert(history.contains("\"administrative\":true"), history)
    assert(history.contains("\"at\":\""), "every entry has a time")
    assertEquals(
      send("GET", "/services/checkout/cart/history", dave)._1,
      404,
      "history is scoped like everything else"
    )
    val members = send("GET", "/organizations/acme/members", alice)._2
    assert(members.contains("\"invitedBy\":\"bob@example.test\""), members)
  }
