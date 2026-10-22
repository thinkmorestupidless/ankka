package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.sdk.BucketExposure
import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Paths}

/**
 * `features/storage-client/buckets.feature`, the scenarios a service's own code decides, against
 * the test kit's store in memory and a stand-in object store on loopback (feature 055).
 *
 * What the platform does with a descriptor's buckets (the variables it renders, the buckets and the
 * credential it makes, what the store admits from the internet, the status, keeping and moving) is
 * `StorageClientClusterFeatures`' (k3s, the control plane's tests), against Garage on the local
 * overlay; the move is `ObjectStorageGcsClusterFeatures`'.
 */
final class StorageBucketsFeatures
    extends StorageFeatures("../../features/storage-client/buckets.feature"):

  private val cluster  = "StorageClientClusterFeatures (k3s), against Garage on the local overlay"
  private val outcomes = "the platform's part, which no service in this JVM has"

  override protected def ranElsewhere: Map[String, String] = Map(
    "a service is given every bucket its descriptor names"              -> cluster,
    "a browser reads an object in a public bucket without a signed URL" -> cluster,
    "an object in a private bucket is not readable from the internet whatever the service's other buckets admit" -> cluster,
    "a service signs a URL for an object in a signed bucket" -> cluster,
    "a supplied bucket is reached through the storage client and the platform makes nothing for it" -> cluster,
    "the storage credential of a service reaches every bucket the platform made for it and no other service's" -> cluster,
    "a descriptor that names two buckets alike is refused" -> s"$cluster, at apply; the rule is ObjectStorageDescriptorSuite's (controlplane-api)",
    "a descriptor whose bucket name cannot name a bucket is refused" -> s"$cluster, at apply; the rule is ObjectStorageDescriptorSuite's (controlplane-api)",
    "the status of a service names each of its buckets, its exposure and its address" -> s"$cluster; the lines are OutputSuite's (cli)",
    "deleting a service keeps every bucket the platform made and its objects" -> cluster,
    "a service that stops naming a bucket keeps it and loses its variables"   -> cluster,
    "a service's buckets are moved together" -> s"ObjectStorageGcsClusterFeatures (k3s): $outcomes"
  )

  private var exposures: Map[String, BucketExposure] = Map.empty
  private var stores: Vector[StandInStore]           = Vector.empty
  private var store: Option[StandInStore]            = None

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    exposures = Map.empty
    store = None

  override def afterEach(context: AfterEach): Unit =
    try super.afterEach(context)
    finally
      stores.foreach(_.stop())
      stores = Vector.empty

  private def standIn(down: Boolean = false): StandInStore =
    val s = StandInStore(down)
    stores :+= s
    store = Some(s)
    s

  private def withBuckets(name: String, buckets: (String, BucketExposure)*): Unit =
    exposures = buckets.toMap
    start(name, StorageBackendChoice.Memory(buckets = exposures)): Unit

  /**
   * A service whose store is the stand-in, with one public bucket read at `publicUrl`: the
   * variables the platform renders for a public bucket, `ANKKA_S3_PUBLIC_URL_<NAME>` among them.
   */
  private def onStandIn(name: String, bucket: String, s: StandInStore, publicUrl: String): Unit =
    exposures = Map(bucket -> BucketExposure.Public)
    val suffix = bucket.toUpperCase.replace('-', '_')
    start(
      name,
      StorageBackendChoice.FromEnvironment(
        Map(
          "ANKKA_S3_ENDPOINT"            -> s.endpoint,
          "ANKKA_S3_REGION"              -> "garage",
          "ANKKA_S3_BUCKET"              -> s"t.$name",
          "ANKKA_S3_ACCESS_KEY"          -> "GK-test",
          "ANKKA_S3_SECRET_KEY"          -> "not-a-secret",
          s"ANKKA_S3_BUCKET_$suffix"     -> s"t.$name.$bucket",
          s"ANKKA_S3_EXPOSURE_$suffix"   -> "public",
          s"ANKKA_S3_PUBLIC_URL_$suffix" -> publicUrl
        )
      )
    ): Unit

  private def addressOf(name: String, bucket: String, objectName: String) =
    send(name, "GET", "/storage/url" + query("bucket" -> Some(bucket), "name" -> Some(objectName)))

  // ── a named bucket by name ─────────────────────────────────────────────────

  Given("a deployed service {string} with a bucket and a named bucket {string}") {
    (name: String, bucket: String) => withBuckets(name, bucket -> BucketExposure.Private)
  }

  When(
    "a handler of {string} keeps the object {string} in the bucket {string} through the storage client"
  ) { (name: String, objectName: String, bucket: String) =>
    assertOk(keepThroughEndpoint(name, objectName, bucket = Some(bucket)))
  }

  Then(
    "a handler of {string} reads the object {string} back from the bucket {string} through the storage client"
  ) { (name: String, objectName: String, bucket: String) =>
    val read = readThroughEndpoint(name, objectName, Some(bucket))
    assertOk(read)
    assertEquals(read.body, keptText(name, objectName, Some(bucket)))
  }

  Then("the bucket of {string} does not hold the object {string}") {
    (name: String, objectName: String) =>
      assertEquals(readThroughEndpoint(name, objectName).status, 404)
  }

  When("a handler of {string} asks the storage client for the bucket {string}") {
    (name: String, bucket: String) => readThroughEndpoint(name, "anything.txt", Some(bucket)): Unit
  }

  // A refusal is the client's, answered as the handler's: never a crash of the endpoint.
  Then("the handler is refused") { () =>
    assert(Set(400, 503).contains(told.status), s"${told.status} ${told.body}")
  }

  Then("the refusal names the bucket {string} as the one the descriptor gives") { (bucket: String) =>
    assert(told.body.contains(s"'$bucket'"), told.body)
  }

  // ── what each exposure allows ──────────────────────────────────────────────

  Given(
    "a deployed service {string} with a public bucket {string}, a signed bucket {string} and a private bucket {string}"
  ) { (name: String, public: String, signed: String, priv: String) =>
    withBuckets(
      name,
      public -> BucketExposure.Public,
      signed -> BucketExposure.Signed,
      priv   -> BucketExposure.Private
    )
  }

  When(
    "a handler of {string} asks the storage client for a signed URL for reading {string} in the bucket {string}"
  ) { (name: String, objectName: String, bucket: String) =>
    send(
      name,
      "GET",
      "/storage/signed" +
        query("bucket" -> Some(bucket), "name" -> Some(objectName), "access" -> Some("read"))
    ): Unit
  }

  When(
    "a handler of {string} asks the storage client for the public address of {string} in the bucket {string}"
  ) { (name: String, objectName: String, bucket: String) =>
    addressOf(name, bucket, objectName): Unit
  }

  Then("the refusal names the exposure of the bucket {string}") { (bucket: String) =>
    val word = exposures.getOrElse(bucket, fail(s"no bucket '$bucket' was declared")).word
    assert(told.body.contains(s"'$bucket' is $word"), told.body)
  }

  // ── a public address, answered without the store ──────────────────────────

  Given("a deployed service {string} with a public bucket {string}") {
    (name: String, bucket: String) => withBuckets(name, bucket -> BucketExposure.Public)
  }

  Given("the object store of {string} cannot be reached") { (name: String) =>
    val bucket = exposures.keys.headOption.getOrElse(fail("no bucket was declared"))
    service(name).kit.stop()
    services -= name
    val s = standIn(down = true)
    onStandIn(name, bucket, s, s.publicBase(s"t.$name.$bucket"))
  }

  Then("the handler is told the public address") { () =>
    assertOk(told)
    val s = store.getOrElse(fail("no stand-in store"))
    assert(told.body.startsWith(s.endpoint + "/"), told.body)
    assert(told.body.endsWith("/listings/42/front.jpg"), told.body)
  }

  Then("nothing was sent to the object store") { () =>
    assertEquals(store.getOrElse(fail("no stand-in store")).requests, 0)
  }

  // ── the same code with or without a content delivery network ──────────────

  Given(
    "a deployed service {string} with a public bucket {string}, on an installation with a content delivery network in front of public buckets"
  ) { (name: String, bucket: String) =>
    val s = standIn()
    onStandIn(name, bucket, s, s.cdnBase(s"t.$name.$bucket"))
  }

  Given(
    "a deployed service {string} with a public bucket {string}, on an installation with no content delivery network"
  ) { (name: String, bucket: String) =>
    val s = standIn()
    onStandIn(name, bucket, s, s.publicBase(s"t.$name.$bucket"))
  }

  Given("a handler of {string} has kept the object {string} in the bucket {string}") {
    (name: String, objectName: String, bucket: String) =>
      assertOk(keepThroughEndpoint(name, objectName, bucket = Some(bucket)))
  }

  When(
    "a person on the internet sends a request to the public address that the storage client answered for {string} in the bucket {string}"
  ) { (objectName: String, bucket: String) =>
    val address = addressOf(current, bucket, objectName)
    assertOk(address)
    val response = http.send(
      HttpRequest.newBuilder(URI.create(address.body)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    answer = Some(
      Answer(
        response.statusCode,
        response.headers.firstValue("Content-Type").orElse(""),
        response.body
      )
    )
  }

  Then("the person is shown the object {string}") { (objectName: String) =>
    assertOk(told)
    val bucket = exposures.keys.headOption
    assertEquals(told.body, keptText(current, objectName, bucket))
  }

  Then("the code of {string} names no address") { (_: String) =>
    val source = Paths.get(
      "src/test/scala/com/thinkmorestupidless/ankka/testkit/storage/StorageComponents.scala"
    )
    val text = Files.readString(source)
    Seq("http://", "https://", "127.0.0.1", "cdn", "PUBLIC_URL").foreach { word =>
      assert(!text.contains(word), s"the fixture service's code names '$word'")
    }
  }
