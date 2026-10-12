package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{GrantDetail, GrantState}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import scala.concurrent.duration.{Deadline, DurationInt}
import scala.jdk.CollectionConverters.*

/**
 * `features/cross-project/topic-grants.feature` on k3s, on the installation `BrokerClusterFeatures`
 * installs: the broker stack, the operator told of it, and the control plane and the CLI as an
 * owner uses them. The broker steps are that suite's; these are the grants.
 *
 * Every service is the cart sample. A view "of another project's topic" is the sample's
 * `checkouts-seen` view with `CART_CHECKOUTS_TOPIC=<project>/<topic>`, and a consumer publishing to
 * one is its checkout notifier with `CART_PUBLISH_TO=<project>/<topic>`. What the broker allows a
 * credential is asked with `BrokerProbe`, a pod holding that service's certificate and nothing
 * else, so a refusal is the broker's own.
 *
 * The suite has one organization, the one its owner made; the scenario's organization names it.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class CrossProjectTopicGrantsFeatures
    extends BrokerClusterFeatures("topic-grants.feature", "cross-project"):

  /** The grants this scenario made, each with its project, revoked when the scenario ends. */
  private var grants: Vector[(String, String)]              = Vector.empty
  private var lastGrant: Run                                = Run(0, "", "")
  private var revokedAt: Option[Deadline]                   = None
  private var restartsBefore: Map[String, Map[String, Int]] = Map.empty
  private var checkedOut: Vector[String]                    = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    grants = Vector.empty
    lastGrant = Run(0, "", "")
    revokedAt = None
    restartsBefore = Map.empty
    checkedOut = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore then
      for (id, p) <- grants do ankka("projects", "grants", "revoke", id, "-p", p): Unit
    super.afterEach(context)

  // ── grants ────────────────────────────────────────────────────────────────

  private def grant(service: String, of: String, right: String, topic: String, p: String): Run =
    val run = ankka(
      "projects",
      "grants",
      "make",
      s"service:${project(of)}/${a(service)}",
      "topic",
      topic,
      right,
      "-p",
      project(p),
      "-o",
      "json"
    )
    lastGrant = run
    if run.code == 0 then
      val detail = readFromString[GrantDetail](run.out)
      assertEquals(detail.state, GrantState.Accepted, run.all)
      grants :+= (detail.id -> project(p))
    run

  /** The literal entry the operator writes on the grantee's user for a granted right. */
  private def granted(service: String, of: String, topic: String, operations: Set[String]) =
    aclsOf(s"${project(of)}.${a(service)}").contains(("topic", topic, "literal", operations))

  /** Each pod of every service of `p`, by uid, with how often its containers have restarted. */
  private def restartsIn(p: String): Map[String, Int] =
    k8s
      .pods()
      .inNamespace(ns(p))
      .withLabel("app.kubernetes.io/managed-by", "ankka")
      .list()
      .getItems
      .asScala
      .filter(pod =>
        Option(pod.getMetadata.getLabels).exists(_.containsKey("app.kubernetes.io/name"))
      )
      .map(pod =>
        pod.getMetadata.getUid -> Option(pod.getStatus.getContainerStatuses)
          .map(_.asScala.map(_.getRestartCount.intValue).sum)
          .getOrElse(0)
      )
      .toMap

  private def restartsOf(service: String, p: String): Map[String, Int] =
    podsOf(service, p).map { pod =>
      pod.getMetadata.getUid -> Option(pod.getStatus.getContainerStatuses)
        .map(_.asScala.map(_.getRestartCount.intValue).sum)
        .getOrElse(0)
    }.toMap

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("the projects {string} and {string} of the organization {string}") {
    (p1: String, p2: String, _: String) =>
      ensureProject(project(p1))
      ensureProject(project(p2))
  }

  Given("a deployed service {string} in the project {string} of {string}") {
    (s: String, p: String, _: String) =>
      deploy(a(s), project(p)): Unit
  }

  Given(
    "an owner of {string} has granted the service {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, s: String, of: String, t: String, p: String) =>
    ok(grant(s, of, "consume", t, p)): Unit
    waitFor(120.seconds, s"${a(s)}'s user being granted $t") {
      granted(s, of, s"${project(p)}.$t", Set("Read", "Describe"))
    }
  }

  Given(
    "an owner of {string} has granted the service {string} of {string} to produce to the topic {string} of {string}"
  ) { (_: String, s: String, of: String, t: String, p: String) =>
    ok(grant(s, of, "produce", t, p)): Unit
    waitFor(120.seconds, s"${a(s)}'s user being granted to publish to $t") {
      granted(s, of, s"${project(p)}.$t", Set("Write", "Describe"))
    }
  }

  Given("a view {string} of {string} that reads the topic {string} of {string}") {
    (v: String, s: String, t: String, p: String) =>
      val own = currentProject(a(s))
      views += v -> (a(s), own)
      noticesTo(a(s), own, s"${project(p)}/$t")
  }

  Given("a view {string} of {string} that has read the topic {string} of {string}") {
    (v: String, s: String, t: String, p: String) =>
      val own = currentProject(a(s))
      views += v -> (a(s), own)
      noticesTo(a(s), own, s"${project(p)}/$t")
      val publisher               = ensurePublisher(project(p), t)
      val cart                    = checkout(publisher, project(p))
      val (reader, readerProject) = view(v)
      waitFor(180.seconds, s"$reader's view reading $cart") {
        seen(reader, readerProject, cart)._1 == 200
      }
      restartsBefore = Map(
        "attribution" -> restartsOf(a(s), own),
        project(p)    -> restartsIn(project(p))
      )
  }

  Given("a consumer {string} of {string} that publishes to the topic {string} of {string}") {
    (c: String, s: String, t: String, p: String) =>
      val own = currentProject(a(s))
      consumers += c -> (a(s), own)
      val current = appliedAs.getOrElse((a(s), own), fail(s"${a(s)} of $own was never deployed"))
      val target  = s"${project(p)}/$t"
      if !current.env.get("CART_PUBLISH_TO").contains(target) then
        ok(apply(current.copy(env = current.env.updated("CART_PUBLISH_TO", target))))
        waitFor(240.seconds, s"${a(s)} of $own publishing to $target") {
          environment(a(s), own).get(a(s)).exists(_.get("CART_PUBLISH_TO").contains(target)) &&
          podsOf(a(s), own).forall(pod =>
            pod.getSpec.getContainers.asScala.exists(
              _.getEnv.asScala.exists(e => e.getName == "CART_PUBLISH_TO" && e.getValue == target)
            ) && Option(pod.getStatus.getConditions)
              .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))
          )
        }
  }

  /** A service of `p` publishing its checkout notices to `topic` of its own project. */
  private def ensurePublisher(p: String, topic: String): String =
    if !appliedAs.contains(("wallet", p)) then deploy("wallet", p, notices = topic): Unit
    else noticesTo("wallet", p, topic)
    "wallet"

  // ═══ When ═════════════════════════════════════════════════════════════════

  When(
    "a service of {string} publishes {int} messages with distinct subjects to the topic {string}"
  ) { (p: String, n: Int, t: String) =>
    val publisher = ensurePublisher(project(p), t)
    checkedOut = Vector.fill(n)(checkout(publisher, project(p)))
  }

  When("the view subscribes") { () =>
    val (reader, p) = views.values.headOption.getOrElse(fail("no view was named"))
    waitFor(180.seconds, s"$reader's view subscribing") {
      logsOf(reader, p).contains("topic source subscribed: kind=view")
    }
  }

  When("the owner revokes the grant") { () =>
    val (id, p) = grants.lastOption.getOrElse(fail("no grant was made"))
    ok(ankka("projects", "grants", "revoke", id, "-p", p)): Unit
    grants = grants.dropRight(1)
    revokedAt = Some(Deadline.now)
  }

  When(
    "an owner of {string} grants the service {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, s: String, of: String, t: String, p: String) =>
    grant(s, of, "consume", t, p): Unit
  }

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("the view {string} holds {int} rows") { (v: String, n: Int) =>
    val (reader, p) = view(v)
    // Each checkout is one notice under its cart's id, and one row of the view once read.
    assertEquals(checkedOut.distinct.size, n)
    var missing = checkedOut
    waitFor(300.seconds, s"$reader's view holding $n rows") {
      missing = missing.filterNot(cart => seen(reader, p, cart)._1 == 200)
      missing.isEmpty
    }
  }

  Then("its group is {string}") { (expected: String) =>
    val (reader, p) = views.values.headOption.getOrElse(fail("no view was named"))
    val (name, _)   = views.headOption.getOrElse(fail("no view was named"))
    // The scenario's view is the sample's `checkouts-seen`, whose component id names the group.
    val group = expected.replace(s".view.$name", ".view.checkouts-seen")
    val logs  = logsOf(reader, p)
    assert(
      logs.contains(s"group=$group "),
      logs.linesIterator.filter(_.contains("subscribed")).mkString("\n")
    )
  }

  Then("the credential of {string} may read no group of {string}") { (s: String, other: String) =>
    val p    = currentProject(a(s))
    val acls = aclsOf(s"$p.${a(s)}")
    assert(
      !acls.exists((kind, name, _, _) =>
        kind == "group" && name.startsWith(s"ankka.${project(other)}.")
      ),
      acls.toString
    )
    val foreign =
      probe(a(s), p).read(
        s"${project(other)}.casino.players",
        s"ankka.${project(other)}.wallet.view.x",
        waitMs = 10000
      )
    assert(foreign.output.contains("GroupAuthorizationException"), foreign.output)
  }

  Then(
    "within {string} seconds the broker refuses the next read of {string} by the credential of {string}"
  ) { (seconds: String, topic: String, s: String) =>
    val p        = currentProject(a(s))
    val deadline = revokedAt.getOrElse(fail("nothing was revoked")) + seconds.toInt.seconds
    var last     = probe(a(s), p).read(topic, groupOf(a(s), p), waitMs = 5000)
    while !last.refused && deadline.hasTimeLeft() do
      Thread.sleep(2000)
      last = probe(a(s), p).read(topic, groupOf(a(s), p), waitMs = 5000)
    assert(last.refused, s"still read $seconds seconds after the revocation: ${last.output}")
  }

  Then("no instance of {string} is restarted") { (s: String) =>
    assertEquals(restartsOf(a(s), currentProject(a(s))), restartsBefore(s))
  }

  Then("no service of {string} is restarted") { (p: String) =>
    assertEquals(restartsIn(project(p)), restartsBefore(project(p)))
  }

  Then("the owner is refused")(() => assert(lastGrant.code != 0, lastGrant.all))

  Then("the refusal says that {string} has not declared the topic {string}") {
    (p: String, t: String) =>
      assert(
        lastGrant.all.contains(s"project '${project(p)}' has not declared the topic '$t'"),
        lastGrant.all
      )
  }
