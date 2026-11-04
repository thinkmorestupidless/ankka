package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given
import com.thinkmorestupidless.ankka.runtime.{Database, RotatingTls}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*

/** Feature 042, T052: the keyring end to end, over mutual TLS from a test authority. */
class KeyringServerSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit        = null
  private var keyring: Keyring.Started = null
  private val pki                      = TestPki.root("keyring-suite")
  private val dir                      = Files.createTempDirectory("keyring-suite")

  private def leaf(name: String, uri: String, dns: Seq[String] = Nil): Path =
    pki.issue(uris = Seq(uri), dnsNames = dns).writeTo(dir.resolve(name))

  private val keyringDir = leaf("keyring", Main.Identity, Seq("localhost"))
  private val grantsDir  = Files.createDirectories(dir.resolve("grants"))
  Files.createDirectories(grantsDir.resolve("brand"))
  Files.writeString(
    grantsDir.resolve("brand").resolve("grants.json"),
    """{"project":"brand","grants":[{"id":"g1","grantee":"service:analytics/reader","kind":"topic","topic":"players","right":"consume","decrypt":true}]}"""
  ): Unit

  /** A copy of the log the suite holds, which fails while `away`. */
  private final class Held extends LogCopy:
    val name                               = "held"
    @volatile var away                     = true
    @volatile var entries                  = Vector.empty[LogEntry]
    def after(seq: Long): Vector[LogEntry] =
      if away then throw IllegalStateException("away") else entries.filter(_.seq > seq)
  private val held = Held()

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq.empty)
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    keyring = Keyring.start(
      Database(),
      RootKey.Local(IArray.fill[Byte](32)(3), "test"),
      ProjectGrants.fromDirectory(grantsDir),
      Vector(held),
      RotatingTls(keyringDir, 1.minute),
      port = 0,
      probePort = 0,
      machines = None,
      replica = "keyring-0",
      retryEvery = 100.millis
    )

  override def afterAll(): Unit =
    if keyring != null then keyring.stop()
    if kit != null then kit.stop()

  private def client(name: String, uri: String): HttpClient =
    HttpClient
      .newBuilder()
      .sslContext(RotatingTls(leaf(name, uri), 1.minute).sslContext)
      .connectTimeout(Duration.ofSeconds(5))
      .build()

  private lazy val players      = client("players", "ankka://brand/players")
  private lazy val reader       = client("reader", "ankka://analytics/reader")
  private lazy val counter      = client("counter", "ankka://analytics/counter")
  private lazy val controlPlane = client("cp", "ankka://platform/controlplane")
  private def base              = s"https://localhost:${keyring.server.ports.main}"

  private def call(as: HttpClient, method: String, path: String, body: String = ""): (Int, String) =
    val request = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .method(
        method,
        if body.isEmpty then HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body)
      )
      .build()
    val response = as.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def probe(path: String): Int =
    HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${keyring.server.ports.probe}$path"))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .statusCode

  private def eventually[A](what: String)(attempt: => Option[A]): A =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var found    = attempt
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      found = attempt
    found.getOrElse(fail(s"timed out waiting for $what"))

  test("nothing is answered until both copies of the log are read, then the log is applied") {
    assertEquals(probe("/ready"), 503)
    assertEquals(
      call(
        players,
        "POST",
        "/v1/keys",
        """{"project":"brand","subject":"player/1","create":true}"""
      )._1,
      503
    )
    held.entries = Vector(LogEntry(1, "brand", "player/replayed", "rid-1", java.time.Instant.now()))
    held.away = false
    eventually("ready")(Option.when(probe("/ready") == 200)(()))
    val (status, body) = call(
      players,
      "POST",
      "/v1/keys",
      """{"project":"brand","subject":"player/replayed","create":true}"""
    )
    assertEquals(status, 200, body)
    assertEquals(Wire.read[KeyReply](body), Right(KeyReply(destroyed = Some(true))))
    assertEquals(probe("/status"), 200)
  }

  test(
    "a service makes and reads its keys; another project reads only under its grant; the control plane never"
  ) {
    eventually("ready")(Option.when(probe("/ready") == 200)(()))
    val (made, body) = call(
      players,
      "POST",
      "/v1/keys",
      """{"project":"brand","subject":"player/8c1f","create":true}"""
    )
    assertEquals(made, 200, body)
    val key = Wire.read[KeyReply](body).toOption.flatMap(_.key).getOrElse(fail(body))
    assertEquals(
      Wire
        .read[KeyReply](
          call(players, "POST", "/v1/keys", """{"project":"brand","subject":"player/8c1f"}""")._2
        )
        .toOption
        .flatMap(_.key),
      Some(key)
    )
    val (granted, gb) =
      call(reader, "POST", "/v1/keys", """{"project":"brand","subject":"player/8c1f"}""")
    assertEquals(granted, 200, gb)
    val (refused, rb) =
      call(counter, "POST", "/v1/keys", """{"project":"brand","subject":"player/8c1f"}""")
    assertEquals(refused, 403)
    assert(Wire.read[KeyReply](rb).toOption.flatMap(_.refused).nonEmpty, rb)
    assertEquals(
      call(controlPlane, "POST", "/v1/keys", """{"project":"brand","subject":"player/8c1f"}""")._1,
      403
    )
    assertEquals(
      call(players, "POST", "/v1/keys", """{"project":"brand","subject":"bad subject"}""")._1,
      400
    )
    assertEquals(call(players, "POST", "/v1/lookup-keys", """{"project":"brand"}""")._1, 200)
  }

  test(
    "an erasure destroys the key, notifies the instance on its socket, and is complete once it acks"
  ) {
    eventually("ready")(Option.when(probe("/ready") == 200)(()))
    call(
      players,
      "POST",
      "/v1/keys",
      """{"project":"brand","subject":"player/9d2e","create":true}"""
    ): Unit
    val frames = LinkedBlockingQueue[String]()
    val socket = players
      .newWebSocketBuilder()
      .buildAsync(
        URI.create(s"wss://localhost:${keyring.server.ports.main}/v1/notices"),
        new WebSocket.Listener:
          private val text = StringBuilder()
          override def onText(ws: WebSocket, data: CharSequence, last: Boolean)
              : CompletionStage[?] =
            text.append(data)
            if last then
              frames.add(text.toString)
              text.clear()
            ws.request(1)
            null
      )
      .get(10, TimeUnit.SECONDS)
    socket
      .sendText(
        Wire.write[NoticeFrame](NoticeFrame.Hello("brand", "players", "players-0", 1)),
        true
      )
      .get(5, TimeUnit.SECONDS): Unit
    eventually("the connection recorded")(
      Option.when(
        scala.concurrent.Await
          .result(
            Database()(using kit.service.system).query(
              com.thinkmorestupidless.ankka.runtime.SqlFragment
                .raw("SELECT instance FROM keyring.notice_connection WHERE instance = 'players-0'")
            )(_.get("instance", classOf[String])),
            10.seconds
          )
          .nonEmpty
      )(())
    )
    val (destroyed, db) = call(
      controlPlane,
      "POST",
      "/v1/erasures",
      """{"seq":7,"project":"brand","subject":"player/9d2e"}"""
    )
    assertEquals(destroyed, 200, db)
    val notice = Wire.frame(frames.poll(10, TimeUnit.SECONDS)).toOption.getOrElse(fail("no notice"))
    assertEquals(notice, NoticeFrame.Notice(7, "brand", "player/9d2e", rerun = false))
    assertEquals(
      Wire.read[KeyReply](
        call(players, "POST", "/v1/keys", """{"project":"brand","subject":"player/9d2e"}""")._2
      ),
      Right(KeyReply(destroyed = Some(true)))
    )
    def statusOf =
      Wire.read[ErasureStatus](call(controlPlane, "GET", "/v1/erasures/7")._2).toOption.get
    assertEquals(
      statusOf.services.map(s => (s.service, s.completedAt.isEmpty)),
      Vector("players" -> true)
    )
    socket
      .sendText(
        Wire.write[NoticeFrame](NoticeFrame.Ack(7, "players", "players-0", Completion(1, 0, 0))),
        true
      )
      .get(5, TimeUnit.SECONDS): Unit
    eventually("players complete")(statusOf.services.headOption.flatMap(_.completedAt))
    assertEquals(call(controlPlane, "POST", "/v1/erasures/7/resend")._1, 202)
    assertEquals(
      Wire.frame(frames.poll(10, TimeUnit.SECONDS)).toOption.collect { case n: NoticeFrame.Notice =>
        n.rerun
      },
      Some(true)
    )
    // Only the control plane orders, and a service hears only its own project's notices as itself.
    assertEquals(
      call(
        players,
        "POST",
        "/v1/erasures",
        """{"seq":8,"project":"brand","subject":"player/x"}"""
      )._1,
      403
    )
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS): Unit
  }

  test("the keyring refuses to start on a certificate that is not the keyring's") {
    val other = leaf("other", "ankka://platform/models")
    assert(
      Main.identityMismatch(RotatingTls(other, 1.minute), other).exists(_.contains(Main.Identity))
    )
    assertEquals(Main.identityMismatch(RotatingTls(keyringDir, 1.minute), keyringDir), None)
    val problems = collection.mutable.Buffer[String]()
    assertEquals(Main.run(Array.empty, _ => None, problems += _), 1)
    assert(problems.exists(_.contains(KeyringSettings.ControlPlane)), problems.toString)
  }
