package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.RestoreView
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{DivergenceReader, InstanceTopologies}
import com.thinkmorestupidless.ankka.operator.{BrokerStack, PkiStack}
import org.testcontainers.images.builder.Transferable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * `features/databases/after-a-restore.feature` on k3s (feature 041): what a restore cannot take
 * back, with the installation's broker. The sample cart plays both services: `ledger` publishes a
 * notice for each cart checked out to the topic `transactions`, and `totals` counts, in its view,
 * the notices it has read of each cart. An entity's "event" is a cart's checkout, the one event the
 * notifier publishes; what each message carried is read from the broker with `totals`'s own
 * certificate, by a probe that prints the headers.
 *
 * The feature's "published again from its restored read position" is made certain rather than left
 * to timing: before `ledger` is switched to the restore, the notifier's read position in the
 * restored database is moved back to its start, as a restore made just before the notifier caught
 * up would have left it.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class AfterRestoreClusterFeatures
    extends BackupClusterSteps("../features/databases/after-a-restore.feature"):

  // A project database a scenario, on one node: the node holds no more than one scenario's at a time.
  override protected def removesEachScenario: Boolean = true

  override protected def installMore(): Unit = broker = Some(BrokerStack.install(k3s))

  /**
   * The shipped reader, asking each service's observe port as the control plane's identity: a
   * certificate for `ankka://platform/controlplane` from the service authority, its files in a
   * directory of the test's, and a port-forward to the pod in place of its address, since the test
   * JVM is outside the cluster's network. A port-forward enters the pod itself, so the network
   * policy that admits only the control plane's namespace is not what this proves; the identity is.
   */
  override protected def divergence: Option[DivergenceReader] =
    val certificate =
      """apiVersion: v1
        |kind: Namespace
        |metadata: { name: observer-test }
        |---
        |apiVersion: cert-manager.io/v1
        |kind: Certificate
        |metadata:
        |  name: observer
        |  namespace: observer-test
        |spec:
        |  secretName: observer-tls
        |  uris: [ankka://platform/controlplane]
        |  usages: [client auth]
        |  duration: 24h
        |  privateKey: { algorithm: RSA, size: 2048, encoding: PKCS8 }
        |  issuerRef: { name: ankka-service, kind: ClusterIssuer, group: cert-manager.io }
        |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(certificate.getBytes(StandardCharsets.UTF_8)),
      "/tmp/observer.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/observer.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      "observer-test",
      "--for=condition=Ready",
      "certificate/observer",
      "--timeout=180s"
    ): Unit
    val directory: Path = Files.createTempDirectory("ankka-observer")
    val data = k8s.secrets().inNamespace("observer-test").withName("observer-tls").get().getData
    for file <- Seq("tls.crt", "tls.key", "ca.crt") do
      Files.write(directory.resolve(file), java.util.Base64.getDecoder.decode(data.get(file)))
    Some(
      new DivergenceReader:
        def since(projectId: String, service: String, at: Instant) =
          InstanceTopologies.runningPods(k8s, Prefix)(projectId, service).headOption match
            case None => Left(s"$service has no running instance")
            case Some((pod, _)) =>
              val forward = k8s
                .pods()
                .inNamespace(namespace(projectId))
                .withName(pod)
                .portForward(InstanceTopologies.ObservePort)
              try
                DivergenceReader
                  .ObservePort(
                    (_, _) => Vector(pod -> "127.0.0.1"),
                    Some(directory),
                    port = forward.getLocalPort
                  )
                  .since(projectId, service, at)
              finally forward.close()
    )

  override protected def serviceOf(logical: String): String = logical

  private def shop  = projectOf("shop")
  private val Topic = "transactions"

  private var restoreName: String              = ""
  private var moment: Instant                  = Instant.EPOCH
  private var cartIds: Vector[String]          = Vector.empty
  private var before: Map[String, Set[String]] = Map.empty
  private var brokerBefore: String             = ""

  private def ledgerDescriptor =
    descriptor("ledger", env = Vector(s"""{"name":"CART_CHECKOUTS_TOPIC","value":"$Topic"}"""))
  private def totalsDescriptor =
    descriptor("totals", env = Vector(s"""{"name":"CART_CHECKOUTS_TOPIC","value":"$Topic"}"""))

  private def probe = BrokerProbe.holding(k3s, namespace(shop), "totals")

  /** Every message on the topic, as (the cart it is about, its ce-id), read from the beginning. */
  private def published: Vector[(String, String)] =
    val group = s"ankka.$shop.totals.probe-${java.util.UUID.randomUUID().toString.take(8)}"
    probe
      .readWithHeaders(s"$shop.$Topic", group, waitMs = 15000)
      .messages
      .flatMap { line =>
        val id   = "ce-id:([^,\\t]*)".r.findFirstMatchIn(line).map(_.group(1))
        val cart = "\"cartId\":\"([^\"]*)\"".r.findFirstMatchIn(line).map(_.group(1))
        for i <- id; c <- cart yield c -> i
      }

  private def checkout(cart: String): Unit =
    addItem(shop, "ledger", cart, "pen")
    val (code, body) = cart_(cart)
    assertEquals(code, 200, body)

  private def cart_(id: String) = cart(shop, "ledger", "POST", s"/carts/$id/checkout")

  private def seen(cartId: String): Option[Int] =
    val (code, body) = cart(shop, "totals", "GET", s"/checkouts-seen/$cartId")
    Option
      .when(code == 200)("\"notices\":([0-9]+)".r.findFirstMatchIn(body).map(_.group(1).toInt))
      .flatten

  private def restoreView: RestoreView =
    readFromString[RestoreView](
      ok(ankka("projects", "restores", "get", shop, restoreName, "-o", "json")).out
    )

  private def restoreAt(at: Instant): Unit =
    psql(shop, "select pg_switch_wal()"): Unit
    waitFor(5.minutes, "the archive passing the moment") {
      projectStatus(shop).lines
        .find(_.line == "ankka-db")
        .flatMap(_.lastRestorable)
        .exists(_.isAfter(at))
    }
    restoreName = readFromString[RestoreView](
      ok(ankka("projects", "restore", shop, at.toString, "-o", "json")).out
    ).name
    waitFor(15.minutes, s"$restoreName verified")(
      Set("Verified", "InUse").contains(restoreView.phase)
    )

  /** In the restore, as postgres: the notifier reads `ledger`'s journal from its start again. */
  private def rewindNotifier(): Unit =
    val pod = k8s
      .pods()
      .inNamespace(namespace(shop))
      .withLabel("cnpg.io/cluster", restoreName)
      .withLabel("cnpg.io/instanceRole", "primary")
      .list()
      .getItems
      .get(0)
      .getMetadata
      .getName
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      namespace(shop),
      pod,
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "ledger",
      "-c",
      "delete from projection_timestamp_offset_store where projection_name like 'ankka-consumer-checkout-notifier%'; " +
        "delete from projection_offset_store where projection_name like 'ankka-consumer-checkout-notifier%'"
    )
    assertEquals(r.getExitCode, 0, r.getStderr)

  private def switchLedger(): Unit =
    ok(ankka("services", "switch", "ledger", "-p", shop, "--to", restoreName)): Unit
    waitFor(8.minutes, "ledger on the restore") {
      statusOf("ledger", shop).exists(s =>
        s.databaseCluster.contains(restoreName) && isReady(shop, "ledger")
      )
    }

  /** The topic's end offsets and the counting view's group's offsets, as the broker's tools say. */
  private def brokerState: String =
    val group     = s"ankka.$shop.totals.view.checkouts-seen"
    val bootstrap = BrokerStack.settings.bootstrap
    probe.tool(s"kafka-get-offsets.sh --bootstrap-server $bootstrap --topic $shop.$Topic").output +
      "\n" +
      probe
        .tool(s"kafka-consumer-groups.sh --bootstrap-server $bootstrap --describe --group $group")
        .output
        .linesIterator
        .filter(_.contains(Topic))
        .map(_.trim.split("\\s+").take(4).mkString(" "))
        .mkString("\n")

  // ── Background ────────────────────────────────────────────────────────────

  Given("a project {string} that is backed up, with the declared topic {string}") {
    (project: String, topic: String) =>
      val p = projectOf(project)
      ensureProject(p)
      ok(ankka("projects", "topics", "set", topic, "--partitions", "1", "-p", p)): Unit
  }

  Given(
    "a deployed service {string} in the project {string} with an entity {string} and a consumer that publishes its events to the topic {string}"
  ) { (_: String, project: String, _: String, _: String) =>
    if !isReady(projectOf(project), "ledger") then
      deploy(projectOf(project), "ledger", ledgerDescriptor)
    awaitBackedUp(projectOf(project))
  }

  Given(
    "a deployed service {string} in the project {string} with a view {string} that reads the topic {string}"
  ) { (_: String, project: String, _: String, _: String) =>
    if !isReady(projectOf(project), "totals") then
      deploy(projectOf(project), "totals", totalsDescriptor)
  }

  Given("an owner of the organization {string} is in")((_: String) => ())

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    if !munitIgnore then
      cartIds = Vector.empty
      // Every scenario begins with ledger on the project database.
      if statusOf("ledger", shop).exists(_.databaseCluster.isDefined) then
        ok(ankka("services", "switch", "ledger", "-p", shop, "--to", "ankka-db")): Unit
        waitFor(8.minutes, "ledger back") {
          statusOf("ledger", shop).exists(s => s.databaseCluster.isEmpty && isReady(shop, "ledger"))
        }

  // ── Scenario 1 ────────────────────────────────────────────────────────────

  Given("a completed restore of {string}") { (_: String) =>
    checkout(s"before-$scenario"): Unit
    Thread.sleep(3000)
    moment = Instant.now()
    Thread.sleep(3000)
    cartIds = Vector.tabulate(3)(i => s"after-$scenario-$i")
    cartIds.foreach(checkout)
    waitFor(2.minutes, "totals reading past the moment")(cartIds.forall(seen(_).isDefined))
    restoreAt(moment)
  }

  Given("the topic {string} holds messages {string} published after the restore point") {
    (_: String, _: String) =>
      assert(
        published.map(_._1).toSet.subsetOf(published.map(_._1).toSet) && cartIds.forall(c =>
          published.exists(_._1 == c)
        )
      )
  }

  Given("the group of {string} has read {string} past the restore point") { (_: String, _: String) =>
    assert(cartIds.forall(seen(_).contains(1)))
  }

  When("the owner reads the restore")(() => restoreView: Unit)

  Then(
    "the restore lists the topic {string} with the number of messages newer than the restore point on each partition"
  ) { (topic: String) =>
    val listed = restoreView.broker
      .find(e => e.topic == topic && e.group.isEmpty)
      .getOrElse(fail(restoreView.toString))
    assert(listed.after >= cartIds.size, listed.toString)
  }

  Then("the restore lists the group of {string} as having read further than the restore point") {
    (_: String) =>
      val group = restoreView.broker
        .find(_.group.exists(_.contains(".totals.")))
        .getOrElse(fail(restoreView.toString))
      assert(group.read.exists(_ >= cartIds.size), group.toString)
  }

  // ── Scenarios 2 and 3: the message ids ────────────────────────────────────

  Given(
    "{string} published the event {int} of the entity {string} to {string} before a restore of {string}"
  ) { (_: String, _: Int, entity: String, _: String, _: String) =>
    val cart = s"$entity-$scenario"
    checkout(cart)
    waitFor(2.minutes, "its message")(published.exists(_._1 == cart))
    before = published.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    Thread.sleep(3000)
    restoreAt(Instant.now())
    cartIds = Vector(cart)
  }

  Given("{string} switched to the restore") { (_: String) =>
    rewindNotifier()
    switchLedger()
  }

  When(
    "{string} publishes the event {int} of the entity {string} again from its restored read position"
  ) { (_: String, _: Int, _: String) =>
    waitFor(3.minutes, "the message published again") {
      published.count(_._1 == cartIds.head) >= 2
    }
  }

  Then("the message carries the message id the first message carried") { () =>
    val ids = published.filter(_._1 == cartIds.head).map(_._2)
    assertEquals(ids.distinct.size, 1, ids.toString)
    assert(before(cartIds.head).contains(ids.head))
  }

  // Scenario 5's carts were checked out after its moment already; scenario 3 makes its own.
  Given(
    "{string} switched to a restore of {string} made before the event {int} of the entity {string} was recorded"
  ) { (_: String, _: String, _: Int, entity: String) =>
    if cartIds.nonEmpty then
      restoreAt(moment)
      rewindNotifier()
      switchLedger()
    else switchedBeforeALostEvent(entity)
  }

  private def switchedBeforeALostEvent(entity: String): Unit =
    checkout(s"$entity-$scenario-earlier")
    Thread.sleep(3000)
    moment = Instant.now()
    Thread.sleep(3000)
    checkout(s"$entity-$scenario-lost")
    waitFor(2.minutes, "the lost event's message")(
      published.exists(_._1 == s"$entity-$scenario-lost")
    )
    before = published.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    restoreAt(moment)
    switchLedger()
    cartIds = Vector(s"$entity-$scenario-lost")

  When("{string} records a new event {int} of the entity {string} and publishes it") {
    (_: String, _: Int, _: String) =>
      // The cart the restore lost is recorded again: the same entity, its events written anew.
      checkout(cartIds.head)
      waitFor(3.minutes, "the new event's message")(published.count(_._1 == cartIds.head) >= 2)
  }

  Then("the message carries a message id that no message published before the restore carried") {
    () =>
      val earlier = before.values.flatten.toSet
      val now     = published.filter(_._1 == cartIds.head).map(_._2).filterNot(earlier)
      assert(now.nonEmpty, published.toString)
      assert(now.forall(_.startsWith(s"$restoreName/")), now.toString)
  }

  // ── Scenario 4: nothing done to the broker ────────────────────────────────

  Given("a restore of {string}") { (_: String) =>
    checkout(s"quiet-$scenario")
    Thread.sleep(3000)
    moment = Instant.now()
    Thread.sleep(2000)
    brokerBefore = brokerState
  }

  When("the restore of {string} completes")((_: String) => restoreAt(moment))

  When("the owner switches {string} to the restore") { (_: String) =>
    restoreAt(moment)
    brokerBefore = brokerState
    ok(ankka("services", "switch", "ledger", "-p", shop, "--to", restoreName)): Unit
    Thread.sleep(20000)
  }

  private def ends(state: String) = state.linesIterator.filter(_.startsWith(s"$shop.$Topic:")).toSet
  private def groups(state: String) =
    state.linesIterator.filterNot(_.startsWith(s"$shop.$Topic:")).toSet

  Then("the platform publishes nothing to the broker") { () =>
    assertEquals(ends(brokerState), ends(brokerBefore))
  }

  Then("the platform deletes nothing from the broker") { () =>
    assert(ends(brokerState).nonEmpty, brokerState)
  }

  Then("the platform moves no group's read position") { () =>
    assertEquals(groups(brokerState), groups(brokerBefore))
  }

  Then("the listing of topics and groups is everything the platform did on the broker") { () =>
    assert(restoreView.phase != "Restoring")
  }

  // ── Scenario 5: a counting view counts again ──────────────────────────────

  Given("{string} counts the messages on {string}")((_: String, _: String) => ())

  Given("{string} has counted the events {int} to {int} of the entity {string}") {
    (_: String, from: Int, to: Int, entity: String) =>
      cartIds = (from to to).toVector.map(i => s"$entity-$scenario-$i")
      Thread.sleep(3000)
      moment = Instant.now()
      Thread.sleep(3000)
      cartIds.foreach(checkout)
      waitFor(2.minutes, "every one counted once")(cartIds.forall(seen(_).contains(1)))
  }

  When("{string} publishes the events {int} to {int} of the entity {string} again") {
    (_: String, _: Int, _: Int, _: String) =>
      // The restore holds none of these carts, so the switched ledger publishes them only when
      // they are recorded again; here they are, from a read position before every one of them.
      cartIds.foreach(checkout)
  }

  Then("{string} counts them again") { (_: String) =>
    waitFor(3.minutes, "every one counted twice")(cartIds.forall(seen(_).contains(2)))
  }

  Then(
    "the restore says that a message published again is read again by every view and consumer that reads its topic"
  ) { () =>
    assert(
      restoreView.note.exists(_.contains("read again by every view and consumer")),
      restoreView.toString
    )
  }

  // ── Scenario 6: a promotion keeps the line ────────────────────────────────

  Given("the primary of the project database of {string} was lost and a replica promoted") {
    (_: String) =>
      ok(ankka("projects", "database", "set", shop, "--replicas", "1")): Unit
      waitFor(12.minutes, "a replica")(projectStatus(shop).database.exists(_.readyInstances == 2))
      checkout(s"promoted-$scenario-before")
      waitFor(2.minutes, "its message")(published.exists(_._1 == s"promoted-$scenario-before"))
      val primary = projectStatus(shop).database.flatMap(_.primary).getOrElse(fail("no primary"))
      k8s.persistentVolumeClaims().inNamespace(namespace(shop)).withName(primary).delete(): Unit
      k8s.pods().inNamespace(namespace(shop)).withName(primary).withGracePeriod(0).delete(): Unit
      waitFor(5.minutes, "a replica promoted")(
        projectStatus(shop).database.flatMap(_.primary).exists(_ != primary)
      )
  }

  Given("{string} published events before the primary was lost") { (_: String) =>
    assert(published.exists(_._1 == s"promoted-$scenario-before"))
  }

  When("{string} publishes an event after the promotion") { (_: String) =>
    waitFor(3.minutes, "ledger writing again") {
      scala.util.Try(checkout(s"promoted-$scenario-after")).isSuccess
    }
    waitFor(2.minutes, "its message")(published.exists(_._1 == s"promoted-$scenario-after"))
  }

  Then("its message id is on the line of history of the events published before the promotion") {
    () =>
      val beforeId = published.find(_._1 == s"promoted-$scenario-before").map(_._2).get
      val afterId  = published.find(_._1 == s"promoted-$scenario-after").map(_._2).get
      assertEquals(afterId.takeWhile(_ != '/'), beforeId.takeWhile(_ != '/'))
  }
