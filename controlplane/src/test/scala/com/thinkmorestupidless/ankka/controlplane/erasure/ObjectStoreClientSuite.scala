package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.deploy.ErasureLogBucket
import com.thinkmorestupidless.ankka.operator.GarageStore
import com.thinkmorestupidless.ankka.runtime.erasure.{KeyringApi, ObjectErasures, ObjectStoreClient}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The runtime's own S3 calls — signed by hand, no S3 library — against the installation's store,
 * Garage, configured and started as `kustomization/components/garage` starts it: an object kept,
 * read, kept again only when absent, listed under a prefix and deleted; a data subject's objects
 * erased and nothing else; the erasure log's bucket copy appended once per erasure and read back.
 */
class ObjectStoreClientSuite extends munit.FunSuite with LogCapturing:

  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "object-store-client-suite"

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
  private var store: GarageStore          = null
  private var endpoint: URI               = null

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
    store = GarageStore(s"http://${c.getHost}:${c.getMappedPort(3903)}", Token)
    endpoint = URI.create(s"http://${c.getHost}:${c.getMappedPort(3900)}")

  override def afterAll(): Unit = if garage != null then garage.stop()

  /** A bucket of its own and a client holding the only key allowed on it. */
  private def bucket(name: String): ObjectStoreClient =
    val made = store.createBucket(name)
    val key  = store.createKey(name)
    store.allow(made.id, key.accessKeyId)
    ObjectStoreClient(endpoint, "garage", name, key.accessKeyId, key.secretAccessKey)

  private def text(bytes: Option[Array[Byte]]): Option[String] = bytes.map(String(_, UTF_8))

  test("an object is kept and read back, and a missing one is none") {
    val client = bucket("brand.kyc")
    assert(client.put("subjects/player/8c1f/passport.pdf", "scan".getBytes(UTF_8)))
    assertEquals(text(client.get("subjects/player/8c1f/passport.pdf")), Some("scan"))
    assertEquals(client.get("subjects/player/8c1f/missing.pdf"), None)
  }

  test("the store overwrites a write asked to be made only when absent: nothing may rely on it") {
    val client = bucket("brand.overwrites")
    client.put("k", "first".getBytes(UTF_8)): Unit
    client.put("k", "second".getBytes(UTF_8), ifAbsent = true): Unit
    // If this ever reads "first", the store honours If-None-Match and the log's read-before-write
    // can go; until then it is what keeps the copy append-only.
    assertEquals(text(client.get("k")), Some("second"))
  }

  test("a data subject's objects are erased, every one under its prefix and nothing else") {
    val client = bucket("brand.documents")
    Seq("passport.pdf", "selfie.jpg", "notes/2026.txt").foreach(name =>
      client.put(s"subjects/player/8c1f/$name", name.getBytes(UTF_8)): Unit
    )
    client.put("subjects/player/9d2e/passport.pdf", "kept".getBytes(UTF_8)): Unit
    client.put("reports/march.pdf", "kept".getBytes(UTF_8)): Unit
    val erased = ObjectErasures.over(client, "player/8c1f").erase()
    assertEquals(erased.count, 3L)
    assertEquals(client.list("subjects/player/8c1f/"), Vector.empty)
    assertEquals(text(client.get("subjects/player/9d2e/passport.pdf")), Some("kept"))
    assertEquals(text(client.get("reports/march.pdf")), Some("kept"))
    assertEquals(ObjectErasures.over(client, "player/8c1f").erase().count, 0L, "nothing left")
  }

  test("the erasure log's bucket copy keeps one object per erasure, never a different one") {
    val log   = ErasureLogBucket.over(bucket("platform.erasures"))
    val entry = KeyringApi.LogEntry("e1", "brand", "player/8c1f", 1, 1760000000000L)
    log.append(entry)
    log.append(entry)
    log.append(KeyringApi.LogEntry("e2", "brand", "player/9d2e", 2, 1760000001000L))
    assertEquals(log.entries().sortBy(_.sequence).map(_.erasureId), Vector("e1", "e2"))
    intercept[IllegalStateException](log.append(entry.copy(subject = "player/other")))
  }
