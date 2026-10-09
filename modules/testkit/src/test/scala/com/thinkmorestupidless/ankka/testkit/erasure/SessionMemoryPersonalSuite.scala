package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, InMemoryKeyring, LogCapturing}

/**
 * A session's memory with a data subject, with no runtime: what a tagged session records, what it
 * reads once the subject is erased, and that an untagged one records what it always did.
 */
class SessionMemoryPersonalSuite extends munit.FunSuite with LogCapturing:

  private var n = 0
  private def subject: String =
    n += 1
    s"player/session-suite-$n-${System.nanoTime()}"

  private def user(text: String) = SessionMessage.UserMessage(1L, text, "helper")

  test("a tagged session keeps each message under the subject's key, and reads it back") {
    val s   = subject
    val kit = EventSourcedTestKit.of(SessionMemoryEntity, "tagged")
    kit.call(SessionMemoryEntity.assignSubject)(s)
    val added = kit.call(SessionMemoryEntity.addUserMessage)(user("my card was declined"))
    assert(
      added.events.forall {
        case SessionMemoryEvent.Protected(content) => content.subject == s
        case _                                     => false
      },
      added.events.toString
    )
    assertEquals(
      kit.call(SessionMemoryEntity.history).replyValue.messages,
      Vector(user("my card was declined"))
    )
  }

  test("once the subject is erased the history is a note, and the next turn starts after it") {
    val s   = subject
    val kit = EventSourcedTestKit.of(SessionMemoryEntity, "erased")
    kit.call(SessionMemoryEntity.assignSubject)(s)
    kit.call(SessionMemoryEntity.addUserMessage)(user("my card was declined"))
    InMemoryKeyring.shared.erase("local", s): Unit
    val read = kit.call(SessionMemoryEntity.history).replyValue
    assert(read.erased)
    assertEquals(read.messages.map(_.toString).exists(_.contains("declined")), false)
    assert(read.messages.map(_.toString).exists(_.contains(SessionMemoryEntity.ErasedNote)))
    val next = kit.call(SessionMemoryEntity.addUserMessage)(user("hello again"))
    assert(next.events.head.isInstanceOf[SessionMemoryEvent.Forgotten], next.events.toString)
    assertEquals(next.events(1), SessionMemoryEvent.UserMessageAdded(user("hello again")))
    val after = kit.call(SessionMemoryEntity.history).replyValue.messages.map(_.toString)
    assert(after.head.contains(SessionMemoryEntity.ErasedNote), after.toString)
    assert(after.last.contains("hello again"), after.toString)
  }

  test("the erasure's duty forgets a session about the subject, and leaves one about another") {
    val s     = subject
    val other = subject
    val kit   = EventSourcedTestKit.of(SessionMemoryEntity, "duty")
    kit.call(SessionMemoryEntity.assignSubject)(s)
    assertEquals(kit.call(SessionMemoryEntity.forget)(other).events, Vector.empty)
    assertEquals(kit.call(SessionMemoryEntity.forget)(s).events.size, 1)
    assert(kit.call(SessionMemoryEntity.history).replyValue.erased)
  }

  test("an untagged session records its events as it always did") {
    val kit   = EventSourcedTestKit.of(SessionMemoryEntity, "untagged")
    val added = kit.call(SessionMemoryEntity.addUserMessage)(user("what is the weather"))
    assertEquals(
      added.events,
      Vector(SessionMemoryEvent.UserMessageAdded(user("what is the weather")))
    )
  }

  test("a session about one subject refuses another") {
    val kit = EventSourcedTestKit.of(SessionMemoryEntity, "one-subject")
    kit.call(SessionMemoryEntity.assignSubject)(subject)
    assert(kit.call(SessionMemoryEntity.assignSubject)(subject).isError)
  }
