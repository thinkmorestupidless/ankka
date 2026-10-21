package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.mcp.features.Fixtures.{
  CartEntity,
  CartMcp,
  Item,
  References,
  given
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/**
 * What a tool costs over the route it stands beside: the same read of one cart, two hundred times
 * through `GET /carts/{cartId}` and two hundred times through the MCP endpoint's `get_cart`, each
 * after a warm-up, on one service on loopback. The tool's median must be within one and a half
 * times the route's. Skipped unless `-Dankka.spikes=on`, since a shared machine's timings are not a
 * test; `.claude/rules/mcp.md` records a run.
 */
class McpLatencySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private final class CartRoutes(clients: EndpointClients) extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll
    get("/{cartId}") { (cartId: String) =>
      clients.componentClient
        .forEventSourcedEntity(EntityId(cartId))
        .call(CartEntity.getCart)
        .invoke()
    }

  private val Reads  = 200
  private val WarmUp = 100

  test("a tool call's median is within one and a half times the route's") {
    val server = HttpServer.at("127.0.0.1", 0)(
      clients => CartRoutes(clients),
      clients => CartMcp(clients, References(), Acl.AllowAll)
    )
    val kit = AnkkaTestKit.start(Seq(CartEntity.descriptor), Seq(server))
    try
      val base = s"http://127.0.0.1:${server.boundPort.get}"
      val http = HttpClient.newHttpClient()
      kit.componentClient
        .forEventSourcedEntity(EntityId("c1"))
        .call(CartEntity.addItem)
        .invoke(Item("tea", 2)): Unit

      def route(): Int =
        http
          .send(
            HttpRequest.newBuilder(URI.create(s"$base/carts/c1")).build(),
            HttpResponse.BodyHandlers.ofString()
          )
          .statusCode()

      val call =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_cart","arguments":{"cartId":"c1"}}}"""
      def tool(): Int =
        val response = http.send(
          HttpRequest
            .newBuilder(URI.create(s"$base/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString(call))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        assert(response.body().contains("\"isError\":false"), response.body())
        response.statusCode()

      def median(read: () => Int): Double =
        (1 to WarmUp).foreach(_ => assertEquals(read(), 200))
        val times = (1 to Reads).map { _ =>
          val started = System.nanoTime()
          assertEquals(read(), 200)
          (System.nanoTime() - started) / 1e6
        }.sorted
        times(times.size / 2)

      val routeMedian = median(() => route())
      val toolMedian  = median(() => tool())
      println(
        f"latency: GET /carts/{cartId} median $routeMedian%.2f ms, get_cart median $toolMedian%.2f ms, ratio ${toolMedian / routeMedian}%.2f"
      )
      assert(
        toolMedian <= routeMedian * 1.5,
        f"get_cart $toolMedian%.2f ms against the route's $routeMedian%.2f ms"
      )
    finally kit.stop()
  }
