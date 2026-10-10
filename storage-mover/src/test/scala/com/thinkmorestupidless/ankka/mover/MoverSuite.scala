package com.thinkmorestupidless.ankka.mover

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  HeadObjectRequest,
  PutObjectRequest
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * The storage mover (feature 039, contracts/mover.md) between two real stores: a Garage standing
 * for the service's bucket in Garage, and a second standing for its bucket in Google Cloud Storage.
 * Both speak S3 with a static key, which is all the mover knows of either.
 */
class MoverSuite extends munit.FunSuite:

  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "mover-suite"

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

  /** One running store and the admin calls a test needs to set a bucket up. */
  private final class Garage:
    val container: GenericContainer[?] =
      val c = new GenericContainer(DockerImageName.parse(Image))
      c.withCopyToContainer(Transferable.of(config.getBytes(UTF_8)), "/etc/garage.toml")
      c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
      c.withEnv("GARAGE_ADMIN_TOKEN", Token)
      c.withCommand("/garage", "server", "--single-node")
      c.withExposedPorts(3900, 3903)
      c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
      c

    def start(): Unit = container.start()
    def stop(): Unit  = container.stop()

    def s3Endpoint: URI =
      URI.create(s"http://${container.getHost}:${container.getMappedPort(3900)}")

    private val http = HttpClient.newHttpClient()

    private def admin(path: String, body: String): String =
      val response = http.send(
        HttpRequest
          .newBuilder(
            URI.create(s"http://${container.getHost}:${container.getMappedPort(3903)}$path")
          )
          .header("Authorization", s"Bearer $Token")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assert(
        response.statusCode() / 100 == 2,
        s"$path: ${response.statusCode()} ${response.body()}"
      )
      response.body()

    private def field(json: String, name: String): String =
      s""""$name"\\s*:\\s*"([^"]+)"""".r
        .findFirstMatchIn(json)
        .map(_.group(1))
        .getOrElse(fail(s"no $name in $json"))

    /** A bucket and a key allowed on it, as a `Store`. */
    def bucket(name: String, write: Boolean = true): Store =
      val id     = field(admin("/v2/CreateBucket", s"""{"globalAlias":"$name"}"""), "id")
      val key    = admin("/v2/CreateKey", s"""{"name":"$name"}""")
      val access = field(key, "accessKeyId")
      admin(
        "/v2/AllowBucketKey",
        s"""{"bucketId":"$id","accessKeyId":"$access",""" +
          s""""permissions":{"read":true,"write":$write,"owner":$write}}"""
      ): Unit
      Store(s3Endpoint, "garage", name, access, field(key, "secretAccessKey"))

    /** A second key on an existing bucket, as a `Store`. */
    def key(bucket: String, name: String, write: Boolean): Store =
      val id = field(
        http
          .send(
            HttpRequest
              .newBuilder(
                URI.create(
                  s"http://${container.getHost}:${container.getMappedPort(3903)}" +
                    s"/v2/GetBucketInfo?globalAlias=$bucket"
                )
              )
              .header("Authorization", s"Bearer $Token")
              .build(),
            HttpResponse.BodyHandlers.ofString()
          )
          .body(),
        "id"
      )
      val key    = admin("/v2/CreateKey", s"""{"name":"$name"}""")
      val access = field(key, "accessKeyId")
      admin(
        "/v2/AllowBucketKey",
        s"""{"bucketId":"$id","accessKeyId":"$access",""" +
          s""""permissions":{"read":true,"write":$write,"owner":$write}}"""
      ): Unit
      Store(s3Endpoint, "garage", bucket, access, field(key, "secretAccessKey"))

  private val garage = Garage()
  private val gcs    = Garage()

  override def beforeAll(): Unit =
    garage.start()
    gcs.start()

  override def afterAll(): Unit =
    garage.stop()
    gcs.stop()

  private def put(
      store: Store,
      key: String,
      body: Array[Byte],
      contentType: String = "application/pdf",
      metadata: Map[String, String] = Map.empty
  ): Unit =
    val client = store.client()
    try
      client.putObject(
        PutObjectRequest
          .builder()
          .bucket(store.bucket)
          .key(key)
          .contentType(contentType)
          .metadata(metadata.asJava)
          .build(),
        RequestBody.fromBytes(body)
      ): Unit
    finally client.close()

  private def read(store: Store, key: String): Array[Byte] =
    val client = store.client()
    try
      client
        .getObjectAsBytes(GetObjectRequest.builder().bucket(store.bucket).key(key).build())
        .asByteArray()
    finally client.close()

  private def headOf(store: Store, key: String) =
    val client = store.client()
    try client.headObject(HeadObjectRequest.builder().bucket(store.bucket).key(key).build())
    finally client.close()

  private def bytes(n: Int, seed: Int): Array[Byte] =
    Array.tabulate(n)(i => ((i * 31 + seed) % 251).toByte)

  test("a copy carries every object with its content type, metadata and the hash of its bytes") {
    val source = garage.bucket("casino.kyc1")
    val target = gcs.bucket("t-casino-kyc1")
    put(source, "passport.pdf", bytes(1000, 1), metadata = Map("player" -> "p-17"))
    put(source, "selfies/one.jpg", bytes(5000, 2), contentType = "image/jpeg")
    val report = Mover(source, target).copy()
    assertEquals((report.counted, report.copied, report.failed), (2, 2, None))
    assertEquals(read(target, "passport.pdf").toSeq, bytes(1000, 1).toSeq)
    assertEquals(read(target, "selfies/one.jpg").toSeq, bytes(5000, 2).toSeq)
    val head = headOf(target, "passport.pdf")
    assertEquals(head.contentType(), "application/pdf")
    assertEquals(head.metadata().get("player"), "p-17")
    assertEquals(headOf(target, "selfies/one.jpg").contentType(), "image/jpeg")
    val sha = java.util.HexFormat
      .of()
      .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes(1000, 1)))
    assertEquals(head.metadata().get(Mover.ShaKey), sha)
  }

  test("a second copy uploads nothing, and one after a change uploads only what changed") {
    val source = garage.bucket("casino.kyc2")
    val target = gcs.bucket("t-casino-kyc2")
    put(source, "a.pdf", bytes(100, 1))
    put(source, "b.pdf", bytes(100, 2))
    Mover(source, target).copy(): Unit
    assertEquals(Mover(source, target).copy().copied, 0)
    put(source, "b.pdf", bytes(100, 3))
    val again = Mover(source, target).copy()
    assertEquals((again.counted, again.copied), (2, 1))
    assertEquals(read(target, "b.pdf").toSeq, bytes(100, 3).toSeq)
  }

  test("an object larger than the buffer is hashed and uploaded as a stream") {
    val source = garage.bucket("casino.kyc3")
    val target = gcs.bucket("t-casino-kyc3")
    put(source, "scan.tiff", bytes(300_000, 4), contentType = "image/tiff")
    val report = Mover(source, target, bufferLimit = 1024).copy()
    assertEquals(report.copied, 1)
    assertEquals(read(target, "scan.tiff").toSeq, bytes(300_000, 4).toSeq)
    assertEquals(Mover(source, target, bufferLimit = 1024).copy().copied, 0)
  }

  test("verify copies what was written since the copy, then finds both sides the same") {
    val source = garage.bucket("casino.kyc4")
    val target = gcs.bucket("t-casino-kyc4")
    put(source, "passport.pdf", bytes(400, 5))
    Mover(source, target).copy(): Unit
    // Written while the bulk copy ran, before the write pause: the pause's delta.
    put(source, "selfie.jpg", bytes(700, 6), contentType = "image/jpeg")
    val report = Mover(source, target).verify()
    assertEquals((report.counted, report.copied, report.verified, report.failed), (2, 1, 2, None))
    assertEquals(read(target, "selfie.jpg").toSeq, bytes(700, 6).toSeq)
  }

  test("verify fails naming an object whose bytes differ on the target") {
    val source = garage.bucket("casino.kyc5")
    val target = gcs.bucket("t-casino-kyc5")
    put(source, "passport.pdf", bytes(400, 7))
    Mover(source, target).copy(): Unit
    // Same size and the same recorded hash, other bytes: only reading both sides finds it.
    val recorded = headOf(target, "passport.pdf").metadata().asScala.toMap
    put(target, "passport.pdf", bytes(400, 8), metadata = recorded)
    val run = intercept[MoveRun](Mover(source, target).verify())
    assertEquals(run.exitCode, 1)
    assertEquals(run.report.failed, Some("passport.pdf"))
    assertEquals(run.report.reason, Some("differs"))
  }

  test("verify fails naming an object the target holds and the source does not") {
    val source = garage.bucket("casino.kyc6")
    val target = gcs.bucket("t-casino-kyc6")
    put(source, "passport.pdf", bytes(10, 1))
    put(target, "stray.pdf", bytes(10, 2))
    val run = intercept[MoveRun](Mover(source, target).verify())
    assertEquals(run.report.failed, Some("stray.pdf"))
    assertEquals(run.report.reason, Some("missing on source"))
  }

  test("a source credential that reads and cannot write still copies: the write pause's") {
    val writer = garage.bucket("casino.kyc7")
    put(writer, "passport.pdf", bytes(200, 9))
    val reader = garage.key("casino.kyc7", "casino.kyc7#ro1", write = false)
    val target = gcs.bucket("t-casino-kyc7")
    assertEquals(Mover(reader, target).verify().verified, 1)
  }

  test("a target that refuses the credential is a run that could not proceed") {
    val source = garage.bucket("casino.kyc8")
    put(source, "passport.pdf", bytes(10, 1))
    val target = gcs.bucket("t-casino-kyc8").copy(secretKey = "wrong")
    val run    = intercept[MoveRun](Mover(source, target).copy())
    assertEquals(run.exitCode, 2)
    assertEquals(run.report.reason, Some("refused by target"))
  }

  private def main(args: String*)(env: Map[String, String]): (Int, String, String) =
    val out  = new ByteArrayOutputStream()
    val log  = Files.createTempFile("termination", ".log")
    val code = Main.run(args.toVector, env, new PrintStream(out, true, UTF_8), log)
    (code, out.toString(UTF_8).trim.linesIterator.toVector.last, Files.readString(log))

  private def env(source: Store, target: Store): Map[String, String] =
    def side(prefix: String, s: Store) = Map(
      s"MOVER_${prefix}_ENDPOINT"   -> s.endpoint.toString,
      s"MOVER_${prefix}_REGION"     -> s.region,
      s"MOVER_${prefix}_BUCKET"     -> s.bucket,
      s"MOVER_${prefix}_ACCESS_KEY" -> s.accessKey,
      s"MOVER_${prefix}_SECRET_KEY" -> s.secretKey
    )
    side("SOURCE", source) ++ side("TARGET", target)

  test(
    "the program reports one JSON line, to standard output and the termination log, and exits 0"
  ) {
    val source = garage.bucket("casino.kyc9")
    val target = gcs.bucket("t-casino-kyc9")
    put(source, "passport.pdf", bytes(10, 1))
    val (code, last, log) = main("verify")(env(source, target))
    assertEquals(code, 0)
    assert(
      last.startsWith(
        """{"mode":"verify","counted":1,"copied":1,"verified":1,"failed":null,"reason":null,"""
      ),
      last
    )
    assertEquals(log, last)
  }

  test("a missing variable is a run that could not proceed, naming it") {
    val source          = garage.bucket("casino.kyc10")
    val target          = gcs.bucket("t-casino-kyc10")
    val (code, last, _) = main("copy")(env(source, target) - "MOVER_TARGET_SECRET_KEY")
    assertEquals(code, 2)
    assert(last.contains("\"reason\":\"MOVER_TARGET_SECRET_KEY is not set\""), last)
  }

  test("a mode that is neither copy nor verify is refused") {
    val (code, last, _) = main("delete")(Map.empty)
    assertEquals(code, 2)
    assert(last.contains("mode must be copy or verify"), last)
  }

  test("a report escapes what it quotes") {
    val r = Report("copy", 0, 0, 0, Some("a \"quoted\"\nkey"), None, 0)
    assert(r.json.contains("\"failed\":\"a \\\"quoted\\\"\\nkey\""), r.json)
  }
