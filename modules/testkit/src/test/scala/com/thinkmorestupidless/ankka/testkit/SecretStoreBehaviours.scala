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
 * The secret store in a whole running service: every scenario of
 * `features/secrets/secret-store.feature` that does not name the database or the secret key, run on
 * each secret backend by a suite of its own (`PostgresSecretStoreSuite`,
 * `SecretManagerSecretStoreSuite`). Where the two must differ — where a kept value lives — the
 * backend's suite says, through `held`.
 *
 * The cases that could pass while the store leaked are written so they cannot: the dump reads every
 * row of every table the database has, not a list of tables this suite knows, and searches each for
 * the value both as text and as the hex Postgres shows a `bytea` as.
 */
abstract class SecretStoreBehaviours extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  /** The backend this suite's service is started on; called once, before it starts. */
  protected def backend(): SecretBackendChoice

  /**
   * How many copies of `name` the backend holds where it keeps service secrets: rows of
   * `ankka_secrets` (`rows`) on Postgres, enabled versions in the fake on Secret Manager.
   */
  protected def held(name: String, rows: Vector[(String, String)]): Int

  /** What the dump case asserts of the rows of `ankka_secrets` for `name`, beyond no plaintext. */
  protected def storedAsExpected(rows: Vector[String]): Unit

  protected var testKit: AnkkaTestKit = null
  private var server: HttpServer      = null
  private var baseUrl: String         = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => ProviderCredentialsEndpoint(clients.secrets))
    testKit = AnkkaTestKit.start(
      Seq(ChargeWorkflow.descriptor, WalletEntity.descriptor, OrderEntity.descriptor),
      Seq(server),
      secretBackend = backend()
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  protected def secrets = testKit.secrets

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
  protected def dump(): Vector[(String, String)] =
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

  protected def rowsFor(name: String): Vector[String] =
    dump().collect { case ("ankka_secrets", row) if row.contains(s""""name":"$name"""") => row }

  protected def refused(work: => Any): CommandError =
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
    storedAsExpected(rowsFor("dump"))
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
    assertEquals(held("replaced", dump()), 1)
  }

  test("a removed service secret is read as none") {
    secrets.put("removed", "sk-gone")
    secrets.delete("removed")
    assertEquals(secrets.get("removed"), None)
    assertEquals(held("removed", dump()), 0)
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
