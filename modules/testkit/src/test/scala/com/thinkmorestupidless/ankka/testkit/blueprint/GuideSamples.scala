package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.judgment.{Question, YesNoQuestion}
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Json}

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The blueprint the guide shows step by step: an ask, a work, a judge, a critique and a call step,
 * in one. `GuideSamplesSuite` runs it, so what the guide shows is what the platform does.
 */
object GuideSamples:

  val noted = ConcurrentLinkedQueue[String]()

  // docs:start tools
  /** A tool a worker may name; it calls an entity in a real service. */
  val note: FunctionTool = FunctionTool
    .named("note")
    .describedAs("Keeps a note for the reader.")
    .param[String]("text", "What to note.")
    .handle { (text: String) =>
      noted.add(text): Unit
      "noted"
    }

  /** A handler a call step runs: code with the run's durability, given what the step reads. */
  val archive: BlueprintHandler = BlueprintHandler("archive") { (run, input) =>
    Json.obj("filed" -> Json.bool(input("polish").isDefined), "run" -> Json.str(run.runId))
  }

  /** A judgment question a judge step asks, and a verdict a critique step may draft until. */
  val onTopic: YesNoQuestion = Question.yesNo("on-topic", "Is the draft about the topic asked for?")
  // docs:end tools

  // docs:start blueprint
  val text: Shape = Shape.obj("text" -> Shape.string)

  val brief: Blueprint = Blueprint("brief")
    .input(Shape.obj("topic" -> Shape.string))
    // Workers are data: instructions, tools by name, a model by name, and a budget of model calls.
    .worker(Worker("writer").instructions("You write briefly.").tools("note").budget(4))
    .worker(
      Worker("researcher")
        .instructions("You research until you have a draft.")
        .tools("note")
        .budget(6)
    )
    .worker(Worker("critic").instructions("You pass a draft only when it is on topic.").budget(2))
    // An ask step: one turn of a worker, its answer checked against the step's shape.
    .step(
      Step("outline")
        .ask("writer")
        .reads("input")
        .result(Shape.obj("points" -> Shape.arr(Shape.string)))
    )
    // A work step: an autonomous task the worker iterates until it completes it, within its budget.
    .step(Step("draft").work("researcher").reads("outline").result(text))
    // A judge step: a judgment's answers to questions about what the step reads.
    .step(Step("check").judge("on-topic").reads("draft").result(Shape.any))
    // A critique step: the writer drafts until the critic's verdict passes, within two rounds.
    .step(
      Step("polish")
        .critique("writer", Verdict.critic("critic"), rounds = 2)
        .reads("draft", "check")
        .result(text)
    )
    // A call step: a handler the service registered, with what the step reads.
    .step(
      Step("file")
        .call("archive")
        .reads("polish")
        .result(Shape.obj("filed" -> Shape.boolean, "run" -> Shape.string))
    )
    .runBudget(20)
  // docs:end blueprint

  // docs:start registry
  /**
   * What the service's blueprints may name. A tool that needs the service is built from its
   * context.
   */
  val registry: BlueprintRegistry =
    BlueprintRegistry.empty
      .tools(note)
      .handlers(archive)
      .questions(onTopic)
      .carrying(brief) // registered when the service starts; a changed blueprint is a new version
  // docs:end registry
