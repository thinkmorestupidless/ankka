package nakka.controlplane

import nakka.controlplane.api.ControlPlaneAcl
import nakka.http.HttpServer
import nakka.runtime.ProjectionRuntime
import nakka.testkit.NakkaTestKit

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

  private val Token = "test-token-not-a-secret"

  private var testKit: NakkaTestKit = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(ControlPlaneAcl.bearer(Token))*
    )
    testKit = NakkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

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

  test("health needs no token, everything else does") {
    assertEquals(send("GET", "/_nakka/health", token = None), (200, "ok"))

    val (noToken, _) = send("GET", "/organizations", token = None)
    assertEquals(noToken, 403)

    val (wrongToken, _) = send("GET", "/organizations", token = Some("nope"))
    assertEquals(wrongToken, 403)
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
    val body = eventually("the cart service reaches the services view") {
      val (status, listing) = send("GET", "/services/checkout")
      Option.when(status == 200 && listing.contains("\"name\":\"cart\""))(listing)
    }
    assert(body.contains("\"image\":\"cart:2.0\""), body)
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
