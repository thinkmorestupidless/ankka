package shoppingcart.application

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

import java.util.concurrent.atomic.AtomicInteger

/**
 * An agent that answers questions about stock with one tool, on an installation model.
 *
 * Registered only when the service names a model provider `local` whose preset is `installation`:
 * how the platform's cluster suite shows an agent on the installation's model server calling a
 * tool, with no key and no address. Its tool counts its runs, so a caller can tell that it ran.
 */
final class StockHelper extends Agent:

  private val lookup = FunctionTool
    .named("lookup")
    .describedAs("Says whether an item is in stock.")
    .param[String]("item", "The item to look up.")
    .handle { item =>
      StockHelper.lookups.incrementAndGet(): Unit
      s"$item: in stock, 12 left"
    }

  def ask(question: String): Effect[String] =
    effects
      .systemMessage(
        "You answer questions about stock. Always call the lookup tool before you answer."
      )
      .userMessage(question)
      .tools(lookup)
      .thenReply()

object StockHelper extends Agent.Companion[StockHelper](ComponentId("helper")):
  def create(context: AgentContext) = new StockHelper
  val ask                           = command("ask")(_.ask)

  /** How many times the tool has run in this instance. */
  val lookups: AtomicInteger = AtomicInteger()
