package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, RegistryWriter}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The control plane end to end, over real HTTP against real Postgres.
 *
 * Boots from `ControlPlane.components` and `ControlPlane.endpoints` — the same values
 * `ControlPlane.builder` uses — so this exercises the assembly that ships rather than a test-only
 * arrangement of it.
 *
 * Listings are asynchronous because they are views. Every assertion against one polls to a
 * deadline; that is the consistency model, not a flake being papered over.
 */
class ControlPlaneHttpSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  // A real issuer is not needed to prove anything here: an in-process one mints tokens the
  // verifier accepts, and KeycloakRealmSuite is where real ones are read.
  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)

  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /**
   * This node's deploy token index (feature 013).
   *
   * Driven by the suite's mutable clock, so a case can advance past a token's expiry; registered as
   * an extension below, so it replays the token journal exactly as a deployed node does.
   */
  private lazy val tokens = new DeployTokenIndex(identity.clock)

  private val deployConfig = DeployConfig.default.copy(baseDomain = Some("example.test"))

  /**
   * An in-memory cluster, so a registry credential can be followed all the way to where it lands
   * (feature 013).
   *
   * The fake stands in for the projector's client and records the password, which is the only way
   * to assert the thing that matters: that it reached the *cluster* and appears in no reply, no
   * journal and no listing.
   */
  private lazy val cluster = new FakeAnkkaServiceClient

  private lazy val registryWriter: RegistryWriter =
    (projectId, server, username, password) =>
      cluster.ensurePullSecret(deployConfig.namespaceFor(projectId), server, username, password)

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.composite(tokens, identity.acl(), identity.config()),
        deployConfig,
        auth = Some(identity.config()),
        clock = identity.clock,
        tokens = Some(tokens),
        registry = Some(registryWriter)
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server, tokens))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      token: Option[String] = Some(Token)
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    token.foreach(value => builder.header("Authorization", s"Bearer $value"): Unit)
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None =>
        builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** Polls until `check` holds or the deadline passes. */
  private def eventually(description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[String]
  ): String =
    val deadline             = System.nanoTime() + within.toNanos
    var last: Option[String] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(200)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def descriptor(name: String, image: String) =
    s"""{"name":"$name","service":{"image":"$image"}}"""

  private def sendRaw(path: String, token: Option[String]): JdkResponse[String] =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    token.foreach(value => builder.header("Authorization", s"Bearer $value"): Unit)
    http.send(builder.GET().build(), JdkResponse.BodyHandlers.ofString())

  test("GET /auth needs no credential and reveals only where to log in (US1)") {
    val (status, body) = send("GET", "/auth", token = None)
    assertEquals(status, 200)
    assert(body.contains(s"\"issuer\":\"${identity.issuer}\""), body)
    assert(body.contains("\"clientId\":\"ankka-cli\""), body)
    assert(body.contains("\"audience\":\"ankka-controlplane\""), body)
    assert(!body.contains("jwks"), "the key URL is the control plane's business, not the CLI's")
  }

  test("GET /auth/whoami needs a credential and echoes the principal") {
    assertEquals(sendRaw("/auth/whoami", None).statusCode, 401)
    val (status, body) = send("GET", "/auth/whoami")
    assertEquals(status, 200)
    assert(body.contains("\"subject\":\"tester\""), body)
    assert(body.contains("\"email\":\"tester@example.test\""), body)
    // Defaults are omitted on the wire (false, empty), and the CLI's codec fills them back in.
    assert(!body.contains("\"platformAdmin\":true"), body)
  }

  test("every kind of bad credential is 401 with a challenge that says why (S1.2, S1.7)") {
    def challenge(token: Option[String]): (Int, String) =
      val response = sendRaw("/organizations", token)
      (response.statusCode, response.headers.firstValue("WWW-Authenticate").orElse(""))
    val (noneStatus, noneChallenge) = challenge(None)
    assertEquals(noneStatus, 401)
    assertEquals(noneChallenge, "Bearer realm=\"ankka\"")
    val expired = challenge(
      Some(identity.token("tester", expiresIn = scala.concurrent.duration.Duration(-2, "min")))
    )
    assert(expired._1 == 401 && expired._2.contains("error=\"invalid_token\""), expired.toString)
    val elsewhere = challenge(Some(identity.token("tester", issuer = "https://elsewhere/realms/x")))
    assert(elsewhere._1 == 401 && elsewhere._2.contains("invalid_token"), elsewhere.toString)
    val otherAudience = challenge(Some(identity.token("tester", audience = Seq("account"))))
    assert(
      otherAudience._1 == 401 && otherAudience._2.toLowerCase.contains("aud"),
      otherAudience.toString
    )
    val shared = challenge(Some("dev-local-token"))
    assert(shared._1 == 401 && shared._2.contains("run 'ankka login'"), shared.toString)
  }

  test("health needs no token, everything else does") {
    assertEquals(send("GET", "/_ankka/health", token = None), (200, "ok"))

    val (noToken, _) = send("GET", "/organizations", token = None)
    assertEquals(noToken, 401, "no credential is 'log in', never 'forbidden'")

    val (wrongToken, _) = send("GET", "/organizations", token = Some("nope"))
    assertEquals(wrongToken, 401)
  }

  test("an organization can be created and read back by id") {
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme Corp"}"""))._1, 204)

    val (status, body) = send("GET", "/organizations/acme")
    assertEquals(status, 200)
    assert(body.contains("\"name\":\"Acme Corp\""), body)
    assert(body.contains("\"projects\":0"), body)
  }

  test("creating an organization twice is a 409") {
    val _ = send("POST", "/organizations/dup", Some("""{"name":"Dup"}"""))
    assertEquals(send("POST", "/organizations/dup", Some("""{"name":"Dup"}"""))._1, 409)
  }

  test("a project in an unknown organization is a 404, not a silent orphan") {
    val (status, body) =
      send("POST", "/projects/orphan", Some("""{"name":"Orphan","organizationId":"ghost"}"""))
    assertEquals(status, 404, body)
    assert(body.contains("no such organization 'ghost'"), body)
  }

  test("a project is created under its organization") {
    assertEquals(
      send(
        "POST",
        "/projects/checkout",
        Some("""{"name":"Checkout","organizationId":"acme"}""")
      )._1,
      204
    )

    val (status, body) = send("GET", "/projects/checkout")
    assertEquals(status, 200)
    assert(body.contains("\"organizationId\":\"acme\""), body)
    assert(body.contains("\"services\":0"), body)
  }

  test("applying a descriptor to an unknown project is a 404") {
    val (status, body) =
      send("PUT", "/services/ghost/cart", Some(descriptor("cart", "cart:1.0")))
    assertEquals(status, 404, body)
    assert(body.contains("no such project 'ghost'"), body)
  }

  test("applying a descriptor returns the status it produced") {
    val (status, body) =
      send("PUT", "/services/checkout/cart", Some(descriptor("cart", "cart:1.0")))
    assertEquals(status, 200, body)
    assert(body.contains("\"generation\":1"), body)
    assert(body.contains("\"lifecycle\":\"UpdateInProgress\""), body)
    assert(body.contains("\"image\":\"cart:1.0\""), body)
    assert(body.contains("\"readyInstances\":0"), body)
  }

  test("a descriptor whose name disagrees with the path is a 400") {
    val (status, body) =
      send("PUT", "/services/checkout/cart", Some(descriptor("basket", "cart:1.0")))
    assertEquals(status, 400, body)
    assert(body.contains("names service 'basket'"), body)
  }

  test("an invalid descriptor is a 400 listing every problem") {
    val broken = """{"name":"broken","service":{"image":"","resources":{"instanceType":"huge"}}}"""
    val (status, body) = send("PUT", "/services/checkout/broken", Some(broken))
    assertEquals(status, 400, body)
    assert(body.contains("image must not be empty"), body)
    assert(body.contains("unknown instanceType 'huge'"), body)
  }

  test("re-applying bumps the generation and the reported image") {
    val (status, body) =
      send("PUT", "/services/checkout/cart", Some(descriptor("cart", "cart:2.0")))
    assertEquals(status, 200, body)
    assert(body.contains("\"generation\":2"), body)
    assert(body.contains("\"image\":\"cart:2.0\""), body)
  }

  test("the service appears in its project's listing once the view catches up") {
    // Wait for the generation-2 image, not merely for the row: the previous test re-applied
    // cart:2.0, and the generation-1 row already satisfies `"name":"cart"`. Waiting on the
    // weaker condition and asserting the stronger one outside the retry is a race that a slow
    // machine loses — CI did, on a projection that was one generation behind.
    val body = eventually("the cart service reaches the services view at cart:2.0") {
      val (status, listing) = send("GET", "/services/checkout")
      Option.when(status == 200 && listing.contains("\"image\":\"cart:2.0\""))(listing)
    }
    assert(body.contains("\"name\":\"cart\""), body)
    assert(!body.contains("\"name\":\"broken\""), "a rejected apply must not create a row")
  }

  test("the project listing counts its services") {
    val body = eventually("the checkout project reports one service") {
      val (status, listing) = send("GET", "/projects")
      Option.when(status == 200 && listing.contains("\"services\":1"))(listing)
    }
    assert(body.contains("\"id\":\"checkout\""), body)
  }

  test("the project listing can be filtered by organization") {
    val mine = eventually("checkout is listed under acme") {
      val (status, listing) = send("GET", "/projects?organization=acme")
      Option.when(status == 200 && listing.contains("\"id\":\"checkout\""))(listing)
    }
    assert(mine.contains("\"organizationId\":\"acme\""), mine)

    val (status, others) = send("GET", "/projects?organization=nobody")
    assertEquals(status, 200)
    assertEquals(others, "[]", "an organization with no projects lists nothing")
  }

  test("the organization listing counts its projects") {
    val body = eventually("acme reports one project") {
      val (status, listing) = send("GET", "/organizations")
      Option.when(status == 200 && listing.contains("\"projects\":1"))(listing)
    }
    assert(body.contains("\"id\":\"acme\""), body)
  }

  test("pause, resume and restart each move the lifecycle") {
    val (paused, pausedBody) = send("POST", "/services/checkout/cart/pause")
    assertEquals(paused, 200, pausedBody)
    assert(pausedBody.contains("\"lifecycle\":\"Paused\""), pausedBody)

    val (refused, refusedBody) = send("POST", "/services/checkout/cart/restart")
    assertEquals(refused, 409, refusedBody)

    val (resumed, resumedBody) = send("POST", "/services/checkout/cart/resume")
    assertEquals(resumed, 200, resumedBody)
    assert(resumedBody.contains("\"lifecycle\":\"UpdateInProgress\""), resumedBody)

    val (restarted, restartedBody) = send("POST", "/services/checkout/cart/restart")
    assertEquals(restarted, 200, restartedBody)
    assert(restartedBody.contains("\"generation\":3"), restartedBody)
  }

  // --- Exposure (feature 005): contracts/expose-api.md

  test("exposing reports the derived URL, is idempotent, and shows on get and list") {
    val (status, body) = send("POST", "/services/checkout/cart/expose")
    assertEquals(status, 200, body)
    assert(body.contains("\"hostname\":\"https://cart-checkout.example.test\""), body)
    assert(body.contains("\"exposed\":true"), body)

    val (again, againBody) = send("POST", "/services/checkout/cart/expose")
    assertEquals(again, 200, againBody)
    assert(againBody.contains("\"hostname\":\"https://cart-checkout.example.test\""), againBody)

    val (_, got) = send("GET", "/services/checkout/cart")
    assert(got.contains("\"hostname\":\"https://cart-checkout.example.test\""), got)
    val listing = eventually("the listing shows the hostname") {
      val (_, list) = send("GET", "/services/checkout")
      Option.when(list.contains("cart-checkout.example.test"))(list)
    }
    assert(listing.contains("\"exposed\":true"), listing)
  }

  test("re-applying the descriptor leaves the service exposed") {
    val (status, body) =
      send("PUT", "/services/checkout/cart", Some(descriptor("cart", "cart:3.0")))
    assertEquals(status, 200, body)
    assert(body.contains("\"hostname\":\"https://cart-checkout.example.test\""), body)
  }

  test("a service that serves no HTTP cannot be exposed") {
    val quiet = """{"name":"quiet","service":{"image":"registry.k8s.io/pause:3.9","http":false}}"""
    assertEquals(send("PUT", "/services/checkout/quiet", Some(quiet))._1, 200)
    val (status, body) = send("POST", "/services/checkout/quiet/expose")
    assertEquals(status, 409, body)
    assert(body.contains("serves no HTTP"), body)
    assertEquals(send("DELETE", "/services/checkout/quiet")._1, 204)
  }

  test("a hostname label over 63 characters is refused, naming the limit") {
    val long = "s" * 60
    assertEquals(send("PUT", s"/services/checkout/$long", Some(descriptor(long, "x:1")))._1, 200)
    val (status, body) = send("POST", s"/services/checkout/$long/expose")
    assertEquals(status, 409, body)
    assert(body.contains("69 characters") && body.contains("63"), body)
    assertEquals(send("DELETE", s"/services/checkout/$long")._1, 204)
  }

  test("a hostname another exposed service holds is refused, naming the holder") {
    // `a-b` in project `c` and `a` in project `b-c` both derive a-b-c.example.test.
    for id <- Vector("c", "b-c") do
      val (created, body) =
        send("POST", s"/projects/$id", Some(s"""{"name":"$id","organizationId":"acme"}"""))
      assertEquals(created, 204, body)
    assertEquals(send("PUT", "/services/c/a-b", Some(descriptor("a-b", "x:1")))._1, 200)
    assertEquals(send("PUT", "/services/b-c/a", Some(descriptor("a", "x:1")))._1, 200)
    assertEquals(send("POST", "/services/c/a-b/expose")._1, 200)
    // The check reads the listing view, which follows the journal by a moment.
    val _ = eventually("the holder's row is exposed") {
      val (_, list) = send("GET", "/services/c")
      Option.when(list.contains("\"exposed\":true"))(list)
    }

    val (status, body) = send("POST", "/services/b-c/a/expose")
    assertEquals(status, 409, body)
    assert(body.contains("already exposed by service 'a-b' in project 'c'"), body)

    for (project, name) <- Vector("c" -> "a-b", "b-c" -> "a") do
      assertEquals(send("DELETE", s"/services/$project/$name")._1, 204)
    for id <- Vector("c", "b-c") do
      val _ = eventually(s"project $id is empty") {
        val (_, list) = send("GET", s"/services/$id")
        Option.when(list == "[]")(list)
      }
      assertEquals(send("DELETE", s"/projects/$id")._1, 204)
  }

  test("unexposing clears the hostname and nothing else; again is a no-op") {
    val (status, body) = send("POST", "/services/checkout/cart/unexpose")
    assertEquals(status, 200, body)
    assert(!body.contains("hostname"), body)
    // `exposed: false` is the default and so is omitted from the wire, like every default.
    assert(!body.contains("\"exposed\":true"), body)
    assert(body.contains("\"image\":\"cart:3.0\""), body)
    assertEquals(send("POST", "/services/checkout/cart/unexpose")._1, 200)
    val (_, got) = send("GET", "/services/checkout/cart")
    assert(!got.contains("hostname"), got)
  }

  test("exposing an unknown service is a 404") {
    assertEquals(send("POST", "/services/checkout/nope/expose")._1, 404)
    assertEquals(send("POST", "/services/checkout/nope/unexpose")._1, 404)
  }

  test("a project with services cannot be deleted") {
    val (status, body) = send("DELETE", "/projects/checkout")
    assertEquals(status, 409, body)
    assert(body.contains("still has 1 service"), body)
  }

  test("deleting the service removes it from the listing") {
    assertEquals(send("DELETE", "/services/checkout/cart")._1, 204)
    assertEquals(send("GET", "/services/checkout/cart")._1, 404)

    val listing = eventually("the cart row disappears") {
      val (status, body) = send("GET", "/services/checkout")
      Option.when(status == 200 && !body.contains("\"name\":\"cart\""))(body)
    }
    assertEquals(listing, "[]")
  }

  test("an emptied project can then be deleted, and so can its organization") {
    val emptied = eventually("checkout reports no services") {
      val (status, body) = send("GET", "/projects/checkout")
      Option.when(status == 200 && body.contains("\"services\":0"))(body)
    }
    assert(emptied.contains("\"id\":\"checkout\""), emptied)

    assertEquals(send("DELETE", "/projects/checkout")._1, 204)
    assertEquals(send("GET", "/projects/checkout")._1, 404)

    val organization = eventually("acme reports no projects") {
      val (status, body) = send("GET", "/organizations/acme")
      Option.when(status == 200 && body.contains("\"projects\":0"))(body)
    }
    assert(organization.contains("\"id\":\"acme\""), organization)

    assertEquals(send("DELETE", "/organizations/acme")._1, 204)
    assertEquals(send("GET", "/organizations/acme")._1, 404)
  }

  test("a deleted project's id is not reused") {
    val (status, body) =
      send("POST", "/projects/checkout", Some("""{"name":"Checkout","organizationId":"dup"}"""))
    assertEquals(status, 409, body)
    assert(body.contains("not reused"), body)
  }

  test("renaming an organization is visible immediately by id") {
    val _ = send("POST", "/organizations/rename-me", Some("""{"name":"Before"}"""))
    assertEquals(send("PUT", "/organizations/rename-me/name", Some("""{"name":"After"}"""))._1, 204)

    val (status, body) = send("GET", "/organizations/rename-me")
    assertEquals(status, 200)
    assert(body.contains("\"name\":\"After\""), body)
  }

  test("an unknown path is a 404 and a wrong verb is a 405") {
    assertEquals(send("GET", "/nope")._1, 404)
    assertEquals(send("GET", "/services/a/b/c/d")._1, 404)
    // /organizations/{id} exists for GET, POST, DELETE — but not PATCH.
    assertEquals(send("PATCH", "/organizations/acme", Some("{}"))._1, 405)
  }

  // ── deploy tokens (feature 013) ───────────────────────────────────────────

  /** Pulls `"secret":"ankka_…"` out of a create response. */
  private def secretOf(body: String): String =
    val marker = "\"secret\":\""
    val from   = body.indexOf(marker) + marker.length
    body.substring(from, body.indexOf('"', from))

  private def tokenIdOf(body: String): String =
    val marker = "\"id\":\""
    val from   = body.indexOf(marker) + marker.length
    body.substring(from, body.indexOf('"', from))

  private def createOrganizationFor(id: String): Unit =
    assertEquals(send("POST", s"/organizations/$id", Some(s"""{"name":"$id"}"""))._1, 204)

  test("an owner creates a deploy token and is shown the secret exactly once (S1.1)") {
    createOrganizationFor("tokens-a")

    val (status, body) = send("POST", "/organizations/tokens-a/tokens", Some("""{"label":"ci"}"""))
    assertEquals(status, 200, body)
    val secret = secretOf(body)
    assert(secret.startsWith("ankka_"), body)
    assert(body.contains("\"subject\":\"token:"), body)
    assert(body.contains("\"expiresAt\""), "a default token expires")

    // The listing is a view, so it lags. Retry on the thing that changes — the row appearing —
    // and assert the identity that does not: no secret in it, ever.
    val listing = eventually("the token's row appears in the listing") {
      val (status, body) = send("GET", "/organizations/tokens-a/tokens")
      Option.when(status == 200 && body.contains("\"label\":\"ci\""))(body)
    }
    assert(!listing.contains("ankka_"), listing)
    assert(!listing.contains("secret"), listing)
    assert(!listing.contains("digest"), listing)
  }

  test("a deploy token is authorized as a member and attributed as itself (S1.2)") {
    createOrganizationFor("tokens-b")
    val created =
      send("POST", "/organizations/tokens-b/tokens", Some("""{"label":"deployer"}"""))._2
    val secret = secretOf(created)

    // `whoami` as the token: its own subject, its label, no email.
    val (whoStatus, who) = send("GET", "/auth/whoami", token = Some(secret))
    assertEquals(whoStatus, 200, who)
    assert(who.contains("\"subject\":\"token:"), who)
    assert(who.contains("\"name\":\"deployer\""), who)
    assert(!who.contains("\"email\""), who)

    // A member-level write succeeds.
    assertEquals(
      send(
        "POST",
        "/projects/tokens-b-checkout",
        Some("""{"name":"Checkout","organizationId":"tokens-b"}"""),
        token = Some(secret)
      )._1,
      204
    )
    val (_, project) = send("GET", "/projects/tokens-b-checkout")
    assert(project.contains("tokens-b"), project)
  }

  test("a deploy token is refused everything reserved to owners, including tokens (S1.3)") {
    createOrganizationFor("tokens-c")
    val secret =
      secretOf(send("POST", "/organizations/tokens-c/tokens", Some("""{"label":"ci"}"""))._2)

    def asToken(method: String, path: String, body: Option[String] = None) =
      send(method, path, body, token = Some(secret))._1

    assertEquals(asToken("POST", "/organizations/tokens-c/tokens", Some("""{"label":"x"}""")), 403)
    assertEquals(asToken("GET", "/organizations/tokens-c/tokens"), 403)
    assertEquals(
      asToken("POST", "/organizations/tokens-c/members", Some("""{"email":"x@example.test"}""")),
      403
    )
    assertEquals(asToken("PUT", "/organizations/tokens-c/name", Some("""{"name":"Nope"}""")), 403)
    assertEquals(asToken("DELETE", "/organizations/tokens-c"), 403)
    // And it can still do member things, so the refusals above are about the role, not the token.
    assertEquals(asToken("GET", "/organizations/tokens-c"), 200)
  }

  test("revoking refuses the token on the very next request to this node (S1.4)") {
    createOrganizationFor("tokens-d")
    val created = send("POST", "/organizations/tokens-d/tokens", Some("""{"label":"ci"}"""))._2
    val secret  = secretOf(created)
    val id      = tokenIdOf(created)

    assertEquals(send("GET", "/organizations/tokens-d", token = Some(secret))._1, 200)
    assertEquals(send("DELETE", s"/organizations/tokens-d/tokens/$id")._1, 204)

    // No polling: this node evicted write-through, so the next request is already refused.
    assertEquals(send("GET", "/organizations/tokens-d", token = Some(secret))._1, 401)
    assertEquals(send("GET", "/auth/whoami", token = Some(secret))._1, 401)

    // Revoking twice, and revoking something that never existed, are the same 404.
    assertEquals(send("DELETE", s"/organizations/tokens-d/tokens/$id")._1, 404)
    assertEquals(send("DELETE", "/organizations/tokens-d/tokens/0000000000000000")._1, 404)

    // The membership went with it.
    val (_, members) = send("GET", "/organizations/tokens-d/members")
    assert(!members.contains(s"token:$id"), members)
  }

  test("a deploy token sees nothing of another organization (S1.5)") {
    createOrganizationFor("tokens-e")
    createOrganizationFor("tokens-f")
    val secret =
      secretOf(send("POST", "/organizations/tokens-e/tokens", Some("""{"label":"ci"}"""))._2)

    // Not 403: an outsider must not learn that the organization exists at all.
    assertEquals(send("GET", "/organizations/tokens-f", token = Some(secret))._1, 404)
    assertEquals(
      send(
        "POST",
        "/projects/tokens-f-nope",
        Some("""{"name":"Nope","organizationId":"tokens-f"}"""),
        token = Some(secret)
      )._1,
      404
    )
  }

  test("a token expires on its own, and one created to never expire does not (S1.7)") {
    createOrganizationFor("tokens-g")
    val expiring =
      secretOf(send("POST", "/organizations/tokens-g/tokens", Some("""{"label":"ci"}"""))._2)
    val forever = secretOf(
      send("POST", "/organizations/tokens-g/tokens", Some("""{"label":"f","expiresIn":0}"""))._2
    )

    assertEquals(send("GET", "/organizations/tokens-g", token = Some(expiring))._1, 200)
    assertEquals(send("GET", "/organizations/tokens-g", token = Some(forever))._1, 200)

    // Ninety-one days later. Only the control plane's clock moves; the OIDC tokens the suite
    // mints are checked against the real one, so the owner's credential is untouched.
    identity.clock.advanceDays(91)

    assertEquals(send("GET", "/organizations/tokens-g", token = Some(expiring))._1, 401)
    assertEquals(send("GET", "/organizations/tokens-g", token = Some(forever))._1, 200)

    identity.clock.advanceDays(-91)
  }

  test("a malformed deploy token is refused as one, not handed to the OIDC verifier") {
    def challenge(token: String): (Int, String) =
      val response = sendRaw("/organizations", Some(token))
      (response.statusCode, response.headers.firstValue("WWW-Authenticate").orElse(""))

    val (status, why) = challenge("ankka_not-a-real-token")
    assertEquals(status, 401)
    assert(why.contains("not a deploy token"), why)

    val (unknownStatus, unknownWhy) = challenge(s"ankka_${"0" * 16}_${"0" * 64}")
    assertEquals(unknownStatus, 401)
    assert(unknownWhy.contains("not recognised"), unknownWhy)
  }

  test("a disabled organization refuses a new token") {
    createOrganizationFor("tokens-h")
    val admin = identity.token("root", roles = Set("platform-admin"))
    assertEquals(send("POST", "/organizations/tokens-h/disable", token = Some(admin))._1, 204)
    assertEquals(send("POST", "/organizations/tokens-h/tokens", Some("""{"label":"ci"}"""))._1, 409)
    assertEquals(send("POST", "/organizations/tokens-h/enable", token = Some(admin))._1, 204)
  }

  test("a label is required, and a lifetime cannot exceed a year") {
    createOrganizationFor("tokens-i")
    assertEquals(send("POST", "/organizations/tokens-i/tokens", Some("""{"label":""}"""))._1, 400)
    assertEquals(
      send(
        "POST",
        "/organizations/tokens-i/tokens",
        Some("""{"label":"x","expiresIn":99999999}""")
      )._1,
      400
    )
  }

  // ── a project's registry (feature 013) ─────────────────────────────────

  private val Password = "gho_a-very-secret-token"

  test("a member registers a registry credential, and the password goes only to the cluster") {
    createOrganizationFor("reg-a")
    assertEquals(
      send("POST", "/projects/reg-a-app", Some("""{"name":"App","organizationId":"reg-a"}"""))._1,
      204
    )

    val (status, body) = send(
      "PUT",
      "/projects/reg-a-app/registry",
      Some(s"""{"server":"ghcr.io","username":"octocat","password":"$Password"}""")
    )
    assertEquals(status, 204, body)

    // It reached the cluster, exactly once, with the password.
    val written = cluster
      .pullSecret("ankka-reg-a-app")
      .getOrElse(fail("no credential reached the cluster"))
    assertEquals(written.server, "ghcr.io")
    assertEquals(written.username, "octocat")
    assertEquals(written.password, Password)

    // And nowhere else. The detail and the listing name the server and the user, never the secret.
    val (_, detail) = send("GET", "/projects/reg-a-app")
    assert(detail.contains("\"server\":\"ghcr.io\""), detail)
    assert(detail.contains("\"username\":\"octocat\""), detail)
    assert(!detail.contains(Password), s"the password came back: $detail")
    assert(!detail.contains("password"), detail)

    val listing = eventually("the project's row carries the registry") {
      val (status, body) = send("GET", "/projects?organization=reg-a")
      Option.when(status == 200 && body.contains("\"server\":\"ghcr.io\""))(body)
    }
    assert(!listing.contains(Password), s"the password reached a view: $listing")
  }

  test("clearing the registry leaves the Secret and stops claiming it; clearing twice is 404") {
    val before = cluster.pullSecretCount
    assertEquals(send("DELETE", "/projects/reg-a-app/registry")._1, 204)

    val (_, detail) = send("GET", "/projects/reg-a-app")
    assert(!detail.contains("ghcr.io"), detail)
    // The Secret itself is untouched: the control plane holds no delete on secrets, and one nothing
    // names is inert.
    assertEquals(cluster.pullSecretCount, before)

    assertEquals(send("DELETE", "/projects/reg-a-app/registry")._1, 404)
  }

  test("a cluster that refuses the write is 503, and nothing is recorded") {
    createOrganizationFor("reg-b")
    assertEquals(
      send("POST", "/projects/reg-b-app", Some("""{"name":"App","organizationId":"reg-b"}"""))._1,
      204
    )
    cluster.refuseSecrets()
    try
      val (status, body) = send(
        "PUT",
        "/projects/reg-b-app/registry",
        Some(s"""{"server":"ghcr.io","username":"octocat","password":"$Password"}""")
      )
      assertEquals(status, 503, body)
      assert(body.contains("forbidden"), body)
    finally cluster.allowSecrets()

    // The journal never claims a credential the cluster does not hold.
    val (_, detail) = send("GET", "/projects/reg-b-app")
    assert(!detail.contains("ghcr.io"), detail)
  }

  test(
    "a deploy token may register a registry, because pushing images is a member's job (FR-026)"
  ) {
    createOrganizationFor("reg-c")
    val secret =
      secretOf(send("POST", "/organizations/reg-c/tokens", Some("""{"label":"ci"}"""))._2)
    assertEquals(
      send(
        "POST",
        "/projects/reg-c-app",
        Some("""{"name":"App","organizationId":"reg-c"}"""),
        token = Some(secret)
      )._1,
      204
    )
    assertEquals(
      send(
        "PUT",
        "/projects/reg-c-app/registry",
        Some(s"""{"server":"ghcr.io","username":"ci","password":"$Password"}"""),
        token = Some(secret)
      )._1,
      204
    )
    // Attributed to the token, by its label, like every other change it makes.
    val (_, detail) = send("GET", "/projects/reg-c-app")
    assert(detail.contains("\"setBy\":\"ci\""), detail)
  }

  test("a URL for a server, or an empty field, is refused before the cluster is touched") {
    val before = cluster.pullSecretCount
    def attempt(body: String): (Int, String) =
      send("PUT", "/projects/reg-a-app/registry", Some(body))

    val (schemeStatus, scheme) =
      attempt(s"""{"server":"https://ghcr.io","username":"octocat","password":"$Password"}""")
    assertEquals(schemeStatus, 400, scheme)
    assert(scheme.contains("not a URL"), scheme)

    assertEquals(attempt(s"""{"server":"ghcr.io","username":"","password":"$Password"}""")._1, 400)
    assertEquals(attempt("""{"server":"ghcr.io","username":"octocat","password":""}""")._1, 400)
    assertEquals(cluster.pullSecretCount, before, "a refused request must write nothing")
  }

  test("a project in an organization the caller cannot see answers 404, not 403") {
    createOrganizationFor("reg-d")
    assertEquals(
      send("POST", "/projects/reg-d-app", Some("""{"name":"App","organizationId":"reg-d"}"""))._1,
      204
    )
    val stranger = identity.token("stranger", Some("stranger@example.test"))
    assertEquals(
      send(
        "PUT",
        "/projects/reg-d-app/registry",
        Some(s"""{"server":"ghcr.io","username":"x","password":"$Password"}"""),
        token = Some(stranger)
      )._1,
      404
    )
  }
