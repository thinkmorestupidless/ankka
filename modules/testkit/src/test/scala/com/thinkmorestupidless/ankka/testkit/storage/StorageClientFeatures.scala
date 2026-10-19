package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice

import java.nio.file.{Files, Paths}

import StorageCodecs.given

/**
 * `features/storage-client/client.feature` against the test kit's store in memory, through the
 * fixture service's own endpoint (feature 055).
 *
 * Of "the same on every object store", the Garage row is `S3BucketSuite`'s (runtime), which runs
 * the contract every store meets against Garage in a container; the row of an object store of the
 * service's own is `StorageClientClusterFeatures`'s; the Google Cloud Storage row is ankka-gcp's
 * nightly suite against a real bucket. Each is skipped here by name, never passed.
 */
final class StorageClientFeatures
    extends StorageFeatures("../../features/storage-client/client.feature"):

  Given("a handler of {string} has kept the object {string} with the content type {string}") {
    (name: String, objectName: String, contentType: String) =>
      assertOk(keepThroughEndpoint(name, objectName, contentType))
  }

  Then("the handler is told the content type {string}") { (contentType: String) =>
    assertOk(told)
    assertEquals(told.contentType, contentType)
  }

  Given("a handler of {string} has kept the objects {string}, {string} and {string}") {
    (name: String, a: String, b: String, c: String) =>
      Seq(a, b, c).foreach(o => assertOk(keepThroughEndpoint(name, o)))
  }

  When("a handler of {string} lists the objects under the prefix {string}") {
    (name: String, prefix: String) =>
      send(name, "GET", "/storage/list" + query("prefix" -> Some(prefix))): Unit
  }

  Then("the handler is told {string} and {string} and no other") { (a: String, b: String) =>
    assertOk(told)
    assertEquals(readJson[Listed](told.body).objects.map(_.name).toSet, Set(a, b))
  }

  Then("each is told with its size and when it was kept") { () =>
    readJson[Listed](told.body).objects.foreach { o =>
      assertEquals(o.length, keptText(current, o.name).getBytes("UTF-8").length.toLong, o.toString)
      assert(
        java.time.Instant.parse(o.lastKept).isAfter(java.time.Instant.now().minusSeconds(300)),
        o.toString
      )
    }
  }

  When("a handler of {string} asks what the object {string} is") {
    (name: String, objectName: String) =>
      send(name, "GET", "/storage/stat" + query("name" -> Some(objectName))): Unit
  }

  Then("the handler is told its size, the content type {string} and when it was kept") {
    (contentType: String) =>
      assertOk(told)
      val described = readJson[Described](told.body)
      assertEquals(described.contentType, contentType)
      assertEquals(
        described.length,
        keptText(current, described.name).getBytes("UTF-8").length.toLong
      )
      assert(
        java.time.Instant
          .parse(described.lastKept)
          .isAfter(java.time.Instant.now().minusSeconds(300))
      )
  }

  When("a handler of {string} deletes the object {string}") { (name: String, objectName: String) =>
    assertOk(send(name, "DELETE", "/storage/objects" + query("name" -> Some(objectName))))
  }

  Then("deleting the object {string} again does not fail") { (objectName: String) =>
    assertOk(send(current, "DELETE", "/storage/objects" + query("name" -> Some(objectName))))
  }

  Then("the read does not fail") { () =>
    // Told there is nothing, not failed: a 404 the handler chose, never a 5xx from the client.
    assertEquals(told.status, 404, told.body)
    assert(told.body.contains("no object"), told.body)
  }

  // ── the same code on every store ───────────────────────────────────────────

  Given("the bucket of {string} is in Garage") { (_: String) =>
    assume(
      false,
      "the Garage row is S3BucketSuite's (runtime): the contract every store meets, against Garage"
    )
  }

  Given("the bucket of {string} is in Google Cloud Storage") { (_: String) =>
    assume(
      false,
      "the Google Cloud Storage row is ankka-gcp's nightly suite, against a real bucket"
    )
  }

  Given("the bucket of {string} is in an object store of the service's own") { (_: String) =>
    assume(false, "the row of a store of the service's own is StorageClientClusterFeatures' (k3s)")
  }

  Then("the code of {string} names no object store") { (_: String) =>
    val source = Paths.get(
      "src/test/scala/com/thinkmorestupidless/ankka/testkit/storage/StorageComponents.scala"
    )
    val text = Files.readString(source)
    Seq("S3", "Garage", "garage", "Google", "gcs", "MemoryObjectStorage", "Directory").foreach {
      word =>
        assert(!text.contains(word), s"the fixture service's code names '$word'")
    }
  }

  // ── names that break the rule ──────────────────────────────────────────────

  When("a handler of {string} keeps an object named {string}") { (name: String, objectName: String) =>
    keepThroughEndpoint(name, objectName): Unit
  }

  When("a handler of {string} keeps an object with a name longer than the limit") { (name: String) =>
    keepThroughEndpoint(name, "a/" + "x" * 1023): Unit
  }

  Then("the handler is refused") { () =>
    assertEquals(told.status, 400, told.body)
  }

  Then("the refusal names the rule for object names") { () =>
    assert(told.body.contains("an object's name"), told.body)
  }

  Then("nothing was sent to the object store") { () =>
    // The kit's store records every keep it was asked for, refused or not: nothing reached it.
    assertEquals(memoryStoreOf(current).default.list("").toVector, Vector.empty)
  }

  // ── an unreachable store and a short stream ────────────────────────────────

  Given("the object store of {string} cannot be reached") { (name: String) =>
    service(name).kit.stop()
    services -= name
    val port = closedPort()
    start(
      name,
      StorageBackendChoice.FromEnvironment(
        Map(
          "ANKKA_S3_ENDPOINT"   -> s"http://127.0.0.1:$port",
          "ANKKA_S3_REGION"     -> "garage",
          "ANKKA_S3_BUCKET"     -> "t.listings",
          "ANKKA_S3_ACCESS_KEY" -> "GK-test",
          "ANKKA_S3_SECRET_KEY" -> "not-a-secret"
        )
      )
    ): Unit
  }

  Then("the failure says that the object store cannot be reached") { () =>
    assertEquals(told.status, 503, told.body)
    assert(told.body.contains("cannot be reached") && told.body.contains("127.0.0.1"), told.body)
  }

  When(
    "a handler of {string} keeps the object {string} from a stream that ends before its declared length"
  ) { (name: String, objectName: String) =>
    val before = keptText(name, objectName)
    val told   = keepThroughEndpoint(name, objectName, route = "/storage/objects/short")
    // What the failed keep sent is not what is kept: the object kept before is.
    kept = kept.updated((name, None, objectName), before)
    answer = Some(told)
  }

  Then("a handler that reads the object {string} is told the object kept before") {
    (objectName: String) =>
      val read = readThroughEndpoint(current, objectName)
      assertOk(read)
      assertEquals(read.body, keptText(current, objectName))
  }
