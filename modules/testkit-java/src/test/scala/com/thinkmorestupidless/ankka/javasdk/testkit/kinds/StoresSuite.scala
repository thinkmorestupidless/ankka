package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.bridge.Stores
import com.thinkmorestupidless.ankka.javasdk.client.{CommandException, ServiceResponse}
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings}
import com.thinkmorestupidless.ankka.runtime.ServiceIdentity
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.typesafe.config.ConfigFactory

import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The service's secret store, its bucket and its calls to other services, from a Java endpoint and
 * from the Java kit, over the kit's own secret key and in-memory store.
 */
class StoresSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka.service("vault").withEndpoint(classOf[Vault]),
      Settings.defaults().withScriptedService("stock")
    )

  override def afterAll(): Unit = if kit != null then kit.close()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = HttpRequest.newBuilder(kit.http().resolve(path))
    body match
      case Some(json) =>
        builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
    val response = kit.httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  test(
    "a secret kept by the kit is read by the endpoint, and one kept by the endpoint by the kit"
  ) {
    kit.secrets().put("acme-key", "s3cret")
    assertEquals(send("GET", "/vault/secrets/acme-key"), (200, """{"value":"s3cret"}"""))
    assertEquals(send("POST", "/vault/secrets/other", Some("""{"value":"v2"}"""))._1, 204)
    assertEquals(kit.secrets().get("other").orElse(null), "v2")
    kit.secrets().delete("other")
    assert(kit.secrets().get("other").isEmpty)
  }

  test("a secret's name is held to the store's rule, refused as a CommandException") {
    val e = intercept[CommandException](kit.secrets().put("no spaces", "x"))
    assert(e.getMessage.contains("a secret's name is"), e.getMessage)
  }

  test("an object streamed into the bucket is read back as a stream, listed and stated") {
    assertEquals(send("POST", "/vault/objects/a.txt", Some("""{"value":"hello"}"""))._1, 204)
    assertEquals(send("GET", "/vault/objects/a.txt"), (200, """{"value":"text/plain:hello"}"""))
    assertEquals(send("GET", "/vault/objects")._2, """["a.txt"]""")
    val bucket = kit.storage().defaultBucket()
    assertEquals(String(bucket.getBytes("a.txt").get, UTF_8), "hello")
    assertEquals(bucket.stat("a.txt").get.length, 5L)
    bucket.delete("a.txt")
    assertEquals(send("GET", "/vault/objects/a.txt")._2, """{"value":"(none)"}""")
  }

  test("a bucket the service does not name is refused, saying which it names") {
    val e = intercept[CommandException](kit.storage().bucket("photos"))
    assert(e.getMessage.contains("no bucket named 'photos'"), e.getMessage)
  }

  test("the endpoint calls another service with a header of its own and reads the answer") {
    kit
      .service("stock")
      .answer(r =>
        ServiceResponse(200, "text/plain", s"7 of ${r.path}".getBytes(UTF_8), java.util.List.of())
      )
    assertEquals(send("GET", "/vault/stock/sku-1"), (200, """{"value":"200 7 of /stock/sku-1"}"""))
    val request = kit.service("stock").requests().asScala.last
    assertEquals(
      request.headers.asScala.find(_._1.equalsIgnoreCase("authorization")).map(_._2),
      Some("Bearer vault")
    )
  }

  test("a service's identity is its project and name, or its local name") {
    val local = ServiceIdentity.resolve(ConfigFactory.parseString("ankka.service.name = vault"))
    assertEquals(Stores.identity(local).toString, "local/vault")
    assertEquals(Stores.identity(local).service.get, "vault")
    assert(Stores.identity(local).project.isEmpty)
  }
