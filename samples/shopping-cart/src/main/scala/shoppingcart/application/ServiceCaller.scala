package shoppingcart.application

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

import scala.concurrent.Future

/**
 * An agent whose one tool calls another service of the project, as this service.
 *
 * The call is made through the context's service client, so on the platform it presents this
 * service's certificate and the other service's ACL admits it, or refuses it, by this service's
 * name. Registered only when `CART_CALLING_AGENT=on` and no model key is set, with [[RelayModel]]
 * as its model: how the platform's cluster suite shows a tool's call admitted by name with no model
 * to pay for.
 */
final class ServiceCaller(context: AgentContext) extends Agent:

  private val callService = FunctionTool
    .named("call_service")
    .describedAs("Asks another service of this project for what is at a path.")
    .param[String]("service", "The service to call.")
    .param[String]("path", "The path to ask for.")
    .handle((service, path) => context.services(service).getText(path))

  def ask(request: String): Effect[String] =
    effects
      .systemMessage("You call services for the person asking.")
      .userMessage(request)
      .tools(callService)
      .memory(MemoryProvider.none)
      .thenReply()

object ServiceCaller extends Agent.Companion[ServiceCaller](ComponentId("service-caller")):
  def create(context: AgentContext) = new ServiceCaller(context)
  val ask                           = command("ask")(_.ask)

/**
 * A model that does exactly one thing, deterministically: told `call <service> <path>`, it asks for
 * `call_service` with those arguments, and once the tool has answered it replies with the tool's
 * result, word for word — or `tool error: <message>` when the tool failed. What an agent's caller
 * sees is therefore what the called service answered, so a test can assert on it.
 */
object RelayModel extends ModelProvider:
  def name: String      = "relay"
  def modelName: String = "relay-1"

  def complete(request: ModelRequest): Future[ModelResponse] =
    Future.successful(request.messages.lastOption match
      case Some(ChatMessage.ToolResults(results)) =>
        ModelResponse(
          results
            .map(r => if r.isError then s"tool error: ${r.content}" else r.content)
            .mkString("\n")
        )
      case _ =>
        val asked = request.messages
          .collect { case ChatMessage.User(content) =>
            content.collect { case MessageContent.Text(t) => t }.mkString
          }
          .lastOption
          .getOrElse("")
        asked.trim.split("\\s+").toList match
          case "call" :: service :: path :: Nil =>
            ModelResponse(
              "",
              Vector(
                ToolCall(
                  "relay-1",
                  "call_service",
                  Json.obj("service" -> Json.str(service), "path" -> Json.str(path))
                )
              ),
              StopReason.ToolUse
            )
          case _ => ModelResponse(s"say 'call <service> <path>', not '$asked'"))
