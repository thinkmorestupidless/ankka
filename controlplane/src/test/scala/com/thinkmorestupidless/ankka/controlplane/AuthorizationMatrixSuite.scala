package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
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
class AuthorizationMatrixSuite extends munit.FunSuite with LogCapturing:

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

  /**
   * A deploy token is a fifth actor in the matrix (feature 013), and must obey every rule above.
   */
  private lazy val tokens = new DeployTokenIndex(identity.clock)

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        DeployConfig.default.copy(baseDomain = Some("example.test")),
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens)
      )*
    )
    // The whole assembly, projector included: disabling an organization fans out to its services
    // through the projector, and the audit scenario reads that fan-out back.
    val projector = com.thinkmorestupidless.ankka.controlplane.deploy.ServiceProjector
      .withClient(DeployConfig.default.copy(sweepInterval = 1.second), new FakeAnkkaServiceClient)
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server, tokens)
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
    // Feature 033: a service of a disabled organization cannot be rolled back.
    val (rolled, rolledWhy) =
      send("POST", "/services/checkout/cart/rollback", alice, Some("""{"generation":1}"""))
    assertEquals(rolled, 409, rolledWhy)
    assert(rolledWhy.contains("organization 'acme' is disabled"), rolledWhy)
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

  test("8. an administrator creates an organization for its owner in one request (feature 011)") {
    val body =
      """{"name":"Wonderland","owner":""" +
        """{"subject":"alice","email":"alice@example.test","display":"Alice"}}"""
    assertEquals(send("POST", "/organizations/wonderland", carol, Some(body))._1, 204)

    val members = send("GET", "/organizations/wonderland/members", alice)._2
    assert(members.contains("\"subject\":\"alice\""), members)
    assert(members.contains("\"role\":\"owner\""), members)
    assert(members.contains("\"display\":\"Alice\""), members)
    assert(members.contains("\"addedBy\":\"carol\""), members)
    assert(!members.contains("\"subject\":\"carol\""), "the administrator is not a member")

    val asAdmin = send("GET", "/organizations/wonderland", carol)._2
    assert(!asAdmin.contains("\"role\""), s"carol has no role there: $asAdmin")

    val listed = eventually("alice's listing shows wonderland as its owner") {
      val (_, body) = send("GET", "/organizations", alice)
      Option.when(body.contains("\"id\":\"wonderland\""))(body)
    }
    assert(listed.contains("\"role\":\"owner\""), listed)
    assertEquals(
      send(
        "PUT",
        "/organizations/wonderland/name",
        alice,
        Some("""{"name":"Wonderland Ltd"}""")
      )._1,
      204
    )
  }

  test("9. naming an owner takes the administrator role; naming none makes the admin owner") {
    val (status, refusal) = send(
      "POST",
      "/organizations/bobs-for-alice",
      bob,
      Some("""{"name":"X","owner":{"subject":"alice"}}""")
    )
    assertEquals(status, 403, refusal)
    assert(refusal.contains("platform administrator role required to name an owner"), refusal)
    assertEquals(send("GET", "/organizations/bobs-for-alice", carol)._1, 404, "nothing created")

    assertEquals(
      send("POST", "/organizations/carols", carol, Some("""{"name":"Carol's"}"""))._1,
      204
    )
    val members = send("GET", "/organizations/carols/members", carol)._2
    assert(members.contains("\"subject\":\"carol\""), members)
  }

  test("10. a deploy token is a member and obeys every rule a member does (feature 013)") {
    // `acme` is alice's, from case 1; `globex` is bob's. A token of acme must be exactly as
    // powerful as a member of acme and exactly as blind to globex as a stranger.
    val created = send("POST", "/organizations/acme/tokens", alice, Some("""{"label":"ci"}"""))
    assertEquals(created._1, 200, created._2)
    val marker = "\"secret\":\""
    val from   = created._2.indexOf(marker) + marker.length
    val secret = created._2.substring(from, created._2.indexOf('"', from))
    val idFrom = created._2.indexOf("\"id\":\"") + 6
    val id     = created._2.substring(idFrom, created._2.indexOf('"', idFrom))

    // Member-level: reads and project work.
    assertEquals(send("GET", "/organizations/acme", secret)._1, 200)
    assertEquals(
      send(
        "POST",
        "/projects/acme-ci",
        secret,
        Some("""{"name":"CI","organizationId":"acme"}""")
      )._1,
      204
    )

    // Owner-level: refused, including managing tokens — a leaked CI credential cannot mint a
    // replacement for itself or revoke the one that would stop it.
    assertEquals(send("PUT", "/organizations/acme/name", secret, Some("""{"name":"X"}"""))._1, 403)
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        secret,
        Some("""{"email":"x@example.test"}""")
      )._1,
      403
    )
    assertEquals(send("GET", "/organizations/acme/tokens", secret)._1, 403)
    assertEquals(send("DELETE", s"/organizations/acme/tokens/$id", secret)._1, 403)

    // Another organization is 404, exactly as it is for a stranger — never 403.
    assertEquals(send("GET", "/organizations/globex", secret)._1, 404)
    assertEquals(send("GET", "/organizations/globex", dave)._1, 404)

    // Administrative routes are not a token's either, whatever its organization.
    assertEquals(send("POST", "/organizations/acme/disable", secret)._1, 403)

    // It shows up as a member, labelled, so an owner can see what has access.
    val listed = send("GET", "/organizations/acme/members", alice)._2
    assert(listed.contains(s"token:$id"), listed)
    assert(listed.contains("\"display\":\"ci\""), listed)

    // Removing the *membership* by hand is enough to stop it, without touching the token: the
    // authorization path is the members list, exactly as it is for a person.
    assertEquals(send("DELETE", s"/organizations/acme/members/token:$id", alice)._1, 204)
    assertEquals(send("GET", "/organizations/acme", secret)._1, 404)
  }

  test(
    "11. a quota is the platform administrator's to set and clear, nobody else's (feature 015)"
  ) {
    val body = Some("""{"projects":1}""")
    // An owner is refused as forbidden, and so is a stranger — for the stranger the organization
    // is 404 elsewhere, but the administrative routes refuse the role before looking at anything.
    assertEquals(send("PUT", "/organizations/acme/quota", alice, body)._1, 403)
    assertEquals(send("DELETE", "/organizations/acme/quota", alice)._1, 403)
    assertEquals(send("PUT", "/organizations/acme/quota", bob, body)._1, 403)
    assert(!send("GET", "/organizations/acme", alice)._2.contains("\"quota\""), "unchanged")

    assertEquals(send("PUT", "/organizations/acme/quota", carol, body)._1, 204)
    assert(send("GET", "/organizations/acme", alice)._2.contains("\"quota\":{\"projects\":1}"))
    assertEquals(send("PUT", "/organizations/nowhere/quota", carol, body)._1, 404)
    assertEquals(send("DELETE", "/organizations/acme/quota", carol)._1, 204)
    assert(!send("GET", "/organizations/acme", alice)._2.contains("\"quota\""), "cleared")
  }

  // ── a deploy token scoped to one project (spec 057) ──────────────────────

  private def field(body: String, name: String): String =
    val marker = s"\"$name\":\""
    val from   = body.indexOf(marker)
    if from < 0 then fail(s"no $name in $body")
    body.substring(from + marker.length, body.indexOf('"', from + marker.length))

  /** The project token for `orders` of `acme`: made by test 12, used by the rest. */
  private var projectToken: String = ""

  private def createProject(id: String, organization: String, as: String): Unit =
    val (status, body) =
      send(
        "POST",
        s"/projects/$id",
        as,
        Some(s"""{"name":"$id","organizationId":"$organization"}""")
      )
    assertEquals(status, 204, body)

  private def grantBody(grantee: String, service: String) =
    Some(
      s"""{"grantee":"$grantee","target":{"kind":"route","service":"$service","method":"POST","path":"/v1/orders"}}"""
    )

  test(
    "12. a deploy token scoped to one project acts in that project and is told there is no other"
  ) {
    createProject("orders", "acme", alice)
    createProject("payments", "acme", alice)
    createProject("billing", "acme", alice)
    createProject("globex-shop", "globex", bob)

    // The project must be one of this organization's; any other is answered as a stranger is.
    val elsewhere = send(
      "POST",
      "/organizations/acme/tokens",
      alice,
      Some("""{"label":"agent","project":"globex-shop"}""")
    )
    assertEquals(elsewhere._1, 404, elsewhere._2)
    assert(elsewhere._2.contains("no such project 'globex-shop'"), elsewhere._2)
    assertEquals(
      send(
        "POST",
        "/organizations/acme/tokens",
        alice,
        Some("""{"label":"a","project":"never"}""")
      )._1,
      404
    )

    val (status, created) = send(
      "POST",
      "/organizations/acme/tokens",
      alice,
      Some("""{"label":"agent","project":"orders"}""")
    )
    assertEquals(status, 200, created)
    assert(created.contains("\"project\":\"orders\""), created)
    projectToken = field(created, "secret")

    // Its project: a service applied, attributed to the token.
    val applied = send("PUT", "/services/orders/cart", projectToken, Some(descriptor("cart")))
    assertEquals(applied._1, 200, applied._2)
    val history = send("GET", "/services/orders/cart/history", alice)
    assertEquals(history._1, 200, history._2)
    assert(history._2.contains("agent"), history._2)
    assert(!history._2.contains("alice"), history._2)

    // Another project of the same organization does not exist for it.
    for (method, path) <- Vector(
        "GET"    -> "/projects/billing",
        "GET"    -> "/services/billing",
        "GET"    -> "/projects/billing/grants",
        "DELETE" -> "/projects/billing"
      )
    do
      val (code, body) = send(method, path, projectToken)
      assertEquals(code, 404, s"$method $path: $body")
      assert(body.contains("no such project 'billing'"), body)
    assertEquals(
      send("PUT", "/services/billing/cart", projectToken, Some(descriptor("cart")))._1,
      404
    )

    // Its listing of projects is its one project.
    val listed = eventually("the token's listing shows orders") {
      val (_, body) = send("GET", "/projects", projectToken)
      Option.when(body.contains("\"id\":\"orders\""))(body)
    }
    assert(!listed.contains("billing"), listed)
    assert(!listed.contains("payments"), listed)
  }

  test("13. a project-scoped deploy token makes and ends its project's grants") {
    val (status, made) =
      send(
        "POST",
        "/projects/orders/grants",
        projectToken,
        grantBody("service:payments/merchant", "cart")
      )
    assertEquals(status, 200, made)
    assert(made.contains("\"state\":\"accepted\""), made)
    assert(made.contains("\"by\":\"agent\""), made)
    val grantId = field(made, "id")
    assertEquals(send("DELETE", s"/projects/orders/grants/$grantId", projectToken)._1, 204)
    val ended = send("GET", "/projects/orders/grants", alice)._2
    assert(ended.contains("\"state\":\"revoked\""), ended)

    // A grant to another organization waits for an owner there, as any such grant does.
    val pending =
      send(
        "POST",
        "/projects/orders/grants",
        projectToken,
        grantBody("service:globex-shop/x", "cart")
      )
    assertEquals(pending._1, 200, pending._2)
    assert(pending._2.contains("\"state\":\"pending\""), pending._2)

    // Not another project's grants; and a deploy token of the whole organization makes none.
    assertEquals(
      send(
        "POST",
        "/projects/billing/grants",
        projectToken,
        grantBody("service:payments/m", "cart")
      )._1,
      404
    )
    val wide = field(
      send("POST", "/organizations/acme/tokens", alice, Some("""{"label":"wide"}"""))._2,
      "secret"
    )
    assertEquals(
      send(
        "POST",
        "/projects/orders/grants",
        wide,
        grantBody("service:payments/merchant", "cart")
      )._1,
      403
    )
  }

  test("14. a project-scoped deploy token may not do what only an owner may") {
    // A grant offered to acme by globex: the token may not accept it, an owner of acme may.
    val (status, offered) =
      send("POST", "/projects/globex-shop/grants", bob, grantBody("service:orders/cart", "shop"))
    assertEquals(status, 200, offered)
    val grantId = field(offered, "id")
    eventually("the grant is offered to acme") {
      val (_, body) = send("GET", "/organizations/acme/grants", alice)
      Option.when(body.contains(grantId))(body)
    }
    val refusals = Vector(
      ("POST", "/organizations/acme/tokens", Some("""{"label":"x"}""")),
      ("POST", "/organizations/acme/members", Some("""{"email":"x@example.test"}""")),
      ("POST", s"/organizations/acme/grants/$grantId/accept", None),
      ("POST", "/projects/sideways", Some("""{"name":"S","organizationId":"acme"}""")),
      ("POST", "/organizations/token-made", Some("""{"name":"T"}""")),
      ("GET", "/organizations/acme", None),
      ("GET", "/organizations/acme/members", None)
    )
    for (method, path, body) <- refusals do
      val (code, answer) = send(method, path, projectToken, body)
      assertEquals(code, 403, s"$method $path: $answer")
    assertEquals(send("POST", s"/organizations/acme/grants/$grantId/accept", alice)._1, 204)
  }

  /** Every route of the control plane API, as `ControlPlaneRoutesReferenceSuite` lists them. */
  private def routeTable: Vector[(String, String)] =
    import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, MethodName}
    val unused = new com.thinkmorestupidless.ankka.sdk.CallTransport:
      def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
        scala.concurrent.Future.failed(IllegalStateException("not called while listing routes"))
      def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()
      def askTimeout                                            = 1.second
    val clients = new com.thinkmorestupidless.ankka.http.EndpointClients(
      com.thinkmorestupidless.ankka.sdk.ComponentClient(unused),
      new com.thinkmorestupidless.ankka.runtime.ViewClient(null, 1.second)(using null)
    )
    ControlPlane
      .endpoints(com.thinkmorestupidless.ankka.http.Acl.DenyAll, auth = Some(identity.config()))
      .map(_(clients))
      .toVector
      .flatMap(e =>
        e.routes.map(r => (r.method, e.prefix + r.template.render)) ++
          e.streamRoutes.map(r => (r.method, e.prefix + r.template.render))
      )

  /** A body each body-carrying route decodes, so the walk reaches the check, not the decoder. */
  private val bodies: Map[(String, String), String] = Map(
    ("POST", "/organizations/{organizationId}")         -> """{"name":"X"}""",
    ("PUT", "/organizations/{organizationId}/name")     -> """{"name":"Acme"}""",
    ("POST", "/organizations/{organizationId}/members") -> """{"email":"x@example.test"}""",
    ("PUT", "/organizations/{organizationId}/members/{subject}/role")    -> """{"role":"owner"}""",
    ("POST", "/organizations/{organizationId}/members/{subject}/repair") -> "{}",
    ("POST", "/organizations/{organizationId}/machines")                 -> """{"name":"walker"}""",
    ("PUT", "/organizations/{organizationId}/machines/{name}/byte-rates") ->
      """{"produceBytesPerSecond":1,"consumeBytesPerSecond":1,"requestPercentage":1}""",
    ("POST", "/organizations/{organizationId}/tokens") -> """{"label":"walker"}""",
    ("PUT", "/organizations/{organizationId}/quota")   -> """{"projects":9}""",
    ("POST", "/projects/{projectId}")     -> """{"name":"X","organizationId":"acme"}""",
    ("PUT", "/projects/{projectId}/name") -> """{"name":"Orders"}""",
    ("PUT", "/projects/{projectId}/registry") ->
      """{"server":"ghcr.io","username":"walker","password":"walker"}""",
    ("PUT", "/projects/{projectId}/secrets/{name}") -> """{"entries":{"k":"v"}}""",
    ("PUT", "/projects/{projectId}/topics/{name}")  -> "{}",
    ("PUT", "/projects/{projectId}/brokers/{name}") ->
      """{"bootstrap":"broker:9092","shape":"plain","secret":"nothing"}""",
    ("PUT", "/projects/{projectId}/location")             -> """{"location":"nothing"}""",
    ("POST", "/services/{projectId}/{name}/storage/move") -> "{}",
    ("POST", "/projects/{projectId}/grants") ->
      """{"grantee":"service:payments/merchant","target":{"kind":"erasure"}}""",
    ("PUT", "/services/{projectId}/{name}")           -> descriptor("nothing"),
    ("POST", "/projects/{projectId}/restores")        -> """{"moment":"2026-01-01T00:00:00Z"}""",
    ("POST", "/projects/{projectId}/rehearsals")      -> "{}",
    ("PUT", "/projects/{projectId}/database")         -> """{"cluster":"nothing"}""",
    ("POST", "/services/{projectId}/{name}/switch")   -> """{"cluster":"nothing"}""",
    ("POST", "/services/{projectId}/{name}/rollback") -> "{}"
  )

  private def walk(token: String, project: String): Vector[(String, String, Int, String)] =
    routeTable
      .filterNot(_ == ("DELETE", "/projects/{projectId}"))
      .map { (method, template) =>
        val path = template
          .replace("{projectId}", project)
          .replace("{organizationId}", "acme")
          .replaceAll("\\{[A-Za-z]+\\}", "nothing")
        val (code, body) = send(method, path, token, bodies.get((method, template)))
        (method, template, code, body)
      }

  private def refused(code: Int, body: String): Boolean =
    code == 403 ||
      (code == 404 && (body.contains("no such project") || body.contains("no such organization")))

  test(
    "15. walking every route, a project token reaches its project's routes and its grant routes, and no other"
  ) {
    // An organization-wide token is the member a project token is compared with, route by route.
    val member = field(
      send("POST", "/organizations/acme/tokens", alice, Some("""{"label":"member"}"""))._2,
      "secret"
    )
    val grantRoutes = Set(
      ("POST", "/projects/{projectId}/grants"),
      ("DELETE", "/projects/{projectId}/grants/{grantId}")
    )
    def aboveTheProject(method: String, template: String) =
      template.startsWith("/organizations/{organizationId}") ||
        (method == "POST" && template == "/projects/{projectId}")

    val asMember = walk(member, "orders").map(r => (r._1, r._2) -> r).toMap
    val wrong = walk(projectToken, "orders").flatMap { (method, template, code, body) =>
      val (_, _, memberCode, memberBody) = asMember((method, template))
      val expected =
        if grantRoutes((method, template)) then false
        else if aboveTheProject(method, template) then true
        else refused(memberCode, memberBody)
      Option.when(refused(code, body) != expected)(
        s"$method $template: $code ${body.take(160)} (expected refused=$expected; member $memberCode)"
      )
    }
    assert(wrong.isEmpty, wrong.mkString("\n"))
    assert(grantRoutes.forall(asMember.contains), "the walk covers both grant routes")

    // Another project of the organization: every route under it is refused.
    val leaked = walk(projectToken, "billing").flatMap { (method, template, code, body) =>
      val underAProject = template.startsWith("/projects/{projectId}") ||
        template.startsWith("/services/{projectId}")
      Option.when(underAProject && !refused(code, body))(
        s"$method $template: $code ${body.take(160)}"
      )
    }
    assert(leaked.isEmpty, leaked.mkString("\n"))
  }

  test(
    "16. a project token whose project was deleted reaches nothing, and stays listed until revoked"
  ) {
    createProject("scratch", "acme", alice)
    val created = send(
      "POST",
      "/organizations/acme/tokens",
      alice,
      Some("""{"label":"scratcher","project":"scratch"}""")
    )._2
    val secret = field(created, "secret")
    val id     = field(created, "id")
    assertEquals(send("GET", "/projects/scratch", secret)._1, 200)
    assertEquals(send("DELETE", "/projects/scratch", alice)._1, 204)
    val (code, body) = send("GET", "/projects/scratch", secret)
    assertEquals(code, 404, body)
    assert(body.contains("no such project 'scratch'"), body)
    assertEquals(send("PUT", "/services/scratch/cart", secret, Some(descriptor("cart")))._1, 404)
    eventually("the token is listed with the project it was scoped to") {
      val (_, listing) = send("GET", "/organizations/acme/tokens", alice)
      Option.when(
        listing.contains(s"\"id\":\"$id\"") && listing.contains("\"project\":\"scratch\"")
      )(
        listing
      )
    }
    assertEquals(send("DELETE", s"/organizations/acme/tokens/$id", alice)._1, 204)
    eventually("a revoked token leaves the listing") {
      val (_, listing) = send("GET", "/organizations/acme/tokens", alice)
      Option.when(!listing.contains(s"\"id\":\"$id\""))(listing)
    }
  }
