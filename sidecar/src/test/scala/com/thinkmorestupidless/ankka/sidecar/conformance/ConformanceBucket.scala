package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.operator.GarageStore
import com.thinkmorestupidless.ankka.runtime.erasure.ObjectStoreClient
import com.typesafe.config.{Config, ConfigFactory}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The reference service's bucket: one Garage, as the installation runs it, started once for the
 * suite. Every target is given it as the platform gives a service its bucket, so an erasure handler
 * in any language erases a data subject's objects from it through the platform.
 */
object ConformanceBucket:
  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "conformance-bucket"
  private val Name  = "conformance.reference"

  private val garageConfig =
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

  private final case class Started(
      container: GenericContainer[?],
      endpoint: String,
      accessKey: String,
      secretKey: String
  )

  // Started on first use and again after a stop: a target stops it when it stops, and the suite runs
  // more than once in one JVM, so a second run must not be handed the first run's stopped container.
  private var current: Option[Started] = None

  private def started: Started = synchronized {
    current.filter(_.container.isRunning).getOrElse {
      val fresh = start()
      current = Some(fresh)
      fresh
    }
  }

  private def start(): Started =
    val c = new GenericContainer(DockerImageName.parse(Image))
    c.withCopyToContainer(Transferable.of(garageConfig.getBytes(UTF_8)), "/etc/garage.toml")
    c.withEnv("GARAGE_RPC_SECRET", "0" * 64)
    c.withEnv("GARAGE_ADMIN_TOKEN", Token)
    c.withCommand("/garage", "server", "--single-node")
    c.withExposedPorts(3900, 3903)
    c.waitingFor(Wait.forHttp("/health").forPort(3903).forStatusCode(200))
    c.start()
    val store = GarageStore(s"http://${c.getHost}:${c.getMappedPort(3903)}", Token)
    val made  = store.createBucket(Name)
    val key   = store.createKey(Name)
    store.allow(made.id, key.accessKeyId)
    Started(
      c,
      s"http://${c.getHost}:${c.getMappedPort(3900)}",
      key.accessKeyId,
      key.secretAccessKey
    )

  /** The bucket as configuration, what a kit is started with. */
  def settings: Config =
    ConfigFactory.parseString(
      s"""ankka.erasure.bucket {
         |  endpoint = "${started.endpoint}"
         |  name = "$Name"
         |  access-key = "${started.accessKey}"
         |  secret-key = "${started.secretKey}"
         |}""".stripMargin
    )

  /** The bucket as a test reads and writes it. */
  def client: ObjectStoreClient =
    ObjectStoreClient(
      URI.create(started.endpoint),
      "garage",
      Name,
      started.accessKey,
      started.secretKey
    )

  def stop(): Unit = synchronized {
    current.filter(_.container.isRunning).foreach(_.container.stop())
    current = None
  }
