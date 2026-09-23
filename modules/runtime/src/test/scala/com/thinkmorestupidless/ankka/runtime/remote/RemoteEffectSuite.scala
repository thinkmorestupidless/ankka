package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata, MethodName}

import scala.concurrent.duration.*

class RemoteEffectSuite extends munit.FunSuite:

  private val command = RemoteHandler(MethodName("add-item"), readOnly = false, streaming = false)
  private val query   = RemoteHandler(MethodName("get-cart"), readOnly = true, streaming = false)
  private val event   = Payload(Payload.Json, "cart-event", """{"type":"ItemAdded"}""".getBytes)
  private val done    = Payload(Payload.Binary, "done", Array.emptyByteArray)
  private val state   = Payload(Payload.Json, "cart", """{"items":[]}""".getBytes)

  private def reply(
      id: Long = 1,
      events: Vector[Payload] = Vector.empty,
      newState: Option[Payload] = None,
      outcome: RemoteOutcome = RemoteOutcome.Reply(done, Metadata.empty),
      retention: Option[Retention] = None,
      snapshot: Option[Payload] = None
  ) = Reply(id, events, newState, None, retention, outcome, snapshot)

  test("a reply persists its events and answers") {
    val m = RemoteEffect.materialise(reply(events = Vector(event)), command, 1, false).toOption.get
    assertEquals(m.events, Vector(event))
    assertEquals(m.reply.map(_.map(_._1)), Right(Some(done)))
    assert(m.persisted)
  }

  test("no-reply persists and answers nothing") {
    val m = RemoteEffect
      .materialise(
        reply(events = Vector(event), outcome = RemoteOutcome.NoReply),
        command,
        1,
        false
      )
      .toOption
      .get
    assertEquals(m.events, Vector(event))
    assertEquals(m.reply, Right(None))
  }

  test("a refusal persists nothing and carries its code") {
    val error = CommandError("no", ErrorCode.Conflict)
    val m = RemoteEffect
      .materialise(reply(outcome = RemoteOutcome.Error(error)), command, 1, false)
      .toOption
      .get
    assertEquals(m.events, Vector.empty)
    assertEquals(m.reply, Left(error))
    assert(!m.persisted)
  }

  test("a refusal that also changed state is a violation, not a partial write") {
    val error = CommandError("no", ErrorCode.Conflict)
    val r = RemoteEffect.materialise(
      reply(events = Vector(event), outcome = RemoteOutcome.Error(error)),
      command,
      1,
      false
    )
    assert(r.left.exists(_.getMessage.contains("also changed state")))
  }

  test("a read-only handler may not persist events or state") {
    assert(RemoteEffect.materialise(reply(events = Vector(event)), query, 1, false).isLeft)
    assert(RemoteEffect.materialise(reply(newState = Some(state)), query, 1, false).isLeft)
    assert(RemoteEffect.materialise(reply(), query, 1, false).isRight)
  }

  test("the reply must be for the command in flight") {
    val r = RemoteEffect.materialise(reply(id = 7), command, 1, false)
    assert(r.left.exists(_.getMessage.contains("command 7")))
  }

  test("a snapshot is accepted only when requested") {
    assert(RemoteEffect.materialise(reply(snapshot = Some(state)), command, 1, false).isLeft)
    val m = RemoteEffect.materialise(reply(snapshot = Some(state)), command, 1, true).toOption.get
    assertEquals(m.snapshot, Some(state))
  }

  test("retention and new state pass through") {
    val m = RemoteEffect
      .materialise(
        reply(newState = Some(state), retention = Some(Retention.ExpireAfter(1.second))),
        command,
        1,
        false
      )
      .toOption
      .get
    assertEquals(m.newState, Some(state))
    assertEquals(m.retention, Some(Retention.ExpireAfter(1.second)))
  }

  test("content types follow the manifest as the in-process serializers do") {
    assertEquals(Payload.contentTypeFor("int"), Payload.Text)
    assertEquals(Payload.contentTypeFor("done"), Payload.Binary)
    assertEquals(Payload.contentTypeFor("option[int]"), Payload.Binary)
    assertEquals(Payload.contentTypeFor("shopping-cart"), Payload.Json)
  }
