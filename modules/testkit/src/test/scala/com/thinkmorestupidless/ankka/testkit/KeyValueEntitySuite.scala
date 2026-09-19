package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}

/** Unit-level key value behaviour: no actor system, no database. */
class KeyValueEntitySuite extends munit.FunSuite:

  private def newKit = KeyValueEntityTestKit.of(ProfileEntity, "user-1")

  test("updateState replaces the whole value and reports the change") {
    val kit    = newKit
    val result = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    assertEquals(result.replyValue, Done)
    assert(result.changed)
    assertEquals(kit.currentState, Profile("Ada", "ada@example.com", 1))
  }

  test("a later update overwrites rather than merging") {
    val kit = newKit
    val _   = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    val _   = kit.call(ProfileEntity.rename)("Ada L")
    assertEquals(kit.currentState, Profile("Ada L", "ada@example.com", 1))
  }

  test("a rejection leaves the value untouched") {
    val kit    = newKit
    val result = kit.call(ProfileEntity.register)(Profile("Ada", "", 1))
    assert(result.isError)
    assert(!result.changed)
    assertEquals(kit.currentState, Profile("", "", 0))
  }

  test("registering twice conflicts") {
    val kit = newKit
    val _   = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    assertEquals(
      kit.call(ProfileEntity.register)(Profile("Grace", "grace@example.com", 1)).error.code,
      ErrorCode.Conflict
    )
  }

  test("thenReplyState sees the updated value") {
    val kit = newKit
    val _   = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    assertEquals(kit.call(ProfileEntity.rename)("Ada L").replyValue.name, "Ada L")
  }

  test("reads do not change the value") {
    val kit    = newKit
    val _      = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    val result = kit.call(ProfileEntity.get)
    assert(!result.changed)
    assertEquals(result.replyValue.email, "ada@example.com")
  }

  test("delete empties the value and marks the entity deleted") {
    val kit    = newKit
    val _      = kit.call(ProfileEntity.register)(Profile("Ada", "ada@example.com", 1))
    val result = kit.call(ProfileEntity.close)
    assertEquals(result.replyValue, Done)
    assertEquals(
      result.retention,
      Some(com.thinkmorestupidless.ankka.core.effect.Retention.DeleteNow)
    )
    assert(kit.isDeleted)
    assertEquals(kit.currentState, Profile("", "", 0))
  }
