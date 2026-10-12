package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.operator.cnpg.PostgresCluster

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/databases/replicas.feature` on k3s (feature 041): a project asks for replicas, and
 * survives losing its primary with no redeploy and, when synchronous, no acknowledged write lost.
 *
 * A project per scenario, as the backups suite has. The primary is lost the hard way: its pod and
 * its volume deleted together with the suite's admin client, as a node and its disk going would.
 * What a service wrote is read back through the service, from inside its own pod.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class ReplicasClusterFeatures extends BackupClusterSteps("../features/databases/replicas.feature"):

  // Three instances a scenario: the node holds no more than one scenario's at a time.
  override protected def removesEachScenario: Boolean = true

  private def cluster(project: String): Option[PostgresCluster] =
    Option(
      k8s
        .resources(classOf[PostgresCluster])
        .inNamespace(namespace(project))
        .withName("ankka-db")
        .get()
    )

  private def primaryOf(project: String): Option[String] =
    cluster(project).flatMap(c => Option(c.getStatus)).flatMap(_.currentPrimary)

  private def ready(project: String): Int =
    cluster(project).flatMap(c => Option(c.getStatus)).fold(0)(_.readyInstances)

  private var primaryBefore: Option[String] = None
  private var lostAt: Long                  = 0L
  private val acknowledged                  = new ConcurrentLinkedQueue[String]()
  private val firstFailure                  = new AtomicLong(0L)
  private val firstSuccessAfter             = new AtomicLong(0L)
  private val cartWriting                   = new AtomicBoolean(false)
  private val cartId                        = "replicas"

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    cartWriting.set(false)
    acknowledged.clear()
    firstFailure.set(0L)
    firstSuccessAfter.set(0L)
    lostAt = 0L

  override def afterEach(context: AfterEach): Unit =
    cartWriting.set(false)
    super.afterEach(context)

  private def askFor(project: String, replicas: Int, synchronous: Boolean = false): Unit =
    val args = Vector("projects", "database", "set", project, "--replicas", replicas.toString) ++
      Option.when(synchronous)("--synchronous")
    ok(ankka(args*)): Unit

  private def awaitInstances(project: String, replicas: Int): Unit =
    waitFor(12.minutes, s"$project's database running ${replicas + 1} instances") {
      cluster(project).exists(c =>
        c.getSpec.instances == replicas + 1 && ready(project) == replicas + 1
      )
    }

  private def base(project: String): Unit =
    ensureProject(project)
    if !isReady(project, serviceOf("base")) then
      deploy(project, serviceOf("base"), descriptor(serviceOf("base")))

  /** Adds an item to the service's cart every half second, noting each one acknowledged. */
  private def writeContinuously(project: String, service: String): Unit =
    cartWriting.set(true)
    val counter = new AtomicLong(0L)
    val thread = new Thread(() =>
      while cartWriting.get() do
        val item = s"item-${counter.incrementAndGet()}"
        val (code, _) =
          try
            cart(
              project,
              service,
              "POST",
              s"/carts/$cartId/items",
              Some(s"""{"productId":"$item","name":"$item","quantity":1}""")
            )
          catch case _: Throwable => (0, "")
        if code == 200 || code == 204 then
          acknowledged.add(item): Unit
          if lostAt > 0 && firstFailure.get() > 0 then
            firstSuccessAfter.compareAndSet(0L, System.currentTimeMillis()): Unit
        else if lostAt > 0 then firstFailure.compareAndSet(0L, System.currentTimeMillis()): Unit
        Thread.sleep(500)
    )
    thread.setDaemon(true)
    thread.start()

  /** The primary's pod and its volume, deleted together. */
  private def losePrimary(project: String): Unit =
    val primary = primaryOf(project).getOrElse(fail(s"$project's database has no primary"))
    primaryBefore = Some(primary)
    lostAt = System.currentTimeMillis()
    k8s.persistentVolumeClaims().inNamespace(namespace(project)).withName(primary).delete(): Unit
    k8s.pods().inNamespace(namespace(project)).withName(primary).withGracePeriod(0).delete(): Unit

  private def onReplica(project: String, sql: String, database: String): String =
    val pod = k8s
      .pods()
      .inNamespace(namespace(project))
      .withLabel("cnpg.io/cluster", "ankka-db")
      .withLabel("cnpg.io/instanceRole", "replica")
      .list()
      .getItems
      .asScala
      .headOption
      .getOrElse(fail(s"$project's database has no replica"))
    val out = new ByteArrayOutputStream
    val watch = k8s
      .pods()
      .inNamespace(namespace(project))
      .withName(pod.getMetadata.getName)
      .inContainer("postgres")
      .writingOutput(out)
      .exec("psql", "-U", "postgres", "-d", database, "-tA", "-c", sql)
    try watch.exitCode().get(60, TimeUnit.SECONDS): Unit
    finally watch.close()
    out.toString(StandardCharsets.UTF_8).trim

  private def held(project: String, service: String): String =
    val (code, body) = cart(project, service, "GET", s"/carts/$cartId")
    assertEquals(code, 200, body)
    body

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a project {string} with a project database") { (project: String) =>
    base(projectOf(project))
  }

  // A precondition and an outcome alike: asking again for what was asked records nothing.
  Given("the project database of {string} runs a primary and {int} replicas") {
    (project: String, replicas: Int) =>
      base(projectOf(project))
      askFor(projectOf(project), replicas)
      awaitInstances(projectOf(project), replicas)
  }

  Given("the project database of {string} is synchronous, with a primary and {int} replicas") {
    (project: String, replicas: Int) =>
      base(projectOf(project))
      askFor(projectOf(project), replicas, synchronous = true)
      awaitInstances(projectOf(project), replicas)
  }

  Given("a deployed service {string} in the project {string} that writes to it") {
    (service: String, project: String) =>
      deploy(projectOf(project), serviceOf(service), descriptor(serviceOf(service)))
      podsBefore = pods(projectOf(project), serviceOf(service)).map(_.getMetadata.getUid).toSet
      writeContinuously(projectOf(project), serviceOf(service))
      waitFor(2.minutes, "a first acknowledged write")(!acknowledged.isEmpty)
  }

  Given("a deployed service {string} in the project {string}") {
    (service: String, project: String) =>
      deploy(projectOf(project), serviceOf(service), descriptor(serviceOf(service)))
  }

  Given("a deployed service {string} in the project {string} that has recorded an event") {
    (service: String, project: String) =>
      deploy(projectOf(project), serviceOf(service), descriptor(serviceOf(service)))
      addItem(projectOf(project), serviceOf(service), cartId, "kept")
  }

  Given("a project {string} that asks for no replicas") { (project: String) =>
    ensureProject(projectOf(project))
    askFor(projectOf(project), 0)
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member asks for {int} replicas of the project database of {string}") {
    (replicas: Int, project: String) =>
      primaryBefore = primaryOf(projectOf(project))
      askFor(projectOf(project), replicas)
  }

  When("a member asks for {int} replica of the project database of {string}") {
    (replicas: Int, project: String) =>
      primaryBefore = primaryOf(projectOf(project))
      askFor(projectOf(project), replicas)
  }

  When("the primary is lost") { () =>
    losePrimary(projectOf("shop"))
  }

  When("a write of {string} is acknowledged") { (service: String) =>
    addItem(projectOf("shop"), serviceOf(service), cartId, "acknowledged")
  }

  When("a member applies a descriptor for the service {string} in the project {string}") {
    (service: String, project: String) =>
      deploy(projectOf(project), serviceOf(service), descriptor(serviceOf(service)))
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the project database of {string} runs a primary and {int} replica") {
    (project: String, replicas: Int) => awaitInstances(projectOf(project), replicas)
  }

  Then("the project database of {string} runs a primary and no replica") { (project: String) =>
    awaitInstances(projectOf(project), 0)
    assertEquals(cluster(projectOf(project)).map(_.getSpec.instances), Some(1))
  }

  Then(
    "the status of the project {string} says how many of them are ready and which is the primary"
  ) { (project: String) =>
    waitFor(2.minutes, "the status naming the primary") {
      projectStatus(projectOf(project)).database.exists(d =>
        d.readyInstances == 3 && d.primary == primaryOf(projectOf(project))
      )
    }
  }

  Then("a replica is promoted with no member doing anything") { () =>
    waitFor(3.minutes, "a replica promoted") {
      primaryOf(projectOf("shop")).exists(p => !primaryBefore.contains(p))
    }
  }

  Then("{string} goes on writing with no instance replaced") { (service: String) =>
    waitFor(3.minutes, "a write acknowledged after the loss") {
      firstFailure.get() == 0L && acknowledged.size > 0 && System
        .currentTimeMillis() - lostAt > 10000 ||
      firstSuccessAfter.get() > 0L
    }
    // SC-006: writes resume within a minute of the loss.
    val resumed = Option(firstSuccessAfter.get()).filter(_ > 0).getOrElse(lostAt)
    assert(resumed - lostAt < 60000, s"writes resumed ${resumed - lostAt} ms after the loss")
    assertEquals(
      pods(projectOf("shop"), serviceOf(service)).map(_.getMetadata.getUid).toSet,
      podsBefore
    )
  }

  Then(
    "the connections of {string} to the lost primary are closed, and {string} connects to the promoted replica on its own"
  ) { (service: String, _: String) =>
    val users = psql(
      projectOf("shop"),
      "select distinct usename from pg_stat_activity where usename is not null"
    )
    assert(users.linesIterator.exists(_.trim == serviceOf(service)), users)
    val count = acknowledged.size
    waitFor(1.minute, "another acknowledged write")(acknowledged.size > count)
  }

  Then("a replica holds the write as well as the primary") { () =>
    val database = serviceOf("wallet")
    val sql      = s"select count(*) from event_journal where persistence_id like '%$cartId'"
    assertEquals(psql(projectOf("shop"), sql, database).trim, "1")
    assertEquals(onReplica(projectOf("shop"), sql, database), "1")
  }

  Then("losing the primary loses no write that was acknowledged") { () =>
    losePrimary(projectOf("shop"))
    waitFor(3.minutes, "a replica promoted") {
      primaryOf(projectOf("shop")).exists(p => !primaryBefore.contains(p))
    }
    waitFor(3.minutes, "the acknowledged write read back") {
      scala.util
        .Try(held(projectOf("shop"), serviceOf("wallet")))
        .toOption
        .exists(_.contains("acknowledged"))
    }
  }

  Then("the primary is the one it had") { () =>
    assertEquals(primaryOf(projectOf("shop")), primaryBefore)
  }

  Then("{string} still holds the event") { (service: String) =>
    assert(held(projectOf("shop"), serviceOf(service)).contains("kept"))
  }
