package com.thinkmorestupidless.ankka.testkit.storage

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider, forAgent}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  SqlFragment,
  TimerRuntime
}
import com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage
import com.thinkmorestupidless.ankka.sdk.{BucketExposure, ObjectStorage}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  LogCapturing,
  StorageBackendChoice
}
import com.typesafe.config.{Config, ConfigFactory}

import java.net.{ServerSocket, URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import StorageCodecs.given

/**
 * The steps every in-process file of `features/storage-client` shares (feature 055), over the
 * fixture service in `StorageComponents`: each scenario starts its services with the test kit,
 * keeps and reads through the service's own handlers, and reads the store the kit holds. One suite
 * per file, as the broker features are, so each file's `ranElsewhere` names only its own scenarios.
 */
abstract class StorageFeatures(file: String) extends GherkinSuite(file) with LogCapturing:

  override val munitTimeout: Duration = 3.minutes

  /** One service the scenario started: its kit and the extensions a step reaches. */
  final case class Started(kit: AnkkaTestKit, timers: TimerRuntime, model: TestModelProvider)

  /** What a handler was told over HTTP. */
  final case class Answer(status: Int, contentType: String, body: String)

  protected var services: Map[String, Started]                      = Map.empty
  protected var answer: Option[Answer]                              = None
  protected var refusal: Option[CommandError]                       = None
  protected var startFailure: Option[Throwable]                     = None
  protected var kept: Map[(String, Option[String], String), String] = Map.empty
  protected var lastService: Option[String]                         = None
  protected var directories: Vector[Path]                           = Vector.empty

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    services = Map.empty
    answer = None
    refusal = None
    startFailure = None
    kept = Map.empty
    lastService = None

  override def afterEach(context: AfterEach): Unit =
    try services.values.foreach(_.kit.stop())
    finally
      services = Map.empty
      super.afterEach(context)

  override def afterAll(): Unit =
    directories.foreach(deleteTree)
    super.afterAll()

  // ── starting services ──────────────────────────────────────────────────────

  protected val components: Seq[ComponentDescriptor] =
    Seq(
      Listing.descriptor,
      KeepingWorkflow.descriptor,
      KeepRequests.descriptor,
      KeepingConsumer.descriptor,
      KeepingTimedAction.descriptor,
      CaptionAgent.descriptor
    ) ++ AgentRuntime.descriptors

  protected def start(
      name: String,
      storage: StorageBackendChoice = StorageBackendChoice.Memory(),
      settings: Config = ConfigFactory.empty()
  ): Started =
    val timers = TimerRuntime(pollInterval = 200.millis)
    val model  = TestModelProvider()
    val kit    = AnkkaTestKit.start(
      components,
      Seq(
        HttpServer.at("127.0.0.1", 0)(clients => PhotosEndpoint(clients)),
        ProjectionRuntime(),
        timers,
        AgentRuntime.withDefaultModel(model)
      ),
      storage = storage,
      settings = settings
    )
    val started = Started(kit, timers, model)
    services = services.updated(name, started)
    lastService = Some(name)
    started

  protected def service(name: String): Started =
    lastService = Some(name)
    services.getOrElse(name, fail(s"no service '$name' was started"))

  /** The service a step that names none means: the one the scenario last spoke of. */
  protected def current: String = lastService.getOrElse(fail("no service was started"))

  protected def freshDirectory(): Path =
    val dir = Files.createTempDirectory("ankka-storage-features")
    directories = directories :+ dir
    dir

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val walk = Files.walk(root)
      try walk.iterator.asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p): Unit)
      finally walk.close()

  /** A loopback port nothing listens on. */
  protected def closedPort(): Int =
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  // ── talking to a service's endpoint ────────────────────────────────────────

  private def base(name: String): String =
    service(name).kit.service.boundAddresses.headOption.getOrElse(fail(s"'$name' serves no HTTP"))

  private def enc(s: String): String = URLEncoder.encode(s, UTF_8)

  protected def query(params: (String, Option[String])*): String =
    params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }.mkString("?", "&", "")

  protected def send(
      name: String,
      method: String,
      path: String,
      body: Option[String] = None
  ): Answer =
    val builder = HttpRequest
      .newBuilder(URI.create(base(name) + path))
      .timeout(java.time.Duration.ofSeconds(60))
    val request = body match
      case Some(b) =>
        builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(b))
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody())
    val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    val told     = Answer(
      response.statusCode,
      response.headers.firstValue("Content-Type").orElse(""),
      response.body
    )
    answer = Some(told)
    told

  protected def keepThroughEndpoint(
      name: String,
      objectName: String,
      contentType: String = "text/plain",
      bucket: Option[String] = None,
      route: String = "/storage/objects"
  ): Answer =
    val text = s"the object $objectName"
    val told = send(
      name,
      "POST",
      route,
      Some(writeToString(KeepObject(bucket, objectName, contentType, text)))
    )
    if told.status < 300 then kept = kept.updated((name, bucket, objectName), text)
    told

  protected def readThroughEndpoint(
      name: String,
      objectName: String,
      bucket: Option[String] = None
  ): Answer =
    send(name, "GET", "/storage/objects" + query("bucket" -> bucket, "name" -> Some(objectName)))

  protected def keptText(name: String, objectName: String, bucket: Option[String] = None): String =
    kept.getOrElse((name, bucket, objectName), fail(s"'$name' kept nothing under '$objectName'"))

  protected def told: Answer = answer.getOrElse(fail("no handler was told anything"))

  protected def assertOk(a: Answer): Unit =
    assert(a.status >= 200 && a.status < 300, s"the handler failed: ${a.status} ${a.body}")

  // ── the same keep from every kind of handler ───────────────────────────────

  private def request(objectName: String) =
    KeepObject(None, objectName, "text/plain", s"the object $objectName")

  private def waitForKept(name: String, objectName: String): Unit =
    service(name).kit.eventually(s"'$objectName' was kept") {
      service(name).kit.storage.default.stat(objectName)
    }: Unit
    kept = kept.updated((name, None, objectName), s"the object $objectName")

  private def keepFromWorkflowStep(name: String, objectName: String): Unit =
    val client = service(name).kit.componentClient
    client
      .forWorkflow(EntityId(s"keep-$scenarioId"))
      .call(KeepingWorkflow.start)
      .invoke(request(objectName)): Unit
    waitForKept(name, objectName)

  private def keepFromConsumer(name: String, objectName: String): Unit =
    service(name).kit.componentClient
      .forEventSourcedEntity(EntityId(s"requests-$scenarioId"))
      .call(KeepRequests.request)
      .invoke(request(objectName)): Unit
    waitForKept(name, objectName)

  private def keepFromTimedAction(name: String, objectName: String): Unit =
    service(name).timers.timerScheduler
      .createSingleTimer(
        s"keep-$scenarioId",
        100.millis,
        KeepingTimedAction.keepNow.deferred(request(objectName))
      )
    waitForKept(name, objectName)

  private def keepFromAgent(name: String, objectName: String): Unit =
    val started = service(name)
    started.model
      .expectToolCall("keep_caption", Json.obj("name" -> Json.str(objectName)))
      .expectText("kept")
    started.kit.componentClient
      .forAgent(SessionId(s"captions-$scenarioId"))
      .call(CaptionAgent.ask)
      .invoke(s"Keep a caption as $objectName"): Unit
    service(name).kit.eventually(s"'$objectName' was kept")(
      started.kit.storage.default.stat(objectName)
    ): Unit
    kept = kept.updated((name, None, objectName), s"a caption for $objectName")

  // ── steps shared by every file ─────────────────────────────────────────────

  Given("a deployed service {string} with a bucket") { (name: String) =>
    start(name): Unit
  }

  Given("a deployed service {string} with no bucket") { (name: String) =>
    start(name, StorageBackendChoice.Memory(unnamed = false)): Unit
  }

  // Not asserted here: a scenario that expects the keep to fail says so in its own Then.
  When("a handler of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepThroughEndpoint(name, objectName): Unit
  }

  When("an endpoint of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepThroughEndpoint(name, objectName): Unit
  }

  When("a workflow step of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepFromWorkflowStep(name, objectName)
  }

  When("a consumer of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepFromConsumer(name, objectName)
  }

  When("a timed action of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepFromTimedAction(name, objectName)
  }

  When("an agent of {string} keeps the object {string} through the storage client") {
    (name: String, objectName: String) => keepFromAgent(name, objectName)
  }

  Given("a handler of {string} has kept the object {string} through the storage client") {
    (name: String, objectName: String) => assertOk(keepThroughEndpoint(name, objectName))
  }

  Given("a handler of {string} has kept the object {string}") { (name: String, objectName: String) =>
    assertOk(keepThroughEndpoint(name, objectName))
  }

  When("a handler of {string} keeps the object {string}") { (name: String, objectName: String) =>
    keepThroughEndpoint(name, objectName): Unit
  }

  Then("a handler of {string} reads the object {string} back through the storage client") {
    (name: String, objectName: String) =>
      val read = readThroughEndpoint(name, objectName)
      assertOk(read)
      assertEquals(read.body, keptText(name, objectName))
  }

  When("a handler of {string} reads the object {string} through the storage client") {
    (name: String, objectName: String) => readThroughEndpoint(name, objectName): Unit
  }

  When("a handler of {string} reads the object {string}") { (name: String, objectName: String) =>
    readThroughEndpoint(name, objectName): Unit
  }

  Then("a handler that reads the object {string} is told that there is no such object") {
    (objectName: String) =>
      val read = readThroughEndpoint(current, objectName)
      assertEquals(read.status, 404, read.body)
  }

  Then("the handler is told that there is no such object") { () =>
    assertEquals(told.status, 404, told.body)
  }

  Then("what the handler did fails") { () =>
    assert(told.status >= 400, s"the handler did not fail: ${told.status} ${told.body}")
  }

  Then("the failure says that the service has no bucket and names {string}") { (word: String) =>
    assertEquals(told.status, 503, told.body)
    assert(told.body.contains("has no bucket") && told.body.contains(word), told.body)
  }

  // ── reading the store the kit holds ────────────────────────────────────────

  protected def storageOf(name: String): ObjectStorage = service(name).kit.storage

  protected def memoryStoreOf(name: String): MemoryObjectStorage = storageOf(name) match
    case memory: MemoryObjectStorage => memory
    case other => fail(s"'$name' keeps its objects in $other, not the kit's store in memory")

  /** Every journal record's payload the service's database holds, as text. */
  protected def journalPayloads(name: String): Vector[String] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = service(name).kit.service.system
    Await.result(
      Database().query(
        SqlFragment.raw("SELECT encode(event_payload, 'escape') FROM event_journal")
      )(
        _.get(0, classOf[String])
      ),
      30.seconds
    )

  protected def readJson[A](text: String)(using
      com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[A]
  ): A =
    readFromString[A](text)

  protected def publicBucket(name: String, bucket: String): Unit =
    start(name, StorageBackendChoice.Memory(buckets = Map(bucket -> BucketExposure.Public))): Unit
