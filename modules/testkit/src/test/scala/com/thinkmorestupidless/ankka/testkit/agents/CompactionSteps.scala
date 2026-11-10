package com.thinkmorestupidless.ankka.testkit.agents

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, SessionId}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.ConfigFactory

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The request agent of `features/agents/compaction.feature`: it asks, and keeps its memory. */
final class CompactionHelper extends Agent:
  def ask(question: String): Effect[String] =
    effects.systemMessage("You help people with their payments.").userMessage(question).thenReply()

object CompactionHelper extends Agent.Companion[CompactionHelper](ComponentId("helper")):
  def create(context: AgentContext) = new CompactionHelper
  val ask                           = command("ask")(_.ask)

/**
 * A summariser that can be held, once, inside its next summary: how a step keeps the compactor busy
 * on one session while it stages what the compactor reads next.
 */
final class HeldSummariser(inner: Summariser) extends Summariser:
  @volatile private var held: Option[(CountDownLatch, CountDownLatch)] = None

  /** Holds the next summary: the first latch opens when it is held, the second lets it go. */
  def holdNext(): (CountDownLatch, CountDownLatch) =
    val gate = (CountDownLatch(1), CountDownLatch(1))
    held = Some(gate)
    gate

  def summarise(messages: Vector[SessionMessage]): String =
    held.foreach { (entered, release) =>
      held = None
      entered.countDown()
      release.await(2, TimeUnit.MINUTES): Unit
    }
    inner.summarise(messages)

/**
 * The steps of `features/agents/compaction.feature` (feature 064), over one service of the test kit
 * whose agent is `helper` and whose compaction is the feature's Background. The agent and the
 * summariser have a model each: the compactor runs on its own, so one scripted model would answer
 * whichever asked first.
 *
 * Its keyring's bounds are two seconds, so a scenario can stage an outage in which every held key
 * is dropped without waiting a minute; outside an outage the kit's channel is always confirmed, so
 * the bounds change nothing else.
 */
abstract class CompactionSteps(file: String) extends GherkinSuite(file) with LogCapturing:

  override val munitTimeout: Duration = 5.minutes

  private val Size     = 100000
  private val Kept     = 10
  private val Replaced = 4

  private val model        = TestModelProvider()
  private val summaryModel = TestModelProvider()
  private val summariser   = HeldSummariser(ModelSummariser(summaryModel, 30.seconds))
  private val Summary      = "the earlier conversation, in a few words"

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    // Every summary is this one, so a fence session always compacts, except a summary of a
    // session whose summary model fails, which is refused every time it is asked.
    summaryModel.respondWhen(!refused(_))(_ => ModelResponse(Summary)): Unit
    summaryModel.respondWhen(refused)(_ =>
      ModelResponse("", Vector.empty, StopReason.Refusal, TokenUsage.zero, refusal)
    ): Unit
    val agents = AgentRuntime
      .withDefaultModel(model)
      .withCompaction(
        CompactionSettings(
          maxHistoryBytes = Size,
          keepRecentMessages = Kept,
          minMessagesToCompact = Replaced
        ),
        Some(summariser)
      )
    kit = AnkkaTestKit.start(
      Seq(CompactionHelper.descriptor) ++ agents.descriptors,
      Seq(agents, ProjectionRuntime()),
      settings = ConfigFactory.parseString(
        "ankka.keyring.cache.outage-bound = 2s\nankka.keyring.notice-bound = 2s"
      )
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    if kit != null then kit.keyring.recover()
    SessionCompactor.compacted.clear()
    SessionCompactor.failures.clear()
    session = None
    refusal = None
    refusing = Vector.empty

  /**
   * The session a scenario is about: its id, its data subject, what was sent and what is to come.
   */
  private final case class Session(
      id: String,
      subject: Option[DataSubject],
      sent: Vector[String],
      last: Option[(String, String)]
  )

  private var session: Option[Session] = None

  private def current: Session = session.getOrElse(fail("no session was given"))

  // A word no session id, log line or summary holds, so finding it anywhere is finding a message.
  private def marker() = "said" + java.util.UUID.randomUUID().toString.replace("-", "")

  /** `count` texts, each starting with a marker of its own, `total` characters together. */
  private def texts(count: Int, total: Int): Vector[String] =
    val each = total / count
    Vector.tabulate(count) { i =>
      val length = if i == count - 1 then total - each * (count - 1) else each
      val start  = marker()
      start + "x" * (length - start.length)
    }

  private def calls(s: Session) =
    kit.componentClient.forAgent(SessionId(s.id), subject = s.subject)

  private def turn(s: Session, question: String, answer: String): Unit =
    model.expectText(answer)
    val _ = calls(s).call(CompactionHelper.ask).invoke(question)

  /** The pairs of `texts` as turns: each a question and the answer to it. */
  private def pairs(texts: Vector[String]): Vector[(String, String)] =
    texts.indices.by(2).map(i => texts(i) -> texts(i + 1)).toVector

  private def turns(s: Session, texts: Vector[String]): Unit =
    pairs(texts).foreach((q, a) => turn(s, q, a))

  private def historyOf(id: String) =
    kit.componentClient.forSessionMemory(SessionId(id)).call(SessionMemoryEntity.history).invoke()

  private def eventually[A](what: String, within: FiniteDuration = 90.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(150)
    last.getOrElse(fail(s"$what did not happen within $within"))

  /**
   * A session's every turn but its last: the messages alternate the person's and the agent's, and
   * the last turn is the one the scenario's When (or the keyring's Given) ends.
   */
  private def begin(subject: Option[String], count: Int, total: Int): Unit =
    assert(count % 2 == 0, s"$count messages are not whole turns")
    val all = texts(count, total)
    val s   = Session(s"$scenarioId-${marker().take(12)}", subject.map(DataSubject(_)), all, None)
    turns(s, all.dropRight(2))
    session = Some(s.copy(last = pairs(all).lastOption))

  private def endLastTurn(): Unit =
    val s = current
    s.last.foreach((q, a) => turn(s, q, a))
    session = Some(s.copy(last = None))

  /**
   * Waits until the compactor has read past every event written so far: a session with no data
   * subject, past the size, is written after them and compacted. A check that something did not
   * happen reads only after this, or it reads before the compactor got there.
   */
  private def compactorPassed(): Unit =
    val fence =
      Session(s"fence-$scenarioId-${marker().take(12)}", None, texts(14, Size + 103), None)
    turns(fence, fence.sent)
    eventually("the fence session's compaction") {
      Option.when(SessionCompactor.compacted.asScala.exists(_ == fence.id))(())
    }

  private def text(message: SessionMessage): String = message match
    case m: SessionMessage.UserMessage       => m.text
    case m: SessionMessage.AiMessage         => m.text
    case m: SessionMessage.ToolResultMessage => m.content
    case m: SessionMessage.SummaryMessage    => m.text

  private def holdsSummaryThenKept(count: Int): Unit =
    val s       = current
    val history = eventually(s"a summary in session ${s.id}") {
      Option(historyOf(s.id)).filter(
        _.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage])
      )
    }
    assertEquals(history.messages.size, count + 1, history.messages.map(text(_).take(40)))
    assertEquals(history.messages.tail.map(text), s.sent.takeRight(count))

  // The Background: the service is the kit's, built with exactly these numbers.

  Given("a service {string} with an agent {string}") { (_: String, agent: String) =>
    assertEquals(agent, CompactionHelper.componentId.toString)
  }

  Given(
    "compaction of {string} with a compaction size of {string} characters that keeps the {string} most recent messages and replaces no fewer than {string}"
  ) { (_: String, size: Int, kept: Int, replaced: Int) =>
    assertEquals((size, kept, replaced), (Size, Kept, Replaced))
  }

  Given(
    "a session of {string} started with no data subject, with {string} messages of {string} characters together"
  )((_: String, count: Int, total: Int) => begin(None, count, total))

  Given(
    "a session of {string} started with the data subject {string}, with {string} messages of {string} characters together"
  )((_: String, subject: String, count: Int, total: Int) => begin(Some(subject), count, total))

  When("a turn in the session ends")(() => endLastTurn())

  Then("the session holds a summary and then its {string} most recent messages verbatim") {
    (count: Int) => holdsSummaryThenKept(count)
  }

  Then("the session holds its {string} messages verbatim and no summary") { (count: Int) =>
    compactorPassed()
    val messages = historyOf(current.id).messages
    assert(
      !messages.exists(_.isInstanceOf[SessionMessage.SummaryMessage]),
      s"session ${current.id} was compacted"
    )
    assertEquals(messages.map(text), current.sent)
    assertEquals(messages.size, count)
  }

  // A failure, and what the log says of it.

  @volatile private var refusal: Option[String]  = None
  @volatile private var refusing: Vector[String] = Vector.empty

  /** Whether `request` asks for a summary of a session whose summary model fails. */
  private def refused(request: ModelRequest): Boolean =
    val asked = request.messages.mkString
    refusing.exists(asked.contains)

  // Every time it is asked: the compactor reads behind the turns and considers the session at each
  // of its answers, so a model that failed only once would be asked again and succeed.
  Given("the model that writes the summary fails") { () =>
    refusal = Some(s"I will not summarise ${marker()}")
    refusing = current.sent.map(_.takeWhile(_ != 'x'))
  }

  Then("the log of {string} holds a warning that names the session and the failure") {
    (_: String) =>
      val id      = current.id
      val warning = eventually("the compaction's warning") {
        capturedWarnings.find(_.contains(s"compaction of session '$id' failed"))
      }
      assert(
        warning.startsWith(classOf[SessionCompactor].getName + " "),
        s"not the compactor's: $warning"
      )
      assert(warning.endsWith(": ModelCallFailed"), warning)
  }

  Then("nothing in the log of {string} reads as a message of the session") { (_: String) =>
    assert(capturingLogs, "the log is not captured, so nothing in it proves anything")
    val log    = capturedLog
    val words  = current.sent.map(_.takeWhile(_ != 'x')) ++ refusal.toList
    val leaked = words.filter(log.contains)
    assert(leaked.isEmpty, s"the log holds the session's words: ${leaked.mkString(", ")}")
  }

  // The keyring, out and back.

  Given("the keyring cannot be reached since the last turn in the session ended") { () =>
    // The compactor is held inside a plain session's compaction while the sealed session's last
    // turn is written behind it, so it reads that turn only once the keyring is out.
    val (entered, release) = summariser.holdNext()
    val ahead              =
      Session(s"ahead-$scenarioId-${marker().take(12)}", None, texts(14, Size + 103), None)
    turns(ahead, ahead.sent)
    assert(entered.await(90, TimeUnit.SECONDS), "the compactor never reached the held summary")
    endLastTurn()
    kit.keyring.outage(10.minutes)
    // Past the notice bound every held key is dropped, so the next key needed is asked for.
    eventually("every held key dropped") {
      capturedWarnings.find(_.contains("has not been confirmed within"))
    }
    release.countDown()
    eventually("the compactor refused the sealed session's event") {
      Option.when(capturedLog.contains("keyring is unavailable"))(())
    }
  }

  When("the keyring can be reached again") { () =>
    assert(
      !historyOf(current.id).messages.exists(_.isInstanceOf[SessionMessage.SummaryMessage]),
      "compacted while the keyring could not be reached"
    )
    kit.keyring.recover()
  }

  Then("no compaction of the session failed") { () =>
    val id = current.id
    assertEquals(SessionCompactor.failures.asScala.filter(_.startsWith(s"$id:")).toVector, Vector())
  }
