package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Compaction end to end: a session grows past its limit, the compactor notices, a summary replaces
 * the oldest messages, and the next request sees the shortened history.
 */
class CompactionIntegrationSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null

  /**
   * Two providers, deliberately.
   *
   * A single scripted model cannot serve both: the compactor runs asynchronously, so whether the
   * agent or the summariser reaches the queue first is a race — and a summary queued "for the
   * summariser" gets consumed as the agent's answer instead. Separate providers make each assertion
   * mean what it says.
   */
  private val model        = TestModelProvider()
  private val summaryModel = TestModelProvider()

  // Small enough that a handful of ordinary turns crosses it.
  private val settings = CompactionSettings(
    maxHistoryBytes = 400,
    keepRecentMessages = 2,
    minMessagesToCompact = 2
  )

  override def beforeAll(): Unit =
    val agents = AgentRuntime
      .withDefaultModel(model)
      .withCompaction(settings, Some(ModelSummariser(summaryModel, 30.seconds)))
    testKit = AnkkaTestKit.start(
      Seq(WeatherAgent.descriptor) ++ agents.descriptors,
      Seq(agents, ProjectionRuntime())
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    summaryModel.reset()
    SessionCompactor.compacted.clear()
    SessionCompactor.failures.clear()

  private def agent(session: String) = testKit.componentClient.forAgent(SessionId(session))

  private def historyOf(session: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(session))
      .call(SessionMemoryEntity.history)
      .invoke()

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(150)
    last.getOrElse(fail(s"$description did not happen within $within"))

  /** One long-ish exchange, so history grows quickly. */
  private def turn(session: String, question: String, answer: String): Unit =
    model.expectText(answer)
    val _ = agent(session).call(WeatherAgent.ask).invoke(question)

  private val padding = "x" * 120

  // `asScala` on a ConcurrentLinkedQueue yields Iterable, which has no `contains`.
  private def compactedSessions: Vector[String] = SessionCompactor.compacted.asScala.toVector
  private def failureMessages: Vector[String]   = SessionCompactor.failures.asScala.toVector

  test("a session over its limit is compacted into a summary plus recent messages") {
    val session = "c-basic"

    turn(session, s"first question $padding", s"first answer $padding")
    turn(session, s"second question $padding", s"second answer $padding")

    summaryModel.expectText("Earlier: two questions about padding.")
    turn(session, s"third question $padding", s"third answer $padding")

    val compacted = eventually("the session is compacted") {
      Option(historyOf(session)).filter { history =>
        history.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage])
      }
    }

    // A summary, then the retained recent messages — nothing else.
    assertEquals(compacted.messages.size, 1 + settings.keepRecentMessages)
    val summary = compacted.messages.head.asInstanceOf[SessionMessage.SummaryMessage]
    assertEquals(summary.agentId, SessionCompactor.AgentId)
    assertEquals(summary.text, "Earlier: two questions about padding.")
    assert(compacted.sizeInBytes < 400 * 3, s"history should have shrunk: ${compacted.sizeInBytes}")
  }

  test("the summary is carried into the next request's prompt") {
    val session = "c-prompt"

    turn(session, s"alpha $padding", s"answer alpha $padding")
    turn(session, s"beta $padding", s"answer beta $padding")
    summaryModel.expectText("Earlier: alpha and beta were discussed.")
    turn(session, s"gamma $padding", s"answer gamma $padding")

    val _ = eventually("the session is compacted") {
      Option.when(compactedSessions.contains(session))(())
    }

    // The next turn should replay the summary rather than the messages it replaced.
    model.expectText("final")
    val _ = agent(session).call(WeatherAgent.ask).invoke("what did we cover?")

    val replayed = model.lastRequest.messages
      .collect {
        case ChatMessage.User(content) =>
          content.collect { case MessageContent.Text(text) => text }.mkString
        case ChatMessage.Assistant(text, _) => text
      }
      .mkString("\n")

    assert(replayed.contains("alpha and beta were discussed"), replayed)
    assert(replayed.contains("summarised"), "the summary should be marked as such")
    assert(!replayed.contains("answer alpha"), "the replaced messages should be gone")
  }

  test("a short session is left alone") {
    val session = "c-short"
    turn(session, "hello", "hi")

    Thread.sleep(2000)
    val history = historyOf(session)
    assertEquals(history.messages.size, 2)
    assert(!compactedSessions.contains(session))
    assert(!history.messages.exists(_.isInstanceOf[SessionMessage.SummaryMessage]))
  }

  test("compacting twice folds the earlier summary into the later one") {
    val session = "c-twice"

    turn(session, s"one $padding", s"answer one $padding")
    turn(session, s"two $padding", s"answer two $padding")
    summaryModel.expectText("Summary A.")
    turn(session, s"three $padding", s"answer three $padding")
    val _ = eventually("first compaction")(
      Option.when(compactedSessions.count(_ == session) == 1)(())
    )

    turn(session, s"four $padding", s"answer four $padding")
    summaryModel.expectText("Summary B, which includes Summary A.")
    turn(session, s"five $padding", s"answer five $padding")

    val _ = eventually("second compaction")(
      Option.when(compactedSessions.count(_ == session) >= 2)(())
    )

    val history = historyOf(session)
    // Still exactly one summary at the front — summaries do not accumulate.
    assertEquals(history.messages.count(_.isInstanceOf[SessionMessage.SummaryMessage]), 1)
    val summary = history.messages.head.asInstanceOf[SessionMessage.SummaryMessage]
    assert(summary.text.contains("Summary B"), summary.text)
  }

  test("a failing summariser loses that compaction rather than stalling the rest") {
    val session = "c-failing"

    turn(session, s"one $padding", s"answer one $padding")
    turn(session, s"two $padding", s"answer two $padding")
    // The summariser's own model refuses, so this compaction cannot proceed.
    summaryModel.expectRefusal("no summarising today")
    turn(session, s"three $padding", s"answer three $padding")

    val _ = eventually("the failure is recorded") {
      Option(failureMessages.filter(_.startsWith(session))).filter(_.nonEmpty)
    }
    // History is untouched: better to keep it than to replace it with nothing.
    assert(!historyOf(session).messages.exists(_.isInstanceOf[SessionMessage.SummaryMessage]))

    // And the consumer kept moving — a later session still compacts.
    val other = "c-after-failure"
    turn(other, s"one $padding", s"answer one $padding")
    turn(other, s"two $padding", s"answer two $padding")
    summaryModel.expectText("Recovered summary.")
    turn(other, s"three $padding", s"answer three $padding")

    val _ = eventually("a later session still compacts") {
      Option.when(compactedSessions.contains(other))(())
    }
  }

  test("compaction survives a restart, and history stays compacted") {
    val session = "c-durable"

    turn(session, s"one $padding", s"answer one $padding")
    turn(session, s"two $padding", s"answer two $padding")
    summaryModel.expectText("Durable summary.")
    turn(session, s"three $padding", s"answer three $padding")

    val before = eventually("the session is compacted") {
      Option(historyOf(session)).filter(
        _.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage])
      )
    }

    testKit.restartService()

    val after = historyOf(session)
    assertEquals(after.messages.size, before.messages.size)
    assertEquals(
      after.messages.head.asInstanceOf[SessionMessage.SummaryMessage].text,
      before.messages.head.asInstanceOf[SessionMessage.SummaryMessage].text
    )
  }

  test("compaction requires a summariser") {
    val failure = intercept[IllegalArgumentException] {
      AgentRuntime().withCompaction(CompactionSettings())
    }
    assert(failure.getMessage.contains("summariser"), failure.getMessage)

    // An explicit one works without a default model.
    val runtime = AgentRuntime().withCompaction(CompactionSettings(), Some(_ => "summary"))
    assertEquals(runtime.descriptors.size, 2)
  }
