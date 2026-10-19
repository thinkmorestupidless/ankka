package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.sdk.BucketExposure
import com.thinkmorestupidless.ankka.sidecar.wasm.{HostImports, ImportRefused}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, StorageBackendChoice}
import org.apache.pekko.actor.typed.ActorSystem

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The service's object storage as a module reaches it (protocol 1.20): `ClientLogic.storage`, which
 * the `storage` import delegates to, against the test kit's store in memory with the unnamed
 * bucket, a public bucket `photos` and a signed bucket `uploads`.
 */
class ClientStorageSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq.empty,
      storage = StorageBackendChoice.Memory(
        buckets = Map("photos" -> BucketExposure.Public, "uploads" -> BucketExposure.Signed)
      )
    )
  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def logic: ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => None)

  private def ask(request: StorageRequest): StorageReply =
    Await.result(logic.storage(request), 30.seconds)

  private def put(name: String, body: Array[Byte], bucket: Option[String] = None) =
    ask(
      StorageRequest(
        bucket = bucket,
        op = StorageRequest.Op.PUT,
        name = name,
        contentType = "text/plain",
        body = Some(ByteString.copyFrom(body))
      )
    )

  private def op(o: StorageRequest.Op, name: String, bucket: Option[String] = None) =
    ask(StorageRequest(bucket = bucket, op = o, name = name))

  private def refusal(reply: StorageReply): pb.Error =
    reply.reply.error.getOrElse(fail(s"not refused: $reply"))

  test("an object kept is read back with its content type, length and when it was kept") {
    assert(put("notes/a.txt", "hello".getBytes(UTF_8)).reply.isDone)
    val got = op(StorageRequest.Op.GET, "notes/a.txt").reply.`object`.getOrElse(fail("no object"))
    assertEquals(got.body.toStringUtf8, "hello")
    assertEquals(got.contentType, "text/plain")
    assertEquals(got.length, 5L)
    assert(got.lastKept.nonEmpty)
    val info = op(StorageRequest.Op.STAT, "notes/a.txt").reply.info.getOrElse(fail("no info"))
    assertEquals((info.name, info.contentType, info.length), ("notes/a.txt", "text/plain", 5L))
  }

  test("a get or a stat of nothing answers done, with no error") {
    assert(op(StorageRequest.Op.GET, "never").reply.isDone)
    assert(op(StorageRequest.Op.STAT, "never").reply.isDone)
  }

  test("a listing answers the names under a prefix, in order, and pages at a thousand") {
    for n <- Vector("list/b", "list/a", "other/c") do put(n, Array[Byte](1)): Unit
    val listed = op(StorageRequest.Op.LIST, "list/").reply.entries.getOrElse(fail("no entries"))
    assertEquals(listed.entries.map(_.name), Seq("list/a", "list/b"))
    assertEquals(listed.next, None)
    for n <- 0 until 1001 do testKit.storage.default.putBytes(f"many/$n%04d", Array[Byte](1), "x/y")
    val first = op(StorageRequest.Op.LIST, "many/").reply.entries.getOrElse(fail("no entries"))
    assertEquals(first.entries.size, 1000)
    assertEquals(first.next, Some("many/0999"))
    val rest = ask(
      StorageRequest(op = StorageRequest.Op.LIST, name = "many/", continuation = first.next)
    ).reply.entries.getOrElse(fail("no entries"))
    assertEquals(rest.entries.map(_.name), Seq("many/1000"))
    assertEquals(rest.next, None)
  }

  test("a deletion removes the object, and a second removes nothing and is not an error") {
    put("gone", Array[Byte](1)): Unit
    assert(op(StorageRequest.Op.DELETE, "gone").reply.isDone)
    assert(op(StorageRequest.Op.DELETE, "gone").reply.isDone)
    assert(op(StorageRequest.Op.GET, "gone").reply.isDone)
  }

  test("a put above the bound is refused naming it, and nothing is kept") {
    val e = refusal(put("big", new Array[Byte](ClientLogic.MaxBodyBytes + 1)))
    assertEquals(e.code, pb.ErrorCode.BAD_REQUEST)
    assert(e.message.contains("4000000"), e.message)
    assertEquals(testKit.storage.default.stat("big"), None)
    assert(put("at-bound", new Array[Byte](ClientLogic.MaxBodyBytes)).reply.isDone)
  }

  test("a get of an object above the bound is refused naming it") {
    testKit.storage.default.putBytes("large", new Array[Byte](ClientLogic.MaxBodyBytes + 1), "x/y")
    val e = refusal(op(StorageRequest.Op.GET, "large"))
    assertEquals(e.code, pb.ErrorCode.BAD_REQUEST)
    assert(e.message.contains("4000000"), e.message)
  }

  test("a name that breaks the rule is refused with the rule's words") {
    val e = refusal(put("/rooted", Array[Byte](1)))
    assertEquals(e.code, pb.ErrorCode.BAD_REQUEST)
    assert(e.message.contains("must not begin with '/'"), e.message)
  }

  test("a bucket the service does not have is unavailable, naming the ones it has") {
    val e = refusal(op(StorageRequest.Op.GET, "x", Some("videos")))
    assertEquals(e.code, pb.ErrorCode.UNAVAILABLE)
    assert(e.message.contains("'videos'") && e.message.contains("'photos'"), e.message)
  }

  test("a public bucket answers an address; the unnamed bucket refuses one") {
    val url = op(StorageRequest.Op.URL, "front.jpg", Some("photos")).reply.url
    assert(url.exists(_.endsWith("front.jpg")), url.toString)
    assertEquals(refusal(op(StorageRequest.Op.URL, "front.jpg")).code, pb.ErrorCode.BAD_REQUEST)
  }

  test("a signed bucket answers a signed URL; a public one refuses it") {
    val signed = ask(
      StorageRequest(
        bucket = Some("uploads"),
        op = StorageRequest.Op.SIGNED_URL,
        name = "in/a.pdf",
        access = StorageRequest.Access.KEEP,
        expiresSeconds = 60
      )
    ).reply.url
    assert(signed.exists(_.contains("a.pdf")), signed.toString)
    val e = refusal(op(StorageRequest.Op.SIGNED_URL, "x", Some("photos")))
    assertEquals(e.code, pb.ErrorCode.BAD_REQUEST)
  }

  test("the storage import is refused outside a call into a module") {
    val imports = HostImports(5.seconds, 5.seconds)
    imports.bind(logic)
    val refused = intercept[ImportRefused](
      imports.storage(StorageRequest(op = StorageRequest.Op.STAT, name = "x").toByteArray)
    )
    assertEquals(refused.importName, "storage")
  }
