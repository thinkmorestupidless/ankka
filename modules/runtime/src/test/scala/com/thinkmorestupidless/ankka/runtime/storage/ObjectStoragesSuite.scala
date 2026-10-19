package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.BucketExposure
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
