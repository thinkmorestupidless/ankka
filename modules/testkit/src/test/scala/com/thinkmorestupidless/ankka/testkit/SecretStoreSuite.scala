package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * The secret store on a real database: every scenario of `features/secrets/secret-store.feature`.
 *
 * The cases that could pass while the store leaked are written so they cannot: the dump reads every
 * row of every table the database has, not a list of tables this suite knows, and searches each for
 * the value both as text and as the hex Postgres shows a `bytea` as.
 */
class SecretStoreSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => ProviderCredentialsEndpoint(clients.secrets))
    testKit = AnkkaTestKit.start(
      Seq(ChargeWorkflow.descriptor, WalletEntity.descriptor, OrderEntity.descriptor),
      Seq(server)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def secrets = testKit.secrets

  private def post(path: String, body: String): (Int, String) =
    val response = http.send(
      JdkRequest
        .newBuilder(URI.create(baseUrl + path))
        .header("Content-Type", "application/json")
        .POST(JdkRequest.BodyPublishers.ofString(body))
        .build(),
      JdkResponse.BodyHandlers.ofString()
    )
    (response.statusCode, response.body)

  /** Every row of every table in the service's database, each as one line of text. */
  private def dump(): Vector[(String, String)] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system
    val database                                      = Database()
    val tables = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        )
      )(_.get(0, classOf[String])),
      30.seconds
    )
    assert(tables.contains("event_journal") && tables.contains("ankka_secrets"), tables.toString)
    tables.flatMap { table =>
      Await
        .result(
          database.query(SqlFragment.raw(s"""SELECT row_to_json(t)::text FROM "$table" t"""))(
            _.get(0, classOf[String])
          ),
          30.seconds
        )
        .map(table -> _)
    }

  private def hex(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).map(b => f"${b & 0xff}%02x").mkString

  private def rowsFor(name: String): Vector[String] =
    dump().collect { case ("ankka_secrets", row) if row.contains(s""""name":"$name"""") => row }

  private def refused(work: => Any): CommandError =
    val error = intercept[CommandError](work)
    assertEquals(error.code, ErrorCode.BadRequest, error.message)
    error

  test("a service secret kept by one component is read by another") {
    val (status, _) = post("/credentials/", """{"name":"provider/acme","value":"sk-acme-1"}""")
    assertEquals(status, 204)
    val charge = testKit.componentClient.forWorkflow(EntityId("ch-1"))
    assertEquals(charge.call(ChargeWorkflow.start).invoke(Charge("acme", 10)), Done)
    val state = testKit.eventually("the step read the credential") {
      Some(charge.call(ChargeWorkflow.status).invoke()).filter(_.status == "charged")
    }
    assertEquals(state.credentialLength, "sk-acme-1".length)
  }

  test("the database holds a service secret only encrypted, and only in the secret store") {
    val value = "sk-dump-7f3a9c"
    secrets.put("dump", value)
    // Something in the journal too, so the search covers rows that exist.
    testKit.componentClient
      .forKeyValueEntity(EntityId("w-dump"))
      .call(WalletEntity.deposit)
      .invoke(5): Unit
    val rows  = dump()
    val leaks = rows.filter((_, row) => row.contains(value) || row.contains(hex(value)))
    assertEquals(leaks, Vector.empty, s"the value is readable in: ${leaks.map(_._1).distinct}")
    val stored = rowsFor("dump")
    assertEquals(stored.size, 1, stored.toString)
    assert(
      stored.head.contains("\"ciphertext\":\"\\\\x01"),
      "the stored form begins with its version"
    )
  }

  test("a service secret is still read after the service restarts") {
    secrets.put("restart", "sk-restart-1")
    testKit.restartService()
    assertEquals(secrets.get("restart"), Some("sk-restart-1"))
  }

  test("a service secret kept on one instance is read on another") {
    secrets.put("peer", "sk-peer-1")
    val peer = testKit.startPeer(Seq.empty)
    try assertEquals(peer.service.secrets.get("peer"), Some("sk-peer-1"))
    finally peer.stop()
  }

  test("a service secret that was never kept is read as none, without a failure") {
    assertEquals(secrets.get("never-kept"), None)
  }

  test("keeping a service secret again replaces its value") {
    secrets.put("replaced", "sk-old")
    secrets.put("replaced", "sk-new")
    assertEquals(secrets.get("replaced"), Some("sk-new"))
    assertEquals(rowsFor("replaced").size, 1)
  }

  test("a removed service secret is read as none") {
    secrets.put("removed", "sk-gone")
    secrets.delete("removed")
    assertEquals(secrets.get("removed"), None)
    assertEquals(rowsFor("removed"), Vector.empty)
  }

  test("a workflow reads a service secret in a step and not in a command") {
    secrets.put("provider/globex", "sk-globex")
    val charge = testKit.componentClient.forWorkflow(EntityId("ch-peek"))
    val error  = intercept[CommandError](charge.call(ChargeWorkflow.peek).invoke("globex"))
    assertEquals(error.code, ErrorCode.BadRequest)
    assert(error.message.contains("in a step"), error.message)
  }

  test("a value larger than a service secret may be is refused") {
    val error = refused(secrets.put("large", "x" * 65537))
    assert(error.message.contains("65536"), error.message)
    assertEquals(secrets.get("large"), None)
  }

  test("an empty value is refused") {
    refused(secrets.put("empty", ""))
    assertEquals(secrets.get("empty"), None)
  }

  test("a service secret's name may have a slash") {
    secrets.put("provider/initech", "sk-initech")
    assertEquals(secrets.get("provider/initech"), Some("sk-initech"))
  }

  test("a service secret's name is refused when it breaks the rule for names") {
    for name <- Vector("provider acme", "provider:acme", "") do
      val error = refused(secrets.put(name, "sk-1"))
      assert(error.message.contains("'.', '_', '-' or '/'"), error.message)
  }

  test("a service secret's name longer than the limit is refused") {
    val error = refused(secrets.put("a" * 254, "sk-1"))
    assert(error.message.contains("253"), error.message)
  }

  // The cases below restart the service under other keys; each puts back the kit's own.

  test("a service with no secret key starts, and cannot keep or read a service secret") {
    val key = testKit.secretKey
    try
      testKit.restartService(secretKey = None)
      for work <- Vector(() => secrets.put("acme", "sk-1"), () => secrets.get("acme")) do
        val error = intercept[CommandError](work())
        assertEquals(error.code, ErrorCode.Internal)
        assert(error.message.contains("ANKKA_SECRET_KEY"), error.message)
      // Removing decrypts nothing, so it needs no key.
      secrets.delete("acme")
    finally testKit.restartService(secretKey = key)
  }

  test("a service secret cannot be read with another secret key") {
    val key = testKit.secretKey
    secrets.put("rotated", "sk-rotated")
    try
      testKit.restartService(secretKey = Some(AnkkaTestKit.generateSecretKey()))
      val error = intercept[CommandError](secrets.get("rotated"))
      assertEquals(error.code, ErrorCode.Internal)
      assert(error.message.contains("not the one 'rotated' was kept with"), error.message)
    finally testKit.restartService(secretKey = key)
    assertEquals(secrets.get("rotated"), Some("sk-rotated"))
  }

  test("a service whose secret key is malformed does not start") {
    val key = testKit.secretKey
    val failure = intercept[IllegalArgumentException](
      testKit.restartService(secretKey = Some("not-a-key"))
    )
    assert(failure.getMessage.contains("ANKKA_SECRET_KEY"), failure.getMessage)
    assert(!failure.getMessage.contains("not-a-key"), "the refusal never repeats the key")
    testKit.restartService(secretKey = key)
    assertEquals(secrets.get("provider/initech"), Some("sk-initech"))
  }
