package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode, Personal}
import com.thinkmorestupidless.ankka.sdk.{ChangeSource, ConsumerContext}

import scala.concurrent.duration.DurationInt

/** The summariser and the compactor's own arithmetic, without a running service. */
class CompactionSuite extends munit.FunSuite:

  private def user(text: String)    = SessionMessage.UserMessage(1L, text, "a")
  private def ai(text: String)      = SessionMessage.AiMessage(2L, text, "a")
  private def summary(text: String) = SessionMessage.SummaryMessage(3L, text, "ankka-compactor")
  private def toolOk(text: String)  =
    SessionMessage.ToolResultMessage(4L, "c1", "lookup", text, isError = false, "a")
  private def toolBad(text: String) =
    SessionMessage.ToolResultMessage(4L, "c1", "lookup", text, isError = true, "a")

  test("settings reject values that could never compact anything") {
    intercept[IllegalArgumentException](CompactionSettings(maxHistoryBytes = 0))
    intercept[IllegalArgumentException](CompactionSettings(keepRecentMessages = -1))
    intercept[IllegalArgumentException](CompactionSettings(minMessagesToCompact = 0))
  }

  test("size accounting covers every message kind") {
    val messages = Vector(user("12345"), ai("123"), toolOk("12"), summary("1"))
    assertEquals(SessionCompactor.sizeOf(messages), 11)
    assertEquals(SessionCompactor.sizeOf(Vector.empty), 0)
  }

  test("the transcript labels each speaker so the model can follow it") {
    val text = ModelSummariser.transcript(
      Vector(user("hello"), ai("hi"), toolOk("42"), toolBad("boom"), summary("before"))
    )
    assertEquals(
      text,
      """User: hello
        |Assistant: hi
        |Tool lookup returned: 42
        |Tool lookup failed: boom
        |Earlier summary: before""".stripMargin
    )
  }

  test("an earlier summary is fed back in, so context carries forward") {
    // Compacting twice must not silently drop what the first summary established.
    val text = ModelSummariser.transcript(Vector(summary("Ada prefers museums"), user("and now?")))
    assert(text.startsWith("Earlier summary: Ada prefers museums"), text)
  }

  test("the summariser asks the model and returns its text") {
    val model  = TestModelProvider().expectText("They discussed museums in Lisbon.")
    val result = ModelSummariser(model, 5.seconds).summarise(Vector(user("museums?"), ai("yes")))

    assertEquals(result, "They discussed museums in Lisbon.")
    // Sent as one user turn, under a compression system message.
    assertEquals(model.lastRequest.messages.size, 1)
    assertEquals(model.lastRequest.timeout, Some(5.seconds), "the summariser's wait is the call's")
    assert(
      model.lastRequest.systemMessage.exists(_.contains("compress")),
      model.lastRequest.toString
    )
  }

  test("a refusal to summarise is an error, not an empty summary") {
    // Returning "" would replace real history with nothing.
    val model   = TestModelProvider().expectRefusal("I cannot summarise that.")
    val failure = intercept[ModelCallFailed] {
      ModelSummariser(model, 5.seconds).summarise(Vector(user("x")))
    }
    assert(failure.getMessage.contains("cannot summarise"), failure.getMessage)
  }

  test("the compactor descriptor names its source and stays single-worker") {
    val descriptor = SessionCompactor.descriptor(CompactionSettings(), _ => "summary")
    assertEquals(descriptor.componentId, SessionCompactor.ComponentId)
    assertEquals(descriptor.parallelism, 1)
    assertEquals(descriptor.produceTo, None)
    descriptor.source match
      case ChangeSource.EventSourced(componentId, _) =>
        assertEquals(componentId, SessionMemoryEntity.componentId)
      case other => fail(s"expected an event-sourced source, got $other")
  }

  // What the compactor acts on (feature 064). Built over a context that answers nothing: an event
  // the compactor ignores returns at once, and one it acts on reads the message's context, which
  // outside a delivery throws, before it reaches any client.
  private val noContext =
    java.lang.reflect.Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[ConsumerContext]),
        (_, _, _) => null
      )
      .asInstanceOf[ConsumerContext]

  private val compactor = SessionCompactor(noContext, CompactionSettings(), _ => "summary")

  private val subject = DataSubject("player/8c1f")

  private def sealedAs(event: SessionMemoryEvent): SessionMemoryEvent =
    SessionMemoryEvent.Sealed(
      Sealing.seal(subject, event, erased = false)(using SessionMemoryCodecs.event)
    )

  private def considered(event: SessionMemoryEvent): Boolean =
    try
      compactor.onMessage(event): Unit
      false
    catch case _: IllegalStateException => true

  private val answer   = SessionMemoryEvent.AiMessageAdded(ai("an answer"), TokenUsage.zero)
  private val question = SessionMemoryEvent.UserMessageAdded(user("a question"))
  private val written  = SessionMemoryEvent.HistoryCompacted(summary("before"), 4)
  private val waiting  = SessionMemoryEvent.TurnSuspended(
    SuspendedTurn("a", "ask", false, "", Vector.empty, TokenUsage.zero, 1, Vector.empty)
  )

  test("the compactor considers a session after an AI message, plain or sealed") {
    assert(considered(answer))
    assert(considered(sealedAs(answer)))
  }

  test("the compactor ignores every other event, sealed or not, and an erased one") {
    for event <- Seq(question, written, waiting, SessionMemoryEvent.Started(subject.value)) do
      assert(!considered(event), s"considered $event")
      assert(!considered(sealedAs(event)), s"considered a sealed $event")
    assert(!considered(SessionMemoryEvent.Sealed(Personal.Erased(subject))))
  }

  test("a failed compaction's warning names the failure's kind and never its message") {
    val words   = "my card was declined"
    val refused = ModelCallFailed("test", s"refused: $words")
    assertEquals(SessionCompactor.kind(refused), "ModelCallFailed")
    val out = CommandError(s"no session holding $words", ErrorCode.Unavailable)
    assertEquals(SessionCompactor.kind(out), "CommandError(Unavailable)")
    assertEquals(
      SessionCompactor.kind(java.util.concurrent.TimeoutException(words)),
      "TimeoutException"
    )
  }
