package com.thinkmorestupidless.ankka.runtime.storage

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The installation's object store in a container: the image, configuration and start the `garage`
 * component uses, with a web listener on 3902 as feature 055 adds. Buckets and keys are made
 * through the admin API with the JDK's client, as the operator's `GarageStore` makes them;
 * `runtime` cannot see the operator.
 */
final class GarageContainer private (container: GenericContainer[?]):
  import GarageContainer.*

  val s3: URI       = URI.create(s"http://${container.getHost}:${container.getMappedPort(3900)}")
  val web: URI      = URI.create(s"http://${container.getHost}:${container.getMappedPort(3902)}")
  private val admin = s"http://${container.getHost}:${container.getMappedPort(3903)}"
  private val http  = HttpClient.newHttpClient()

  /** A bucket and its id. */
  def bucket(name: String): String =
    field(post("/v2/CreateBucket", s"""{"globalAlias":"$name"}"""), "id")

  /** A key with read, write and owner on `bucketIds`. */
  def key(name: String, bucketIds: String*): SigV4.Credentials =
    val body        = post("/v2/CreateKey", s"""{"name":"$name"}""")
    val credentials = SigV4.Credentials(field(body, "accessKeyId"), field(body, "secretAccessKey"))
    bucketIds.foreach { id =>
      post(
        "/v2/AllowBucketKey",
        s"""{"bucketId":"$id","accessKeyId":"${credentials.accessKey}","permissions":{"read":true,"write":true,"owner":true}}"""
      )
    }
    credentials

  /** Turns the web listener on for a bucket. */
  def website(bucketId: String, enabled: Boolean): Unit =
    post(
      s"/v2/UpdateBucket?id=$bucketId",
      s"""{"websiteAccess":{"enabled":$enabled,"indexDocument":"index.html"}}"""
    ): Unit

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
      throw new IllegalStateException(
        s"garage admin $path: ${response.statusCode} ${response.body}"
      )
    response.body

object GarageContainer:

  val Region        = "garage"
  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "runtime-storage-suite"

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

  def start(): GarageContainer =
    val c = new GenericContainer(DockerImageName.parse(Image))
    c.withCopyToContainer(Transferable.of(Config.getBytes(UTF_8)), "/etc/garage.toml")
    c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
    c.withEnv("GARAGE_ADMIN_TOKEN", Token)
    c.withCommand("/garage", "server", "--single-node")
    c.withExposedPorts(3900, 3902, 3903)
    c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
    c.start()
    new GarageContainer(c)

  private def field(json: String, name: String): String =
    s""""$name"\\s*:\\s*"([^"]+)"""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .getOrElse(throw new IllegalStateException(s"no $name in $json"))
