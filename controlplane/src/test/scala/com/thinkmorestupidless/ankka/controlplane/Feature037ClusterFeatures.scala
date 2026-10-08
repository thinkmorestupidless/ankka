package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  ProjectBroker,
  ProjectEndpoint,
  ProjectTopic,
  ServiceLifecycle
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The cluster proofs of feature 037 that the broker suites' installation can give: a declared
 * topic's contract and schema through the control plane, a compacted topic on Strimzi, a declared
 * broker's secret on a service's platform container and nowhere else. Each file is a suite of its
 * own on an installation of its own, as the broker files are. What a scenario needs that no sample
 * on this cluster can give — a service stating a contract of its own, a second broker to read from
 * — is proved offline, and `ranElsewhere` says where.
 */
abstract class Feature037ClusterFeatures(area: String, feature: String)
    extends BrokerClusterFeatures(feature, area):

  /** The schema every contract here is declared with. */
  protected val Schema: String =
    """{"type":"object","required":["id"],"properties":{"id":{"type":"string"}}}"""

  protected var lastRun: Run                           = Run(0, "", "")
  protected var lastContract: Option[(String, String)] = None
  protected var listed: Vector[ProjectTopic]           = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    lastRun = Run(0, "", "")
    lastContract = None
    listed = Vector.empty

  protected def declareWith(t: String, p: String, n: Int, args: String*): Run =
    ensureProject(p)
    lastRun = ankka(
      (Seq("projects", "topics", "set", t, "--partitions", n.toString) ++ args ++ Seq("-p", p))*
    )
    lastRun

  protected def declareContract(t: String, p: String, n: Int, contract: String): Run =
    val file = Files.createTempFile("schema", ".json")
    try
      Files.writeString(file, Schema): Unit
      val run = declareWith(t, p, n, "--contract", contract, "--schema", file.toString)
      if run.code == 0 then lastContract = Some((p, t))
      run
    finally Files.deleteIfExists(file): Unit

  protected def topicsOf(p: String): Vector[ProjectTopic] =
    readFromString[Vector[ProjectTopic]](
      ok(ankka("projects", "topics", "list", "-p", p, "-o", "json")).out
    )

  protected def sameJson(a: String, b: String): Boolean =
    GraphJson.parse(a.getBytes(UTF_8)) == GraphJson.parse(b.getBytes(UTF_8))

  When("a member lists the topics of {string}") { (p: String) =>
    listed = topicsOf(project(p))
  }

/** features/topics/contracts.feature on k3s. */
class TopicContractsFeatures extends Feature037ClusterFeatures("topics", "contracts.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    // A service that states a contract of its own: the sample on this cluster states none, and a
    // consumer that does is run against the in-memory broker.
    "a component that states the declared contract is accepted"        -> "TopicContractsSuite",
    "a component that states another contract is refused, naming both" -> "TopicContractsSuite",
    "a component built against another schema is refused, naming both" -> "TopicContractsSuite",
    "a topic without a contract checks nothing"                        -> "TopicContractsSuite",
    "a published message carries the contract as its type"             -> "TopicContractsSuite"
  )

  When(
    "a member declares the topic {string} on {string} with {int} partitions and the contract {string} with its schema"
  ) { (t: String, p: String, n: Int, c: String) =>
    ok(declareContract(t, project(p), n, c)): Unit
  }

  Given("the topic {string} is declared on {string} with the contract {string} with its schema") {
    (t: String, p: String, c: String) =>
      ok(declareContract(t, project(p), 3, c))
      topicMade(project(p), t)
  }

  Given("the topic {string} is declared on {string} with the contract {string}") {
    (t: String, p: String, c: String) =>
      ok(declareContract(t, project(p), 3, c))
      topicMade(project(p), t)
  }

  Given("the topic {string} is declared on {string} with no contract") { (t: String, p: String) =>
    ok(declare(t, project(p)))
    topicMade(project(p), t)
  }

  Then("the topics of {string} show {string} with the contract {string}") {
    (p: String, t: String, c: String) =>
      assertEquals(topicsOf(project(p)).find(_.name == t).flatMap(_.contract).map(_.name), Some(c))
  }

  Then("the project holds the schema of {string}") { (c: String) =>
    val (p, t) = lastContract.getOrElse(fail("no contract was declared"))
    val run    = ok(ankka("projects", "topics", "schema", "get", t, "-p", p))
    assert(sameJson(run.out, Schema), s"the schema of $c: ${run.out}")
  }

  When("a member fetches the schema of {string} on {string}") { (t: String, p: String) =>
    lastRun = ankka("projects", "topics", "schema", "get", t, "-p", project(p))
  }

  Then("the member is given the schema of {string} as it was declared") { (c: String) =>
    assertEquals(lastRun.code, 0, lastRun.all)
    assert(sameJson(lastRun.out, Schema), s"the schema of $c: ${lastRun.out}")
  }

  // The cart sample's consumer publishes its checkout notices to CART_CHECKOUTS_TOPIC, stating
  // no contract: exactly the side the project's declaration refuses.
  Given("a service {string} whose consumer publishes to {string} stating no contract") {
    (s: String, t: String) =>
      descriptorOf = Some(Desc(a(s), project("money"), env = Map("CART_CHECKOUTS_TOPIC" -> t)))
  }

  When("a member applies the descriptor for {string}") { (s: String) =>
    val d = descriptorOf.getOrElse(fail(s"no descriptor for $s"))
    ok(apply(d)): Unit
  }

  Then("the consumer neither reads nor publishes") { () =>
    val d = descriptorOf.getOrElse(fail("no descriptor"))
    // Refused at start, the service never becomes Ready: its lifecycle says it failed.
    waitFor(360.seconds, s"${d.service} reporting its refusal") {
      statusOf(d.service, d.project).exists(s =>
        s.lifecycle == ServiceLifecycle.Failed && s.detail.exists(_.contains("contract"))
      )
    }
  }

  Then(
    "the status of {string} says the topic {string} is declared {string} and the consumer states none"
  ) { (s: String, t: String, c: String) =>
    val status = statusOf(a(s), project("money")).getOrElse(fail(s"no status for ${a(s)}"))
    val detail = status.detail.getOrElse("")
    assert(detail.contains(s"'$t' with no contract"), detail)
    assert(detail.contains(s"declares '$c'"), detail)
  }

  Then("the listing shows {string} with the contract {string}") { (t: String, c: String) =>
    assertEquals(listed.find(_.name == t).flatMap(_.contract).map(_.name), Some(c))
  }

/** features/broker/compaction.feature on k3s. */
class BrokerCompactionFeatures extends Feature037ClusterFeatures("broker", "compaction.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic already made is compacted when its declaration says so" -> "TopicProvisioningSuite",
    // What the broker keeps of a compacted topic is Kafka's own doing; the platform's part, the
    // declaration reaching the topic's config, is proved by the scenarios here.
    "a compacted topic keeps the last message under each key" -> "TopicProvisioningSuite"
  )

  private def compacted(p: String, t: String): Boolean =
    jsonPath("kafkatopic", "-n", Broker, s"$p.$t", "{.spec.config.cleanup\\.policy}") == "compact"

  When("a member declares the topic {string} on {string} with {int} partitions, compacted") {
    (t: String, p: String, n: Int) =>
      ok(declareWith(t, project(p), n, "--compacted")): Unit
  }

  Given("the topic {string} is declared on {string} with {int} partitions, not compacted") {
    (t: String, p: String, n: Int) =>
      ok(declare(t, project(p), n))
      topicMade(project(p), t)
  }

  Given("the topic {string} is declared on {string} compacted") { (t: String, p: String) =>
    ok(declareWith(t, project(p), 3, "--compacted"))
    topicMade(project(p), t)
  }

  Given("the topic {string} is declared on {string} not compacted") { (t: String, p: String) =>
    ok(declare(t, project(p), 3))
    topicMade(project(p), t)
  }

  Then("the installation's broker holds the topic {string} of {string} compacted") {
    (t: String, p: String) =>
      waitFor(180.seconds, s"${project(p)}.$t being compacted")(compacted(project(p), t))
  }

  Then("the status of the topic {string} on {string} is {string}") {
    (t: String, p: String, word: String) =>
      waitFor(180.seconds, s"the topic $t being $word") {
        topicsOf(project(p))
          .find(_.name == t)
          .flatMap(_.phase)
          .contains(ProjectEndpoint.topicPhrase(word))
      }
  }

  Then("the listing shows {string} compacted and {string} not") { (yes: String, no: String) =>
    assertEquals(listed.find(_.name == yes).map(_.compacted), Some(true))
    assertEquals(listed.find(_.name == no).map(_.compacted), Some(false))
  }

/** features/topics/brokers.feature on k3s. */
class DeclaredBrokersFeatures extends Feature037ClusterFeatures("topics", "brokers.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a component naming a broker the project has not declared is refused" -> "TopicContractsSuite",
    // Reading from a second broker needs one this cluster does not have; the credential's shape
    // and the per-broker connection are proved offline.
    "a topic source names a declared broker and reads from it" -> "KafkaCredentialSuite",
    "a consumer reads from a declared broker and publishes to the installation's" -> "KafkaCredentialSuite"
  )

  /** Where the outside broker would be: nothing here connects to it. */
  private val Outside = "kafka.legacy.svc:9094"

  private def secret(name: String, p: String, entries: Map[String, String]): Unit =
    ensureProject(p)
    ok(
      ankka(
        (Seq("projects", "secrets", "set", name) ++ entries
          .map((k, v) => s"$k=$v") ++ Seq("-p", p))*
      )
    ): Unit

  private def declareBroker(name: String, p: String, shape: String, secretName: String): Run =
    lastRun = ankka(
      "projects",
      "brokers",
      "set",
      name,
      "--bootstrap",
      Outside,
      "--shape",
      shape,
      "--secret",
      secretName,
      "-p",
      p
    )
    lastRun

  private def brokersOf(p: String): Vector[ProjectBroker] =
    readFromString[Vector[ProjectBroker]](
      ok(ankka("projects", "brokers", "list", "-p", p, "-o", "json")).out
    )

  Given("a broker outside the installation holding the topic {string}")((_: String) => ())

  Given("the project secret {string} on {string} holds {} for the outside broker") {
    (name: String, p: String, what: String) =>
      val entries =
        if what.contains("certificate") then
          Map("ca.crt"    -> "authority", "tls.crt"  -> "certificate", "tls.key" -> "key")
        else Map("ca.crt" -> "authority", "username" -> "ingest", "password"     -> "s3cret")
      secret(name, project(p), entries)
  }

  Given(
    "the project secret {string} on {string} holds a username and a password and no authority"
  ) { (name: String, p: String) =>
    // An earlier scenario may have declared a broker on this secret and given it an authority:
    // the broker goes first (a named secret keeps what its shape needs), then the authority.
    ankka("projects", "brokers", "unset", "legacy", "-p", project(p)): Unit
    ankka("projects", "secrets", "unset", name, "ca.crt", "-p", project(p)): Unit
    secret(name, project(p), Map("username" -> "ingest", "password" -> "s3cret"))
  }

  When(
    "a member declares the broker {string} on {string} with the address of the outside broker, the shape {string} and the project secret {string}"
  ) { (name: String, p: String, shape: String, secretName: String) =>
    // The feature writes the shape as a word ("SASL"); the declaration's values are lower-case.
    declareBroker(name, project(p), shape.toLowerCase, secretName): Unit
  }

  Then("the brokers of {string} show {string} with the shape {string}") {
    (p: String, name: String, shape: String) =>
      assertEquals(lastRun.code, 0, lastRun.all)
      assertEquals(brokersOf(project(p)).find(_.name == name).map(_.shape), Some(shape.toLowerCase))
      // And it goes as it came, through the same door.
      ok(ankka("projects", "brokers", "unset", name, "-p", project(p))): Unit
      assert(!brokersOf(project(p)).exists(_.name == name))
  }

  Then("the declaration is refused, naming what the secret lacks") { () =>
    assertNotEquals(lastRun.code, 0, lastRun.all)
    assert(lastRun.all.contains("lacks 'ca.crt'"), lastRun.all)
  }

  Given("the broker {string} is declared on {string}") { (name: String, p: String) =>
    secret(
      "legacy-credential",
      project(p),
      Map("ca.crt" -> "authority", "username" -> "ingest", "password" -> "s3cret")
    )
    ok(declareBroker(name, project(p), "sasl", "legacy-credential")): Unit
  }

  Given(
    "a ready service {string}, hosted as a process, whose consumer reads {string} from the broker {string}"
  ) { (s: String, _: String, _: String) =>
    // Only the Deployment is read: the process need not be a real one.
    val d =
      Desc(a(s), project("shop"), hosting = Some("process"), image = "registry.k8s.io/pause:3.9")
    ok(apply(d))
    waitFor(180.seconds, s"${a(s)}'s Deployment with the broker on its platform container") {
      environment(a(s), project("shop"))
        .get(a(s))
        .exists(_.contains("ANKKA_TOPIC_BROKER_LEGACY_BOOTSTRAP_SERVERS"))
    }
  }

  private var read: Option[(Map[String, Map[String, String]], Map[String, Vector[String]])] = None

  When("the process's environment and mounts are read") { () =>
    val deployment =
      k8s.apps().deployments().inNamespace(ns(project("shop"))).withName(a("intake")).get()
    val mounts = deployment.getSpec.getTemplate.getSpec.getContainers.asScala.map { c =>
      c.getName -> c.getVolumeMounts.asScala.map(_.getMountPath).toVector
    }.toMap
    read = Some((environment(a("intake"), project("shop")), mounts))
  }

  Then("neither holds the credential of the outside broker") { () =>
    val (env, mounts) = read.getOrElse(fail("nothing was read"))
    val process       = a("intake") + "-app"
    assert(
      !env(process).keys.exists(_.startsWith("ANKKA_TOPIC_BROKER_")),
      env(process).keys.toString
    )
    assert(
      !mounts(process).exists(_.startsWith("/var/run/secrets/ankka/brokers")),
      mounts(process).toString
    )
    // The platform's container has both: the credential reaches the program that connects.
    assertEquals(
      env(a("intake")).get("ANKKA_TOPIC_BROKER_LEGACY_SECRET_DIRECTORY"),
      Some("/var/run/secrets/ankka/brokers/legacy")
    )
    assert(
      mounts(a("intake")).contains("/var/run/secrets/ankka/brokers/legacy"),
      mounts(a("intake")).toString
    )
  }
