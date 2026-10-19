package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage
import com.thinkmorestupidless.ankka.sdk.{BucketExposure, SignedAccess}

import java.nio.charset.StandardCharsets.UTF_8

/** A workflow's storage client answers in a step and refuses a command handler (feature 055). */
class StepScopeSuite extends munit.FunSuite:

  private def fresh() =
    val underlying = MemoryObjectStorage(Map("photos" -> BucketExposure.Signed))
    (underlying, StepScope.stepsOnly(underlying))

  test("a keep outside a step is refused with the step's words, and nothing is kept") {
    val (underlying, scoped) = fresh()
    val refused =
      intercept[CommandError](scoped.default.putBytes("a.txt", "a".getBytes(UTF_8), "text/plain"))
    assertEquals(refused.code, ErrorCode.BadRequest)
    assert(refused.getMessage.contains("in a step, not in a command handler"), refused.getMessage)
    assertEquals(underlying.default.stat("a.txt"), None)
  }

  test("every operation outside a step is refused, a signed URL included") {
    val (_, scoped) = fresh()
    val photos      = scoped.bucket("photos")
    val calls: Vector[(String, () => Any)] = Vector(
      "get"       -> (() => photos.get("a")),
      "stat"      -> (() => photos.stat("a")),
      "delete"    -> (() => photos.delete("a")),
      "list"      -> (() => photos.list("")),
      "signedUrl" -> (() => photos.signedUrl("a", SignedAccess.Read)),
      "url"       -> (() => photos.url("a"))
    )
    calls.foreach { case (label, call) =>
      val refused = intercept[CommandError](call())
      assert(refused.getMessage.contains("in a step"), s"$label: ${refused.getMessage}")
    }
  }

  test("inside a step every call passes through to the store") {
    val (underlying, scoped) = fresh()
    StepScope.within(scoped.bucket("photos").putBytes("a.txt", "a".getBytes(UTF_8), "text/plain"))
    assertEquals(underlying.bucket("photos").getBytes("a.txt").map(_._1), Some("text/plain"))
    val listed = StepScope.within(scoped.bucket("photos").list("").map(_.name).toVector)
    assertEquals(listed, Vector("a.txt"))
  }

  test(
    "naming a bucket outside a step is harmless, and an unknown name is refused as the store refuses it"
  ) {
    val (_, scoped) = fresh()
    assertEquals(scoped.bucket("photos").name, Some("photos"))
    val refused = intercept[CommandError](scoped.bucket("other"))
    assertEquals(refused.code, ErrorCode.Unavailable)
  }
