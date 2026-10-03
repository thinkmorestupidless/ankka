package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, PodLogReader}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Which container each hosting's logs are read from (feature 021, R11): the developer's unless the
 * platform's is asked for, and the platform's only where there is one. The route is driven over
 * real HTTP through the assembly that ships, with a reader that records what it was asked instead
 * of a cluster.
 */
class ServiceLogsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  /** What the route asked for: the instance, the container, and whether the previous one. */
  final case class Asked(instance: String, container: String, previous: Boolean, tail: Option[Int])

  private final class RecordingLogs extends PodLogReader:
    val asked = new ConcurrentLinkedQueue[Asked]()
    def instances(projectId: String, service: String): Vector[String] = Vector(s"$service-abc12")
    def read(
        projectId: String,
        instance: String,
        container: String,
        tail: Option[Int],
        sinceSeconds: Option[Int],
        previous: Boolean
    ): Either[String, String] =
      asked.add(Asked(instance, container, previous, tail))
      Right(s"output of $container")

  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)

  private val reader                = new RecordingLogs
  private var testKit: AnkkaTestKit = null
  private var baseUrl: String       = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default,
        auth = Some(identity.config()),
        clock = identity.clock,
        logs = Some(reader)
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/shop", Some("""{"name":"Shop","organizationId":"acme"}"""))._1,
      204
    )
    for (name, service) <- Vector(
        "cart" -> """{"image":"cart:1"}""",
        "py"   -> """{"image":"py:1","hosting":"process","protocol":"1.0"}""",
        "mod"  -> """{"image":"mod:1","hosting":"wasm","protocol":"1.0"}""",
        "web"  -> """{"image":"web:1","hosting":"web"}"""
      )
    do
      val (status, body) =
        send("PUT", s"/services/shop/$name", Some(s"""{"name":"$name","service":$service}"""))
      assertEquals(status, 200, s"$name: $body")

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $Token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def logs(name: String, query: String = ""): (Int, String) =
    reader.asked.clear()
    send("GET", s"/services/shop/$name/logs$query")

  private def askedFor: Vector[Asked] = reader.asked.asScala.toVector

  test("without the platform's container asked for, the developer's is read, for every hosting") {
    for (name, container) <- Vector(
        "cart" -> "cart",
        "py"   -> "py-app",
        "mod"  -> "mod",
        "web"  -> "web-app"
      )
    do
      val (status, body) = logs(name)
      assertEquals(status, 200, s"$name: $body")
      assertEquals(askedFor, Vector(Asked(s"$name-abc12", container, false, None)), name)
      assert(body.contains(s"output of $container"), body)
  }

  test(
    "with platform=true the platform's container is read beside a process and beside a web process"
  ) {
    for (name, container) <- Vector("py" -> "py", "web" -> "web") do
      val (status, body) = logs(name, "?platform=true")
      assertEquals(status, 200, s"$name: $body")
      assertEquals(askedFor, Vector(Asked(s"$name-abc12", container, false, None)), name)
  }

  test("platform=true is refused for a service whose pod has one container, and nothing is read") {
    for name <- Vector("cart", "mod") do
      val (status, body) = logs(name, "?platform=true")
      assertEquals(status, 400, s"$name: $body")
      assert(body.contains("--platform applies to a service with process or web hosting"), body)
      assertEquals(askedFor, Vector.empty, name)
  }

  test("the other choices reach the reader unchanged, with the container") {
    val (status, _) = logs("web", "?platform=true&previous=true&tail=20")
    assertEquals(status, 200)
    assertEquals(askedFor, Vector(Asked("web-abc12", "web", true, Some(20))))
    val (status2, _) = logs("py", "?previous&instance=py-zzz")
    assertEquals(status2, 200)
    assertEquals(askedFor, Vector(Asked("py-zzz", "py-app", true, None)))
  }
