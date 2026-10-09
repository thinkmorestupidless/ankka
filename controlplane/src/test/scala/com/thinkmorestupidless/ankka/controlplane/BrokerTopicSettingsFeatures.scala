package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  ProjectHistoryEntry,
  ProjectTopic,
  RetentionTime,
  Setting,
  SettingChange
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * A topic's settings on Strimzi (feature 043): what only a broker can show, each file a suite of
 * its own on an installation of its own, as the other broker files are. Like every k3s suite these
 * run only with `ankka.cluster.tests` on, in the `cluster` workflow, never on a pull request. What
 * the control plane alone decides — a refusal, a default, the history — is proved offline by
 * `TopicSettingsOfflineFeatures`, and `ranElsewhere` names it.
 */
abstract class BrokerTopicSettingsFeatures(feature: String) extends BrokerClusterFeatures(feature):

  /** "90 days" as a member types it: `90d`. */
  protected def words(text: String): String =
    val Units = """(\d+)\s*(day|days|hour|hours|year|years|GiB|MiB)""".r
    text.trim match
      case "everything"               => "everything"
      case Units(n, "year" | "years") => s"${n.toInt * 365}d"
      case Units(n, "day" | "days")   => s"${n}d"
      case Units(n, "hour" | "hours") => s"${n}h"
      case Units(n, unit)             => s"$n$unit"
      case other                      => other

  protected def flag(name: String, value: String): Seq[String] = name match
    case "retention time" => Seq("--retention", words(value))
    case "retention size" => Seq("--retention-size", words(value))
    case "cleanup policy" => Seq("--cleanup", value)
    case other            => fail(s"no setting '$other'")

  protected def declareWith(t: String, p: String, args: String*): Run =
    ensureProject(p)
    val known = topicsOf(p).exists(_.name == t)
    ankka(
      (Seq("projects", "topics", "set", t) ++ (if known then Nil
                                               else Seq("--partitions", "3")) ++ args ++ Seq(
        "-p",
        p
      ))*
    )

  protected def topicsOf(p: String): Vector[ProjectTopic] =
    val run = ankka("projects", "topics", "list", "-p", p, "-o", "json")
    if run.code == 0 then readFromString[Vector[ProjectTopic]](run.out) else Vector.empty

  protected def config(p: String, t: String, key: String): String =
    jsonPath("kafkatopic", "-n", Broker, s"$p.$t", s"{.spec.config.${key.replace(".", "\\.")}}")

  /** The topic's resource says `key` is `value`, and Strimzi has applied it to the broker. */
  protected def applied(p: String, t: String, key: String, value: String): Unit =
    waitFor(180.seconds, s"$p.$t having $key=$value on the broker") {
      config(p, t, key) == value &&
      topicsOf(p).find(_.name == t).flatMap(_.phase).contains("provisioned")
    }

  protected var lastProject: String = ""

  Given("the topic {string} is declared on {string} with the {word} {word} {string}") {
    (t: String, p: String, a: String, b: String, v: String) =>
      lastProject = project(p)
      ok(declareWith(t, lastProject, flag(s"$a $b", v)*))
      topicMade(lastProject, t)
  }

  When("a member declares the topic {string} on {string} with the {word} {word} {string}") {
    (t: String, p: String, a: String, b: String, v: String) =>
      lastProject = project(p)
      ok(declareWith(t, lastProject, flag(s"$a $b", v)*)): Unit
  }

  Then("the topic {string} of {string} is compacted on the installation's broker") {
    (t: String, p: String) =>
      applied(project(p), t, "cleanup.policy", "compact")
  }

  Then("the status of {string} shows the cleanup policy {string}") { (t: String, c: String) =>
    assertEquals(
      topicsOf(lastProject).find(_.name == t).flatMap(_.settings).map(_.cleanup),
      Some(c)
    )
  }

/** `features/broker/retention.feature`: the broker keeps by time and size what the topic says. */
class BrokerRetentionFeatures extends BrokerTopicSettingsFeatures("retention.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic declared with partitions alone is filled from the installation's defaults" -> "TopicRetentionOfflineFeature",
    "a declaration longer than the installation's longest retention time is refused" -> "TopicRetentionOfflineFeature",
    "a topic keeps everything where the installation sets no longest retention time" -> "TopicRetentionOfflineFeature",
    "a change to the installation's default changes no topic already declared" -> "TopicRetentionOfflineFeature",
    "a topic declared before a declaration could say its settings is filled by the control plane when it is upgraded" ->
      "TopicRetentionOfflineFeature and TopicSettingsSweepSuite"
  )

  Given("the installation's default retention time is {string}") { (v: String) =>
    assertEquals(topicPolicy.defaults.retention, RetentionTime.parse(words(v)).toOption.get)
  }

  When(
    "a member declares the topic {string} on {string} with the retention time {string} and the retention size {string}"
  ) { (t: String, p: String, r: String, s: String) =>
    lastProject = project(p)
    ok(declareWith(t, lastProject, flag("retention time", r) ++ flag("retention size", s)*)): Unit
  }

  // What the broker then does with a message is Kafka's own; the platform's part is that the topic
  // on the broker says both, and Strimzi has applied them.
  Then(
    "the installation's broker keeps a message on {string} until it is older than {string} or its partition holds more than {string}, whichever comes first"
  ) { (t: String, r: String, s: String) =>
    applied(
      lastProject,
      t,
      "retention.ms",
      RetentionTime.parse(words(r)).toOption.get.kafka.toString
    )
    applied(
      lastProject,
      t,
      "retention.bytes",
      com.thinkmorestupidless.ankka.controlplane.api.RetentionSize
        .parse(words(s))
        .toOption
        .get
        .kafka
        .toString
    )
    for key <- Seq(
        "cleanup.policy",
        "delete.retention.ms",
        "min.compaction.lag.ms",
        "max.compaction.lag.ms",
        "min.insync.replicas"
      )
    do assert(config(lastProject, t, key).nonEmpty, s"$key is left to the broker's default")
  }

  Then("the status of {string} shows the retention time {string} and the retention size {string}") {
    (t: String, r: String, s: String) =>
      val settings = topicsOf(lastProject)
        .find(_.name == t)
        .flatMap(_.settings)
        .getOrElse(fail(s"no settings for $t"))
      assertEquals((settings.retention, settings.retentionSize), (words(r), words(s)))
  }

/** `features/broker/cleanup-policy.feature`: a compacted topic on Strimzi. */
class BrokerCleanupFeatures extends BrokerTopicSettingsFeatures("cleanup-policy.feature"):

  // What the broker does under a policy is Kafka's own: the platform's part, every setting reaching
  // the topic's config, is held by StrimziModelsSuite against the control plane's own rendering.
  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic with the cleanup policy \"compact,delete\" keeps the last message under a key only within its retention time" -> "StrimziModelsSuite",
    "a message published under no key to a compacted topic fails in the service before it reaches the broker" -> "KafkaSuite",
    "a deletion on a compacted topic is read for the topic's tombstone window" -> "StrimziModelsSuite",
    "no message on a compacted topic is compacted away within its minimum compaction lag" -> "StrimziModelsSuite"
  )

/** `features/broker/changing.feature`: a setting changed in place, with no service restarted. */
class BrokerChangingFeatures extends BrokerTopicSettingsFeatures("changing.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a change that removes messages is refused to a member who is not an owner" -> "TopicChangingOfflineFeature",
    "an owner is told what a shorter retention time removes and confirms before it is sent" -> "TopicChangingOfflineFeature",
    "a declaration that removes messages without stating what it accepts removing is refused" -> "TopicChangingOfflineFeature",
    "a change that removes no message needs only a member" -> "TopicChangingOfflineFeature",
    "a declaration that changes nothing records nothing"   -> "TopicChangingOfflineFeature",
    // The cart sample keys every notice by its cart, so it never publishes without a key; the reading
    // of a topic's policy from the broker, refreshed while the service runs, is held by these.
    "a running service learns a topic's new cleanup policy from the broker without a restart" -> "TopicConfigsSuite and KafkaSuite"
  )

  private var pods: Map[String, Vector[(String, Int)]] = Map.empty

  private def podsOf(service: String, p: String): Vector[(String, Int)] =
    k8s
      .pods()
      .inNamespace(ns(p))
      .withLabel("app.kubernetes.io/name", service)
      .list()
      .getItems
      .asScala
      .toVector
      .map(pod =>
        pod.getMetadata.getName ->
          Option(pod.getStatus.getContainerStatuses)
            .fold(0)(_.asScala.map(_.getRestartCount.intValue).sum)
      )
      .sortBy(_._1)

  Given("a deployed service {string} in {string} that publishes to and reads the topic {string}") {
    (s: String, p: String, t: String) =>
      lastProject = project(p)
      deploy(a(s), lastProject, notices = t): Unit
      pods += a(s) -> podsOf(a(s), lastProject)
  }

  Then("the installation's broker keeps a message on {string} for {string}") {
    (t: String, r: String) =>
      applied(
        lastProject,
        t,
        "retention.ms",
        RetentionTime.parse(words(r)).toOption.get.kafka.toString
      )
  }

  Then("no instance of {string} is restarted") { (s: String) =>
    assertEquals(podsOf(a(s), lastProject), pods.getOrElse(a(s), fail(s"$s was never deployed")))
  }

  Then("{string} records the actor, the retention time {string} and the retention time {string}") {
    (p: String, from: String, to: String) =>
      val history = readFromString[Vector[ProjectHistoryEntry]](
        ok(ankka("projects", "history", "-p", project(p), "-o", "json")).out
      )
      val entry = history.headOption.getOrElse(fail("no history"))
      assertEquals(entry.actor.map(_.subject), Some("tester"))
      assertEquals(entry.changes, Vector(SettingChange(Setting.Retention, words(from), words(to))))
  }

/** `features/broker/copies.feature` on a broker of three nodes. */
class BrokerCopiesFeatures extends BrokerTopicSettingsFeatures("copies.feature"):

  override protected def brokerNodes: Int = 3

  // One scenario declares "transactions" with five copies, which the topic keeps for its life.
  override protected def projectPerScenario: Boolean = true

  // The three-node component's control plane: three copies, two in sync; at most five.
  override protected def topicPolicy: TopicPolicy =
    TopicPolicy.default.copy(
      defaults = TopicPolicy.default.defaults.copy(copies = 3, minInSync = 2),
      bounds = TopicPolicy.default.bounds.copy(mostCopies = 5)
    )

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic on a broker of one broker node says it has a single copy" -> "TopicCopiesOfflineFeature",
    "a topic's copies and minimum in-sync copies are fixed when it is declared" -> "TopicCopiesOfflineFeature"
  )

  private val Pool = s"${com.thinkmorestupidless.ankka.operator.BrokerStack.Cluster}-dual"

  private def stopNode(): Unit =
    // Strimzi first, or its pod set controller starts the node again at once.
    stopStrimzi()
    node("kubectl", "delete", "pod", "-n", Broker, s"$Pool-2", "--wait=true"): Unit

  private def nodeBack(): Unit =
    startStrimzi()
    waitFor(300.seconds, "the third broker node being ready again") {
      jsonPath(
        "pod",
        "-n",
        Broker,
        s"$Pool-2",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }

  Given("the platform as it is installed in a cluster with three broker nodes")(() => ())
  When("the installation starts")(() => ())

  Then(
    "the broker's default copies, its default minimum in-sync copies and the copies of the broker's own topics are set for three broker nodes"
  ) { () =>
    def setting(key: String) =
      jsonPath(
        "kafka",
        "-n",
        Broker,
        com.thinkmorestupidless.ankka.operator.BrokerStack.Cluster,
        s"{.spec.kafka.config.${key.replace(".", "\\.")}}"
      )
    assertEquals(setting("default.replication.factor"), "3")
    assertEquals(setting("min.insync.replicas"), "2")
    assertEquals(setting("offsets.topic.replication.factor"), "3")
    assertEquals(setting("transaction.state.log.replication.factor"), "3")
    assertEquals(setting("transaction.state.log.min.isr"), "2")
  }

  Then("the operator reports that the broker has three broker nodes") { () =>
    val p = project("shape")
    ok(declareWith("probe-topic", p))
    waitFor(180.seconds, "the operator's count of broker nodes")(
      topicsOf(p).exists(_.brokerNodes.contains(3))
    )
  }

  Given("an installation whose broker has three broker nodes")(() => ())

  Given("the installation's most copies is {int}") { (n: Int) =>
    assertEquals(topicPolicy.bounds.mostCopies, n)
  }

  When("a member declares the topic {string} on {string} with {int} copies") {
    (t: String, p: String, n: Int) =>
      lastProject = project(p)
      ok(declareWith(t, lastProject, "--copies", n.toString)): Unit
  }

  Then("{string} declares the topic {string}") { (p: String, t: String) =>
    assert(topicsOf(project(p)).exists(_.name == t), topicsOf(project(p)).toString)
  }

  Then("the topic {string} is {string}, naming the broker's three broker nodes") {
    (t: String, word: String) =>
      waitFor(180.seconds, s"$t being $word") {
        topicsOf(lastProject)
          .find(_.name == t)
          .exists(r =>
            r.phase.contains(word.toLowerCase) && r.detail
              .exists(_.contains("the broker has 3 broker nodes"))
          )
      }
  }

  Then("nothing is made on the installation's broker") { () =>
    Thread.sleep(5000)
    assert(!topicExists(s"$lastProject.transactions"), s"$lastProject.transactions was made")
  }

  Given("the topic {string} is declared on {string} with partitions alone") {
    (t: String, p: String) =>
      lastProject = project(p)
      ok(declareWith(t, lastProject))
      topicMade(lastProject, t)
  }

  Given(
    "the topic {string} is declared on {string} with {int} copies and {int} minimum in-sync copies"
  ) { (t: String, p: String, c: Int, m: Int) =>
    lastProject = project(p)
    ok(declareWith(t, lastProject, "--copies", c.toString, "--min-in-sync", m.toString))
    topicMade(lastProject, t)
  }

  /**
   * `count` messages to `topic` in one producer, as the service holding `service`'s certificate.
   */
  private def publishAs(service: String, topic: String, from: Int, count: Int): Unit =
    val lines = (from until from + count).map(n => s"message-$n").mkString("\n")
    val run   = probe(service, lastProject).publish(s"$lastProject.$topic", lines, waitMs = 60000)
    assertEquals(run.code, 0, run.output)

  Given("a consumer of {string} has published {int} messages to {string}, each acknowledged") {
    (p: String, n: Int, t: String) =>
      lastProject = project(p)
      deploy("wallet", lastProject, notices = t): Unit
      publishAs("wallet", t, 0, n)
  }

  When("one broker node is stopped")(() => stopNode())

  When("the consumer publishes {int} messages more") { (n: Int) =>
    publishAs("wallet", "transactions", 100, n)
  }

  Then("{int} messages are read from {string} from the earliest") { (n: Int, t: String) =>
    val read = probe("wallet", lastProject).read(
      s"$lastProject.$t",
      groupOf("wallet", lastProject),
      max = n,
      waitMs = 60000
    )
    assertEquals(read.messages.count(_.startsWith("message-")), n, read.output)
    nodeBack()
  }

  Given(
    "a deployed service {string} in {string} with a consumer that publishes to the topic {string}"
  ) { (s: String, p: String, t: String) =>
    lastProject = project(p)
    deploy(a(s), lastProject, notices = t): Unit
  }

  Then(
    "publishing to {string} is refused until the broker node returns, and the log of {string} names the topic and the reason"
  ) { (t: String, s: String) =>
    checkout(a(s), lastProject): Unit
    waitFor(180.seconds, s"${a(s)}'s log naming $t and the missing copies") {
      val log = logsOf(a(s), lastProject)
      log.contains(s"could not publish to topic '$t'") && log.contains("NotEnoughReplicas")
    }
    nodeBack()
  }

  Then("what the consumer publishes is read from {string}") { (t: String) =>
    val cart = checkout("wallet", lastProject)
    waitFor(180.seconds, s"$cart being read from $t") {
      probe("wallet", lastProject)
        .read(s"$lastProject.$t", groupOf("wallet", lastProject), waitMs = 15000)
        .output
        .contains(cart)
    }
    nodeBack()
  }
