package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.erasure.*
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The keyring in-process, as a service reaches it: two channels over real WebSockets, a key made
 * once whichever asks first, an erasure applied over HTTP and told to both, a channel that does not
 * acknowledge closed, another project's key refused, and no key readable in the keyring's database.
 */
class KeyringSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null
  private val state =
    KeyringState(Grants.none, LogSources.none, ackWithin = 2.seconds, applySchema = false)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Keyring.components,
      extensions = Seq(
        KeyringRuntime(state),
        HttpServer.at("127.0.0.1", 0)(clients => KeyringEndpoint(clients, state))
      ),
      keyring = None
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def base: String = kit.service.boundAddresses.find(_.startsWith("http")).get

  private final class Recorder(acknowledge: Boolean) extends KeyringListener:
    val logs                                 = ConcurrentLinkedQueue[Vector[LogEntry]]()
    val destroyed                            = ConcurrentLinkedQueue[(String, String, String)]()
    val orders                               = ConcurrentLinkedQueue[ErasureOrder]()
    val closes                               = ConcurrentLinkedQueue[String]()
    @volatile var client: KeyringClient      = null
    def log(entries: Vector[LogEntry]): Unit = logs.add(entries): Unit
    def destroyed(project: String, subject: String, erasureId: String): Unit =
      destroyed.add((project, subject, erasureId))
      if acknowledge then client.ack(erasureId)
    def apply(order: ErasureOrder): Unit = orders.add(order): Unit
    def closed(reason: String): Unit     = closes.add(reason): Unit

  private def open(
      service: String,
      acknowledge: Boolean = true,
      appliedUpTo: Option[Long] = None
  ): (KeyringClient, Recorder) =
    val recorder = Recorder(acknowledge)
    val client   = KeyringClient(URI.create(base.replaceFirst("^http", "ws") + "/channel"), None)
    recorder.client = client
    client.open(Hello("local", service, s"$service-1", Set.empty, appliedUpTo), recorder)
    kit.eventually(s"$service's channel open and its log sent")(Option(recorder.logs.peek()))
    (client, recorder)

  private def apply(erasureId: String, subject: String, sequence: Long): KeyringApi.ErasureStatus =
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"$base/projects/local/erasures"))
          .header("Content-Type", "application/json")
          .POST(
            HttpRequest.BodyPublishers
              .ofByteArray(writeToArray(KeyringApi.ApplyErasure(erasureId, subject, sequence)))
          )
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
    assertEquals(response.statusCode(), 200, String(response.body()))
    readFromArray[KeyringApi.ErasureStatus](response.body())

  private def status(erasureId: String): KeyringApi.ErasureStatus =
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"$base/projects/local/erasures/$erasureId"))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
    readFromArray[KeyringApi.ErasureStatus](response.body())

  private def key(result: KeyResult): String = result match
    case KeyResult.Available(k) => Base64.getEncoder.encodeToString(k)
    case other                  => fail(s"expected a key, got $other")

  test(
    "a subject's key is made on its first write, once, whichever service asks first; never on a read"
  ) {
    val (a, _) = open("players")
    val (b, _) = open("wallet")
    try
      assertEquals(a.fetch("local", "player/fresh", create = false), KeyResult.Unknown)
      val first = key(a.fetch("local", "player/8c1f", create = true))
      assertEquals(key(b.fetch("local", "player/8c1f", create = true)), first)
      assertEquals(key(b.fetch("local", "player/8c1f", create = false)), first)
      kit.assertNoPersonalValue(first)
    finally
      a.close()
      b.close()
  }

  test(
    "an erasure destroys the key, tells every channel, orders the apply, and records the answers"
  ) {
    val (a, ra) = open("players")
    val (b, rb) = open("wallet")
    try
      key(a.fetch("local", "player/ada", create = true)): Unit
      apply("erasure-ada", "player/ada", 7)
      kit.eventually("both channels told")(
        Option.when(ra.destroyed.size == 1 && rb.destroyed.size == 1)(())
      )
      assertEquals(
        ra.orders.asScala.toVector,
        Vector(ErasureOrder("erasure-ada", 7, "player/ada", reapply = false))
      )
      assert(a.fetch("local", "player/ada", create = false).isInstanceOf[KeyResult.Destroyed])
      assert(
        a.fetch("local", "player/ada", create = true).isInstanceOf[KeyResult.Destroyed],
        "a tombstone makes no new key"
      )
      a.completed(Completion("erasure-ada", 7, Duties(true, Vector("profiles"), 2, 0, 0), None))
      b.completed(Completion("erasure-ada", 7, Duties(true, Vector.empty, 0, 0, 0), None))
      val done = kit.eventually("both services complete")(
        Some(status("erasure-ada")).filter(_.completedServices.size == 2)
      )
      assertEquals(done.channels.count(_.acknowledged), 2)
      assert(done.everExisted)
    finally
      a.close()
      b.close()
  }

  test("a service opening its channel is sent every erasure past the highest it applied") {
    apply("erasure-late", "player/late", 9)
    val (c, rc) = open("ledger", appliedUpTo = Some(8))
    try assertEquals(rc.logs.peek().map(_.erasureId), Vector("erasure-late"))
    finally c.close()
  }

  test(
    "a channel that does not acknowledge a notice is closed, so its service drops its whole cache"
  ) {
    val (a, ra) = open("slow", acknowledge = false)
    try
      apply("erasure-slow", "player/slow", 11)
      kit.eventually("the unacknowledging channel closed", 15.seconds)(
        Option.when(ra.closes.contains("unacknowledged"))(())
      )
      assert(
        kit
          .eventually("recorded")(
            Some(status("erasure-slow")).filter(_.channels.exists(_.closedUnacknowledged))
          )
          .channels
          .nonEmpty
      )
    finally a.close()
  }

  test(
    "another project's key is refused without a grant that allows decryption, and the refusal recorded"
  ) {
    val (a, _) = open("players")
    try
      assert(a.fetch("payments", "cardholder/77a0", create = false).isInstanceOf[KeyResult.Refused])
    finally a.close()
  }

  test(
    "an erasure for a subject the keyring never saw leaves a tombstone, so no later write makes a key"
  ) {
    val (a, _) = open("players")
    try
      val applied = apply("erasure-unseen", "player/unseen", 12)
      assert(!applied.everExisted)
      assert(a.fetch("local", "player/unseen", create = true).isInstanceOf[KeyResult.Destroyed])
    finally a.close()
  }
