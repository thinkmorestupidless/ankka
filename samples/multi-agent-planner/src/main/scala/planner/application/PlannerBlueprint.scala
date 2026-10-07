package planner.application

import com.thinkmorestupidless.ankka.agent.FunctionTool
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/**
 * The planner's three steps as a blueprint, beside the workflow that does the same by hand: select
 * as an ask whose result shape lists the specialists it may name, consult as a gather chosen by
 * what select named, summarise as an ask over the contributions. No orchestration code: the
 * platform runs the steps, keeps each result, and resumes after a crash.
 *
 * What the workflow does with a selection that names nothing usable — fall back to the activity
 * specialist — is a condition, and a blueprint has none: a selector that names an unknown
 * specialist is refused by the shape and asked again.
 */
object PlannerBlueprint:

  /** The traveller's stored preferences, which the activity specialist reads itself. */
  def preferences(client: ComponentClient): FunctionTool =
    FunctionTool
      .named("get_preferences")
      .describedAs("The traveller's stored preferences: likes, dislikes and daily budget.")
      .param[String]("userId", "The traveller.")
      .handle { (userId: String) =>
        client.forKeyValueEntity(EntityId(userId)).call(PreferencesEntity.get).invoke().summary
      }

  val blueprint: Blueprint =
    Blueprint("planner")
      .input(Shape.obj("userId" -> Shape.string, "destination" -> Shape.string))
      .worker(
        Worker("selector")
          .instructions(
            s"You route planning requests to specialists. Available specialists: ${Specialist.All.mkString(", ")}. " +
              "Choose only the specialists the request genuinely needs."
          )
          // Two model calls: one to choose, one more should the shape refuse the choice.
          .budget(2)
      )
      .worker(
        Worker("weather")
          .instructions(
            "You are a concise weather specialist. Use your tool, then answer in one sentence."
          )
          .tools("get_forecast")
          .budget(2)
      )
      .worker(
        Worker("activity")
          .instructions(
            "You are a concise activity specialist. Read the traveller's preferences with your tool, then suggest two activities in one sentence."
          )
          .tools("get_preferences")
          .budget(2)
      )
      .worker(
        Worker("budget")
          .instructions("You are a concise budget specialist. Answer in one sentence.")
          .budget(1)
      )
      .worker(
        Worker("summary")
          .instructions(
            "You combine the specialists' contributions into one short brief for the traveller."
          )
          .budget(1)
      )
      .step(
        Step("select")
          .ask("selector")
          .reads("input")
          .result(
            Shape.obj(
              "specialists" -> Shape.arr(Shape.enumOf(Specialist.All*)),
              "reason"      -> Shape.string
            )
          )
      )
      .step(
        Step("consult")
          .gather(Specialist.All, chosenBy = "select.specialists")
          .reads("input")
          .result(Shape.arr(Shape.obj("worker" -> Shape.string, "result" -> Shape.string)))
      )
      .step(Step("summarise").ask("summary").reads("input", "consult").result(Shape.string))

  /** What the blueprint names: the two tools, and the blueprint itself. */
  def registry(context: BlueprintContext): BlueprintRegistry =
    BlueprintRegistry.empty
      .tools(WeatherAgent.forecast, preferences(context.componentClient))
      .carrying(blueprint)
