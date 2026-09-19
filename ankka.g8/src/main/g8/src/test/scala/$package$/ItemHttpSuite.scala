package $package$

import $package$.api.ItemEndpoint
import $package$.application.{ItemEntity, ItemRows}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/** The endpoint over a real runtime and a throwaway Postgres (Docker). */
class ItemHttpSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    // Port 0: concurrent test runs cannot collide.
    val server = HttpServer.at("127.0.0.1", 0)(clients =>
      ItemEndpoint(clients.componentClient, clients.viewClient)
    )
    testKit = AnkkaTestKit.start(
      Seq(ItemEntity.descriptor, ItemRows.descriptor),
      Seq(ProjectionRuntime(), server)
    )
    baseUrl = s"http://127.0.0.1:\${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  test("health is served without an endpoint or an acl") {
    assertEquals(send("GET", "/_ankka/health"), (200, "ok"))
  }

  test("an item posted is an item read") {
    assertEquals(send("POST", "/items/i1", Some("""{"name":"Widget","count":2}"""))._1, 204)
    val (status, body) = send("GET", "/items/i1")
    assertEquals(status, 200)
    assert(body.contains("\"name\":\"Widget\"") && body.contains("\"count\":2"), body)
  }

  test("a rejected command is a 400 with the reason") {
    val (status, body) = send("POST", "/items/i2", Some("""{"name":"Widget","count":0}"""))
    assertEquals(status, 400)
    assert(body.contains("greater than zero"), body)
  }

  test("the listing catches up with the writes") {
    val _        = send("POST", "/items/i3", Some("""{"name":"Gadget","count":1}"""))
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var listed   = ""
    while !listed.contains("\"id\":\"i3\"") && System.nanoTime() < deadline do
      listed = send("GET", "/items/")._2
      if !listed.contains("\"id\":\"i3\"") then Thread.sleep(200)
    assert(listed.contains("\"id\":\"i3\""), s"the view never listed i3: \$listed")
  }
