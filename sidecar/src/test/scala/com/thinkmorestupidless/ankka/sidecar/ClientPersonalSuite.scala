package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Keyring,
  Metadata,
  PersonalCipher
}
import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import com.thinkmorestupidless.ankka.runtime.{CallOrigin, Trace}
import com.thinkmorestupidless.ankka.runtime.keyring.{ErasureHooks, ServiceKeyring}
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sdk.{
  ComponentClient,
  ErasureContext,
  Finality,
  ObjectStorage,
  SecretStore,
  ServiceClients
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** A conversation that answers only the erasure handler, as `answer` says. */
private final class ErasureOnly(answer: RemoteErasureCall => Future[RemoteErasureReport])
    extends Conversation:
  override def invokeErasure(call: RemoteErasureCall): Future[RemoteErasureReport] = answer(call)
  def open(init: Init): InstanceSession                                            = ???
  def handleView(request: ViewRequest): Future[ViewOutcome]                        = ???
  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome]            = ???
  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]] = ???
  def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]]             = ???
  def invokeTool(
      componentId: ComponentId,
      sessionId: String,
      tool: String,
      argumentsJson: String,
      metadata: Metadata
  ): Future[Either[String, String]] = ???
  def checkGuardrail(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String,
      metadata: Metadata
  ): Future[Either[String, Unit]] = ???
  def checkTaskResult(
      componentId: ComponentId,
      taskId: String,
      taskType: String,
      resultJson: String,
      metadata: Metadata
  ): Future[TaskResultVerdict]                                                     = ???
  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]] = ???
  def handleHttpStream(request: HttpForward): Source[String, NotUsed]              = ???
  def reachable(): Boolean                                                         = true

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

  test("a module's erased field is written by the host, under the host's project") {
    val reply = logic
      .keyringSeal(
        KeyringSealRequest("player/m4", "player-registered", ByteString.EMPTY, erased = true)
      )
      .reply
    val text = reply.envelope.map(_.toStringUtf8).getOrElse(fail(reply.toString))
    assertEquals(text, s"""{"$$personal":1,"project":"${testKit.project}","subject":"player/m4"}""")
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

  test("the process's erasure handler is sent the trace and the caller current where it runs") {
    val sent         = new java.util.concurrent.atomic.AtomicReference[Metadata](Metadata.empty)
    val conversation = new ErasureOnly(call =>
      sent.set(call.metadata)
      Future.successful(RemoteErasureReport(0, "immediate", None))
    )
    val handler = RemoteErasureHandler(conversation, 5.seconds)
    val context = new ErasureContext:
      def client: ComponentClient  = testKit.componentClient
      def secrets: SecretStore     = testKit.service.secrets
      def services: ServiceClients = testKit.service.services
      def storage: ObjectStorage   = testKit.service.storage
      def erasureSeq: Long         = 3L
      def rerun: Boolean           = false
    val origin = CallOrigin("players", "erasure-handler")
    Trace.within(41L, 7L, origin)(handler.run(context)(DataSubject("player/t1"))): Unit
    assertEquals(Trace.traceIdOf(sent.get), Some(41L), sent.get.toString)
    assertEquals(Trace.parentSpanIdOf(sent.get), Some(7L), sent.get.toString)
  }

  // ── The keyring out of reach, and a refusal (review, 2026-11-05) ────────────

  private def subjectOfThisRun(prefix: String) =
    s"player/$prefix-${java.util.UUID.randomUUID().toString.take(8)}"

  test("a module's field met in a keyring outage is UNAVAILABLE, never INTERNAL") {
    val subject = subjectOfThisRun("out")
    testKit.keyring.outage(1.minute)
    try
      val sealing = seal(subject, "\"ada@example.com\"").error
      assertEquals(sealing.map(_.code), Some(pb.ErrorCode.UNAVAILABLE), sealing.toString)
      assertEquals(sealing.flatMap(_.details.get("personal")), Some("unavailable"))
      val asked = key(subject, create = true).error
      assertEquals(asked.map(_.code), Some(pb.ErrorCode.UNAVAILABLE), asked.toString)
      assertEquals(asked.flatMap(_.details.get("personal")), Some("unavailable"))
    finally testKit.keyring.recover()
    // Once the keyring answers again, the same write is sealed.
    assert(seal(subject, "\"ada@example.com\"").envelope.isDefined)
  }

  test("another project's key the keyring refuses reads as erased; a write under it is refused") {
    val system   = testKit.service.system
    val own      = ServiceKeyring.of(system)
    val refusing = new Keyring:
      def project: String                                             = own.project
      def key(project: String, subject: DataSubject, create: Boolean) =
        KeyAnswer.Refused("no decrypt grant of 'brand'")
    ServiceKeyring.register(system, refusing)
    try
      val read = await(logic.getSubjectKey(SubjectKeyRequest("player/r1", project = "brand")))
      assertEquals(read.reply.erased, Some(true), read.toString)
      assertEquals(read.project, "brand")
      val write = await(
        logic.getSubjectKey(SubjectKeyRequest("player/r1", create = true, project = "brand"))
      ).reply.error
      assertEquals(write.map(_.code), Some(pb.ErrorCode.FORBIDDEN), write.toString)
      assert(write.exists(_.message.contains("no decrypt grant of 'brand'")), write.toString)
    finally ServiceKeyring.register(system, own)
  }

  // ── A process's notice stream opened again, and a process that never acks ───

  test("a stream opened again under one name keeps hearing notices when the first one ends") {
    given ActorSystem[?] = testKit.service.system
    val notices          = ProcessNotices()
    val first            = notices.open("p").runWith(Sink.queue[SubjectNotice]())
    while !notices.holding("p") do Thread.sleep(10)
    val second = notices.open("p").runWith(Sink.queue[SubjectNotice]())
    // The first stream is ended by the second; its end must not take the second away.
    assertEquals(await(first.pull()), None)
    Thread.sleep(200)
    assert(notices.holding("p"), "the second stream was deregistered by the first one's end")
    val done = notices.notify(KeyringNotice(11, "brand", "player/twice", rerun = false))
    assertEquals(await(second.pull()).map(_.seq), Some(11L))
    Thread.sleep(200)
    assert(!done.isCompleted, "done before the instance acked on the stream it holds")
    assert(notices.ack(11, "p"))
    await(done)
    second.cancel()
  }

  test("an instance that does not ack within the notice bound has its stream ended, and is done") {
    given ActorSystem[?] = testKit.service.system
    val notices          = ProcessNotices(ackBound = 300.millis)
    val heard            = notices.open("hung").runWith(Sink.queue[SubjectNotice]())
    while !notices.holding("hung") do Thread.sleep(10)
    val done = notices.notify(KeyringNotice(12, "brand", "player/hung", rerun = false))
    assertEquals(await(heard.pull()).map(_.seq), Some(12L))
    await(done)
    // The stream was failed, which every SDK answers by dropping every key it holds.
    val ended = scala.util.Try(await(heard.pull()))
    assert(ended.isFailure, s"the stream did not end: $ended")
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while notices.holding("hung") && System.nanoTime() < deadline do Thread.sleep(10)
    assert(!notices.holding("hung"))
  }

  test("a channel unconfirmed past the notice bound ends every process stream, so it drops all") {
    val keyring = com.thinkmorestupidless.ankka.testkit.InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq.empty,
      settings = com.typesafe.config.ConfigFactory.parseString(
        "ankka.keyring.notice-bound = 1s\nankka.keyring.cache.outage-bound = 1s"
      ),
      keyring = keyring
    )
    try
      given ActorSystem[?] = kit.service.system
      val notices          = ProcessNotices()
      notices.attach(ErasureHooks.of(kit.service.system))
      val held = await(
        ClientLogic(kit.service, settings, () => None)
          .getSubjectKey(SubjectKeyRequest("player/lapse", true))
      ).reply
      assert(held.key.isDefined, s"no key: $held")
      val heard = notices.open("p").runWith(Sink.queue[SubjectNotice]())
      while !notices.holding("p") do Thread.sleep(10)
      // The keyring stops confirming the channel; within the bound and a check, the stream ends.
      keyring.outage(1.hour)
      val ended = scala.util.Try(Await.result(heard.pull(), 10.seconds))
      assert(
        ended.failed.toOption.exists(!_.isInstanceOf[java.util.concurrent.TimeoutException]),
        s"the stream did not end: $ended"
      )
      val deadline = System.nanoTime() + 5.seconds.toNanos
      while notices.holding("p") && System.nanoTime() < deadline do Thread.sleep(10)
      assert(!notices.holding("p"))
    finally kit.stop()
  }

  // ── An erasure request's not-before ─────────────────────────────────────────

  test("a not-before is exactly a date, or an instant at midnight UTC, and nothing else") {
    assertEquals(ClientLogic.notBefore("2031-10-08"), java.time.LocalDate.of(2031, 10, 8))
    assertEquals(ClientLogic.notBefore("2031-10-08T00:00:00Z"), java.time.LocalDate.of(2031, 10, 8))
    for bad <- Seq("2031-10-08garbage", "2031-10-08T13:45:00Z", "2031-10-08T00:00:00", "soon", "")
    do
      val e = intercept[CommandError](ClientLogic.notBefore(bad))
      assertEquals(e.code, ErrorCode.BadRequest)
      assert(e.message.contains(s"'$bad'"), e.message)
  }

  // ── A field read from another project, written again (FR-027) ───────────────

  test("a module's field read from another project is sealed again under that project's key") {
    val subject = DataSubject(subjectOfThisRun("foreign"))
    val brand   = s"brand-${java.util.UUID.randomUUID().toString.take(6)}"
    // The producing project holds the subject's key; this service reads it under a grant.
    val key = testKit.keyring.key(brand, subject, create = true) match
      case KeyAnswer.Key(k) => k
      case other            => fail(other.toString)
    def sealUnder(s: DataSubject, json: String) = logic
      .keyringSeal(
        KeyringSealRequest(s.value, "card", ByteString.copyFromUtf8(json), project = brand)
      )
      .reply
    val reply = sealUnder(subject, "\"ada@example.com\"")
    val text  = reply.envelope.map(_.toStringUtf8).getOrElse(fail(reply.toString))
    assert(text.contains(s""""project":"$brand""""), text)
    val boxed  = """"c":"([^"]+)"""".r.findFirstMatchIn(text).map(_.group(1)).getOrElse(fail(text))
    val opened = PersonalCipher.open(
      key,
      java.util.Base64.getDecoder.decode(boxed),
      PersonalCipher.associatedData(subject.value, brand, "card")
    )
    assertEquals(opened.map(String(_, "UTF-8")), Some("\"ada@example.com\""))
    // Erased there: written erased, under that project. (A key this service was given is held
    // for `foreign-ttl`, so the erased one is a subject it was never given.)
    val gone = DataSubject(subjectOfThisRun("gone"))
    testKit.keyring.key(brand, gone, create = true): Unit
    testKit.keyring.erase(brand, gone): Unit
    assertEquals(
      sealUnder(gone, "\"x\"").envelope.map(_.toStringUtf8),
      Some(s"""{"$$personal":1,"project":"$brand","subject":"${gone.value}"}""")
    )
    // A key that project never made is not made by this one, there or here.
    val never  = DataSubject(subjectOfThisRun("never"))
    val absent = sealUnder(never, "\"x\"")
    assert(absent.envelope.exists(!_.toStringUtf8.contains("\"c\"")), absent.toString)
    assertEquals(testKit.keyring.keysMade(brand, never), 0)
    assertEquals(testKit.keyring.keysMade(testKit.project, never), 0)
  }

  // ── A follower behind the sidecar reads with its home's keys (FR-032) ───────

  test("a follower's key is its home's, through the relay, and an unreachable home is no erasure") {
    import com.thinkmorestupidless.ankka.testkit.TestKeyringRelay
    // Names no other suite of this JVM starts: an installation is one name per JVM.
    val (home, follower)           = ("hither", "yonder")
    val group                      = AnkkaTestKit.installations(home, follower)
    def logicOf(kit: AnkkaTestKit) =
      given ActorSystem[?] = kit.service.system
      ClientLogic(kit.service, settings, () => None)
    def sealAt(kit: AnkkaTestKit, s: DataSubject, json: String): ByteString =
      logicOf(kit)
        .keyringSeal(KeyringSealRequest(s.value, "event", ByteString.copyFromUtf8(json)))
        .reply
        .envelope
        .getOrElse(fail(s"not sealed at ${kit.project}"))
    try
      val atHome  = group.start(home, Seq.empty)
      val there   = group.start(follower, Seq.empty)
      val project = atHome.project
      val subject = DataSubject(subjectOfThisRun("replicated"))
      // Written at the home, as a replicated entity's event is.
      val written = sealAt(atHome, subject, "\"ada\"")
      val homeKey = await(
        logicOf(atHome).getSubjectKey(SubjectKeyRequest(subject.value, project = project))
      ).reply.key
      assert(homeKey.isDefined)

      // The follower's process names the home: it is given the home's key, and its own keyring
      // makes none.
      val followed = logicOf(there)
      val handed   = await(
        followed.getSubjectKey(SubjectKeyRequest(subject.value, project = project, home = home))
      ).reply
      assertEquals(handed.key, homeKey, handed.toString)
      val opened = followed.keyringOpen(KeyringOpenRequest(written, "event"), Some(home)).reply
      assertEquals(
        opened match
          case ankka.protocol.v1.wasm.KeyringOpenReply.Reply.Value(v) => Some(v.toStringUtf8)
          case _                                                      => None
        ,
        Some("\"ada\""),
        opened.toString
      )
      assertEquals(there.keyring.keysMade(project, subject), 0)
      assert(!there.keyring.holdsKey(project, subject))
      // Asked of its own keyring, as before, the follower read the field as erased.
      assertEquals(
        await(
          followed.getSubjectKey(SubjectKeyRequest(subject.value, project = project))
        ).reply.erased,
        Some(true)
      )

      // The home's keyring does not answer: UNAVAILABLE, so the read fails and is retried, and is
      // never read as erased.
      val unreached = DataSubject(subjectOfThisRun("unreached"))
      val envelope  = sealAt(atHome, unreached, "\"bo\"")
      TestKeyringRelay.block(follower, home)
      val cut = await(
        followed.getSubjectKey(SubjectKeyRequest(unreached.value, project = project, home = home))
      ).reply
      assertEquals(cut.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE), cut.toString)
      val unopened = followed.keyringOpen(KeyringOpenRequest(envelope, "event"), Some(home)).reply
      assertEquals(unopened.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE), unopened.toString)
      TestKeyringRelay.unblockAll()
      // A follower never makes a key, even for a write.
      val made = await(
        followed.getSubjectKey(
          SubjectKeyRequest(subjectOfThisRun("new"), create = true, home = home)
        )
      ).reply
      assertEquals(made.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE), made.toString)
    finally
      TestKeyringRelay.unblockAll()
      group.stopAll()
  }
