package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.Bucket

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger

/**
 * What every store behind the storage client does the same (feature 055): the memory store, the S3
 * store and the directory store each run these cases against a bucket of their own. Each case keeps
 * its objects under a prefix of its own, so a store that keeps what earlier cases kept still
 * passes.
 */
trait BucketContract extends munit.FunSuite:

  /** The bucket under test, ready to keep objects. */
  protected def contractBucket: Bucket

  /** The store's name in the test names. */
  protected def storeName: String

  private val counter        = new AtomicInteger()
  private def area(): String = s"contract-${System.nanoTime()}-${counter.incrementAndGet()}/"

  private def refusal(expected: ErrorCode)(body: => Any): CommandError =
    val e = intercept[CommandError](body)
    assertEquals(e.code, expected, e.getMessage)
    e

  test(s"$storeName: an object is read back whole with its content type") {
    val at = area() + "photos/front.jpg"
    contractBucket.putBytes(at, "front".getBytes(UTF_8), "image/jpeg")
    val read = contractBucket.getBytes(at).getOrElse(fail("nothing read back"))
    assertEquals(read._1, "image/jpeg")
    assertEquals(new String(read._2, UTF_8), "front")
  }

  test(s"$storeName: a put replaces what was kept") {
    val at = area() + "a.txt"
    contractBucket.putBytes(at, "one".getBytes(UTF_8), "text/plain")
    contractBucket.putBytes(at, "two".getBytes(UTF_8), "text/markdown")
    assertEquals(
      contractBucket.getBytes(at).map((t, b) => t -> new String(b, UTF_8)),
      Some("text/markdown" -> "two")
    )
  }

  test(
    s"$storeName: a stream that ends before its declared length keeps nothing and leaves the old object"
  ) {
    val at = area() + "a.txt"
    contractBucket.putBytes(at, "kept before".getBytes(UTF_8), "text/plain")
    val e = intercept[CommandError](
      contractBucket.put(at, new ByteArrayInputStream("short".getBytes(UTF_8)), 1000L, "text/plain")
    )
    assert(e.getMessage.contains("declared"), e.getMessage)
    assertEquals(contractBucket.getBytes(at).map(b => new String(b._2, UTF_8)), Some("kept before"))
    val fresh = area() + "never.txt"
    intercept[CommandError](
      contractBucket.put(
        fresh,
        new ByteArrayInputStream("short".getBytes(UTF_8)),
        1000L,
        "text/plain"
      )
    )
    assertEquals(contractBucket.stat(fresh), None)
  }

  test(s"$storeName: a stream longer than its declared length keeps the declared bytes") {
    val at = area() + "a.txt"
    contractBucket.put(at, new ByteArrayInputStream("abcdef".getBytes(UTF_8)), 3L, "text/plain")
    assertEquals(contractBucket.getBytes(at).map(b => new String(b._2, UTF_8)), Some("abc"))
  }

  test(s"$storeName: an empty object is kept") {
    val at = area() + "empty"
    contractBucket.putBytes(at, Array.emptyByteArray, "application/octet-stream")
    assertEquals(contractBucket.stat(at).map(_.length), Some(0L))
    assertEquals(contractBucket.getBytes(at).map(_._2.length), Some(0))
  }

  test(s"$storeName: reading or asking after nothing answers None") {
    val at = area() + "nothing.txt"
    assertEquals(contractBucket.get(at), None)
    assertEquals(contractBucket.stat(at), None)
  }

  test(s"$storeName: stat tells the size, the content type and when it was kept") {
    val before = java.time.Instant.now().minusSeconds(5)
    val at     = area() + "photos/front.jpg"
    contractBucket.putBytes(at, "12345".getBytes(UTF_8), "image/jpeg")
    val info = contractBucket.stat(at).getOrElse(fail("nothing kept"))
    assertEquals(info.name, at)
    assertEquals(info.length, 5L)
    assertEquals(info.contentType, "image/jpeg")
    assert(!info.lastKept.isBefore(before), info)
    assert(!info.lastKept.isAfter(java.time.Instant.now().plusSeconds(5)), info)
  }

  test(s"$storeName: a listing under a prefix tells only what is under it, in name order") {
    val base = area()
    for n <- Vector(
        "42/photos/front.jpg",
        "42/photos/back.jpg",
        "43/photos/front.jpg",
        "42/notes.txt"
      )
    do contractBucket.putBytes(base + n, n.getBytes(UTF_8), "text/plain")
    val listed = contractBucket.list(base + "42/").toVector
    assertEquals(
      listed.map(_.name),
      Vector("42/notes.txt", "42/photos/back.jpg", "42/photos/front.jpg").map(base + _)
    )
    assertEquals(
      listed.map(_.length),
      Vector("42/notes.txt", "42/photos/back.jpg", "42/photos/front.jpg").map(_.length.toLong)
    )
    assert(listed.forall(_.lastKept != null))
    assertEquals(contractBucket.list(base).size, 4)
    assertEquals(contractBucket.list(base + "44/").size, 0)
  }

  test(s"$storeName: a listing with an empty prefix includes what was kept") {
    val at = area() + "found.txt"
    contractBucket.putBytes(at, "x".getBytes(UTF_8), "text/plain")
    assert(contractBucket.list("").exists(_.name == at))
  }

  test(s"$storeName: a listing of more objects than one answer holds reads them all") {
    val base = area()
    for i <- 1 to 25 do
      contractBucket.putBytes(f"$base$i%03d", Array[Byte](1), "application/octet-stream")
    assertEquals(
      contractBucket.list(base).map(_.name).toVector,
      (1 to 25).map(i => f"$base$i%03d").toVector
    )
  }

  test(s"$storeName: a deleted object is gone, and deleting it again does not fail") {
    val at = area() + "a.txt"
    contractBucket.putBytes(at, "x".getBytes(UTF_8), "text/plain")
    contractBucket.delete(at)
    assertEquals(contractBucket.get(at), None)
    contractBucket.delete(at)
  }

  test(s"$storeName: a read closed before its end releases what it held") {
    val at = area() + "large.bin"
    contractBucket.putBytes(at, Array.fill[Byte](256 * 1024)(7), "application/octet-stream")
    for _ <- 1 to 20 do
      val o = contractBucket.get(at).getOrElse(fail("nothing kept"))
      assertEquals(o.length, 256L * 1024)
      assertEquals(o.body.read(), 7)
      o.close()
    assertEquals(contractBucket.getBytes(at).map(_._2.length), Some(256 * 1024))
  }

  test(s"$storeName: a name or a prefix that breaks the rule is refused before anything is sent") {
    for bad <- Vector("/a", "a//b", "a/./b", "a/../b", "", "a" * 1025) do
      val e =
        refusal(ErrorCode.BadRequest)(contractBucket.putBytes(bad, Array[Byte](1), "text/plain"))
      assert(e.getMessage.contains("an object's name"), e.getMessage)
      refusal(ErrorCode.BadRequest)(contractBucket.get(bad))
      refusal(ErrorCode.BadRequest)(contractBucket.stat(bad))
      refusal(ErrorCode.BadRequest)(contractBucket.delete(bad))
    val e = refusal(ErrorCode.BadRequest)(contractBucket.list("/a/"))
    assert(e.getMessage.contains("a prefix"), e.getMessage)
  }

  test(s"$storeName: a negative length is refused") {
    refusal(ErrorCode.BadRequest)(
      contractBucket.put(area() + "a", InputStream.nullInputStream(), -1L, "text/plain")
    )
  }

  test(
    s"$storeName: a name with spaces, unicode and reserved characters is kept and listed as given"
  ) {
    val base = area()
    val at   = base + "naïve file (1)+&=?#.txt"
    contractBucket.putBytes(at, "x".getBytes(UTF_8), "text/plain")
    assertEquals(contractBucket.getBytes(at).map(b => new String(b._2, UTF_8)), Some("x"))
    assertEquals(contractBucket.list(base).map(_.name).toVector, Vector(at))
  }
