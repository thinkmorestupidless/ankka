package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.BucketExposure
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, StorageBackendChoice}
import com.typesafe.config.ConfigFactory

import java.nio.charset.StandardCharsets.UTF_8

/**
 * The test kit's storage beyond what `testkit.feature` says (feature 055): buckets a test declares,
 * a name the service does not declare, and a store the runtime chooses that refuses the start.
 */
class AnkkaTestKitStorageSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = scala.concurrent.duration.Duration(3, "min")

  private def withKit[A](storage: StorageBackendChoice)(body: AnkkaTestKit => A): A =
    val kit = AnkkaTestKit.start(Seq(Listing.descriptor), storage = storage)
    try body(kit)
    finally kit.stop()

  test("a bucket the test declares after the start is answered, and its public address made") {
    withKit(StorageBackendChoice.Memory()) { kit =>
      // docs:start kit
      kit.declareBucket("photos", BucketExposure.Public)
      kit.storage.bucket("photos").putBytes("a/b.png", "png".getBytes(UTF_8), "image/png")
      assertEquals(
        kit.service.storage.bucket("photos").stat("a/b.png").map(_.contentType),
        Some("image/png")
      )
      assert(kit.storage.bucket("photos").url("a/b.png").toString.endsWith("/a/b.png"))
      // docs:end kit
    }
  }

  test("a name the service does not declare is refused, naming the ones it does") {
    withKit(
      StorageBackendChoice.Memory(buckets =
        Map("photos" -> BucketExposure.Public, "exports" -> BucketExposure.Signed)
      )
    ) { kit =>
      val refused = intercept[CommandError](kit.service.storage.bucket("invoices"))
      assertEquals(refused.code, ErrorCode.Unavailable)
      assert(refused.message.contains("'exports', 'photos'"), refused.message)
    }
  }

  test("an upload passes to the bucket as it arrives, and a download is read from it") {
    val kit = AnkkaTestKit.start(
      Seq(Listing.descriptor),
      Seq(
        com.thinkmorestupidless.ankka.http.HttpServer.at("127.0.0.1", 0)(clients =>
          PhotosEndpoint(clients)
        )
      )
    )
    try
      val base   = kit.service.boundAddresses.head
      val client = java.net.http.HttpClient.newHttpClient()
      val body   = Array.fill[Byte](3 * 1024 * 1024)('p'.toByte)
      val uploaded = client.send(
        java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"$base/storage/upload?name=listings%2F42%2Ffront.jpg"))
          .header("Content-Type", "image/jpeg")
          .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(uploaded.statusCode, 204, uploaded.body)
      assertEquals(
        kit.storage.default.stat("listings/42/front.jpg").map(i => (i.contentType, i.length)),
        Some(("image/jpeg", body.length.toLong))
      )
      val downloaded = client.send(
        java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(s"$base/storage/download?name=listings%2F42%2Ffront.jpg"))
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofByteArray()
      )
      assertEquals(downloaded.statusCode, 200)
      assertEquals(downloaded.headers.firstValue("Content-Type").orElse(""), "image/jpeg")
      assert(java.util.Arrays.equals(downloaded.body, body))
    finally kit.stop()
  }

  test("a store in memory is the kit's alone: two kits keep apart") {
    withKit(StorageBackendChoice.Memory()) { a =>
      withKit(StorageBackendChoice.Memory()) { b =>
        a.storage.default.putBytes("x.txt", "a".getBytes(UTF_8), "text/plain")
        assertEquals(b.storage.default.stat("x.txt"), None)
      }
    }
  }

  test("a service told to use the s3 store with no endpoint does not start, naming the variable") {
    val refused = intercept[Throwable] {
      AnkkaTestKit.start(
        Seq(Listing.descriptor),
        storage = StorageBackendChoice.FromEnvironment(),
        settings = ConfigFactory.parseString("ankka.storage.backend = s3")
      )
    }
    val messages =
      Iterator
        .iterate(refused)(_.getCause)
        .takeWhile(_ != null)
        .map(t => String.valueOf(t.getMessage))
        .mkString(" / ")
    assert(messages.contains("ANKKA_S3_ENDPOINT is not set"), messages)
  }
