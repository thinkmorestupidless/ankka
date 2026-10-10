package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{ProjectStatus, RestoreView}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.operator.cnpg.PostgresCluster

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/databases/restoring.feature` on k3s (feature 041): an owner restores a project to a
 * moment and switches one service to it.
 *
 * One project for every scenario, `shop-restoring`, with the background's two services deployed
 * once: a restore is a cluster of its own, so scenarios never share one, and every service is
 * switched back to the project database before a scenario begins. What a cart holds is read through
 * the cart itself, from inside its own pod, so "holds A and not B" is the service's own answer,
 * after its rolling update, on whichever cluster it is on.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class RestoringClusterFeatures
    extends BackupClusterSteps("../features/databases/restoring.feature"):

  override protected def projectOf(logical: String): String = s"$logical-restoring"
  override protected def serviceOf(logical: String): String = logical

  private def shop = projectOf("shop")

  private var restoreName: String                        = ""
  private var moment: Instant                            = Instant.EPOCH
  private var liveBefore: Map[String, String]            = Map.empty
  private var refusal: Run                               = Run(0, "", "")
  private var cartId: String                             = ""
  private var podsBeforeSwitch: Map[String, Set[String]] = Map.empty

  private lazy val bob = identity.token("bob", Some("bob@example.test"), expiresIn = 3.hours)

  private def as(token: String, args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    if !munitIgnore then
      restoreName = ""
      refusal = Run(0, "", "")
      cartId = s"cart-$scenario"
      // Every service back on the project database, rolled and ready, before a scenario begins.
      for
        service <- Vector("wallet", "rewards")
        if statusOf(service, shop).exists(_.databaseCluster.isDefined)
      do
        ok(ankka("services", "switch", service, "-p", shop, "--to", "ankka-db")): Unit
        awaitOn(service, "ankka-db")

  // ── helpers ───────────────────────────────────────────────────────────────

  private def status: ProjectStatus = projectStatus(shop)

  private def restoreOf(name: String): RestoreView =
    readFromString[RestoreView](
      ok(ankka("projects", "restores", "get", shop, name, "-o", "json")).out
    )

  private def items(service: String): String =
    val (code, body) = cart(shop, service, "GET", s"/carts/$cartId")
    assertEquals(code, 200, body)
    body

  /** Writes A to both carts, notes the moment, writes B, and waits for the archive to pass it. */
  private def writeAroundAMoment(): Unit =
    for service <- Vector("wallet", "rewards") do addItem(shop, service, cartId, "A")
    Thread.sleep(3000)
    moment = Instant.now()
    Thread.sleep(3000)
    for service <- Vector("wallet", "rewards") do addItem(shop, service, cartId, "B")
    psql(shop, "select pg_switch_wal()"): Unit
    waitFor(5.minutes, "the archive passing the moment") {
      status.lines.find(_.line == "ankka-db").flatMap(_.lastRestorable).exists(_.isAfter(moment))
    }

  /** The two carts the feature names; the project's other service is the suite's own. */
  private val Carts = Set("wallet", "rewards")

  /** This scenario's cart's events in a service's project database. */
  private def cartEvents(service: String): Long =
    psql(
      shop,
      s"select count(*) from event_journal where persistence_id like '%$cartId'",
      database = service
    ).trim.toLong

  private def liveCounts(): Map[String, String] =
    Vector("wallet", "rewards").map { service =>
      service -> psql(
        shop,
        "select count(*), max(seq_nr) from event_journal",
        database = service
      ).trim
    }.toMap

  private def restoreToMoment(): Unit =
    writeAroundAMoment()
    liveBefore = liveCounts()
    restoreName = readFromString[RestoreView](
      ok(ankka("projects", "restore", shop, moment.toString, "-o", "json")).out
    ).name

  private def awaitVerified(): RestoreView =
    waitFor(15.minutes, s"$restoreName being verified") {
      Set("Verified", "InUse").contains(restoreOf(restoreName).phase)
    }
    restoreOf(restoreName)

  private def completedRestore(): RestoreView =
    restoreToMoment()
    awaitVerified()

  /** The service's pods all run on `cluster`, and its rolling update is done. */
  private def awaitOn(service: String, cluster: String): Unit =
    waitFor(8.minutes, s"$service on $cluster") {
      val running = pods(shop, service)
      val hosts = running.map { pod =>
        pod.getSpec.getContainers.asScala
          .find(_.getName == service)
          .flatMap(_.getEnv.asScala.find(_.getName == "ANKKA_DB_HOST"))
          .map(_.getValue)
          .getOrElse("ankka-db-rw")
      }
      running.nonEmpty && hosts.forall(_ == s"$cluster-rw") && isReady(shop, service)
    }

  private def switched(service: String, to: String): Unit =
    podsBeforeSwitch = Map(service -> pods(shop, service).map(_.getMetadata.getUid).toSet)
    ok(ankka("services", "switch", service, "-p", shop, "--to", to)): Unit
    awaitOn(service, to)

  /** Who is connected to a cluster's primary, by role. */
  private def connectedTo(cluster: String): Set[String] =
    val pod = k8s
      .pods()
      .inNamespace(namespace(shop))
      .withLabel("cnpg.io/cluster", cluster)
      .withLabel("cnpg.io/instanceRole", "primary")
      .list()
      .getItems
      .asScala
      .headOption
      .getOrElse(fail(s"$cluster has no primary"))
    val out = new ByteArrayOutputStream
    val watch = k8s
      .pods()
      .inNamespace(namespace(shop))
      .withName(pod.getMetadata.getName)
      .inContainer("postgres")
      .writingOutput(out)
      .exec(
        "psql",
        "-U",
        "postgres",
        "-tA",
        "-c",
        "select distinct usename from pg_stat_activity where usename is not null"
      )
    try watch.exitCode().get(60, java.util.concurrent.TimeUnit.SECONDS): Unit
    finally watch.close()
    out.toString(StandardCharsets.UTF_8).linesIterator.map(_.trim).filter(_.nonEmpty).toSet

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("an owner of the organization {string} is in") { (_: String) =>
    // The suite's caller created the organization, and is its owner.
    assert(
      ok(ankka("organizations", "list")).out.linesIterator.exists(l =>
        l.contains("acme") && l.contains("owner")
      )
    )
  }

  Given("a completed restore of {string}")((_: String) => completedRestore(): Unit)

  Given("{string} switched to a restore of {string}") { (service: String, _: String) =>
    completedRestore(): Unit
    switched(service, restoreName)
  }

  Given("the project database {string} left holds an event {string} recorded before the switch") {
    (service: String, _: String) =>
      // A, and B after the moment, both in the cluster it left.
      assertEquals(cartEvents(service), 2L)
  }

  Given("a member of the organization {string} who is not an owner") { (_: String) =>
    val _ = as(bob, "whoami")
    if !ok(ankka("organizations", "members", "list", "acme")).out.contains("bob@example.test") then
      ok(ankka("organizations", "members", "add", "acme", "--email", "bob@example.test")): Unit
    waitFor(1.minute, "bob being a member") {
      as(bob, "projects", "get", shop).code == 0
    }
  }

  Given("a restore of {string} that is in progress") { (_: String) =>
    restoreToMoment()
    assertEquals(restoreOf(restoreName).phase, "Restoring")
  }

  /**
   * Only the two carts on the project: the scenarios that say where "every service" is mean these
   * two, and the project, shared by every scenario, can hold the `base` an earlier one deployed.
   */
  private def onlyTheCarts(): Unit =
    val base = serviceOf("base")
    if ankka("services", "get", base, "-p", shop).code == 0 then
      ok(ankka("services", "delete", base, "-p", shop)): Unit

  Given("{string} switched to a restore of {string} and {string} not") {
    (service: String, _: String, _: String) =>
      onlyTheCarts()
      completedRestore(): Unit
      switched(service, restoreName)
  }

  Given("{string} and {string} both switched to a restore of {string}") {
    (first: String, second: String, _: String) =>
      onlyTheCarts()
      completedRestore(): Unit
      switched(first, restoreName)
      switched(second, restoreName)
  }

  Given("a restore of {string} that no service was switched to") { (_: String) =>
    completedRestore(): Unit
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("the owner restores {string} to a moment inside its retention window") { (_: String) =>
    restoreToMoment()
  }

  When("the owner restores {string} to a moment before its retention window") { (_: String) =>
    val earliest =
      status.lines
        .find(_.line == "ankka-db")
        .flatMap(_.firstRestorable)
        .getOrElse(fail("no window"))
    refusal = ankka("projects", "restore", shop, earliest.minusSeconds(86400).toString)
  }

  When("the owner restores {string} to a moment in the future") { (_: String) =>
    refusal = ankka("projects", "restore", shop, Instant.now().plusSeconds(86400).toString)
  }

  When("the owner reads the restore") { () =>
    restoreOf(restoreName): Unit
  }

  When("the owner switches {string} to the restore") { (service: String) =>
    podsBeforeSwitch =
      Vector("wallet", "rewards").map(s => s -> pods(shop, s).map(_.getMetadata.getUid).toSet).toMap
    ok(ankka("services", "switch", service, "-p", shop, "--to", restoreName)): Unit
  }

  When("the owner switches {string} back to the project database it left") { (service: String) =>
    ok(ankka("services", "switch", service, "-p", shop, "--to", "ankka-db")): Unit
  }

  When("the member restores {string} to a moment") { (_: String) =>
    refusal = as(bob, "projects", "restore", shop, Instant.now().minusSeconds(60).toString)
  }

  When("the member switches {string} to a restore of {string}") { (service: String, _: String) =>
    refusal = as(bob, "services", "switch", service, "-p", shop, "--to", "ankka-db-r000000000000")
  }

  When("the owner restores {string} to another moment") { (_: String) =>
    refusal = ankka("projects", "restore", shop, moment.toString)
  }

  When("the project database {string} is on next takes a base backup") { (_: String) =>
    waitFor(10.minutes, s"$restoreName taking a base backup") {
      status.lines.find(_.line == restoreName).exists(_.lastBaseBackup.isDefined)
    }
  }

  When("the owner reads the status of the project {string}")((_: String) => status: Unit)

  // ── Then ──────────────────────────────────────────────────────────────────

  Then(
    "a restore of {string} is made beside the project database of {string}, from the latest base backup before that moment and the archive up to it"
  ) { (_: String, _: String) =>
    val restore = awaitVerified()
    assert(
      k8s
        .resources(classOf[PostgresCluster])
        .inNamespace(namespace(shop))
        .withName(restoreName)
        .get() != null
    )
    // A, and not B: one event in each cart's journal where the project database has two.
    for service <- Vector("wallet", "rewards") do
      val held = restore.services.find(_.name == service).getOrElse(fail(s"no $service"))
      assert(held.present, held.toString)
      assertEquals(
        held.journalRows + 1,
        liveBefore(service).takeWhile(_ != '|').toLong,
        held.toString
      )
  }

  Then("the project database of {string} is unchanged") { (_: String) =>
    assertEquals(liveCounts(), liveBefore)
  }

  Then("the owner is refused") { () =>
    assertNotEquals(refusal.code, 0, refusal.all)
  }

  Then("the refusal names the earliest and the latest moments {string} can be restored to") {
    (_: String) => assert(refusal.all.contains("can be restored between"), refusal.all)
  }

  Then("the restore reports the moment it reached") { () =>
    val reached = restoreOf(restoreName).reachedAt.getOrElse(fail("no moment reached"))
    assert(!reached.isAfter(moment), s"$reached is after $moment")
  }

  Then("for each service of {string} the restore reports that its database is present") {
    (_: String) =>
      val restore = restoreOf(restoreName)
      for service <- Vector("wallet", "rewards") do
        assert(restore.services.exists(v => v.name == service && v.present), restore.toString)
  }

  Then(
    "for each service of {string} the restore reports the number of rows in its journal, its states, its read positions and its timers"
  ) { (_: String) =>
    for v <- restoreOf(restoreName).services if Carts(v.name) do
      assert(v.journalRows > 0, v.toString)
      assert(v.stateRows >= 0 && v.offsetRows >= 0 && v.timerRows >= 0, v.toString)
  }

  Then(
    "for each service of {string} the restore reports the highest sequence number its journal holds"
  ) { (_: String) =>
    for v <- restoreOf(restoreName).services if Carts(v.name) do
      assert(v.highestSequence > 0, v.toString)
  }

  Then("the switch starts a rolling update of {string}") { (service: String) =>
    waitFor(3.minutes, s"$service rolling") {
      pods(shop, service).exists(p => !podsBeforeSwitch(service).contains(p.getMetadata.getUid))
    }
  }

  Then("{string} is on the restore when that rolling update completes") { (service: String) =>
    awaitOn(service, restoreName)
    // The service's own answer: A, and not B, which the cluster it left still holds.
    val held = items(service)
    assert(held.contains("\"A\"") && !held.contains("\"B\""), held)
    assert(connectedTo(restoreName).contains(service), connectedTo(restoreName).toString)
  }

  Then("{string} is on the project database it was on") { (service: String) =>
    assertEquals(pods(shop, service).map(_.getMetadata.getUid).toSet, podsBeforeSwitch(service))
    val held = items(service)
    assert(held.contains("\"A\"") && held.contains("\"B\""), held)
  }

  Then("the history of {string} shows the switch of {string} by the owner") {
    (_: String, service: String) =>
      val history = ok(ankka("services", "history", service, "-p", shop)).out
      assert(
        history.linesIterator.exists(l => l.contains("switched") && l.contains(restoreName)),
        history
      )
  }

  Then("the project database {string} left is kept and listed on {string}") {
    (_: String, _: String) =>
      assert(status.clusters.exists(_.name == "ankka-db"), status.clusters.toString)
      assert(
        k8s
          .resources(classOf[PostgresCluster])
          .inNamespace(namespace(shop))
          .withName("ankka-db")
          .get() != null
      )
  }

  Then("{string} is on that project database when the rolling update the switch starts completes") {
    (service: String) => awaitOn(service, "ankka-db")
  }

  Then("{string} still holds the event") { (service: String) =>
    val held = items(service)
    assert(held.contains("\"B\""), held)
  }

  Then("the member is refused") { () =>
    assertNotEquals(refusal.code, 0, refusal.all)
    assert(refusal.all.contains("owner"), refusal.all)
  }

  Then("the refusal says that a restore of {string} is in progress") { (_: String) =>
    assert(refusal.all.contains("in progress"), refusal.all)
  }

  Then(
    "the restore is archived as a line of history of its own, beside the line of history of the project database {string} left"
  ) { (_: String) =>
    val lines = status.lines.map(_.line).toSet
    assert(lines.contains(restoreName) && lines.contains("ankka-db"), lines.toString)
  }

  Then(
    "every earlier line of history of {string} can still be restored within its retention window"
  ) { (_: String) =>
    assert(
      status.lines.find(_.line == "ankka-db").exists(_.firstRestorable.isDefined),
      status.toString
    )
  }

  Then("the status names the project database each service is on") { () =>
    val clusters = status.clusters.map(c => c.name -> c.services.toSet).toMap
    assertEquals(clusters.get(restoreName), Some(Set("rewards")))
    assertEquals(clusters.get("ankka-db"), Some(Set("wallet")))
  }

  Then("the status says that the services of {string} are on two project databases") { (_: String) =>
    assertEquals(status.clusters.count(_.services.nonEmpty), 2)
  }

  Then(
    "the status lists the project database both left as left, with the time the last service left it"
  ) { () =>
    val left = status.clusters.find(_.name == "ankka-db").getOrElse(fail("not listed"))
    assertEquals(left.phase, "left")
    assert(left.leftAt.isDefined, left.toString)
  }

  Then("the platform never removes it") { () =>
    Thread.sleep(15000)
    assert(
      k8s
        .resources(classOf[PostgresCluster])
        .inNamespace(namespace(shop))
        .withName("ankka-db")
        .get() != null
    )
  }

  Then("the status lists the restore with its age") { () =>
    val listed = status.clusters.find(_.name == restoreName).getOrElse(fail("not listed"))
    assert(listed.since.isDefined, listed.toString)
  }

  Then("the platform does not remove it") { () =>
    Thread.sleep(15000)
    assert(
      k8s
        .resources(classOf[PostgresCluster])
        .inNamespace(namespace(shop))
        .withName(restoreName)
        .get() != null
    )
  }

  Then("the documentation says how a platform administrator removes it by hand") { () =>
    val page = Files.readString(repoRoot.resolve("docs/operate/recovery.md"))
    assert(
      page.contains("kubectl -n ankka-<project> delete cluster"),
      "no removal by hand in the docs"
    )
  }
