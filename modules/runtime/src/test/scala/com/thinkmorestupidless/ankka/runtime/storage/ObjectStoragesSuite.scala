package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{BucketExposure, SignedAccess}
import com.typesafe.config.ConfigFactory

import java.nio.file.Files

class ObjectStoragesSuite extends munit.FunSuite:

  private def settings(env: Map[String, String], locally: Boolean, backend: String) =
    val base = ConfigFactory.load()
    val c =
      ConfigFactory
        .parseString(s"""ankka.storage { backend = "$backend", directory = "${Files
            .createTempDirectory("ankka-objs")}" }""")
        .withFallback(base)
    StorageSettings.from(c, env, locally).fold(fail(_), identity)

  private def build(env: Map[String, String], locally: Boolean = false, backend: String = "") =
    ObjectStorages.build(
      settings(env, locally, backend),
      Right(None),
      () => "http://127.0.0.1:9000"
    )

  private val named = Map(
    "ANKKA_S3_ENDPOINT"          -> "http://127.0.0.1:1",
    "ANKKA_S3_REGION"            -> "garage",
    "ANKKA_S3_ACCESS_KEY"        -> "GK1",
    "ANKKA_S3_SECRET_KEY"        -> "s",
    "ANKKA_S3_BUCKET_PHOTOS"     -> "shop.listings.photos",
    "ANKKA_S3_EXPOSURE_PHOTOS"   -> "public",
    "ANKKA_S3_PUBLIC_URL_PHOTOS" -> "https://storage.example.test/shop.listings.photos",
    "ANKKA_S3_BUCKET_EXPORTS"    -> "shop.listings.exports"
  )

  test("an unknown bucket's refusal lists the ones the service has") {
    val built = build(named).fold(fail(_), identity)
    assertEquals(built.backend, StorageBackend.S3)
    val e = intercept[CommandError](built.storage.bucket("archive"))
    assertEquals(e.code, ErrorCode.Unavailable)
    assert(e.getMessage.contains("'exports', 'photos'"), e.getMessage)
    assertEquals(built.storage.bucket("photos").exposure, BucketExposure.Public)
    assert(
      intercept[CommandError](built.storage.default).getMessage.contains("provisionObjectStorage")
    )
  }

  test("a bucket with no storage credential refuses naming objectStorageCredential") {
    val built =
      build(named -- Vector("ANKKA_S3_ACCESS_KEY", "ANKKA_S3_SECRET_KEY")).fold(fail(_), identity)
    val e = intercept[CommandError](built.storage.bucket("exports").get("a.txt"))
    assert(e.getMessage.contains("objectStorageCredential"), e.getMessage)
  }

  test("the memory store is the test kit's, refused by name") {
    assert(
      build(Map.empty, backend = "memory").left.exists(_.contains("ServiceBuilder.withStorage"))
    )
  }

  test(
    "a developer's machine is given the directory store, a deployed service with no bucket none"
  ) {
    assertEquals(build(Map.empty, locally = true).map(_.backend), Right(StorageBackend.Directory))
    val none = build(Map.empty).fold(fail(_), identity)
    assertEquals(none.backend, StorageBackend.Unavailable)
    assert(
      intercept[CommandError](none.storage.default).getMessage.contains("provisionObjectStorage")
    )
  }

  // T035: every bucket by name, each refused what its exposure does not allow.

  private val four = named ++ Map(
    "ANKKA_S3_BUCKET"             -> "shop.listings",
    "ANKKA_S3_EXPOSURE_EXPORTS"   -> "signed",
    "ANKKA_S3_ENDPOINT_ARCHIVE"   -> "https://s3.acme.example",
    "ANKKA_S3_REGION_ARCHIVE"     -> "eu-west-1",
    "ANKKA_S3_BUCKET_ARCHIVE"     -> "acme-archive",
    "ANKKA_S3_ACCESS_KEY_ARCHIVE" -> "AK",
    "ANKKA_S3_SECRET_KEY_ARCHIVE" -> "SK"
  )

  test("a public, a signed and a supplied bucket are built beside the unnamed one") {
    val built = build(four).fold(fail(_), identity)
    assertEquals(
      built.storage.buckets.map(b => b.name -> b.exposure).toMap,
      Map(
        Option("photos")  -> BucketExposure.Public,
        Option("exports") -> BucketExposure.Signed,
        Option("archive") -> BucketExposure.Supplied
      )
    )
    assertEquals(built.storage.default.name, None)
  }

  test("an address and a signed URL are refused on a bucket whose exposure does not allow them") {
    import scala.concurrent.duration.*
    val built = build(four).fold(fail(_), identity)
    val url   = intercept[CommandError](built.storage.bucket("exports").url("march.pdf"))
    assertEquals(url.code, ErrorCode.BadRequest)
    assert(url.getMessage.contains("'exports' is signed"), url.getMessage)
    val signed = intercept[CommandError](
      built.storage.bucket("photos").signedUrl("march.pdf", SignedAccess.Read, 15.minutes)
    )
    assertEquals(signed.code, ErrorCode.BadRequest)
    assert(signed.getMessage.contains("'photos' is public"), signed.getMessage)
  }

  test("a public address encodes the object's name and keeps its slashes") {
    val built = build(four).fold(fail(_), identity)
    assertEquals(
      built.storage.bucket("photos").url("listings/42/front jpg").toString,
      "https://storage.example.test/shop.listings.photos/listings/42/front%20jpg"
    )
  }

  test("the same code answers whatever base the installation gives a public bucket") {
    val cdn = "https://cdn.example.test/photos/"
    for base <- Vector("https://storage.example.test/shop.listings.photos", cdn) do
      val built = build(four.updated("ANKKA_S3_PUBLIC_URL_PHOTOS", base)).fold(fail(_), identity)
      assertEquals(
        built.storage.bucket("photos").url("listings/42/front.jpg").toString,
        base.stripSuffix("/") + "/listings/42/front.jpg"
      )
  }
