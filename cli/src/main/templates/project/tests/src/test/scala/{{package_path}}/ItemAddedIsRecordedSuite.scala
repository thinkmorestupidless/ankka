package {{package}}

import {{package}}.{{first_pkg}}.{{First}}Service
import {{package}}.{{second_pkg}}.{{Second}}Service
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * An item added at {{first}} is recorded by {{second}}: the project's two crossings, run.
 *
 * Each service runs under a test kit of its own, under its own name and in its own database, as it
 * does when deployed; both are on one broker in memory, so {{first}}'s publication to the declared
 * topic `{{first}}-events` reaches {{second}}'s reader. {{first}} finds {{second}} by name through
 * `localServices`, which is the route crossing.
 */
class ItemAddedIsRecordedSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private val broker = InMemoryBroker()
  private val http   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private var second: AnkkaTestKit = null
  private var first: AnkkaTestKit  = null
  private var firstUrl: String     = ""

  override def beforeAll(): Unit =
    val secondServer = HttpServer.at("127.0.0.1", 0)({{Second}}Service.endpoint)
    second = AnkkaTestKit.start(
      {{Second}}Service.descriptors,
      {{Second}}Service.extensions(ProjectionRuntime.withBroker(broker, broker), secondServer),
      serviceIdentity = ServiceIdentity.local("{{second}}")
    )
    val secondPort   = secondServer.boundPort.getOrElse(fail("{{second}} did not bind"))
    val firstServer  = HttpServer.at("127.0.0.1", 0)({{First}}Service.endpoint)
    first = AnkkaTestKit.start(
      {{First}}Service.descriptors,
      {{First}}Service.extensions(ProjectionRuntime.withBroker(broker, broker), firstServer),
      serviceIdentity = ServiceIdentity.local("{{first}}"),
      localServices = Map("{{second}}" -> s"http://127.0.0.1:$secondPort")
    )
    firstUrl = s"http://127.0.0.1:${firstServer.boundPort.getOrElse(fail("{{first}} did not bind"))}"

  override def afterAll(): Unit =
    if first != null then first.stop()
    if second != null then second.stop()

  private def send(method: String, path: String, body: String = ""): (Int, String) =
    val request = HttpRequest
      .newBuilder(URI.create(firstUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .method(method, if body.isEmpty then HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())

  test("an item added at {{first}} is recorded by {{second}}, and {{first}} reads it back from {{second}}") {
    val (added, _) = send("POST", "/{{first}}/widget", """{"name":"Widget","count":3}""")
    assertEquals(added, 204)

    // {{second}} records it once the message has crossed; ask {{first}}'s route, which calls {{second}},
    // until the answer is the item, and assert on the item.
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var answer   = send("GET", "/{{first}}/widget/{{second}}")
    while !(answer._1 == 200 && answer._2.contains("\"count\":3")) && System.nanoTime() < deadline do
      Thread.sleep(200)
      answer = send("GET", "/{{first}}/widget/{{second}}")
    assertEquals(answer._1, 200, answer._2)
    assert(answer._2.contains("\"name\":\"Widget\""), answer._2)
    assert(answer._2.contains("\"count\":3"), answer._2)
  }
