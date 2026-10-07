package digest.application

import com.thinkmorestupidless.ankka.agent.blueprint.{
  Blueprint,
  BlueprintContext,
  BlueprintRegistry
}
import com.thinkmorestupidless.ankka.agent.judgment.{Question, YesNoQuestion}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock

/**
 * The two blueprints, carried as JSON beside the code and registered when the service starts, and
 * what they name. Neither starts the other: the watch keeps entries, the digest reads the ones its
 * period holds.
 */
object Blueprints:

  /** The verdict a digest's script is drafted until: the judgment a critique step asks. */
  val namesPaper: YesNoQuestion = Question.yesNo(
    "names-a-paper",
    "Does every statement in the script name a paper by its identifier, or say that no papers were found in the period?"
  )

  val watch: Blueprint  = load("watch")
  val digest: Blueprint = load("digest")

  private def load(name: String): Blueprint =
    val stream = Option(getClass.getResourceAsStream(s"/blueprints/$name.json"))
      .getOrElse(throw IllegalStateException(s"no resource blueprints/$name.json"))
    val text =
      try String(stream.readAllBytes(), UTF_8)
      finally stream.close()
    Blueprint
      .fromJson(text)
      .fold(e => throw IllegalStateException(s"blueprints/$name.json does not read: $e"), identity)

  // docs:start registry
  /**
   * What the service's blueprints may name: the tools over its records, the question, and the two.
   */
  def registry(
      context: BlueprintContext,
      sources: Vector[Source],
      clock: Clock
  ): BlueprintRegistry =
    val tools = Tools(context.componentClient, context.viewClient, clock)
    BlueprintRegistry.empty.tools(tools.all(sources)*).questions(namesPaper).carrying(watch, digest)
  // docs:end registry
