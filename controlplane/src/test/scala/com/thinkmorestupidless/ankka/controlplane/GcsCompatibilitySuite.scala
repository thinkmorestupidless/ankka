package com.thinkmorestupidless.ankka.controlplane

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{
  RequestChecksumCalculation,
  ResponseChecksumValidation
}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CORSConfiguration,
  CORSRule,
  DeleteObjectRequest,
  GetObjectRequest,
  ListObjectVersionsRequest,
  PutBucketCorsRequest,
  PutObjectRequest,
  S3Exception
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * What the docs promise a service of an S3 client against Google Cloud Storage, held against a real
 * bucket (feature 039, research S2).
 *
 * No suite of ankka reaches Google but this one, and it runs only where a bucket is named: nightly
 * and on demand in the `gcs` workflow, or by hand. Without `ANKKA_GCS_BUCKET`,
 * `ANKKA_GCS_ACCESS_KEY` and `ANKKA_GCS_SECRET_KEY` it skips, unless `-Dankka.gcs.tests=on` asked
 * for it, when it fails: a workflow that asked for it must not report green having run nothing. It
 * never reads `ankka.cluster.tests`, so the k3s matrix never lists it.
 *
 * The bucket is prepared once by hand (contracts/installation.md): versioning on, soft delete,
 * public access prevention, uniform access, a CORS rule admitting `ANKKA_GCS_ORIGIN`, and an HMAC
 * key of a service account granted on it alone. Tests named for a scenario prove that scenario for
 * `ObjectStorageGcsClusterFeatures`, which cannot reach Google.
 */
class GcsCompatibilitySuite extends munit.FunSuite:

  private def env(name: String): Option[String] =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty)

  private val endpoint = env("ANKKA_GCS_ENDPOINT").getOrElse("https://storage.googleapis.com")
  private val region   = env("ANKKA_GCS_REGION").getOrElse("auto")
  private val origin   = env("ANKKA_GCS_ORIGIN").getOrElse("https://play.example")
  private val bucket   = env("ANKKA_GCS_BUCKET")
  private val access   = env("ANKKA_GCS_ACCESS_KEY")
  private val secret   = env("ANKKA_GCS_SECRET_KEY")

  private val configured = bucket.isDefined && access.isDefined && secret.isDefined
  private val required   = sys.props.get("ankka.gcs.tests").contains("on")

  override def beforeEach(context: BeforeEach): Unit =
    if !configured then
      if required then
        fail(
          "ankka.gcs.tests=on, and ANKKA_GCS_BUCKET, ANKKA_GCS_ACCESS_KEY or ANKKA_GCS_SECRET_KEY " +
            "is not set: the suite was asked for and cannot run"
        )
      else assume(false, "no Google Cloud Storage bucket is named; skipping")

  private def credentials =
    StaticCredentialsProvider.create(AwsBasicCredentials.create(access.get, secret.get))

  // docs:start gcs-client
  private lazy val s3: S3Client =
    S3Client
      .builder()
      .endpointOverride(URI.create(endpoint)) // ANKKA_S3_ENDPOINT
      .region(Region.of(region))              // ANKKA_S3_REGION
      .forcePathStyle(true)
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .credentialsProvider(credentials) // ANKKA_S3_ACCESS_KEY, ANKKA_S3_SECRET_KEY
      .build()
  // docs:end gcs-client

  override def afterAll(): Unit = if configured then s3.close()

  private def fresh(name: String): String = s"gcs-compatibility/${UUID.randomUUID()}/$name"

  private def put(key: String, body: String): Unit =
    s3.putObject(
      PutObjectRequest.builder().bucket(bucket.get).key(key).build(),
      RequestBody.fromString(body)
    ): Unit

  private def get(key: String, version: Option[String] = None): String =
    val b = GetObjectRequest.builder().bucket(bucket.get).key(key)
    version.foreach(b.versionId)
    s3.getObjectAsBytes(b.build()).asUtf8String()

  /** Every version of one object, newest first, as the XML API's `?versions` lists them. */
  private def versions(key: String): Vector[(String, Boolean)] =
    s3.listObjectVersions(
      ListObjectVersionsRequest.builder().bucket(bucket.get).prefix(key).build()
    ).versions()
      .asScala
      .filter(_.key() == key)
      .map(v => (v.versionId(), v.isLatest.booleanValue()))
      .toVector

  test("a signature made for the region auto is accepted, and an object round-trips") {
    val key = fresh("round-trip.txt")
    put(key, "hello")
    assertEquals(get(key), "hello")
  }

  // features/object-storage-gcs/retention.feature

  // docs:start every-version
  test("an object that is overwritten can be read as it was before") {
    val key = fresh("passport.pdf")
    put(key, "first")
    put(key, "second")
    val listed = versions(key)
    assertEquals(listed.size, 2, listed.toString)
    val noncurrent = listed.collectFirst { case (id, false) => id }.getOrElse(fail(listed.toString))
    assertEquals(get(key), "second")
    assertEquals(get(key, Some(noncurrent)), "first")
  }
  // docs:end every-version

  test("an object that is deleted can be read back as its noncurrent version") {
    val key = fresh("passport.pdf")
    put(key, "kept")
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket.get).key(key).build()): Unit
    val listed = versions(key)
    assert(listed.nonEmpty, "a deleted object left no version")
    assertEquals(get(key, Some(listed.head._1)), "kept")
  }

  // docs:start delete-every-version
  test("deleting every version of an object leaves none listed") {
    val key = fresh("passport.pdf")
    put(key, "first")
    put(key, "second")
    for (id, _) <- versions(key) do
      s3.deleteObject(
        DeleteObjectRequest.builder().bucket(bucket.get).key(key).versionId(id).build()
      ): Unit
    assertEquals(versions(key), Vector.empty)
  }
  // docs:end delete-every-version

  // features/object-storage-gcs/reachable.feature

  private val http = HttpClient.newHttpClient()

  test("a request without a signed URL is refused by every bucket") {
    val key = fresh("passport.pdf")
    put(key, "private")
    val response = http.send(
      HttpRequest.newBuilder(URI.create(s"$endpoint/${bucket.get}/$key")).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assert(Set(401, 403).contains(response.statusCode()), response.statusCode().toString)
    assert(!response.body().contains("private"), response.body())
  }

  private def preflight(url: URI, from: String): HttpResponse[String] =
    http.send(
      HttpRequest
        .newBuilder(url)
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", from)
        .header("Access-Control-Request-Method", "PUT")
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a browser on an origin the descriptor names keeps an object through a signed URL") {
    val key = fresh("selfie.jpg")
    val presigner = S3Presigner
      .builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.of(region))
      .serviceConfiguration(
        software.amazon.awssdk.services.s3.S3Configuration
          .builder()
          .pathStyleAccessEnabled(true)
          .build()
      )
      .credentialsProvider(credentials)
      .build()
    try
      val url = presigner
        .presignPutObject(
          PutObjectPresignRequest
            .builder()
            .signatureDuration(Duration.ofMinutes(5))
            .putObjectRequest(PutObjectRequest.builder().bucket(bucket.get).key(key).build())
            .build()
        )
        .url()
        .toURI
      val allowed = preflight(url, origin)
      assertEquals(allowed.statusCode(), 200, allowed.body())
      assertEquals(allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""), origin)
      val refused = preflight(url, "https://elsewhere.example")
      assertNotEquals(
        refused.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
        "https://elsewhere.example"
      )
      val sent = http.send(
        HttpRequest
          .newBuilder(url)
          .header("Origin", origin)
          .PUT(HttpRequest.BodyPublishers.ofString("a selfie"))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(sent.statusCode(), 200, sent.body())
      assertEquals(get(key), "a selfie")
    finally presigner.close()
  }

  test("a service cannot set its bucket's CORS rule through S3: the platform must (FR-016)") {
    // The reason the platform sets a bucket's rule on both stores: this call works on Garage.
    val e = intercept[S3Exception](
      s3.putBucketCors(
        PutBucketCorsRequest
          .builder()
          .bucket(bucket.get)
          .corsConfiguration(
            CORSConfiguration
              .builder()
              .corsRules(CORSRule.builder().allowedOrigins("*").allowedMethods("GET").build())
              .build()
          )
          .build()
      )
    )
    assert(e.statusCode() >= 400, e.toString)
  }
