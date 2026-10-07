package shoppingcart

import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, ScriptedService}
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import shoppingcart.api.ServiceCallerEndpoint
import shoppingcart.application.{RelayModel, ServiceCaller}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

/**
 * The agent the platform's cluster suite drives to show a tool's call admitted by name, without a
 * cluster: its tool calls a service played on loopback, and its model relays what that service
 * answered, so the reply is the called service's answer or its refusal.
 */
class ServiceCallerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val carts                 = ScriptedService.start()
  private var testKit: AnkkaTestKit = null
  private var base                  = ""
  private val http                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val server =
      HttpServer.at("127.0.0.1", 0)(clients => ServiceCallerEndpoint(clients.componentClient))
    testKit = AnkkaTestKit.start(
      Seq(ServiceCaller.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(RelayModel), server),
      localServices = Map("carts" -> carts.address)
    )
    base = s"http://127.0.0.1:${server.boundPort.get}"

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    carts.stop()

  private def get(path: String): (Int, String) =
    val r = http.send(
      HttpRequest.newBuilder(URI.create(base + path)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (r.statusCode, r.body)

  test("the agent's tool calls the service, and its reply is what the service answered") {
    carts.clear()
    carts.answer(_ =>
      ServiceResponse(
        200,
        "text/plain",
        "admitted: me".getBytes(StandardCharsets.UTF_8),
        Vector.empty
      )
    )
    assertEquals(get("/agent/call/carts?path=/callers/orders-alone"), (200, "admitted: me"))
    assertEquals(
      carts.requests.map(r => (r.method, r.path)),
      Vector(("GET", "/callers/orders-alone"))
    )
  }

  test("a refusal reaches the agent as its tool's error, and the reply says so") {
    carts.failNext(403)
    val (status, reply) = get("/agent/call/carts?path=/callers/orders-alone")
    assertEquals(status, 200)
    assert(reply.startsWith("tool error:") && reply.contains("403"), reply)
  }
