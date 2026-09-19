package planner.application

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import planner.domain.AgentSelection

/** Named once so the selector, the workflow and the agents cannot disagree. */
object Specialist:
  val Weather  = "weather"
  val Activity = "activity"
  val Budget   = "budget"

  val All: List[String] = List(Weather, Activity, Budget)

// The selector's structured reply. Top-level givens, because `thenReplyAs` is called
// from inside an agent class, which does not see a companion object's givens.
given JsonValueCodec[AgentSelection] = Codecs.make[AgentSelection]
given Serializer[AgentSelection]     = Codecs.serializer[AgentSelection]("agent-selection")

/**
 * Decides which specialists a request actually needs.
 *
 * This is what makes the orchestration *dynamic*: the workflow does not hard-code which agents to
 * consult, it asks. A question about rain consults the weather specialist; a question about cost
 * consults the budget one; the workflow is unchanged either way.
 */
final class SelectorAgent extends Agent:

  def select(request: String): Effect[AgentSelection] =
    if request.isBlank then effects.error("nothing to plan", ErrorCode.BadRequest)
    else
      effects
        .systemMessage(
          s"""You route planning requests to specialists.
             |Available specialists: ${Specialist.All.mkString(", ")}.
             |Reply with JSON: {"specialists":["..."],"reason":"..."}.
             |Choose only the specialists the request genuinely needs.""".stripMargin
        )
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the
        // specialists and summariser share.
        .memory(MemoryProvider.none)
        .thenReplyAs[AgentSelection]

object SelectorAgent extends Agent.Companion[SelectorAgent](ComponentId("selector-agent")):
  def create(context: AgentContext) = new SelectorAgent
  val select                        = command("select")(_.select)

/**
 * Answers weather questions, with a tool.
 *
 * The tool stands in for a forecast API. What matters for the sample is that the model decides to
 * call it and ankka runs it.
 */
final class WeatherAgent extends Agent:

  def consult(destination: String): Effect[String] =
    effects
      .systemMessage(
        "You are a concise weather specialist. Use your tools, then answer in one sentence."
      )
      .userMessage(s"What is the weather like in $destination?")
      .tools(WeatherAgent.forecast)
      .thenReply()

object WeatherAgent extends Agent.Companion[WeatherAgent](ComponentId("weather-agent")):

  override val role: String = Specialist.Weather

  /** A stand-in forecast service, deterministic so the sample behaves the same each run. */
  val forecast = FunctionTool
    .named("get_forecast")
    .describedAs("Returns a short weather forecast for a destination.")
    .param[String]("destination", "The city or region to forecast.")
    .handle { destination =>
      val outlook =
        if destination.toLowerCase.contains("reykjav") then "cold and windy"
        else if destination.toLowerCase.contains("cairo") then "hot and dry"
        else "mild with occasional rain"
      s"$destination: $outlook"
    }

  def create(context: AgentContext) = new WeatherAgent

  val consult = command("consult")(_.consult)

/**
 * Suggests activities, informed by the user's stored preferences.
 *
 * Reads the preferences entity through `componentClient` rather than being handed them, which is
 * the documented way an agent enriches its own context.
 */
final class ActivityAgent extends Agent:

  def consult(request: ActivityAgent.Request): Effect[String] =
    // `componentClient` is inherited from `Agent`; no constructor plumbing needed.
    val preferences = componentClient
      .forKeyValueEntity(EntityId(request.userId))
      .call(PreferencesEntity.get)
      .invoke()

    effects
      .systemMessage(
        "You are a concise activity specialist. Suggest two activities, in one sentence."
      )
      .userMessage(s"What should I do in ${request.destination}?")
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(s"Traveller preferences: ${preferences.summary}")
      .thenReply()

object ActivityAgent extends Agent.Companion[ActivityAgent](ComponentId("activity-agent")):

  override val role: String = Specialist.Activity

  final case class Request(userId: String, destination: String)

  given Serializer[Request] = Codecs.serializer[Request]("activity-request")

  def create(context: AgentContext) = new ActivityAgent

  val consult = command("consult")(_.consult)

/** Answers cost questions. Deliberately tool-free, to vary the mix. */
final class BudgetAgent extends Agent:

  def consult(destination: String): Effect[String] =
    effects
      .systemMessage("You are a concise budget specialist. Answer in one sentence.")
      .userMessage(s"Roughly what should I budget per day in $destination?")
      .thenReply()

object BudgetAgent extends Agent.Companion[BudgetAgent](ComponentId("budget-agent")):
  override val role: String         = Specialist.Budget
  def create(context: AgentContext) = new BudgetAgent
  val consult                       = command("consult")(_.consult)

/**
 * Combines the specialists' answers.
 *
 * Reads the session filtered to the specialists, so it sees their contributions but not the
 * selector's routing chatter. This is why session memory is keyed by conversation rather than by
 * agent: collaboration is the default, and filtering is how an agent narrows it.
 */
final class SummaryAgent extends Agent:

  def summarise(destination: String): Effect[String] =
    effects
      .systemMessage(
        "You write short trip briefs. Combine what the specialists said into two sentences."
      )
      .userMessage(s"Write a brief for a trip to $destination.")
      .memory(
        MemoryProvider.limitedWindow.filtered(
          Specialist.All.foldLeft(MemoryFilter.all)((filter, id) => filter.includeFromAgentId(id))
        )
      )
      .thenReply()

object SummaryAgent extends Agent.Companion[SummaryAgent](ComponentId("summary-agent")):
  override val role: String         = "summary"
  def create(context: AgentContext) = new SummaryAgent
  val summarise                     = command("summarise")(_.summarise)
