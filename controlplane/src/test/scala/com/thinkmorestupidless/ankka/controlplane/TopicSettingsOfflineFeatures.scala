package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.{ProjectEntity, TopicSettingsSweep}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.controlplane.domain.DeclareTopic
import com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectTopicEntry}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * A topic's settings against the real routes, entity, trigger and projector, with an in-memory
 * cluster (feature 043): every scenario of a feature whose outcome is the control plane's to say —
 * a refusal, what is recorded, what is written for the operator, the history. What only a broker
 * can show is left by name to the k3s suite that runs the same file against Strimzi.
 *
 * The installation's policy is a variable a scenario may change, which the endpoint reads per
 * request as the control plane reads its configuration once. Declarations go through the CLI's
 * `Main.run` as a member would, and through a raw request where the scenario is about what the
 * control plane itself refuses.
 */
abstract class TopicSettingsOfflineFeatures(feature: String)
    extends GherkinSuite(s"../features/broker/$feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val OwnerToken =
    identity.token(
      "owner",
      Some("owner@example.test"),
      name = Some("Olive Owner"),
      expiresIn = 2.hours
    )
  private lazy val MemberToken =
    identity.token(
      "member",
      Some("member@example.test"),
      name = Some("Mo Member"),
      expiresIn = 2.hours
    )

  protected def memberToken: String = MemberToken

  @volatile protected var policy: TopicPolicy = TopicPolicy.default

  protected val deployConfig          = DeployConfig.default
  protected lazy val cluster          = new FakeAnkkaServiceClient
  private lazy val projector          = ServiceProjector.withClient(deployConfig, cluster)
  protected var testKit: AnkkaTestKit = null
  private var url: String             = ""
  private val http        = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
  private lazy val config = Files.createTempFile("ankka-topic-settings", ".json")

  override def beforeAll(): Unit =
    Files.delete(config)
    sys.props("ankka.config") = config.toString
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        deployConfig,
        auth = Some(identity.config()),
        clock = identity.clock,
        topics = Some(projector),
        topicPolicy = policy
      )*
    )
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector, server)
    )
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(
      send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""), OwnerToken)._1,
      204
    )
    // A member who is not an owner: invited by email, the invitation claimed by their first listing.
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        Some("""{"email":"member@example.test","role":"member"}"""),
        OwnerToken
      )._1,
      204
    )
    send("GET", "/organizations", None, MemberToken): Unit

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    Files.deleteIfExists(config): Unit
    if testKit != null then
      testKit.stop()
      identity.stop()

  protected def send(
      method: String,
      path: String,
      body: Option[String],
      token: String
  ): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  // ── the scenario's state ──────────────────────────────────────────────────

  private var count: Int                              = 0
  private var projects: Map[String, String]           = Map.empty
  protected var result: (Int, String)                 = (0, "")
  protected var told: String                          = ""
  private var writtenBefore: Option[AnkkaProjectSpec] = None

  override def beforeEach(context: BeforeEach): Unit =
    count += 1
    projects = Map.empty
    result = (0, "")
    told = ""
    policy = TopicPolicy.default

  protected def project(name: String): String =
    projects.getOrElse(
      name, {
        val id = s"$name-$count"
        assertEquals(
          send(
            "POST",
            s"/projects/$id",
            Some(s"""{"name":"$name","organizationId":"acme"}"""),
            OwnerToken
          )._1,
          204
        )
        projects += name -> id
        id
      }
    )

  /** "90 days" as a member types it on the command line: `90d`; "everything" as it is. */
  protected def words(text: String): String =
    val Units = """(\d+)\s*(day|days|hour|hours|minute|minutes|year|years|GiB|MiB)""".r
    text.trim match
      case "everything"                   => "everything"
      case Units(n, "year" | "years")     => s"${n.toInt * 365}d"
      case Units(n, "day" | "days")       => s"${n}d"
      case Units(n, "hour" | "hours")     => s"${n}h"
      case Units(n, "minute" | "minutes") => s"${n}m"
      case Units(n, unit)                 => s"$n$unit"
      case other                          => other

  protected def listed(p: String): Vector[ProjectTopic] =
    val (status, body) = send("GET", s"/projects/${project(p)}/topics", None, OwnerToken)
    assertEquals(status, 200, body)
    readFromString[Vector[ProjectTopic]](body)

  protected def topic(p: String, t: String): ProjectTopic =
    listed(p).find(_.name == t).getOrElse(fail(s"'$p' declares no topic '$t'"))

  protected def history(p: String): Vector[ProjectHistoryEntry] =
    val (status, body) = send("GET", s"/projects/${project(p)}/history", None, OwnerToken)
    assertEquals(status, 200, body)
    readFromString[Vector[ProjectHistoryEntry]](body)

  /**
   * What the control plane wrote for the operator: the entry its pass renders the `KafkaTopic`
   * from.
   */
  protected def written(p: String, t: String): Option[ProjectTopicEntry] =
    cluster
      .project(deployConfig.namespaceFor(project(p)), project(p))
      .flatMap(_.topics.find(_.name == t))

  protected def writtenSoon(p: String, t: String)(
      check: ProjectTopicEntry => Boolean
  ): ProjectTopicEntry =
    val deadline = 30.seconds.fromNow
    while !written(p, t).exists(check) && deadline.hasTimeLeft() do Thread.sleep(200)
    written(p, t).filter(check).getOrElse(fail(s"what was written for '$t' is ${written(p, t)}"))

  /** A declaration through the CLI as `token`, the answer to its question read from `answer`. */
  protected def declare(
      p: String,
      t: String,
      flags: Seq[String],
      token: String = OwnerToken,
      answer: String = ""
  ): Unit =
    val isNew = !listed(p).exists(_.name == t)
    val partitions =
      if isNew && !flags.contains("--partitions") then Seq("--partitions", "3") else Seq.empty
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Console.withIn(ByteArrayInputStream(answer.getBytes(UTF_8))) {
      Console.withOut(PrintStream(out, true, UTF_8)) {
        Main.run(
          Seq("projects", "topics", "set", t) ++ partitions ++ flags ++
            Seq("-p", project(p), "--url", url, "--token", token),
          PrintStream(out, true, UTF_8),
          PrintStream(err, true, UTF_8)
        )
      }
    }
    told = out.toString(UTF_8)
    result = (code, err.toString(UTF_8))

  protected def declareRaw(p: String, t: String, body: String, token: String = OwnerToken): Unit =
    result = send("PUT", s"/projects/${project(p)}/topics/$t", Some(body), token)

  protected def setting(name: String, value: String): Seq[String] = name match
    case "retention time"         => Seq("--retention", words(value))
    case "retention size"         => Seq("--retention-size", words(value))
    case "cleanup policy"         => Seq("--cleanup", value)
    case "tombstone window"       => Seq("--tombstone-window", words(value))
    case "minimum compaction lag" => Seq("--min-compaction-lag", words(value))
    case other                    => fail(s"no setting '$other'")

  protected def refused(): Unit =
    assert(result._1 != 0 && result._1 != 204, s"it was accepted: $result")

  /** What was written for the operator once the trigger has written what was declared so far. */
  protected def remember(p: String): Unit =
    listed(p).foreach(t => writtenSoon(p, t.name)(_.retentionMs.isDefined): Unit)
    writtenBefore = cluster.project(deployConfig.namespaceFor(project(p)), project(p))
  protected def unchangedSince(p: String): Unit =
    Thread.sleep(1000)
    assertEquals(cluster.project(deployConfig.namespaceFor(project(p)), project(p)), writtenBefore)

  // ── steps every file shares ───────────────────────────────────────────────

  Given("an installation with a broker")(() => ())
  Given("a project {string}")((p: String) => project(p): Unit)

  Given("the topic {string} is declared on {string} with the {word} {word} {string}") {
    (t: String, p: String, a: String, b: String, v: String) =>
      declare(p, t, setting(s"$a $b", v))
      assertEquals(result._1, 0, result._2)
  }

  Then("the member is refused")(() => refused())
  Then("the owner is refused")(() => refused())

/** `features/broker/retention.feature`, but for what only a broker can show. */
class TopicRetentionOfflineFeature extends TopicSettingsOfflineFeatures("retention.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic keeps a message until it is older than its retention time or its partition is larger than its retention size" ->
      "BrokerRetentionFeatures"
  )

  Given("the installation's default retention time is {string}") { (v: String) =>
    policy = policy.copy(defaults =
      policy.defaults.copy(retention = RetentionTime.parse(words(v)).toOption.get)
    )
  }
  Given("the installation's longest retention time is {string}") { (v: String) =>
    policy = policy.copy(bounds =
      policy.bounds.copy(longestRetention = RetentionTime.parse(words(v)).toOption.get)
    )
  }
  Given("the installation sets no longest retention time") { () =>
    policy = policy.copy(bounds = policy.bounds.copy(longestRetention = RetentionTime.Everything))
  }
  Given("the topic {string} is declared on {string} with partitions alone") {
    (t: String, p: String) =>
      declare(p, t, Seq("--partitions", "3"))
      assertEquals(result._1, 0, result._2)
  }
  Given("the topic {string} was declared on {string} before a declaration could say its settings") {
    (t: String, p: String) =>
      // What a node from before settings sends: partitions, and nothing of settings.
      testKit.componentClient
        .forEventSourcedEntity(EntityId(project(p)))
        .call(ProjectEntity.declareTopic)
        .invoke(DeclareTopic(t, Some(3))): Unit
  }

  When("a member declares the topic {string} on {string} with {int} partitions") {
    (t: String, p: String, n: Int) => declare(p, t, Seq("--partitions", n.toString))
  }
  When(
    "a member declares the topic {string} on {string} with the retention time {string} and the retention size {string}"
  ) { (t: String, p: String, r: String, s: String) =>
    declare(p, t, setting("retention time", r) ++ setting("retention size", s))
  }
  When("a member declares the topic {string} on {string} with the retention time {string}") {
    (t: String, p: String, r: String) =>
      declareRaw(p, t, s"""{"retention":"${words(r)}"}""")
  }
  When("a member declares the topic {string} on {string} to keep everything") {
    (t: String, p: String) =>
      declare(p, t, Seq("--retention", "everything"))
  }
  When("the installation's default retention time is changed to {string}") { (v: String) =>
    policy = policy.copy(defaults =
      policy.defaults.copy(retention = RetentionTime.parse(words(v)).toOption.get)
    )
  }
  When("the control plane starts upgraded") { () =>
    // The sweep lists projects from a projection, which may not yet hold one made a moment ago.
    val sweep    = TopicSettingsSweep(policy)
    val deadline = 30.seconds.fromNow
    while !listed("money").forall(_.settings.isDefined) && deadline.hasTimeLeft() do
      sweep.run(testKit.service): Unit
      Thread.sleep(200)
  }

  Then(
    "the declaration of {string} records the retention time {string}, the cleanup policy {string} and the installation's default copies"
  ) { (t: String, r: String, c: String) =>
    val s = topic("money", t).settings.getOrElse(fail("no settings"))
    assertEquals((s.retention, s.cleanup, s.copies), (words(r), c, Some(policy.defaults.copies)))
  }
  Then(
    "the declaration of {string} records the retention time {string}, the cleanup policy {string}, and the installation's default tombstone window, minimum compaction lag and maximum compaction lag"
  ) { (t: String, r: String, c: String) =>
    val s = topic("money", t).settings.getOrElse(fail("no settings"))
    assertEquals(
      (s.retention, s.cleanup, s.tombstoneWindow, s.minCompactionLag, s.maxCompactionLag),
      (
        words(r),
        c,
        policy.defaults.tombstoneWindow.text,
        policy.defaults.minCompactionLag.text,
        policy.defaults.maxCompactionLag.text
      )
    )
  }
  Then("the status of {string} shows each setting, marked as the installation's default") {
    (t: String) =>
      val s        = topic("money", t).settings.getOrElse(fail("no settings"))
      val unstated = if s.copies.isEmpty then Set("copies", "minInSync") else Set.empty[String]
      assertEquals(s.defaulted.toSet, Setting.values.map(_.wire).toSet -- unstated)
  }
  Then("the installation's broker holds each setting on the topic {string} itself") { (t: String) =>
    val e = writtenSoon("money", t)(_.retentionMs.isDefined)
    assert(
      Seq(
        e.retentionMs,
        e.retentionBytes,
        e.deleteRetentionMs,
        e.minCompactionLagMs,
        e.maxCompactionLagMs
      ).forall(_.isDefined) &&
        e.cleanupPolicy.isDefined,
      e.toString
    )
  }
  Then("the status of {string} shows the retention time {string} and the retention size {string}") {
    (t: String, r: String, s: String) =>
      val settings = topic("money", t).settings.getOrElse(fail("no settings"))
      assertEquals((settings.retention, settings.retentionSize), (words(r), words(s)))
  }
  Then("the refusal names the bound") { () =>
    assertEquals(result._1, 400, result._2)
    assert(result._2.contains("longer than the installation's longest"), result._2)
  }
  Then("nothing is made on the installation's broker") { () =>
    Thread.sleep(1000)
    assertEquals(written("money", "transactions"), None)
  }
  Then("{string} declares the topic {string}") { (p: String, t: String) =>
    assertEquals(result._1, 0, result._2)
    topic(p, t): Unit
  }
  Then("the status of {string} says that it keeps everything") { (t: String) =>
    assertEquals(topic("money", t).settings.map(_.retention), Some("everything"))
  }
  Then("the installation's broker keeps a message on {string} for {string}") {
    (t: String, r: String) =>
      val ms = RetentionTime.parse(words(r)).toOption.get.kafka
      writtenSoon("money", t)(_.retentionMs.contains(ms)): Unit
  }
  Then("the status of {string} shows the retention time {string}") { (t: String, r: String) =>
    assertEquals(topic("money", t).settings.map(_.retention), Some(words(r)))
  }
  Then("{string} records that the platform filled them, and no member") { (p: String) =>
    val fills = history(p).filter(_.kind == "topic-filled")
    assert(fills.nonEmpty && fills.forall(_.actor.isEmpty), history(p).toString)
  }
  Then("the copies of {string} are what the installation's broker holds") { (t: String) =>
    val s = topic("money", t).settings.getOrElse(fail("no settings"))
    assertEquals((s.copies, s.minInSync), (None, None))
    assertEquals(writtenSoon("money", t)(_.retentionMs.isDefined).replicas, None)
  }

/**
 * `features/broker/copies.feature`'s rule that copies are fixed; the rest needs three broker nodes.
 */
class TopicCopiesOfflineFeature extends TopicSettingsOfflineFeatures("copies.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "an installation installed with three broker nodes sets every setting that counts copies for three" -> "BrokerShapeSuite and BrokerCopiesFeatures",
    "a topic with more copies than the broker has broker nodes is reported failed by the operator" -> "BrokerCopiesFeatures",
    "stopping one broker node of three loses no acknowledged message" -> "BrokerCopiesFeatures",
    "a publication waits for the topic's minimum in-sync copies"      -> "BrokerCopiesFeatures"
  )

  // The operator's own decision over a broker of one node holding one copy, reported where the
  // operator reports it, and read through the listing as a member reads it.
  Given("an installation whose broker has one broker node")(() => ())
  Given("the topic {string} is declared on {string}") { (t: String, p: String) =>
    declare(p, t, Seq.empty)
    assertEquals(result._1, 0, result._2)
  }
  When("a member reads the topics of {string}") { (p: String) =>
    val id = project(p)
    val spec = {
      val deadline = 30.seconds.fromNow
      while cluster.project(deployConfig.namespaceFor(id), id).forall(_.topics.isEmpty) && deadline
          .hasTimeLeft()
      do Thread.sleep(200)
      cluster.project(deployConfig.namespaceFor(id), id).getOrElse(fail("nothing was written"))
    }
    val held = spec.topics.map { e =>
      val config = com.thinkmorestupidless.ankka.operator.StrimziRendering
        .kafkaConfig(e)
        .getOrElse(Map.empty)
        .map((k, v) => k -> v.toString)
      s"$id.${e.name}" -> com.thinkmorestupidless.ankka.operator.TopicState(
        com.thinkmorestupidless.ankka.operator
          .StrimziObjectState(exists = true, ready = Some(true)),
        Some(e.partitions),
        Some(e.compacted),
        Some(1),
        Some(config)
      )
    }.toMap
    cluster.reportProject(
      deployConfig.namespaceFor(id),
      id,
      com.thinkmorestupidless.ankka.operator.TopicProvisioning
        .status(
          spec,
          Some(com.thinkmorestupidless.ankka.operator.BrokerStack.settings),
          held,
          Some(1)
        )
    )
  }
  Then("the topic {string} says that it has a single copy") { (t: String) =>
    val row = topic("money", t)
    assertEquals(row.copiesHeld, Some(1))
    assertEquals(row.brokerNodes, Some(1))
    assert(row.detail.exists(_.contains("single copy")), row.toString)
  }

  Given("an installation whose broker has three broker nodes")(() => ())
  Given(
    "the topic {string} is declared on {string} with {int} copies and {int} minimum in-sync copies"
  ) { (t: String, p: String, c: Int, m: Int) =>
    declare(p, t, Seq("--copies", c.toString, "--min-in-sync", m.toString, "--retention", "90d"))
    assertEquals(result._1, 0, result._2)
  }
  When(
    "a member declares the topic {string} on {string} with {int} copies and {int} minimum in-sync copies"
  ) { (t: String, p: String, c: Int, m: Int) =>
    // With another setting changed beside them, which must not be applied either.
    declareRaw(p, t, s"""{"copies":$c,"minInSync":$m,"retention":"180d"}""")
  }
  Then(
    "the refusal names that copies and minimum in-sync copies are fixed when a topic is declared"
  ) { () =>
    assertEquals(result._1, 409, result._2)
    assert(
      result._2.contains("copies and minimum in-sync copies are fixed when a topic is declared"),
      result._2
    )
  }
  Then("nothing else in the declaration is applied") { () =>
    assertEquals(topic("money", "transactions").settings.map(_.retention), Some("90d"))
  }

/** `features/broker/changing.feature`: who may change what, and what is recorded. */
class TopicChangingOfflineFeature extends TopicSettingsOfflineFeatures("changing.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic declared again with a longer retention time is changed on the broker in place" -> "BrokerChangingFeatures",
    "a topic declared again with the cleanup policy \"compact\" is compacted from then on" -> "BrokerChangingFeatures",
    "a running service learns a topic's new cleanup policy from the broker without a restart" -> "BrokerChangingFeatures"
  )

  // The service's part is the broker suite's; here the topic is the project's alone.
  Given("a deployed service {string} in {string} that publishes to and reads the topic {string}") {
    (_: String, _: String, _: String) => ()
  }

  When(
    "a member who is not an owner declares the topic {string} on {string} with the {word} {word} {string}"
  ) { (t: String, p: String, a: String, b: String, v: String) =>
    declare(p, t, setting(s"$a $b", v), token = MemberTokenFor)
  }
  When("an owner declares the topic {string} on {string} with the retention time {string}") {
    (t: String, p: String, r: String) =>
      remember(p)
      declare(p, t, setting("retention time", r), answer = "y\n")
  }
  When(
    "an owner declares the topic {string} on {string} with the retention time {string} without stating what it accepts removing"
  ) { (t: String, p: String, r: String) =>
    declareRaw(p, t, s"""{"retention":"${words(r)}"}""")
  }
  When("a member declares the topic {string} on {string} with the retention time {string}") {
    (t: String, p: String, r: String) =>
      remember(p)
      declare(p, t, setting("retention time", r), token = MemberTokenFor)
  }

  Then("the refusal names that only an owner may remove messages") { () =>
    assert(result._2.contains("owner role required: this declaration removes"), result._2)
  }
  Then(
    "the owner is told, before the declaration is sent, that messages older than {string} are removed and gone"
  ) { (r: String) =>
    assert(
      told.contains(
        s"This declaration removes messages older than ${words(r)}, and they are gone."
      ),
      s"told <$told>, answered $result"
    )
  }
  Then(
    "the declaration is sent only once the owner confirms, stating what the owner accepts removing"
  ) { () =>
    assertEquals(result._1, 0, result._2)
  }
  Then("{string} records the owner, the retention time {string} and the retention time {string}") {
    (p: String, from: String, to: String) =>
      val entry = history(p).headOption.getOrElse(fail("no history"))
      assertEquals(entry.actor.map(_.subject), Some("owner"))
      assertEquals(entry.changes, Vector(SettingChange(Setting.Retention, words(from), words(to))))
  }
  Then("the refusal names that messages older than {string} would be removed and gone") {
    (r: String) =>
      assertEquals(result._1, 400, result._2)
      assert(
        result._2.contains(s"this declaration removes messages older than ${words(r)}"),
        result._2
      )
  }
  Then("the installation's broker keeps a message on {string} for {string}") {
    (t: String, r: String) =>
      assertEquals(topic("money", t).settings.map(_.retention), Some(words(r)))
  }
  Then("{string} declares the topic {string} with the retention time {string}") {
    (p: String, t: String, r: String) =>
      assertEquals(result._1, 0, result._2)
      assertEquals(topic(p, t).settings.map(_.retention), Some(words(r)))
  }
  Then("{string} records nothing") { (p: String) =>
    assertEquals(result._1, 0, result._2)
    assertEquals(history(p).size, 1, history(p).toString)
  }
  Then("nothing is written to the installation's broker")(() => unchangedSince("money"))

  private def MemberTokenFor: String = memberToken
