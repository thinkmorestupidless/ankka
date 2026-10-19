package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.sdk.BucketExposure

import java.nio.file.Files

class LocalBucketsSuite extends munit.FunSuite:

  private def withDescriptor(json: String) =
    val dir = Files.createTempDirectory("ankka-local-buckets")
    Files.writeString(dir.resolve("service.json"), json)
    LocalBuckets.read(dir)

  test("the contract's descriptor is the unnamed bucket and four named ones") {
    val read = withDescriptor(
      """{
        |  "name": "listings",
        |  "service": {
        |    "image": "registry.example.com/acme/listings:1.0.0",
        |    "provisionObjectStorage": true,
        |    "buckets": [
        |      { "name": "photos", "exposure": "public" },
        |      { "name": "exports", "exposure": "signed", "origins": ["https://app.example.com"] },
        |      { "name": "documents" },
        |      { "name": "archive", "supplied": {
        |          "endpoint": "https://s3.eu-west-2.amazonaws.com", "region": "eu-west-2",
        |          "bucket": "acme-archive", "credential": "archive-store" } }
        |    ]
        |  }
        |}""".stripMargin
    )
    assertEquals(
      read,
      Right(
        Some(
          LocalBuckets.Declared(
            true,
            Map(
              "photos"    -> BucketExposure.Public,
              "exports"   -> BucketExposure.Signed,
              "documents" -> BucketExposure.Private,
              "archive"   -> BucketExposure.Supplied
            )
          )
        )
      )
    )
  }

  test("no service.json is nothing declared") {
    assertEquals(LocalBuckets.read(Files.createTempDirectory("ankka-none")), Right(None))
  }

  test("a descriptor without buckets is the unnamed bucket alone, or none") {
    assertEquals(
      withDescriptor("""{"service":{"provisionObjectStorage":true}}"""),
      Right(Some(LocalBuckets.Declared(true, Map.empty)))
    )
    assertEquals(
      withDescriptor("""{"service":{"image":"x"}}"""),
      Right(Some(LocalBuckets.Declared(false, Map.empty)))
    )
  }

  test("an exposure that is no word is refused naming the bucket") {
    assert(
      withDescriptor("""{"service":{"buckets":[{"name":"photos","exposure":"visible"}]}}""").left
        .exists(_.contains("'photos'"))
    )
    assert(withDescriptor("""not json""").left.exists(_.contains("service.json")))
  }
