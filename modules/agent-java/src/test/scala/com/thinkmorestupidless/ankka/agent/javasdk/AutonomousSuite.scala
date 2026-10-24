package com.thinkmorestupidless.ankka.agent.javasdk

import com.thinkmorestupidless.ankka.agent.{ModelResponse, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.judgment
import com.thinkmorestupidless.ankka.agent.judgment.{Answers, TestJudgmentProvider}
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge
import com.thinkmorestupidless.ankka.agent.javasdk.fixtures.{
  Answerer,
  AutonomousCalls,
  Judge,
  Keeper
}
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder, Modules}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.{JavaFlowSupport, Sink}

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Java autonomous agents, judgments and blueprints, hosted by the Scala agent runtime with a
 * scripted model and a scripted judge: a task whose structured result a rule rejects and then
 * accepts, a type an instance refuses, a single task, notifications, a judgment of each kind, a
 * judged guardrail, and a blueprint composed in Java and run through a call step.
 */
class AutonomousSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private val judge = TestJudgmentProvider()

  private def module =
    Agents
      .module()
      .withModelProvider(AgentBridge.provider(model))
      .withJudgments(AgentBridge.judgmentProvider(judge))
      .withBlueprints(Keeper.registry)

  private lazy val read =
    JavaServiceBuilder.read(
      Vector(classOf[Answerer] -> None, classOf[Judge] -> None),
      Map.empty,
      modules = Vector(Modules.asJava(module))
    )

  private var kit: AnkkaTestKit = null
  private def client            = Clients.of(kit.componentClient, read.refs)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      read.descriptors,
      extensions = read.platformExtensionsWith(ProjectionRuntime(), read.timerRuntime)
    )
  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()
    Answerer.LOOKED_UP.clear()
    Keeper.KEPT.clear()

  private def scala[T](q: ChoiceQuestion[T]): judgment.ChoiceQuestion[T] =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.ChoiceQuestion[T]]
  private def scala(q: ScoreQuestion): judgment.ScoreQuestion =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.ScoreQuestion]
  private def scala(q: YesNoQuestion): judgment.YesNoQuestion =
    AgentBridge.scalaQuestion(q).asInstanceOf[judgment.YesNoQuestion]

  test("a task's structured result is checked by its rule: rejected, then accepted") {
    model
      .expectToolCall("lookup", com.thinkmorestupidless.ankka.agent.Json.obj())
      .expectCompleteTaskJson("""{"text":"42","sources":[]}""")
      .expectCompleteTaskJson("""{"text":"42","sources":["the guide"]}""")
    val id       = AutonomousCalls.create(client, Answerer.ANSWER, "q-1", "What is the answer?")
    val assigned = AutonomousCalls.assign(client, "a-1", id)
    assertEquals(assigned.accepted.asScala.toList, List(id))
    val done = AutonomousCalls.await(client, Answerer.ANSWER, id)
    assertEquals(done.status, "completed", done.toString)
    assert(done.ended)
    assertEquals(done.result.get, Answerer.Answer("42", java.util.List.of("the guide")))
    assertEquals(done.instanceId.get, "a-1")
    assertEquals(Answerer.LOOKED_UP.asScala.toList, List(id))
    val stored = AutonomousCalls.get(client, id)
    assert(stored.result.get.contains("the guide"), stored.toString)
    val rejected = model.requests.map(_.toString).exists(_.contains("cite at least one source"))
    assert(rejected, "the model was not told why its result was rejected")
  }

  test("a task of a type the agent does not accept is refused, not queued") {
    val id      = AutonomousCalls.create(client, Answerer.POEM, "poem-1", "A poem about rain")
    val refused = intercept[CommandException](AutonomousCalls.assign(client, "a-2", id))
    assertEquals(refused.problem.status, 400, refused.problem.toString)
    assert(refused.getMessage.contains("does not accept"), refused.getMessage)
    assertEquals(AutonomousCalls.get(client, id).status, "pending")
    assertEquals(AutonomousCalls.state(client, "a-2").queued.asScala.toList, Nil)
  }

  test("a single task runs on an instance the platform names, and its result is text") {
    model.expectCompleteTaskText("rain falls")
    val id   = AutonomousCalls.summarise(client, "It rained all day, and then it rained more.")
    val done = AutonomousCalls.await(client, Answerer.SUMMARY, id)
    assertEquals(done.status, "completed", done.toString)
    assertEquals(done.result.get, "rain falls")
  }

  test("an instance's notifications reach a Java subscriber as they happen") {
    given org.apache.pekko.stream.Materializer = SystemMaterializer(kit.service.system).materializer
    val seen                                   = JavaFlowSupport.Source
      .fromPublisher(AutonomousCalls.notifications(client, "a-3"))
      .takeWhile(_.kind != "TaskCompleted", inclusive = true)
      .runWith(Sink.seq)
    model.expectCompleteTaskText("brief")
    val id = AutonomousCalls.create(client, Answerer.SUMMARY, "s-3", "Summarise this.")
    AutonomousCalls.assign(client, "a-3", id): Unit
    val kinds = Await.result(seen, 1.minute)
    assert(kinds.map(_.kind).contains("TaskAssigned"), kinds.toString)
    val completed = kinds.last
    assertEquals(completed.kind, "TaskCompleted")
    assertEquals(completed.instanceId, "a-3")
    assertEquals(completed.taskId.get, id)
    assert(completed.json.contains("TaskCompleted"), completed.json)
  }

  test("a judgment answers a choice, a score and a yes/no, read through the Java questions") {
    judge.expect(
      Answers.choice(scala(Judge.TONE), Judge.Tone.RUDE, 0.8),
      Answers.score(scala(Judge.QUALITY), 2.0),
      Answers.yesNo(scala(Judge.ABUSIVE), 0.9)
    )
    val verdict = AutonomousCalls.verdict(client, "j-1", "You call this writing?")
    assertEquals(verdict, Judge.Verdict("RUDE", 2, true, "test-judge"))
    assertEquals(model.callCount, 0)
  }

  test("a handler replies with the judgment itself, and the caller reads every answer") {
    judge.expect(
      Answers.choice(scala(Judge.TONE), Judge.Tone.POLITE),
      Answers.score(scala(Judge.QUALITY), 1.0),
      Answers.yesNo(scala(Judge.ABUSIVE), 0.05)
    )
    val judged = AutonomousCalls.judge(client, "j-2", "Thank you kindly.")
    assertEquals(judged.answer(Judge.TONE).choice, Judge.Tone.POLITE)
    assertEquals(judged.answer(Judge.QUALITY).level, 1)
    assertEquals(judged.answer(Judge.ABUSIVE).probability, 0.05)
    assert(judged.contains(Judge.ABUSIVE))
  }

  test("a judged guardrail refuses before the model is asked, and allows what it may") {
    judge.expect(Answers.yesNo(scala(Judge.ABUSIVE), 0.95))
    val refused = intercept[CommandException](AutonomousCalls.guarded(client, "g-1", "you fool"))
    assertEquals(refused.problem.status, 403, refused.problem.toString)
    assertEquals(model.callCount, 0)

    judge.expect(Answers.yesNo(scala(Judge.ABUSIVE), 0.05))
    model.expectText("hello to you too")
    assertEquals(AutonomousCalls.guarded(client, "g-2", "hello"), "hello to you too")
  }

  test("a blueprint composed in Java runs, its call step given what it reads") {
    model.whenUserAsks("Step 'titles'")(ModelResponse("""["one","two"]""")): Unit
    val run = AutonomousCalls.keep(client, "keep-1", "rain")
    assertEquals(run.status, "completed", run.toString)
    assertEquals(run.stepNamed("keep").get.result.get, "2")
    assertEquals(run.blueprint, "keeper")
    assertEquals(AutonomousCalls.keeperVersions(client), 1)
    val kept = Keeper.KEPT.asScala.toList
    assertEquals(kept.size, 1, kept.toString)
    assert(kept.head.startsWith("keeper/keep:") && kept.head.contains("one"), kept.head)
  }
