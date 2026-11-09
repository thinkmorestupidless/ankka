package com.thinkmorestupidless.ankka.kotlin.agent.samples.blueprint

import com.thinkmorestupidless.ankka.agent.javasdk.Blueprint
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintContext
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintHandler
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintRegistry
import com.thinkmorestupidless.ankka.agent.javasdk.FunctionTool
import com.thinkmorestupidless.ankka.agent.javasdk.Question
import com.thinkmorestupidless.ankka.agent.javasdk.Schedule
import com.thinkmorestupidless.ankka.agent.javasdk.Shape
import com.thinkmorestupidless.ankka.agent.javasdk.Step
import com.thinkmorestupidless.ankka.agent.javasdk.Verdict
import com.thinkmorestupidless.ankka.agent.javasdk.Worker
import com.thinkmorestupidless.ankka.agent.javasdk.YesNoQuestion
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.kotlin.agent.samples.planner.PreferencesEntity
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The blueprint the guide shows step by step: an ask, a work, a judge, a critique and a call step,
 * in one. `GuideSamplesTest` registers it, so what the guide shows is what the platform checks.
 */
object GuideSamples {

    val noted = ConcurrentLinkedQueue<String>()

    // docs:start tools
    data class Note(@Describe("What to note.") val text: String)

    /** A tool a worker may name; it calls an entity in a real service. */
    val note: FunctionTool =
        FunctionTool.of("note", "Keeps a note for the reader.", Note::class.java) {
            noted.add(it.text)
            "noted"
        }

    /** A handler a call step runs: code with the run's durability, given what the step reads, as JSON. */
    val archive: BlueprintHandler =
        BlueprintHandler.of("archive") { run, input -> """{"filed":${"\"polish\"" in input},"run":"${run.runId()}"}""" }

    /** A judgment question a judge step asks, and a verdict a critique step may draft until. */
    val onTopic: YesNoQuestion = Question.yesNo("on-topic", "Is the draft about the topic asked for?")
    // docs:end tools

    // docs:start blueprint
    val text: Shape = Shape.obj(mapOf("text" to Shape.string()))

    val brief: Blueprint =
        Blueprint.named("brief")
            .input(Shape.obj(mapOf("topic" to Shape.string())))
            // Workers are data: instructions, tools by name, a model by name, and a budget of model calls.
            .worker(Worker.named("writer").instructions("You write briefly.").tools("note").budget(4))
            .worker(Worker.named("researcher").instructions("You research until you have a draft.").tools("note").budget(6))
            .worker(Worker.named("critic").instructions("You pass a draft only when it is on topic.").budget(2))
            // An ask step: one turn of a worker, its answer checked against the step's shape.
            .step(Step.named("outline").ask("writer").reads("input").result(Shape.obj(mapOf("points" to Shape.arr(Shape.string())))))
            // A work step: an autonomous task the worker iterates until it completes it, within its budget.
            .step(Step.named("draft").work("researcher").reads("outline").result(text))
            // A judge step: a judgment's answers to questions about what the step reads.
            .step(Step.named("check").judge("on-topic").reads("draft").result(Shape.any()))
            // A critique step: the writer drafts until the critic's verdict passes, within two rounds.
            .step(Step.named("polish").critique("writer", Verdict.critic("critic"), 2).reads("draft", "check").result(text))
            // A call step: a handler the service registered, with what the step reads.
            .step(Step.named("file").call("archive").reads("polish").result(Shape.obj(mapOf("filed" to Shape.bool(), "run" to Shape.string()))))
            .runBudget(20)
    // docs:end blueprint

    // docs:start combinations
    val dig: Step = Step.named("dig").work("researcher").each("papers.papers", 4, false).result(Shape.arr(text))
    val views: Step =
        Step.named("views").ask("reviewer").times(3).reads("draft").result(Shape.arr(Shape.obj(mapOf("n" to Shape.integer(), "result" to text))))
    // docs:end combinations

    // docs:start registry
    /** What the service's blueprints may name. */
    val registry: BlueprintRegistry =
        BlueprintRegistry.empty()
            .tools(note)
            .handlers(archive)
            .questions(onTopic)
            .carrying(brief) // registered when the service starts; a changed blueprint is a new version
    // docs:end registry

    // docs:start context-registry
    data class Traveller(@Describe("The traveller.") val userId: String)

    /**
     * What the service's blueprints may name, when a tool needs the service: the registry is built
     * from its context, which gives the component client.
     */
    fun registry(context: BlueprintContext): BlueprintRegistry {
        val preferences =
            FunctionTool.of("get_preferences", "The traveller's stored preferences.", Traveller::class.java) {
                context.componentClient().forKeyValueEntity(it.userId).call(PreferencesEntity::get).invoke().summary
            }
        return BlueprintRegistry.empty().tools(note, preferences).handlers(archive).questions(onTopic).carrying(brief)
    }
    // docs:end context-registry

    // docs:start schedule
    /** A scheduled run's input: the period from the previous due time to this one. */
    val period: Shape = Shape.obj(mapOf("from" to Shape.string(), "to" to Shape.string(), "dueTimes" to Shape.arr(Shape.string())))

    val digest: Blueprint =
        Blueprint.named("digest")
            .input(period)
            .schedule(Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London")).perMissedPeriod())
    // docs:end schedule
}
