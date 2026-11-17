package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `ankka projects topics apply` (feature 066) through `Main.run`, against the real routes, entity
 * and projector, as `ProjectTopicsFeature` drives `topics set`.
 */
class TopicsApplySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Token    =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)

  private val deployConfig          = DeployConfig.default
  private lazy val cluster          = new FakeAnkkaServiceClient
  private lazy val projector        = ServiceProjector.withClient(deployConfig, cluster)
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private val http        = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  private lazy val config = Files.createTempFile("ankka-topics-apply", ".json")
  private var count       = 0

  override def beforeAll(): Unit =
    Files.delete(config)
    sys.props("ankka.config") = config.toString
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        deployConfig,
        auth = Some(identity.config()),
        clock = identity.clock,
        topics = Some(projector)
      )*
    )
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server)
    )
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    Files.deleteIfExists(config): Unit
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $Token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** A project of its own for each case. */
  private def project(): String =
    count += 1
    val id = s"shop-apply-$count"
    assertEquals(
      send("POST", s"/projects/$id", Some(s"""{"name":"$id","organizationId":"acme"}"""))._1,
      204
    )
    id

  private def topics(text: String): Path =
    val root = Files.createTempDirectory("topics-apply")
    Files.writeString(root.resolve("topics.json"), text, UTF_8)
    root

  private def apply(root: Path, project: String, extra: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Console.withIn(ByteArrayInputStream(Array.emptyByteArray)) {
      Main.run(
        Seq(
          "projects",
          "topics",
          "apply",
          "--dir",
          root.toString,
          "-p",
          project,
          "--url",
          url,
          "--token",
          Token
        ) ++
          extra,
        PrintStream(out, true, UTF_8),
        PrintStream(err, true, UTF_8)
      )
    }
    (code, out.toString(UTF_8), err.toString(UTF_8))

  private def partitionsOf(project: String): Map[String, Int] =
    val (status, body) = send("GET", s"/projects/$project/topics")
    assertEquals(status, 200, body)
    """"name":"([^"]+)","partitions":(\d+)""".r
      .findAllMatchIn(body)
      .map(m => m.group(1) -> m.group(2).toInt)
      .toMap

  private val Two =
    """{"topics":[{"name":"orders-placed","partitions":6},{"name":"customers","partitions":3,"compacted":true}]}"""

  test("a file of two topics declares both, and a second run leaves both unchanged") {
    val p                = project()
    val root             = topics(Two)
    val (code, out, err) = apply(root, p)
    assertEquals(code, 0, err)
    assertEquals(
      out.linesIterator.toVector,
      Vector("orders-placed: declared", "customers: declared")
    )
    assertEquals(partitionsOf(p), Map("orders-placed" -> 6, "customers" -> 3))
    val (again, unchanged, err2) = apply(root, p)
    assertEquals(again, 0, err2)
    assertEquals(
      unchanged.linesIterator.toVector,
      Vector("orders-placed: unchanged", "customers: unchanged")
    )
  }

  test("fewer partitions for one is refused naming it, and the other stays declared") {
    val p = project()
    assertEquals(apply(topics(Two), p)._1, 0)
    val fewer =
      """{"topics":[{"name":"customers","partitions":3,"compacted":true},{"name":"orders-placed","partitions":2}]}"""
    val (code, out, err) = apply(topics(fewer), p)
    assertEquals(code, 1)
    assert(err.contains("orders-placed"), err)
    assertEquals(out.linesIterator.toVector, Vector("customers: unchanged"))
    assertEquals(partitionsOf(p), Map("orders-placed" -> 6, "customers" -> 3))
  }

  test("a change that removes messages asks, and --removes answers") {
    val p = project()
    assertEquals(
      apply(topics("""{"topics":[{"name":"audit","partitions":1,"retention":"7d"}]}"""), p)._1,
      0
    )
    val shorter        = topics("""{"topics":[{"name":"audit","partitions":1,"retention":"1d"}]}""")
    val (code, _, err) = apply(shorter, p)
    assertEquals(code, 1)
    assert(err.contains("audit: this declaration removes"), err)
    assert(err.contains("--removes"), err)
    val (answered, out, err2) = apply(shorter, p, "--removes")
    assertEquals(answered, 0, err2)
    assertEquals(out.linesIterator.toVector.last, "audit: declared")
  }

  test("a topic the file does not name is untouched") {
    val p = project()
    assertEquals(
      send("PUT", s"/projects/$p/topics/returns", Some("""{"partitions":4}"""))._1 / 100,
      2
    )
    assertEquals(apply(topics(Two), p)._1, 0)
    assertEquals(partitionsOf(p).get("returns"), Some(4))
  }
