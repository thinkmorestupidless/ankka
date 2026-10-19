package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage

import java.nio.charset.StandardCharsets.UTF_8

/**
 * `features/storage-client/testkit.feature`: the test kit's store in memory, which a test seeds and
 * reads, which outlives a restart of the service and goes with the kit (feature 055).
 */
final class StorageTestKitFeatures
    extends StorageFeatures("../../features/storage-client/testkit.feature"):

  Given("a service {string} started by the test kit") { (name: String) =>
    start(name): Unit
  }

  Given("a service {string} and a service {string} started by the test kit") {
    (a: String, b: String) =>
      start(a): Unit
      start(b): Unit
      lastService = Some(a)
  }

  Given("a service {string} started by the test kit with a public bucket {string}") {
    (name: String, bucket: String) => publicBucket(name, bucket)
  }

  Given("the test has kept the object {string} in the bucket of {string}") {
    (objectName: String, name: String) =>
      val text = s"kept by the test: $objectName"
      storageOf(name).default.putBytes(objectName, text.getBytes(UTF_8), "image/jpeg")
      kept = kept.updated((name, None, objectName), text)
  }

  Then("the handler is told the object {string}") { (objectName: String) =>
    assertOk(told)
    assertEquals(told.body, keptText(current, objectName))
    assertEquals(told.contentType, "image/jpeg")
  }

  Then("no object store was started") { () =>
    val kit = service(current).kit
    assert(kit.storage.isInstanceOf[MemoryObjectStorage], s"the kit's store is ${kit.storage}")
    assert(
      kit.service.storage eq kit.storage,
      "the service keeps its objects somewhere other than the kit's store"
    )
    assertEquals(kit.service.directoryStorage, None)
  }

  When(
    "a handler of {string} keeps the object {string} with the content type {string} through the storage client"
  ) { (name: String, objectName: String, contentType: String) =>
    assertOk(keepThroughEndpoint(name, objectName, contentType))
  }

  Then("the test reads the object {string} back with the content type {string}") {
    (objectName: String, contentType: String) =>
      val (ct, body) =
        storageOf(current).default
          .getBytes(objectName)
          .getOrElse(fail(s"the test reads no '$objectName'"))
      assertEquals(ct, contentType)
      assertEquals(String(body, UTF_8), keptText(current, objectName))
  }

  When("the test restarts {string}") { (name: String) =>
    service(name).kit.restartService(): Unit
  }

  When("the test kit stops") { () =>
    val name = current
    service(name).kit.stop()
    services -= name
  }

  When("the test kit starts {string} again") { (name: String) =>
    start(name): Unit
  }

  When(
    "a handler of {string} keeps the object {string} in the public bucket {string} through the storage client"
  ) { (name: String, objectName: String, bucket: String) =>
    assertOk(keepThroughEndpoint(name, objectName, "image/jpeg", Some(bucket)))
  }

  Then("the test reads the object {string} back from the bucket {string} of {string}") {
    (objectName: String, bucket: String, name: String) =>
      val (_, body) = storageOf(name)
        .bucket(bucket)
        .getBytes(objectName)
        .getOrElse(fail(s"the test reads no '$objectName' in '$bucket'"))
      assertEquals(String(body, UTF_8), keptText(name, objectName, Some(bucket)))
  }

  Then("the storage client answers a public address for {string} in the bucket {string}") {
    (objectName: String, bucket: String) =>
      val address = send(
        current,
        "GET",
        "/storage/url" + query("bucket" -> Some(bucket), "name" -> Some(objectName))
      )
      assertOk(address)
      assert(address.body.endsWith("/" + objectName), address.body)
      assertEquals(address.body, storageOf(current).bucket(bucket).url(objectName).toString)
  }
