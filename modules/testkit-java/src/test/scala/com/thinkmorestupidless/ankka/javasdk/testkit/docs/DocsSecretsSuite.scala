package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.OptionConverters.*

/** The Java samples of the secrets page: the credentials endpoint and the charging workflow. */
class DocsSecretsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("payments")
        .register(classOf[ChargeWorkflow])
        .withEndpoint(classOf[CredentialsRoutes])
    )
  override def afterAll(): Unit = if kit != null then kit.close()

  private def send(method: String, path: String, body: String = ""): HttpResponse[String] =
    val publisher =
      if body.isEmpty then HttpRequest.BodyPublishers.noBody()
      else HttpRequest.BodyPublishers.ofString(body)
    kit.httpClient.send(
      HttpRequest
        .newBuilder(kit.http.resolve(path))
        .header("content-type", "application/json")
        .method(method, publisher)
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a credential kept through the endpoint is read by a step, and never recorded") {
    val kept = send("POST", "/credentials", """{"name":"provider/stripe","value":"sk-test-123"}""")
    assertEquals(kept.statusCode, 204, kept.body)
    assertEquals(send("GET", "/credentials?name=provider/stripe").body, "sk-test-123")
    assertEquals(kit.secrets().get("provider/stripe").toScala, Some("sk-test-123"))

    kit.componentClient
      .forWorkflow("ch-1")
      .call(DocsRefs.startCharge)
      .invoke(ChargeWorkflow.Charge("stripe", 500)): Unit
    val state =
      kit.componentClient
        .forWorkflow("ch-1")
        .awaitEnd(classOf[ChargeWorkflow], Duration.ofSeconds(20))
    assertEquals(state, ChargeWorkflow.ChargeState("stripe", 11, "charged"))
    kit.assertNoPersonalValue("sk-test-123")

    assertEquals(send("DELETE", "/credentials?name=provider/stripe").statusCode, 204)
    val gone = send("GET", "/credentials?name=provider/stripe")
    assertEquals(gone.statusCode, 404, gone.body)
    assert(gone.body.contains("no secret 'provider/stripe'"), gone.body)
  }
