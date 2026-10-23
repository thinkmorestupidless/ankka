package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class DirectoryBucketSuite extends BucketContract:

  private val root  = Files.createTempDirectory("ankka-directory-store")
  private val store = DirectoryObjectStorage(root, None, () => "http://127.0.0.1:9000")

  protected def contractBucket: Bucket = store.default
  protected def storeName: String      = "directory"

  private def refused(code: ErrorCode)(body: => Any): String =
    val e = intercept[CommandError](body)
    assertEquals(e.code, code, e.getMessage)
    e.getMessage

  private def files(under: Path): Vector[Path] =
    if !Files.exists(under) then Vector.empty
    else
      scala.util.Using.resource(Files.walk(under))(
        _.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      )

  test("a failed put leaves no part file behind and the old object readable") {
    store.default.putBytes("kept.txt", "before".getBytes(UTF_8), "text/plain")
    intercept[CommandError](
      store.default
        .put("kept.txt", new ByteArrayInputStream("ab".getBytes(UTF_8)), 10L, "text/plain")
    )
    assertEquals(files(root.resolve(".tmp")), Vector.empty)
    assertEquals(
      store.default.getBytes("kept.txt").map(b => new String(b._2, UTF_8)),
      Some("before")
    )
  }

  test(
    "an object is a file under its bucket's directory, its name, content type and time beside it"
  ) {
    store.default.putBytes("listings/42/photos/front.jpg", "front".getBytes(UTF_8), "image/jpeg")
    val objectFile = root.resolve("_default/objects/listings%2F42%2Fphotos%2Ffront.jpg")
    assertEquals(new String(Files.readAllBytes(objectFile), UTF_8), "front")
    val meta = new String(
      Files.readAllBytes(root.resolve("_default/meta/listings%2F42%2Fphotos%2Ffront.jpg.json")),
      UTF_8
    )
    assert(meta.contains("\"name\":\"listings/42/photos/front.jpg\""), meta)
    assert(meta.contains("\"contentType\":\"image/jpeg\""), meta)
    assert(meta.contains("\"lastKept\":\""), meta)
  }

  test("an object 'a' and an object 'a/b' are both kept, as a bucket allows") {
    store.default.putBytes("both/a", "a".getBytes(UTF_8), "text/plain")
    store.default.putBytes("both/a/b", "b".getBytes(UTF_8), "text/plain")
    assertEquals(store.default.list("both/").map(_.name).toVector, Vector("both/a", "both/a/b"))
  }

  test("a name too long for a file name is kept under its hash and read back by its name") {
    val long = "long/" + ("x" * 900)
    store.default.putBytes(long, "long".getBytes(UTF_8), "text/plain")
    assertEquals(store.default.getBytes(long).map(b => new String(b._2, UTF_8)), Some("long"))
    assertEquals(store.default.list("long/").map(_.name).toVector, Vector(long))
  }

  test(
    "a root that cannot be written is refused, naming it, when the descriptor declares a bucket"
  ) {
    val readOnly = Files.createTempDirectory("ankka-read-only")
    readOnly.toFile.setWritable(false): Unit
    try
      val declared = LocalBuckets.Declared(unnamed = false, Map("photos" -> BucketExposure.Public))
      val opened   =
        DirectoryObjectStorage.open(readOnly.resolve("storage"), Some(declared), () => "")
      assert(opened.left.exists(_.contains(readOnly.resolve("storage").toString)), opened)
    finally readOnly.toFile.setWritable(true): Unit
  }

  test(
    "with no bucket declared, a root that cannot be written is not touched until a call, which is Unavailable naming it"
  ) {
    val readOnly = Files.createTempDirectory("ankka-read-only")
    readOnly.toFile.setWritable(false): Unit
    try
      val storage = readOnly.resolve("storage")
      val opened  = DirectoryObjectStorage.open(storage, None, () => "http://127.0.0.1:9000")
      assert(opened.isRight, opened)
      assert(!Files.exists(storage), "the store was made at open")
      val bucket = opened.toOption.get.default
      val put    = refused(ErrorCode.Unavailable)(
        bucket.put("a.txt", ByteArrayInputStream("a".getBytes(UTF_8)), 1, "text/plain")
      )
      assert(put.contains(storage.toString), put)
      val signed =
        refused(ErrorCode.Unavailable)(bucket.signedUrl("a.txt", SignedAccess.Read, 1.minute))
      assert(signed.contains(storage.toString), signed)
    finally readOnly.toFile.setWritable(true): Unit
  }

  test("a signed URL and a public address point at the service's own port, under /_ankka/storage") {
    val declared = LocalBuckets.Declared(
      true,
      Map("photos" -> BucketExposure.Public, "ledger" -> BucketExposure.Private)
    )
    val local = DirectoryObjectStorage(
      Files.createTempDirectory("ankka-addresses"),
      Some(declared),
      () => "http://127.0.0.1:9000"
    )
    val signed = local.default.signedUrl("exports/march.pdf", SignedAccess.Read, 1.minute).toString
    assert(
      signed.startsWith(
        "http://127.0.0.1:9000/_ankka/storage/_default/exports/march.pdf?access=read&expires="
      ),
      signed
    )
    assert(signed.contains("&token="), signed)
    assertEquals(
      local.bucket("photos").url("listings/42/front.jpg").toString,
      "http://127.0.0.1:9000/_ankka/storage/photos/listings/42/front.jpg"
    )
    assert(
      refused(ErrorCode.BadRequest)(local.bucket("ledger").signedUrl("a", SignedAccess.Read))
        .contains("private")
    )
    assert(refused(ErrorCode.Unavailable)(local.bucket("archive")).contains("'ledger', 'photos'"))
  }

  test("the signer admits its own token until it expires, and nothing else") {
    val signer  = store.signer
    val now     = Instant.now()
    val expires = now.getEpochSecond + 60
    val token   = signer.token("_default", "a.txt", "read", expires)
    assert(signer.admits("_default", "a.txt", "read", expires, token, now))
    assert(!signer.admits("_default", "a.txt", "keep", expires, token, now))
    assert(!signer.admits("_default", "b.txt", "read", expires, token, now))
    assert(!signer.admits("_default", "a.txt", "read", expires, token, now.plusSeconds(120)))
    assert(
      DirectorySigner.at(root).admits("_default", "a.txt", "read", expires, token, now),
      "a restart keeps the key"
    )
  }

  test(
    "without service.json any bucket name is a private bucket, and a name that is not one is refused"
  ) {
    assertEquals(store.bucket("exports").exposure, BucketExposure.Private)
    assert(refused(ErrorCode.BadRequest)(store.bucket("Exports")).contains("not a bucket name"))
  }
