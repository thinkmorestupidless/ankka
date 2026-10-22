package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * A Garage for the conformance suite's storage cases (feature 055): the image and start the
 * `garage` component uses, an unnamed bucket and a public `photos`, one key on both. The runtime's
 * `GarageContainer` is in its test sources, which the sidecar does not see; this is the part of it
 * the suite needs.
 */
final class ConformanceGarage private (container: GenericContainer[?]):
  import ConformanceGarage.*

  private val s3    = s"http://${container.getHost}:${container.getMappedPort(3900)}"
  private val web   = s"http://${container.getHost}:${container.getMappedPort(3902)}"
  private val admin = s"http://${container.getHost}:${container.getMappedPort(3903)}"
  private val http  = HttpClient.newHttpClient()

  private val unnamedId = field(post("/v2/CreateBucket", s"""{"globalAlias":"$Unnamed"}"""), "id")
  private val photosId  = field(post("/v2/CreateBucket", s"""{"globalAlias":"$Photos"}"""), "id")
  private val keyBody   = post("/v2/CreateKey", """{"name":"conformance"}""")
  private val accessKey = field(keyBody, "accessKeyId")
  private val secretKey = field(keyBody, "secretAccessKey")
  Seq(unnamedId, photosId).foreach { id =>
    post(
      "/v2/AllowBucketKey",
      s"""{"bucketId":"$id","accessKeyId":"$accessKey","permissions":{"read":true,"write":true,"owner":true}}"""
    )
  }

  /** The variables a deployed service with these two buckets is given, for one kit's service. */
  def choice: StorageBackendChoice =
    StorageBackendChoice.FromEnvironment(
      Map(
        "ANKKA_S3_ENDPOINT"          -> s3,
        "ANKKA_S3_REGION"            -> "garage",
        "ANKKA_S3_BUCKET"            -> Unnamed,
        "ANKKA_S3_ACCESS_KEY"        -> accessKey,
        "ANKKA_S3_SECRET_KEY"        -> secretKey,
        "ANKKA_S3_BUCKET_PHOTOS"     -> Photos,
        "ANKKA_S3_EXPOSURE_PHOTOS"   -> "public",
        "ANKKA_S3_PUBLIC_URL_PHOTOS" -> s"$web/$Photos"
      )
    )

  def stop(): Unit = container.stop()

  private def post(path: String, json: String): String =
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(admin + path))
        .header("Authorization", s"Bearer $Token")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if response.statusCode / 100 != 2 then
      throw IllegalStateException(s"garage admin $path: ${response.statusCode} ${response.body}")
    response.body

object ConformanceGarage:

  private val Image   = "dxflrs/garage:v2.3.0"
  private val Token   = "conformance-suite"
  private val Unnamed = "conformance"
  private val Photos  = "conformance-photos"

  private val Config =
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
      |[s3_web]
      |bind_addr = "[::]:3902"
      |root_domain = ".web.ankka.invalid"
      |index = "index.html"
      |
      |[admin]
      |api_bind_addr = "[::]:3903"
      |""".stripMargin

  def start(): ConformanceGarage =
    val c = new GenericContainer(DockerImageName.parse(Image))
    c.withCopyToContainer(Transferable.of(Config.getBytes(UTF_8)), "/etc/garage.toml")
    c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
    c.withEnv("GARAGE_ADMIN_TOKEN", Token)
    c.withCommand("/garage", "server", "--single-node")
    c.withExposedPorts(3900, 3902, 3903)
    c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
    c.start()
    new ConformanceGarage(c)

  private def field(json: String, name: String): String =
    s""""$name"\\s*:\\s*"([^"]+)"""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .getOrElse(throw IllegalStateException(s"no $name in $json"))
