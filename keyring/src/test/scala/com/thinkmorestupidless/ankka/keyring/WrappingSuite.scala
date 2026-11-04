package com.thinkmorestupidless.ankka.keyring

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.runtime.{Database, DatabaseSecretStore, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.secrets.AccessTokens
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Feature 042, T048: the root key, each project's KEK, and the keys they wrap. */
class WrappingSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database        = Database()(using kit.service.system)
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)
  private def secrets                   = DatabaseSecretStore(
    database,
    kit.secretKey.flatMap(k => com.thinkmorestupidless.ankka.runtime.SecretKey.parse(k).toOption)
  )

  private def bytes(k: IArray[Byte]) = IArray.genericWrapArray(k).toVector

  /** An answer's key as bytes, since `IArray` compares by reference. */
  private def keyOf(answer: Wrapping.Answer): Vector[Byte] = answer match
    case Wrapping.Answer.Key(k) => bytes(k)
    case other                  => fail(s"no key: $other")

  test("replicas starting at once make one root key, and every key it wraps reads back") {
    val roots =
      await(Future.sequence((1 to 4).map(_ => Future(RootKey.postgres(secrets, database)))))
    val store = KeyStore(database)
    val first = Wrapping(store, roots.head)
    val key   = await(first.subjectKey("brand", "player/8c1f", create = true)) match
      case Wrapping.Answer.Key(k) => bytes(k)
      case other                  => fail(other.toString)
    // Every other replica, with a fresh cache, unwraps the same key through the same root.
    roots.tail.foreach { root =>
      assertEquals(
        keyOf(
          await(
            Wrapping(KeyStore(database), root).subjectKey("brand", "player/8c1f", create = false)
          )
        ),
        key
      )
    }
    // The store holds the key wrapped, never in the clear.
    val stored = await(
      database.query(
        SqlFragment.raw("SELECT wrapped FROM keyring.subject_key WHERE subject = 'player/8c1f'")
      )(
        _.get("wrapped", classOf[Array[Byte]])
      )
    )
    assert(stored.nonEmpty)
    assert(!stored.exists(_.sliding(key.length).exists(_.toVector == key)))
    assertEquals(
      bytes(await(first.lookupKey("brand"))),
      bytes(await(Wrapping(KeyStore(database), roots(1)).lookupKey("brand")))
    )
  }

  test("a wrapped key moved to another data subject does not unwrap") {
    val root = RootKey.postgres(secrets, database)
    val w    = Wrapping(KeyStore(database), root)
    await(w.subjectKey("brand", "player/moved", create = true)): Unit
    await(
      database.execute(
        SqlFragment.raw(
          "INSERT INTO keyring.subject_key (project, subject, wrapped) SELECT project, 'player/thief', wrapped FROM keyring.subject_key WHERE subject = 'player/moved'"
        )
      )
    ): Unit
    intercept[IllegalStateException](await(w.subjectKey("brand", "player/thief", create = false)))
  }

  /** Cloud KMS on loopback: encrypt and decrypt with a key of its own, answering as KMS does. */
  private final class FakeKms:
    val calls          = ConcurrentLinkedQueue[(String, String)]()
    private val key    = IArray.fill[Byte](32)(7)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/",
      exchange =>
        val path                = exchange.getRequestURI.getPath
        val body                = String(exchange.getRequestBody.readAllBytes(), UTF_8)
        calls.add(path -> exchange.getRequestHeaders.getFirst("Authorization"))
        def field(name: String) =
          Base64.getDecoder.decode(s""""$name":"([^"]*)"""".r.findFirstMatchIn(body).get.group(1))
        val ad    = field("additionalAuthenticatedData")
        val reply =
          if path.endsWith(":encrypt") then
            val c = com.thinkmorestupidless.ankka.core.PersonalCipher
              .seal(key, None, field("plaintext"), ad)
            s"""{"ciphertext":"${Base64.getEncoder.encodeToString(c)}"}"""
          else
            com.thinkmorestupidless.ankka.core.PersonalCipher
              .open(key, field("ciphertext"), ad) match
              case Some(p) => s"""{"plaintext":"${Base64.getEncoder.encodeToString(p)}"}"""
              case None    => ""
        val status = if reply.isEmpty then 400 else 200
        val out    = reply.getBytes(UTF_8)
        exchange.sendResponseHeaders(status, if out.isEmpty then -1 else out.length.toLong)
        if out.nonEmpty then exchange.getResponseBody.write(out)
        exchange.close()
    )
    server.start()
    def base: String = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    def stop(): Unit = server.stop(0)

  test(
    "on Google Cloud each KEK is wrapped by KMS, as the pod, and never by a key the keyring holds"
  ) {
    val kms  = FakeKms()
    val name = "projects/p/locations/europe-west2/keyRings/ankka/cryptoKeys/keyring"
    try
      val root =
        RootKey.kms(name, RootKey.CloudKms(name, AccessTokens.fixed("pod-token"), kms.base))
      val w   = Wrapping(KeyStore(database), root)
      val key = await(w.subjectKey("kms-project", "player/1", create = true))
      assert(key.isInstanceOf[Wrapping.Answer.Key])
      // A second keyring, with no KEK in memory, asks KMS to unwrap it.
      assertEquals(
        keyOf(
          await(
            Wrapping(KeyStore(database), root).subjectKey("kms-project", "player/1", create = false)
          )
        ),
        keyOf(key)
      )
      val made = kms.calls.asScala.toVector
      assertEquals(
        made.map(_._1).distinct.sorted,
        Vector(s"/v1/$name:decrypt", s"/v1/$name:encrypt")
      )
      assert(made.forall(_._2 == "Bearer pod-token"), made.toString)
      val refs = await(
        database.query(
          SqlFragment.raw("SELECT root_ref FROM keyring.kek WHERE project = 'kms-project'")
        )(
          _.get("root_ref", classOf[String])
        )
      )
      assertEquals(refs, Vector(s"kms:$name"))
    finally kms.stop()
  }
