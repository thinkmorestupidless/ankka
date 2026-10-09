package com.thinkmorestupidless.ankka.operator

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{
  RequestChecksumCalculation,
  ResponseChecksumValidation
}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest, S3Exception}

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * `GarageStore` against the store's real image (feature 034): a bucket named as the platform names
 * one, a key issued and allowed, an object kept and read with an S3 client nobody here wrote, and
 * another key refused by that bucket.
 *
 * The store is the one the installation runs, configured as `kustomization/components/garage`
 * configures it and started as it starts it, `server --single-node`, so this suite is also what
 * holds that a single node needs no layout step.
 */
class GarageStoreSuite extends munit.FunSuite:

  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "garage-store-suite"

  private val config =
    """metadata_dir = "/var/lib/garage/meta"
      |data_dir = "/var/lib/garage/data"
      |db_engine = "lmdb"
      |replication_factor = 1
      |rpc_bind_addr = "[::]:3901"
      |
      |[s3_api]
      |s3_region = "garage"
      |api_bind_addr = "[::]:3900"
      |
      |[admin]
      |api_bind_addr = "[::]:3903"
      |""".stripMargin

  private var garage: GenericContainer[?] = null
  private val requests                    = new ConcurrentLinkedQueue[URI]()
  private var store: GarageStore          = null
  private var s3Endpoint: URI             = null

  override def beforeAll(): Unit =
    val c = new GenericContainer(DockerImageName.parse(Image))
    c.withCopyToContainer(Transferable.of(config.getBytes(UTF_8)), "/etc/garage.toml")
    c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
    c.withEnv("GARAGE_ADMIN_TOKEN", Token)
    c.withCommand("/garage", "server", "--single-node")
    c.withExposedPorts(3900, 3903)
    c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
    c.start()
    garage = c
    store = GarageStore(
      s"http://${c.getHost}:${c.getMappedPort(3903)}",
      Token,
      onRequest = uri => requests.add(uri): Unit
    )
    s3Endpoint = URI.create(s"http://${c.getHost}:${c.getMappedPort(3900)}")

  override def afterAll(): Unit = if garage != null then garage.stop()

  // docs:start s3-client
  private def s3(key: IssuedKey, region: String = "garage"): S3Client =
    S3Client
      .builder()
      .endpointOverride(s3Endpoint)
      .region(Region.of(region))
      .forcePathStyle(true)
      // The SDK's default since 2.30 sends a trailing checksum in an aws-chunked body, which the
      // store refuses as an invalid payload signature. A checksum only where an operation needs one
      // is what every S3-compatible store accepts.
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(key.accessKeyId, key.secretAccessKey)
        )
      )
      .build()
  // docs:end s3-client

  private def put(client: S3Client, bucket: String, name: String, body: String): Unit =
    client.putObject(
      PutObjectRequest.builder().bucket(bucket).key(name).build(),
      RequestBody.fromString(body)
    ): Unit

  private def get(client: S3Client, bucket: String, name: String): String =
    client
      .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(name).build())
      .asUtf8String()

  test("a bucket that was never made is none") {
    assertEquals(store.bucket("never.made"), None)
  }

  test("a bucket named as the platform names one is made, found, and says when it was made") {
    val before  = Instant.now().minusSeconds(60)
    val created = store.createBucket("shop.reports")
    val found   = store.bucket("shop.reports").getOrElse(fail("the bucket was not found"))
    assertEquals(found.id, created.id)
    assert(found.created.isAfter(before), found.created.toString)
    assertEquals(found.allowedKeys, Set.empty[String])
  }

  test("a key is issued with its secret, and found by its exact name and no other") {
    val key = store.createKey("shop.ledger")
    assert(key.secretAccessKey.nonEmpty)
    store.createKey("shop.ledger-archive"): Unit
    assertEquals(store.keysNamed("shop.ledger"), Vector(key.accessKeyId))
    assertEquals(store.keysNamed("shop.ledg"), Vector.empty[String])
  }

  test("a key allowed on its bucket keeps and reads an object there, path-style") {
    val bucket = store.createBucket("shop.exports")
    val key    = store.createKey("shop.exports")
    store.allow(bucket.id, key.accessKeyId)
    assertEquals(store.bucket("shop.exports").map(_.allowedKeys), Some(Set(key.accessKeyId)))
    val client = s3(key)
    put(client, "shop.exports", "march.pdf", "a report")
    assertEquals(get(client, "shop.exports", "march.pdf"), "a report")
  }

  test("allowing a key that is allowed changes nothing") {
    val bucket = store.createBucket("shop.twice")
    val key    = store.createKey("shop.twice")
    store.allow(bucket.id, key.accessKeyId)
    store.allow(bucket.id, key.accessKeyId)
    assertEquals(store.bucket("shop.twice").map(_.allowedKeys), Some(Set(key.accessKeyId)))
  }

  test("a key is refused by a bucket it is not allowed on, by the store") {
    val mine   = store.createBucket("bank.mine")
    val theirs = store.createBucket("bank.theirs")
    val key    = store.createKey("bank.mine")
    store.allow(mine.id, key.accessKeyId)
    val other = store.createKey("bank.theirs")
    store.allow(theirs.id, other.accessKeyId)
    put(s3(other), "bank.theirs", "secret.txt", "theirs")
    val refused = intercept[S3Exception](get(s3(key), "bank.theirs", "secret.txt"))
    assertEquals(refused.statusCode(), 403)
  }

  test("a client that signs for another region is refused") {
    val bucket = store.createBucket("shop.region")
    val key    = store.createKey("shop.region")
    store.allow(bucket.id, key.accessKeyId)
    val refused = intercept[S3Exception](put(s3(key, "us-east-1"), "shop.region", "a", "b"))
    assertEquals(refused.awsErrorDetails().errorCode(), "AuthorizationHeaderMalformed")
  }

  test("a deleted key is refused") {
    val bucket = store.createBucket("shop.deleted")
    val key    = store.createKey("shop.deleted")
    store.allow(bucket.id, key.accessKeyId)
    put(s3(key), "shop.deleted", "a", "b")
    store.deleteKey(key.accessKeyId)
    assertEquals(store.keysNamed("shop.deleted"), Vector.empty[String])
    val refused = intercept[S3Exception](get(s3(key), "shop.deleted", "a"))
    assertEquals(refused.statusCode(), 403)
  }

  test("a presigned URL reads an object without the credential") {
    val bucket = store.createBucket("shop.presigned")
    val key    = store.createKey("shop.presigned")
    store.allow(bucket.id, key.accessKeyId)
    put(s3(key), "shop.presigned", "hello.txt", "hello")
    val presigner = software.amazon.awssdk.services.s3.presigner.S3Presigner
      .builder()
      .endpointOverride(s3Endpoint)
      .region(Region.of("garage"))
      .serviceConfiguration(
        software.amazon.awssdk.services.s3.S3Configuration
          .builder()
          .pathStyleAccessEnabled(true)
          .build()
      )
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(key.accessKeyId, key.secretAccessKey)
        )
      )
      .build()
    val url = presigner
      .presignGetObject(
        software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
          .builder()
          .signatureDuration(Duration.ofMinutes(5))
          .getObjectRequest(
            GetObjectRequest.builder().bucket("shop.presigned").key("hello.txt").build()
          )
          .build()
      )
      .url()
    val response = java.net.http.HttpClient
      .newHttpClient()
      .send(
        java.net.http.HttpRequest.newBuilder(url.toURI).build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode(), 200)
    assertEquals(response.body(), "hello")
  }

  private def preflight(bucket: String, origin: String): java.net.http.HttpResponse[String] =
    java.net.http.HttpClient
      .newHttpClient()
      .send(
        java.net.http.HttpRequest
          .newBuilder(s3Endpoint.resolve(s"/$bucket/upload.pdf"))
          .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
          .header("Origin", origin)
          .header("Access-Control-Request-Method", "PUT")
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )

  private def admits(origin: String, response: java.net.http.HttpResponse[String]): Boolean =
    response.statusCode() == 200 &&
      response.headers().firstValue("Access-Control-Allow-Origin").orElse("") == origin

  // Feature 039, research S1: the platform sets a bucket's CORS rules, not the service.
  test("CORS rules set on a bucket admit the named origin's preflight and no other, and clear") {
    val bucket = store.createBucket("casino.cors")
    store.setCors(bucket.id, Seq("https://play.example"))
    assert(admits("https://play.example", preflight("casino.cors", "https://play.example")))
    assert(
      !admits("https://elsewhere.example", preflight("casino.cors", "https://elsewhere.example"))
    )
    store.setCors(bucket.id, Nil)
    assert(!admits("https://play.example", preflight("casino.cors", "https://play.example")))
  }

  // Feature 039, research S1: a move's write pause is a key that reads and cannot write.
  test("a key allowed to read and not write reads an object and is refused a write") {
    val bucket = store.createBucket("casino.paused")
    val writer = store.createKey("casino.paused")
    store.allow(bucket.id, writer.accessKeyId)
    put(s3(writer), "casino.paused", "passport.pdf", "a passport")
    val reader = store.createKey("casino.paused#ro1")
    store.allow(bucket.id, reader.accessKeyId, write = false)
    assertEquals(get(s3(reader), "casino.paused", "passport.pdf"), "a passport")
    val refused = intercept[S3Exception](put(s3(reader), "casino.paused", "proof.pdf", "proof"))
    assertEquals(refused.statusCode(), 403)
  }

  // Feature 039, research S1: an old key ends by the store's own clock.
  test("a key whose expiry has passed is reported expired and refused") {
    val bucket = store.createBucket("casino.expiry")
    val key    = store.createKey("casino.expiry")
    store.allow(bucket.id, key.accessKeyId)
    put(s3(key), "casino.expiry", "a", "b")
    assertEquals(store.keyInfo(key.accessKeyId).map(_.expired), Some(false))
    store.expire(key.accessKeyId, Instant.now().minusSeconds(60))
    assertEquals(store.keyInfo(key.accessKeyId).map(_.expired), Some(true))
    val refused = intercept[S3Exception](get(s3(key), "casino.expiry", "a"))
    assertEquals(refused.statusCode(), 403)
  }

  test("a key's information names it and says whether it has expired, and a lost key is none") {
    val key  = store.createKey("casino.info#2")
    val info = store.keyInfo(key.accessKeyId).getOrElse(fail("the key was not found"))
    assertEquals(info, KeyInfo(key.accessKeyId, "casino.info#2", expired = false))
    assertEquals(store.keyInfo("GK000000000000000000000000"), None)
  }

  test("a store nothing answers at is unavailable") {
    val nowhere = GarageStore("http://127.0.0.1:1", Token)
    intercept[ObjectStoreUnavailable](nowhere.bucket("shop.reports"))
  }

  test("a store that refuses the token is not unavailable: it said no") {
    val wrong = GarageStore(s"http://${garage.getHost}:${garage.getMappedPort(3903)}", "wrong")
    val e     = intercept[RuntimeException](wrong.bucket("shop.reports"))
    assert(!e.isInstanceOf[ObjectStoreUnavailable], e.toString)
    assert(e.getMessage.contains("403"), e.getMessage)
  }

  test("no request the client made asked the store for a key's secret") {
    val made = requests.asScala.toVector
    assert(made.nonEmpty)
    assert(!made.exists(_.toString.contains("showSecretKey")), made.mkString("\n"))
  }
