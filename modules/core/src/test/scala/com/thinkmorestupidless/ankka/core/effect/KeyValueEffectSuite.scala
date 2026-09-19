package com.thinkmorestupidless.ankka.core.effect

import com.thinkmorestupidless.ankka.core.ErrorCode

import scala.concurrent.duration.*

private final case class Profile(name: String, email: String)

class KeyValueEffectSuite extends munit.FunSuite:

  private val effects = new KeyValueEffects[Profile]()
  private val initial = Profile("ada", "ada@example.com")

  private def run[R](effect: KeyValueEffect[Profile, R], from: Profile = initial) =
    KeyValueEffect.materialise(effect, from)

  test("updateState replaces state wholesale and marks it changed") {
    val updated = Profile("grace", "grace@example.com")
    val result  = run(effects.updateState(updated).thenReply(_.name))
    assertEquals(result.newState, updated)
    assert(result.changed)
    assertEquals(result.reply, Right(Some("grace")))
  }

  test("thenReplyState replies with the updated state") {
    val updated = Profile("grace", "grace@example.com")
    assertEquals(run(effects.updateState(updated).thenReplyState).reply, Right(Some(updated)))
  }

  test("reply leaves state untouched and unchanged") {
    val result = run(effects.reply(42))
    assertEquals(result.newState, initial)
    assert(!result.changed)
    assertEquals(result.reply, Right(Some(42)))
  }

  test("error rejects without changing state") {
    val result = run(effects.error[Int]("email already taken", ErrorCode.Conflict))
    assertEquals(result.newState, initial)
    assert(!result.changed)
    assertEquals(result.reply.left.map(_.code), Left(ErrorCode.Conflict))
  }

  test("deleteEntity records retention and does not fabricate a new state") {
    val result = run(effects.deleteEntity().thenReply(_ => "gone"))
    assertEquals(result.retention, Some(Retention.DeleteNow))
    assertEquals(result.newState, initial, "state stands until the runtime deletes it")
    assert(!result.changed)
  }

  test("expireAfter attaches a TTL to an update") {
    val updated = Profile("grace", "grace@example.com")
    val result  = run(effects.updateState(updated).expireAfter(7.days).thenReplyState)
    assertEquals(result.retention, Some(Retention.ExpireAfter(7.days)))
    assert(result.changed)
  }

  test("read-only effects cannot carry a state change") {
    val readOnly: KeyValueReadOnlyEffect[Profile, Int] = effects.reply(1)
    assertEquals(readOnly.newState, None)
    assertEquals(readOnly.retention, None)
  }
