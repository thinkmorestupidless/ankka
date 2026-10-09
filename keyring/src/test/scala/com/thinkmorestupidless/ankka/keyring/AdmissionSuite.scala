package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.erasure.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Another project's keys (FR-025, FR-026, R12): a channel of `payments` is given a key of `brand`
 * only while a grant allows it — asked at each fetch, so a revoked grant refuses the next one — is
 * never made a key of `brand`, and every refusal is counted on the subject's key.
 */
class AdmissionSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  @volatile private var granted: Set[(String, String)] = Set.empty
  private val state = KeyringState(
    (reader, owner) => granted((reader, owner)),
    LogSources.none,
    ackWithin = 2.seconds,
    applySchema = false
  )
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Keyring.components,
      Seq(
        KeyringRuntime(state),
        HttpServer.at("127.0.0.1", 0)(clients => KeyringEndpoint(clients, state))
      ),
      keyring = None
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit = granted = Set.empty

  private def base = kit.service.boundAddresses.find(_.startsWith("http")).get

  private final class Quiet extends KeyringListener:
    val logs                                 = ConcurrentLinkedQueue[Vector[LogEntry]]()
    def log(entries: Vector[LogEntry]): Unit = logs.add(entries): Unit
    def destroyed(project: String, subject: String, erasureId: String): Unit = ()
    def apply(order: ErasureOrder): Unit                                     = ()
    def closed(reason: String): Unit                                         = ()

  private def open(project: String, service: String, reads: Set[String] = Set.empty) =
    val listener = Quiet()
    val client   = KeyringClient(URI.create(base.replaceFirst("^http", "ws") + "/channel"), None)
    client.open(Hello(project, service, s"$service-1", reads, None), listener)
    kit.eventually(s"$service's channel open")(Option(listener.logs.peek()))
    client

  private def refusals(project: String, subject: String): Long =
    kit.componentClient
      .forKeyValueEntity(EntityId(s"$project/$subject"))
      .call(SubjectKeyEntity.state)
      .invoke()
      .refusals

  test("a grant that allows decryption gives another project the key the producing project made") {
    granted = Set("payments" -> "brand")
    val brand    = open("brand", "players")
    val payments = open("payments", "cardholders", reads = Set("brand"))
    try
      val made = brand.fetch("brand", "player/a1", create = true)
      assert(made.isInstanceOf[KeyResult.Available], made.toString)
      assert(payments.fetch("brand", "player/a1", create = false).isInstanceOf[KeyResult.Available])
    finally
      brand.close()
      payments.close()
  }

  test("without a grant the key is refused, and the refusal is counted on the subject's key") {
    val brand    = open("brand", "players")
    val payments = open("payments", "cardholders", reads = Set("brand"))
    try
      brand.fetch("brand", "player/a2", create = true): Unit
      assert(payments.fetch("brand", "player/a2", create = false).isInstanceOf[KeyResult.Refused])
      kit.eventually("the refusal counted")(Option.when(refusals("brand", "player/a2") >= 1)(()))
    finally
      brand.close()
      payments.close()
  }

  test("a grant revoked after the channel opened refuses the next fetch") {
    granted = Set("payments" -> "brand")
    val brand    = open("brand", "players")
    val payments = open("payments", "cardholders", reads = Set("brand"))
    try
      brand.fetch("brand", "player/a3", create = true): Unit
      assert(payments.fetch("brand", "player/a3", create = false).isInstanceOf[KeyResult.Available])
      granted = Set.empty
      assert(payments.fetch("brand", "player/a3", create = false).isInstanceOf[KeyResult.Refused])
    finally
      brand.close()
      payments.close()
  }

  test("a grantee is never made a key of the producing project") {
    granted = Set("payments" -> "brand")
    val payments = open("payments", "cardholders", reads = Set("brand"))
    try assertEquals(payments.fetch("brand", "player/never", create = true), KeyResult.Unknown)
    finally payments.close()
  }
