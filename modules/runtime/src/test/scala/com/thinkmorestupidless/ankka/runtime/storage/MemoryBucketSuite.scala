package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

class MemoryBucketSuite extends BucketContract:

  private val store = MemoryObjectStorage(
    Map(
      "photos"  -> BucketExposure.Public,
      "exports" -> BucketExposure.Signed,
      "ledger"  -> BucketExposure.Private
    )
  )

  protected def contractBucket: Bucket = store.default
  protected def storeName: String      = "memory"

  private def refused(code: ErrorCode)(body: => Any): String =
    val e = intercept[CommandError](body)
    assertEquals(e.code, code, e.getMessage)
    e.getMessage

  test("each bucket keeps its own objects") {
    store.bucket("photos").putBytes("a.txt", "photos".getBytes(UTF_8), "text/plain")
    store.bucket("exports").putBytes("a.txt", "exports".getBytes(UTF_8), "text/plain")
    assertEquals(
      store.bucket("photos").getBytes("a.txt").map(b => new String(b._2, UTF_8)),
      Some("photos")
    )
    assertEquals(
      store.bucket("exports").getBytes("a.txt").map(b => new String(b._2, UTF_8)),
      Some("exports")
    )
    assertEquals(store.bucket("ledger").get("a.txt"), None)
    assertEquals(store.buckets.flatMap(_.name).toSet, Set("photos", "exports", "ledger"))
  }

  test("a bucket the service does not declare is refused naming those it does") {
    val message = refused(ErrorCode.Unavailable)(store.bucket("archive"))
    assert(
      message.contains("'archive'") && message.contains("'exports', 'ledger', 'photos'"),
      message
    )
  }

  test("a service with no unnamed bucket is refused naming provisionObjectStorage") {
    val none = MemoryObjectStorage(Map.empty, unnamed = false)
    assert(refused(ErrorCode.Unavailable)(none.default).contains("provisionObjectStorage"))
  }

  test("a signed URL is made for a signed bucket and the unnamed one, within the bound") {
    val signed = store.bucket("exports").signedUrl("march.pdf", SignedAccess.Read, 10.minutes)
    assert(
      signed.toString.startsWith("http://memory/exports/march.pdf?access=read&expires="),
      signed.toString
    )
    assert(
      store.default
        .signedUrl("a b.txt", SignedAccess.Keep)
        .toString
        .contains("/default/a%20b.txt?access=keep")
    )
    assert(
      refused(ErrorCode.BadRequest)(store.bucket("photos").signedUrl("a", SignedAccess.Read))
        .contains("public")
    )
    assert(
      refused(ErrorCode.BadRequest)(store.bucket("ledger").signedUrl("a", SignedAccess.Read))
        .contains("private")
    )
    assert(
      refused(ErrorCode.BadRequest)(
        store.bucket("exports").signedUrl("a", SignedAccess.Read, 8.days)
      ).contains("7 days")
    )
  }

  test("a public bucket answers an address; any other is refused naming its exposure") {
    assertEquals(
      store.bucket("photos").url("listings/42/front.jpg").toString,
      "http://memory/public/photos/listings/42/front.jpg"
    )
    assert(refused(ErrorCode.BadRequest)(store.bucket("exports").url("a")).contains("signed"))
    assert(refused(ErrorCode.BadRequest)(store.default.url("a")).contains("unnamed bucket"))
  }

  test(
    "a bucket declared while the service runs is reached; clear forgets objects and keeps buckets"
  ) {
    val local = MemoryObjectStorage(Map.empty)
    local.declare("late", BucketExposure.Private)
    local.bucket("late").putBytes("x", Array[Byte](1), "text/plain")
    local.clear()
    assertEquals(local.bucket("late").get("x"), None)
    intercept[IllegalArgumentException](local.declare("Not_A_Name", BucketExposure.Private))
  }
