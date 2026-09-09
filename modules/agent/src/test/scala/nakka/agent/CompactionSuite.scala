package nakka.agent

import nakka.sdk.ChangeSource

import scala.concurrent.duration.DurationInt

/** The summariser and the compactor's own arithmetic, without a running service. */
class CompactionSuite extends munit.FunSuite:

  private def user(text: String)      = SessionMessage.UserMessage(1L, text, "a")
  private def ai(text: String)        = SessionMessage.AiMessage(2L, text, "a")
  private def summary(text: String)   = SessionMessage.SummaryMessage(3L, text, "nakka-compactor")
  private def toolOk(text: String)    =
    SessionMessage.ToolResultMessage(4L, "c1", "lookup", text, isError = false, "a")
  private def toolBad(text: String)   =
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
    val model = TestModelProvider().expectText("They discussed museums in Lisbon.")
    val result = ModelSummariser(model, 5.seconds).summarise(Vector(user("museums?"), ai("yes")))

    assertEquals(result, "They discussed museums in Lisbon.")
    // Sent as one user turn, under a compression system message.
    assertEquals(model.lastRequest.messages.size, 1)
    assert(model.lastRequest.systemMessage.exists(_.contains("compress")), model.lastRequest.toString)
  }

  test("a refusal to summarise is an error, not an empty summary") {
    // Returning "" would replace real history with nothing.
    val model = TestModelProvider().expectRefusal("I cannot summarise that.")
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
