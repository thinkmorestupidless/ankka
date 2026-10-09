package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.autonomous.{Answerer, Tasks}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing,
  WeatherAgent
}

import java.nio.file.{Files, Path}
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

/**
 * `features/erasure/agents.feature`: a service with a request agent (the weather agent stands for
 * `helper`) and an autonomous agent (the answerer stands for `resolver`), a scripted model, and its
 * own keyring. Every scenario works on a data subject of its own.
 */
class AgentsFeatures
    extends GherkinSuite("../../features/erasure/agents.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private val model             = TestModelProvider()
  private val keyring           = InMemoryKeyring()
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(WeatherAgent.descriptor, Answerer.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime()),
      keyring = Some(keyring)
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    Answerer.calls.clear()
    Answerer.gate.set(CountDownLatch(0))

  private def suffix                 = Integer.toHexString(scenarioId.hashCode)
  private def subject(named: String) = s"$named-$suffix"
  private def sessionId              = s"session-$suffix"
  private def client                 = kit.componentClient
  private def agent(tagged: Option[String]) =
    val calls = client.forAgent(SessionId(sessionId))
    tagged.fold(calls)(calls.withSubject)
  private def history =
    client.forSessionMemory(SessionId(sessionId)).call(SessionMemoryEntity.history).invoke()

  private def turn(tagged: Option[String], message: String, answer: String): String =
    model.expectText(answer)
    agent(tagged).call(WeatherAgent.ask).invoke(message)

  private def journalOf(persistenceId: String): String =
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    scala.concurrent.Await
      .result(
        Database().query(
          SqlFragment.raw(
            s"SELECT encode(event_payload, 'escape') FROM event_journal WHERE persistence_id = '$persistenceId'"
          )
        )(r => r.get(0, classOf[String])),
        10.seconds
      )
      .mkString("\n")

  Given("a service {string} with a request agent {string} and an autonomous agent {string}") {
    (_: String, _: String, _: String) => ()
  }

  // ── A tagged session ──

  Given("a session of {string} started with the data subject {string}") {
    (_: String, named: String) => agent(Some(subject(named))): Unit
  }
  When("a turn with the message {string} is sent to the session") { (message: String) =>
    turn(Some(subject("player/8c1f")), message, "I am sorry to hear that.")
  }
  Then(
    "the database of {string} holds the message, the tool calls and the results of the turn encrypted under the subject key of {string}"
  ) { (_: String, named: String) =>
    val journal = journalOf(s"ankka-session-memory|$sessionId")
    assert(journal.contains(s""""subject":"${subject(named)}""""), journal)
    assert(journal.contains("\"data\":"), journal)
  }
  Then("nothing in the database of {string} reads as {string}") { (_: String, value: String) =>
    kit.assertNoPersonalValue(value)
  }

  // ── An erased session ──

  Given("a session of {string} started with the data subject {string}, with turns in it") {
    (_: String, named: String) =>
      turn(Some(subject(named)), "my card was declined", "I am sorry to hear that.")
      turn(Some(subject(named)), "it was the blue one", "Noted.")
  }
  Given("{string} has since been erased") { (named: String) =>
    kit.erase(subject(named)): Unit
  }
  When("the session is read")(() => ())
  Then("its conversation is erased") { () =>
    val read = history
    assert(read.erased, read.toString)
    assertEquals(read.messages.map(_.toString).count(_.contains("blue one")), 0)
  }
  Then(
    "a new turn in the session begins with no earlier conversation, and the model is told that the earlier conversation was erased"
  ) { () =>
    val answer = turn(None, "hello again", "Hello.")
    assertEquals(answer, "Hello.")
    val told = model.lastRequest.messages.map(_.toString).mkString("\n")
    assert(told.contains(SessionMemoryEntity.ErasedNote), told)
    assert(!told.contains("card was declined") && !told.contains("blue one"), told)
  }

  // ── A tagged task and the instance working on it ──

  private var taskId     = ""
  private val instanceId = "resolver-1"
  private def instance   = s"$instanceId-$suffix"

  /** A task tagged with the subject, assigned to an instance that is working on it. */
  private def taggedTaskAtWork(named: String): Unit =
    // The instance holds the task in a tool, so it is working when the erasure arrives.
    Answerer.gate.set(CountDownLatch(1))
    model.expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-erasure")))
    taskId = client.tasks
      .create(Tasks.answer, "Why was the card of Ada Byron declined?")
      .withSubject(subject(named))
      .create()
    client.forAutonomousAgent(Answerer)(instance).assign(taskId): Unit
    kit.eventually("the task in progress")(
      Option.when(client.forTask(taskId).get().status == TaskStatus.InProgress)(())
    )

  Given("an agent instance of {string} started with the data subject {string}") {
    (_: String, named: String) => taggedTaskAtWork(named)
  }
  Given("a task of {string} started with the data subject {string}") { (_: String, named: String) =>
    taggedTaskAtWork(named)
  }
  When("{string} is erased") { (named: String) =>
    val erasing = Thread(() => kit.erase(subject(named)): Unit)
    erasing.start()
    // The tool the instance waits in is let go once the instance is told to stop.
    kit.eventually("the instance told to stop", 30.seconds)(
      Option.when(client.forTask(taskId).get().status == TaskStatus.Cancelled)(())
    )
    Answerer.gate.get().countDown()
    erasing.join(60000)
  }
  Then("its instructions, its task inputs and its results read as erased") { () =>
    val record = client.forTask(taskId).get()
    assert(record.erased, record.toString)
    assertEquals(record.instructions, "")
    assertEquals(record.result, None)
    kit.assertNoPersonalValue("Ada Byron")
  }
  Then("the agent instance is terminated") { () =>
    kit.eventually("the instance terminated")(
      Option.when(client.forAutonomousAgent(Answerer)(instance).state().terminated)(())
    )
  }

  // ── An untagged session ──

  private var before: SessionHistory = null

  Given("a session of {string} started with no data subject, with turns in it") { (_: String) =>
    turn(None, "what is the weather", "Sunny.")
    before = history
  }
  Then("the session is unchanged") { () =>
    assertEquals(history.messages, before.messages)
    assert(!history.erased)
  }
  Then("the documentation says that a session started with no data subject cannot be erased") { () =>
    assert(page.contains("A session started with no data subject cannot be erased"), "erasure.md")
  }
  Then("the documentation says that what a session sent to its model is beyond an erasure") { () =>
    assert(page.contains("beyond the installation and beyond an erasure"), "erasure.md")
  }

  private def page: String =
    Files.readString(Path.of("../../docs/platform/erasure.md"))
