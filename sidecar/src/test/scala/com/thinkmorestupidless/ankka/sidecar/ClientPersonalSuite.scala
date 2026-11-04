package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import com.thinkmorestupidless.ankka.runtime.keyring.ErasureHooks
import com.thinkmorestupidless.ankka.sdk.Finality
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Personal data as a process reaches it (feature 042, protocol 1.23): the subject's key from the
 * runtime's keyring, never a value; the erasure notices held until every instance of the process
 * has dropped the key; and the process's handler's finality as the runtime records it.
 */
class ClientPersonalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def logic: ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => None)

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  private def key(subject: String, create: Boolean) =
    await(logic.getSubjectKey(SubjectKeyRequest(subject, create))).reply

  test("a write is handed the subject's key, and a read the same key") {
    val written = key("player/8c1f", create = true)
    assertEquals(written.key.map(_.size), Some(32), written.toString)
    assertEquals(key("player/8c1f", create = false).key, written.key)
  }

  test("an answer names its project, the service's own when the request names none") {
    val own = await(logic.getSubjectKey(SubjectKeyRequest("player/p1", create = true)))
    assertEquals(own.project, testKit.project)
    val named =
      await(logic.getSubjectKey(SubjectKeyRequest("player/p1", project = testKit.project)))
    assertEquals(named.project, testKit.project)
  }

  test("a read of a subject no key was made for, or of an erased one, reads as erased") {
    assertEquals(key("player/never", create = false).erased, Some(true))
    key("player/41d2", create = true).key.getOrElse(fail("no key")): Unit
    testKit.keyring.erase(testKit.project, DataSubject("player/41d2")): Unit
    assertEquals(key("player/41d2", create = false).erased, Some(true))
  }

  test("a write for an erased subject is refused, and a subject outside the rule is named") {
    testKit.keyring.erase(testKit.project, DataSubject("player/77")): Unit
    assertEquals(key("player/77", create = true).erased, Some(true))
    val refused = key("player 77", create = true).error
    assertEquals(refused.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
    assert(
      refused.exists(_.message.contains("a data subject is 1 to 253 characters")),
      refused.toString
    )
  }

  test("a notice is done once every instance holding the stream has acked it, or has gone") {
    given ActorSystem[?] = testKit.service.system
    val notices          = ProcessNotices()
    val notice           = KeyringNotice(7, "brand", "player/8c1f", rerun = false)
    // Nothing holds a stream: nothing is cached, so nothing waits.
    assert(notices.notify(notice).isCompleted)

    val heardA = notices.open("a").runWith(Sink.queue[SubjectNotice]())
    val heardB = notices.open("b").runWith(Sink.queue[SubjectNotice]())
    while !notices.holding("a") || !notices.holding("b") do Thread.sleep(10)
    val done = notices.notify(notice)
    assertEquals(await(heardA.pull()).map(_.subject), Some("player/8c1f"))
    assertEquals(await(heardB.pull()).map(_.seq), Some(7L))
    assert(notices.ack(7, "a"))
    Thread.sleep(200)
    assert(!done.isCompleted, "done before b acked")
    // b's stream closes: a process that went caches nothing.
    heardB.cancel()
    await(done)
    assert(!notices.ack(7, "b"), "an ack after the notice is done waits for nothing")
    heardA.cancel()
  }

  test("the runtime tells the keyring only after the process has dropped the key") {
    given ActorSystem[?] = testKit.service.system
    val notices          = ProcessNotices()
    ErasureHooks.of(testKit.service.system).onProcess(notices.notify)
    try
      val heard = notices.open("p-0").runWith(Sink.queue[SubjectNotice]())
      while !notices.holding("p-0") do Thread.sleep(10)
      val before = testKit.keyring.acks.size
      val seq    = testKit.keyring.erase(testKit.project, DataSubject("player/held"))
      val got    = await(heard.pull()).getOrElse(fail("no notice"))
      assertEquals((got.seq, got.subject), (seq, "player/held"))
      Thread.sleep(500)
      assertEquals(testKit.keyring.acks.size, before, "acked before the process dropped the key")
      assert(notices.ack(seq, "p-0"))
      val deadline = System.nanoTime() + 10.seconds.toNanos
      while testKit.keyring.acks.forall(_.seq != seq) && System.nanoTime() < deadline do
        Thread.sleep(20)
      assert(testKit.keyring.acks.exists(_.seq == seq), testKit.keyring.acks.toString)
      heard.cancel()
    finally ErasureHooks.of(testKit.service.system).onProcess(_ => scala.concurrent.Future.unit)
  }

  // ── A module's two imports (protocol 1.23) ──────────────────────────────────

  import ankka.protocol.v1.wasm.{KeyringOpenRequest, KeyringSealRequest}
  import com.google.protobuf.ByteString

  private def seal(subject: String, json: String, manifest: String = "player-registered") =
    logic.keyringSeal(KeyringSealRequest(subject, manifest, ByteString.copyFromUtf8(json))).reply

  private def open(envelope: ByteString, manifest: String = "player-registered") =
    logic.keyringOpen(KeyringOpenRequest(envelope, manifest)).reply

  test("a module's field is sealed as a Scala field is, and read back") {
    val envelope = seal("player/m1", "\"ada@example.com\"").envelope.getOrElse(fail("no envelope"))
    val text     = envelope.toStringUtf8
    assert(
      text.startsWith(
        s"""{"$$personal":1,"project":"${testKit.project}","subject":"player/m1","c":"""
      ),
      text
    )
    assert(!text.contains("ada@example.com"), text)
    assertEquals(
      open(envelope) match
        case ankka.protocol.v1.wasm.KeyringOpenReply.Reply.Value(v) => Some(v.toStringUtf8)
        case _                                                      => None
      ,
      Some("\"ada@example.com\"")
    )
  }

  test("a module's field under another manifest fails authentication; an erased one reads erased") {
    val envelope = seal("player/m2", "\"bo@example.com\"").envelope.getOrElse(fail("no envelope"))
    val corrupt  = open(envelope, manifest = "deposit-made").error
    assertEquals(corrupt.map(_.details.get("personal")), Some(Some("corrupt")), corrupt.toString)
    testKit.keyring.erase(testKit.project, DataSubject("player/m2")): Unit
    assertEquals(open(envelope).erased, Some(true))
    val refused = seal("player/m2", "\"bo@example.com\"").error
    assertEquals(refused.map(_.code), Some(pb.ErrorCode.CONFLICT), refused.toString)
    assertEquals(seal("player m2", "1").error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
  }

  test("a module's keyring imports are refused outside a call into the module") {
    val host = com.thinkmorestupidless.ankka.sidecar.wasm.HostImports(5.seconds, 5.seconds)
    val e    = intercept[com.thinkmorestupidless.ankka.sidecar.wasm.ImportRefused](
      host.keyringSeal(
        KeyringSealRequest("player/m3", "m", ByteString.copyFromUtf8("1")).toByteArray
      )
    )
    assert(e.getMessage.contains("keyring_seal may not be called outside"), e.getMessage)
  }

  test("a handler's finality is immediate, or after an ISO-8601 duration, and nothing else") {
    assertEquals(RemoteErasureHandler.finality("immediate"), Finality.Immediate)
    assertEquals(RemoteErasureHandler.finality(""), Finality.Immediate)
    assertEquals(RemoteErasureHandler.finality("after:P7D"), Finality.After(7.days))
    for bad <- Seq("soon", "after:a week", "after:-P1D") do
      val e = intercept[IllegalStateException](RemoteErasureHandler.finality(bad))
      assert(e.getMessage.contains(bad), e.getMessage)
  }
