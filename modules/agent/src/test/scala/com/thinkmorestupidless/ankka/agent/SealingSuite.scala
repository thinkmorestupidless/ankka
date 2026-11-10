package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.autonomous.{TaskEntity, TaskRecord, TaskEvent}
import com.thinkmorestupidless.ankka.core.*

import java.nio.charset.StandardCharsets.UTF_8

/**
 * An agent's conversation about a data subject (feature 042, user story 6), as its serializers keep
 * it: sealed under the subject's key, read in either shape, and read as erased once the key is
 * gone.
 */
final class SealingSuite extends munit.FunSuite:

  private val subject = DataSubject("player/8c1f")
  private val words   = "my card was declined"

  /** A keyring of project `brand` holding one key for the subject, or none once it is destroyed. */
  private final class TestKeyring(var destroyed: Boolean = false) extends Keyring:
    private val bytes   = Array.fill[Byte](32)(7)
    val project: String = "brand"
    def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
      if destroyed then KeyAnswer.Destroyed else KeyAnswer.Key(IArray.unsafeFromArray(bytes))

  private def text(bytes: Array[Byte]) = new String(bytes, UTF_8)

  private val message = SessionMessage.UserMessage(1L, words, "helper")

  private def sealedEvent: SessionMemoryEvent =
    SessionMemoryEvent.Sealed(
      Sealing.seal(subject, SessionMemoryEvent.UserMessageAdded(message), erased = false)(using
        SessionMemoryCodecs.event
      )
    )

  test("a session's event with a data subject is written sealed, and reads back whole") {
    val keyring = TestKeyring()
    val events  = SessionMemoryEntity.eventSerializer
    val bytes   = Keyring.binding(keyring)(events.toBytes(sealedEvent))
    assert(!text(bytes).contains(words), text(bytes))
    assert(text(bytes).contains("\"$personal\":1"), text(bytes))
    Keyring.binding(keyring)(events.fromBytes(bytes)) match
      case SessionMemoryEvent.Sealed(Personal.Present(_, json)) => assert(json.contains(words))
      case other                                                => fail(s"not sealed: $other")
  }

  test("a session's event written before sealing reads as it always did") {
    val plain = SessionMemoryEvent.UserMessageAdded(message)
    val bytes =
      Serializer.json("session-memory-event")(using SessionMemoryCodecs.event).toBytes(plain)
    assertEquals(SessionMemoryEntity.eventSerializer.fromBytes(bytes), plain)
  }

  test("a sealed event read after the key is destroyed reads as erased, and never fails") {
    val keyring = TestKeyring()
    val bytes   = Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.toBytes(sealedEvent))
    keyring.destroyed = true
    assertEquals(
      Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.fromBytes(bytes)),
      SessionMemoryEvent.Sealed(Personal.Erased(subject))
    )
  }

  /**
   * `event` sealed under the subject's key and read back, as a consumer of the journal is given it.
   */
  private def throughTheJournal(
      event: SessionMemoryEvent,
      keyring: TestKeyring
  ): SessionMemoryEvent =
    val sealedOne = SessionMemoryEvent.Sealed(
      Sealing.seal(subject, event, erased = false)(using SessionMemoryCodecs.event)
    )
    val events = SessionMemoryEntity.eventSerializer
    val bytes  = Keyring.binding(keyring)(events.toBytes(sealedOne))
    Keyring.binding(keyring)(events.fromBytes(bytes))

  test("a plain session event is opened as itself") {
    val plain = SessionMemoryEvent.AiMessageAdded(
      SessionMessage.AiMessage(2L, "an answer", "helper"),
      TokenUsage.zero
    )
    assertEquals(SessionMemoryEvent.opened(plain), Some(plain))
    assertEquals(
      SessionMemoryEvent.opened(SessionMemoryEvent.Started(subject.value)),
      Some(
        SessionMemoryEvent.Started(subject.value)
      )
    )
  }

  test("a sealed session event is opened as the event it holds") {
    val keyring = TestKeyring()
    val answer  = SessionMemoryEvent.AiMessageAdded(
      SessionMessage.AiMessage(2L, words, "helper"),
      TokenUsage(3, 4)
    )
    val summary = SessionMemoryEvent.HistoryCompacted(
      SessionMessage.SummaryMessage(3L, s"they said $words", SessionCompactor.AgentId),
      4
    )
    val waiting = SessionMemoryEvent.TurnSuspended(
      SuspendedTurn("helper", "ask", false, "", Vector(message), TokenUsage.zero, 1, Vector.empty)
    )
    for event <- Seq(answer, summary, waiting) do
      val read = throughTheJournal(event, keyring)
      assert(read.isInstanceOf[SessionMemoryEvent.Sealed], s"not sealed: $read")
      assertEquals(SessionMemoryEvent.opened(read), Some(event))
  }

  test("a sealed session event read after its key is destroyed opens as nothing") {
    val keyring = TestKeyring()
    val bytes   = Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.toBytes(sealedEvent))
    keyring.destroyed = true
    val read = Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.fromBytes(bytes))
    assertEquals(SessionMemoryEvent.opened(read), None)
  }

  private val held =
    SessionHistory(Vector(message), TokenUsage.zero, 10, subject = Some(subject.value))

  test(
    "a session's snapshot with a data subject is sealed whole; one without is written as before"
  ) {
    val keyring = TestKeyring()
    val states  = SessionMemoryEntity.stateSerializer
    val bytes   = Keyring.binding(keyring)(states.toBytes(held))
    assert(text(bytes).startsWith("{\"sealedState\":"), text(bytes))
    assert(!text(bytes).contains(words), text(bytes))
    assertEquals(Keyring.binding(keyring)(states.fromBytes(bytes)), held)

    val none  = held.copy(subject = None)
    val plain = Serializer.json("session-history")(using SessionMemoryCodecs.history).toBytes(none)
    assertEquals(states.toBytes(none).toVector, plain.toVector)
    assertEquals(states.fromBytes(plain), none)
  }

  test("a sealed snapshot read after the key is destroyed is an erased session") {
    val keyring = TestKeyring()
    val bytes   = Keyring.binding(keyring)(SessionMemoryEntity.stateSerializer.toBytes(held))
    keyring.destroyed = true
    val read = Keyring.binding(keyring)(SessionMemoryEntity.stateSerializer.fromBytes(bytes))
    assert(read.erased)
    assertEquals(read.messages, Vector.empty)
    assertEquals(read.subject, Some(subject.value))
    // And an erased session is written with no key at all.
    assertEquals(
      Keyring.binding(keyring)(
        SessionMemoryEntity.stateSerializer.fromBytes(
          Keyring.binding(keyring)(SessionMemoryEntity.stateSerializer.toBytes(read))
        )
      ),
      read
    )
  }

  test("a session is observed with each personal text named by its data subject") {
    val observed = held.observed.messages.mkString
    assert(!observed.contains(words), observed)
    assert(observed.contains(s"Personal(${subject.value})"), observed)
    assertEquals(held.copy(subject = None).observed, held.copy(subject = None))
  }

  test("a task's record with a data subject is sealed, and keeps its shape once erased") {
    val keyring = TestKeyring()
    val record  = TaskRecord.empty.copy(
      id = "t1",
      typeName = "resolve",
      instructions = words,
      createdAt = 5L,
      subject = Some(subject.value)
    )
    val bytes = Keyring.binding(keyring)(TaskEntity.stateSerializer.toBytes(record))
    assert(!text(bytes).contains(words), text(bytes))
    assertEquals(Keyring.binding(keyring)(TaskEntity.stateSerializer.fromBytes(bytes)), record)
    keyring.destroyed = true
    val erased = Keyring.binding(keyring)(TaskEntity.stateSerializer.fromBytes(bytes))
    assert(erased.erased)
    assertEquals(erased.instructions, "")
    assertEquals((erased.id, erased.typeName, erased.createdAt), ("t1", "resolve", 5L))
  }

  private val taskEvents: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[TaskEvent] =
    Codecs.make[TaskEvent]

  test("a task's event that holds a personal text reads as its clear copy once erased") {
    val keyring = TestKeyring()
    val created = TaskEvent.Created("t1", "resolve", words, Vector.empty, Vector.empty, 5L)
    val event   = TaskEvent.Sealed(
      """{"type":"Created","id":"t1","typeName":"resolve","instructions":"","attachments":[],"dependencies":[],"at":5}""",
      Sealing.seal(subject, created: TaskEvent, erased = false)(using taskEvents)
    )
    val bytes = Keyring.binding(keyring)(TaskEntity.eventSerializer.toBytes(event))
    assert(!text(bytes).contains(words), text(bytes))
    keyring.destroyed = true
    Keyring.binding(keyring)(TaskEntity.eventSerializer.fromBytes(bytes)) match
      case TaskEvent.Sealed(clear, Personal.Erased(_)) => assert(!clear.contains(words))
      case other                                       => fail(s"not erased: $other")
  }

  test("a record still in memory when its subject was erased is written erased, never refused") {
    val keyring = TestKeyring(destroyed = true)
    val state   = Keyring.binding(keyring)(SessionMemoryEntity.stateSerializer.toBytes(held))
    assert(!text(state).contains(words), text(state))
    assert(Keyring.binding(keyring)(SessionMemoryEntity.stateSerializer.fromBytes(state)).erased)
    val event = Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.toBytes(sealedEvent))
    assert(!text(event).contains(words), text(event))
    assertEquals(
      Keyring.binding(keyring)(SessionMemoryEntity.eventSerializer.fromBytes(event)),
      SessionMemoryEvent.Sealed(Personal.Erased(subject))
    )
  }
