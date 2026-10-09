package shoppingcart

import com.thinkmorestupidless.ankka.http.{Caller, GrantEntry, GrantTarget, Grants, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, EventSourcedTestKit, LogCapturing}
import shoppingcart.api.WalletEndpoint
import shoppingcart.application.WalletEntity
import shoppingcart.domain.Deposit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt

/**
 * The wallet another project may be granted (feature 040): a deposit applied once per key however
 * often it is sent, and routes that admit the granted caller and nobody else — tested without a
 * cluster by giving the server its grants and naming the caller.
 */
class WalletSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("a deposit sent twice with one key is applied once") {
    val kit   = EventSourcedTestKit.of(WalletEntity, "p1")
    val first = kit.call(WalletEntity.deposit)(Deposit("k1", "eur", 100))
    val again = kit.call(WalletEntity.deposit)(Deposit("k1", "eur", 100))
    assertEquals((first.replyValue.balance, first.replyValue.applied), (100L, true))
    assertEquals((again.replyValue.balance, again.replyValue.applied), (100L, false))
    assertEquals(again.events, Vector.empty)
    assertEquals(kit.call(WalletEntity.deposit)(Deposit("k2", "eur", 5)).replyValue.balance, 105L)
  }

  test("a deposit with no key, or nothing to deposit, is refused") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p1")
    assert(kit.call(WalletEntity.deposit)(Deposit("", "eur", 1)).isError)
    assert(kit.call(WalletEntity.deposit)(Deposit("k", "eur", 0)).isError)
  }

  private val merchant = Caller.Service("payments", "merchant")
  private val grants = Grants.of(
    GrantEntry(merchant, GrantTarget.Route("POST", "/v1/wallets/{player}/{currency}/deposits"))
  )

  private var testKit: AnkkaTestKit = null
  private var base                  = ""
  private val http                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val server = HttpServer
      .at("127.0.0.1", 0)(clients => WalletEndpoint(clients.componentClient))
      .withGrants(grants)
    testKit = AnkkaTestKit.start(Seq(WalletEntity.descriptor), Seq(server))
    base = s"http://127.0.0.1:${server.boundPort.get}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def deposit(as: Caller, key: String): (Int, String) =
    val (name, value) = testKit.asCaller(as)
    val request = HttpRequest
      .newBuilder(URI.create(s"$base/v1/wallets/p9/eur/deposits"))
      .header(name, value)
      .header("Idempotency-Key", key)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("""{"amount":25}"""))
      .build()
    val r = http.send(request, HttpResponse.BodyHandlers.ofString())
    (r.statusCode, r.body)

  test("the granted service deposits, and a retried deposit is answered without applying it") {
    val (status, body) = deposit(merchant, "dep-1")
    assertEquals(status, 200, body)
    assert(body.contains("\"balance\":25") && body.contains("\"applied\":true"), body)
    assert(body.contains("\"by\":\"service:payments/merchant\""), body)
    val (_, retried) = deposit(merchant, "dep-1")
    assert(retried.contains("\"balance\":25") && retried.contains("\"applied\":false"), retried)
  }

  test("a service of the same project the grant did not name is refused") {
    assertEquals(deposit(Caller.Service("payments", "psp-gateway"), "dep-2")._1, 403)
  }
