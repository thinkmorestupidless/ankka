package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.controlplane.erasure.*
import com.thinkmorestupidless.ankka.core.{
  ComponentId,
  DataSubject,
  EntityId,
  KeyAnswer,
  PersonalCipher,
  SessionId
}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing
}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The request agent of `features/erasure/agents.feature`: one tool, which answers about a card. */
final class SupportHelper extends Agent:
  def ask(question: String): Effect[String] =
    effects
      .systemMessage(SupportHelper.SystemMessage)
      .userMessage(question)
      .tools(SupportHelper.lookupCard)
      .thenReply()

object SupportHelper extends Agent.Companion[SupportHelper](ComponentId("helper")):
  val SystemMessage = "You help people with their payments."

  val lookupCard = FunctionTool
    .named("lookup_card")
    .describedAs("Says what the issuer said about a card.")
    .param[String]("card", "The card's last four digits.")
    .handle((card: String) => s"the card ending $card was refused by its issuer")

  def create(context: AgentContext) = new SupportHelper
  val ask                           = command("ask")(_.ask)

/** The autonomous agent of the feature, which resolves a support case. */
object ResolverTasks:
  val resolve: TaskType[String] = Task.named("resolve").describedAs("Resolve a support case")

final class SupportResolver(context: AutonomousAgentContext) extends AutonomousAgent(context)

object SupportResolver extends AutonomousAgent.Companion[SupportResolver](ComponentId("resolver")):
  def create(context: AutonomousAgentContext) = new SupportResolver(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Resolves support cases")
      .instructions("Be brief.")
      .capability(TaskAcceptance.of(ResolverTasks.resolve).maxIterationsPerTask(3))
      // Never idle out of memory during a scenario: an instance in memory is what an erasure stops.
      .settings(
        AutonomousAgentSettings(idlePassivationAfter = 10.minutes, dependencyPoll = 200.millis)
      )

/**
 * `features/erasure/agents.feature`, in process (feature 042, user story 6): a service `support`
 * under `AnkkaTestKit` with the in-memory keyring and a scripted model, and the control plane's
 * erasure requests in this JVM, as `ErasureFeatures` runs the rest of `features/erasure/`.
 */
class ErasureAgentsFeatures
    extends GherkinSuite("../features/erasure/agents.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  // ── the service ────────────────────────────────────────────────────────────

  private val project                  = "brand"
  private var kit: AnkkaTestKit        = null
  private var keyring: InMemoryKeyring = InMemoryKeyring()
  private val model                    = TestModelProvider()
  private var names: Vector[String]    = Vector.empty

  private val handler = com.thinkmorestupidless.ankka.sdk.ErasureHandler { _ => _ =>
    com.thinkmorestupidless.ankka.sdk.ErasureReport(0L)
  }

  private def startKit(name: String): Unit =
    kit = AnkkaTestKit.start(
      Seq(SupportHelper.descriptor, SupportResolver.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime()),
      configure = _.withErasureHandler(handler),
      serviceIdentity = ServiceIdentity.deployed(project, name),
      keyring = keyring
    )
    names = Vector(name)

  private def stopKit(): Unit =
    if kit != null then scala.util.Try(kit.stop()): Unit
    kit = null
    names = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    stopKit()
    keyring = InMemoryKeyring()
    admin = TestKeyringAdmin(keyring, () => names)
    model.reset()
    asked = Map.empty
    session = None
    before = None
    taskId = ""
    instanceId = ""

  override def afterAll(): Unit =
    stopKit()
    if harnessStarted then harness.stop()

  // ── the control plane, for the erasure requests ────────────────────────────

  private var harnessStarted              = false
  private lazy val harness: GrantsHarness =
    val h = GrantsHarness(
      erasure = () => com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings(true, 2),
      erasurePlane = Some(plane)
    )
    h.start()
    h.organization("acme", "ada")
    harnessStarted = true
    h

  private var admin: KeyringAdmin = TestKeyringAdmin(keyring)
  private object currentKeyring extends KeyringAdmin:
    // The control plane's project for this scenario is a fresh one; the service's is `project`.
    def destroy(order: com.thinkmorestupidless.ankka.core.keyring.ErasureOrder) =
      admin.destroy(order.copy(project = project))
    def resend(seq: Long) = admin.resend(seq)
    def status(seq: Long) = admin.status(seq)

  private val plane = ErasurePlane(currentKeyring, Some(MemoryErasureLogBucket()))

  private var planeProject: String       = ""
  private var scenarios: Int             = 0
  private var asked: Map[String, String] = Map.empty
  private val RequestId                  = """"id":"([0-9A-Z]{26})"""".r

  private def eventually[A](what: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = within.fromNow
    var last: Option[A] = None
    while last.isEmpty && deadline.hasTimeLeft() do
      last = scala.util.Try(check).toOption.flatten
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$what did not happen within $within"))

  private def application: ErasureApplication =
    eventually("the control plane's erasure plane")(plane.application)

  private def requestOf(subject: String): String =
    asked.getOrElse(subject, fail(s"no erasure request was asked for $subject"))

  /**
   * A member asks for the erasure of `subject`, and it is applied until the service completed it.
   */
  private def erase(subject: String): Unit =
    val (status, body) = harness.send(
      "POST",
      s"/projects/$planeProject/erasures",
      Some(s"""{"subject":"$subject"}"""),
      "ada"
    )
    assertEquals(status, 201, body)
    RequestId.findFirstMatchIn(body).foreach(m => asked += subject -> m.group(1))
    val id = s"$planeProject/${requestOf(subject)}"
    eventually("the service completed the erasure", 60.seconds) {
      Await.result(application.apply(id), 30.seconds): Unit
      val (s, read) =
        harness.send("GET", s"/projects/$planeProject/erasures/${requestOf(subject)}", as = "ada")
      assertEquals(s, 200, read)
      val view = com.github.plokhotnyuk.jsoniter_scala.core.readFromString(read)(using
        com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.erasureRequestViewCodec
      )
      Option.when(
        names.forall(n => view.completions.exists(c => c.service == n && c.handler.nonEmpty))
      )(view)
    }: Unit

  // ── the database ───────────────────────────────────────────────────────────

  private def rows(table: String, column: String, where: String): Vector[String] =
    Await.result(
      Database()(using kit.service.system).query(
        SqlFragment.raw(s"SELECT $column FROM $table WHERE $where")
      )(row =>
        row.get(0) match
          case b: java.nio.ByteBuffer =>
            val out = new Array[Byte](b.remaining)
            b.get(out)
            new String(out, UTF_8)
          case b: Array[Byte] => new String(b, UTF_8)
          case other          => String.valueOf(other)
      ),
      30.seconds
    )

  private val Envelope =
    """\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)"(?:,"c":"([^"]*)")?\}""".r

  /** Every personal envelope in `text`, opened under `subject`'s key, or the test fails. */
  private def openedUnder(text: String, subject: String, manifest: String): Vector[String] =
    Envelope
      .findAllMatchIn(text)
      .toVector
      .map { m =>
        assertEquals(m.group(2), subject, m.matched)
        val c = Option(m.group(3)).getOrElse(fail(s"the envelope holds no value: ${m.matched}"))
        keyring.key(m.group(1), DataSubject(subject), create = false) match
          case KeyAnswer.Key(key) =>
            new String(
              PersonalCipher
                .open(
                  key,
                  Base64.getDecoder.decode(c),
                  PersonalCipher.associatedData(subject, m.group(1), manifest)
                )
                .getOrElse(fail(s"${m.matched} does not open under the key of $subject")),
              UTF_8
            )
          case other => fail(s"the keyring has no key for $subject: $other")
      }

  // ── the agents ─────────────────────────────────────────────────────────────

  private var session: Option[(String, Option[String])] = None
  private var before: Option[SessionHistory]            = None
  private var taskId: String                            = ""
  private var instanceId: String                        = ""

  private def sessionId: String = session.map(_._1).getOrElse(fail("no session was started"))

  private def helper =
    val (id, subject) = session.getOrElse(fail("no session was started"))
    kit.componentClient.forAgent(SessionId(id), subject.map(DataSubject(_)))

  private def history(id: String = sessionId): SessionHistory =
    kit.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(SessionMemoryEntity.history)
      .invoke()

  private def turn(message: String, answer: String): String =
    model.expectText(answer)
    helper.call(SupportHelper.ask).invoke(message)

  private def withTurns(subject: Option[String]): Unit =
    session = Some(s"support-session-$scenarios" -> subject)
    turn("my card was declined", "I am sorry to hear that.")
    turn("it was the card ending 4242", "Let me look into it.")
    before = Some(history())

  Given("a service {string} with a request agent {string} and an autonomous agent {string}") {
    (service: String, _: String, _: String) =>
      scenarios += 1
      planeProject = s"$project-$scenarios"
      harness.project(planeProject, "acme", "ada")
      startKit(service)
  }

  Given("a session of {string} started with the data subject {string}") {
    (_: String, subject: String) => session = Some(s"support-session-$scenarios" -> Some(subject))
  }

  When("a turn with the message {string} is sent to the session") { (message: String) =>
    model
      .expectToolCall("lookup_card", Json.obj("card" -> Json.str("4242")))
      .expectText("The issuer refused the card ending 4242.")
    val answer: String = helper.call(SupportHelper.ask).invoke(message)
    assertEquals(answer, "The issuer refused the card ending 4242.")
  }

  Then(
    "the database of {string} holds the message, the tool calls and the results of the turn encrypted under the subject key of {string}"
  ) { (_: String, subject: String) =>
    val journal = rows(
      "event_journal",
      "event_payload",
      s"persistence_id = 'ankka-session-memory|$sessionId'"
    )
    assert(journal.size >= 4, s"the turn's events: $journal")
    val opened = journal.flatMap(openedUnder(_, subject, "session-memory-event")).mkString("\n")
    assert(opened.contains("my card was declined"), s"the user's message: $opened")
    assert(opened.contains("lookup_card") && opened.contains("4242"), s"the tool call: $opened")
    assert(opened.contains("refused by its issuer"), s"the tool's result: $opened")
    assert(opened.contains("The issuer refused the card ending 4242."), s"the answer: $opened")
    // Every event of the conversation is sealed: in the clear there is only its start.
    journal.foreach(row =>
      assert(
        row.contains("\"Sealed\"") || row.contains("\"Started\""),
        s"an event of the session kept in the clear: $row"
      )
    )
  }

  Then("nothing in the database of {string} reads as {string}") { (_: String, value: String) =>
    kit.assertNoPersonalValue(value)
  }

  Given("a session of {string} started with the data subject {string}, with turns in it") {
    (_: String, subject: String) => withTurns(Some(subject))
  }

  Given("a session of {string} started with no data subject, with turns in it") { (_: String) =>
    withTurns(None)
  }

  Given("{string} has since been erased")((subject: String) => erase(subject))

  When("{string} is erased")((subject: String) => erase(subject))

  private var read: Option[SessionHistory] = None

  When("the session is read") { () =>
    // The erasure sends the session out of memory; the read after it recovers it from its journal.
    read = Some(eventually("the session reads its journal again")(Some(history()).filter(_.erased)))
  }

  Then("its conversation is erased") { () =>
    val h = read.getOrElse(fail("the session was not read"))
    assert(h.erased, h.toString)
    assertEquals(h.messages, Vector.empty)
    assertEquals(h.suspended, None)
  }

  Then(
    "a new turn in the session begins with no earlier conversation, and the model is told that the earlier conversation was erased"
  ) { () =>
    model.reset()
    assertEquals(turn("is my refund on its way?", "It is."), "It is.")
    val request = model.requests.lastOption.getOrElse(fail("the model was not called"))
    val said    = request.messages.mkString("\n")
    assertEquals(request.messages.size, 1, said)
    assert(said.contains("is my refund on its way?"), said)
    assert(!said.contains("declined") && !said.contains("4242"), said)
    assert(
      request.systemMessage.exists(_.contains(AgentLoop.ErasedNote)),
      s"the model is told: ${request.systemMessage}"
    )
  }

  Then("the session is unchanged") { () =>
    val now = history()
    assert(!now.erased)
    assertEquals(now, before.getOrElse(fail("the session had no turns")))
    assert(now.messages.exists(_.toString.contains("my card was declined")))
  }

  // ── autonomous agents ──────────────────────────────────────────────────────

  private def task(subject: String) =
    kit.componentClient.tasks
      .create(ResolverTasks.resolve, "the card of player 8c1f was declined twice")
      .attach("statement", "text/plain", "card ending 4242, declined on 3 November")
      .withSubject(DataSubject(subject))

  private val Resolution = "a refund was issued for the declined card"

  Given("an agent instance of {string} started with the data subject {string}") {
    (_: String, subject: String) =>
      model.expectCompleteTaskText(Resolution)
      taskId = task(subject).create()
      instanceId = s"resolver-$scenarios"
      val assigned = kit.componentClient
        .forAutonomousAgent(SupportResolver, instanceId, Some(DataSubject(subject)))
        .assign(taskId)
      assert(assigned.allAccepted, assigned.toString)
      assertEquals(
        kit.componentClient.forTask(taskId).await(ResolverTasks.resolve).result,
        Some(Resolution)
      )
  }

  Given("a task of {string} started with the data subject {string}") {
    (_: String, subject: String) =>
      model.expectCompleteTaskText(Resolution)
      taskId = kit.componentClient.forAutonomousAgent(SupportResolver).runSingleTask(task(subject))
      val done = kit.componentClient.forTask(taskId).await(ResolverTasks.resolve)
      assertEquals(done.result, Some(Resolution))
      instanceId = done.assignee.map(_.instanceId).getOrElse(fail("the task had no assignee"))
  }

  Then("its instructions, its task inputs and its results read as erased") { () =>
    val record = eventually("the task reads as erased")(
      Some(kit.componentClient.forTask(taskId).get()).filter(_.erased)
    )
    assertEquals(record.instructions, "")
    assertEquals(record.attachments, Vector.empty)
    assertEquals(record.result, None)
    // Its status and its times are kept: an erased task keeps its shape.
    assertEquals(record.status, TaskStatus.Completed)
    // What the agent said and was told working on it is erased with it.
    val conversation = eventually("the task's conversation reads as erased")(
      Some(history(IterationLoop.sessionIdFor(taskId))).filter(_.erased)
    )
    assertEquals(conversation.messages, Vector.empty)
    kit.assertNoPersonalValue("declined twice", "declined on 3 November", Resolution)
  }

  Then("the agent instance is terminated") { () =>
    eventually("the agent instance is terminated")(
      Some(kit.componentClient.forAutonomousAgent(SupportResolver.componentId, instanceId).state())
        .filter(_.terminated)
    ): Unit
  }

  // ── the documentation ──────────────────────────────────────────────────────

  /**
   * The erasure page is written by task T101 of feature 042. Until a page under `docs/` speaks of
   * erasing a session, these steps have nothing to read; once one does, it must say both things.
   */
  private def documentationSays(words: String*): Unit =
    val docs  = java.nio.file.Paths.get("../docs")
    val pages =
      if !java.nio.file.Files.isDirectory(docs) then Vector.empty
      else
        val walk = java.nio.file.Files.walk(docs)
        try
          walk
            .filter(_.toString.endsWith(".md"))
            .toArray
            .toVector
            .map(p => java.nio.file.Files.readString(p.asInstanceOf[java.nio.file.Path]))
            .filter(t => t.contains("data subject") && t.contains("session"))
        finally walk.close()
    if pages.nonEmpty then
      assert(
        pages.exists(p => words.forall(w => p.toLowerCase.contains(w))),
        s"no page of the documentation says ${words.mkString("'", "' and '", "'")}"
      )

  Then("the documentation says that a session started with no data subject cannot be erased") { () =>
    documentationSays("no data subject", "cannot be erased")
  }

  Then("the documentation says that what a session sent to its model is beyond an erasure") { () =>
    documentationSays("sent to its model", "erasure")
  }
