package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.operator.BaseBackupRetry.{Attempt, due}

import java.time.Instant

class BaseBackupRetrySuite extends munit.FunSuite:
  private val now          = Instant.parse("2026-10-09T16:20:00Z")
  private def ago(s: Long) = Some(now.minusSeconds(s))
  private val failed       = Attempt(Some("failed"), ago(300))

  test("a first base backup that failed is taken again once a while has passed") {
    assert(due(Vector(failed), now))
    assert(!due(Vector(Attempt(Some("failed"), ago(30))), now), "too soon after the failure")
  }

  test("nothing is taken again once one has completed, while one runs, or before any was taken") {
    assert(!due(Vector(failed, Attempt(Some("completed"), ago(200))), now))
    assert(!due(Vector(failed, Attempt(Some("running"), ago(200))), now))
    assert(!due(Vector(failed, Attempt(None, ago(200))), now), "a backup CNPG has not begun")
    assert(!due(Vector.empty, now))
  }

  test("a target that keeps refusing is tried a few times, not every few minutes for ever") {
    assert(due(Vector.fill(BaseBackupRetry.AtMost - 1)(failed), now))
    assert(!due(Vector.fill(BaseBackupRetry.AtMost)(failed), now))
  }

  test("the latest failure decides when, whichever order they are listed in") {
    assert(!due(Vector(failed, Attempt(Some("failed"), ago(10))), now))
  }
