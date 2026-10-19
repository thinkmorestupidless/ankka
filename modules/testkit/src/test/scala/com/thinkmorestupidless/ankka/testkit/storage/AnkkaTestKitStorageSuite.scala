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
      kit.declareBucket("photos", BucketExposure.Public)
      kit.storage.bucket("photos").putBytes("a/b.png", "png".getBytes(UTF_8), "image/png")
      assertEquals(
        kit.service.storage.bucket("photos").stat("a/b.png").map(_.contentType),
        Some("image/png")
      )
      assert(kit.storage.bucket("photos").url("a/b.png").toString.endsWith("/a/b.png"))
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
