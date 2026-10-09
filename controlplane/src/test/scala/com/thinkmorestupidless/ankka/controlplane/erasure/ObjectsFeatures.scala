package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.operator.GarageStore
import com.thinkmorestupidless.ankka.runtime.ServiceIdentity
import com.thinkmorestupidless.ankka.runtime.erasure.{Completion, ObjectStoreClient}
import com.thinkmorestupidless.ankka.sdk.{ErasureHandler, ErasureOutcome, ObjectErasure}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing
}
import com.typesafe.config.ConfigFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `features/erasure/objects.feature` against the installation's store, Garage, which keeps one
 * version of an object: a `kyc` service whose bucket is a Garage bucket of its own, its erasure
 * handler asking the platform to erase the subject's objects, and a `ledger` service with none. A
 * store that keeps every version, and one with a soft-delete window, are spec 039's to provide.
 */
class ObjectsFeatures extends GherkinSuite("../features/erasure/objects.feature") with LogCapturing:

  override val munitTimeout = 4.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    "the erasure request says when the erasure of objects becomes final on an object store with a soft-delete window" ->
      "spec 039's object storage on Google Cloud, the first store with a soft-delete window"
  )

  private val Image = "dxflrs/garage:v2.3.0"
  private val Token = "objects-features"
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
  private var bucket: ObjectStoreClient   = null
  private var settings                    = ConfigFactory.empty()
  private val ring                        = InMemoryKeyring()
  private var kyc: AnkkaTestKit           = null
  private var ledger: AnkkaTestKit        = null

  /** Every run of a handler, with what it answered. */
  private val runs = ConcurrentLinkedQueue[(String, String)]()

  private def handlerOf(service: String): ErasureHandler = ctx =>
    try
      val erased = ctx.objects.erase()
      runs.add(service -> s"erased ${erased.count}"): Unit
      ErasureOutcome.Done(s"erased ${erased.count}", Some(erased))
    catch
      case e: Exception =>
        runs.add(service -> s"refused: ${e.getMessage}"): Unit
        throw e

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
    val store    = GarageStore(s"http://${c.getHost}:${c.getMappedPort(3903)}", Token)
    val made     = store.createBucket("brand.kyc")
    val key      = store.createKey("brand.kyc")
    val endpoint = s"http://${c.getHost}:${c.getMappedPort(3900)}"
    store.allow(made.id, key.accessKeyId)
    bucket = ObjectStoreClient(
      URI.create(endpoint),
      "garage",
      "brand.kyc",
      key.accessKeyId,
      key.secretAccessKey
    )
    // What the platform gives a service that asked for a bucket, as configuration a kit can take.
    settings = ConfigFactory.parseString(
      s"""ankka.erasure.bucket {
         |  endpoint = "$endpoint"
         |  name = "brand.kyc"
         |  access-key = "${key.accessKeyId}"
         |  secret-key = "${key.secretAccessKey}"
         |}""".stripMargin
    )

  override def afterAll(): Unit =
    Seq(kyc, ledger).filter(_ != null).foreach(_.stop())
    if garage != null then garage.stop()

  private def suffix                 = Integer.toHexString(scenarioId.hashCode)
  private def subject(named: String) = s"$named-$suffix"
  private def under(named: String, name: String) =
    s"${ObjectErasure.prefix(subject(named))}$name"
  private def keep(key: String): Unit = bucket.put(key, key.getBytes(UTF_8)): Unit

  private var erasureId = ""

  private def erase(named: String): Unit = erasureId = kyc.erase(subject(named))

  private def completionOf(service: String): Option[Completion] =
    ring.completionsOf(erasureId).filter(_._1 == service).lastOption.map(_._2)

  // ── Background ──

  Given("a service {string} in the project {string} with a bucket") { (_: String, _: String) =>
    if kyc == null then
      kyc = AnkkaTestKit.start(
        Seq.empty,
        serviceIdentity = ServiceIdentity.deployed("brand", "kyc"),
        keyring = Some(ring),
        settings = settings,
        configure = _.withErasureHandler(handlerOf("kyc"))
      )
  }
  Given("{string} has an erasure handler that erases the objects of the data subject") {
    (_: String) => () // registered with the service
  }

  // ── Erasing ──

  Given(
    "{string} has kept the objects {string} and {string} under the subject prefix of {string}"
  ) { (_: String, first: String, second: String, named: String) =>
    keep(under(named, first))
    keep(under(named, second))
  }
  Given("{string} has kept the object {string} outside any subject prefix") {
    (_: String, name: String) => keep(s"$suffix/$name")
  }
  Given("the object store of the installation keeps every version of an object") { () =>
    assume(false, "Garage keeps one version; a versioned store is spec 039's to provide")
  }
  Given("the object store of the installation keeps one version of an object")(() => ())
  When("{string} is erased in {string}") { (named: String, _: String) =>
    val (_, refusing) = (kyc, ledger)
    if refusing != null then erasureId = refusing.erase(subject(named)) else erase(named)
  }
  Then(
    "the bucket of {string} holds no version of {string} or of {string} under the subject prefix of {string}"
  ) { (_: String, first: String, second: String, named: String) =>
    assertEquals(bucket.get(under(named, first)), None)
    assertEquals(bucket.get(under(named, second)), None)
    assertEquals(bucket.list(ObjectErasure.prefix(subject(named))), Vector.empty)
  }
  Then("the bucket of {string} still holds {string}") { (_: String, name: String) =>
    assert(bucket.get(s"$suffix/$name").isDefined, name)
  }
  Then("the completion of {string} records that {int} objects were erased") {
    (service: String, count: Int) =>
      val handled = completionOf(service).flatMap(_.handler)
      assertEquals(handled.flatMap(_.objectsErased), Some(count.toLong), handled.toString)
  }

  // ── A late write ──

  Given("{string} has been erased in {string}")((named: String, _: String) => erase(named))
  Given("{string} has since kept the object {string} under the subject prefix of {string}") {
    (_: String, name: String, named: String) => keep(under(named, name))
  }
  private var before = 0
  When("the erasure request for {string} is applied again") { (_: String) =>
    before = runs.asScala.count(_._1 == "kyc")
    ring.reapply("brand", erasureId)
    kyc.eventually("the handler ran again")(
      Option.when(runs.asScala.count(_._1 == "kyc") > before)(())
    )
  }
  Then("the erasure handler of {string} ran again") { (service: String) =>
    assert(runs.asScala.count(_._1 == service) > before)
  }
  Then("the bucket of {string} holds no {string} under the subject prefix of {string}") {
    (_: String, name: String, named: String) => assertEquals(bucket.get(under(named, name)), None)
  }

  // ── No bucket ──

  Given("a service {string} in the project {string} whose descriptor asks for no bucket") {
    (_: String, _: String) =>
      ledger = AnkkaTestKit.start(
        Seq.empty,
        serviceIdentity = ServiceIdentity.deployed("brand", "ledger"),
        keyring = Some(ring),
        configure = _.withErasureHandler(handlerOf("ledger"))
      )
  }
  Then("the erasure handler of {string} is refused") { (service: String) =>
    assert(
      runs.asScala.exists((s, what) => s == service && what.startsWith("refused")),
      runs.toString
    )
  }
  Then("the refusal names the missing bucket") { () =>
    assert(
      runs.asScala.exists((s, what) => s == "ledger" && what.contains("no bucket")),
      runs.toString
    )
  }
  Then("the completion of {string} says so") { (service: String) =>
    val handled = ring
      .awaitCompletions(erasureId, 1)
      .filter(_._1 == service)
      .lastOption
      .flatMap(_._2.handler)
      .getOrElse(fail(s"no completion of $service"))
    assert(!handled.ok && handled.detail.contains("no bucket"), handled.toString)
  }

  // ── Not this store's ──

  Given(
    "the object store of the installation keeps every version of an object behind a soft-delete window"
  )(() => ())
  Given("{string} has kept objects under the subject prefix of {string}") { (_: String, _: String) =>
    ()
  }
  Then(
    "the erasure request says that the erasure of objects becomes final when the soft-delete window has passed"
  )(() => ())
