package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.controlplane.tenancy.{OrganizationCreation, OrganizationPolicy}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * A hosted installation, over real HTTP (feature 011, US1): organizations are the platform
 * administrator's to create. A registered user is refused with the reason and where to sign up, and
 * nothing is created; the administrator creates one for them; and from then on every route the user
 * is entitled to answers exactly as it would in an open installation.
 */
class OrganizationCreationPolicySuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private val SignupUrl = "https://ankka.cloud"

  private lazy val identity         = TestIdentity()
  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)
  private lazy val bob   = identity.token("bob", Some("bob@example.test"), expiresIn = 2.hours)
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
        auth = Some(identity.config()),
        policy = OrganizationPolicy(OrganizationCreation.PlatformAdmin, Some(SignupUrl))
      )*
    )
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

  test("1. a registered user cannot create an organization, and is told where to sign up") {
    val (status, body) = send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))
    assertEquals(status, 403, body)
    assert(
      body.contains("organizations in this installation are created by the platform administrator"),
      body
    )
    assert(body.contains(s"sign up at $SignupUrl"), body)
    assertEquals(send("GET", "/organizations/acme", carol)._1, 404, "nothing was created")
  }

  test("2. naming an owner without the role is refused for that reason, not the policy's") {
    val (status, body) = send(
      "POST",
      "/organizations/acme",
      alice,
      Some("""{"name":"Acme","owner":{"subject":"alice"}}""")
    )
    assertEquals(status, 403, body)
    assert(body.contains("platform administrator role required to name an owner"), body)
    assertEquals(send("GET", "/organizations/acme", carol)._1, 404)
  }

  test("3. the platform administrator creates one, for an owner and for themselves") {
    assertEquals(
      send(
        "POST",
        "/organizations/acme",
        carol,
        Some(
          """{"name":"Acme","owner":{"subject":"alice","email":"Alice@Example.test","display":"Alice"}}"""
        )
      )._1,
      204
    )
    assertEquals(send("POST", "/organizations/ops", carol, Some("""{"name":"Ops"}"""))._1, 204)
    val members = send("GET", "/organizations/ops/members", carol)._2
    assert(members.contains("\"subject\":\"carol\""), members)
  }

  test("4. the owner's every other route answers as in an open installation") {
    val listed = eventually("alice's listing shows acme") {
      val (_, body) = send("GET", "/organizations", alice)
      Option.when(body.contains("\"id\":\"acme\""))(body)
    }
    assert(listed.contains("\"role\":\"owner\""), listed)
    assert(!listed.contains("\"id\":\"ops\""), "not hers")
    assertEquals(send("GET", "/organizations/acme", alice)._1, 200)
    assertEquals(
      send("PUT", "/organizations/acme/name", alice, Some("""{"name":"Acme Corp"}"""))._1,
      204
    )
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"bob@example.test"}""")
      )._1,
      204
    )
    assertEquals(send("GET", "/organizations/acme/members", alice)._1, 200)
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
      send(
        "PUT",
        "/services/checkout/cart",
        alice,
        Some("""{"name":"cart","service":{"image":"cart:1.0"}}""")
      )._1,
      200
    )
    assertEquals(send("GET", "/services/checkout/cart", alice)._1, 200)
  }

  test("5. an invitation is claimed as in an open installation, and the member deploys") {
    val claimed = eventually("bob's listing shows acme") {
      val (_, body) = send("GET", "/organizations", bob)
      Option.when(body.contains("\"id\":\"acme\""))(body)
    }
    assert(claimed.contains("\"role\":\"member\""), claimed)
    assertEquals(send("POST", "/services/checkout/cart/pause", bob)._1, 200)
    assertEquals(
      send("POST", "/organizations/bobs", bob, Some("""{"name":"Bob's"}"""))._1,
      403,
      "and still cannot create one of his own"
    )
  }
