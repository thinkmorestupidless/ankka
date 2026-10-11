package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.RehearsalView
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.operator.cnpg.PostgresCluster
import io.fabric8.kubernetes.api.model.authorization.v1.{
  ResourceAttributesBuilder,
  SelfSubjectAccessReviewBuilder
}

import scala.concurrent.duration.DurationInt

/**
 * `features/databases/rehearsals.feature` on k3s (feature 041): a member rehearses a restore into
 * the project's rehearsal namespace; it is checked, timed and removed, and the operator may remove
 * that database and no other, which the API server is asked with the operator's own token.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class RehearsalsClusterFeatures
    extends BackupClusterSteps("../features/databases/rehearsals.feature"):

  // A project database a scenario, on one node: the node holds no more than one scenario's at a time.
  override protected def removesEachScenario: Boolean = true

  /**
   * A rehearsal not healthy in three minutes fails; one that ended goes a minute after it began.
   */
  override protected def operatorSettings =
    val base = super.operatorSettings
    base.copy(backups = base.backups.copy(rehearsalTtl = 1.minute, restoreTimeout = 3.minutes))

  private def shop = projectOf("shop")

  /**
   * Refuses every delete of a rehearsal's database while it exists, as an API server refusing the
   * operator would: an admission policy, which binds the suite's admin client as much as anyone.
   */
  private val Hold = "ankka-test-hold-rehearsals"

  private def holdDeletes(): Unit =
    val yaml =
      s"""apiVersion: admissionregistration.k8s.io/v1
         |kind: ValidatingAdmissionPolicy
         |metadata:
         |  name: $Hold
         |spec:
         |  failurePolicy: Fail
         |  matchConstraints:
         |    resourceRules:
         |      - apiGroups: ["postgresql.cnpg.io"]
         |        apiVersions: ["*"]
         |        resources: ["clusters"]
         |        operations: ["DELETE"]
         |  validations:
         |    - expression: "!oldObject.metadata.namespace.endsWith('-rehearsal')"
         |      message: "the suite holds every rehearsal's database"
         |---
         |apiVersion: admissionregistration.k8s.io/v1
         |kind: ValidatingAdmissionPolicyBinding
         |metadata:
         |  name: $Hold
         |spec:
         |  policyName: $Hold
         |  validationActions: ["Deny"]
         |""".stripMargin
    k8s
      .load(java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .serverSideApply(): Unit

  private def releaseDeletes(): Unit =
    k8s
      .genericKubernetesResources(
        "admissionregistration.k8s.io/v1",
        "ValidatingAdmissionPolicyBinding"
      )
      .withName(Hold)
      .delete(): Unit
    k8s
      .genericKubernetesResources("admissionregistration.k8s.io/v1", "ValidatingAdmissionPolicy")
      .withName(Hold)
      .delete(): Unit

  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore then scala.util.Try(releaseDeletes()): Unit
    super.afterEach(context)
  private def rehearsalNamespace = s"${namespace(shop)}-rehearsal"

  private var latest: Option[RehearsalView] = None
  private var answers: Map[String, Boolean] = Map.empty
  private var databaseBefore: String        = ""
  private var linesBefore: Vector[String]   = Vector.empty

  private def rehearsals: Vector[RehearsalView] =
    readFromString[Vector[RehearsalView]](
      ok(ankka("projects", "rehearsals", shop, "-o", "json")).out
    )

  private def rehearse(): RehearsalView =
    readFromString[RehearsalView](ok(ankka("projects", "rehearse", shop, "-o", "json")).out)

  private def awaitEnded(name: String): RehearsalView =
    waitFor(20.minutes, s"rehearsal $name ending") {
      rehearsals.find(_.name == name).exists(_.outcome != "Running")
    }
    rehearsals.find(_.name == name).get

  private def clusterIn(ns: String, name: String) =
    Option(k8s.resources(classOf[PostgresCluster]).inNamespace(ns).withName(name).get())

  /** Whether the operator's own token may delete a database cluster in `ns`. */
  private def operatorMayDelete(ns: String): Boolean =
    val minted = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      "ankka-operator",
      "-n",
      "ankka-operator",
      "--duration=10m"
    )
    assertEquals(minted.getExitCode, 0, minted.getStderr)
    val token = minted.getStdout.trim
    val asOperator = new io.fabric8.kubernetes.client.KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder(k8s.getConfiguration)
          .withOauthToken(token)
          .withClientCertData(null)
          .withClientKeyData(null)
          .build()
      )
      .build()
    try
      asOperator
        .authorization()
        .v1()
        .selfSubjectAccessReview()
        .create(
          new SelfSubjectAccessReviewBuilder()
            .withSpec(
              new io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewSpecBuilder()
                .withResourceAttributes(
                  new ResourceAttributesBuilder()
                    .withGroup("postgresql.cnpg.io")
                    .withResource("clusters")
                    .withVerb("delete")
                    .withNamespace(ns)
                    .build()
                )
                .build()
            )
            .build()
        )
        .getStatus
        .getAllowed
    finally asOperator.close()

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a member of the organization {string} is in")((_: String) => ())

  Given("a rehearsal of {string} that {word}") { (_: String, ended: String) =>
    val started = rehearse()
    if ended == "failed" then
      // Without the credential it reads the backups with, the restore never becomes healthy, and
      // the operator, which writes a credential once, does not write it again.
      waitFor(5.minutes, "the rehearsal's cluster")(
        clusterIn(rehearsalNamespace, started.name).isDefined
      )
      k8s.secrets().inNamespace(rehearsalNamespace).withName("ankka-db-backups").delete(): Unit
    latest = Some(awaitEnded(started.name))
    if ended == "failed" then assertEquals(latest.map(_.outcome), Some("Failed"), latest.toString)
  }

  Given("a deployed service {string} in the project {string} that has recorded an event") {
    (service: String, project: String) =>
      deploy(projectOf(project), serviceOf(service), descriptor(serviceOf(service)))
      addItem(projectOf(project), serviceOf(service), "rehearsed", "kept")
      podsBefore = pods(projectOf(project), serviceOf(service)).map(_.getMetadata.getUid).toSet
  }

  Given(
    "a project database made for a rehearsal of {string} that the platform failed to remove when the rehearsal ended"
  ) { (_: String) =>
    holdDeletes()
    latest = Some(awaitEnded(rehearse().name))
    assert(clusterIn(rehearsalNamespace, latest.get.name).isDefined, "the hold kept it")
  }

  // A rehearsal namespace as the control plane makes one, with the RoleBinding whose grant is read:
  // each scenario has a project of its own, and this one has rehearsed nothing.
  Given("the operator") { () =>
    ensureProject(shop)
    projectorOf.getOrElse(fail("no projector")).ensureRehearsalNamespace(shop)
    deployedProjects += shop
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("the member rehearses a restore of {string} to a moment") { (_: String) =>
    latest = Some(rehearse())
  }

  When("the member lists the rehearsals of {string}")((_: String) => ())

  When("the member sets {string} to rehearse a restore every day") { (_: String) =>
    ok(ankka("projects", "database", "set", shop, "--rehearse", "daily")): Unit
  }

  When("a rehearsal of {string} runs") { (_: String) =>
    databaseBefore = psql(shop, "select count(*) from pg_stat_database").trim
    linesBefore = projectStatus(shop).lines.map(_.line)
    latest = Some(awaitEnded(rehearse().name))
  }

  When("its time to live passes") { () =>
    // The suite's operator runs with a time to live of a minute, long since passed; the hold goes,
    // and the next pass's sweep removes what it held.
    releaseDeletes()
  }

  When("what the operator may do in the cluster is read") { () =>
    answers = Map(
      "rehearsal" -> operatorMayDelete(rehearsalNamespace),
      "project"   -> operatorMayDelete(namespace(shop))
    )
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then(
    "a project database is made for the rehearsal, apart from the project database of {string}"
  ) { (_: String) =>
    val name = latest.map(_.name).getOrElse(fail("no rehearsal"))
    waitFor(10.minutes, "the rehearsal's cluster")(clusterIn(rehearsalNamespace, name).isDefined)
    assert(clusterIn(namespace(shop), name).isEmpty, "nothing beside the project database")
  }

  Then("no service is switched to it") { () =>
    assert(projectStatus(shop).clusters.forall(_.name != latest.get.name))
  }

  Then("it is checked as a restore is checked") { () =>
    latest = Some(awaitEnded(latest.get.name))
    assertEquals(latest.get.outcome, "Completed", latest.toString)
    assert(latest.get.services.exists(_.present), latest.toString)
  }

  Then("the rehearsal records how long it took") { () =>
    assert(latest.get.elapsedSeconds.exists(_ > 0), latest.toString)
  }

  Then("the project database made for the rehearsal is then removed") { () =>
    waitFor(5.minutes, "the rehearsal's cluster removed")(
      clusterIn(rehearsalNamespace, latest.get.name).isEmpty
    )
  }

  Then(
    "the rehearsal is listed with who asked for it, when, the moment, its outcome and how long it took"
  ) { () =>
    val listed = rehearsals.find(_.name == latest.get.name).getOrElse(fail("not listed"))
    assert(listed.requestedBy.isDefined && listed.requestedAt.isDefined, listed.toString)
    assert(Set("Completed", "Failed").contains(listed.outcome), listed.toString)
    assert(listed.elapsedSeconds.isDefined, listed.toString)
  }

  Then("a rehearsal of {string} runs every day") { (_: String) =>
    waitFor(20.minutes, "a scheduled rehearsal")(rehearsals.exists(_.requestedBy.isEmpty))
  }

  Then(
    "a rehearsal that fails is reported on the status of the project {string} and as a metric, as a backup failure is"
  ) { (_: String) =>
    // The scheduled rehearsal ends first: one still running is neither, and sets no gauge.
    waitFor(15.minutes, "the latest rehearsal ending")(
      projectStatus(shop).rehearsal.exists(_.outcome != "Running")
    )
    // A failed rehearsal is the latest one: the status names it and the gauge reads 1.
    val status = projectStatus(shop)
    status.rehearsal.filter(_.outcome != "Completed") match
      case Some(failed) =>
        assert(!status.backedUp && status.detail.exists(_.contains(failed.name)), status.toString)
        waitFor(2.minutes, "the rehearsal gauge")(
          gauge("ankka.rehearsals.failing", shop).contains(1.0)
        )
      case None =>
        waitFor(2.minutes, "the rehearsal gauge")(
          gauge("ankka.rehearsals.failing", shop).contains(0.0)
        )
  }

  Then("no instance of {string} is replaced") { (service: String) =>
    assertEquals(pods(shop, serviceOf(service)).map(_.getMetadata.getUid).toSet, podsBefore)
  }

  Then("the project database of {string} is unchanged") { (_: String) =>
    assertEquals(psql(shop, "select count(*) from pg_stat_database").trim, databaseBefore)
  }

  Then("the backups of {string} are unchanged") { (_: String) =>
    assertEquals(projectStatus(shop).lines.map(_.line), linesBefore)
    assert(projectStatus(shop).backedUp)
  }

  Then("it is removed") { () =>
    waitFor(5.minutes, "the expired rehearsal removed")(
      clusterIn(rehearsalNamespace, latest.get.name).isEmpty
    )
  }

  Then(
    "the status of the project {string} reported that it was not removed when the rehearsal ended"
  ) { (_: String) =>
    assertEquals(latest.map(_.outcome), Some("NotRemoved"))
  }

  Then("it may remove a project database made for a rehearsal") { () =>
    assert(answers("rehearsal"))
  }

  Then("it may remove no other project database") { () =>
    assert(!answers("project"))
  }
