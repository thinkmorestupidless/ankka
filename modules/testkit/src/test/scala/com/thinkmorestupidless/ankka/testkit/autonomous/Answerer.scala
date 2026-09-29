package com.thinkmorestupidless.ankka.testkit.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.DurationInt

/** The fixture the whole-service suites drive: an answerer with two tools and a rule. */
final case class Answer(answer: String, sources: List[String])

object Answer:
  given JsonValueCodec[Answer] = Codecs.make
  given JsonSchema[Answer]     = JsonSchema.derived

object Tasks:
  val answer: TaskType[Answer] = Task
    .named("answer")
    .describedAs("Answer a question, citing what you looked up")
    .resultConformsTo[Answer]
    .rule("cites-sources")(a =>
      if a.sources.isEmpty then TaskRule.Rejected("sources must not be empty")
      else TaskRule.Accepted
    )

  val summary: TaskType[String] = Task.named("summary").describedAs("Summarise something")

  /** Two rules, checked in order: the first to refuse is the one reported. */
  val strict: TaskType[Answer] = Task
    .named("strict")
    .describedAs("Answer, with something to say and somewhere it came from")
    .resultConformsTo[Answer]
    .rule("says-something")(a =>
      if a.answer.isEmpty then TaskRule.Rejected("the answer must not be empty")
      else TaskRule.Accepted
    )
    .rule("cites-sources")(a =>
      if a.sources.isEmpty then TaskRule.Rejected("sources must not be empty")
      else TaskRule.Accepted
    )

  /** A rule that throws while `ruleFaults` is above zero, then accepts. */
  val flaky: TaskType[Answer] = Task
    .named("flaky")
    .describedAs("Answer, checked by something unreliable")
    .resultConformsTo[Answer]
    .rule("unreliable") { _ =>
      Answerer.ruleChecks.incrementAndGet(): Unit
      if Answerer.ruleFaults.getAndUpdate(n => (n - 1).max(0)) > 0 then
        throw IllegalStateException("the checker is down")
      TaskRule.Accepted
    }

  /** A type the answerer does not accept. */
  val review: TaskType[String] = Task.named("review").describedAs("Review something")

final class Answerer(context: AutonomousAgentContext) extends AutonomousAgent(context):
  override def tools: Seq[FunctionTool] = Seq(
    FunctionTool
      .named("lookup")
      .describedAs("Looks a topic up")
      .param[String]("topic", "what to look up")
      .handle { (topic: String) =>
        Answerer.calls.add(s"lookup($topic)"): Unit
        // A topic starting "hold" waits for the test to open the gate: a tool mid-call.
        if topic.startsWith("hold") then Answerer.gate.get().await()
        if topic == "boom" then throw IllegalStateException("the lookup service is down")
        s"$topic is well documented"
      },
    FunctionTool
      .named("count")
      .describedAs("Counts things")
      .handle { () =>
        Answerer.calls.add("count"): Unit
        "3 items"
      }
  )

object Answerer extends AutonomousAgent.Companion[Answerer](ComponentId("answerer")):
  def create(context: AutonomousAgentContext) = new Answerer(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Answers questions")
      .instructions("Be brief.")
      .guardrails(Guardrail.forbidding("no-secrets", "sk-".r))
      .capability(TaskAcceptance.of(Tasks.answer).maxIterationsPerTask(5))
      .capability(TaskAcceptance.of(Tasks.summary).maxIterationsPerTask(3))
      .capability(TaskAcceptance.of(Tasks.strict).maxIterationsPerTask(3))
      .capability(TaskAcceptance.of(Tasks.flaky).maxIterationsPerTask(3))
      .settings(
        AutonomousAgentSettings(idlePassivationAfter = 3.seconds, dependencyPoll = 200.millis)
      )

  /** Tool invocations, in order. */
  val calls: CopyOnWriteArrayList[String] = CopyOnWriteArrayList()

  /** How many times `flaky`'s rule has run, and how many more of them throw. */
  val ruleChecks: java.util.concurrent.atomic.AtomicInteger =
    java.util.concurrent.atomic.AtomicInteger(0)
  val ruleFaults: java.util.concurrent.atomic.AtomicInteger =
    java.util.concurrent.atomic.AtomicInteger(0)

  /** Holds a `lookup` of a topic starting "hold" mid-call. Open unless a test closes it. */
  val gate: java.util.concurrent.atomic.AtomicReference[java.util.concurrent.CountDownLatch] =
    java.util.concurrent.atomic.AtomicReference(java.util.concurrent.CountDownLatch(0))
