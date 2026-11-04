package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{ErasureRequestView, RestoreView}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DivergenceReader,
  ErasureReplayed,
  InstanceTopologies
}
import com.thinkmorestupidless.ankka.operator.PkiStack

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/erasure/restores.feature` on k3s (feature 042): a project's database restored by CNPG
 * to a moment before an erasure, with the keyring untouched (case A), and the keyring's own
 * database restored to such a moment (case B).
 *
 * Each scenario has a project of its own (`brand-<n>`), whose `players` registers 8c1f, is backed
 * up, and has 8c1f erased through the command line after a moment the restore goes back to. What
 * the restore brought back is read in the restored database before anything is switched to it, so a
 * step that finds it gone afterwards proves the replay took it, not the restore that never held it.
 *
 * Case B restores the keyring's database as an installation would by hand, since nothing of the
 * platform restores it: a CNPG cluster `keyring-db-r1` recovered from the keyring's archive in its
 * platform bucket to the moment, and the keyring's Deployment pointed at it. What the restored
 * database held of 8c1f's key is read before the keyring rolls onto it.
 *
 * Off with `-Dankka.cluster.tests=off`.
 */
class ErasureRestoreClusterFeatures
    extends ErasureClusterSteps("../features/erasure/restores.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "the keyring applies the union of both copies of the erasure log and reports the copy that was behind" ->
      "ErasureFeatures, against the keyring's server on loopback",
    "an erasure whose erasure log write failed destroys no subject key" ->
      "ErasureFeatures, with the bucket's copy refusing"
  )

  // A project database a scenario, on one node: the node holds no more than one at a time.
  override protected def removesEachScenario: Boolean       = true
  override protected def keyringBackedUp: Boolean           = true
  override protected def serviceOf(logical: String): String = logical

  private val Id      = "8c1f"
  private val Subject = s"player/$Id"

  /**
   * The shipped reader of what a service says about the erasure log, asked as the control plane's
   * identity through a port-forward to the service's pod, since this JVM is outside the cluster.
   */
  override protected def divergence: Option[DivergenceReader] =
    def through[A](projectId: String, service: String)(
        ask: DivergenceReader => Either[String, A]
    ): Either[String, A] =
      InstanceTopologies.runningPods(k8s, Prefix)(projectId, service).headOption match
        case None           => Left(s"$service has no running instance")
        case Some((pod, _)) =>
          val forward = k8s
            .pods()
            .inNamespace(namespace(projectId))
            .withName(pod)
            .portForward(InstanceTopologies.ObservePort)
          try
            ask(
              DivergenceReader.ObservePort(
                (_, _) => Vector(pod -> "127.0.0.1"),
                Some(controlPlaneTls),
                port = forward.getLocalPort
              )
            )
          finally forward.close()
    Some(
      new DivergenceReader:
        def since(projectId: String, service: String, at: Instant) =
          through(projectId, service)(_.since(projectId, service, at))
        override def erasureReplay(projectId: String, service: String) =
          through(projectId, service)(_.erasureReplay(projectId, service))
    )

  private def brand = projectOf("brand")

  private var moment: Instant                   = Instant.EPOCH
  private var erasure: ErasureRequestView       = null
  private var restoreName: String               = ""
  private var replayed: Option[ErasureReplayed] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    moment = Instant.EPOCH
    erasure = null
    restoreName = ""
    replayed = None

  // ── helpers ───────────────────────────────────────────────────────────────────

  /** `psql` as postgres in a cluster of the project's namespace, its primary. */
  private def sqlIn(cluster: String, sql: String, database: String = "players"): String =
    val pod = k8s
      .pods()
      .inNamespace(namespace(brand))
      .withLabel("cnpg.io/cluster", cluster)
      .withLabel("cnpg.io/instanceRole", "primary")
      .list()
      .getItems
      .asScala
      .headOption
      .getOrElse(fail(s"$cluster has no primary"))
      .getMetadata
      .getName
    val r = PkiStack.exec(
      k3s,
      1.minute,
      "kubectl",
      "exec",
      "-n",
      namespace(brand),
      pod,
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      database,
      "-tA",
      "-v",
      "ON_ERROR_STOP=1",
      "-c",
      sql
    )
    assertEquals(r.getExitCode, 0, r.getStderr)
    r.getStdout.trim

  /** Whether 8c1f's row of the profiles view holds its email and name sealed, and its token. */
  private def profileRow(cluster: String): String =
    sqlIn(
      cluster,
      "select (payload::jsonb->'email') ? 'c', (payload::jsonb->'name') ? 'c', lookup is not null " +
        s"from ankka_view_profiles where payload::jsonb->>'playerId' = '$Id'"
    )

  private def restoreView: RestoreView =
    readFromString[RestoreView](
      ok(ankka("projects", "restores", "get", brand, restoreName, "-o", "json")).out
    )

  private def read(path: String): (Int, String) = players(brand, "players", path)

  private def readyPods(project: String, service: String) =
    pods(project, service).filter(p =>
      Option(p.getStatus.getConditions).toVector
        .flatMap(_.asScala)
        .exists(c => c.getType == "Ready" && c.getStatus == "True")
    )

  private def readySince(pod: io.fabric8.kubernetes.api.model.Pod): Instant =
    Instant.parse(
      pod.getStatus.getConditions.asScala
        .find(_.getType == "Ready")
        .getOrElse(fail(s"${pod.getMetadata.getName} has no Ready condition"))
        .getLastTransitionTime
    )

  private def restoreBeforeTheErasure(): Unit =
    assert(moment != Instant.EPOCH, "the Background noted no moment")
    psql(brand, "select pg_switch_wal()"): Unit
    waitFor(5.minutes, "the project's archive passing the erasure") {
      projectStatus(brand).lines
        .find(_.line == "ankka-db")
        .flatMap(_.lastRestorable)
        .exists(_.isAfter(erasure.destroyedAt.getOrElse(moment)))
    }
    restoreName = readFromString[RestoreView](
      ok(ankka("projects", "restore", brand, moment.toString, "-o", "json")).out
    ).name
    waitFor(15.minutes, s"$restoreName verified")(
      Set("Verified", "InUse").contains(restoreView.phase)
    )
    // What the restore brought back: 8c1f's row as it was before the erasure, sealed, with its token.
    assertEquals(profileRow(restoreName), "t|t|t", "the restore does not hold 8c1f's row as it was")

  private def switchToTheRestore(): Unit =
    ok(ankka("services", "switch", "players", "-p", brand, "--to", restoreName)): Unit

  private def awaitOnTheRestore(): Unit =
    waitFor(8.minutes, "players on the restore") {
      statusOf("players", brand).exists(s =>
        s.databaseCluster.contains(restoreName) && isReady(brand, "players")
      ) && readyPods(brand, "players").nonEmpty
    }

  // ── Background ────────────────────────────────────────────────────────────────

  Given("a project {string} with the service {string}") { (project: String, service: String) =>
    assertEquals((project, service), ("brand", "players"))
    deploy(brand, "players", playersDescriptor("players", "players"))
    awaitBackedUp(brand)
  }

  Given("{string} has been erased in {string}") { (subject: String, project: String) =>
    assertEquals((subject, project), (Subject, "brand"))
    register(brand, "players", Id, "Ada Byron")
    waitFor(2.minutes, "8c1f's profile row and its key") {
      field(read(s"/players/$Id/profile")._2, "email").contains(s"$Id@example.com") &&
      keyringSql(
        s"select count(*) from keyring.subject_key where project = '$brand' and subject = '$Subject'"
      ) == "1"
    }
    // The lookup finds the row by its token before the erasure.
    assert(read(s"/players/by-email/$Id@example.com")._2.contains(Id))
    Thread.sleep(3000)
    moment = Instant.now()
    Thread.sleep(3000)
    erasure = erase(brand, Subject)
  }

  // ── case A: the project's database restored, the keyring untouched ────────────

  Given("the keyring has not been restored") { () =>
    // Every instance runs on the keyring's own database, and 8c1f's key is gone from it.
    for pod <- keyringPods() do
      val env = pod.getSpec.getContainers.asScala
        .find(_.getName == "ankka-keyring")
        .toVector
        .flatMap(_.getEnv.asScala)
        .find(_.getName == "ANKKA_DB_HOST")
        .getOrElse(fail("the keyring is told no database"))
      assertEquals(Option(env.getValueFrom).map(_.getSecretKeyRef.getName), Some("keyring-db-app"))
    assertEquals(
      keyringSql(
        s"select count(*) from keyring.subject_key where project = '$brand' and subject = '$Subject'"
      ),
      "0"
    )
  }

  When("the database of {string} is restored to a point before the erasure") { (project: String) =>
    assertEquals(project, "brand")
    restoreBeforeTheErasure()
  }

  When("{string} is switched to the restored database") { (service: String) =>
    assertEquals(service, "players")
    switchToTheRestore()
  }

  Then(
    "{string} is not ready until it has applied the entries of the erasure log for {string} to its own tables"
  ) { (service: String, project: String) =>
    assertEquals((service, project), ("players", "brand"))
    awaitOnTheRestore()
    waitFor(2.minutes, "players saying when it applied the erasure log on the restore") {
      replayed = divergence.get.erasureReplay(brand, "players").toOption
      replayed.exists(r => r.line == restoreName && r.replayedAt.isDefined)
    }
    val at = replayed.flatMap(_.replayedAt).get
    // Every instance on the restore became ready only once the log was applied there.
    for pod <- readyPods(brand, "players") do
      assert(
        !readySince(pod).isBefore(at.truncatedTo(ChronoUnit.SECONDS)),
        s"${pod.getMetadata.getName} was ready at ${readySince(pod)}, before the log was applied at $at"
      )
  }

  Then(
    "afterwards every row of every view of {string} holds erased in each personal field of {string}"
  ) { (service: String, subject: String) =>
    assertEquals((service, subject), ("players", Subject))
    assertEquals(profileRow(restoreName).split('|').take(2).toVector, Vector("f", "f"))
    val (code, body) = read(s"/players/$Id/profile")
    assertEquals(code, 200, body)
    assertEquals((field(body, "email"), field(body, "name")), (Some("erased"), Some("erased")))
  }

  Then("no lookup token for {string} remains in any row of {string}") {
    (subject: String, service: String) =>
      assertEquals((subject, service), (Subject, "players"))
      assertEquals(
        sqlIn(
          restoreName,
          "select count(*) from ankka_view_profiles where lookup is not null " +
            s"and payload::jsonb->>'playerId' = '$Id'"
        ),
        "0"
      )
      val (code, body) = read(s"/players/by-email/$Id@example.com")
      assertEquals(code, 200, body)
      assert(!body.contains(Id), body)
  }

  Then("the status of the restore says when {string} finished applying the erasure log") {
    (service: String) =>
      assertEquals(service, "players")
      waitFor(1.minute, "the restore's status naming when players applied the log") {
        restoreView.erasureReplayedAt.get("players") == replayed.flatMap(_.replayedAt)
      }
  }

  // ── case A: an entity recovered from the restore ─────────────────────────────

  Given("the database of {string} has been restored to a point before the erasure") {
    (project: String) =>
      assertEquals(project, "brand")
      restoreBeforeTheErasure()
      switchToTheRestore()
      awaitOnTheRestore()
  }

  When("an entity of {string} holding events of {string} is recovered") {
    (service: String, subject: String) =>
      assertEquals((service, subject), ("players", Subject))
      // The restore's journal holds 8c1f's registration, sealed, from before the erasure.
      assert(
        sqlIn(
          restoreName,
          s"select count(*) from event_journal where persistence_id like '%$Id'"
        ).toInt >= 1,
        "the restore holds no event of 8c1f"
      )
      // Every instance on the restore started after the switch, so its first read recovers it.
      ok(ankka("services", "restart", "players", "-p", brand)): Unit
      waitFor(8.minutes, "players restarted on the restore")(isReady(brand, "players"))
  }

  Then("its state holds erased in each personal field of {string}") { (subject: String) =>
    assertEquals(subject, Subject)
    var body = ""
    waitFor(2.minutes, "8c1f read from a recovered entity") {
      val answer = read(s"/players/$Id")
      body = answer._2
      answer._1 == 200
    }
    assertEquals(
      (field(body, "email"), field(body, "name"), field(body, "currency")),
      (Some("erased"), Some("erased"), Some("EUR")),
      body
    )
  }

  // ── case B: the keyring's database restored ───────────────────────────────────

  private val Restored = "keyring-db-r1"

  When("the database of the keyring is restored to a point before the erasure") { () =>
    // A commit after the erasure, archived, so the recovery reaches the moment and passes it.
    val after = Instant.now()
    keyringSql(
      "create table if not exists suite_marker(x int); insert into suite_marker values (1); " +
        "select pg_switch_wal()"
    ): Unit
    waitFor(5.minutes, "the keyring's archive passing the erasure") {
      keyringSql(
        "select coalesce(last_archived_time > timestamptz '" + after + "', false) from pg_stat_archiver"
      ) == "t"
    }
    applyYaml(
      s"""apiVersion: postgresql.cnpg.io/v1
         |kind: Cluster
         |metadata:
         |  name: $Restored
         |  namespace: $KeyringNamespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  instances: 1
         |  storage: { size: 1Gi }
         |  bootstrap:
         |    recovery:
         |      source: origin
         |      database: keyring
         |      owner: keyring
         |      secret: { name: keyring-db-app }
         |      recoveryTarget: { targetTime: "$moment" }
         |  externalClusters:
         |    - name: origin
         |      plugin:
         |        name: barman-cloud.cloudnative-pg.io
         |        parameters: { barmanObjectName: ankka-backups, serverName: keyring-db }
         |""".stripMargin,
      "keyring-restore"
    )
    waitFor(15.minutes, s"$Restored recovered") {
      PkiStack.jsonPath(
        k3s,
        "cluster",
        "-n",
        KeyringNamespace,
        Restored,
        "{.status.readyInstances}"
      ) == "1"
    }
    // What the restore brought back: 8c1f's key, destroyed since.
    assertEquals(
      keyringSql(
        s"select count(*) from keyring.subject_key where project = '$brand' and subject = '$Subject'",
        Restored
      ),
      "1",
      "the keyring's restore does not hold 8c1f's key"
    )
    before = keyringPods().map(_.getMetadata.getName).toSet
    k8s
      .apps()
      .deployments()
      .inNamespace(KeyringNamespace)
      .withName("ankka-keyring")
      .edit { (d: io.fabric8.kubernetes.api.model.apps.Deployment) =>
        val spec = d.getSpec.getTemplate.getSpec
        for container <- spec.getContainers.asScala ++ spec.getInitContainers.asScala do
          container.setEnv(
            container.getEnv.asScala
              .map(e =>
                if e.getName == "ANKKA_DB_HOST" then
                  new io.fabric8.kubernetes.api.model.EnvVar(e.getName, s"$Restored-rw", null)
                else e
              )
              .asJava
          )
        d
      }: Unit
  }

  private var before: Set[String] = Set.empty

  Then("the keyring answers no request for a subject key until it has applied the erasure log") {
    () =>
      waitFor(10.minutes, "two keyring instances on the restored database") {
        val ready = readyKeyringPods()
        ready.size == 2 && ready.toSet.intersect(before).isEmpty &&
        keyringPods().forall(p => !before.contains(p.getMetadata.getName))
      }
      val seq     = erasure.erasureSeq.getOrElse(fail("the erasure has no log sequence"))
      val reports = readyKeyringPods().map(pod => pod -> keyringStatus(pod))
      for (pod, report) <- reports do assert(report.contains("\"finished\":true"), s"$pod: $report")
      // The first to start found the restore behind the log and applied the erasure before ready.
      val applying = reports
        .find((_, r) => "\"applied\":([0-9]+)".r.findFirstMatchIn(r).exists(_.group(1).toInt >= 1))
        .getOrElse(fail(s"no instance applied the log: $reports"))
      val from = "\"from\":([0-9]+)".r
        .findFirstMatchIn(applying._2)
        .map(_.group(1).toLong)
        .getOrElse(fail(applying._2))
      assert(from < seq, s"the instance applied the log from $from, after the erasure's $seq")
      val pod  = k8s.pods().inNamespace(KeyringNamespace).withName(applying._1).get()
      val logs = PkiStack
        .exec(
          k3s,
          1.minute,
          "kubectl",
          "logs",
          "--timestamps",
          "-n",
          KeyringNamespace,
          applying._1,
          "-c",
          "ankka-keyring"
        )
        .getStdout
      val replayedAt = logs.linesIterator
        .find(_.contains("the keyring replayed"))
        .map(l => Instant.parse(l.takeWhile(_ != ' ')))
        .getOrElse(fail(s"${applying._1} logged no replay:\n${logs.takeRight(3000)}"))
      assert(
        !readySince(pod).isBefore(replayedAt.truncatedTo(ChronoUnit.SECONDS)),
        s"${applying._1} was ready at ${readySince(pod)}, before it replayed at $replayedAt"
      )
  }

  Then("afterwards the keyring holds no subject key for {string}") { (subject: String) =>
    assertEquals(subject, Subject)
    assertEquals(
      keyringSql(
        s"select count(*) from keyring.subject_key where project = '$brand' and subject = '$subject'",
        Restored
      ),
      "0"
    )
    assertEquals(
      keyringSql(
        s"select count(*) from keyring.tombstone where project = '$brand' and subject = '$subject'",
        Restored
      ),
      "1"
    )
  }

  Then("the next read of a personal field of {string} by any service of {string} is erased") {
    (subject: String, project: String) =>
      assertEquals((subject, project), (Subject, "brand"))
      // Its instances forget every key they held: the next read asks the restored keyring.
      ok(ankka("services", "restart", "players", "-p", brand)): Unit
      waitFor(8.minutes, "players restarted")(isReady(brand, "players"))
      var entity  = ""
      var profile = ""
      waitFor(2.minutes, "8c1f read again") {
        val (a, b) = (read(s"/players/$Id"), read(s"/players/$Id/profile"))
        entity = a._2
        profile = b._2
        a._1 == 200 && b._1 == 200
      }
      assertEquals(
        (field(entity, "email"), field(entity, "name")),
        (Some("erased"), Some("erased"))
      )
      assertEquals(
        (field(profile, "email"), field(profile, "name")),
        (Some("erased"), Some("erased"))
      )
  }
